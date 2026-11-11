# Branching Analysis: v1.1 Offline Voice Pipeline Wiring & Multi-Target Fallback Chain

## Goal and invariant
- **Goal:**
  1. Wire offline on-device speech processing in `:app` (`SherpaVoiceEngine.kt`) utilizing staged models on the physical device (`/data/local/tmp/models/` for VAD, Zipformer STT, Kokoro TTS).
  2. Implement Multi-Target Fallback Chain in `ChatController.kt` (line 1288) so that requests automatically fall back across configured secondary/backup providers upon rate-limit, 429, or network errors, rather than being restricted to a hardcoded single-element list.
- **Invariant:**
  1. Pure-Kotlin `:core`: Voice native audio libraries and Android permissions/framework stay isolated in `:app`.
  2. Invariant 1 (Privacy): Audio recorded in memory/cache is temporary; transcripts only reach SQLite; zero private user text in Device-Protected storage.
  3. No model weights or large binary files committed to Git (`.gitignore` enforced for `*.aar`, `*.onnx`, etc.).
  4. ZERO vibration calls or permissions (physical motor broken on test device moto g73 5G).

## Hypothesis table (min 4, different layers)
| # | hypothesis | prediction | cheapest check | kill condition | result |
|---|---|---|---|---|---|
| H1 | Pre-built `sherpa-onnx-2.0.1.aar` placed in `app/libs/` can be cleanly integrated via Gradle without compilation or ABI clashes | Gradle builds `:app:assembleDebug` and tests run on moto g73 5G (`arm64-v8a`) | Check AAR jni dirs and run `./gradlew :app:assembleDebug` | Duplicate class or missing JNI symbol for arm64-v8a | Alive (AAR inspected: has arm64-v8a jni + classes) |
| H2 | Kokoro TTS requires `model.int8.onnx`, `voices.bin`, `tokens.txt`, and `espeak-ng-data` directory paths | `OfflineTtsKokoroModelConfig` accepts these exact paths and generates PCM audio samples | Inspect class signature of `OfflineTtsKokoroModelConfig` via `javap` | Missing required parameter or incompatible model format | In progress |
| H3 | Streaming Zipformer ASR requires `encoder`, `decoder`, `joiner`, and `tokens.txt` | `OnlineTransducerModelConfig` accepts encoder, decoder, joiner, tokens paths | Inspect class signature of `OnlineTransducerModelConfig` via `javap` | Incompatible model architecture | In progress |
| H4 | Silero VAD requires `silero_vad.onnx` and can segment speech intervals from raw PCM audio stream | `SileroVadModelConfig` + `Vad` detects speech start and end timestamps | Inspect class signature of `SileroVadModelConfig` and `Vad` | Silero model version mismatch (v4 vs v5) | In progress |
| H5 | `ChatController.kt` fallback chain can be constructed from active settings (`provider.endpoint`, `provider.key`, and fallback secondary slots) | Orchestrator iterates targets in chain on rate-limit or error | Unit test in `:core` verifies secondary target chosen when primary fails | Controller crashes or drops messages on fallback | Planned |

## Angles (one line each, mandatory)
- **Data/schema:** No schema change required; settings store provider fallback configurations and voice toggle flags.
- **Concurrency/lifecycle:** Audio recording and TTS synthesis run in background coroutines; audio session releases promptly on stop/cancel or screen exit.
- **Failure paths:** If model files are missing or unreadable, `SherpaVoiceEngine` returns a graceful `VoiceEngineResult.Unavailable` rather than crashing the app.
- **User-visible behaviour:** Mic button and TTS speak action execute offline voice transcription and synthesis smoothly.
- **Privacy/security:** Audio samples never leave device memory; temp files scrubbed immediately after transcription.
- **Testability:** Unit tests for voice configuration validation and fallback chain routing; connected test on physical device executing offline TTS & STT with staged models.
- **Blast radius:** Isolated to new `voice` package in `:app` and `ChatController` target chain construction in `:core`.
- **Platform (Android 14, OEM):** Moto g73 5G (`arm64-v8a`, Android 14 API 34). Zero vibration.
- **Model behaviour (what a real LLM would send):** Fallback provider receives identical chat payload and emits SSE deltas cleanly.

