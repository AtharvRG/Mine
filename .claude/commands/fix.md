---
description: Fix one task using the branching-analysis protocol, tests first, then independent review
argument-hint: <task id from TASKS.md>
---
Task: $ARGUMENTS.
1. Read docs/agent/HANDOFF.md, STATE.md, the task entry in TASKS.md, and the cited blueprints.
2. Run the branching-analysis skill; write docs/agent/work/$ARGUMENTS.md (hypotheses, angles, evidence, chosen change, rejected options).
3. Write failing tests first; run them and paste the failing output.
4. Implement the smallest change; if SQL/schema is touched, invoke migration-guard.
5. Run ./gradlew :core:test (and connectedDebugAndroidTest or the android-device agent where the task needs it).
6. Spawn reviewer and test-auditor with the diff. Fix blocking findings (max 3 rounds), otherwise record them in STATE.md.
7. Update TASKS.md, STATE.md, LEARNINGS.md; commit with git commit -s citing the blueprint and test; write HANDOFF.md. Report in the standard 8-part format. Stop.
