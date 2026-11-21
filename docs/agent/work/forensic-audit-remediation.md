# Branching Analysis: Forensic Codebase & Architecture Audit Remediation (v1–v4 Non-Sync)

## Goal and Invariant
- **Goal:** Eliminate all theater code, phantom features, crash bugs, silent data loss, resource leaks, and test suite contradictions identified in the forensic audit.
- **Invariants:**
  1. Pure Kotlin in `:core` without Android dependencies.
  2. Capture-first invariant: A user's sent message is NEVER cleared or discarded without persistence to `raw_message` and `outbox` or offline execution.
  3. Secret task isolation: Tasks with `local_only = true` are NEVER exposed to external AI prompts or counts (default `includeLocalOnly = false`).
  4. Audio hardware safety: Zero unreleased `AudioRecord` instances; zero concurrent access between ambient service and in-app mic; RAM-only audio buffering.
  5. Deterministic undo: Every mutation tool (`upsert_topic_note`, `link_note_reference`, `schedule_agent_wakeup`) must write an undoable `raw_event`.
  6. Battery/IPC efficiency: No 15-minute forced alarm thrashing; no rogue notifications on empty transitions.

## Hypothesis Table
| # | Hypothesis | Prediction | Cheapest Check | Kill Condition | Result |
|---|---|---|---|---|---|
| H1 | `LiteRtEmbedder` runs `runPrefill` on a text-generation engine and discards the output | Discarding `runPrefill` wastes CPU/RAM without computing embeddings | Inspect `LiteRtEmbedder.kt:105-108` | `session.runPrefill` returns an embedding tensor | Confirmed: returns `Unit` and calls `generateDeterministicVector` |
| H2 | `AndroidExportManager` queries `registered_alarms` instead of `alarm_registry` | Query throws SQLiteException caught silently, dropping alarm cancellation | Grep `registered_alarms` vs `CREATE TABLE alarm_registry` | `registered_alarms` table exists in schema | Confirmed: table does not exist in schema; table is `alarm_registry` |
| H3 | `sendText` without API key wipes `composerDraft` and drops user message | Sending without API key loses message forever if user skips onboarding | Inspect `sendText` lines 776-788 | Message is saved in `raw_message` or `outbox` | Confirmed: returns before `insertRawMessage` or `enqueueChatTurn` |
| H4 | `MineApp.startStorageInitialization` fails permanently if first deferred completed exceptionally | Retry broadcast returns same failed deferred without re-attempting init | Check `sharedStorage.isCompleted` behavior on failure | `isCompleted` is false after `completeExceptionally` | Confirmed: `isCompleted` is true when failed; bricks retry |
| H5 | `ContextSensingManager` sends "Have a great walk!" on every Wi-Fi drop | Unconditionally notifies on `CONTEXT_LEFT_HOME` even with 0 errands and 0 tasks | Inspect `handleTriggers` line 344 | Only notifies when `errands.isNotEmpty()` | Confirmed: calls `walk.message` and notifies unconditionally |
| H6 | Device tests assert `localOnly` nodes are filtered from queries, while core test asserted inclusion | Device test fails when `local_only = 0` was removed from queries | Inspect `AndroidStorageTest.kt:1367-1384` | Device test allows `localOnly` in active counts | Confirmed: explicit assertion `assertFalse(counts.totalOpen > 0)` |

## Angles
- **Data / Schema:** `alarm_registry` is the correct table name in SQLCipher; `raw_event` needs records for topic notes, note references, and wakeups; `local_only` parameter added with default `false`.
- **Concurrency / Lifecycle:** Atomic mic arbitration via `AudioRecorderHelper.isForegroundMicActive`; `DisposableEffect` for recording in Compose; self-healing `CompletableDeferred` in `MineApp`.
- **Failure Paths:** API key missing falls back to `RuleBasedQuickAdd` or `enqueueChatTurn`; provider 429/500/timeout falls back to outbox rather than orphaning user message.
- **User-Visible Behaviour:** Hands-Free speaks receipts/errors and transitions to `LISTENING`/`IDLE` instead of freezing; calendar has long-press bottom sheet with reminder toggles; markdown export includes living notes and day digests.
- **Privacy / Security:** `local_only` tasks strictly excluded from LLM prompts and counts; web search tool notice injected when globe toggle is disabled.
- **Testability:** Both `:core:test` and `:app:connectedDebugAndroidTest` pass because `includeLocalOnly = false` is the default.
- **Blast Radius:** Targeted fixes in `:core` and `:app` with zero breaking changes to existing contracts.
- **Platform (Android 14, OEM):** Direct boot aware; no battery/IPC thrashing from 15-minute forced reconciliation; thermal status and battery percentage checks in Ambient service.
- **Model Behaviour:** Gemma prompt formatted with `<start_of_turn>` tokens rather than raw OpenAI JSON.

