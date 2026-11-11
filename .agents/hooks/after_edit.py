import json, sys

try:
    d = json.load(sys.stdin)
except Exception:
    d = {}

# Antigravity hook input contract: d["toolCall"]["args"]["Path"]
# Fallback to Claude Code format: d["tool_input"]["file_path"]
path = ""
if "toolCall" in d:
    path = d.get("toolCall", {}).get("args", {}).get("Path", "")
elif "tool_input" in d:
    path = d.get("tool_input", {}).get("file_path", "")

if path.endswith(".kt") or path.endswith(".kts"):
    try:
        with open(path, encoding="utf-8") as f:
            head = f.read(400)
            f.seek(0)
            full_content = f.read()
    except Exception:
        print("{}")
        sys.exit(0)

    if path.endswith(".kt") and "SPDX-License-Identifier: GPL-3.0-or-later" not in head:
        print(f"{path}: missing SPDX header (// SPDX-License-Identifier: GPL-3.0-or-later / // Copyright (C) 2026 Mine contributors / // Part of Mine. See LICENSE in the repository root.)", file=sys.stderr)
        # Antigravity PostToolUse expects empty JSON on stdout
        print("{}")
        sys.exit(0)

    if "Mine Author" in head:
        print(f"{path}: replace 'Mine Author' with 'Mine contributors'.", file=sys.stderr)
        print("{}")
        sys.exit(0)

    norm_path = path.replace("\\", "/")
    if "core" in norm_path.split("/") and "import android." in full_content:
        print(f"{path}: core module must not import android.*", file=sys.stderr)
        print("{}")
        sys.exit(0)

print("{}")
sys.exit(0)
