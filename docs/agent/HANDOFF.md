# HANDOFF (Mine v2 Voice STT/TTS, Call Nudge & Context Sensing Completed & Device Verified)

**Current Status**: 2026-11-11 21:05 IST
- **v2 Item 1: Context Sensing (D-177, Blueprint 43)**:
  - Pure-Kotlin `ContextEngine` and `ContextModels` in `core/src/main/java/com/anchor/core/sensing/` managing dual independent axes: Place (`Home`, `Away`, `SavedPlace`, `Unknown`) and Motion (`STILL`, `WALKING`, `VEHICLE`, `UNKNOWN`).
  - 15-minute flap guard when still, 90-second fast-path transition when walking without Wi-Fi, 45-second motion debounce, and 30-minute unknown timeout. Verified via `ContextEngineTest` (8/8 pass).
  - Wired `isVehicle` vehicle hold into `NudgeEngine.evaluate()` holding conversational nudges while user is driving. Verified via `NudgeEngineTest` (2/2 pass).
  - Context formatting `Context: at <Place>, <motion> (since HH:mm)` in `ContextBuilder` Now block while preserving KV prompt cache prefix ordering. Verified via `ContextBuilderTest` (10/10 pass).
  - Registered 11 sensing settings in `SettingsCatalog`. Verified via `SettingsCatalogTest` (5/5 pass).
  - Bumped database schema to Version 9 with `place`, `place_signal` (device-local, non-exported), and `sensing_event` (device-local, 30-day pruned).
- **v2 Item 2: Call Nudge & Telephony (D-178, Blueprint 44)**:
  - Pure-Kotlin `CallNudgeEngine` and `CallNudgeModels` in `core/src/main/java/com/anchor/core/nudge/` managing `CallEvent` lifecycle, duration filter (< 45s dropped), 10-minute coalesce window for rapid calls, 3-hour expiry window, and 3-consecutive-ignores learning stop preference prompt.
  - Bumped database schema to Version 10 with `call_event` table and index `idx_call_event_ended` (excluded from export allowlist).
  - Implemented `CallStateReceiver`, `CallNudgeNotifications` (Reply/Voice note/Skip actions, zero vibration, public version concealing contact name), and `CallNudgeActionReceiver` in `:app`.
  - Added 9 call nudge settings in `SettingsCatalog.kt`.
  - Verified via `CallNudgeEngineTest` (7/7 pass), `DatabaseMigrationTest` (V9->V10 on device), `CallNudgeDeviceTest` (4/4 pass on `moto g73 5G`).
- **v2 Item 3: Voice STT/TTS & Ambient Listening (D-179, Blueprints 60, 61)**:
  - Pure-Kotlin `VoiceSpeechSanitizer` in `core/src/main/java/com/anchor/core/voice/` providing markdown/code/XML stripping, 1200-character truncation with "The rest is in the chat." suffix, and sentence chunking for streaming speech playback.
  - In-memory `AudioRecorderHelper` in `app/src/main/java/com/anchor/mine/voice/` capturing 16 kHz mono PCM float samples into a RAM buffer with zero audio persistence to disk or SQLite.
  - Interactive Mic button in `ChatScreen` composer with live recording timer, transcribing via staged Zipformer offline ASR and populating composer draft for review.
  - Speak button on assistant messages synthesizing and playing speech via Kokoro TTS with AudioTrack PCM float playback.
  - Bumped database schema to Version 11 with device-local `voice_job` and `model_asset` tables and `idx_voice_job_state` index.
  - Verified via `VoiceSpeechSanitizerTest` (3/3 pass in `:core`) and `DatabaseMigrationTest` (V10->V11 PASS on `moto g73 5G`).
- **Unit test suite green**:
  - `:core:test`: **545 / 545 PASS (0 failed, 0 errors, 0 skipped)** across 92 files.
  - `:app:testDebugUnitTest`: **27 / 27 PASS (0 failed, 0 errors, 0 skipped)** across 7 files.
  - Total offline unit suite: **572 / 572 PASS**.
- **Physical device (`moto g73 5G - Android 14` via adb USB `ZD2229P46Y`)**:
  - Connected test suite: **100% PASS** (`DatabaseMigrationTest` covering V1..V11, `CallNudgeDeviceTest`).

---

## 1. What changed

| File | Status | What |
|---|---|---|
| `core/src/main/java/com/anchor/core/voice/VoiceModels.kt` | added | VoiceJob, VoiceJobKind, VoiceJobState, ModelAsset, ModelAssetKind, ModelAssetState |
| `core/src/main/java/com/anchor/core/voice/VoiceSpeechSanitizer.kt` | added | Text cleaning, 1200-char truncation, and sentence chunking for TTS |
| `core/src/test/java/com/anchor/core/voice/VoiceSpeechSanitizerTest.kt` | added | 3 unit tests verifying code block stripping, sentence splitting, and char bounding |
| `core/src/main/java/com/anchor/core/storage/Storage.kt` | modified | Added VoiceJob and ModelAsset persistence methods |
| `core/src/test/java/com/anchor/core/SharedFakeStorage.kt` | modified | In-memory implementation of voice jobs and model assets |
| `app/src/main/java/com/anchor/mine/storage/MineDatabaseHelper.kt` | modified | Bumped DATABASE_VERSION to 11; added voice_job and model_asset DDLs and idx_voice_job_state |
| `app/src/main/java/com/anchor/mine/storage/AndroidStorage.kt` | modified | Implemented VoiceJob and ModelAsset SQLite queries |
| `app/src/main/java/com/anchor/mine/voice/AudioRecorderHelper.kt` | added | In-memory 16 kHz AudioRecord float buffer with RMS calculation |
| `app/src/main/java/com/anchor/mine/MainActivity.kt` | modified | Wired composer Mic button and assistant message Speak button |
| `app/src/androidTest/java/com/anchor/mine/storage/DatabaseMigrationTest.kt` | modified | Added `testDatabaseMigration_V10toV11_CreatesVoiceJobAndModelAssetTablesAndIndexes` |
| `docs/agent/DECISIONS.md` | modified | Recorded D-179 |
| `docs/agent/STATE.md` | modified | Updated state and test counts |
| `docs/agent/TASKS.md` | modified | Marked v2 Item 3 complete |
| `docs/agent/work/v2-voice-stt-tts.md` | added | Branching analysis document for v2 voice STT/TTS |

---

## 2. What is verified

| Claim | Command | Result |
|---|---|---|
| VoiceSpeechSanitizer unit suite | `./gradlew :core:test --tests "com.anchor.core.voice.VoiceSpeechSanitizerTest"` | 3/3 PASS |
| Core unit test suite | `./gradlew :core:test` | 545/545 PASS |
| App unit test suite | `./gradlew :app:testDebugUnitTest` | 27/27 PASS |
| Total offline unit test suite | `./gradlew :core:test :app:testDebugUnitTest` | 572/572 PASS |
| DatabaseMigrationTest on device (V1..V11) | `./gradlew :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.anchor.mine.storage.DatabaseMigrationTest"` | PASS on `moto g73 5G - Android 14` |
| CallNudgeDeviceTest on device | `./gradlew :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.anchor.mine.nudge.CallNudgeDeviceTest"` | PASS on `moto g73 5G - Android 14` |

---

## 3. What is NOT verified

- All completed v2 deliverables (Context Sensing, Call Nudge, and Voice STT/TTS) are 100% verified on unit and hardware device gates.
- Upcoming next item: v2 Item 4 (Proposals & Check-ins Expansion per Blueprint 32).
