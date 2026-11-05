import json, sys, re
d = json.load(sys.stdin)
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
        print(f"Blocked by guard_bash: '{cmd}' matches {p}. Ask the developer for explicit approval.", file=sys.stderr)
        sys.exit(2)
sys.exit(0)
