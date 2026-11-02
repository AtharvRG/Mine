---
description: Read-only audit of the current repo state against blueprints and known defects
argument-hint: [area or leave blank for everything]
allowed-tools: Read, Grep, Glob, Bash
---
No edits. Audit $ARGUMENTS (or the whole repo). Read docs/agent/*, then the code. Use the debugger, test-auditor, blueprint-checker, and migration-guard sub-agents in parallel on their areas. Produce: (1) table of known defects in TASKS.md: confirmed / fixed / not a bug, with file and line evidence; (2) new defects with proof; (3) tests that give false confidence; (4) blueprint contradictions; (5) a proposed ordered task list with the test that proves each; (6) questions for me. Write the result to docs/agent/work/audit-<date>.md. Then stop.
