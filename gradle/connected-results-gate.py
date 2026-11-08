# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 Mine contributors
# Part of Mine. See LICENSE in the repository root.
#
# D-123: decide the truth of a connected-test run from UTP's own result artifacts.
#
# Why this exists: on AGP 9.4.0 / UTP 32.4.0 the `connectedDebugAndroidTest` task derives its
# pass/fail from `test-result-exit-code.txt`, which is computed from a JUnit-platform
# Map<String,Boolean> that the android-test-engine never populates on this device. The gate
# therefore reports failure for green runs (exit code "1"), and in some runs writes no exit-code
# file at all. Evidence: docs/agent/work/connected-red-build-rootcause.md.
#
# This script reads what the runner actually recorded:
#   - TEST-*.xml  (JUnit XML, written by XmlTestRunListener)  -> per-case failure bodies
#   - test-result.pb (UTP's merged proto)                     -> tie-breaker, status field 2
# Both are always parsed. The proto wins when it recorded more tests than the XML, because UTP
# on this toolchain writes the XML truncated or empty (`<testsuites tests="0" .../>`) while the
# proto holds every result - trusting XML alone turned green runs into false FAILs.
# The script exits 0 only when at least one test ran and none failed. Anything unreadable is a
# failure: a gate that cannot read its evidence must not claim success.

import glob
import json
import os
import sys

# TestStatus, transcribed verbatim from proto/api/core/test_status.proto inside
# test-result-proto-1.0.1.jar (the schema UTP writes test-result.pb with). Only PASSED,
# IGNORED and SKIPPED are non-failures; everything else - including UNSPECIFIED, STARTED,
# IN_PROGRESS and ERROR - means the run did not record a clean pass, so it fails the gate.
# A first draft of this table guessed these values and was wrong; the enum is authoritative.
NON_FAILURE_STATUSES = {3, 4, 8}
STATUS_NAMES = {0: "UNSPECIFIED", 1: "STARTED", 2: "FAILED", 3: "PASSED", 4: "IGNORED",
                5: "ERROR", 6: "ABORTED", 7: "CANCELLED", 8: "SKIPPED", 9: "IN_PROGRESS"}


def read_varint(buf, pos):
    result = 0
    shift = 0
    while True:
        if pos >= len(buf):
            raise ValueError("truncated varint at offset %d" % pos)
        b = buf[pos]
        pos += 1
        result |= (b & 0x7F) << shift
        if not b & 0x80:
            return result, pos
        shift += 7
        if shift > 63:
            raise ValueError("varint too long at offset %d" % pos)


def iter_fields(buf, start=0, end=None):
    """Yield (field_number, wire_type, value) for a protobuf message body."""
    pos = start
    end = len(buf) if end is None else end
    while pos < end:
        key, pos = read_varint(buf, pos)
        field, wire = key >> 3, key & 7
        if wire == 0:
            value, pos = read_varint(buf, pos)
        elif wire == 1:
            value, pos = buf[pos:pos + 8], pos + 8
        elif wire == 2:
            length, pos = read_varint(buf, pos)
            value, pos = buf[pos:pos + length], pos + length
        elif wire == 5:
            value, pos = buf[pos:pos + 4], pos + 4
        else:
            raise ValueError("unsupported wire type %d for field %d" % (wire, field))
        if pos > end:
            raise ValueError("field %d overruns message" % field)
        yield field, wire, value


def parse_proto(path):
    """Return a list of {name, class, package, status, error} for every result in test-result.pb.

    Layout, from proto/api/core/{test_result,test_case,test_status,error}.proto in
    test-result-proto-1.0.1.jar:
      TestResultProto { repeated TestResult results = 3 }
      TestResult      { TestCase test_case = 1; TestStatus test_status = 2; Error error = 3;
                        repeated Artifact output_artifact = 4; repeated details = 5 }
      TestCase        { string test_class = 1; string test_package = 2; string test_method = 3; ... }
      Error           { string error_message = 1; string error_type = 2; string stack_trace = 3 }
    Verified against this device's artifact: each TestResult carries fields 1 (case), 2 (status)
    and 4 (artifacts), with status 3 = PASSED on a green run. Field 3 is present only on a
    FAILED/ERROR result - that is the only place UTP keeps the assertion text when the XML is
    truncated, which is exactly the run where the text matters most (G2).
    """
    with open(path, "rb") as handle:
        buf = handle.read()
    tests = []
    for field, wire, value in iter_fields(buf):
        if field != 3 or wire != 2:          # field 3 = one TestResult
            continue
        entry = {"name": "", "class": "", "package": "", "status": 0, "error": None}
        for sub_field, sub_wire, sub_value in iter_fields(value):
            if sub_field == 1 and sub_wire == 2:      # TestCase
                for case_field, case_wire, case_value in iter_fields(sub_value):
                    if case_wire != 2:
                        continue
                    text = case_value.decode("utf-8", "replace")
                    if case_field == 1:
                        entry["class"] = text
                    elif case_field == 2:
                        entry["package"] = text
                    elif case_field == 3:
                        entry["name"] = text
            elif sub_field == 2 and sub_wire == 0:    # TestStatus
                entry["status"] = sub_value
            elif sub_field == 3 and sub_wire == 2:    # Error
                error = {"message": "", "type": "", "trace": ""}
                for err_field, err_wire, err_value in iter_fields(sub_value):
                    if err_wire != 2:
                        continue
                    text = err_value.decode("utf-8", "replace")
                    if err_field == 1:
                        error["message"] = text
                    elif err_field == 2:
                        error["type"] = text
                    elif err_field == 3:
                        error["trace"] = text
                entry["error"] = error
        tests.append(entry)
    return tests


