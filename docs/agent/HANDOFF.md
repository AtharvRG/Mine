# HANDOFF (Mine v2 Call Nudge & Context Sensing Completed & Device Verified)

**Current Status**: 2026-11-11 20:55 IST
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
  - Added `testDatabaseMigration_V9toV10_CreatesCallEventTableAndIndexes` in `DatabaseMigrationTest.kt`.
  - Added `CallNudgeDeviceTest.kt` in `:app/src/androidTest/`.
- **Unit test suite green**:
  - `:core:test`: **542 / 542 PASS (0 failed, 0 errors, 0 skipped)** across 91 files.
  - `:app:testDebugUnitTest`: **27 / 27 PASS (0 failed, 0 errors, 0 skipped)** across 7 files.
  - Total offline unit suite: **569 / 569 PASS**.
- **Physical device (`moto g73 5G - Android 14` via adb USB `ZD2229P46Y`)**:
  - Connected test suite: **100% PASS** (`BUILD SUCCESSFUL in 3m 27s`).
  - `DatabaseMigrationTest` (covering V1..V10) passed.
  - `CallNudgeDeviceTest` passed.

---

## 1. What changed

| File | Status | What |
|---|---|---|
| `core/src/main/java/com/anchor/core/nudge/CallNudgeModels.kt` | added | Pure-Kotlin models: CallEvent, CallNudgeState, CallNudgeConfig, CallNudgeDecision |
| `core/src/main/java/com/anchor/core/nudge/CallNudgeEngine.kt` | added | Call nudge detection, coalescing, gating, expiry, and learning stop |
| `core/src/test/java/com/anchor/core/nudge/CallNudgeEngineTest.kt` | added | 7 unit tests verifying min duration, delay, coalescing, expiry, vehicle hold, 3-ignore stop, and evening recap |
| `core/src/main/java/com/anchor/core/settings/SettingsCatalog.kt` | modified | Added 9 call nudge configuration and dark-ship settings |
| `core/src/main/java/com/anchor/core/storage/Storage.kt` | modified | Added CallEvent persistence methods |
| `core/src/test/java/com/anchor/core/SharedFakeStorage.kt` | modified | In-memory implementation of call event storage |
| `app/src/main/java/com/anchor/mine/storage/MineDatabaseHelper.kt` | modified | Bumped DATABASE_VERSION to 10; added call_event DDL and idx_call_event_ended |
| `app/src/main/java/com/anchor/mine/storage/AndroidStorage.kt` | modified | Implemented CallEvent SQLite queries and 30-day pruning |
| `app/src/main/java/com/anchor/mine/alarms/NotificationChannels.kt` | modified | Added CALL_NUDGE channel with zero vibration |
| `app/src/main/java/com/anchor/mine/nudge/CallNudgeNotifications.kt` | added | Post-call notifications with Reply, Voice note, Skip, and lockscreen privacy |
| `app/src/main/java/com/anchor/mine/nudge/CallStateReceiver.kt` | added | Off-hook to idle telephony receiver dispatching to CallNudgeEngine |
| `app/src/main/java/com/anchor/mine/nudge/CallNudgeActionReceiver.kt` | added | RemoteInput and Skip action receiver updating CallEvent state |
| `app/src/main/AndroidManifest.xml` | modified | Declared READ_PHONE_STATE permission and registered call receivers |
| `app/src/androidTest/java/com/anchor/mine/storage/DatabaseMigrationTest.kt` | modified | Added `testDatabaseMigration_V9toV10_CreatesCallEventTableAndIndexes` |
| `app/src/androidTest/java/com/anchor/mine/nudge/CallNudgeDeviceTest.kt` | added | Device test verifying notifications, actions, and zero vibration on hardware |
| `docs/agent/DECISIONS.md` | modified | Recorded D-178 |
| `docs/agent/STATE.md` | modified | Updated state and test counts |
| `docs/agent/TASKS.md` | modified | Marked v2 Item 2 complete |
| `docs/agent/work/v2-call-nudge.md` | added | Branching analysis document for v2 call nudge |

---

## 2. What is verified

| Claim | Command | Result |
|---|---|---|
| CallNudgeEngine unit suite | `./gradlew :core:test --tests "com.anchor.core.nudge.CallNudgeEngineTest"` | 7/7 PASS |
| SettingsCatalog call nudge definitions | `./gradlew :core:test --tests "com.anchor.core.settings.SettingsCatalogTest"` | 5/5 PASS |
| Core unit test suite | `./gradlew :core:test` | 542/542 PASS |
| App unit test suite | `./gradlew :app:testDebugUnitTest` | 27/27 PASS |
| CallNudgeDeviceTest on device | `./gradlew :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.anchor.mine.nudge.CallNudgeDeviceTest"` | PASS on `moto g73 5G` |
| DatabaseMigrationTest on device | `./gradlew :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.anchor.mine.storage.DatabaseMigrationTest"` | PASS on `moto g73 5G` |
| Full connected Android test suite | `./gradlew :app:connectedDebugAndroidTest` | 100% PASS on `moto g73 5G - Android 14` (3m 27s) |

---

## 3. What is NOT verified

- All currently implemented features through v2 Item 2 are 100% verified on unit and physical device hardware.
- Upcoming next item: v2 Item 3 (Full Ambient Listening & Voice Interactivity per Blueprints 60, 61).

---

## 4. Next steps

1. **Build v2 Item 3: Full Ambient Listening & Voice Interactivity (`docs/blueprints/60-voice-stt-tts.md`, `docs/blueprints/61-ambient-listening.md`)**:
   - Audio capture with 16 kHz in-memory recording buffer.
   - Interactive microphone button in `ChatScreen` composer.
   - Transcript preview & edit sheet before sending turn.
   - Streaming Kokoro TTS playback with audio focus management.
