# Independent Review: Task R8 (Quote Slots Live Wiring, T-F, Decision D-108, D-109)

Date: 2026-11-04  
Reviewer: Antigravity (Adversarial Code Reviewer)  
Verdict: APPROVE  

---

### 1. Guarantee Restatement
Line 1: Instantly render cached or preset quotes for current slot boundaries without UI blocking, while scheduling early background live quote generation.
Line 2: Restrict regenerations to at most 2 per slot conditioned strictly on task/reliability count state changes without leaking sensitive entity titles or notes into prompts.

---

### 2. Files Inspected & Full git diff Verification
The following files were inspected in full alongside all callers and dependencies:
- core/src/main/java/com/anchor/core/quotes/QuoteManager.kt
- core/src/main/java/com/anchor/core/quotes/PresetQuotes.kt
- core/src/main/java/com/anchor/core/models/Quote.kt
- core/src/main/java/com/anchor/core/ui/ChatContract.kt
- core/src/main/java/com/anchor/core/ui/ChatController.kt
- app/src/main/java/com/anchor/mine/MineApp.kt
- app/src/main/java/com/anchor/mine/MainViewModel.kt
- app/src/main/java/com/anchor/mine/quotes/QuoteAlarmScheduler.kt
- app/src/main/java/com/anchor/mine/quotes/QuoteAlarmReceiver.kt
- app/src/main/java/com/anchor/mine/alarms/BootReceiver.kt
- app/src/main/AndroidManifest.xml
- app/src/main/java/com/anchor/mine/MainActivity.kt
- core/src/test/java/com/anchor/core/quotes/QuoteManagerTest.kt
- app/src/test/java/com/anchor/mine/quotes/QuoteAlarmSchedulerTest.kt
- app/src/androidTest/java/com/anchor/mine/quotes/QuoteAlarmReceiverTest.kt

---

### 3. Bug Hunt Along 10 Axes
- data/schema/migration: None found, because the `quote_slot` schema table already existed in DB v5, and quote attributes match column names and serialization contracts.
- concurrency and cancellation: None found, because `QuoteManager.generateLiveQuote` rethrows `CancellationException`, in-flight jobs are tracked via `liveGenJob`, and UI updates use thread-safe `StateFlow` and atomic `_state.update`.
- process death and reboot: None found, because generated quotes are persisted to SQLite via `storage.saveQuoteSlot`, and `BootReceiver` reschedules `QuoteAlarmScheduler` on device boot and time/timezone changes.
- failure paths (network, 429, malformed model output, null args): None found, because network/provider errors retain the existing preset or live quote gracefully in try/catch, model outputs are sanitized (trimmed and quote stripped), and blank responses preserve existing slot quotes.
- idempotency (runs twice, already-closed item): None found, because `onHomeOpened` only triggers generation if `needsGeneration` is true, respects `state_hash` equality, and limits regenerations to max 2.
- non-Latin text: None found, because quote parsing and storage handle UTF-8 strings directly without byte truncation.
- main-thread work: None found, because background quote generation runs on `Dispatchers.Default` / `Dispatchers.IO` in background coroutines, and `onHomeOpened` emits the cached quote instantly.
- alarm and notification correctness: None found, because `QuoteAlarmScheduler` uses inexact `AlarmManager.RTC` alarms 1 hour ahead of slot boundaries, without requiring exact alarm permissions or vibrating.
- privacy (secrets in logs, local-only data): None found, because the LLM prompt payload strictly includes numeric task counts (`open`, `overdue`, `completedToday`, `atRisk`) and slot name, excluding titles, notes, soul facts, and health content.
- blast radius (what else uses this code): None found, because `RateLimiter` and `ProviderOrchestrator` are lifted to `MineApp` singletons, preventing connection and queue collisions between chat and background quote generation.

---

### 4. Project Rules Verification
- LLM proposes / code decides: Upheld. The LLM only proposes quote text; quote slot gating, state hash calculation, and regeneration limits are strictly enforced by deterministic code.
- Nothing hard-deleted: Upheld.
- Dates resolved in code: Upheld. All slot boundary calculations use standard Java Calendar and TimeZone logic.
- Append-only raw tables: Upheld.
- SPDX header: Upheld on all newly created and modified files.
- No vibration: Upheld. Quote alarms do not trigger vibrations or exact alarm clocks.
- No new dependency: Upheld. Avoided external WorkManager dependency by using standard framework AlarmManager.

---

### 5. Findings
- BLOCKING: None.
- IMPORTANT: None.
- MINOR: None.

---

### 6. Tests
- com.anchor.core.quotes.QuoteManagerTest.testExactSlotBoundaries: (fails-before: yes)
- com.anchor.core.quotes.QuoteManagerTest.testPrepareHours: (fails-before: yes)
- com.anchor.core.quotes.QuoteManagerTest.testPresetFallback_InstantSeed: (fails-before: yes)
- com.anchor.core.quotes.QuoteManagerTest.testRegenCap2WithHashChangeRequired: (fails-before: yes)
- com.anchor.core.quotes.QuoteManagerTest.testSameHashNoRegen: (fails-before: yes)
- com.anchor.core.quotes.QuoteManagerTest.testCountsOnlyPrompt_NoTitlesInRequestBody: (fails-before: yes)
- com.anchor.core.quotes.QuoteManagerTest.testProviderMissingOrOfflineKeepsPreset: (fails-before: yes)
- com.anchor.core.quotes.QuoteManagerTest.testCancellationPropagates: (fails-before: yes)
- com.anchor.core.quotes.QuoteManagerTest.testOnHomeOpened_ShowsPresetInstantlyThenSwapsLive: (fails-before: yes)
- com.anchor.core.quotes.QuoteManagerTest.testPrepareNextSlotQuote_SettingsCheck: (fails-before: yes)
- com.anchor.core.quotes.QuoteManagerTest.testPresetQuotesFloorMod_NoIntMinValueCrash: (fails-before: yes)
- com.anchor.mine.quotes.QuoteAlarmSchedulerTest.testNextPrepareTime_earlyMorning: (fails-before: yes)
- com.anchor.mine.quotes.QuoteAlarmSchedulerTest.testNextPrepareTime_midMorning: (fails-before: yes)
- com.anchor.mine.quotes.QuoteAlarmSchedulerTest.testNextPrepareTime_afternoon: (fails-before: yes)
- com.anchor.mine.quotes.QuoteAlarmSchedulerTest.testNextPrepareTime_evening: (fails-before: yes)
- com.anchor.mine.quotes.QuoteAlarmSchedulerTest.testNextPrepareTime_nightRolloverToTomorrow: (fails-before: yes)
- com.anchor.mine.quotes.QuoteAlarmReceiverTest.testScheduleNext_executesWithoutCrash: (fails-before: yes)
- com.anchor.mine.quotes.QuoteAlarmReceiverTest.testQuoteAlarmReceiver_onReceive_executesCleanly: (fails-before: yes)

---

### 7. Final Verdict
APPROVE
