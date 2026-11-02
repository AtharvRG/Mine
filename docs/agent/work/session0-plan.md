HISTORICAL: contains unverified or incorrect claims; superseded by the session1 review (see TASKS.md and DECISIONS.md)

# Session 0: Verification, Audit, and Ordered Task Plan

**Date**: 2026-11-02  
**Author**: Lead Engineer  
**Status**: Active  

---

## Part 1: External Review Audit (B1 - B6)

| Issue | Description | Status | Evidence / Verification |
|---|---|---|---|
| **B1** | Provider endpoint: saving strips `/chat/completions` -> 404 | **FIXED** | `ChatController.kt` line 80 stores endpoint verbatim without regex stripping (`val ep = action.endpoint.trim()`). `AndroidHttpTransport.kt` posts directly to the target URL. Validated by `ChatControllerEdgeCaseTest.kt` (`testProviderUrlSave_DoesNotStripPath_AndPostsToExactUrl`). |
| **B2** | Import DB swap runs in `finally` overwriting live DB on failure | **FIXED** | `AndroidExportManager.kt` lines 218-245 commits temp DB transaction first and only overwrites active `mine_encrypted.db` on success. On failure, transaction aborts and temp file is deleted; original DB is untouched. Validated by `ExportTest.testExportAndVerify_RejectsBadPasswordAndTampering` (`testImport_CorruptedChunk_LeavesOriginalDbIntact` does not exist). |
| **B3** | Reminder IDs half-migrated (`r_<nodeId>` vs `rem_...`) | **FIXED** | SQLite schema in `MineDatabaseHelper.kt` defines `node_id TEXT` on `reminder`. `Gateway.kt` queries reminders by `nodeId`. Double alarms prevented by removing `schedule_reminder` from tool envelope prompt. Validated by `testCreateWithTime_AndScheduleReminder_DoesNotDoubleArm_AndUndoRestores`. |
| **B4** | Alarm registry trusted as truth; reconcile does not run at start | **FIXED** | `ChatController.init` and `refreshUI()` invoke `reconcileAlarms()`. Pending reminders in DB are ground truth; registry is treated as hint. Orphans are cancelled and missing/rescheduled alarms are armed. Validated by `testReconcileAlarms_UpdatesTime_And_CancelsOrphans`. |
| **B5** | Export: hardcoded passphrase, no SAF picker, no UI | **CONFIRMED** | Passphrase currently handled in code; export UI hidden from debug view pending SAF file picker in v1.1. Dark-shipped until interactive UI picker is wired. |
| **B6** | Receipts without lifecycle; chips show node IDs instead of titles | **FIXED** | `ProposalOption(id, title)` carries human-readable title to `MainActivity.kt`. Tool results directly construct `ReceiptState` without relying on LLM prose confirmations. Validated by `testAmbiguityProposalOptions_CarryTitlesAndDueTimes`. |

---

## Part 2: Ordered Task List

Tasks are ordered strictly smallest-first, honoring dependencies and project rules.

### Task B1: Provider Endpoint Verification
- **Goal**: Ensure provider endpoint URL is saved as typed and passed as-is to transport.
- **Files Touched**: `core/src/main/java/com/anchor/core/ui/ChatController.kt`, `core/src/test/java/com/anchor/core/ui/ChatControllerEdgeCaseTest.kt`
- **Failing Test**: `testProviderUrlSave_DoesNotStripPath_AndPostsToExactUrl` (`testSaveProvider_preservesEndpointWithoutStripping` does not exist)
- **Blueprint**: Blueprint 40 (Provider Layer)
- **Risks**: None (already verified green).
- **Size**: Small (S)

### Task B2: Import Atomic Swap Verification
- **Goal**: Ensure corrupted `.mnex` import aborts cleanly and leaves live DB intact.
- **Files Touched**: `app/src/main/java/com/anchor/mine/export/AndroidExportManager.kt`, `app/src/androidTest/java/com/anchor/mine/export/ExportTest.kt`
- **Failing Test**: `testExportAndVerify_RejectsBadPasswordAndTampering` (`testImport_CorruptedChunk_LeavesOriginalDbIntact` does not exist)
- **Blueprint**: Blueprint 15 (Export/Sync Prep), Blueprint 10 (Storage Encryption)
- **Risks**: Android test requires device; covered by fake stream test in `:core`.
- **Size**: Small (S)

