# Branching Analysis: Full Codebase Audit Remediation (v1 -> v3)

Date: 2026-11-12
Authors: Antigravity Agent & Reviewer Team

## Goal and Invariants
- **Goal**: Remediate all 13 findings across Priority 0, Priority 1, and Priority 2 from the Full Codebase & Agentic Harness Audit Report.
- **Invariants**:
  1. Pure Kotlin in `:core` (zero `android.*` framework dependencies).
  2. Absolute zero raw audio persistence in SQLite or disk (RAM-only ring buffers).
  3. Absolute zero vibration across all notification channels and alerts (`enableVibration(false)`).
  4. Proposal-Only Invariant for Ambient (can only create `status = proposed`, `source = ambient`; cannot mutate or close existing items; spoken commands treated as data).
  5. User-Presence Gate Invariant (speech uploaded to STT/extraction only if user speaks $\ge t_{lo} = 0.60$; third-party noise/TV discarded in RAM).
  6. Never silent restart of microphone foreground service (starts only on user tap; heartbeat posts "Tap to resume").
  7. Local-only biometrics & privacy (voiceprint and ambient staging tables excluded from export allowlist; numbers scrubbed to `[number]`; zero raw database UUIDs leaked to model prompts/tools).

## Hypothesis Table (min 4, different layers)

| # | Hypothesis | Prediction | Cheapest Check | Kill Condition | Result |
|---|---|---|---|---|---|
| H1 | `HybridRetriever` leaks local_only items because vector scoring filters only for status != archived/cancelled without checking `!node.localOnly` or `node.status != done`. | Adding `!node.localOnly` and `node.status != "done"` in `HybridRetriever.kt` blocks private and closed items from vector hits. | Check lines 48-56 of `HybridRetriever.kt`. | If `searchMemoryFts` already filters them and vector hits don't bypass it, H1 is dead. But lines 48-56 directly hydrate `node` from vector hits. | Confirmed (H1 alive & verified). |
| H2 | Raw database UUIDs leak to provider prompts because `aliasFor` uses `"${prefix}_$targetId"` and `ContextBuilder` outputs `node.id` directly. | A shared `AliasRegistry` generating sequential per-turn aliases (`n1`, `n2`, `s1`, `p1`, `m1`) with bidirectional mapping prevents raw UUID leaks while allowing tool arguments to resolve cleanly. | Search `"${prefix}_$targetId"` in `Gateway.kt` and `node.id` in `ContextBuilder.kt`. | If tests fail when raw IDs are replaced with `n1`, `s1`, alias resolution breaks. | Confirmed (H2 alive & verified). |
| H3 | `AndroidStorage` drops `node.origin` and corrupts `attrs` on write/update because SQL queries hardcode `'extracted'` and overwrite `attrs` with pinned JSON. | Adding `origin` to `NODE_COLS`, updating insert/update SQL statements, and merging `"pinned"` into existing `attrs` JSON preserves all attributes. | Inspect `NODE_COLS` and `createNodeAndCommit` in `AndroidStorage.kt`. | If SQLite schema lacks `origin`, H3 dies. But schema line 27 in `MineDatabaseHelper` defines `origin TEXT`. | Confirmed (H3 alive & verified). |
| H4 | `DirectBootReconciler` deletes recurring reminders because it calls `deleteReminder` without inspecting `rrule`. | Checking `rem.rrule != null` and rescheduling via `RecurrenceEngine.nextOccurrence()` before falling back to `deleteReminder` preserves recurring reminders across direct-boot fires. | Inspect `DirectBootReconciler.kt` line 40. | If `DirectBootAlarmStore` already handles recurrence, H4 is dead. But `DirectBootReconciler` directly executes `storage.deleteReminder(rec.id)`. | Confirmed (H4 alive & verified). |
| H5 | `eraseNode` leaves armed alarms because it deletes from `node` and `reminder` without notifying `AlarmManager` or `DirectBootAlarmStore`. | Calling `alarmScheduler.cancel(rem.id)` and `directBootStore.removeAlarm(rem.id)` before deleting rows eliminates orphan fires. | Inspect `AndroidStorage.eraseNode` line 384 and `Gateway.handleEraseNode`. | If `AlarmReceiver` auto-drops missing reminders cleanly without notifying, alarms still ring with generic intent text. | Confirmed (H5 alive & verified). |
| H6 | `AndroidExportManager` drops `learned_pref`, `signal_log`, `place`, and `day_digest` because they are omitted from `EXPORTED_TABLES` and `exportTable` calls. | Adding them to `EXPORTED_TABLES` and export loop ensures complete backup round-trip, and notifying `ChatController` on import reloads `UiState`. | Inspect `EXPORTED_TABLES` in `AndroidExportManager.kt`. | If tables are device-local by design (like ambient staging), exporting is prohibited. But learned preferences, places, signal logs, and day digests are user memory. | Confirmed (H6 alive & verified). |

