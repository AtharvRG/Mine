---
name: audit
description: Read-only audit of the current repo state against blueprints and known defects. Triggers /audit slash command.
---

# Repo State and Blueprint Audit

Read-only audit of the current repo state against blueprints and known defects.

## Instructions

1. **No edits**. Audit specified area (or the whole repo if unspecified).
2. Read `docs/agent/*`, then the code.
3. Use the `debugger`, `test-auditor`, `blueprint-checker`, and `migration-guard` sub-agents in parallel on their areas.
4. Produce:
   - (1) Table of known defects in `TASKS.md`: confirmed / fixed / not a bug, with file and line evidence
   - (2) New defects with proof
   - (3) Tests that give false confidence
   - (4) Blueprint contradictions
   - (5) A proposed ordered task list with the test that proves each
   - (6) Questions for the user
5. Write the result to `docs/agent/work/audit-<date>.md`. Then stop.