## Evidence Log
- `LiteRtEmbedder.kt:105-108`: calls `session.runPrefill(input)` and returns `generateDeterministicVector(text)`.
- `AndroidExportManager.kt:339`: `SELECT reminder_id FROM registered_alarms` inside swallowed `try/catch`.
- `ChatController.kt:776-787`: `sendText` returns with error before `raw_message` insertion or `enqueueChatTurn`.
- `MineApp.kt:113`: `if (sharedStorage.isCompleted) return sharedStorage` returns failed deferred.
- `AndroidStorageTest.kt:1367-1384`: Asserts `localOnly` nodes are excluded from `getNodesDueBetween`, `getActiveNodeCounts`, `getUrgentNodes`.

## Decision: Chosen Change, Rejected Options and Why
- **Chosen:**
  1. Remove wasteful `session.runPrefill` from `LiteRtEmbedder` until an embedding tensor API exists; use mathematical projection with explicit logging.
  2. Cache `AndroidOnDeviceLlmEngine` instance; parse chat messages and format Gemma chat prompt.
  3. Add `alarm_sound_uri` support in `AlarmReceiver`; add long-press bottom sheet in `CalendarViewScreen`; add topic notes and day digests in `ReadableMarkdownExporter`; enqueue to outbox on provider failures.
  4. Implement Worker LLM extraction, missing sleep blockers (`CHARGING_ONLY`, `THERMAL_PAUSE`, `PAUSE_PLACE`, `DAILY_CAP_REACHED`), and session second counters in `AmbientForegroundService`.
  5. Fix table name to `alarm_registry` in `AndroidExportManager`; re-embed after import.
  6. Universal capture-first in `ChatController.sendText`.
  7. Self-healing `CompletableDeferred` in `MineApp`.
  8. De-duplication and deleted note protection in `runWorkerAiExtraction`.
  9. Sane window guard on monotonic `day_key` in `AndroidStorage`.
  10. AudioRecord resource cleanup in `AudioRecorderHelper` + mic arbitration.
  11. Hands-Free state machine deadlock fix.
  12. Fix rogue notifications and step counting in `ContextSensingManager`.
  13. Make 15-min sweep use `force = false` in `BootReceiver`.
  14. Write `raw_event` for `upsert_topic_note`, `link_note_reference`, `schedule_agent_wakeup`.
  15. Add week units and calendar dates in `DateResolver`.
  16. Add `includeLocalOnly: Boolean = false` to node query APIs.
  17. Tell LLM that `web_search` is disabled when globe toggle is off.
- **Rejected:**
  - Removing `local_only` flag entirely (violates user privacy).
  - Leaving `registered_alarms` query in place (silently breaks alarm cleanup on import).
  - Wiping user drafts without queueing when API key is missing (violates capture-first blueprint).

## Pre-mortem
1. *Pre-mortem 1:* If `DateResolver` explicit calendar date parsing conflicts with relative date parsing (e.g., "Nov 20" vs "in 20 mins"), prioritize unambiguous patterns and test thoroughly.
2. *Pre-mortem 2:* If `includeLocalOnly: Boolean = false` breaks an existing unit test expecting `localOnly` items, ensure tests explicitly pass `includeLocalOnly = true` if they are testing local-only retrieval.
3. *Pre-mortem 3:* If Hands-Free state transitions trigger prematurely on receipt updates, ensure only terminal receipts or non-loading states transition back to `LISTENING`.

## Breaking Input Defense
- Input: Message sent with empty API key $\rightarrow$ Enqueued in outbox, stored in `raw_message`, user draft preserved or handled by `RuleBasedQuickAdd`.
- Input: Calendar date "Nov 20 at 5pm" $\rightarrow$ Parsed to exact timestamp.
- Input: Consecutive Wi-Fi drops with no errands $\rightarrow$ Silent transition, zero spam notifications.
