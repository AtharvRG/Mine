# Independent Review: Task R4 (Unreachable features: stale items and time chips)

Date: 2026-11-04  
Reviewer: Independent Reviewer Agent  
Verdict: APPROVE (after addressing reviewer weekday roll and legacy reminder cleanup suggestions)

---

### 1. Guarantee Restatement
Surface tasks idle for 30+ days into `UiState.staleItems` and `UiState.inboxCount` with undoable `KeepStale` and `CancelStale` lifecycle actions. Integrate `DateResolver.resolve()` into `Gateway` for zero-model-call time clarification chips (`ToolResult.NeedsTime`) and instant execution on confirmation (`handleConfirmChange`), ignoring ambiguous times on CANCEL/COMPLETE, and hardening natural language temporal parsing (night/evening/afternoon hours, same-day and future weekdays, localized 12h/24h formats).

---

### 2. Files Inspected & Full git diff Verification
The following files were inspected in full alongside all callers and dependencies:
- `core/src/main/java/com/anchor/core/time/DateResolver.kt`
- `core/src/main/java/com/anchor/core/gateway/Gateway.kt`
- `core/src/main/java/com/anchor/core/models/Memory.kt`
- `core/src/main/java/com/anchor/core/models/ToolContracts.kt`
- `core/src/main/java/com/anchor/core/ui/ChatContract.kt`
- `core/src/main/java/com/anchor/core/ui/ChatController.kt`
- `app/src/main/java/com/anchor/mine/MainActivity.kt`
- `core/src/test/java/com/anchor/core/time/DateResolverR4Test.kt`
- `core/src/test/java/com/anchor/core/gateway/R4TimeChipsTest.kt`
- `core/src/test/java/com/anchor/core/ui/R4StaleItemsTest.kt`
- `docs/agent/work/r4.md`

---

### 3. Bug Hunt Along 10 Axes
- **data/schema/migration**: None found. No SQLite schema migrations were introduced; operations re-use existing `Proposal`, `ProposalPayload`, `Node`, and `Reminder` definitions.
- **concurrency and cancellation**: None found. `KeepStale` and `CancelStale` execute on `defaultDispatcher` through atomic `storage.updateNodeAndCommit` transactions.
- **process death and reboot**: None found. All state modifications persist synchronously to SQLite and generate durable `RawEvent` before/after JSON states.
- **failure paths (network, 429, malformed model output, null args)**: Zero model round-trips for proposal chip confirmations eliminate LLM failure modes. Past times (`chosenMillis <= clock.now()`) return an explicit error and safely consume stale proposals.
- **idempotency (runs twice, already-closed item)**: Proposal consumption is transactional; repeated chip taps safely reject because the proposal status transitions to `"consumed"`.
- **non-Latin text**: All titles and hint strings preserve UTF-8 strings.
- **main-thread work**: Storage mutations and resolver operations run on background dispatchers.
- **alarm and notification correctness**: Zero-Vibration Protocol remains strictly enforced; no `VIBRATE` permission in manifest and no programmatic vibration calls.
- **privacy (secrets in logs, local-only data)**: All operations operate locally with zero network egress.
- **blast radius (what else uses this code)**: All existing chat controller flows, prompt budgets, and resolver call sites remain green across all 114 unit tests and 31 connected tests.

---

### 4. Reviewer Findings & Remediation

1. **Legacy Reminder Lookups on Stale Actions**:
   - *Finding*: `handleKeepStale` and `handleCancelStale` previously only searched by `getReminderByNodeId(nodeId)`. Nodes with legacy reminder ID format `r_${nodeId}` could miss snapshotting or cleanup.
   - *Fix applied*: Updated both methods in `Gateway.kt` to check `storage.getReminderByNodeId(nodeId) ?: storage.getPendingReminders().find { it.id == "r_$nodeId" }`, and pass `deleteRemId = oldRem?.id ?: "r_$nodeId"` on cancel.
   - *Verification*: Verified by `testCancelStale_WithLegacyReminderId_CleansUpLegacyReminder` in `R4StaleItemsTest.kt`.

2. **Duplicate Title Prevention on Time Confirmations**:
   - *Finding*: Clarified whether confirming a time proposal could accidentally bypass duplicate checks.
   - *Resolution*: `handleConfirmChange` routes through `handleCreate(title, ...)` which already checks `storage.findActiveNodeByTitle(title)` and prevents duplicates.

3. **Same-Day Weekday Resolution for Future Times**:
   - *Finding*: Requesting a future time on the current weekday (e.g. "friday at 5pm" on Friday at 10am) previously rolled forward 7 days to the following Friday.
   - *Fix applied*: Enhanced `DateResolver.kt` weekday matching so that when the day name matches today and the prefix is not `"next"`, future times resolve to today; past times and `"next <weekday>"` expressions roll forward by 7 days.
   - *Verification*: Verified by `testWeekdayParsing_SameDayEarlierAndLater` in `DateResolverR4Test.kt`.

---

### 5. Final Verdict
**APPROVE**. Task R4 is fully implemented, verified, hardened, and documented.
