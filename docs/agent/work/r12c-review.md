# Task R12c Review: Item 1 Proposal Rollback Status-Only Update (created_at Preservation)

Date: 2026-11-05
Task: R12c Item 1 (Rollback fix)
Reviewers: Migration Guard, Independent Code Reviewer, Test Auditor

---

## 1. Migration Guard Report

### Schema & SQL Invariants
1. **Schema Alteration**: **NO**. The query `UPDATE proposal SET status = ? WHERE id = ?` is pure DML on the existing `proposal` table. It introduces no DDL changes and requires no alterations to table definitions, columns, or indexes.
2. **Database Version Bump**: **NO**. Database schema version remains at 5 (`MineDatabaseHelper.kt:13`). Table `proposal` already defines `id TEXT PRIMARY KEY`, `status TEXT NOT NULL DEFAULT 'open'`, and `created_at INTEGER NOT NULL` in `onCreate` (`MineDatabaseHelper.kt:31`).
3. **Preservation of `created_at` Timestamp**: **YES**. Unlike `saveProposal` (`AndroidStorage.kt:302`) which executes `INSERT OR REPLACE` and resets `created_at` to `System.currentTimeMillis()`, `updateProposalStatus` only mutates the `status` column, keeping `created_at` strictly immutable.
4. **Parameter Binding & Injection Safety**: **YES**. Query parameters `status` and `id` are bound via positional placeholders (`?`) in `arrayOf(status, id)` (`AndroidStorage.kt:337`).
5. **Triggers & FTS Interaction**: **NO INTERFERENCE**. Triggers guard `raw_message` (`trg_raw_msg_*`) and `raw_event` (`trg_raw_evt_*`). FTS5 tables and sync triggers index only `node`, `soul_fact`, and `msg_chunk` (`MineDatabaseHelper.kt:138-148`). `proposal` has zero triggers and no FTS tables.
6. **Concurrency & Thread Safety**: **YES**. Wrapped in `withContext(ioDispatcher)` to offload blocking I/O from the main thread. SQLite executes the single UPDATE statement as an atomic transaction.
7. **Fresh-Install Smoke Test Verification**: Updated `app/src/androidTest/java/com/anchor/mine/storage/DatabaseMigrationTest.kt:testFreshV5SmokeTest_RunsEveryStorageMethod` to explicitly invoke `updateProposalStatus("p_fresh", "open")` and `consumeProposalCas("p_fresh")`, ensuring 100% of Storage interface methods are exercised on fresh database creation.

### VERDICT: APPROVE

---

## 2. Independent Code Reviewer Report

### Code Diff Audit
1. **Storage Interface (`core/src/main/java/com/anchor/core/storage/Storage.kt`)**:
   - Added `suspend fun updateProposalStatus(id: String, status: String)` to the `Storage` interface.
2. **Android Storage Implementation (`app/src/main/java/com/anchor/mine/storage/AndroidStorage.kt`)**:
   - Implemented `updateProposalStatus(id: String, status: String)` via:
     ```kotlin
     override suspend fun updateProposalStatus(id: String, status: String) = withContext(ioDispatcher) {
         dbHelper.writableDatabase.execSQL("UPDATE proposal SET status = ? WHERE id = ?", arrayOf(status, id))
     }
     ```
   - Avoids `INSERT OR REPLACE` and leaves `created_at` unchanged.
3. **Test Double Implementation (`core/src/test/java/com/anchor/core/SharedFakeStorage.kt`)**:
   - Implemented `updateProposalStatus(id: String, status: String)` mutating `savedProposals` in-place while leaving `proposalCreatedAt[id]` untouched.
   - Parity with SQLite confirmed: `getProposal` checks `proposalCreatedAt[id]` against `clock.now() - 24h` threshold.
4. **Gateway Rollback Coordination (`core/src/main/java/com/anchor/core/gateway/Gateway.kt`)**:
   - Replaced all 6 instances of `storage.saveProposal(proposal.copy(status = "open"))` with `storage.updateProposalStatus(proposal.id, "open")`:
     - Line 354: duplicate title collision on CREATE time-chip.
     - Line 367: `handleCreate` returning `ToolResult.Error`.
     - Line 375: `handleResolveChange` (RESCHEDULE) returning `ToolResult.Error`.
     - Line 384: `handleScheduleReminderWithTime` (UPDATE) returning `ToolResult.Error`.
     - Line 391: exception thrown during time-chip execution.
     - Line 449: exception thrown during node-chip confirmation execution.
   - Result: 24h expiration window is never artificially extended upon rollback.

### VERDICT: APPROVE

---

## 3. Test Auditor Report

### Test Quality & Revert-Proof Checks
1. **Unit Test (`core/src/test/java/com/anchor/core/ui/R12ConcurrencyLifecycleTest.kt`)**:
   - `testRollbackDoesNotExtend24hExpiry_ExpiryMustNotExtendAfterRolledBackConfirm`:
     - Proposal created at $T_0$.
     - Clock advances to $T_0 + 23\text{h}50\text{m}$.
     - Gateway executes confirm on duplicate title; validation fails, rolling proposal back to `"open"`.
     - Clock advances to $T_0 + 24\text{h}10\text{m}$.
     - Asserts `storage.getProposal(id)` returns `null` and `storage.getOpenProposals()` excludes the proposal.
     - **Revert-Proof Proof**: If reverted to `saveProposal(proposal.copy(status = "open"))`, `proposalCreatedAt` would be reset to $T_0 + 23\text{h}50\text{m}$, and at $T_0 + 24\text{h}10\text{m}$ (only 20m later), `getProposal` would return non-null, causing test failure.
2. **Instrumented Real-SQLite Test (`app/src/androidTest/java/com/anchor/mine/storage/AndroidStorageTest.kt`)**:
   - `testUpdateProposalStatus_PreservesCreatedAt_RealSqlite`:
     - Creates proposal, artificially ages `created_at` in SQLCipher table.
     - Calls `updateProposalStatus("consumed")` then `updateProposalStatus("open")`.
     - Queries raw SQLite table: asserts `created_at` is exactly unchanged.
     - Calls old `saveProposal(copy(status = "open"))`: asserts `created_at` is overwritten with current timestamp.
3. **Fresh Install Smoke Test (`app/src/androidTest/java/com/anchor/mine/storage/DatabaseMigrationTest.kt`)**:
   - `testFreshV5SmokeTest_RunsEveryStorageMethod`: asserts `updateProposalStatus` and `consumeProposalCas` function on a pristine database.

### Build and Test Execution Evidence
- `:core:test`: 194/194 unit tests PASS (exit 0)
- `:app:compileDebugAndroidTestKotlin`: PASS (exit 0)
- Connected tests on physical moto g73 5G (API 34): 45/45 tests PASS (exit 0, 0 failures, 0 skipped across 8 test suites)

### VERDICT: APPROVE
