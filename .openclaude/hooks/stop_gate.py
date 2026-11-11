import json, sys, os, subprocess, glob

repo_root = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
try:
    os.chdir(repo_root)
except Exception:
    pass
d = json.load(sys.stdin)
if d.get("stop_hook_active"):          # prevent infinite loops
    sys.exit(0)
changed = subprocess.run(["git", "status", "--porcelain"], capture_output=True, text=True).stdout
if ".kt" not in changed and "docs/agent" not in changed:
    sys.exit(0)                        # nothing code-related changed
gradle = os.path.abspath("gradlew.bat") if os.name == "nt" else "./gradlew"
r = subprocess.run([gradle, ":core:test", "-q"], shell=(os.name == "nt"), capture_output=True, text=True)
if r.returncode != 0:
    print("Cannot stop: ./gradlew :core:test failed.\n" + (r.stdout + r.stderr)[-3000:], file=sys.stderr)
    sys.exit(2)
latest_kt = max((os.path.getmtime(p) for p in glob.glob("**/*.kt", recursive=True)), default=0)
h = "docs/agent/HANDOFF.md"
if not os.path.exists(h) or os.path.getmtime(h) < latest_kt:
    print("Cannot stop: update docs/agent/STATE.md, TASKS.md and HANDOFF.md (run /handoff) after your last code change.", file=sys.stderr)
    sys.exit(2)
sys.exit(0)
