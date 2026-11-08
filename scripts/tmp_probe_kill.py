# Temporary D-124 probe: one run, then dump the kill reason from the device.
import glob, os, re, shutil, subprocess, time
ROOT = r"C:\Users\Banespire\AndroidStudioProjects\Mine"
RESULTS = os.path.join(ROOT, "app", "build", "outputs", "androidTest-results", "connected", "debug")
OUT = os.path.join(ROOT, "docs", "agent", "evidence", "d124-probe")
if os.path.isdir(OUT):
    shutil.rmtree(OUT)
os.makedirs(OUT)
subprocess.run(["adb", "logcat", "-c"], check=False)
t0 = time.time()
proc = subprocess.run([os.path.join(ROOT, "gradlew.bat"), "--no-daemon", "-q",
    ":app:connectedDebugAndroidTest",
    "-Pandroid.testInstrumentationRunnerArguments.class=com.anchor.mine.alarms.AlarmSelfTest"],
    cwd=ROOT, capture_output=True, text=True, errors="replace")
print("gradle exit=%d wall=%.0fs" % (proc.returncode, time.time() - t0))
for name in ("test-result.pb", "test-result-exit-code.txt"):
    src = os.path.join(RESULTS, name)
    if os.path.exists(src):
        shutil.copy2(src, os.path.join(OUT, name))
for f in glob.glob(os.path.join(RESULTS, "TEST-*.xml")):
    shutil.copy2(f, os.path.join(OUT, os.path.basename(f)))
dump = subprocess.run(["adb", "logcat", "-d", "-v", "time"], capture_output=True, text=True, errors="replace").stdout
open(os.path.join(OUT, "logcat.txt"), "w", encoding="utf-8").write(dump)
for line in dump.splitlines():
    if re.search(r"TestRunner|am_kill|Killing|lowmemorykiller|lmkd|ANR in|signal 9|am_proc_died|Process com\.anchor", line):
        print(line[:220])
