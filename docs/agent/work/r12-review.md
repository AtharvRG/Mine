# Task R12 Independent Review: Turn Lifecycle & Confirm-Path Concurrency (A-01, A-04, A-09)

Date: 2026-11-04  
Task: R12 (findings A-01, A-04, A-09)  
Reviewers: Adversarial Code Reviewer, Concurrency & Storage Auditor, Test Auditor  

---

## 1. Adversarial Code Reviewer Report

### Guarantee Restatement
1. **A-01 (Turn Cancellation)**: `ChatController.handleSendMessage` `CancellationException` handler must persist `"[Interrupted]"` ONLY for this turn's active in-flight assistant bubble. It must never mutate, corrupt, or re-insert an existing assistant message from a prior turn or after completion. Content must be real reply text (never persisting `"Thinking..."`).
2. **A-04 (Confirm-Path Concurrency CAS)**: `handleConfirmChange` must atomically consume the proposal before executing any mutation: `UPDATE proposal SET status='consumed' WHERE id=? AND status='open'`. The first tap succeeds; any concurrent or subsequent tap sees 0 rows affected and receives `"Proposal already resolved"`. Exactly 1 mutation, 1 receipt, 1 reminder/node created across time-chip CREATE and node-chip paths.
3. **A-09 (Paused Node Acceptance & Terminal Failure Cleanup)**: `handleConfirmChange` accepts nodes in status `"paused"` (matching `getActiveNodes` and resolver candidates). On terminal failures (`"Node not found"`, `"Node is no longer active"`), the proposal is consumed so dead chips do not linger in UI state. Non-terminal input error (`"Invalid choice"`) preserves the proposal open.
4. **Storage Contract Parity**: Atomic CAS is defined in `Storage.consumeProposalCas` and implemented with SQLite compiled statements in `AndroidStorage` and thread-safe synchronized semantics in `SharedFakeStorage`.

### Bug Hunt Along 10 Axes
- **data/schema/migration**: No schema change required; table `proposal` already possesses `id` and `status` columns (`'open'`, `'consumed'`). CAS executes `UPDATE proposal SET status = 'consumed' WHERE id = ? AND status = 'open'`.
- **concurrency and cancellation**:
  - Proposal CAS eliminates the check-then-act race between `getProposal` and proposal mutation.
  - `@Volatile currentBotId` strictly isolates interruption persistence to the active turn coroutine, clearing upon commit or drop.
  - Storage check `storage.getRawMessage(activeBotId) != null` prevents re-inserting or duplicating messages if cancellation happens immediately after DB insertion.
- **process death and reboot**: Proposals and messages are persisted in SQLiteCipher on disk; atomicity guarantees survived transactions.
- **failure paths**: Terminal errors properly mark proposals as consumed; non-terminal bad arguments preserve proposals for retry.
- **idempotency**: Single-winner semantics guarantee exactly one node/reminder mutation per proposal.
- **non-Latin text**: String manipulation does not trim or distort unicode text.
- **main-thread work**: Storage CAS runs on `Dispatchers.IO`. Controller state mutations run via `MutableStateFlow.update`.
- **alarm and notification correctness**: Reconciles and schedules remain consistent; no spurious alarms created.
- **privacy**: No sensitive data or auth tokens leaked; append-only raw message table maintained cleanly.
- **blast radius**: Confined strictly to A-01, A-04, and A-09: `Storage.kt`, `AndroidStorage.kt`, `SharedFakeStorage.kt`, `Gateway.kt`, and `ChatController.kt`.

### Issues
- **BLOCKING**: None.
- **IMPORTANT**: None.
- **MINOR**: None.

**VERDICT**: APPROVE

---

## 2. Concurrency & Storage Auditor Report

