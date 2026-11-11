# Branching Analysis: Bulletproof & Invisible Recovery Audit (Titanium Polish)

## Goal and Invariant
- Eliminate latency spikes, main-thread micro-stutters, database lock contention, memory leaks/boxing storms, unhandled model output glitches, and unpruned operational logs.
- Invariants:
  1. Main thread must have 0ms disk/DB I/O during object construction.
  2. Pure Kotlin `:core` domain with zero Android framework dependencies.
  3. Absolute zero vibration strictly maintained in all notification channels.
  4. Silent self-healing: model glitches auto-retry or fail gracefully without raw JSON or error banners.
  5. Immutable op log (`raw_event`) append-only integrity preserved.
  6. Zero audio on disk; memory-only PCM processing.

## Hypothesis Table
| # | Hypothesis | Prediction | Cheapest Check | Kill Condition | Result |
|---|---|---|---|---|---|
| H1 | `AndroidStorage.init` runs SQLCipher queries synchronously on constructor thread | Reading `app_meta` device_id triggers native library loading & key derivation on caller | Inspect `AndroidStorage.kt:37` | Initialization is lazy or asynchronous | Confirmed: `init {}` executes synchronous SQL queries |
| H2 | `MineDatabaseCallback.onOpen` re-creates triggers/indexes on every open | Rewriting schema catalog causes disk churn on every launch and receiver fallback | Inspect `MineDatabaseHelper.kt:163` | Only runs during onCreate/onUpgrade | Confirmed: `createIndexes` & `createTriggers` run unconditionally |
| H3 | `refreshUI()` issues up to 40 sequential N+1 queries for `hasUndo` | `loadReceipts` and `recentlyClosed` query `hasUndo(opId)` in loops | Inspect `ChatController.kt:109, 1689` | Done in a single batch query | Confirmed: maps over collections calling single-item queries |
| H4 | `AudioRecorderHelper` allocates millions of boxed `Float` objects | `sampleBuffer = mutableListOf<Float>()` boxes every 16kHz sample | Inspect `AudioRecorderHelper.kt:41, 99` | Uses primitive arrays or buffers | Confirmed: `mutableListOf<Float>()` allocates heap wrappers |
| H5 | Malformed/prose LLM outputs show raw JSON or false error receipts | `EnvelopeParser.parse` returns null on invalid JSON; `ChatController` dumps raw text or error | Inspect `EnvelopeParser.kt:30` & `ChatController.kt:1527` | Lenient extraction and auto-retry | Confirmed: raw output dumped to UI |
| H6 | OfflineException sets connectionError banner even when handled by quick-add | Line 1594 updates `connectionError` before evaluating quick-add | Inspect `ChatController.kt:1594` | Error banner suppressed if handled | Confirmed: banner set unconditionally |

## Angles
1. **Data/Schema**: Add `pruneOperationalLogs` to clean `diag_log` (7d), `reminder_fire` (90d), `outbox` (7d). Avoid index/trigger recreation in `onOpen` if 13 triggers exist.
2. **Concurrency/Lifecycle**: Add `PRAGMA busy_timeout = 5000` and `PRAGMA synchronous = NORMAL` in `onConfigure` to eliminate `SQLITE_BUSY` crashes under multi-receiver concurrency.
3. **Failure Paths**: Auto-retire poison-pill `outbox` rows in `drainQueuedTurns` when `queuedMsgId` is null or deleted from `raw_message`.
4. **User-Visible Behaviour**: Suppress `connectionError` when `OfflineException` is handled by rule-based quick add or outbox enqueue; flush last streamed characters before tool execution.
5. **Privacy/Security**: Audio focus pauses/stops voice playback when calls arrive or focus is lost.
6. **Testability**: Unit tests in `:core` and connected tests on `moto g73 5G`.
7. **Blast Radius**: Affects `AndroidStorage`, `MineDatabaseHelper`, `ChatController`, `AudioRecorderHelper`, `SherpaVoiceEngine`.
8. **Platform (Android 14)**: AudioFocusRequest API 26+ with legacy fallback; `PRAGMA busy_timeout = 5000` prevents SQLite locks during doze wakeups.
9. **Model Behaviour**: If model outputs prose claiming an alarm without a JSON envelope, loop 1 silently prompts the model to emit JSON before showing anything to the user.

## Decision: Chosen Change, Rejected Options and Why
- **Change 1 (AndroidStorage device_id)**: Make `deviceId` lazy and thread-safe (`getOrInitDeviceId()`), removing `init {}` block entirely.
- **Change 2 (MineDatabaseHelper onOpen & onConfigure)**: Add `busy_timeout = 5000` and `synchronous = NORMAL` in `onConfigure`. Guard `createTriggers` in `onOpen` with `SELECT COUNT(*) FROM sqlite_master WHERE type = 'trigger' >= 13`.
- **Change 3 (ChatController N+1 queries)**: Add `Storage.getUndoneOpIds(): Set<String>` executing a single `SELECT undo_of FROM raw_event WHERE undo_of IS NOT NULL`. Use in `loadReceipts` and `recentlyClosed`.
- **Change 4 (AudioRecorderHelper primitive buffer)**: Replace `mutableListOf<Float>()` with `ArrayList<FloatArray>` chunk list, copying to a single primitive `FloatArray` on stop.
- **Change 5 (Model recovery & cover-up)**:
  - Add fallback regex extraction in `EnvelopeParser.parse` when strict JSON decoding fails.
  - In `ChatController`, if `claimsAction && loops < 2`, silently feed back system instruction to output JSON and continue loop.
  - If `envelope == null` and raw output starts with `{`, extract reply prose instead of dumping raw JSON.
- **Change 6 (Streaming flush)**: Flush `extractReplyFromJsonStream` to `updateLastMessage` immediately when stream event collection finishes.
- **Change 7 (Poison-pill outbox)**: Retire outbox rows in `drainQueuedTurns` if `msgId == null` or `!hasRawMessage(msgId)`.
- **Change 8 (OfflineException cover-up)**: Catch `OfflineException` and clear `connectionError` if handled by quick-add or outbox enqueue.
- **Change 9 (Operational log pruning)**: Implement `Storage.pruneOperationalLogs(now)` and call from `ReliabilityManager.runSweeper()`.
- **Change 10 (Audio focus)**: Wire `AudioManager.requestAudioFocus` in `SherpaVoiceEngine` with ducking and loss callbacks.

## Pre-Mortem: "It is a week later and this fix caused a bug; what was it?" List 3.
1. *Risk*: `getUndoneOpIds` loads too many rows if the database has millions of undos over years.
   *Mitigation*: Filter `WHERE undo_of IS NOT NULL AND ts_utc > (now - 24h * 3600 * 1000L)` since undos are only valid within 24 hours per Blueprint 16.
2. *Risk*: Silent 1-turn retry loops indefinitely if the model repeatedly claims an action without tool JSON.
   *Mitigation*: Strictly gate silent auto-retry to `loops < 2`. On loop 2+, fall back to normal message completion.
3. *Risk*: `PRAGMA busy_timeout = 5000` causes an ANR if a transaction deadlocks.
   *Mitigation*: SQLite transactions in Mine are ultra-short and single-threaded on `ioDispatcher`. 5000ms handles normal WAL checkpointing and background receiver overlap seamlessly.
