import json, sys, os, subprocess, glob

repo_root = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
try:
    os.chdir(repo_root)
except Exception:
    pass

try:
    d = json.load(sys.stdin)
except Exception:
    d = {}

# prevent infinite loops or unwanted stop blocks
if d.get("stop_hook_active"):
    print(json.dumps({"decision": "allow"}))
    sys.exit(0)

try:
    changed = subprocess.run(["git", "status", "--porcelain"], capture_output=True, text=True).stdout
except Exception:
    changed = ""

if ".kt" not in changed and "docs/agent" not in changed:
    print(json.dumps({"decision": "allow"}))
    sys.exit(0)

gradle = os.path.abspath("gradlew.bat") if os.name == "nt" else "./gradlew"
try:
    r = subprocess.run([gradle, ":core:test", "-q"], shell=(os.name == "nt"), capture_output=True, text=True)
    if r.returncode != 0:
        err = "Cannot stop: ./gradlew :core:test failed.\n" + (r.stdout + r.stderr)[-1000:]
        print(json.dumps({"decision": "continue", "reason": err}))
        sys.exit(0)
except Exception as e:
    pass

latest_kt = max((os.path.getmtime(p) for p in glob.glob("**/*.kt", recursive=True)), default=0)
h = "docs/agent/HANDOFF.md"
if not os.path.exists(h) or os.path.getmtime(h) < latest_kt:
    msg = "Cannot stop: update docs/agent/STATE.md, TASKS.md and HANDOFF.md (run /handoff) after your last code change."
    print(json.dumps({"decision": "continue", "reason": msg}))
    sys.exit(0)

print(json.dumps({"decision": "allow"}))
sys.exit(0)