## Evidence log
1. **Sherpa-ONNX Native Integration (AAR):**
   - Downloaded official pre-built `sherpa-onnx-2.0.1.aar` (49.4 MB) containing `libonnxruntime.so`, `libsherpa-onnx-c-api.so`, `libsherpa-onnx-cxx-api.so`, and `libsherpa-onnx-jni.so` for `arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86`.
   - Placed in `app/libs/sherpa-onnx-2.0.1.aar`. Added `*.aar` to `.gitignore` to maintain strict zero-weights/binaries commit invariant.
   - Added `pickFirsts += listOf("com/k2fsa/sherpa/onnx/BuildConfig.class")` in `app/build.gradle.kts`.
   - Added `-keep class com.k2fsa.sherpa.onnx.** { *; }` and `-dontwarn` to `app/proguard-rules.pro`.
2. **Offline Voice Pipeline Architecture (`SherpaVoiceEngine.kt`):**
   - Integrated Silero VAD (`SileroVadModelConfig` + `VadModelConfig` + `Vad`).
   - Integrated Streaming Zipformer transducer ASR (`OnlineTransducerModelConfig` + `OnlineModelConfig` + `OnlineRecognizer`).
   - Integrated Kokoro TTS (`OfflineTtsKokoroModelConfig` + `OfflineTtsModelConfig` + `OfflineTts`).
   - Fixed sherpa-onnx >= v2.0 lexicon requirement: Kokoro requires `lexicon-us-en.txt` for grapheme-to-phoneme token mapping. Staged `lexicon-us-en.txt` (5.9 MB) and updated `VoiceModelPaths` to resolve it.
   - Text cleaning per Blueprint 60 §3.3: `SherpaVoiceEngine.cleanTextForSpeech` strips markdown fences, inline code, XML/data tags, links, and formatting symbols.
   - Pure audio playback via Android `AudioTrack` with `AudioFormat.ENCODING_PCM_FLOAT` at 24000 Hz.
3. **Multi-Target Fallback Chain (`ChatController.kt`):**
   - In `ChatController.kt`, replaced single hardcoded `listOf(target)` with dynamic fallback chain built from `_state.value.providerEndpoint`, `_state.value.providerKey`, `provider.rpm_default`, and fallback settings (`provider.fallback.endpoint`, `provider.fallback.key`, `provider.fallback.rpm`).
   - Added setting definitions in `SettingsCatalog.kt`.
   - Verified via `ProviderFallbackChainTest` in `:core`: when primary endpoint returns 429, orchestrator transparently retries and completes turn on fallback endpoint.

## Decision: chosen change, rejected options and why
- **Chosen:** Pre-built `sherpa-onnx-2.0.1.aar` in `app/libs/` with `*.aar` in `.gitignore`. Official binary package with arm64 JNI for moto g73 5G.
- **Rejected:** Compiling sherpa-onnx from C++ source via CMake/NDK in Gradle build. Unnecessary complexity and high build time when official pre-compiled `.aar` is maintained directly by the k2-fsa team.
- **Chosen:** Kokoro `lexicon-us-en.txt` mapping for tokenization. Robust, avoids runtime C-level fork of espeak-ng.
- **Chosen:** Multi-target fallback chain in `ChatController.kt` leveraging `ProviderOrchestrator`'s existing chain walk. Zero architecture churn.

## Pre-mortem: "it is a week later and this fix caused a bug; what was it?" List 3.
1. Native JNI memory leak if `Vad`, `OfflineTts`, or `OnlineRecognizer` instances are not explicitly `.release()` or closed on engine shutdown.
2. Incomplete or missing `espeak-ng-data` files causing Kokoro TTS phonemizer crash at runtime.
3. Fallback chain in `ChatController` loops infinitely or attempts non-existent fallback targets when keys are invalid.
