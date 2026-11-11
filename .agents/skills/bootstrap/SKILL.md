---
name: bootstrap
description: One-time import of the old AI Studio chat handoff into the repo memory files. Triggers /bootstrap slash command.
---

# Bootstrap Repo Memory

One-time import of the old AI Studio chat handoff into the repo memory files.

## Instructions

Read `docs/agent/INBOX_HANDOFF.md`. It was written by a different model that could not run code, so treat every claim as UNVERIFIED.

1. Read `AGENTS.md`, `docs/blueprints/README.md`, and the repo (`git log -20`, `git status`, module layout).
2. Create `docs/agent/STATE.md`, `TASKS.md`, `DECISIONS.md`, `LEARNINGS.md` from the handoff, but only claims you verified yourself. Put unverified claims in a section "UNVERIFIED (from old chat)" with the check needed.
3. Run `./gradlew :core:test` and `./gradlew :app:assembleDebug`; record exit codes.
4. Compare the handoff's list of "files changed" to git log/diff. List mismatches (handoff says changed but not in repo, or vice versa).
5. Write `docs/agent/HANDOFF.md` with the exact next step. Stop and wait for user.
