# R12d Review: In-Transaction Proposal CAS (Option b / D-118) & Guarded Rollback

Date: 2026-11-05  
Status: Verified & Approved  
Author: Agent Review Team (Migration Guard, Independent Reviewer, Test Auditor)

---

## 1. Migration Guard Report

### Verdict: PASS

### Checklist
| Item | Status | File and Line | Details |
|---|---|---|---|
| **(a) DB version bump** | **PASS (N/A)** | `MineDatabaseHelper.kt:13` | No DDL/schema modifications made in R12d. Version remains v5. |
| **(b) Idempotent migration** | **PASS (N/A)** | `MineDatabaseHelper.kt:63-102` | No migration needed; existing migrations (v1–v5) remain idempotent. |
| **(c) Triggers recreated on upgrade / open** | **PASS** | `MineDatabaseHelper.kt:101, 106, 110-150` | `createTriggers(db)` is called in `onCreate`, `onUpgrade`, and `onOpen`. |
| **(d) Migration test from previous version** | **PASS** | `DatabaseMigrationTest.kt:21-120` | Migration tests pass; no v5->v6 migration required. |
| **(e) Fresh-install smoke test calls EVERY Storage method** | **PASS** | `DatabaseMigrationTest.kt:22-59` | Calls `consumeProposalCas` and `reopenProposal` alongside all existing Storage methods. |
| **(f) FTS tables rebuilt when content migrated** | **PASS (N/A)** | `MineDatabaseHelper.kt:56-59` | No content tables migrated. FTS shadow triggers remain synchronized. |
| **No INSERT OR REPLACE on nodes** | **PASS** | `AndroidStorage.kt:511-512` | Uses immutable `INSERT INTO node`, preserving node identity invariants. |
| **No unmigrated columns in queries** | **PASS** | `AndroidStorage.kt:342, 352` | Queries only access `status` and `id` on table `proposal`, defined since schema v1. |
| **ORDER BY with NULLs handling** | **PASS** | `AndroidStorage.kt:393` | `ORDER BY due_at IS NULL, due_at ASC` explicitly puts nulls last in SQLite. |
| **Append-only triggers compliance** | **PASS** | `AndroidStorage.kt:529, 562, 592` | Events are strictly appended via `INSERT INTO raw_event`; `trg_raw_evt_no_update` and `trg_raw_evt_no_delete` are respected. |
| **SQLCipher + FTS5 availability** | **PASS** | `MineDatabaseHelper.kt:153-162` | Configured with `System.loadLibrary("sqlcipher")` and `SupportOpenHelperFactory`. |

### Detailed Findings
1. **Schema & Table Definition Impact**:
   - `consumeProposalInTx` and `reopenProposal` execute pure DML `UPDATE` statements on the existing `proposal` table (`status` column). Database schema remains v5.
2. **Statement Preparation and Injection Safety**:
   - `reopenProposal`: Compiled via `compileStatement("UPDATE proposal SET status = 'open' WHERE id = ? AND status = 'consumed'")` with bound parameter `stmt.bindString(1, id)`. Statement closed in `finally`.
   - `consumeProposalInTx`: Compiled via `db.compileStatement("UPDATE proposal SET status = 'consumed' WHERE id = ? AND status = 'open'")` with bound parameter `stmt.bindString(1, proposalId)`. Statement closed in `finally`.
3. **Transaction Rollback Semantics**:
   - In `createNodeAndCommit`, `updateNodeAndCommit`, and `createReminderAndCommit`, `consumeProposalInTx` executes inside `db.beginTransaction()`.
   - If `affected == 0`, `ProposalAlreadyResolvedException` is thrown before `db.setTransactionSuccessful()` is reached. The enclosing `finally { db.endTransaction() }` cleanly rolls back all preceding uncommitted modifications.

---

## 2. Independent Code Reviewer Report

### Verdict: PASS (Post-Remediation)

### Remediations Applied During Review
1. **Resolved Terminal Failure Consumption Preservation in `Gateway.kt`**:
   - *Issue*: In `handleScheduleReminderWithTime`, when `targetRef` is missing (terminal failure), line 294 consumes the proposal. Previously, `handleConfirmChange` had `if (res is ToolResult.Error) storage.reopenProposal(proposal.id)`, which undid the terminal failure consumption and left dead UI chips permanently active.
   - *Fix*: Removed `if (res is ToolResult.Error) storage.reopenProposal(...)` across `handleConfirmChange`. Under Option (b), effect transactions only consume upon execution or throw `ProposalAlreadyResolvedException`; non-throwing validation errors never consume the proposal, while explicit terminal consumptions are preserved.
2. **Antecedent Proposal Consumption on `Resolution.Ambiguous` in `Gateway.kt`**:
   - *Issue*: When a time proposal for `RESCHEDULE` resolves to `Resolution.Ambiguous`, a new ambiguous proposal was created carrying `destinationTimeMillis`, but the antecedent time proposal was not consumed, leaving it open in the user inbox.
   - *Fix*: Added `if (consumeProposalId != null) storage.consumeProposal(consumeProposalId)` in `handleResolveChange` under `Resolution.Ambiguous`.
3. **Revert-Proof Crash-Window Test in `R12ConcurrencyLifecycleTest.kt`**:
   - *Issue*: The crash-window test previously verified status only after tool execution returned, which could have passed under Option (a) compensating rollback.
   - *Fix*: Added assertion inside `crashingStorage.createNodeAndCommit`:
     ```kotlin
     assertEquals("Proposal must still be 'open' upon entering effect transaction in Option (b)", "open", storage.getProposal("p_crash_1")?.status)
     ```
     This strictly fails on Option (a) (where the proposal was marked consumed before effect transaction entry), ensuring true revert-proof verification.

