---
name: fix
description: Fix one task using the branching-analysis protocol, tests first, then independent review. Triggers /fix slash command.
---

# Fix Task

Fix a specific task using the branching-analysis protocol, tests first, followed by independent review.

## Instructions

Target: `<task id from TASKS.md>`

1. Read `docs/agent/HANDOFF.md`, `STATE.md`, the task entry in `TASKS.md`, and the cited blueprints.
2. Run the `branching-analysis` skill; write `docs/agent/work/<task id>.md` (hypotheses, angles, evidence, chosen change, rejected options).
3. Write failing tests first; run them and inspect the failing output.
4. Implement the smallest change; if SQL/schema is touched, invoke `migration-guard`.
5. Run `./gradlew :core:test` (and `connectedDebugAndroidTest` or the `android-device` agent where the task needs it).
6. Spawn `reviewer` and `test-auditor` subagents with the diff. Fix blocking findings (max 3 rounds), otherwise record them in `STATE.md`.
7. Update `TASKS.md`, `STATE.md`, `LEARNINGS.md`; commit with `git commit -s` citing the blueprint and test; write `HANDOFF.md`. Report in the standard 8-part format. Stop.
