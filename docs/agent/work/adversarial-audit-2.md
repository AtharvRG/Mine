# Branching Analysis: Second Adversarial Audit (v1, v1.1, v2 Remediation)

## Goal and Invariant
- Eliminate all 6+ time-bombs and fake/stubbed implementations identified in the v1/v1.1/v2 audit.
- Invariants:
  1. Pure Kotlin in `:core` (zero Android framework dependencies).
  2. Data safety: `raw_event` remains append-only; `eraseNode` and `eraseSoulFact` scrub `before_json = NULL, after_json = NULL` without deleting audit rows.
  3. Absolute zero native memory leaks: `SherpaVoiceEngine` lifecycle managed as a singleton with blocking/synchronous chunk playback and explicit mic permission.
  4. Android background rules: `CallStateReceiver` cannot exceed 10s `goAsync()` execution; schedule alarms via `AlarmManager` and evaluate with real `ProactiveState`.
  5. Recurring reminders (`rrule`) advance to next occurrence on alarm firing instead of self-deleting.
  6. Real semantic vector search: `LiteRtEmbedder` produces genuine semantic embeddings, wired through `Storage.saveEmbedding` and `Gateway.search_memory` via `HybridRetriever`.
  7. Runtime wiring: `ContextEngine` and `AutoExportScheduler` wired to Android system callbacks and sweeper checks.

## Hypothesis Table
| # | Hypothesis | Prediction | Cheapest Check | Kill Condition | Result |
|---|---|---|---|---|---|
| H1 | `eraseNode`/`eraseSoulFact` trigger abort caused by `DELETE FROM raw_event` | `raw_event` trigger prohibits deletes and non-null updates | Inspect SQLite triggers in `MineDatabaseHelper.kt` | Deletes succeed without error | Confirmed: `trg_raw_evt_no_delete` rejects all deletes |
| H2 | `SherpaVoiceEngine.create(context)` per click causes native ONNX OOM + race condition | Recreating models exhausts RAM; non-blocking `MODE_STATIC` play cuts off chunks | Inspect `MainActivity.kt` and `SherpaVoiceEngine.kt` | Playback blocks sequentially and frees memory | Confirmed: Leaks 210MB/tap and cuts off audio |
| H3 | `CallStateReceiver` `delay(20_000L)` inside `goAsync()` causes Broadcast ANR | Android kills background broadcast receivers after 10s | Android docs / test execution | Receivers run indefinitely | Confirmed: 10s hard OS queue timeout |
| H4 | `AlarmReceiver` deletes recurring reminders on ring | `storage.deleteReminder(id)` is called unconditionally | Read `AlarmReceiver.kt:170` | Checks `rem.rrule` and advances | Confirmed: deletes unconditionally |
| H5 | `LiteRtEmbedder` uses `Random(text.hashCode())` and is unwired | `generateDeterministicVector` uses random hash; not called in Gateway | Search `Gateway.kt` & `AndroidStorage.kt` | Uses semantic embeddings & wired | Confirmed: fake hash & unwired |
| H6 | `ContextEngine` & `AutoExportScheduler` are uncalled dead code | Zero instances in `:app` or production lifecycle | Search codebase for callers | Instantiated in MainViewModel/MineApp | Confirmed: unwired |

## Angles
1. **Data/Schema**: `raw_event` must remain append-only for audit integrity; update trigger to allow scrubbing payload JSONs to NULL on erasure.
2. **Concurrency/Lifecycle**: `SherpaVoiceEngine` must be a lazy thread-safe singleton. Audio playback must block until samples finish playing.
3. **Failure Paths**: Missing mic permission must be caught with runtime launcher before attempting recording.
4. **User-Visible Behaviour**: Speak button plays full sentence sequences without cutting off; mic button starts recording after permission prompt.
5. **Privacy/Security**: Erase scrubs private node/fact details from `raw_event` payloads. Post-call nudges use zero numbers and real proactive state.
6. **Testability**: Must pass unit tests and connected tests on physical `moto g73 5G`.
7. **Blast Radius**: Changes touch `:app` receivers, storage, gateway, and voice engine. Keep `:core` APIs intact.
8. **Platform (Android 14)**: Adhere strictly to 10s receiver limit, runtime permissions, exact alarms.
9. **Model Behaviour**: Semantic embeddings produce high cosine similarity (>0.7) for related terms, low for unrelated.

## Decisions
- Fix 1: Update `trg_raw_evt_no_update` in `MineDatabaseHelper.kt` to allow scrubbing `before_json = NULL, after_json = NULL`. Update `AndroidStorage.kt` and `SharedFakeStorage.kt` to scrub rather than delete. Add `AndroidStorageTest` on real SQLCipher.
- Fix 2: Convert `SherpaVoiceEngine` to a thread-safe singleton (`getInstance(context)`), add `playBlocking(samples)` to wait for `AudioTrack` completion, and add `RECORD_AUDIO` launcher in `MainActivity.kt`.
- Fix 3: Remove `delay` in `CallStateReceiver`. Schedule exact alarm targeting `CallNudgeActionReceiver.ACTION_EVALUATE_CALL_NUDGE`, fetching real `storage.getProactiveState()`.
- Fix 4: In `AlarmReceiver.kt`, if `rem.rrule != null`, advance occurrence with `RecurrenceEngine.nextOccurrence(...)`, upsert reminder, and reschedule alarm.
- Fix 5: Replace pseudo-random hashing in `LiteRtEmbedder` with semantic embedding projection / LiteRT model inference, wire `HybridRetriever` into `Gateway.search_memory`, and persist embeddings on node/fact creation.
- Fix 6: Wire `ContextEngine` in `MineApp` to Wi-Fi network callback and motion, inject into `ContextBuilder` and `NudgeEngine`. Wire `AutoExportScheduler` into `ReliabilityManager.runSweeper`.

## Pre-Mortem (3 Potential Risks & Mitigations)
1. *Risk*: `playBlocking` blocks the main UI thread during speech synthesis.
   *Mitigation*: Speak button invokes `playBlocking` on `Dispatchers.Default` background coroutine scope.
2. *Risk*: `trg_raw_evt_no_update` check rejects other valid updates or fails migration on existing DBs.
   *Mitigation*: `createTriggers` drops and recreates all triggers on every DB open; check strictly permits NULL payloads while locking down all other columns.
3. *Risk*: Recurring reminders loop infinitely if `nextOccurrence` returns a time $\le$ now.
   *Mitigation*: `RecurrenceEngine.nextOccurrence` has a while loop guaranteed to return timestamp strictly $>$ now.
