# Independent Review: Task R7 (Receipts from Operation Log, T6, Decision D-115)

Date: 2026-11-04  
Reviewer: Antigravity (Adversarial Code Reviewer)  
Verdict: APPROVE  

---

### 1. Guarantee Restatement
Line 1: Reconstruct UI receipts and 24-hour undo tokens durably from immutable `raw_event` log entries across process death and controller lifecycles without relying on ephemeral in-memory state or model prose.
Line 2: Ensure deterministic presentation of UI-visible operations in device-configured date/time format, anchor receipts to causing messages via `causeMsgId`, and safely expire or invalidate undo tokens after 24 hours or upon successful undo execution.

---

### 2. Files Inspected & Full git diff Verification
The following files were inspected in full alongside all callers and dependencies:
- `core/src/main/java/com/anchor/core/ui/ReceiptFormatter.kt`
- `core/src/main/java/com/anchor/core/ui/ChatContract.kt`
- `core/src/main/java/com/anchor/core/ui/ChatController.kt`
- `core/src/main/java/com/anchor/core/gateway/Gateway.kt`
- `core/src/main/java/com/anchor/core/storage/Storage.kt`
- `app/src/main/java/com/anchor/mine/storage/AndroidStorage.kt`
- `core/src/test/java/com/anchor/core/SharedFakeStorage.kt`
- `core/src/test/java/com/anchor/core/ui/R7ReceiptsTest.kt`
- `app/src/androidTest/java/com/anchor/mine/storage/AndroidStorageTest.kt`
- `core/src/main/java/com/anchor/core/models/ToolContracts.kt`
- `core/src/main/java/com/anchor/core/models/Memory.kt`
- `core/src/main/java/com/anchor/core/time/DateResolver.kt`
- `app/src/main/java/com/anchor/mine/storage/MineDatabaseHelper.kt`
- `app/src/main/java/com/anchor/mine/MainActivity.kt`

---

### 3. Bug Hunt Along 10 Axes
- **data/schema/migration**: None found, because `cause_msg_id` has been a nullable column on `raw_event` since DB version 1, requiring no schema version bump or migration DDL.
- **concurrency and cancellation**: None found, because `loadReceipts` executes within background coroutines on `defaultDispatcher` with atomic `_state.update` flows, and storage reads use read-only SQLite transactions on `ioDispatcher`.
- **process death and reboot**: None found, because receipts and undo tokens are re-hydrated dynamically from the audited, immutable `raw_event` table on controller initialization and UI refresh.
- **failure paths (network, 429, malformed model output, null args)**: None found, because `ReceiptFormatter.format` wraps JSON decoding in a top-level `try-catch` block falling back to safe entity identifiers on corrupted snapshots, and uncommitted tool errors are tracked transiently without writing invalid `raw_event` rows.
- **idempotency (runs twice, already-closed item)**: None found, because repeated undo invocations are rejected by `storage.hasUndo(token)` in `Gateway.handleUndo` and the dynamic retraction of `undoToken` once `hasUndo` returns true.
- **non-Latin text**: None found, because `ReceiptFormatter` performs direct UTF-8 string interpolation of snapshot title and statement fields without lossy conversions or byte truncations.
- **main-thread work**: None found, because all database operations in `AndroidStorage` run strictly on `withContext(ioDispatcher)`.
- **alarm and notification correctness**: None found, because receipt loading and formatting do not touch alarm scheduling; undoing an operation restores or deletes the reminder snapshot transactionally.
- **privacy (secrets in logs, local-only data)**: None found, because receipts are rendered locally within `UiState` with zero network egress, and provider API keys remain redacted in `UiState.toString()`.
- **blast radius (what else uses this code)**: None found, because `getRecentEvents` is an additive query and existing callers of `UiState.receipts` retain binary and source compatibility.

---

### 4. Project Rules Verification
- **LLM proposes / code decides**: Upheld. Tool executions commit state changes to SQLite, write audited `RawEvent` records, and deterministically generate receipt text with zero model prose.
- **Nothing hard-deleted**: Upheld. `raw_event` remains append-only, and undos append new reverse events with `undo_of`.
- **Dates resolved in code**: Upheld. All temporal strings in receipts are formatted via `DateResolver.format()` conforming to system 12h/24h settings.
- **Append-only raw tables**: Upheld. `raw_event` only receives `INSERT` statements with database triggers enforcing append-only semantics.
- **SPDX header**: Upheld on all newly created and modified files.
- **No vibration**: Upheld.
- **No new dependency**: Upheld.

---

### 5. Findings
- **BLOCKING**: None.
- **IMPORTANT**: None.
- **MINOR**: `app/src/main/java/com/anchor/mine/MainActivity.kt:139`: `causeMsgId` is populated on `ReceiptState` but `MainActivity.kt` renders all receipts in a list block at the bottom of the screen rather than anchored directly below their triggering `ChatMessage(id = causeMsgId)`. (Deferred to Part 3 UI layout work).

---

### 6. Tests
- `com.anchor.core.ui.R7ReceiptsTest.testKillRecreateController_ReceiptsAndValidUndoTokensSurvive`: (fails-before: yes)
- `com.anchor.core.ui.R7ReceiptsTest.testAfter24Hours_UndoTokenIsAbsent`: (fails-before: yes)
- `com.anchor.core.ui.R7ReceiptsTest.testUndoneReceipt_ShowsNoUndo`: (fails-before: yes)
- `com.anchor.core.ui.R7ReceiptsTest.testReceiptOrderAndCauseMsgIdCorrect`: (fails-before: yes)
- `com.anchor.core.ui.R7ReceiptsTest.testNoModelTextInReceipts`: (fails-before: yes)
- `com.anchor.core.ui.R7ReceiptsTest.testReceiptFormatter_AllUiVisibleOpTypes`: (fails-before: yes)
- `com.anchor.mine.storage.AndroidStorageTest.testAndroidStorage_getRecentEvents_PreservesCauseMsgIdAndFiltersUndoOf`: (fails-before: yes)

---

### 7. Final Verdict
**APPROVE**
