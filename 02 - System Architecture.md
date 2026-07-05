# System Architecture

## High-Level Data Flow

```mermaid
flowchart TD
A[Phone Mic] --> B[Sherpa-ONNX Service]
B --> C{VAD Detects Speech?}
C -->|No| B
C -->|Yes| D[Speaker ID & Denoiser]
D --> E[Audio Chunker<br/>Max 15s or 1s Silence]
E --> F[NVIDIA Riva Whisper<br/>gRPC]
F --> G[Transcript Vault & Queue]
G --> H[Curator Agent<br/>gpt-oss-120b]
H --> I[Vector + FTS4 Context Fetch]
I --> J[Tool Executor<br/>SQLite Transaction]
J --> K[(SQLite Graph + Vector DB)]
J --> L{Nudge Worthy?}
L -->|Yes| M[High Priority Push Notification]
L -->|No| N[Silent Graph Insert]

O[Chat Input] --> P[Vector Search Context]
P --> Q[Chat Agent<br/>kimi-k2.6]
Q --> R[Daily Chat Canvas]
R --> S[Async Delegation to Curator]
```

## Component Stack

| Layer | Technology | Purpose |
|-------|-----------|---------|
| Platform | Android API 28+ | Operating system |
| Language | Kotlin (Coroutines/Flow) | Primary development language |
| UI | Jetpack Compose | User interface toolkit |
| Architecture | MVVM + Hilt DI | Code organization |
| Database | SQLite via Room + SQLCipher | Encrypted local storage |
| Search | SQLite FTS4 + Cosine Similarity | Keyword & Semantic node search |
| Audio | Sherpa-ONNX via JNI | VAD, Denoiser, Speaker ID |
| Network | Ktor + gRPC | REST and Binary API communication |
| Background | WorkManager | Offline queues, Chronos, Pruning |
| Crypto | Android Keystore + BuildConfig | Secure key storage |
