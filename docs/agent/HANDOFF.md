# HANDOFF: Ambient Pipeline, Multi-Key NIM, Model Manager & Conversational Polish Integration
Date: 2026-11-22 08:37 IST
Status: COMPLETE & VERIFIED; ULTRON PERSONA CADENCE RECOMPILED; 756 CORE TESTS PASSED, 53 APP UNIT TESTS PASSED, ASSEMBLEDEBUG PASSED; PHYSICAL DEVICE VERIFIED ON MOTO G73 5G

## Executive Summary
All 11 feature upgrades and associated architectural improvements from `C:\Users\Banespire\Downloads\mine-integrated\mine-changed-files` have been integrated into `Mine`.
The pure Kotlin `:core` test suite expanded to 756 tests (49 new tests covering time parsing, key pool, ambient pipeline, voice enrollment scoring, tokenizer, and About You interviewer), all passing with 0 failures.
The Android `:app` unit test suite contains 53 tests, all passing with 0 failures.
Compilation error in `NewScreens.kt:347` (`SMARTCAST_IMPOSSIBLE`) was diagnosed and resolved.
The debug build `:app:assembleDebug` succeeds cleanly in 27s, packaging all JNI native libraries without collision.
The app was installed and verified live on a physical Android device (`moto g73 5G`), confirming cold startup, SQLCipher database keying, ContextSensingManager background operation, and clean Compose UI rendering.

---

## 1. What Changed (11 Upgrades Integrated)

### 1. Ambient Listening Pipeline & Voice Enrollment
- **Speech Segmentation**: Added `SpeechSegments.kt` and `SileroSegmenter.kt` in `:app` implementing VAD segmentation with fallback to loudness/energy detection.
- **Utterance Speaker Verification**: Utterances longer than ~0.7s are evaluated against the user's enrolled voiceprint (x-vectors) to tag utterances as USER vs OTHER. Audio is kept purely in RAM (120s buffer) and never persisted to disk.
- **Voice Enrollment Session**: Guided onboarding flow with quiet-room verification, SNR checks, clipping checks, consistency checks, and adaptive threshold tuning.
- **Cloud Whisper**: Integrated NVIDIA Whisper ASR via HTTP/2 gRPC framing over OkHttp (`RivaAsrProto.kt`, `NimWhisperClient.kt`).

### 2. Notification Sources ("What Mine can read")
- **Installed Apps Screen**: Lists launchable applications (`InstalledApps.kt`, `SystemScreens.kt`) allowing users to explicitly select which apps Mine is allowed to read from.
- **Security & Manifest**: Declared narrow `<queries>` launcher intent filter in `AndroidManifest.xml` without requesting invasive `QUERY_ALL_PACKAGES` permission.

### 3. Natural Time Parsing & Scheduling
- **Text Normalizer & Extractor**: `TimeTextNormalizer.kt` and `TimeExpressionExtractor.kt` parsing complex expressions: "24th nov at 3.40pm", "in 3 days at 5", "half past 9 tonight", "1540 hrs", "tmrw", timezones (IST, EST, etc.).
- **Ambiguous Date Disambiguation**: Resolves ambiguous formats (e.g. 04/05) with clarification prompts.
- **Custom Time Pill**: Added "Custom time..." pill in manual task addition modal (`MineModals.kt`) with date and time pickers.

### 4. Ultron Persona
- **Preset**: Added original dry, witty, mocking, yet loyal Ultron persona to `PersonaPresets.kt`.
- **Contract Test**: Updated `PersonaEngineTest.kt` asserting 6 presets including Ultron.

### 5. Models Page & Tokenizers
- **Model Catalog & Manager**: `ModelCatalog.kt` and `ModelManager.kt` handling resumable Hugging Face model downloads with SHA-256 verification and Wi-Fi gating.
- **Tokenizers**: `HfTokenizer.kt` implementing GPT-2 byte-level BPE (IBM Granite R2 / ModernBERT) and WordPiece (MiniLM / BGE).
- **ONNX Embedder**: `OnnxEmbedder.kt` leveraging `onnxruntime-android:1.27.0` for sentence embeddings with automatic fallback to hash projection.

### 6. Chat Messages & Conversational Context
- **Proactive Funnel**: Background events (alarms, call nudges, proactive wakeups) funnel through `ProactiveMessages.kt` and `MessageAttrs.kt`, marking assistant bubbles as "Mine reached out".
- **Fired Reminders**: Fired reminders leave a quiet, compact receipt in the chat timeline.
- **Message Reply Context**: Swipe-to-reply and long-press reply WhatsApp-style with quoted reference.
- **Cross-Day Context**: Long-press "Talk about this" injects prior day messages into today's conversation context.

### 7. NVIDIA NIM Chat Provider & Key Pool
- **Multi-Key Pool**: `KeyPool.kt` rotating up to 10 NVIDIA NIM API keys with 40 requests/minute per-key rate limiting and 60s cooldown on HTTP 429.
- **Unified Quotas**: Chat completions and ambient Whisper share quota counters to prevent exhaustion.

### 8. Personal Details & About-You Interviewer
- **Profile Fields**: `ProfileFields.kt` storing name, date of birth, pronouns, city, work in `profile.*` preferences with on-device-only option. Seeded into Soul facts during onboarding.
- **About-You Interviewer**: `AboutYouInterviewer.kt` and `AboutYouSession.kt` powering an isolated interview chat on the About You page with proposed Soul fact confirmation.

### 9. Hardening & Compiler Fixes
- **Smart Cast**: Fixed `SMARTCAST_IMPOSSIBLE` error on `ModelCatalogEntry.note` in `NewScreens.kt:347`.
- **Intent Dispatch**: Made `MainActivity.onNewIntent` public for deep links and shortcuts.
- **Third-Party Documentation**: Added OkHttp 4.12.0 and ONNX Runtime Android 1.27.0 to `THIRD_PARTY_LICENSES.md`.

---

## 2. Verification Evidence
- `./gradlew :core:test`: **756 tests passed (0 failed)**. Exit code 0.
- `./gradlew :app:testDebugUnitTest`: **53 unit tests passed (0 failed)**. Exit code 0.
- `./gradlew :app:assembleDebug`: **BUILD SUCCESSFUL**. Exit code 0. Output APK: `app/build/outputs/apk/debug/app-debug.apk`.
- **Physical Hardware Verification**: Installed on `moto g73 5G` (`10.175.15.5:5555`), launched successfully, database opened cleanly, screen capture verified live Home screen UI.

---

## 3. What Is NOT Verified
- Remote NVIDIA NIM API calls with a live production key (requires valid user API key).
- Physical mic audio capture of live ambient speech on device (ambient service requires user manual activation).

---

## 4. Current Failing Tests
- None (0 failing tests).

---

## 5. Decisions Recorded
- Decision **D-226** recorded in `docs/agent/DECISIONS.md`.
