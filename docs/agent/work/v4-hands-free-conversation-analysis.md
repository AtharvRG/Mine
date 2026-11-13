# Branching Analysis: Hands-Free Conversation Mode (v4 / P-018 / Blueprint 60 §9 & Blueprint 61)

- **Date**: 2026-11-13
- **Author**: Lead Engineer & Quality Gate
- **Status**: IN PROGRESS
- **Decision**: D-197 (pending review)

---

## 1. Goal and Invariant

### Goal
Implement **Hands-Free Conversation Mode** ("talk, hear, talk again" continuous audio-dialogue loop, Parking Lot `P-018`, Voice Blueprint 60 §9, Roadmap line 52):
1. **Continuous Conversational Loop**: User talks -> speech is transcribed -> turn is executed by `ChatController` -> assistant reply is synthesized and spoken aloud -> upon playback completion, the microphone automatically opens for the user's next utterance without requiring repeated manual taps.
2. **Turn-Taking State Machine in `:core`**: Pure Kotlin state machine managing `Idle`, `Listening`, `Processing`, and `Speaking` states with well-defined transitions, timeouts, and stop conditions.
3. **Stop-Word & Intent Termination**: Automatic session exit when user says goodbye/stop phrases ("stop", "exit", "cancel", "bye", "goodbye", "that's all", "thank you that's all", "done").
4. **Silence Safety Timeout**: Automatic session exit after 10 seconds of silence to prevent unbounded microphone drain or pocket eavesdropping.
5. **Acoustic Echo Prevention**: Strict temporal isolation between microphone recording and speaker playback—the microphone is never active while TTS is playing.

### Invariant
- **Echo & Feedback Isolation**: Microphone recording MUST NOT occur while TTS is playing.
- **Pure Kotlin `:core` State Machine**: State transitions, stop-word recognition, and timeout policies live in `:core` with zero Android framework dependencies.
- **Zero Raw Audio on Disk/SQLite**: Voice samples remain strictly in memory and are discarded immediately after transcription.
- **Zero Vibration**: No vibration on turn transitions or session termination.
- **Fail-Safe & Interruption Recovery**: Phone calls, manual Stop taps, or audio focus loss instantly abort hands-free mode and release all audio hardware.

---

## 2. Hypothesis Table (Min 4, Different Layers)

| # | Hypothesis | Prediction | Cheapest Check | Kill Condition | Result |
|---|---|---|---|---|---|
| **H1 (Always-On Open Mic with Software AEC)** | Keep microphone continuously recording in background, using software acoustic echo cancellation to filter out TTS. | High CPU usage, battery drain, and imperfect echo cancellation on mobile will cause model to transcribe its own spoken replies. | Test microphone input while phone speaker plays audio at medium volume. | Transcribed audio contains fragments of assistant's own output. | **KILLED** (Unacceptable echo loop risk & high battery drain). |
| **H2 (Half-Duplex Sequential Turn-Taking State Machine)** | Strictly alternate: Recording -> Transcribing -> LLM Generation -> TTS Playback -> (Focus Release + 300ms Delay) -> Recording. | Guarantees zero acoustic echo because the mic is closed during playback; clear state machine transitions. | Step through state machine unit test: verify `isRecording` is false during `Speaking`. | Any state overlap where recording starts before playback completes. | **SELECTED (CHOSEN OPTION)**. |
| **H3 (UI-Driven Polling Loop in Compose LaunchedEffect)** | Drive hands-free turn-taking purely via Compose `LaunchedEffect(isSpeaking)` in `MainActivity`. | Hard to unit-test without Android Compose test harness; UI recomposition or backgrounding can corrupt state. | Check if turn-taking logic can be unit-tested in `:core:test`. | Logic requires Compose runtime to evaluate turn transitions. | **KILLED** (Violates CLAUDE.md: "Pure logic lives in module core"). |
| **H4 (Continuous Keyword Spotter / Wake Word Engine)** | Run a continuous wake-word detector like "Hey Mine" between turns. | Heavy model download, battery consumption, and complex on-device keyword spotting model not yet bundled. | Check whether wake word model is in repo. | Requires new model weights and license approval (parked P-024). | **KILLED** (P-024 is explicitly parked; silence timeout and stop words suffice for P-018). |

