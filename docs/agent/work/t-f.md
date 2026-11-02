# Task T-F: Home Quote Slots (D-108) Branching Analysis

**Date**: 2026-11-02  
**Task**: T-F (D-108)  
**Module**: `:core` and `:app`  

---

## 1. Goal and Invariant

### Goal
Implement quote slots per Decision D-108 and Blueprint 40:
1. Four fixed time slots by 24h clock:
   - `MORNING` (06:00 - 11:59)
   - `AFTERNOON` (12:00 - 16:59)
   - `EVENING` (17:00 - 21:59)
   - `NIGHT` (22:00 - 05:59)
2. Table `quote_slot (slot TEXT NOT NULL, day_key TEXT NOT NULL, text TEXT NOT NULL, generated_at INTEGER NOT NULL, state_hash TEXT, source TEXT NOT NULL, PRIMARY KEY (slot, day_key))`.
3. Preset pool of generic quotes per slot: instant fallback, zero network, zero blocking.
4. Asynchronous live LLM quote replacement: generates ~1h before slot change or lazily in background, max 2 regenerations per slot.
5. Storage interfaces in `Storage.kt`, `SharedFakeStorage.kt`, `AndroidStorage.kt`.
6. Database version bumped from 3 to 4 with idempotent `onUpgrade`.
7. `QuoteManager` in `:core` orchestrating slot lookup, preset fallback, and generation state.
8. Comprehensive unit tests in `:core` and schema/migration tests in `:app`.

### Invariant
1. D-108 strictly satisfied: Preset quotes display first, live quotes swap asynchronously; never block UI on LLM output.
2. Max 2 regenerations per slot.
3. Zero `android.*` imports in `:core`.
4. Idempotent SQLite migrations across V1 -> V4, V2 -> V4, and V3 -> V4.
5. Database and memory operations run off main thread.

---

## 2. Hypothesis Table (4 approaches across layers)

| # | Hypothesis / Approach | Prediction | Cheapest Check | Kill Condition | Result |
|---|---|---|---|---|---|
| **H1 (Pure Kotlin QuoteManager in `:core` with SQLite Persistence)** | Define `QuoteSlot`, `QuoteSource`, `Quote` in `:core:models`. Implement `QuoteManager` in `:core` with deterministic slot calculation and async generator hook. Store in `quote_slot` table (V4). | Fast, 100% testable in JVM unit tests, clean UI contract, non-blocking UI. | `./gradlew :core:test` + `:app:compileDebugAndroidTestKotlin`. | Complex Android WorkManager coupling in `:core`. | **CHOSEN** |
| **H2 (Hardcoded In-Memory Quotes Only, No SQLite Table)** | Don't create `quote_slot` table; compute random preset on every app launch in ViewModel. | Quick to write. | Check D-108 and session0-plan. | Rejects explicit D-108 schema requirements (`quote_slot` table, `state_hash`, `source=preset|live`). | Killed |
| **H3 (Blocking LLM Generation on Slot Transition)** | Wait for LLM response before rendering Home quote; fallback only on network timeout. | Live quote is always ready if LLM is online. | Time to render Home screen. | Delays UI rendering by 1-3 seconds, flashes blank screen; violates D-108 directive. | Killed |
| **H4 (WorkManager Direct Service inside Core Module)** | Embed Android `CoroutineWorker` inside `:core` module for background polling. | Standard Android background execution. | Compile `:core`. | Violates modular architecture: zero `android.*` in `:core`. | Killed |

---

## 3. Angles (Mandatory one-line checks)

- **data/schema**: Table `quote_slot` with composite primary key `(slot, day_key)`. Bumps DB version to 4 with clean migration.
- **concurrency/lifecycle**: Quote lookup is synchronous/instant (returns cached or preset); LLM generation runs in background coroutine without blocking UI.
- **failure paths**: If LLM fails or is unconfigured, preset quote remains active gracefully; never throws to UI.
- **user-visible behaviour**: Home screen always displays an inspiring quote appropriate for the current time of day without delay.
- **privacy/security**: Preset quotes are offline; LLM generation prompts contain only privacy-sanitized prompt context.
- **testability**: `QuoteManagerTest` in `:core` verifies time-of-day slot mapping, preset determinism, and async live swap with fake transport.
- **blast radius**: Isolated to quote feature; additive schema migration has zero effect on existing tables.
- **platform (Android 14, OEM)**: Compatible across all versions, independent of OEM alarm/sleep restrictions.
- **model behaviour**: Generation is throttled (max 2 per slot) to avoid burning user token limits.
