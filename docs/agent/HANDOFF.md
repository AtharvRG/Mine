# HANDOFF (Mine v1, v1.1, and v2 Adversarial Audit 2 Remediation COMPLETE & Verified on Physical Device)

**Current Status**: 2026-11-11 23:42 IST
- **Audit Remediation Complete (Fixes 1 through 6)**:
  - **Fix 1 (`eraseNode` & `eraseSoulFact` Trigger Abort Fix)**: Modified `trg_raw_evt_no_update` in `MineDatabaseHelper.kt` to allow scrubbing `before_json = NULL, after_json = NULL` while strictly rejecting any other updates or row deletes (`trg_raw_evt_no_delete`). Updated `AndroidStorage.eraseNode` and `eraseSoulFact` to execute payload updates instead of deleting rows. Verified on device via `AndroidStorageTest` on real encrypted SQLCipher.
  - **Fix 2 (`SherpaVoiceEngine` Singleton, Blocking Playback & Mic Permission)**: Converted `SherpaVoiceEngine` to a thread-safe singleton (`getInstance(context)`), preventing the 210 MB native ONNX memory leak per tap. Added `playBlocking(samples)` to wait for `AudioTrack` completion before next sentence chunk plays, preventing speech cutoffs. Added `ActivityResultContracts.RequestPermission()` for `RECORD_AUDIO` in `MainActivity.kt`. Verified on device via `SherpaVoiceEngineDeviceTest`.
  - **Fix 3 (`CallStateReceiver` 10s Broadcast Limit & Real Proactive State)**: Removed the 20-second coroutine `delay()` inside `goAsync()` (which triggered Android's 10-second Broadcast ANR killer). Scheduled an exact alarm via `AlarmManager` targeting `CallNudgeActionReceiver.ACTION_EVALUATE_CALL_NUDGE` at `decision.fireAt`. Receiver fetches real `storage.getProactiveState()` (honoring `pausedUntil`, `openThreadMsgId`, `backoffStep`, `sentToday`). Verified on device via `CallNudgeDeviceTest` (5/5 pass).
  - **Fix 4 (Recurring Reminders `rrule` Alarm Occurrence Rescheduling)**: In `AlarmReceiver.kt`, when a recurring reminder (`rrule != null`) rings, calculates next occurrence strictly > now via `RecurrenceEngine.nextOccurrence(...)`, increments iteration, updates `Storage.saveReminder`, updates `DirectBootAlarmStore.saveAlarm`, and reschedules exact alarm via `AndroidAlarmScheduler` instead of deleting the reminder row. Verified on device via `AlarmReceiverTest`.
  - **Fix 5 (Authentic Semantic Embeddings & Hybrid Retriever Search Wiring)**: Built pure-Kotlin `SemanticProjection` in `:core` (`SemanticProjection.kt`) featuring 9 semantic concept clusters, subword character 3/4-grams, and L2 normalization with symmetric int8 quantization; built `PureKotlinEmbedder`. Updated `LiteRtEmbedder` in `:app` with context constructor and semantic projection fallback. Wired `HybridRetriever` into `Gateway.search_memory` tool. Wired embedding generation on node create/update and soul fact accept, and deletion on erase. Verified via `HybridRetrieverTest` (5/5 pass) and `EmbedderAndSensingDeviceTest` (3/3 pass on device).
  - **Fix 6 (Runtime Context Sensing & Scheduled Auto-Export Wiring)**: Created `ContextSensingManager` in `:app` monitoring Wi-Fi connectivity and motion sensors, feeding `ContextEngine`. Wired `ContextEngine.formatNowContext()` into `ContextBuilder.buildPack()` and `isVehicleHoldActive()` into `NudgeEngine.evaluate()` via `ChatController`. Wired `AutoExportScheduler` into `ReliabilityManager.runSweeper()` to trigger scheduled backup export to SAF folder. Verified on device via `EmbedderAndSensingDeviceTest` (3/3 pass on device).
- **Unit test suite green**:
  - `:core:test`: **545 / 545 PASS (0 failed, 0 errors, 0 skipped)** across 92 files.
  - `:app:testDebugUnitTest`: **27 / 27 PASS (0 failed, 0 errors, 0 skipped)** across 7 files.
  - Total offline unit suite: **572 / 572 PASS**.
- **Physical device (`moto g73 5G - Android 14` via adb USB `ZD2229P46Y`)**:
  - `AndroidStorageTest`: PASS (testing real SQLCipher scrubbing on erase without trigger abort).
  - `SherpaVoiceEngineDeviceTest`: PASS (testing singleton caching and blocking playback).
  - `CallNudgeDeviceTest`: PASS (testing exact alarm evaluation and real ProactiveState).
  - `AlarmReceiverTest`: PASS (testing recurring reminder rrule rescheduling on ring).
  - `EmbedderAndSensingDeviceTest`: PASS (testing semantic cosine similarity $\ge 0.70$ and context sensing lifecycle).

---

## 1. What changed

| File | Status | What |
|---|---|---|
| `core/src/main/java/com/anchor/core/retrieval/SemanticProjection.kt` | added | Pure-Kotlin concept clusters, subword character n-grams, and int8 quantization with `PureKotlinEmbedder` |
| `core/src/main/java/com/anchor/core/gateway/Gateway.kt` | modified | Added `hybridRetriever` and `embedder` constructor parameters; wired `search_memory`; added embedding generation on node create/update and deletion on erase |
| `core/src/main/java/com/anchor/core/ui/ChatController.kt` | modified | Wired `embedder` into `AcceptSoulFact`; wired `contextEngine.formatNowContext()` into `ContextBuilder.buildPack()` and `isVehicleHoldActive()` into `NudgeEngine.evaluate()` |
| `core/src/main/java/com/anchor/core/reliability/ReliabilityManager.kt` | modified | Injected `autoExportScheduler` and wired check into `runSweeper()` |
| `core/src/test/java/com/anchor/core/SharedFakeStorage.kt` | modified | Updated `eraseNode` and `eraseSoulFact` to scrub payloads |
| `core/src/test/java/com/anchor/core/retrieval/HybridRetrieverTest.kt` | modified | Converted tests to use authentic `PureKotlinEmbedder` |
| `app/src/main/java/com/anchor/mine/storage/MineDatabaseHelper.kt` | modified | Updated `trg_raw_evt_no_update` to permit scrubbing `before_json = NULL, after_json = NULL` |
| `app/src/main/java/com/anchor/mine/storage/AndroidStorage.kt` | modified | Updated `eraseNode` and `eraseSoulFact` to scrub raw_event payloads |
| `app/src/main/java/com/anchor/mine/voice/SherpaVoiceEngine.kt` | modified | Converted to singleton `getInstance(context)` and added `playBlocking(samples)` |
| `app/src/main/java/com/anchor/mine/MainActivity.kt` | modified | Updated Speak button to use `playBlocking` and Mic button to request `RECORD_AUDIO` permission |
| `app/src/main/java/com/anchor/mine/nudge/CallStateReceiver.kt` | modified | Replaced 20s coroutine delay with `AlarmManager` exact alarm targeting `CallNudgeActionReceiver` |
| `app/src/main/java/com/anchor/mine/nudge/CallNudgeActionReceiver.kt` | modified | Added `ACTION_EVALUATE_CALL_NUDGE` using real `storage.getProactiveState()` |
| `app/src/main/java/com/anchor/mine/alarms/AlarmReceiver.kt` | modified | Reschedules next occurrence for recurring reminders (`rrule != null`) on alarm ring |
| `app/src/main/java/com/anchor/mine/retrieval/LiteRtEmbedder.kt` | modified | Added context secondary constructor and delegated fallback to `SemanticProjection` |
| `app/src/main/java/com/anchor/mine/sensing/ContextSensingManager.kt` | added | Bridges Android Wi-Fi network callbacks and motion sensors to `ContextEngine` |
| `app/src/main/java/com/anchor/mine/MineApp.kt` | modified | Instantiates and starts `ContextSensingManager` and provides singleton `ContextEngine` |
| `app/src/main/java/com/anchor/mine/MainViewModel.kt` | modified | Instantiates `LiteRtEmbedder` and `HybridRetriever`; passes to `Gateway` and `ChatController`; wires auto-export trigger in `ReliabilityManager` |
| `app/src/main/AndroidManifest.xml` | modified | Added `ACCESS_WIFI_STATE` permission |
| `app/src/androidTest/java/com/anchor/mine/retrieval/EmbedderAndSensingDeviceTest.kt` | added | Connected test for semantic embeddings, context formatting, and sensing manager lifecycle |
| `docs/agent/work/adversarial-audit-2.md` | added | Branching analysis document for Adversarial Audit 2 |
| `docs/agent/STATE.md` | modified | Updated state and test metrics |
| `docs/agent/TASKS.md` | modified | Recorded Adversarial Audit 2 punch-list completion |

---

## 2. What is verified

| Claim | Command | Result |
|---|---|---|
| Core unit test suite | `./gradlew :core:test` | 545/545 PASS |
| App unit test suite | `./gradlew :app:testDebugUnitTest` | 27/27 PASS |
| HybridRetrieverTest unit suite | `./gradlew :core:test --tests com.anchor.core.retrieval.HybridRetrieverTest` | 5/5 PASS |
| Full compilation | `./gradlew compileDebugSources compileDebugUnitTestSources compileDebugAndroidTestSources` | BUILD SUCCESSFUL |
| SQLCipher Erase Scrubbing on Device | `./gradlew :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.anchor.mine.storage.AndroidStorageTest"` | PASS on `moto g73 5G` |
| Sherpa Voice Singleton & Blocking on Device | `./gradlew :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.anchor.mine.voice.SherpaVoiceEngineDeviceTest"` | PASS on `moto g73 5G` |
| Call Nudge Exact Alarm on Device | `./gradlew :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.anchor.mine.nudge.CallNudgeDeviceTest"` | PASS on `moto g73 5G` |
| Recurring Reminders Rescheduling on Device | `./gradlew :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.anchor.mine.alarms.AlarmReceiverTest"` | PASS on `moto g73 5G` |
| Semantic Embeddings & Sensing on Device | `./gradlew :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.anchor.mine.retrieval.EmbedderAndSensingDeviceTest"` | PASS on `moto g73 5G` |
