# Branching Analysis: Phase 3 — Real Voice, Real Hands-Free & Real Continuous Audio Pipeline

## Goal and Invariant
- **Goal**: Fully remediate Part 1 Items 3 & 4 and Part 6 Item 4 from the adversarial audit.
  1. Real Hands-Free half-duplex conversational loop in `MainActivity.kt` with continuous `AudioRecord` capture, Silero VAD / energy endpointing (1.2s silence detection), 10s idle timeout without speech, and Kokoro TTS speech synthesis (with fallback to `NativeTtsHelper`).
  2. Continuous non-stop `AudioRecord` ring capture in `AmbientForegroundService.kt` using `AudioRecorderHelper.snapshotAndClear()`, real neural `SpeakerEmbeddingExtractor` (`campplus.onnx`) with graceful acoustic fallback, presence gate evaluation *before* transcription to protect battery, and full multi-turn dialogue window context preservation for proposal extraction.
- **Invariants**:
  - Zero raw audio persisted to disk or SQLite (pure RAM memory buffers).
  - Half-duplex turn-taking: microphone strictly closed during TTS playback.
  - Zero vibration across ambient and voice notifications.
  - Seamless fallback: if ONNX models are absent, gracefully fall back to energy VAD and autocorrelation acoustic feature vectors without crashing.

## Hypothesis Table (min 4, different layers)
| # | Hypothesis | Prediction | Cheapest Check | Kill Condition | Result |
|---|---|---|---|---|---|
| H1 | Hands-Free mode can detect speech endpoints using `AudioRecorderHelper.currentRms` and Silero VAD without closing the mic hardware prematurely. | Sampling `currentRms` at 100ms intervals allows detecting speech start (`rms > 0.015f`) and silence endpoint (`silence >= 1200ms`). | Verify `AudioRecorderHelper.currentRms` accurately reflects audio input frames. | `currentRms` stays 0 or cannot distinguish speech from silence. | Validated: `AudioRecorderHelper` computes root-mean-square energy per PCM frame. |
| H2 | Kokoro TTS can be invoked sequentially per sentence with audio focus before falling back to `NativeTtsHelper`. | `SherpaVoiceEngine.synthesize` and `playBlocking` deliver high-fidelity offline voice; fallback to `NativeTtsHelper` handles missing models. | Check `SherpaVoiceEngine.isTtsAvailable` and synthesize sample sentence. | `playBlocking` hangs UI thread or fails to release audio focus. | Validated: `playBlocking` uses coroutine delay and releases audio focus in `finally`. |
| H3 | `AmbientForegroundService` can run non-stop using `snapshotAndClear()` on a single `AudioRecord` instance. | Eliminates hardware restart overhead and prevents cutting words at 3s boundaries. | Run `snapshotAndClear()` repeatedly on active `AudioRecorderHelper`. | `snapshotAndClear()` misses samples or leaks memory. | Validated: `snapshotAndClear()` copies atomic chunks and clears internal list under lock. |
| H4 | `SpeakerEmbeddingExtractor` in `sherpa-onnx` can be resolved dynamically when model file (`campplus.onnx`) is present. | `sherpa-onnx-2.0.1.aar` includes `SpeakerEmbeddingExtractor` and `SpeakerEmbeddingExtractorConfig`. | Javap inspection confirms class presence and method signatures in AAR. | Class missing from AAR or crashes on native loading. | Validated: classes exist in AAR and are ready for instantiation. |

## Angles (one line each, mandatory)
- **data/schema**: No schema migrations needed; voiceprints continue to store 192-dim float embeddings in `voiceprint` table.
- **concurrency/lifecycle**: Recording runs on `Dispatchers.IO`; UI updates and state transitions dispatch to `Dispatchers.Main`; audio buffers are guarded by synchronized locks.
- **failure paths**: Missing ONNX files gracefully degrade to energy VAD, `NativeTtsHelper`, and autocorrelation feature extraction without crashing.
- **user-visible behaviour**: Hands-Free mode now listens continuously, speaks back with natural TTS, and re-arms listening automatically; user never gets stuck in infinite silence or premature timeouts.
- **privacy/security**: Microphone is released immediately when leaving `LISTENING`; audio samples remain strictly in RAM and are never written to disk or network.
- **testability**: Unit tests verify state machine transitions, VAD endpointing, and graceful fallbacks; connected tests verify ONNX engine and audio hardware.
- **blast radius**: Confined to `:app` voice and ambient modules (`MainActivity`, `SherpaVoiceEngine`, `AmbientForegroundService`, `VoiceModelPaths`). Pure `:core` contracts remain unaffected.
- **platform (Android 14, OEM)**: Complies with Android 14 `microphone` foreground service while-in-use restrictions and audio focus requirements.
- **model behaviour (what a real LLM would send)**: Kokoro TTS sanitizes Markdown symbols, code fences, and links before synthesizing speech.

## Evidence Log
```
Command: javap -cp classes.jar com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
Output: public final class com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor ... compute(stream): float[]
Command: javap -cp classes.jar com.k2fsa.sherpa.onnx.Vad
Output: public final class com.k2fsa.sherpa.onnx.Vad ... isSpeechDetected(): boolean, acceptWaveform(float[])
Command: Select-String -Path "app\src\main\java\com\anchor\mine\voice\AudioRecorderHelper.kt" -Pattern "snapshotAndClear"
Output: fun snapshotAndClear(): FloatArray
```

## Decision: Chosen Change, Rejected Options and Why
- **Chosen Change**:
  1. Update `VoiceModelPaths` to resolve optional `campplus.onnx` speaker model.
  2. Enhance `SherpaVoiceEngine` with `SpeakerEmbeddingExtractor` and `extractSpeakerEmbedding(samples)`.
  3. Wire `MainActivity` Hands-Free loop to monitor speech onset and 1.2s silence endpointing, auto-invoke `stopAndTranscribe()`, speak assistant replies via Kokoro TTS with fallback to `NativeTtsHelper`, and auto-rearm `LISTENING`.
  4. Refactor `AmbientForegroundService` to keep a single continuous `AudioRecord` instance, use `snapshotAndClear()`, evaluate speaker embedding / presence gate before STT, and preserve full multi-turn dialogue window for proposal extraction.
- **Rejected Options**:
  - *Hardcoding fixed 5s listening windows*: Rejected because natural speech pauses vary; 1.2s silence endpointing provides fluid conversational flow.
  - *Forcing neural models without fallback*: Rejected because users without downloaded ONNX assets must still have functional voice recording and quick-add.

## Pre-mortem: "It is a week later and this fix caused a bug; what was it?" List 3.
1. *Audio focus contention*: TTS audio playback does not release audio focus if cancelled midway, blocking other apps or subsequent recording. Mitigated by `finally { abandonFocus() }` in `SherpaVoiceEngine.playBlocking`.
2. *VAD sensitivity in noisy environments*: Ambient background noise (e.g. fan or cafe) keeps `currentRms > 0.015f`, preventing silence endpointing from firing. Mitigated by maximum utterance duration cap (30s) in Hands-Free mode which forces endpointing.
3. *Microphone permission revocation during background ambient*: User revokes permission while ambient service is active. Mitigated by `try/catch` and defensive checks before starting `AudioRecord`.

## What Input Would Break the Fix
- An audio input where background noise is exactly at 0.015f RMS threshold continuously. The 30s maximum utterance safety cap prevents an infinite hang and triggers transcription of the recorded buffer.
