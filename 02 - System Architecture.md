# System Architecture

## High-Level Data Flow

```mermaid
flowchart TD
    A[Phone Mic] --> B[Sherpa-ONNX Service]
    B --> C{VAD Detects Speech?}
    C -->|No| B
    C -->|Yes| D[Speaker ID<br/>Noise Suppression]
    D --> E[Audio Chunker<br/>5-10s ByteArrays]
    E --> F[Groq Whisper Router]
    F --> G[Transcript Queue<br/>memory_queue]
    G --> H[Background Agent<br/>Kotlin Coroutine]
    H --> I[Noise Gate<br/>Local]
    I --> J[FTS5 Context Fetch<br/>Local]
    J --> K[NVIDIA NIM Acc2<br/>gpt-oss-20b]
    K --> L[Tool Executor<br/>SQLite Transaction]
    L --> M[(SQLite Graph<br/>Encrypted)]
    L --> N{Nudge Worthy?}
    N -->|Yes| O[Nudge Queue]
    O --> P[NVIDIA NIM Acc1<br/>gpt-oss-120b]
    P --> Q[Notification Manager]
    N -->|No| R[Silent Graph Insert]
    
    S[Chat Input] --> T[Context Assembler]
    T --> U{Thinking Mode}
    U -->|ON| V[NIM Acc2<br/>Planning]
    V --> W[NIM Acc1<br/>Final Response]
    U -->|OFF| W
    W --> X[Chat Log<br/>chat_messages]
```
## Component Stack

| Layer | Technology | Purpose |
|-------|-----------|---------|
| Platform | Android API 28+ | Operating system |
| Language | Kotlin | Primary development language |
| UI | Jetpack Compose | User interface toolkit |
| Architecture | MVVM + Repository | Code organization |
| Database | SQLite via Room + SQLCipher | Encrypted local storage |
| Search | SQLite FTS5 | Full-text node search |
| Audio | Sherpa-ONNX via JNI | VAD, NS, Speaker ID |
| Network | Ktor Client | API communication |
| Background | WorkManager + Coroutines | Queue draining, pruning |
| Crypto | Android Keystore + EncryptedSharedPreferences | Secure key storage |
