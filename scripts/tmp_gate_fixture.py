# Temporary: prove the D-123 gate no longer false-FAILs on empty XML + populated proto.
# Builds three fixtures under a temp dir and runs the real gate against each.
import importlib.util
import os
import shutil
import subprocess
import sys
import tempfile

ROOT = r"C:\Users\Banespire\AndroidStudioProjects\Mine"
GATE = os.path.join(ROOT, "gradle", "connected-results-gate.py")

spec = importlib.util.spec_from_file_location("gate", GATE)
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)


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
    """entries: list of (package, class, method, status_int) -> TestResultProto bytes."""
    body = b""
    for pkg, cls, meth, status in entries:
        case = field(1, 2, cls.encode()) + field(2, 2, pkg.encode()) + field(3, 2, meth.encode())
        result = field(1, 2, case) + field(2, 0, status)
        body += field(3, 2, result)
    return body


EMPTY_XML = ('<?xml version="1.0" encoding="UTF-8"?>\n'
             '<testsuites tests="0" failures="0" errors="0" skipped="0" time="0.000"/>\n')

GREEN = [("com.anchor.mine.alarms", "AlarmSelfTest", "testAlarmFires_SetsFlag", 3),
         ("com.anchor.mine.alarms", "AlarmSelfTest", "testGentleReminderFires_SetsFlag", 3),
         ("com.anchor.mine.alarms", "AlarmSelfTest", "testAlarmClock_NextAlarmClockVerification", 3)]
RED = [GREEN[0], ("com.anchor.mine.alarms", "AlarmSelfTest", "testGentleReminderFires_SetsFlag", 2), GREEN[2]]

CASES = [
    ("empty-xml + green-proto  -> expect PASS", EMPTY_XML, make_pb(GREEN), 0),
    ("empty-xml + red-proto    -> expect FAIL", EMPTY_XML, make_pb(RED), 1),
    ("no-xml + green-proto     -> expect PASS", None, make_pb(GREEN), 0),
    ("empty-xml + empty-proto  -> expect FAIL", EMPTY_XML, make_pb([]), 1),
]

tmp = tempfile.mkdtemp(prefix="gatefix-")
failures = 0
try:
    for label, xml, pb, expect in CASES:
        d = os.path.join(tmp, label.split()[0].replace("+", "_"))
        os.makedirs(d, exist_ok=True)
        if xml is not None:
            open(os.path.join(d, "TEST-device.xml"), "w", encoding="utf-8").write(xml)
        open(os.path.join(d, "test-result.pb"), "wb").write(pb)
        proc = subprocess.run([sys.executable, GATE, d, os.path.join(d, "report.json")],
                              capture_output=True, text=True)
        verdict = "PASS" if proc.returncode == 0 else "FAIL"
        ok = proc.returncode == expect
        failures += 0 if ok else 1
        print("%-40s exit=%d %s  %s" % (label, proc.returncode, "OK" if ok else "WRONG",
                                        proc.stdout.strip().splitlines()[0] if proc.stdout.strip() else ""))
finally:
    shutil.rmtree(tmp, ignore_errors=True)

print("\nfixture failures: %d/%d" % (failures, len(CASES)))
sys.exit(1 if failures else 0)
