import json, sys
d = json.load(sys.stdin)
path = d.get("tool_input", {}).get("file_path", "")
if path.endswith(".kt") or path.endswith(".kts"):
    try:
        head = open(path, encoding="utf-8").read(400)
    except Exception:
        sys.exit(0)
    if path.endswith(".kt") and "SPDX-License-Identifier: GPL-3.0-or-later" not in head:
        print(f"{path}: missing SPDX header (// SPDX-License-Identifier: GPL-3.0-or-later / // Copyright (C) 2026 Mine contributors / // Part of Mine. See LICENSE in the repository root.)", file=sys.stderr)
        sys.exit(2)
    if "Mine Author" in head:
        print(f"{path}: replace 'Mine Author' with 'Mine contributors'.", file=sys.stderr)
        sys.exit(2)
    if "core" in path.replace("\\", "/").split("/") and "import android." in open(path, encoding="utf-8").read():
        print(f"{path}: core module must not import android.*", file=sys.stderr)
        sys.exit(2)
sys.exit(0)
