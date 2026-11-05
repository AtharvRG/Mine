# DO NOT RUN until the developer re-enables it
import subprocess, sys, os, re

MAX = 12
PROMPT = open("scripts/ralph_prompt.txt", encoding="utf-8").read()
gradle = "gradlew.bat" if os.name == "nt" else "./gradlew"

def open_tasks():
    t = open("docs/agent/TASKS.md", encoding="utf-8").read()
    return len(re.findall(r"^\[[ ~]\]", t, re.M))

for i in range(MAX):
    print(f"--- iteration {i+1}/{MAX}")
    subprocess.run(["openclaude", "-p", PROMPT])           # adjust binary/flags to your install
    tests = subprocess.run([gradle, ":core:test", "-q"]).returncode
    left = open_tasks()
    print(f"tests exit={tests}, open tasks={left}")
    if tests != 0:
        print(f"Tests failed with exit code {tests}; stopping loop.")
        sys.exit(tests)
    if left == 0:
        print("DONE")
        sys.exit(0)

print("Max iterations reached; review manually.")
sys.exit(1)
