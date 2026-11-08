# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 Mine contributors
# Part of Mine. See LICENSE in the repository root.
#
# G2: the gate must be able to say WHY a connected test failed.
#
# The gate's job before this file was only the verdict (PASS/FAIL). A FAIL printed the test's
# name and nothing else, so the one artifact a reader has after a red device run could not
# answer "what did the assertion say?" - the text existed in TEST-*.xml and was thrown away.
# These fixtures pin the message path on every route the gate can take:
#   xml    - the message must reach stdout and gate.json
#   proto  - the message must reach them when the XML is truncated/absent (D-123 fallback)
#   empty  - a failure with no recorded message must say so, not look like a clean failure
#
# Run: python gradle/test_connected_results_gate.py
# Wired into `check` by app/build.gradle.kts (task `connectedGateFixtures`).

import importlib.util
import json
import os
import shutil
import subprocess
import sys
import tempfile

GATE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "connected-results-gate.py")
spec = importlib.util.spec_from_file_location("gate", GATE)
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)

# The exact shape AlarmSelfTest produces when the ALARM tier never fires (AlarmSelfTest.kt:85).
ALARM_MESSAGE = ("ALARM tier did not fire within 20000ms. lastFiredId=null "
                 "nextAlarmClock=null fireTime=1794082453702 stillNextAlarmClock=false "
                 "reminderRowStillPending=true schedulerFiredFlow=null")
ALARM_TRACE = ("java.lang.AssertionError: " + ALARM_MESSAGE + "\n"
               "\tat org.junit.Assert.fail(Assert.java:89)\n"
               "\tat com.anchor.mine.alarms.AlarmSelfTest.testAlarmFires_SetsFlag(AlarmSelfTest.kt:85)\n")

FAILING_XML = (
    '<?xml version="1.0" encoding="UTF-8"?>\n'
    '<testsuites tests="1" failures="1" errors="0" skipped="0" time="20.1">\n'
    '  <testsuite name="com.anchor.mine.alarms.AlarmSelfTest" tests="1" failures="1" errors="0" skipped="0" time="20.1">\n'
    '    <testcase name="testAlarmFires_SetsFlag" classname="com.anchor.mine.alarms.AlarmSelfTest" time="20.1">\n'
    '      <failure>%s</failure>\n'
    '    </testcase>\n'
    '  </testsuite>\n'
    '</testsuites>\n' % ALARM_TRACE.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
)

EMPTY_FAILURE_XML = (
    '<?xml version="1.0" encoding="UTF-8"?>\n'
    '<testsuites tests="1" failures="1" errors="0" skipped="0" time="20.1">\n'
    '  <testsuite name="com.anchor.mine.alarms.AlarmSelfTest" tests="1" failures="1" errors="0" skipped="0" time="20.1">\n'
    '    <testcase name="testGentleReminderFires_SetsFlag" classname="com.anchor.mine.alarms.AlarmSelfTest" time="20.1">\n'
    '      <failure></failure>\n'
    '    </testcase>\n'
    '  </testsuite>\n'
    '</testsuites>\n'
)

EMPTY_XML = ('<?xml version="1.0" encoding="UTF-8"?>\n'
             '<testsuites tests="0" failures="0" errors="0" skipped="0" time="0.000"/>\n')


def varint(n):
    out = bytearray()
    while True:
        b = n & 0x7F
        n >>= 7
        if n:
            out.append(b | 0x80)
        else:
            out.append(b)
            return bytes(out)


def field(num, wire, payload):
    key = varint((num << 3) | wire)
    if wire == 0:
        return key + varint(payload)
    return key + varint(len(payload)) + payload


def make_pb(entries):
    """entries: list of (package, class, method, status, error_or_None)."""
    body = b""
    for pkg, cls, meth, status, error in entries:
        case = field(1, 2, cls.encode()) + field(2, 2, pkg.encode()) + field(3, 2, meth.encode())
        result = field(1, 2, case) + field(2, 0, status)
        if error is not None:
            message, error_type, trace = error
            payload = (field(1, 2, message.encode()) + field(2, 2, error_type.encode())
                       + field(3, 2, trace.encode()))
            result += field(3, 2, payload)
        body += field(3, 2, result)
    return body


RED_PROTO = [
    ("com.anchor.mine.alarms", "AlarmSelfTest", "testAlarmFires_SetsFlag", 2,
     (ALARM_MESSAGE, "java.lang.AssertionError", ALARM_TRACE)),
    ("com.anchor.mine.alarms", "AlarmSelfTest", "testGentleReminderFires_SetsFlag", 3, None),
]


