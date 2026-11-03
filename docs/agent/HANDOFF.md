# HANDOFF (Session 1 - Remediation R0/R0b)

## 1. What Changed

- **Commit `3ed71b3`**: Baseline commit capturing initial repo state prior to remediation review.
- **Commit `81ec87a`** (`chore: record correction, hygiene, R1-R10 plan`):
  - Recorded architectural decisions D-109, D-110, D-111, and clarified D-105 gate status in `docs/agent/DECISIONS.md`.
  - Re-opened tasks T-A, T-B, T-C, T-D, T6, T13, T20 to in-progress `[~]` in `docs/agent/TASKS.md`.
  - Appended remediation tasks R1 through R10 and restored dropped roadmap backlog items (P1-B 15, 17, DateResolver weekday names, P1-D 20 remainder, P1-E 21..34) in `docs/agent/TASKS.md`.
  - Corrected toolchain versions, accurate test counts, and device offline reality in `docs/agent/STATE.md`.
  - Redacted device identifiers to git-ignored `device-local.properties`.
  - Deleted 0-byte dead stub `core/src/main/java/com/anchor/core/alarms/AlarmReceiver.kt`.
  - Hardened `scripts/ralph.py` against blind `git add -A`, `--allow-empty`, and continued loop on test failure.
  - Repointed hook commands in `.openclaude/settings.json` to `.openclaude/hooks/*.py`.
  - Aligned `docs/ui-contract.md` to remove unimplemented storage query claims.
  - Created `docs/agent/BLUEPRINT_PATCHES.md` with Patch 1 for Blueprint 13 s3.6 (`follow_up` edge type).
- **Commit `982dbc7` (R0b)**:
  - Created `docs/agent/work/session1-remediation-spec.md` preserving the full verbatim session 1 specification prompt.
  - Added pointer to `session1-remediation-spec.md` at top of `docs/agent/TASKS.md`.
  - Added historical warning banner to `docs/agent/INBOX_HANDOFF.md`, `docs/agent/work/session0-plan.md`, and `docs/agent/work/t-a.md` through `t-f.md`.
  - Reconciled all cited test names in `docs/agent/work/session0-plan.md` to verified existing test names or marked "does not exist".
  - Replaced `getEdgesFrom`/`getEdgesTo` with `getEdgesForSource`/`getEdgesForDestination` in `docs/ui-contract.md` and verified all UiState fields, UiActions, and Storage methods exist in `:core`.
  - Verified directory junction `docs/blueprints -> D:\CnP\MDocs (Attempt 3)`, added `docs/blueprints/` to `.gitignore`, and added deny rules for `Edit` and `Write` on `docs/blueprints/**` in both `.openclaude/settings.json` and `.claude/settings.json`.
    - *Safety Note*: The `Edit`/`Write` deny rules on `docs/blueprints/**` only block those two tools, not Bash, and may not match once the path resolves to `D:\CnP\...`. The developer will set the source folder to read-only.
  - Added developer-lock comment to `scripts/ralph.py` and gated `scripts/ralph_prompt.txt` behind `RALPH: ENABLED`.
  - Re-ran `:core:test`, `:app:assembleDebug`, `:app:assembleRelease`, `:app:compileDebugAndroidTestKotlin`, and `:app:lintDebug` with full status captured.
- **Commit `ff78d71` (R0c)**:
  - Verified blueprint junction directory listing and gitignore compliance (`git check-ignore -v docs/blueprints` matches; `git ls-files docs/blueprints` empty).
  - Split `:app:compileDebugAndroidTestKotlin` and `:app:testDebugUnitTest` in `STATE.md` and re-ran `:app:testDebugUnitTest` (SUCCESS, exit 0, `NO-SOURCE`).
  - Corrected `docs/ui-contract.md` for `AcceptSoulFact` (sets status to "active") and `inboxCount` (proposals + soul facts; stale items excluded pending R4).
  - Appended R1 notes to `docs/agent/work/session1-remediation-spec.md` (v5 migration assertions update, `MineDatabaseCallback.onUpgrade` trigger limitation recorded).
  - Logged trigger limitation task in `docs/agent/TASKS.md`.
- **Current Commit (Task R1 - Reschedule/cancel/complete after ambiguity, D-110, D-111)**:
  - SQLite schema bumped to v5 with nullable `payload TEXT` on `proposal` table, with idempotent `PRAGMA table_info` upgrade logic in `MineDatabaseCallback`.
  - Added `ProposalPayload` serializable data class and updated `Proposal` model.
  - Implemented `propose_change` intent validation in `Gateway`: immediately reject unsupported `UPDATE`, `PAUSE`, `RESUME` with `ToolResult.Error` before mutating state.
  - For `RESCHEDULE`, pass `timeForResolver = null` to `Resolver.resolve` to prevent +0.5 time proximity bonus biasing candidate selection; store destination timestamp in `proposal.payload`.
  - In `handleConfirmChange`, branch on `RESCHEDULE`: validate destination time `> clock.now()`, preserve node `active` status, update node `dueAt`, update/create reminder preserving existing tier (`oldRem?.tier ?: AlarmTier.ALARM`), snapshot before/after state for undo, atomically consume proposal in SQLite transaction, format deterministic receipt `"Rescheduled: <title> to <date>"`. Stale/past destination times consume the proposal and return explicit error `"That time has already passed; tell me the new time again"`.
  - Removed `UPDATE` from prompt instructions in `ChatController.kt`.
  - Authored comprehensive TDD test suite `R1RescheduleAmbiguityTest.kt` in `:core`.
  - Authored v4-to-v5 migration test `testDatabaseMigration_V4toV5_AddsProposalPayloadColumn` and updated smoke test in `DatabaseMigrationTest.kt`.
  - Authored branching analysis `docs/agent/work/r1.md` and independent 3-agent review `docs/agent/work/R1-review.md`.

## 2. Verified Working (with Evidence)

### Command: `./gradlew :core:test` (Exit code: 0)
77 unit tests executed, 77 passed, 0 failures, 0 skipped (100% success rate).

### Other Verified Builds:
- `./gradlew :app:assembleDebug`: SUCCESS (exit 0)
- `./gradlew :app:assembleRelease`: SUCCESS (exit 0, R8 minification + lintVitalRelease)
- `./gradlew :app:compileDebugAndroidTestKotlin`: SUCCESS (exit 0, compiles all 24 instrumented test methods across 6 files)
- `./gradlew :app:connectedDebugAndroidTest`: SUCCESS (exit 0, 24/24 instrumented tests passed on moto g73 5G - Android 14)
- `./gradlew :app:testDebugUnitTest`: SUCCESS (exit 0, NO-SOURCE; app/src/test contains no tests)
- `./gradlew :app:lintDebug`: SUCCESS (exit 0)

## 3. NOT Verified
- **D-105 Phone Gate**: Status-bar alarm clock icon display for Alarm-tier reminders remains to be checked on real UI while phone is active. Task T-C remains open.
- **T-E (Calendar Queries) & T-F (Home Quotes)**: Unit-verified only.
- **Remediation Tasks R2 through R10**: Open, pending execution (R1 completed).
- **B5 Export UI**: SAF picker and passphrase dialog dark-shipped pending v1.1.

## 4. Operating Rules
- Commit per task using `git commit -s`.
- Never commit failing tests.
- One task per run; stop and await user approval before proceeding to the next task.

## 5. Next Step
- **Task R2**: Candidate selection broken by 100-item default (T-B regression).
  - First step: Read spec in `docs/agent/work/session1-remediation-spec.md` (R2 section), create branching analysis `docs/agent/work/r2.md`, and write failing tests first.
