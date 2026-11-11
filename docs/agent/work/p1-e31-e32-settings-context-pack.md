# Branching Analysis: P1-E 31 (Settings Catalog Backend) & P1-E 32 (Context Pack Full Budget & Compact Mode)

## Goal and Invariant
- **Goal:**
  1. Implement Blueprint 20 Context Pack budget management:
     - Exact token budget constants from Blueprint 20 §3.1.
     - Compact mode per Blueprint 20 §3.7 (low token budget ~2700 tokens when compact mode is active or context window <= 8k).
     - Recent chat window guarantee: at least 6 messages kept (or all if < 6) per Blueprint 20 §3.1 & §3.4.
     - Fast token approximation (chars / 3.5, clamped to 2.0 chars/token for dense Unicode/Indic text).
  2. Implement Blueprint 52 Settings Catalog Backend:
     - Strongly-typed `SettingDef` catalog for the 12 sections in Blueprint 52 §3.5.
     - Scope metadata ('S' synced vs 'D' device-local).
     - Range/type validation for keys (`day.start`, `day.end`, `ai.temperature`, `ai.compact_mode`, `persona.intensity`, etc.).
     - Section-level reset to defaults for sections 4-8.
- **Invariants:**
  1. Core module purity: `:core` must never import `android.*`.
  2. Newest user message is always preserved in recent history walk (H3 invariant).
  3. Minimum 6 messages in recent chat preserved when history exists (Blueprint 20 §3.4).
  4. Account-like data (providers, encryption keys, raw messages, soul facts) is never wiped by section resets.
  5. Zero-vibration hardware protocol strictly preserved.
  6. DB stays at v6; settings persist cleanly in `app_meta` / `setting` tables.

## Hypothesis Table
| # | Hypothesis | Prediction | Cheapest Check | Kill Condition | Result |
|---|------------|------------|----------------|----------------|--------|
| H1 | Context pack token budget can use character counting without token conversion | Chars-to-tokens conversion varies by language, leading to overflow on Unicode text | Test with Hindi/CJK text vs English | English 1500 tokens != 1500 chars | REJECTED: Must use token approximation formula (chars / 3.5 for ASCII, 2.0 for Unicode) |
| H2 | Compact mode should cut all sections proportionally | Trimming core rules or now block breaks agent execution | Check Blueprint 20 §3.1 | Core rules and Now block can NEVER be dropped | REJECTED: Only active nodes (700->300), recent chat (1500->600), soul (600->300) are trimmed in compact mode |
| H3 | Settings catalog can be defined as static typed metadata in `:core` | Allows unit testing of validation, default values, and section resets without Android dependencies | Unit test `SettingsCatalog` in `:core` | Android-specific classes leak into catalog | ACCEPTED: Pure Kotlin `SettingDef` and `SettingsCatalog` in `:core` |
| H4 | Section reset can simply delete keys from `Storage.saveSetting` | Resetting keys to default means either restoring default value or deleting row so fallback applies | Test getSetting after reset | Missing key returns default value | ACCEPTED: Deleting or writing default value restores catalog default |

## Angles (one line each, mandatory)
- **Data/schema:** No DB migration needed; `setting` table in `AndroidStorage` and `SharedFakeStorage` stores key-value pairs.
- **Concurrency/lifecycle:** `ContextBuilder` runs on `ioDispatcher`; `SettingsCatalog` is immutable/stateless metadata.
- **Failure paths:** Invalid setting value rejects with `ValidationResult.Error` and preserves existing setting value.
- **User-visible behaviour:** Compact mode reduces prompt tokens significantly for low-cost/fallback models; settings reset restores sensible defaults.
- **Privacy/security:** Sensitive keys (API keys, passphrase) have `scope = Scope.DEVICE_LOCAL` and are excluded from section resets.
- **Testability:** 100% testable in JVM unit tests with `FakeClock` and `SharedFakeStorage`.
- **Blast radius:** Additive changes in `:core/context` and `:core/settings`; backward-compatible defaulted parameters in `ContextBuilder`.
- **Platform (Android 14, OEM):** Pure Kotlin, fully platform agnostic.
- **Model behaviour (what a real LLM would send):** Smaller prompt in compact mode reduces latency and avoids 4k/8k context window exhaustion on budget models.

## Evidence Log
- Baseline test suite: 399 core tests + 13 app unit tests = 412 tests passing.
- Blueprint 20 §3.1 specifies: Core rules + persona 500, Soul 600, Now block 150, Open proposals 100, Since-last-seen 200, Active nodes 700, Recent chat 1500. Total ~5,050.
- Blueprint 20 §3.7 specifies: Compact mode: Active nodes 300, Recent chat 600, Soul 300, Total ~2,700.
- Blueprint 52 §3.5 specifies: 12 sections, `SettingDef` with key, default, scope, notes, validation.

## Decision: Chosen Change, Rejected Options and Why
- **Chosen:**
  1. Add `ContextBudget` and `compactMode` support to `ContextBuilder`.
  2. Implement minimum 6-message preservation guarantee in `ContextBuilder`.
  3. Create `com.anchor.core.settings.SettingDef`, `SettingSection`, and `SettingsCatalog` in `:core`.
  4. Create `SettingsManager` in `:core` with typed get, set, validation, and section reset.
- **Rejected:**
  - Creating separate database tables for settings catalog.

## Pre-mortem: "It is a week later and this fix caused a bug; what was it?"
1. *Bug:* A chat turn with 5 very long messages exceeds recent chat budget and drops messages, violating the 6-message minimum guarantee.
   *Mitigation:* Ensure newest-first collection guarantees the first 6 messages (or all available if <= 6) are kept, only truncating beyond 6 when over budget.
2. *Bug:* Compact mode is turned on permanently by an unknown setting key name mismatch.
   *Mitigation:* Use explicit typed key `SettingsCatalog.AI_COMPACT_MODE = "ai.compact_mode"`.
3. *Bug:* A section reset resets provider API keys.
   *Mitigation:* Section reset is explicitly restricted to sections 4 to 8 per Blueprint 52 §3.4; section 2 (AI/Providers) is strictly excluded.
