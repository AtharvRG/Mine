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
| 03 | [Database Schema](03%20-%20Database%20Schema.md) | SQLite schema for groups, branches, nodes, edges, and vectors |
| 04 | [Memory Graph Model](04%20-%20Memory%20Graph%20Model.md) | Hierarchical memory and local Vector DB deduplication |
| 05 | [External APIs & Rate Limiting](05%20-%20External%20APIs%20&%20Rate%20Limiting.md) | 3-Key NVIDIA NIM architecture and Mutex Rate Limiter |
| 06 | [AI Prompts & Tool Calling](06%20-%20AI%20Prompts%20&%20Tool%20Calling.md) | JSON tool calling schema for the 120B curator agent |
| 07 | [Audio Processing Pipeline](07%20-%20Audio%20Processing%20Pipeline.md) | Mic → Sherpa VAD/Denoiser → Riva Whisper gRPC |
| 08 | [Feature: Ambient Listening](08%20-%20Feature_%20Ambient%20Listening.md) | Always-on mic capture with system event handling |
| 09 | [Feature: Nudge Talking](09%20-%20Feature_%20Nudge%20Talking.md) | Contextual memory surfacing via notifications |
| 10 | [Feature: Chat Interface](10%20-%20Feature_%20Chat%20Interface.md) | Daily canvas chat stream with CQRS architecture |
| 11 | [Feature: Post-Call Reflection](11%20-%20Feature_%20Post-Call%20Reflection.md) | Quick voice capture after phone calls |
| 12 | [Feature: Memory Graph UI & Dev Mode](12%20-%20Feature_%20Memory%20Graph%20UI%20&%20Dev%20Mode.md) | Interactive graph viewer and Transcript Vault |
| 13 | [Settings & Configuration](13%20-%20Settings%20&%20Configuration.md) | Persona presets, contextual location, and voice enrollment |
| 14 | [User Flow: Onboarding](14%20-%20User%20Flow_%20Onboarding.md) | First-launch experience and setup flow |
| 15 | [User Flow: Background Agent & Queue System](15%20-%20User%20Flow_%20Background%20Agent%20&%20Queue%20System.md) | Offline queue handling and nightly pruning |
| 16 | [Security & Privacy](16%20-%20Security%20&%20Privacy.md) | Encryption layers, threat model, and key management |
| 17 | [Development Guide](17%20-%20Development%20Guide.md) | Kotlin project structure and build instructions |