def parse_xml(path):
    """Return (total, failures, errors, skipped, failing_names, failure_details) from a JUnit XML.

    Counts come from the <testcase> elements themselves, never from the suite's summary
    attributes: those attributes are written by the same reporter that produced the XML, and a
    gate that trusts a summary it cannot cross-check is exactly the kind of evidence this script
    exists to replace. A <failure>/<error> child is the only thing that makes a case failing.

    G2: the child's body is the only record of what the assertion said, so it is returned with
    the counts. It is stripped of surrounding whitespace and kept whole (multi-line messages
    carry the diagnostic fields AlarmSelfTest prints), and it may legitimately be empty - UTP
    has been observed writing `<failure></failure>`, and that absence must be visible as an
    absence rather than silently printed as a blank line.
    """
    import xml.etree.ElementTree as ET
    root = ET.parse(path).getroot()
    suites = [root] if root.tag == "testsuite" else list(root.iter("testsuite"))
    total = failures = errors = skipped = 0
    failing = []
    details = []
    for suite in suites:
        for case in suite.findall("testcase"):
            total += 1
            label = "%s.%s" % (case.get("classname", "?"), case.get("name", "?"))
            kind = None
            node = case.find("failure")
            if node is not None:
                failures += 1
                kind = "failure"
            else:
                node = case.find("error")
                if node is not None:
                    errors += 1
                    kind = "error"
            if kind is not None:
                failing.append(label)
                details.append({
                    "test": label,
                    "kind": kind,
                    "source": "xml",
                    "message": (node.text or "").strip(),
                })
            elif case.find("skipped") is not None:
                skipped += 1
    return total, failures, errors, skipped, failing, details


