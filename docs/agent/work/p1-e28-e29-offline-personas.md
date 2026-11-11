# Branching Analysis: P1-E 28 (Offline Outbox, Batching, Templates, Stale Jobs) & P1-E 29 (Personas & Core Rules Layering)

## Goal and Invariant
- **Goal:**
  1. Complete Blueprint 41 offline handling: outbox stale-job dropping (`dropStaleJobs` for expired `opener_text` past deadline), offline message batching templates, and trigger-specific offline templates.
  2. Implement Blueprint 24 persona system: 5 presets (`Companion`, `Cofounder`, `Coach`, `Chill Friend`, `Minimal`) + `Custom`, 4-layer system prompt assembly (Core Rules -> Persona -> Soul communication_style -> Context), Tone Safety override for distress/grief/crisis, and persona-colored offline templates.
- **Invariants:**
  1. Core module purity: `:core` must never import `android.*`.
  2. Core Rules are permanent anchor layer 1: never editable, never overridable by custom personas or user prompts.
  3. Tone Safety override: In crisis/grief/distress, sarcasm and harshness are stripped regardless of persona voice.
  4. Outbox durability & idempotency: Idempotency keys prevent duplicate jobs; expired opener jobs past deadline drop cleanly without crashing.
  5. Zero-vibration hardware protocol strictly preserved.
  6. DB stays at v6; settings/custom personas persist in `app_meta` without requiring a schema migration while the physical phone is unavailable.

## Hypothesis Table
| # | Hypothesis | Prediction | Cheapest Check | Kill Condition | Result |
|---|------------|------------|----------------|----------------|--------|
| H1 | Custom persona storage requires a new SQL table `persona` and DB v7 migration | Higher risk of on-device migration defects while phone is sleeping/offline | Check `app_meta` and `setting` tables in DB v6 | `app_meta` can store JSON blobs up to SQLite text limits | REJECTED: Avoid DB bump, store in `app_meta` (same precedent as D-137) |
| H2 | Core rules can be concatenated naively with persona prompt | Custom persona prompt might contain prompt-injection attacks ("ignore previous rules") | Test custom persona containing "Ignore rules" | Persona rules leak or override core safety | REJECTED: Strict sanitization and explicit layer bounding tags (`<core_rules>`, `<persona>`, `<style_preferences>`) |
| H3 | Offline templates can be unified with persona voice selection | A single TemplateEngine can pick variants by trigger type and active persona tone | Unit test variant resolution across all 5 preset tones | Repetitive text or missing slots | ACCEPTED: Deterministic hashed selection with persona tone support |
| H4 | Stale outbox jobs can be dropped on outbox polling/drain | Calling `dropStaleOutbox(now)` marks expired `deadline_at` jobs as `STATUS_DROPPED` | Unit test with simulated clock passing `deadline_at` | Jobs execute after expiry or stay pending indefinitely | ACCEPTED: `dropStaleOutbox(now)` updates expired pending jobs |

## Angles (one line each, mandatory)
- **Data/schema:** DB v6 outbox table already has `deadline_at` and `status`; `app_meta` persists `persona.active` and `persona.custom.<id>`.
- **Concurrency/lifecycle:** Storage queries run on `ioDispatcher`; `PersonaEngine` and `TemplateEngine` are pure in-memory functions/classes with zero shared mutable state.
- **Failure paths:** Corrupted or invalid custom persona JSON degrades gracefully to default `Companion`; expired jobs cleanly transition to `dropped`.
- **User-visible behaviour:** Persona changes voice immediately on next message; offline nudges/reminders speak in selected persona tone rather than robotic generic text.
- **Privacy/security:** Core rules layer 1 strictly enforced; custom personas cannot disable tone safety, cannot leak local-only facts, and cannot authorize model to execute unapproved actions.
- **Testability:** 100% testable in JVM unit tests with `FakeClock`, `SharedFakeStorage`, and deterministic template seed testing.
- **Blast radius:** Isolated to `:core` persona and nudge/template modules; existing `ChatController` and `ContextBuilder` integrate smoothly via defaulted parameters.
- **Platform (Android 14, OEM):** Pure Kotlin in `:core`, zero platform APIs, fully compatible with Motorola Android 14 and all OEM runtimes.
- **Model behaviour (what a real LLM would send):** Model receives clean layered system prompt; persona gives voice without altering tool call schemas or envelope contracts.

## Evidence Log
- Baseline test suite: 386 core unit tests + 13 app unit tests = 399 tests passing in 7 seconds.
- Checked `docs/blueprints/24-personas.md`: Specifies 5 presets + Custom, prompt layering, tone safety rule 9, token budget ~400 tokens for persona.
- Checked `docs/blueprints/41-offline-fallback.md`: Specifies outbox job types, priorities, `opener_text` deadline expiry, and offline templates.

## Decision: Chosen Change, Rejected Options and Why
- **Chosen:**
  1. Create `com.anchor.core.persona.Persona` model and `PersonaPresets` with Companion, Cofounder, Coach, Chill Friend, Minimal.
  2. Create `PersonaEngine` with prompt layering (`composeSystemPrompt`), custom persona validation, and tone safety guard.
  3. Create `TemplateEngine` with trigger variants (reminders, nudges, check-ins) and persona tone adaptation.
  4. Extend `Storage` with `dropStaleOutbox(now: Long): Int` and implement in `SharedFakeStorage` and `AndroidStorage`.
  5. Wire `PersonaEngine` and `TemplateEngine` into `ContextBuilder` and `ChatController`.
- **Rejected:**
  - Creating a separate SQLite `persona` table (rejected to avoid DB schema migration v6->v7 while phone is away).
  - Hardcoding templates inside `ChatController` (rejected because templates must be persona-aware and modular).

## Pre-mortem: "It is a week later and this fix caused a bug; what was it?"
1. *Bug:* A custom persona prompt with special markdown or angle brackets breaks Envelope Mode JSON parsing.
   *Mitigation:* Persona prompt is validated to exclude raw JSON instruction overrides and length-capped at 1600 characters.
2. *Bug:* An expired `opener_text` outbox job is dropped, but its corresponding reminder notification was never recorded.
   *Mitigation:* Stale outbox dropping only affects deferred background LLM opener requests; notification delivery is always local and immediate.
3. *Bug:* Clock skew backward causes active outbox jobs to be falsely marked as dropped.
   *Mitigation:* Stale check checks `deadline_at < now` strictly where `deadline_at` is positive and non-null.