def run_gate(results_dir):
    proc = subprocess.run([sys.executable, GATE, results_dir, os.path.join(results_dir, "gate.json")],
                          capture_output=True, text=True)
    with open(os.path.join(results_dir, "gate.json"), encoding="utf-8") as handle:
        report = json.load(handle)
    return proc, report


CASES = []


def case(fn):
    CASES.append(fn)
    return fn


@case
def xml_failure_message_reaches_stdout_and_report(tmp):
    d = os.path.join(tmp, "xml")
    os.makedirs(d)
    open(os.path.join(d, "TEST-device.xml"), "w", encoding="utf-8").write(FAILING_XML)
    proc, report = run_gate(d)
    assert proc.returncode == 1, "a failing XML must FAIL the gate, got %d" % proc.returncode
    assert "ALARM tier did not fire within 20000ms" in proc.stdout, \
        "G2: the assertion message must reach the gate's stdout; got:\n%s" % proc.stdout
    assert "reminderRowStillPending=true" in proc.stdout, \
        "G2: the message must survive intact, not be truncated to its first line"
    details = report.get("failureDetails") or []
    assert details and any("ALARM tier did not fire" in (x.get("message") or "") for x in details), \
        "G2: gate.json must carry the failure message; got %r" % (details,)


@case
def proto_failure_message_reaches_stdout_and_report(tmp):
    d = os.path.join(tmp, "proto")
    os.makedirs(d)
    # Empty XML + populated proto: the D-123 route where the proto is the only readable copy.
    open(os.path.join(d, "TEST-device.xml"), "w", encoding="utf-8").write(EMPTY_XML)
    open(os.path.join(d, "test-result.pb"), "wb").write(make_pb(RED_PROTO))
    proc, report = run_gate(d)
    assert report["source"] == "proto", "expected the proto route, got %r" % report["source"]
    assert proc.returncode == 1
    assert "ALARM tier did not fire within 20000ms" in proc.stdout, \
        "G2: on the proto route the Error message must reach stdout; got:\n%s" % proc.stdout
    assert "reminderRowStillPending=true" in proc.stdout
    details = report.get("failureDetails") or []
    assert any("ALARM tier did not fire" in (x.get("message") or "") for x in details), \
        "G2: gate.json must carry the proto failure message; got %r" % (details,)


@case
def empty_failure_body_is_reported_as_missing_not_as_blank(tmp):
    d = os.path.join(tmp, "empty")
    os.makedirs(d)
    open(os.path.join(d, "TEST-device.xml"), "w", encoding="utf-8").write(EMPTY_FAILURE_XML)
    proc, report = run_gate(d)
    assert proc.returncode == 1
    assert "no failure message recorded" in proc.stdout, \
        "G2: a failure with no body must be labelled, not printed as nothing; got:\n%s" % proc.stdout
    details = report.get("failureDetails") or []
    assert details and details[0].get("message") in (None, ""), \
        "G2: gate.json must record the absence rather than invent text; got %r" % (details,)


@case
def green_run_reports_no_failure_details(tmp):
    d = os.path.join(tmp, "green")
    os.makedirs(d)
    open(os.path.join(d, "TEST-device.xml"), "w", encoding="utf-8").write(
        FAILING_XML.replace("<failure>", "<system-out>").replace("</failure>", "</system-out>")
        .replace('failures="1"', 'failures="0"'))
    proc, report = run_gate(d)
    assert proc.returncode == 0, "a green run must PASS, got %d" % proc.returncode
    assert report.get("failureDetails") == [], \
        "a green run must carry no failure details; got %r" % (report.get("failureDetails"),)


def main():
    tmp = tempfile.mkdtemp(prefix="gate-g2-")
    failures = 0
    try:
        for fn in CASES:
            sub = os.path.join(tmp, fn.__name__)
            os.makedirs(sub, exist_ok=True)
            try:
                fn(sub)
                print("PASS  %s" % fn.__name__)
            except AssertionError as exc:
                failures += 1
                print("FAIL  %s\n      %s" % (fn.__name__, str(exc).replace("\n", "\n      ")))
    finally:
        shutil.rmtree(tmp, ignore_errors=True)
    print("\nG2 gate fixtures: %d/%d passed" % (len(CASES) - failures, len(CASES)))
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
