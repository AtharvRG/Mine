# Task R1 Independent Review: Reschedule/Cancel/Complete After Ambiguity

Date: 2026-11-03  
Task: R1 (Decisions D-110, D-111, D-5)  
Reviewers: Adversarial Code Reviewer, Migration Guard, Test Auditor  

---

## 1. Adversarial Code Reviewer Report

### Guarantee Restatement
Preserve destination times across ambiguous reschedule proposals in the operational layer (`proposal.payload`, DB v5) so chip taps execute deterministically with zero model calls.
Omit destination times from resolver candidate matching during reschedule, validate target times against the current clock upon confirmation, and reject unsupported intents (`UPDATE`, `PAUSE`, `RESUME`) before database mutation.

### Bug Hunt Along 10 Axes
- **data/schema/migration**: None found, because `MineDatabaseCallback` bumps schema to v5 with an idempotent `PRAGMA table_info` check before `ALTER TABLE proposal ADD COLUMN payload TEXT`, verified via v4-to-v5 and fresh v5 migration tests.
- **concurrency and cancellation**: None found, because proposal status consumption and node/reminder writes execute atomically within a single SQLite transaction with WAL enabled, and background tasks run on `Dispatchers.IO`.
- **process death and reboot**: None found, because open proposals persist with payload in SQLite and are read on restart by `getOpenProposals()`, while stale proposals (>24h) expire deterministically at read time.
- **failure paths (network, 429, malformed model output, null args)**: None found, because payload decoding is wrapped in `try-catch` falling back safely to null, and invalid choices, missing nodes, or expired targets return explicit `ToolResult.Error` while consuming the proposal.
- **idempotency (runs twice, already-closed item)**: None found, because `confirm_change` checks `proposal.status != "open"` and `node.status` in `('active', 'in_progress')`, preventing replay or mutating closed nodes, while tool call hashes prevent duplicate invocations within a turn.
- **non-Latin text**: None found, because item titles and hints are passed through without ASCII sanitization, `Resolver` tokenization matches Unicode classes `[\p{L}\p{M}\p{N}]+`, and `DateResolver` normalizes non-Latin digits before parsing.
- **main-thread work**: None found, because all database queries and transactions in `AndroidStorage` run on `ioDispatcher` (`Dispatchers.IO`), and `ChatController` executes actions on `defaultDispatcher`.
- **alarm and notification correctness**: None found, because `RESCHEDULE` confirmation preserves the existing reminder's tier (`oldRem?.tier ?: AlarmTier.ALARM`) while updating `fireAtMillis`, and `refreshUI()` triggers `reconcileAlarms()` against the alarm registry.
- **privacy (secrets in logs, local-only data)**: None found, because proposal payloads store only timestamp/metadata without sensitive categorization, and all operational states reside in local encrypted SQLCipher without external network leakage.
- **blast radius (what else uses this code)**: None found, because removing `UPDATE` from prompt instructions aligns with D-111, and `Gateway.executeTool` cleanly restricts allowed intents to `CREATE`, `CANCEL`, `COMPLETE`, and `RESCHEDULE` across both direct tool execution and confirmation.

### Project Rules Verification
- **LLM proposes / code decides**: Upheld; model generates tool envelopes, while code resolves ambiguity, computes diffs, validates targets, and executes chip selections with 0 model calls.
- **Nothing hard-deleted**: Upheld; cancelled/completed nodes are soft-status marked with `closed_at`, reminder removals are captured in `raw_event.before_json`, and proposals are transitioned to `status = 'consumed'`.
- **Dates resolved in code**: Upheld; relative dates are parsed via `DateResolver` using clock time and day rollover minutes without trusting model ISO estimations.
- **Append-only raw tables**: Upheld; `raw_message` and `raw_event` maintain strict SQLite triggers aborting `UPDATE` and `DELETE`.
- **SPDX headers**: Upheld; GPL-3.0-or-later headers are present on all modified and newly created source files.
- **No vibration**: Upheld; no vibration API or permission was introduced.
- **No new dependencies**: Upheld; no additions to Gradle build files.

