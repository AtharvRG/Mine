---
name: handoff
description: Write the end-of-session handoff and state summary. Triggers /handoff slash command.
---

# End-of-Session Handoff

Write the end-of-session handoff and state summary.

## Instructions

Update `docs/agent/STATE.md` and `TASKS.md`, append `LEARNINGS.md`, and overwrite `HANDOFF.md` with:
- What changed (files and commits)
- What is verified (with command and exit code)
- What is NOT verified
- Current failing tests
- Open decisions needing user input
- The exact next step (one command or task id)
- Traps for the next run

Never include claims without evidence.