---

## 3. Angles (One Line Each, Mandatory)

- **Data / Schema**: Zero schema changes; hands-free is an in-memory conversational session mode.
- **Concurrency & Lifecycle**: Turn execution runs inside `ChatController.sendMutex`; stop action cancels active stream or playback immediately.
- **Failure Paths**: Empty STT / silence timeout exits session cleanly without crash; phone call interruption halts session via telephony listener.
- **User-Visible Behavior**: Hands-free toggle button in chat view; state pill shows "Listening..." / "Thinking..." / "Speaking..."; spoken replies stream via TTS.
- **Privacy & Security**: Microphone is only active when session is explicitly started; auto-stops after 10s silence; zero audio persisted.
- **Testability**: Pure Kotlin `HandsFreeControllerTest` verifies all state transitions, stop words, and timeouts in `:core:test`.
- **Blast Radius**: Isolated to voice session management and UI action dispatch; zero impact on text-only chat or background alarms.
- **Platform (Android 14, OEM)**: Proper `AudioFocusRequest` management and `UtteranceProgressListener.onDone` triggers.
- **Model Behavior**: Stop words are intercepted in code before or during turn dispatch; LLM receives standard user text turns.

---

## 4. Evidence Log

1. Inspected existing `NativeTtsHelper.kt`:
   - Has `speak(context, text)` and `stop()`.
   - Needs `setOnUtteranceProgressListener` callback support so `:app` knows exactly when playback finishes.
2. Inspected `AudioRecorderHelper.kt`:
   - Clean in-memory start/stop with RMS calculation.
3. Inspected `ChatController.kt`:
   - `onAction` already handles `UiAction.SendMessage` and serialized streaming.

---

## 5. Decision & Architecture (D-197)

- **Architecture**:
  - `core/src/main/java/com/anchor/core/voice/HandsFreeModels.kt`:
    - `HandsFreeState` enum (`IDLE`, `LISTENING`, `PROCESSING`, `SPEAKING`).
    - `HandsFreeConfig(silenceTimeoutSec: Int = 10, stopWords: Set<String>)`.
  - `core/src/main/java/com/anchor/core/voice/HandsFreeStateMachine.kt`:
    - Pure Kotlin state machine managing events (`Start`, `Stop`, `SpeechStarted`, `SpeechEnded`, `TurnComplete`, `PlaybackComplete`, `Timeout`).
    - Stop word detector (`isStopPhrase(text: String): Boolean`).
  - `core/src/main/java/com/anchor/core/ui/ChatContract.kt`:
    - `UiState.handsFreeState: HandsFreeState`.
    - `UiAction.SetHandsFree(enabled: Boolean)`.
    - `UiAction.HandsFreeEvent(event: HandsFreeEvent)`.
  - `app/src/main/java/com/anchor/mine/voice/NativeTtsHelper.kt`:
    - Added completion listener callback: `onPlaybackComplete: (() -> Unit)?`.
  - `app/src/main/java/com/anchor/mine/MainActivity.kt`:
    - Wired hands-free loop in UI.

---

## 6. Pre-Mortem: "It is a week later and this fix caused a bug; what was it?"

1. **Bug 1: Speaker Echo Feedback Loop**: TTS audio output picked up by microphone, causing assistant to transcribe and reply to itself.
   - *Mitigation*: Half-duplex state machine strictly gates microphone opening until `onPlaybackComplete` + 300ms acoustic grace period.
2. **Bug 2: Mic Stays Open Indefinitely in Pocket**: User walks away while hands-free is active, draining battery and recording silence.
   - *Mitigation*: 10-second silence timeout automatically shuts down the session.
3. **Bug 3: Phone Call Crash or Mic Contention**: An incoming phone call arrives while hands-free is listening.
   - *Mitigation*: Phone state listener calls `stopHandsFree()` immediately.