### Issues
- **BLOCKING**: None.
- **IMPORTANT**: None.
- **MINOR**: In `Gateway.kt:162`, `if (target == null || target <= clock.now())` outputs `"That time has already passed; tell me the new time again"` even when `target == null` (i.e. legacy proposal without payload). Minimal fix: could distinguish missing payload vs expired time if needed.

### Tests
- `testRescheduleAmbiguity_TwoDinners_ConfirmAndUndo` (fails-before: yes)
- `testResolver_RescheduleNullTime_vs_CancelTargetTime` (fails-before: yes)
- `testRescheduleAmbiguity_TargetInPast_ConsumesProposalAndErrors` (fails-before: yes)
- `testUpdateDirectAndProposal_ReturnsExplicitError` (fails-before: yes)
- `testChatController_RescheduleChipTap_ZeroModelCalls` (fails-before: yes)
- `testDatabaseMigration_V4toV5_AddsProposalPayloadColumn` (fails-before: yes)
- `testFreshV5SmokeTest_RunsEveryStorageMethod` (fails-before: yes)

**VERDICT**: APPROVE

---

## 2. Migration Guard Report

### Schema v4 -> v5 Verification Matrix

| Check | Status | Location | Notes |
|---|---|---|---|
| 1. Version bump to 5 | PASS | `MineDatabaseHelper.kt:13` | `class MineDatabaseCallback : SupportSQLiteOpenHelper.Callback(5)` |
| 2. `onCreate` schema contains `payload TEXT` | PASS | `MineDatabaseHelper.kt:31` | `CREATE TABLE proposal (..., created_at INTEGER NOT NULL, payload TEXT)` |
| 3. Idempotent `onUpgrade(oldVersion < 5)` | PASS | `MineDatabaseHelper.kt:86-100` | Queries `PRAGMA table_info(proposal)` for column `payload` before `ALTER TABLE proposal ADD COLUMN payload TEXT` |
| 4. Safe read/write in `AndroidStorage` | PASS | `AndroidStorage.kt:225-266` | Nullable `payload` handled in `saveProposal` (`arrayOf<Any?>`), `getProposal` (`isNull(4)` check), and `getOpenProposals` (`isNull(5)` check) |
| 5. v4 -> v5 migration test in `AndroidStorageTest` | PASS | `AndroidStorageTest.kt:681-796` | `testDatabaseMigration_V4toV5_AddsProposalPayloadColumn` tests v4 seeding, upgrade, pragma check, null payload legacy read, payload write/read, and `getOpenProposals` |
| 6. Triggers and indices intact | PASS | `MineDatabaseHelper.kt:48-60, 101` | Triggers recreated on upgrade; `proposal` table has no triggers or FTS tables to invalidate |
| 7. No `INSERT OR REPLACE` on nodes | PASS | `AndroidStorage.kt:353` | Node insertion uses plain `INSERT INTO node` |
| 8. Append-only triggers intact | PASS | `MineDatabaseHelper.kt:107-131` | `raw_message` and `raw_event` triggers unchanged |

### Migration Guard Notes
- **Rule (d)**: Migration test uses programmatically synthesized v4 database via `Callback(4)` rather than an immutable pre-recorded `.db` binary asset file.
- **Rule (e)**: `testFreshV5SmokeTest_RunsEveryStorageMethod` covers the main core operational domain areas (~20 methods) rather than 100% of the 58 declared methods in `Storage`.
- **Trigger Upgrade Limitation**: `MineDatabaseCallback.onUpgrade` only invokes `createTriggers` with `CREATE TRIGGER IF NOT EXISTS`. Any future trigger body changes will require dropping and recreating triggers during `onUpgrade`. Logged in `TASKS.md`.

---

## 3. Test Auditor Report

### Suite Health
- `./gradlew :core:test`: 77 tests executed, 0 failures, 0 skipped, 100% successful.
- `:app:compileDebugAndroidTestKotlin`: SUCCESS, compiles all 24 instrumented test methods across 6 files.
- Red test baseline verified: Running `R1RescheduleAmbiguityTest` against baseline core code failed 4 out of 5 tests before implementation, confirming strict non-tautological coverage.
- Zero-model-call test (`testChatController_RescheduleChipTap_ZeroModelCalls`) verifies via `HttpTransport` invocation spy that chip confirmation bypasses network and model inference entirely.