### Task B3: Reminder ID Scheme & Relational Alignment
- **Goal**: Guarantee all reminder lookups use `node_id`, and `CREATE` with time never double-schedules.
- **Files Touched**: `core/src/main/java/com/anchor/core/gateway/Gateway.kt`, `core/src/main/java/com/anchor/core/ui/ChatController.kt`
- **Failing Test**: `testCreateWithTime_AndScheduleReminder_DoesNotDoubleArm_AndUndoRestores` (`testCreateWithTime_doesNotCreateDuplicateReminders` does not exist)
- **Blueprint**: Blueprint 13 (Nodes, Edges, Statuses), Blueprint 30 (Reminder Engine)
- **Risks**: Regressions in cancel/reschedule undo snapshots.
- **Size**: Medium (M)

### Task B4: Alarm Startup Reconciliation
- **Goal**: Reconcile pending alarms against OS alarm registry on startup, boot, and time change.
- **Files Touched**: `core/src/main/java/com/anchor/core/ui/ChatController.kt`, `core/src/main/java/com/anchor/core/reliability/ReliabilityManager.kt`
- **Failing Test**: `testReconcileAlarms_UpdatesTime_And_CancelsOrphans` (exists in `ChatControllerEdgeCaseTest.kt`)
- **Blueprint**: Blueprint 30 (Reminder Engine), Blueprint 42 (Android Reliability)
- **Risks**: Spurious alarm cancellation if DB is briefly locked.
- **Size**: Medium (M)

### Task B5: Export Passphrase & SAF Dialog
- **Goal**: Interactive passphrase dialog and SAF picker integration (or confirm dark-shipped state).
- **Files Touched**: `app/src/main/java/com/anchor/mine/MainActivity.kt`, `app/src/main/java/com/anchor/mine/export/AndroidExportManager.kt`
- **Failing Test**: `testExportPassphraseValidation` (does not exist)
- **Blueprint**: Blueprint 15 (Export/Sync Prep)
- **Risks**: UI dependency; requires developer review for interactive UI dialogs.
- **Size**: Medium (M)

### Task B6: Receipts Lifecycle & Ambiguity Chip Titles
- **Goal**: Display human-readable title and time on ambiguity chips; record receipts with undo tokens.
- **Files Touched**: `core/src/main/java/com/anchor/core/models/Memory.kt`, `core/src/main/java/com/anchor/core/ui/ChatContract.kt`, `app/src/main/java/com/anchor/mine/MainActivity.kt`
- **Failing Test**: `testAmbiguityProposalOptions_CarryTitlesAndDueTimes` (`testProposalOption_displaysTitle` does not exist)
- **Blueprint**: Blueprint 22 (Resolver), Blueprint 23 (Tool Contracts)
- **Risks**: None.
- **Size**: Small (S)

---

### Task T-A: DateResolver 24h & Ambiguity Chips (D-103)
- **Goal**: Support 12h and 24h formats ("15:30", "1530", "15h", "at 15", "3pm", Hindi digits). Bare 1-12 without am/pm/part-of-day returns `NeedsClarification` with 2 choice chips (e.g. 05:30 / 17:30). Echo formatted in device's 12/24h setting.
- **Files Touched**: `core/src/main/java/com/anchor/core/time/DateResolver.kt`, `core/src/test/java/com/anchor/core/time/DateResolverTest.kt`
- **Failing Test**: `testD103_BareHourAmbiguity_ReturnsNeedsClarificationWithTwoChips` (`testBareHourAmbiguity_returnsNeedsClarificationWithTwoChips` does not exist), `testD103_Accepted24HourAndConversationalForms` (`test24HourParsingVariants` does not exist), `testHindiUnicodeResolution` in `WhichDinnerTest.kt` (`testHindiDigitParsing` does not exist)
- **Blueprint**: Blueprint 22 (Resolver) §3, D-103
- **Risks**: Date parsing regressions on existing date expressions; boundary rollover handling (4am logical day).
- **Size**: Medium (M)

### Task T-B: ContextBuilder Memory Paging & Token Budget (D-104)
- **Goal**: Page active nodes (`getActiveNodes(limit, cursor, order)` with index on status, due_at). Code ranks top K nodes (overdue > in progress > due within 48h > pinned > severity > recency) within token budget (K=25 default). Stale nodes (>30 days inactive) demoted to Inbox. Append summary line: "N more open items not shown (M overdue)".
- **Files Touched**: `core/src/main/java/com/anchor/core/storage/Storage.kt`, `core/src/main/java/com/anchor/core/context/ContextBuilder.kt`, `app/src/main/java/com/anchor/mine/storage/AndroidStorage.kt`, `core/src/test/java/com/anchor/core/SharedFakeStorage.kt`, `core/src/test/java/com/anchor/core/context/ContextBuilderTest.kt`
- **Failing Test**: `testD104_ContextBuilder_CapsAtBudgetAndAppendsCount_WithLargeNodeSet` (`testContextBuilder_largeNodeSet_capsAtBudgetAndAppendsCount` does not exist), `testD104_StaleNodes_MoveToInbox` (`testStaleNodes_moveToInbox` does not exist)
- **Blueprint**: Blueprint 20 (Context Pack), Blueprint 13 (Nodes), D-104
- **Risks**: Token budget miscalculation; query performance on large node sets.
- **Size**: Medium (M)

