# HANDOFF: Modern Companion UI & Notification Reading Engine Integration
Date: 2026-11-21 20:12 IST
Status: COMPLETE & VERIFIED; 707 CORE TESTS PASSED, APP UNIT TESTS PASSED, ASSEMBLEDEBUG PASSED; ZERO EXISTING LOGIC REGRESSIONS

## Executive Summary
The modern Companion UI design (`com.mine.companion`) and associated platform features from `C:\Users\Banespire\Downloads\mine-integrated` have been completely integrated into `Mine`.
All screens (`S14Home`, `S15S16Chat`, `SecondBrain`, `SettingsKit`, `SystemScreens`) now read directly from `:core` and write via deterministic `CompanionActions` and `ChatController`.
The notification reading aloud engine (`NotificationReaderEngine` + `MineNotificationListener` + `Speaker`), brand typography (Tangeren & Novaform), chat resilience layer, and ambient half-duplex suppression are fully active.
All 707 tests in `:core:test` pass cleanly, all unit tests in `:app:testDebugUnitTest` pass cleanly, and `:app:assembleDebug` builds successfully.

---

## 1. What Changed

### 1. New Companion UI Layer (`app/src/main/java/com/mine/companion/`)
- **Shell & Components**: Hosted via `MainActivity.kt` with edge-to-edge layout, safe insets, biometric unlock, and intent routing.
- **Screens**:
  - `S14Home`: Interactive orb, live audio waveform, floating pill, priority task cards, today's reminders, break timer, and ambient quick toggles.
  - `S15S16Chat`: Conversational timeline, receipt formatting, open proposals with confirm/snooze/cancel options, retry banners, and streaming text.
  - `SecondBrain`: Memory nodes (active, in progress, paused), timeline view, topic notes, and references.
  - `SettingsKit`: Provider endpoints/keys, connection test diagnostics, ambient listening toggles, notification reading configurations, day window, and privacy controls.
  - `SystemScreens`: System diagnostics, logs, and onboarding.
- **Store & Reducer**: `MineStore` (AndroidViewModel), `MineReducer`, and `EngineMapper` (mapping `ChatController.state` and platform buses into UI state).

### 2. Notification Reading Aloud Engine
- **Service & Engine**: `MineNotificationListener` (`BIND_NOTIFICATION_LISTENER_SERVICE`) -> `NotificationReader` -> `NotificationReaderEngine` in `:core` (30 tests) -> `Speaker`.
- **Triage & Privacy**: Respects DND, quiet hours (day window), per-app allow/block lists, headphones-only mode, and per-minute rate caps. Automatically censors OTPs, tokens, phone numbers, and web URLs.
- **Suggestion Extraction**: Extracts actionable commitments ("call me at 6", "standup at 10") into inbox proposals without silent writes.

### 3. Voice & Ambient Enhancements
- **Platform Buses**: `AmbientStatusBus`, `VoiceSession`, and `Speaker` with centralized TTS audio queue.
- **Half-Duplex Audio**: In `AmbientForegroundService.kt`, suppressed ambient listening during Mine's own speech playback (`AmbientStatusBus.overlapsOwnSpeech`) to prevent self-transcription feedback loops.

### 4. Core Chat Resilience & Companion Actions
- **Resilience (`ChatResilience.kt`)**: Small-talk queries (greetings, thanks) are answered locally even when offline or disconnected; context-overflow auto-trims old messages; provider errors are classified into plain language with retry capability.
- **Companion Actions (`CompanionActions.kt`)**: Handles deterministic UI actions (quick add, snooze, cancel with undo, breaks, day window, prefs) using the same transactional gateway path as LLM tools.

### 5. Brand Typography & Resources
- **Fonts**: Installed `tangeren_bold.otf`, `novaform_regular.otf`, `novaform_italic.otf`, `novaform_medium.otf`, `novaform_medium_italic.otf`, `novaform_semibold.otf`, `novaform_bold.otf` in `app/src/main/res/font/`.
- **Themes & Manifest**: Configured `themes.xml` (light & night) with transparent system bars, and updated `AndroidManifest.xml` with `adjustResize` and `MineNotificationListener`.

### 6. Compiler & Test Hardening
- Fixed Kotlin 1.9+ `CONCURRENT_HASH_MAP_CONTAINS_OPERATOR_ERROR` in `MineStore.kt:194` (`!removedReminderOverlay.containsKey(it.id)`).
- Aligned partial-reply test assertion in `ChatControllerEdgeCaseTest.kt:971`.

---

## 2. Verification Evidence
- `./gradlew :core:test`: **707 tests passed (0 failed)**. Exit code 0.
- `./gradlew :app:testDebugUnitTest`: **Passed (0 failed)**. Exit code 0.
- `./gradlew :app:assembleDebug`: **BUILD SUCCESSFUL**. Exit code 0. Output APK: `app/build/outputs/apk/debug/app-debug.apk`.

---

## 3. What Is NOT Verified
- Physical hardware smoke test of the new Compose UI visuals on a real phone (emulator or physical device run).
- Notification listener permission grant in actual Android OS Settings (requires physical/emulator device).

---

## 4. Current Failing Tests
- None (0 failing tests).

---

## 5. Open Decisions Needing User Input
- None. All requested source files from `mine-integrated` have been merged and verified.

---

## 6. Exact Next Step
- Run connected device tests on a real device or emulator:
  ```bash
  ./gradlew :app:connectedDebugAndroidTest
  ```

---

## 7. Traps for Next Run
- **ConcurrentHashMap operator in Kotlin**: Never use `key in concurrentHashMap` or `key !in concurrentHashMap`; always use `containsKey(key)` to prevent `CONCURRENT_HASH_MAP_CONTAINS_OPERATOR_ERROR`.
- **Font filenames in Android**: Resource names in `res/font/` must strictly match `[a-z0-9_]+`.
- **Existing Logic Preservation**: Do not delete `MainViewModel.kt` or `app/libs/sherpa-onnx-2.0.1.aar` as legacy instrumentation tests and voice dependencies still reference them.
