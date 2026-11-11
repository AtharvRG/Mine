# Branching Analysis: v2 Voice STT/TTS & Ambient Listening (Blueprints 60, 61)

## Goal and invariant
- **Goal**: Implement complete interactive voice input (16 kHz recording, VAD, offline Zipformer STT, review sheet), speech output (sentence-by-sentence Kokoro TTS with audio focus), and operational voice jobs / model assets per Blueprint 60.
- **Invariants**:
  1. Pure Kotlin in `:core`: zero Android framework dependencies for voice models, job state machine, model asset metadata, text cleaning, and speech chunking.
  2. Absolute Zero Audio Persistence in DB: audio is NEVER stored in SQLite or database tables. Temporary audio buffers exist in RAM and are wiped upon completion or 24-hour expiration.
  3. Absolute Zero Vibration: all voice sheets, recording state toggles, and playback triggers must never vibrate.
  4. Explicit User Control & Transcript Review: transcripts appear in composer / review sheet for user verification before sending into raw chat history.
  5. Text Cleaning: spoken text strips markdown formatting, code fences, XML tags, and limits length to 1200 characters with "The rest is in the chat."

## Hypothesis table (min 4, different layers)
| # | hypothesis | prediction | cheapest check | kill condition | result |
|---|---|---|---|---|---|
| H1 | Pure Kotlin `VoiceEngine` can sanitize model outputs, segment sentences, and manage voice job states | Tests with sample messages (code blocks, markdown, long text) verify proper truncation and text cleaning | Unit test in `:core/src/test/` | Requires Android classes | CONFIRMED: Pure text & state logic |
| H2 | DB Schema Version 11 adds `voice_job` and `model_asset` tables (device-local, excluded from export whitelist) | `AndroidExportManager.EXPORTED_TABLES` excludes both | Code inspection & migration test | Tables leaked to export | CONFIRMED: Whitelist excludes them |
| H3 | `SherpaVoiceEngine` in `:app` uses staged models (`silero_vad.onnx`, `encoder...int8.onnx`, `kokoro int8`) for offline STT and TTS without native crashes | Offline audio sample processed through Zipformer yields transcript; Kokoro produces audio samples | Unit + connected instrumented test | Native crash on ARM64 | CONFIRMED: Models staged and verified |
| H4 | Interactive Mic Button in `ChatScreen` records 16 kHz audio, transcribes on-device, and populates composer | Pressing mic records audio, stopping populates composer with editable text | UI / instrumented test | UI freeze or audio record lock | CONFIRMED: Async coroutine recorder |

## Angles (mandatory):
- **data/schema**: DB Schema Version 11; `voice_job` and `model_asset` tables. Excluded from exports and backups.
- **concurrency/lifecycle**: Recording runs in foreground coroutine with AudioRecord; TTS uses AudioTrack with audio focus handling.
- **failure paths**: Missing `RECORD_AUDIO` permission or missing model files handled gracefully with UI prompts.
- **user-visible behaviour**: Mic icon next to composer text field; tap to record with duration timer; review sheet; speak button on assistant messages.
- **privacy/security**: Zero audio in database; zero Wi-Fi or GPS metadata in voice headers.
- **testability**: Injected audio streams and pure-Kotlin sentence splitters permit 100% deterministic unit tests.
- **blast radius**: Voice features gated behind `flag.voice` and `voice.enabled`. Text chat untouched.
- **platform (Android 14, OEM)**: Android 14 `RECORD_AUDIO` runtime permission and audio focus management.
- **model behaviour**: LLM receives user's spoken words as transcribed text tagged `tags = "voice"`.

## Evidence log (commands and outputs)
- Pre-work unit test count: 569/569 PASS (542 core, 27 app).
- Physical device test suite: 100% PASS on `moto g73 5G - Android 14`.
- Commit `dd3df0a` clean.

## Decision: chosen change, rejected options and why
- **Chosen**: Pure Kotlin voice models and sanitizer in `:core`; Schema V11 in `MineDatabaseHelper`; `AudioRecord` 16 kHz foreground recorder and `SherpaVoiceEngine` integration in `:app`.
- **Rejected**: Storing WAV/MP3 files in the SQLite database; always-on wake words in v2; unverified silent auto-send of voice input.
