
# Decision Log

Status: core     Version: 0.1     Depends on: all

## 1. Purpose

Record what was decided, why, and what was rejected, so nobody (including future us) has to guess.
Format: `D-nnn | date | decision | reason | alternatives rejected`.
Never edit an old decision. Add a new one that supersedes it.

## 2. Log

| ID    | Date       | Decision                                                                                                                                      | Reason                                                                    | Rejected                                 |
| ----- | ---------- | --------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------- | ---------------------------------------- |
| D-001 | 2026-10-20 | The LLM never deletes or edits by similarity. It states an intent, code resolves it.                                                          | A wrong delete from cosine similarity is unacceptable ("which dinner?").  | LLM passes node IDs or free-text matches |
| D-002 | 2026-10-20 | Nothing is hard-deleted. Statuses and tombstones plus an operation log.                                                                       | Everything must be reversible.                                            | Hard delete                              |
| D-003 | 2026-10-20 | Memory is SQLite with `nodes` and `edges` tables, not a graph database.                                                                   | Single file, trivial export, enough at personal scale.                    | Neo4j, other graph stores                |
| D-004 | 2026-10-20 | Raw messages are stored verbatim forever. Prompts use a fixed budget and retrieval.                                                           | Recall of any past word, constant per-turn cost.                          | Summaries only                           |
| D-005 | 2026-10-20 | Two data layers: raw (immutable) and derived (rebuildable).                                                                                   | Improve extraction without risky migrations.                              | Single mutable store                     |
| D-006 | 2026-10-20 | The scheduler is code. LLM writes words only.                                                                                                 | The LLM must not be the timer.                                            | LLM-driven timers                        |
| D-007 | 2026-10-20 | Two LLM roles (Chat, Worker) plus a non-LLM scheduler.                                                                                        | Separation of conversation from data work.                                | One monolithic agent                     |
| D-008 | 2026-10-20 | OpenAI-compatible endpoints only, user-provided. No preset providers.                                                                         | Freedom and simplicity.                                                   | Native per-vendor SDKs                   |
| D-009 | 2026-10-20 | Embeddings run on device, so vectors do not depend on the chat endpoint.                                                                      | Privacy, cost, offline.                                                   | Endpoint embeddings                      |
| D-010 | 2026-10-20 | Whole local database is encrypted (SQLCipher).                                                                                                | User requirement.                                                         | Plain SQLite                             |
| D-011 | 2026-10-20 | Distribution via GitHub Releases only. Signing key must be backed up from day one.                                                            | No store review. Updates require the same key.                            | Play Store                               |
| D-012 | 2026-10-20 | Min Android 11 (API 30).                                                                                                                      | Reach versus effort.                                                      | API 29                                   |
| D-013 | 2026-10-20 | Soul updates are manual-approve by default, switchable to auto-accept.                                                                        | Protect the user's character definition from misreads.                    | Always auto                              |
| D-014 | 2026-10-20 | Day rollover and week start are user-set.                                                                                                     | People live at different hours.                                           | Fixed midnight                           |
| D-015 | 2026-10-20 | App lock is optional and off by default.                                                                                                      | User choice.                                                              | Mandatory lock                           |
| D-016 | 2026-10-20 | Name: app `Mine`, AI `MineAI`, package `com.anchor.mine`.                                                                               | Chosen by author. Package ID is permanent.                                | n/a                                      |
| D-017 | 2026-10-21 | No user-facing notification cap. Self-regulation: one open thread, ignore-backoff, quiet hours, hidden safety fuse.                           | A hard cap could silence the one important nudge.                         | Daily cap                                |
| D-018 | 2026-10-21 | Reminder = alarm first, conversation second (online only). Offline = alarm only.                                                              | Alarms must always work.                                                  | Notification-only reminders              |
| D-019 | 2026-10-21 | Hard ceiling of 50 RPM per model, retries and tool rounds counted.                                                                            | Safety net over user-set limits.                                          | No ceiling                               |
| D-020 | 2026-10-21 | On HTTP 429, stop sending to that key for a user-set cooldown (default 15 min).                                                               | Avoid hammering and bans.                                                 | Immediate retry                          |
| D-021 | 2026-10-21 | Day window: soft recommendation 10-20 hours, no hard block, sarcastic message outside it.                                                     | People differ.                                                            | Hard limits                              |
| D-022 | 2026-10-21 | Session rolls over at day start.                                                                                                              | Fits "day and night" use.                                                 | Midnight rollover                        |
| D-023 | 2026-10-21 | Ambient listening is optional, off by default, planned for v3 to v4. Labels `user` and `other`. Audio never stored. Proposal-only output. | Privacy and safety.                                                       | Always-on autowrite                      |
| D-024 | 2026-10-21 | Ambient STT: cloud STT for all labeled segments, on-device as offline fallback.                                                               | On-device quality is weak, "yes/no" needs context.                        | Owner-only STT                           |
| D-025 | 2026-10-21 | EmbeddingGemma 2, text-only variant, 256-dim int8 for v1.                                                                                     | Small, good quality, can add other modalities later without re-embedding. | v1 model, MiniLM                         |
| D-026 | 2026-10-21 | v2 features ship as in-place updates behind flags, after 2-3 weeks of real v1 use.                                                            | Real use beats speculation.                                               | Separate v2 app                          |
| D-027 | 2026-10-21 | Blueprints live in a `blueprints` branch, one file per feature or aspect, with this template.                                               | Author's preference.                                                      | Wiki                                     |
| D-028 | 2026-10-21 | Rebuild jobs never overwrite rows the user touched (`origin` is `user_edit` or `approved`).                                             | User edits are the most trusted data.                                     | Full overwrite                           |
| D-029 | 2026-10-21 | Operational layer is a third bucket besides raw and derived.                                                                                  | Queues and alarms are neither history nor knowledge.                      | Mixing with raw                          |
| D-030 | 2026-10-21 | Strong lock (key bound to user authentication) is not offered. The lock is a UI gate.                                                         | Reminders must read data while the phone is locked.                       | Auth-bound database key                  |
| D-031 | 2026-10-21 | Chat AI may write through the gateway for explicit user commands. Worker handles implicit extraction and skips already-handled messages.       | In-turn commands need an in-turn result.                                  | Worker-only writes                       |
| D-032 | 2026-10-21 | Chat history is indexed as time-block chunks, not single messages.                                                                              | "yes" alone is unsearchable.                                              | Per-message embeddings                   |
| D-033 | 2026-10-21 | Chip taps call the gateway directly, with no model call.                                                                                       | Instant, deterministic, free.                                              | LLM-mediated chips                       |
| D-034 | 2026-10-21 | All learning is visible, explainable, bounded by user settings, resettable. Behavioral learning is statistics, and knowledge learning goes through Soul. | Safe "learn everything". | Hidden or LLM-memory learning |
| D-035 | 2026-10-21 | Explicit reminders are requested-class: exempt from open thread, backoff and fuse, and ring even outside the day window. AI-suggested reminders follow the ask or auto setting (default ask) and are moved into the day window. | A command is a command, and a suggestion is not. | One policy for all |
| D-036 | 2026-10-21 | Reminders re-check guards at fire time and transform (start becomes check) or suppress. | Stale reminders are noise. | Fire blindly |
| D-037 | 2026-10-21 | Alarm posts immediately with stored text. A fresh model opener (8 s timeout) becomes the chat message, with stored text as fallback. | An alarm must never wait for a model. | Wait for the opener |
| D-038 | 2026-10-21 | Backoff gap = min(min_gap x multiplier^step, ceiling). Defaults 60 min, 2.0, 6 h. Step halves at day start. Long silence mode after 3 silent days. | Adaptive restraint without hard caps. | Daily cap |
| D-039 | 2026-10-21 | Morning check-in is the delivery form of first_contact. Default day_start + 3 h, learned over time, inline on first open. | One mechanism, no duplicate messages. | Separate morning and first-contact messages |
| D-040 | 2026-10-21 | Evening check-in defaults to day_end - 90 min. Weekly review replaces it the evening before the week start. | Fits the author's day, and is editable. | Fixed clock times |
| D-041 | 2026-10-21 | Scheduled check-ins ignore min gap and backoff, but obey the day window and Pause. | They are user-configured rituals. | Treat like normal nudges |
| D-042 | 2026-10-21 | "Pause nudges" control (1 h, 3 h, until tomorrow). Critical items still pass. Reminders unaffected. | The user needs an off-switch without losing safety. | No pause |
| D-043 | 2026-10-21 | Check-in charts are native cards drawn from the database, with snapshots in message attrs. The model never produces numbers. | Correct numbers, offline, no hallucination. | LLM-generated charts |
| D-044 | 2026-10-21 | Alarms: setAlarmClock for alarm tiers, windowed registration, boot and time re-arm, sweeper, self-test. Direct-boot safety net is v1.1. | OEMs kill alarms. | Single-layer alarms |
| D-045 | 2026-10-21 | Briefing facts are computed by code, narration is validated against them, and offline narration is template-based. | Trust and offline. | Free-form LLM summaries |
| D-046 | 2026-10-21 | Hidden fuse: 1 AI-initiated delivery per 5 min, 8 per hour, 40 per day, 60 proactive LLM calls per day. Requested reminders exempt. | Bug and budget protection. | No safety net |
| D-047 | 2026-10-21 | Two sliding-window buckets (key and key+model), each clamped to 50 RPM. Background classes use at most 80 percent. All requests count. | Protects the key and keeps chat responsive. | Single bucket |
| D-048 | 2026-10-21 | 429 cooldown is per key, persisted, default 15 min, escalating on repeats, with a one-time manual retry for chat. | Avoids hammering and bans. | Per-request retry |
| D-049 | 2026-10-21 | Normalizer accepts string, null, and typed-part content, reasoning_content, think tags, and fragmented tool calls. Reasoning is never sent back. | Real providers differ (GLM sample). | Strict OpenAI only |
| D-050 | 2026-10-21 | A capability probe in the provider wizard sets streaming, tool mode, reasoning format, and strict history. | Non-technical users cannot tune flags. | Manual flags only |
| D-051 | 2026-10-21 | Fallback chain of up to 4 targets with a circuit breaker (3 failures, 5 min). 429 is not a breaker failure. | Resilience with clear semantics. | Unlimited chain |
| D-052 | 2026-10-21 | Messages sent offline are batched into one turn on reconnect, with their original times. | Saves RPM and avoids stale replies. | One reply per message |
| D-053 | 2026-10-21 | A reliability screen with self-test, latency measurement, and vendor guides is part of v1. | Reminders are the core promise. | Silent best effort |
| D-054 | 2026-10-21 | No in-app installer in v1. Update check opens the GitHub releases page. | Avoids install permissions and attack surface. | In-app updater |
| D-055 | 2026-10-21 | Migrations: additive first, expand-migrate-contract, pre-migration snapshot, refuse newer data. | Years of use without loss. | Destructive migrations |
| D-056 | 2026-10-21 | Unknown enum-like strings are tolerated everywhere. | Forward and backward compatibility. | Strict enums |
| D-057 | 2026-10-21 | Export: wrapped random key, JSON Lines, gzip then chunked AES-GCM, final-chunk flag. Supersedes the sketch in 10. | Streaming, tamper-evident, forward-compatible. | Single-key blob |
| D-058 | 2026-10-21 | Sync design: operation log plus row-level LWW with hybrid logical clocks, device-local derived indexes, QR pairing, file bundles then LAN. | No server, safe merges. | Re-extract on every device |
| D-059 | 2026-10-21 | Core logic in a pure Kotlin module for desktop reuse. | A full desktop client is planned. | Android-only code |
| D-060 | 2026-10-21 | No analytics or crash-reporting SDKs. Local diagnostics only. | Privacy promise. | Remote telemetry |
| D-061 | 2026-10-21 | Cleartext HTTP only for localhost or LAN endpoints, opt-in, with a warning. | Local models without weakening normal use. | Always HTTPS only |
| D-062 | 2026-10-21 | Each release publishes the APK SHA-256 and signing fingerprint. | Sideloaded distribution through chat apps. | None |
| D-063 | 2026-10-21 | App lock relock timeout default is 1 minute. | Balance of convenience and safety. | Immediate |
| D-064 | 2026-10-21 | A sensitive-moment flag suppresses playful and getting-to-know-you nudges for 24 hours. | Tone safety. | No suppression |
| D-065 | 2026-10-21 | One continuous timeline: today at the bottom, older days above with day headers, older days read-only, the composer always writes to the current day. | "A day is one conversation" without losing history. | Separate day screens |
| D-066 | 2026-10-21 | Time dividers by gap (default 45 min), part-of-day labels at 3 h or more. Blocks are time-only. A derived day_block table. | The user's design: time is the separator. | Context-based dividers |
| D-067 | 2026-10-21 | Scrubber rail built from blocks (sqrt-compressed), haptic ticks, floating time bubble, TalkBack actions. | Fast travel in a long day. | Time-proportional rail |
| D-068 | 2026-10-21 | Receipts are drawn from raw_event, never from model text. | The UI must be true. | Trust the reply |
| D-069 | 2026-10-21 | Day rule changes take effect at the next rollover. day_key is monotonic. day_rule table. | No split or reversed days. | Immediate rule changes |
| D-070 | 2026-10-21 | Streaming text is held in an operational draft. The assistant raw message is written on completion or stop. | Raw is append-only. | Write partial then update |
| D-071 | 2026-10-21 | One live turn at a time. Messages sent meanwhile are coalesced into a single follow-up turn. | RPM and coherence. | Parallel turns |
| D-072 | 2026-10-21 | Memory viewer has seven areas, plus Ambient when `flag.ambient` is on, all offline, all edits through the gateway as user_edit anchors. Remove (tombstone, 30-day Trash) is separate from Erase (scrub). | Trust and reversibility. | One delete |
| D-073 | 2026-10-21 | Onboarding: only consent and day setup are mandatory. Everything else is skippable and resumable. The app works without an AI as a local notebook. | Low friction for non-technical users. | Hard gate on AI setup |
| D-074 | 2026-10-21 | Settings: one authoritative catalog, scope synced or device, advanced toggle, soft limits with persona messages, technical day window range 1 to 24 h. | One place to check everything. | Scattered settings |
| D-075 | 2026-10-21 | Soul seed via six optional onboarding questions or an interview in chat. Health answer can be local-only. | Cold start with consent. | Empty Soul |
| D-076 | 2026-10-21 | Quick capture: notification reply (v1), optional capture notification (off by default), launcher shortcut. Widget and share sheet in v1.1. | Lowest-friction input. | Widget first |
| D-077 | 2026-10-21 | License is GPL-3.0 (identifier GPL-3.0-or-later, confirmed) for the code and for the blueprints. | Free software, matches a hobby project shared with friends and anyone who finds it. | MIT, Apache 2.0, source-available |
| D-078 | 2026-10-21 | Model weights are never bundled or committed. They are downloaded, and their own terms are shown first. | Gemma terms are not GPL. | Bundling in the APK |
| D-079 | 2026-10-21 | Contributions use the Developer Certificate of Origin (sign-off), inbound equals outbound. | Lightweight, no paperwork. | CLA |
| D-080 | 2026-10-21 | The name Mine, MineAI and the logo are not licensed by GPL. | Avoid confusing forks. | None |
| D-081 | 2026-10-21 | Provider setup import (file or QR) is a v1.1 item. | Gets non-technical people past the API key screen. | v1 |
| D-082 | 2026-10-21 | The Welcome screen has a Quick setup that applies defaults for everything except the AI connection. | Low friction. | Full walkthrough only |
| D-083 | 2026-10-21 | v1 is done only when gates X-01 to X-10 in `90` are met. The v1.1 list is the single consolidated list in `90` 3.3. | One place to look. | Scattered markers |
| D-084 | 2026-10-21 | Blueprint set reconciles to as-built at app v1.0.0. `versionCode = MAJOR*10000 + MINOR*100 + PATCH`. Tags `vX.Y.Z` and `bp-X.Y.Z`. | Docs match the code. | Free-form versions |
| D-085 | 2026-10-21 | Every APK on GitHub has a matching source tag that stays available. | GPLv3 source duty. | Source only on the main branch |
| D-086 | 2026-10-21 | Sensing has two independent axes, place and motion, debounced, coarse, Wi-Fi first. Walking at home is a normal state and triggers nothing. No location trail. | The user's walking-at-home case, privacy. | One combined location state |
| D-087 | 2026-10-21 | Context nudges fire only when relevant open items exist. Conversations are not started while in a vehicle. | No chatter. Safety. | Greeting on every transition |
| D-088 | 2026-10-21 | Call nudge uses call state only: no call audio, no content, no number by default. The reply is typed or a voice note. VoIP calls are not detected in v2. | Android limits and privacy. | Recording calls |
| D-089 | 2026-10-21 | Voice input shows an editable transcript by default. Audio exists only in memory or a temporary file until transcribed, then it is wiped. | Cheap to fix a misheard word, privacy. | Auto-send |
| D-090 | 2026-10-21 | STT is its own provider slot with adapters and a probe. It uses the same limiter, the 50 RPM ceiling and the 429 cooldown. On-device sherpa-onnx is the fallback. | Providers differ. | Cloud only |
| D-091 | 2026-10-21 | TTS is on demand only: after voice input or on Speak. Kokoro through sherpa-onnx, system TTS as fallback, no promise of emotion. | The user's decision, honest limits. | Always-on speaking |
| D-092 | 2026-10-21 | Model manager, VAD, STT adapter and speech engine are shared between voice and ambient. Weights are downloaded, never bundled. | Build once, licensing. | Separate stacks |
| D-093 | 2026-10-21 | Ambient service states: Off, Sleeping, Listening, Processing. Started only by a user tap. Never restarted silently, with a heartbeat that posts "tap to resume". | Android limits. | Boot auto-start |
| D-094 | 2026-10-21 | Speaker labels are `user` and `other`, with confidence tiers. Short low-confidence replies become "attribution uncertain" proposals. | The user's "yes or no" case. | Dropping non-owner speech |
| D-095 | 2026-10-21 | STT upload happens only for windows that contain a `user` segment (the presence gate), default on. | Keeps strangers and TV off the cloud. | Upload all speech |
| D-096 | 2026-10-21 | Ambient lifecycle: audio in RAM only, transcripts at most 48 h or until review, unreviewed proposals 7 days, accepted items keep a short summary. | Privacy. | Keeping transcripts |
| D-097 | 2026-10-21 | The voiceprint is device-local, excluded from export and sync, deletable and re-recordable. | Biometric data. | Syncing it |
| D-098 | 2026-10-21 | Number-like sequences are scrubbed from ambient transcripts before extraction. | Cards and one-time codes. | No scrubbing |
| D-099 | 2026-10-21 | Ambient consent is separate and versioned, and asked again when the STT host changes. Responsibility for use rests with the user, and the consent says so. | Honesty. | One shared consent |

## 3. Changelog

- 0.1 (2026-10-21): initial log from design discussions.
