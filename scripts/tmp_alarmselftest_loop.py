# SPDX-License-Identifier: GPL-3.0-or-later
# Temporary D-124 harness (v2): loop AlarmSelfTest N times, archive every artifact before the
# next run clears the results dir, and decide pass/fail from the JUnit XML with the proto as a
# tie-breaker (same reconciliation as gradle/connected-results-gate.py). Not part of the build.
#
# v1 archived whatever sat in the results dir with no staleness guard, so a run whose UTP
# artifacts were empty silently inherited the previous run's logcat and looked like a
# different failure. v2 records the artifact mtimes and the XML timestamp for every run so a
# stale archive is visible instead of invisible.
import importlib.util
import json
import os
import shutil
import subprocess
import sys
import time

ROOT = r"C:\Users\Banespire\AndroidStudioProjects\Mine"
RESULTS = os.path.join(ROOT, "app", "build", "outputs", "androidTest-results", "connected", "debug")
DEVICE_DIR = os.path.join(RESULTS, "moto g73 5G - 14")
OUT = os.path.join(ROOT, "docs", "agent", "evidence", "d124-loop-v2")
RUNS = int(sys.argv[1]) if len(sys.argv) > 1 else 20

spec = importlib.util.spec_from_file_location("gate", os.path.join(ROOT, "gradle", "connected-results-gate.py"))
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)

if os.path.isdir(OUT):
    shutil.rmtree(OUT)
os.makedirs(OUT)
summary = []

for run in range(1, RUNS + 1):
    started = time.time()
    proc = subprocess.run(
        [os.path.join(ROOT, "gradlew.bat"), "--no-daemon", "-q",
         ":app:connectedDebugAndroidTest",
         "-Pandroid.testInstrumentationRunnerArguments.class=com.anchor.mine.alarms.AlarmSelfTest"],
        cwd=ROOT, capture_output=True, text=True, errors="replace",
    )
    elapsed = time.time() - started

    dest = os.path.join(OUT, "run%02d" % run)
    os.makedirs(dest, exist_ok=True)
    archived = {}
    for name in ("test-result.pb", "test-result-exit-code.txt"):
        src = os.path.join(RESULTS, name)
        if os.path.exists(src):
            shutil.copy2(src, os.path.join(dest, name))
            archived[name] = round(os.path.getmtime(src) - started, 1)   # <0 => stale
    xmls = [f for f in os.listdir(RESULTS) if f.startswith("TEST-") and f.endswith(".xml")]
    for name in xmls:
        src = os.path.join(RESULTS, name)
        shutil.copy2(src, os.path.join(dest, name))
        archived[name] = round(os.path.getmtime(src) - started, 1)
    if os.path.isdir(DEVICE_DIR):
        shutil.copytree(DEVICE_DIR, os.path.join(dest, "device"), dirs_exist_ok=True)
    with open(os.path.join(dest, "gradle.log"), "w", encoding="utf-8") as h:
        h.write(proc.stdout + "\n--- STDERR ---\n" + proc.stderr)

    # Verdict: same reconciliation rule as the gate.
    xml_counts = {"tests": 0, "failures": 0, "errors": 0, "failing": []}
    for name in xmls:
        total, failures, errors, _skipped, failing = gate.parse_xml(os.path.join(dest, name))
        xml_counts["tests"] += total
        xml_counts["failures"] += failures
        xml_counts["errors"] += errors
        xml_counts["failing"].extend(failing)
    pb_tests = []
    pb = os.path.join(dest, "test-result.pb")
    if os.path.exists(pb):
        pb_tests = gate.parse_proto(pb)
    pb_fail = [t["name"] for t in pb_tests if t["status"] not in gate.NON_FAILURE_STATUSES]
    if xml_counts["tests"] >= len(pb_tests):
        source, tests, fails = "xml", xml_counts["tests"], xml_counts["failing"]
    else:
        source, tests, fails = "proto", len(pb_tests), pb_fail
    stale = any(v < 0 for v in archived.values())

    record = {"run": run, "wallSeconds": round(elapsed, 1), "gradleExit": proc.returncode,
              "source": source, "tests": tests, "failed": fails, "staleArtifacts": stale,
              "artifactAgeAtArchive": archived}
    summary.append(record)
    print("RUN %02d  %-5s tests=%d failed=%s stale=%s wall=%.0fs"
          % (run, source, tests, fails or "-", stale, elapsed), flush=True)
    with open(os.path.join(OUT, "summary.json"), "w", encoding="utf-8") as h:
        json.dump(summary, h, indent=2)

total = len(summary)
with_tests = [r for r in summary if r["tests"] > 0]
failing = [r for r in summary if r["failed"]]
print("\n=== D-124 v2 RESULT ===")
print("runs: %d  with-results: %d  failing-runs: %d  stale-archives: %d"
      % (total, len(with_tests), len(failing), sum(1 for r in summary if r["staleArtifacts"])))
for r in summary:
    if r["tests"] == 0 or r["failed"]:
        print("  run %02d: tests=%d failed=%s" % (r["run"], r["tests"], r["failed"] or "-"))