## Angles (one line each, mandatory)
- **data/schema**: Adding missing columns to `NODE_COLS` (`origin`), merging `attrs` JSON cleanly, and adding memory tables (`learned_pref`, `signal_log`, `place`, `day_digest`) to `EXPORTED_TABLES`.
- **concurrency/lifecycle**: Guarding `ChatController.sendText` with `sendMutex` rather than spinning on `currentStreamJob`; preventing double-send between startup capture drain and outbox drain.
- **failure paths**: Wire `storage.markOutboxFailed()` with exponential backoff on non-offline failures; never mark batches answered on tool claim replacements.
- **user-visible behaviour**: Replace crammed `PrimaryTabRow` with `ScrollableTabRow`; wire Kanban board action buttons ("Start", "Done", "Reopen") to real `Gateway` actions.
- **privacy/security**: True sequential per-turn aliases (`n1`, `s1`, `p1`, `m1`) in prompts and tool outputs; zero raw UUIDs leaked; filter `localOnly` from `HybridRetriever`.
- **testability**: Assert `assertFalse(body.contains(rawDatabaseNodeId))` and `assertFalse(body.contains(rawDatabaseSoulId))` in `PrivacyAuditE16Test.kt`.
- **blast radius**: All alias changes must remain bidirectional so tool execution resolves back to real entity IDs without breaking existing tools.
- **platform (Android 14, OEM)**: Runtime permission checks for `RECORD_AUDIO`, `READ_PHONE_STATE` safe try/catch in telephony checks, and `FOREGROUND_SERVICE_MICROPHONE` service types.
- **model behaviour**: Lenient parsing must handle nested tool call JSON braces; `ACTION_CLAIM_RE` must anchor on assistant action phrases without false-positive matching on normal words ending with "done".

## Decision: Chosen Change & Rejected Options
- **Chosen**:
  1. Priority 0: Fix `HybridRetriever` privacy/filter checks; create `AliasRegistry` for `ContextBuilder` & `Gateway` to strictly eliminate raw DB IDs in prompts/tools; update `AndroidStorage` node column persistence (`origin`, `attrs` merging); fix `DirectBootReconciler` recurring reminder rescheduling; cancel alarms in `eraseNode`; expand `AndroidExportManager` exported tables and trigger post-import UI refresh.
  2. Priority 1: Eliminate double-send between startup capture and outbox; wire `markOutboxFailed` backoff; fix `ACTION_CLAIM_RE` regex and `EnvelopeParser` balanced-brace parser; fix `searchMemoryFts` result interleaving to avoid soul fact / chat starvation.
  3. Priority 2: Wire `NudgeEngine` quiet hours and `ConsolidationEngine` day rollover; wire Board action buttons and `PrimaryScrollableTabRow`; wire voice model fallback and real ambient pipeline with honesty guards.
- **Rejected**:
  - Hard-deleting audit rows or logging raw text in DE storage.
  - Using random numbers for biometric voiceprints.
  - Relying on non-greedy regex for nested JSON parsing.

## Pre-mortem (3 items)
1. *Pre-mortem 1*: The sequential alias mapping (`n1`, `n2`) desynchronizes if the model references an alias across multiple turns.
   *Mitigation*: Retain aliases in a thread-safe LRU cache (up to 200 items) across recent turns so prior-turn references still resolve cleanly.
2. *Pre-mortem 2*: Merging `attrs` JSON throws on invalid existing JSON.
   *Mitigation*: Wrap JSON parse in `runCatching` and fall back to building fresh JSON with `pinned` if corrupt.
3. *Pre-mortem 3*: Interleaving FTS results in `searchMemoryFts` drops high-ranking node results if query is specifically targeting tasks.
   *Mitigation*: Allocate proportional quotas (e.g., 50% nodes, 30% soul facts, 20% chat chunks) or round-robin interleaving so diverse content is surfaced without completely penalizing high relevance.

## Break-the-fix Input
- Input: `What did I say yesterday?` when 20 task nodes contain the word "yesterday".
  - *Breakage*: If quotas are hardcoded without checking relevance, or if aliases collide between node `n1` and reminder `r1`.
  - *Defense*: Aliases have unique prefix types (`n`, `s`, `p`, `m`), and FTS interleaves non-empty result sets gracefully.
