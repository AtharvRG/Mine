import os, sys, subprocess

def run():
    gradle = os.path.abspath("gradlew.bat") if os.name == "nt" else "./gradlew"
    cmd = [gradle, ":core:test", "--tests", "com.anchor.core.ui.R12ConcurrencyLifecycleTest", "--no-daemon"]
    print("Running command:", " ".join(cmd))
    res = subprocess.run(cmd, capture_output=True, text=True)
    print("EXIT CODE:", res.returncode)
    print("--- STDOUT ---")
    print(res.stdout)
    print("--- STDERR ---")
    print(res.stderr)
    return res.returncode

if __name__ == "__main__":
    sys.exit(run())
