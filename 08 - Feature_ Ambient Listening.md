# Feature: Ambient Listening

## User Story

As a user, I open Mine, toggle Ambient Listening ON, and place my phone in my pocket. Mine listens to my day, identifies my voice, suppresses noise, and silently builds my graph.

## Activation Flow

```mermaid
flowchart TD
  A[User toggles ON] --> B[Start AmbientListeningService]
  B --> C[Create NotificationChannel]
  C --> D[Post Notification<br/>Mine is listening]
  D --> E[Acquire Partial WakeLock]
  E --> F[Initialize Sherpa-ONNX]
  F --> G[Begin AudioRecord Loop]
```

## Runtime Loop

```mermaid
flowchart TD
  A[AudioRecord.read] --> B[Sherpa VAD.process]
  B --> C{Speech?}
  C -->|No| A
  C -->|Yes| D[Sherpa NS + SpeakerID]
  D --> E[Label Speaker]
  E --> F[Accumulate 5-10s]
  F --> G[Encode Chunk]
  G --> H[Groq Whisper]
  H --> I[Store in memory_queue]
  I --> J[Background Agent picks up]
```

## Mic Hijacking & Call Interruption Handling

Mine must gracefully yield the microphone to other apps (Cellular calls, WhatsApp, Instagram, Zoom) and notify the user.

```mermaid
flowchart TD
  A[System Event] --> B{Event Type}
  
  B -->|Cellular Call| C[PhoneStateListener<br/>OFFHOOK]
  B -->|VoIP / Camera| D[AudioManager<br/>Loss of AudioFocus]
  
  C --> E[Set isPausedBySystem]
  D --> E
  
  E --> F[AudioRecord.stop<br/>temporarily]
  F --> G[Post HIGH PRIORITY Notification<br/>Mic access paused by another app]
  
  H[Call Ends / App Closes] --> I[Regain AudioFocus / IDLE]
  I --> J[Clear isPausedBySystem]
  J --> K[Resume AudioRecord]
  K --> L[Restore Silent Notification<br/>Listening]
```

## Notification Actions

The persistent notification in the silent channel provides:
- Status text: "Ambient Listening is on"
- Action: Pause (temporarily stops mic read loop)
- Action: Stop (fully terminates service)

## States

| State | Visual | Behavior |
|-------|--------|----------|
| OFF | Toggle gray, mic slashed | No service running |
| ON | Toggle green, mic active | Full pipeline active |
| PAUSED | Toggle yellow | Service alive, mic paused by user or system |
| PROCESSING | Subtle progress indicator | Agent working on backlog |