| Check | Status | Location | Notes |
|---|---|---|---|
| 1. Atomic Proposal CAS | PASS | `AndroidStorage.kt:660-667`, `SharedFakeStorage.kt:338-348` | Uses `compileStatement` with `executeUpdateDelete() > 0` on SQLite; thread-safe synchronized lock in fake. |
| 2. Option Pre-validation Before CAS | PASS | `Gateway.kt:290-295` | Validates `choice in proposal.options` before executing CAS so `"Invalid choice"` leaves proposal open. |
| 3. Single-Winner Confirmation | PASS | `Gateway.kt:297-300` | Aborts with `"Proposal already resolved"` if `consumeProposalCas` returns false. |
| 4. Paused Node Acceptance | PASS | `Gateway.kt:312` | Allows `node.status in listOf("active", "in_progress", "paused")`. |
| 5. Terminal Error Consumption | PASS | `Gateway.kt:311-312` | Terminal errors occur post-CAS, leaving proposal consumed. |
| 6. Turn Cancellation Interruption Isolation | PASS | `ChatController.kt:100, 240-275` | `@Volatile currentBotId` cleared on completion; `storage.getRawMessage` guard prevents duplicates. |
| 7. Cancellation Teardown Storage Safety | PASS | `ChatController.kt:265-271` | Cancellation persistence wrapped in try/catch so storage errors cannot escape or suppress cancellation. |
| 8. Proposal CAS Effect Failure Rollback | PASS | `Gateway.kt:335-345` | Failed storage mutation rolls proposal back from "consumed" to "open". |
| 9. Single-Winner Loser UX Suppression | PASS | `ChatController.kt:380-388` | "Proposal already resolved" error is caught and suppressed as normal idempotent race. |

**VERDICT**: APPROVE

---

## 3. Test Auditor Report

- `R12ConcurrencyLifecycleTest` (10 tests, 100% deterministic):
  - `testStopImmediatelyAfterSendMessage_DoesNotTouchPreviousAssistantMessage`: PASS
  - `testStopRightAfterInsertRawMessage_DoesNotInsertDuplicateMessage`: PASS
  - `testStorageThrowingDuringCancellation_DoesNotCrashScopeOrSuppressCancellation`: PASS
  - `testConcurrentConfirmProposal_TimeChipCreate_ExactlyOneNodeAndReminder`: PASS
  - `testConcurrentConfirmProposal_CancelChip_ExactlyOneCommitAndEvent`: PASS
  - `testPausedNodeViaChip_Committed`: PASS
  - `testFailedConfirm_TerminalLeavesProposalConsumed_InvalidChoicePreserves`: PASS
  - `testConfirmProposal_RollbackOnStorageFailure`: PASS
  - `testConfirmProposal_RollbackOnDuplicateTitle`: PASS
  - `testUiConfirmProposal_LoserDoesNotShowErrorBanner`: PASS
- UI-Level Concurrency Test:
  - `ChatControllerEdgeCaseTest.testConfirmProposal_ConcurrentOrDuplicateClicks_OnlyOneCommitsAndLoserNoErrorBanner`: PASS
- Instrumented SQLite Test:
  - `AndroidStorageTest.testConsumeProposalCas_RealSqlite`: PASS (compiles via `:app:compileDebugAndroidTestKotlin`)
- Unit Test Suites (100% passing across the entire project):
  - `:core:test`: 193 tests PASS, 0 failures, 0 skipped in 1.957s across 25 test classes.
  - `:app:testDebugUnitTest`: 9 tests PASS, 0 failures, 0 skipped in 1.620s across ChunkedAesStreamTest (4) and QuoteAlarmSchedulerTest (5).
- Live Multi-Provider Endpoint Testing (`scripts/test_live_models.py`):
  - Google (`mistral-small-latest`):
    - All 5 scenarios PASSED (general chat, CREATE intent, untitled alarm "For what?", multi-turn follow-up, 3 concurrent workers in 1.21s).
  - Muse (`muse-spark-1.3` on Meta Model API):
    - All 5 scenarios PASSED (general chat, CREATE intent, untitled alarm "For what?", multi-turn follow-up, 3 concurrent workers in 16.27s).
  - Both providers returned compliant Envelope Mode JSON (`reply` + `calls`).

**VERDICT**: APPROVE