---

## 3. Test Auditor Report: Escape Analysis ("What It Would NOT Catch")

### Per-Test Escape Analysis

1. **`testCrashWindow_EffectTransactionThrows_ProposalRemainsOpenAndNoEffectRow` (`R12ConcurrencyLifecycleTest.kt`)**:
   - **What it proves**: In `SharedFakeStorage`, when `createNodeAndCommit` is entered, the proposal is still `"open"`. If the effect transaction throws midway, the proposal status modification is not committed and remains `"open"`, and zero entity rows exist.
   - **What it would NOT catch**:
     - SQLite journal / WAL corruption if physical device power cuts during `setTransactionSuccessful()` disk sync.
     - Multi-threaded SQLite deadlocks or locking timeouts if an external thread holds a `RESERVED` or `EXCLUSIVE` lock when `beginTransaction()` is invoked.
     - Uncaught runtime exceptions inside SQLite C-level native code or JNI bindings terminating the Android OS process without unwinding stack frames.

2. **`testInTransactionCas_RealSqlite_AbortOnAlreadyConsumed` (`AndroidStorageTest.kt`)**:
   - **What it proves**: On real SQLCipher SQLite on Android, invoking `createNodeAndCommit` or `updateNodeAndCommit` with a proposal whose status is already `'consumed'` causes `consumeProposalInTx` to throw `ProposalAlreadyResolvedException`. Calling `endTransaction()` without `setTransactionSuccessful()` rolls back all uncommitted entity rows and raw event records.
   - **What it would NOT catch**:
     - Schema-level foreign key constraint cascades or trigger race conditions if triggers on `proposal` were introduced that run before `consumeProposalInTx`.
     - Behavior if SQLite is running in auto-commit mode (e.g., if a raw query bypasses `beginTransaction()`).

3. **`testConcurrentConfirm_RealSqlite_SingleWinnerSemantics` (`AndroidStorageTest.kt`)**:
   - **What it proves**: Two parallel coroutines dispatched across `Dispatchers.IO` targeting the same proposal ID and SQLite database yield exactly 1 winner that completes the transaction and 1 loser that catches `ProposalAlreadyResolvedException`.
   - **What it would NOT catch**:
     - Multi-process concurrency: If two separate Android OS processes (e.g., a background service running in `:remote` and the main UI process) open SQLite without WAL mode, one process may receive `android.database.sqlite.SQLiteDatabaseLockedException` instead of `ProposalAlreadyResolvedException`.
     - Sub-microsecond CPU cycle collisions: Coroutines scheduled across thread pools are subject to thread scheduling jitter; it does not test exact identical hardware lock acquisition clock cycles.

4. **`testReopenProposal_RealSqlite_GuardedSemantics` & `testGuardedRollback_CannotReopenProposalConsumedBySomeoneElse`**:
   - **What it proves**: `reopenProposal` uses atomic `UPDATE ... WHERE id = ? AND status = 'consumed'`. It returns `false` and makes 0 modifications if status is `'open'`, `'archived'`, or belongs to a different proposal ID.
   - **What it would NOT catch**:
     - If a third state exists (e.g. `'rejected'` or `'expired'`) and business logic intended it to reopen from that state.
     - If a proposal is physically deleted from the table (`DELETE FROM proposal`), `reopenProposal` returns `false` but cannot resurrect the deleted row.

5. **`testExpiryParity_MissingCreatedAtReturnsNull` & `testExpiryParity_MissingCreatedAtReturnsNull_RealSqlite`**:
   - **What it proves**: In both `SharedFakeStorage` and real `AndroidStorage` (SQLite), querying a proposal with `created_at <= 0` or missing timestamp returns `null` because `WHERE created_at > ?` evaluates to false.
   - **What it would NOT catch**:
     - System clock manipulation: If the user manually sets device time backwards by >24 hours, an expired proposal could become temporarily visible until the clock catches up.
     - Storage implementations where `created_at` is stored as an ISO-8601 string rather than unix epoch milliseconds.

---

## 4. Ground Truth Test Counts Reconciliation

| Test Suite | Total Tests | Failures | Skipped | Pass Rate | Evidence File |
|---|---|---|---|---|---|
| **`:core:test`** | **197** | 0 | 0 | **100%** | `docs/agent/evidence/core-test.txt` |
| **`:app:testDebugUnitTest`** | **9** | 0 | 0 | **100%** | `docs/agent/evidence/app-testDebugUnitTest.txt` |
| **`androidTest` (Connected on `moto g73 5G`)** | **49** | 0 | 0 | **100%** | `docs/agent/evidence/connected-tests-runner.txt` |
| **Total Project** | **255** | 0 | 0 | **100%** | `docs/agent/evidence/evidence-summary.md` |

### Connected Test Suite Breakdown (49 tests)
1. `AlarmReceiverTest`: 3 passed
2. `AlarmSelfTest`: 3 passed
3. `BootReceiverTest`: 1 passed
4. `ManifestPermissionTest`: 1 passed
5. `ExportTest`: 6 passed
6. `QuoteAlarmReceiverTest`: 2 passed
7. `AndroidStorageTest`: 30 passed
8. `DatabaseMigrationTest`: 1 passed
9. `StorageTest`: 2 passed

---

## 5. Final Consolidated Verdict

- **Migration Guard**: **PASS**
- **Independent Code Reviewer**: **PASS**
- **Test Auditor**: **PASS**
- **Overall R12d Verdict**: **APPROVED**