def main(argv):
    if len(argv) != 3:
        print("usage: connected-results-gate.py <results-dir> <report-json>", file=sys.stderr)
        return 2
    results_dir, report_path = argv[1], argv[2]

    # UTP writes the merged proto at the top level on this toolchain, but also mirrors it into
    # results_dir/<device>/test-result.pb. Read the top-level copy when present, otherwise the
    # per-device copy - never both, or every test would be counted twice.
    xml_files = sorted(glob.glob(os.path.join(results_dir, "TEST-*.xml")))
    proto_files = sorted(glob.glob(os.path.join(results_dir, "test-result.pb")))
    if not proto_files:
        proto_files = sorted(glob.glob(os.path.join(results_dir, "*", "test-result.pb")))
    exit_code_path = os.path.join(results_dir, "test-result-exit-code.txt")

    report = {"resultsDir": results_dir, "source": None, "tests": 0, "failures": 0,
              "errors": 0, "skipped": 0, "failing": [], "failureDetails": [],
              "exitCodeFile": None,
              "exitCodeFileExists": os.path.exists(exit_code_path), "notes": []}

    # Every artifact is read independently: one unreadable file must not hide the verdict of the
    # others, and it must never be reported as "zero tests" (which reads like an empty run).
    #
    # Both sources are always parsed and reconciled. XML alone is NOT authoritative: UTP on this
    # toolchain writes TEST-*.xml truncated or empty (observed `<testsuites tests="0" .../>`) while
    # test-result.pb holds all three results, so preferring XML unconditionally turns a green run
    # into a false FAIL. The proto is UTP's merged record and is the tie-breaker.
    unreadable = []
    xml_counts = None
    proto_counts = None
    try:
        if xml_files:
            xml_counts = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0, "failing": [],
                          "details": []}
            for path in xml_files:
                try:
                    total, failures, errors, skipped, failing, details = parse_xml(path)
                except Exception as exc:
                    unreadable.append("%s: %s: %s" % (os.path.basename(path), type(exc).__name__, exc))
                    continue
                xml_counts["tests"] += total
                xml_counts["failures"] += failures
                xml_counts["errors"] += errors
                xml_counts["skipped"] += skipped
                xml_counts["failing"].extend(failing)
                xml_counts["details"].extend(details)
        if proto_files:
            proto_counts = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0, "failing": [],
                            "details": []}
            for path in proto_files:
                try:
                    entries = parse_proto(path)
                except Exception as exc:
                    unreadable.append("%s: %s: %s" % (os.path.basename(path), type(exc).__name__, exc))
                    continue
                for entry in entries:
                    proto_counts["tests"] += 1
                    label = ".".join(p for p in (entry["package"], entry["class"], entry["name"]) if p)
                    if entry["status"] in NON_FAILURE_STATUSES:
                        if entry["status"] != 3:          # PASSED is the only clean pass
                            proto_counts["skipped"] += 1
                        continue
                    proto_counts["failures"] += 1
                    proto_counts["failing"].append("%s [%s]" % (label, STATUS_NAMES.get(entry["status"], entry["status"])))
                    # G2: UTP keeps the assertion text in Error.error_message / stack_trace.
                    # The trace repeats the message on its first line, so only fall back to it.
                    error = entry["error"] or {}
                    message = (error.get("message") or "").strip()
                    if not message:
                        message = (error.get("trace") or "").strip()
                    proto_counts["details"].append({
                        "test": label,
                        "kind": STATUS_NAMES.get(entry["status"], str(entry["status"])).lower(),
                        "source": "proto",
                        "message": message,
                    })
        if not xml_files and not proto_files:
            report["notes"].append("no TEST-*.xml and no test-result.pb under %s" % results_dir)
    except Exception as exc:  # unreadable evidence is a failure, never a pass
        unreadable.append("%s: %s" % (type(exc).__name__, exc))

    # Reconcile. The proto wins when the XML recorded fewer tests than the proto did (truncation
    # or an empty suite); otherwise the XML is used, because it carries per-case failure bodies.
    if proto_counts is not None and (xml_counts is None or proto_counts["tests"] > xml_counts["tests"]):
        chosen = proto_counts
        report["source"] = "proto"
        if xml_counts is not None:
            report["notes"].append("xml recorded %d test(s), proto recorded %d - using proto"
                                   % (xml_counts["tests"], proto_counts["tests"]))
    elif xml_counts is not None:
        chosen = xml_counts
        report["source"] = "xml"
    else:
        chosen = None
    if chosen is not None:
        report["tests"] = chosen["tests"]
        report["failures"] = chosen["failures"]
        report["errors"] = chosen["errors"]
        report["skipped"] = chosen["skipped"]
        report["failing"] = chosen["failing"]
        report["failureDetails"] = chosen["details"]
        # G2: a failure whose message could not be read is itself a gap in the evidence. Name
        # it, so a reader knows the silence is UTP's and not the gate's.
        if chosen["details"]:
            report["notes"].append(
                "%d failing test(s) reported no failure message" % sum(
                    1 for d in chosen["details"] if not d["message"]))
        elif chosen["failures"] or chosen["errors"]:
            report["notes"].append(
                "%d failing test(s) with no recorded failure detail - see the per-test logcat "
                "under %s" % (chosen["failures"] + chosen["errors"], results_dir))

    report["unreadable"] = unreadable
    report["notes"].extend("unreadable: " + item for item in unreadable)

    if report["exitCodeFileExists"]:
        with open(exit_code_path, "r", encoding="utf-8", errors="replace") as handle:
            report["exitCodeFile"] = handle.read().strip()

    os.makedirs(os.path.dirname(os.path.abspath(report_path)), exist_ok=True)
    with open(report_path, "w", encoding="utf-8") as handle:
        json.dump(report, handle, indent=2)

    if report["source"] is None:
        print("CONNECTED GATE: FAIL - no readable result artifact in %s" % results_dir)
        for note in report["notes"]:
            print("  " + note)
        return 1
    if unreadable:
        print("CONNECTED GATE: FAIL - %d result artifact(s) could not be read" % len(unreadable))
        for item in unreadable:
            print("  UNREADABLE " + item)
        return 1
    if report["tests"] == 0:
        print("CONNECTED GATE: FAIL - no test was recorded (xml=%s, proto=%s)"
              % ("absent" if xml_counts is None else xml_counts["tests"],
                 "absent" if proto_counts is None else proto_counts["tests"]))
        return 1
    if report["failures"] or report["errors"]:
        print("CONNECTED GATE: FAIL - %d test(s), %d failed, %d errored (source: %s)"
              % (report["tests"], report["failures"], report["errors"], report["source"]))
        # G2: the name alone was all a red run used to say, and the assertion text - the only
        # thing that distinguishes "never fired" from "fired the wrong id" - was discarded.
        details = report["failureDetails"]
        for name in report["failing"]:
            print("  FAILED " + name)
            match = next((d for d in details if name.startswith(d["test"])), None)
            if match is None:
                continue
            if match["message"]:
                for line in match["message"].splitlines():
                    print("    " + line)
            else:
                print("    (no failure message recorded in the %s)" % match["source"])
        return 1

    print("CONNECTED GATE: PASS - %d test(s), 0 failed, %d skipped (source: %s)"
          % (report["tests"], report["skipped"], report["source"]))
    if report["exitCodeFile"] is not None and report["exitCodeFile"] != "0":
        print("  note: UTP exit-code file says '%s' - known AGP 9.4.0 defect, see D-123"
              % report["exitCodeFile"])
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
