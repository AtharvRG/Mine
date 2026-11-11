import json, sys, re

try:
    d = json.load(sys.stdin)
except Exception:
    d = {}

# Antigravity hook input contract: d["toolCall"]["args"]["CommandLine"]
# Fallback to Claude Code format if ever called that way: d["tool_input"]["command"]
cmd = ""
if "toolCall" in d:
    cmd = d.get("toolCall", {}).get("args", {}).get("CommandLine", "")
elif "tool_input" in d:
    cmd = d.get("tool_input", {}).get("command", "")

bad = [
    r"\brm\s+-rf\b", r"git\s+push\s+.*--force", r"git\s+reset\s+--hard",
    r"adb\s+(-s\s+\S+\s+)?shell\s+pm\s+clear", r"adb\s+(-s\s+\S+\s+)?uninstall",
    r"adb\s+.*\breboot\b", r"adb\s+.*\bwipe\b", r"git\s+checkout\s+--\s+\.",
    r"\b(?:rd|rmdir)\s+.*[/\\]s\b", r"\bdel\s+.*[/\\]s\b",
    r"\bformat\s+[a-zA-Z]:", r"\bRemove-Item\b.*(?:-Recurse|-r\b)",
]

for p in bad:
    if re.search(p, cmd):
        msg = f"Blocked by guard_bash: '{cmd}' matches {p}. Ask developer for explicit approval."
        # Antigravity hook protocol: return JSON on stdout
        out = {
            "decision": "deny",
            "reason": msg
        }
        print(json.dumps(out))
        sys.exit(0)

# Allow if no dangerous pattern matched
print(json.dumps({"decision": "allow"}))
sys.exit(0)
