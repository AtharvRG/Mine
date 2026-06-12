# Mine

> **Your phone knows everything. It remembers nothing. Mine fixes that.**

Mine is not a chatbot. It is a co-founder for your life — an ambient, graph-based second brain that listens, remembers, and nudges. Designed for the user who wants to own their data, own their memories, and never again forget the context of their own life.

Mine is single-user, zero-server, and fully open source. All data stays on your device. You bring your own AI models. You control your persona. You own your memory graph.

---

## Index

| Doc | Title | Description |
|-----|-------|-------------|
| 01 | [Project Manifesto](01%20-%20Project%20Manifesto.md) | Identity, thesis, core pillars, and non-goals |
| 02 | [System Architecture](02%20-%20System%20Architecture.md) | High-level data flow and component stack |
| 03 | [Database Schema](03%20-%20Database%20Schema.md) | SQLite schema for groups, branches, nodes, and edges |
| 04 | [Memory Graph Model](04%20-%20Memory%20Graph%20Model.md) | Hierarchical memory: Group > Branch > Node > Edge |
| 05 | [External APIs & Rate Limiting](05%20-%20External%20APIs%20&%20Rate%20Limiting.md) | Groq, NVIDIA NIM, and rate limiter implementation |
| 06 | [AI Prompts & Tool Calling](06%20-%20AI%20Prompts%20&%20Tool%20Calling.md) | JSON tool calling schema for the curator agent |
| 07 | [Audio Processing Pipeline](07%20-%20Audio%20Processing%20Pipeline.md) | Mic → VAD → Speaker ID → Chunk → Whisper pipeline |
| 08 | [Feature: Ambient Listening](08%20-%20Feature_%20Ambient%20Listening.md) | Always-on mic capture with system event handling |
| 09 | [Feature: Nudge Talking](09%20-%20Feature_%20Nudge%20Talking.md) | Contextual memory surfacing via notifications |
| 10 | [Feature: Chat Interface](10%20-%20Feature_%20Chat%20Interface.md) | Continuous chat stream with Thinking Mode |
| 11 | [Feature: Post-Call Reflection](11%20-%20Feature_%20Post-Call%20Reflection.md) | Quick voice capture after phone calls |
| 12 | [Feature: Memory Graph UI & Dev Mode](12%20-%20Feature_%20Memory%20Graph%20UI%20&%20Dev%20Mode.md) | Interactive graph viewer and dev tools |
| 13 | [Settings & Configuration](13%20-%20Settings%20&%20Configuration.md) | API keys, persona editor, backup, and export |
| 14 | [User Flow: Onboarding](14%20-%20User%20Flow_%20Onboarding.md) | First-launch experience and setup flow |
| 15 | [User Flow: Background Agent & Queue System](15%20-%20User%20Flow_%20Background%20Agent%20&%20Queue%20System.md) | 4-stage pipeline and offline queue handling |
| 16 | [Security & Privacy](16%20-%20Security%20&%20Privacy.md) | Encryption layers, threat model, and key management |
| 17 | [Development Guide](17%20-%20Development%20Guide.md) | Kotlin project structure and build instructions |

---

## Document Summaries

### 01 — Project Manifesto
Defines the identity of Mine (app name: Mine, AI identity: MineAI), its thesis, four core pillars (Ambient First, Zero Server, Hierarchical Memory, Sovereign AI), and explicit non-goals including multi-user sync and on-device LLM inference for v1.

### 02 — System Architecture
Describes the end-to-end data flow from phone microphone through Sherpa-ONNX, Groq Whisper, background agent, NVIDIA NIM, and into the SQLite graph. Includes the full component stack: Android, Kotlin, Jetpack Compose, Room + SQLCipher, FTS5, Ktor, WorkManager, and Android Keystore.

### 03 — Database Schema
Complete SQLite schema definitions for `groups`, `branches`, `nodes`, and `edges` tables, including foreign keys, indexes, and entity relationships used throughout the app.

### 04 — Memory Graph Model
Explains the tree-structured second brain: hardcoded top-level groups (academic, work, health, people, etc.), dynamic branches under groups, and atomic memory nodes with typed edges connecting related concepts.

### 05 — External APIs & Rate Limiting
Details the dual-account setup (Groq for ASR, NVIDIA NIM for LLM), model quotas, a `RateLimiter` implementation with token-bucket logic, and fallback routing when rate limits are hit.

### 06 — AI Prompts & Tool Calling
Documents the strict JSON tool-call schema injected into GPT-OSS-20B for graph curation, including `update_memory_graph`, `nodes_to_create`, `nodes_to_update`, and `contradictions` handling.

### 07 — Audio Processing Pipeline
Walks through the audio pipeline: AudioRecord → Sherpa VAD → Noise Suppression → Speaker ID → 5–10s chunks → Opus/PCM → Groq Whisper. Covers speaker enrollment via x-vector matching and resource management.

### 08 — Feature: Ambient Listening
The always-on mic service: activation flow, runtime loop, notification channel, wake lock, Sherpa-ONNX initialization, and graceful mic yielding to calls and other apps (PhoneStateListener + AudioFocus).

### 09 — Feature: Nudge Talking
Contextual memory surfacing as notifications. Covers trigger conditions, confidence thresholds, cooldown windows, optional GPT-OSS-120B polishing, notification actions (good/bad nudge feedback), and user-configurable cooldown settings.

### 10 — Feature: Chat Interface
The continuous chat experience: infinite scroll, day headers, floating voice/text input orb, semantic routing for memory recall, lull detection, and the two-stage Thinking Mode (20B plans → 120B responds).

### 11 — Feature: Post-Call Reflection
After a call ends, the user is prompted to record a 30–60 second voice summary. Covers call-state detection, Quick Capture bottom sheet, audio processing, and graph tagging with `source: call_reflection`.

### 12 — Feature: Memory Graph UI & Dev Mode
Read-only tree viewer with FTS5 search, color-coded groups, and node cards. Dev Mode adds manual node/branch editing, raw edge/queue inspection, API call logs, and automated nightly pruning via WorkManager.

### 13 — Settings & Configuration
Settings hierarchy: API key management (masked, validated), `souls.md` persona editor, `core.md` anchor memory editor, ambient toggles, nudge cooldown, and AES-encrypted backup/export with user-provided passwords.

### 14 — User Flow: Onboarding
The first-launch funnel: splash → legal → API keys → voice enrollment → persona setup → battery optimization. Each step persists to `EncryptedSharedPreferences`; certain steps are unskippable.

### 15 — User Flow: Background Agent & Queue System
The 4-stage pipeline: Noise Gate → Context Fetch (FTS5) → Curator (GPT-OSS-20B) → Tool Executor. Describes SQLite WAL concurrency, transaction safety, offline queuing, and rate-limited fallback behavior.

### 16 — Security & Privacy
Threat model and three-layer encryption: AES-256 SQLCipher database, `EncryptedSharedPreferences` with Android Keystore, and AES-256 ZIP backups with user-provided passwords. No cloud. No accounts.

### 17 — Development Guide
Standard Kotlin/Android project structure with Hilt DI, Room entities and DAOs, repository pattern, service and worker layers, and AI prompt builders. Serves as the developer onboarding guide.
