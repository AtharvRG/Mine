# HANDOFF: Onboarding Completion & Voice Mode Crash Remediation
Date: 2026-11-22 10:15 IST
Status: COMPLETE & VERIFIED; 756 CORE TESTS PASSED, 53 APP UNIT TESTS PASSED, ASSEMBLEDEBUG BUILD SUCCESSFUL

## Executive Summary
Two runtime issues reported during physical device testing on Moto G73 5G have been diagnosed and resolved:
1. **Onboarding Completion Crash & Prefilled Restart**:
   - In `ChatController.kt:OnboardingFinish`, calculating `today` via `DayRollover.safeTimeZone(...).toZoneId()` was vulnerable to `ZoneRulesException` on devices using regional timezone abbreviations (e.g., IST). When an exception occurred, `storage.saveSetting(ONBOARDING_DONE_KEY, "1")` was never reached, causing the app to restart into onboarding on the next boot (with previously saved fields prefilled).
   - ZoneId resolution now includes a robust fallback chain (`ZoneId.of(tz.id, ZoneId.SHORT_IDS)` -> `ZoneId.systemDefault()`), and `MineStore.kt` state merge collection now uses atomic `_state.update` to prevent race conditions during the screen navigation to Home (`ScreenId.S14`).
2. **Voice Session & Hands-Free Screen Crash**:
   - Android 11+ package visibility was blocking system speech recognition discovery; added `<intent><action android:name="android.speech.RecognitionService" /></intent>` inside `<queries>` in `AndroidManifest.xml`.
   - In `VoiceSession.kt`, guarded `SpeechRecognizer.createSpeechRecognizer(ctx)` and listening lifecycle against null recognizers and OEM service binding exceptions.
   - Guarded AGSL runtime shaders (`ORB_AGSL` in `Orb.kt` and `PULSING_BORDER_AGSL` in `PulsingBorder.kt`) with safe fallback handling to smooth Canvas-based rendering (`OrbFallback`, `PulsingBorderFallback`) if a device GPU driver encounters compilation or uniform binding issues. Corrected vector subtraction in `ORB_AGSL` (`float3(1.0) - ...`).

All 756 pure Kotlin tests in `:core:test` pass with 0 failures.
All 53 Android unit tests in `:app:testDebugUnitTest` pass with 0 failures.
The debug build `:app:assembleDebug` succeeds cleanly with the output APK generated at `app/build/outputs/apk/debug/app-debug.apk`.

---

## 1. What Changed

### 1. `core/src/main/java/com/anchor/core/ui/ChatController.kt`
- In `UiAction.OnboardingFinish`:
  - Resolved `ZoneId` using fallback mechanisms to avoid `ZoneRulesException` on devices with non-standard or abbreviated timezone strings.
  - Ensured identity facts creation and `storage.saveSetting(ONBOARDING_DONE_KEY, "1")` succeed reliably.

### 2. `app/src/main/AndroidManifest.xml`
- Added `<action android:name="android.speech.RecognitionService" />` inside `<queries>` so Android 11+ package visibility allows querying and binding system speech recognition.

### 3. `app/src/main/java/com/anchor/mine/voice/VoiceSession.kt`
- In `listenOnce`: wrapped speech recognizer calls in `runCatching` so missing offline models or system recognizer errors never crash the session.
- In `listenWithSystem`: safely instantiated `SpeechRecognizer.createSpeechRecognizer(ctx)` with null-check and guarded listener registration and execution.

### 4. `app/src/main/java/com/mine/companion/store/MineStore.kt`
- Switched state merge collector to `_state.update { prev -> merge(prev, engine, dev) }` ensuring atomic state transitions during navigation away from onboarding.

### 5. `app/src/main/java/com/mine/companion/ui/fx/Orb.kt` & `PulsingBorder.kt`
- In `Orb33`: wrapped `PerlinTexture.get()` and `RuntimeShader(ORB_AGSL)` in `runCatching`, falling back to `OrbFallback` if shader compilation fails. In `drawWithCache`, guarded uniform setters and `drawRect` with fallback to `drawCircle`.
- In `PulsingBorder33`: wrapped `RuntimeShader(PULSING_BORDER_AGSL)` in `runCatching`, falling back to `PulsingBorderFallback` if shader compilation fails. In `drawWithCache`, guarded uniform setters with fallback to `drawRoundRect`.

### 6. `app/src/main/java/com/mine/companion/ui/fx/ShaderSources.kt`
- Fixed vector arithmetic in `ORB_AGSL` line 459: `float3(1.0) - (float3(1.0) - color.rgb) * (float3(1.0) - ringColor * totalRingAlpha)` to comply with strict GLSL/SkSL typing rules.

---

## 2. Verification Evidence
- `./gradlew :core:test`: **756/756 tests passed (0 failed)**. Exit code 0.
- `./gradlew :app:testDebugUnitTest`: **53/53 unit tests passed (0 failed)**. Exit code 0.
- `./gradlew :app:assembleDebug`: **BUILD SUCCESSFUL**. Output APK: `app/build/outputs/apk/debug/app-debug.apk`.

---

## 3. Current Failing Tests
- None (0 failing tests).