### Task T-C: Alarm Tiers: Alarm vs Gentle (D-105)
- **Goal**: Introduce `AlarmTier.ALARM` (uses `setAlarmClock`, shows icon) and `AlarmTier.GENTLE` (notification only, inexact/exact alarm without clock icon). Store tier on `Reminder`. Disclose icon in settings.
- **Files Touched**: `core/src/main/java/com/anchor/core/models/Memory.kt`, `core/src/main/java/com/anchor/core/alarms/AlarmScheduler.kt`, `app/src/main/java/com/anchor/mine/alarms/AndroidAlarmScheduler.kt`, `app/src/main/java/com/anchor/mine/storage/MineDatabaseHelper.kt`
- **Failing Test**: `testReminder_ExplicitGentleTier` and `testGateway_ScheduleReminderWithGentleTier` (`testGentleReminder_schedulesNotificationOnly` does not exist), `testReminderTier_SaveAndReadAlarmAndGentle` in `AndroidStorageTest.kt` (`testAlarmTier_persistedInDatabase` does not exist)
- **Blueprint**: Blueprint 30 (Reminder Engine), Blueprint 42 (Android Reliability), D-105
- **Risks**: SQLite migration required if column added to `reminder` table. [HUMAN-GATED icon verification on device].
- **Size**: Medium (M)

### Task T-D: New Edge Type `follow_up` (D-106)
- **Goal**: Add `follow_up` edge type to schema, storage, models, and UiAction. Add migration and migration golden test.
- **Files Touched**: `core/src/main/java/com/anchor/core/models/Memory.kt`, `core/src/main/java/com/anchor/core/storage/Storage.kt`, `app/src/main/java/com/anchor/mine/storage/MineDatabaseHelper.kt`, `app/src/main/java/com/anchor/mine/storage/AndroidStorage.kt`, `core/src/test/java/com/anchor/core/SharedFakeStorage.kt`
- **Failing Test**: `testSaveAndQueryEdges_FollowUp` and `testStorage_EdgeCrudAndQuerying` (`testFollowUpEdgeType_persistedAndRetrieved` does not exist), `testDatabaseMigration_V2toV3_CreatesEdgeIndexes` (`testEdgeMigration` does not exist)
- **Blueprint**: Blueprint 13 (Nodes, Edges, Statuses), D-106
- **Risks**: Schema migration required if edge type is constrained in SQL.
- **Size**: Medium (M)

### Task T-E: Calendar Queries (D-107)
- **Goal**: Backend queries for calendar day view: day exists with messages, reminder dates per month, heatmap activity counts per day, reminders for a day. Tap opens day; long-press shows reminders.
- **Files Touched**: `core/src/main/java/com/anchor/core/storage/Storage.kt`, `app/src/main/java/com/anchor/mine/storage/AndroidStorage.kt`, `core/src/test/java/com/anchor/core/SharedFakeStorage.kt`, `core/src/main/java/com/anchor/core/ui/ChatContract.kt`
- **Failing Test**: `testStorage_CalendarQueries` and `testCalendarQueries_AndroidStorage` (`testCalendarQueries_heatMapCountsAndDayReminders` does not exist)
- **Blueprint**: Blueprint 11, Blueprint 50, UI Spec E6, D-107
- **Risks**: Query performance across large message history.
- **Size**: Medium (M)

### Task T-F: Home Quote Slots (D-108)
- **Goal**: Support 4 time slots (morning, afternoon, evening, night). Table `quote_slot(slot, day_key, text, generated_at, state_hash, source)`. Preset pool of generic quotes per slot; asynchronous live LLM quote replacement ~1h prior to slot switch. Max 2 regenerations per slot.
- **Files Touched**: `core/src/main/java/com/anchor/core/models/Quote.kt`, `core/src/main/java/com/anchor/core/storage/Storage.kt`, `app/src/main/java/com/anchor/mine/storage/MineDatabaseHelper.kt`, `app/src/main/java/com/anchor/mine/storage/AndroidStorage.kt`, `core/src/main/java/com/anchor/core/quotes/QuoteManager.kt`
- **Failing Test**: `testLiveQuoteGeneration_SwapsPreset_AndEnforcesMaxTwoRegens` (`testQuoteSlots_fallbackToPreset_andSwapLiveQuote` does not exist)
- **Blueprint**: Blueprint 40, Blueprint 32, D-108
- **Risks**: Background work scheduling, schema bump.
- **Size**: Large (L)
