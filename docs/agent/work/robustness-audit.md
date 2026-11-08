# Robustness Audit: "Mine"

Research-only audit of `core/src/main/java` and `app/src/main/java` (47 files, ~7,800 LOC).
Scope excludes known items from `docs/agent/STATE.md`: **O-1** (CREATE silently fails for `set alarm ...`), **O-2** (provider fields append instead of replace), **B5** (export/import UI + passphrase dialog not implemented).
Lens: never silently lose data, never silently drop a reminder, never claim success that storage does not back, degrade instead of crash.

Line numbers refer to the working tree at audit time (branch `master`, HEAD `eb63e21` plus uncommitted modifications).

---

## Findings

Severity: **CRITICAL** = user loses a reminder or data with no signal; **HIGH** = likely user-visible wrong state / unrecoverable without data loss; **MEDIUM** = crash or misbehaviour under a plausible condition; **LOW** = latent, needs a specific trigger or is currently unreachable.

| ID | Sev | Area | File:line | Description | Suggested fix |
|----|-----|------|-----------|-------------|---------------|
| R-01 | CRITICAL | Reminders | `core/.../alarms/AlarmReconciler.kt:44` | Overdue reminders are filtered out of reconcile (`fireAtMillis >= now - 60000L`) so nothing re-arms or reports them. | Pass overdue reminders through to the sweeper explicitly instead of silently excluding them. |
| R-02 | CRITICAL | Reminders | `core/.../reliability/ReliabilityManager.kt:22` | Missed-reminder sweeper only runs from `BootReceiver`/`MainViewModel` (`MainViewModel.kt:65`); if the app is not opened after the miss, the user is never told. | Schedule a periodic sweeper (WorkManager / alarm) irrespective of app launch. |
| R-03 | CRITICAL | Reminders | `app/.../alarms/BootReceiver.kt:26-54` | `try/finally` has no `catch`: if `sharedStorage.await()` throws (e.g. `KEYSTORE_LOST`), reconcile + sweeper + quote rescheduling are skipped for the whole boot with no retry. | Catch, record a reliability issue, and schedule a bounded retry instead of letting the exception kill the receiver. |
| R-04 | CRITICAL | Data integrity | `app/.../storage/AndroidStorage.kt:607-620` | `undoChangeAndCommit` does not restore `title`, so the snapshot-equality gate at `Gateway.kt:1212` refuses later undos. | Write `title` in the undo `UPDATE` and check `rowsAffected`. |
| R-05 | HIGH | Truthfulness | `app/.../storage/AndroidStorage.kt:577-605` | `updateNodeAndCommit` updates `WHERE id = ?` without checking `rowsAffected`, then inserts the event anyway, yielding a receipt for a no-op. | Check `rowsAffected == 1` and abort before writing the event. |
| R-06 | HIGH | Reminders | `app/.../storage/AndroidStorage.kt:804-806` | `clearAlarmRegistry` deletes registry rows without cancelling the OS alarms, orphaning armed PendingIntents. | Read rows first, cancel each via the scheduler, then delete. |
| R-07 | HIGH | Reminders | `core/.../ui/ChatController.kt:219-229` | Self-test on `ScheduleResult.Failed` leaves the `reminder` row in the DB unarmed; id is `test_<now>` so two in the same millisecond collide. | Delete the row on failure and use a UUID suffix for the id. |
| R-08 | HIGH | Startup | `core/.../ui/ChatController.kt:89-137` | The init block performs ~10 sequential storage reads with no try/catch; any failure leaves the UI blank with no error. | Wrap each read in `runCatching` and surface a single `globalError`. |
| R-09 | HIGH | Truthfulness | `core/.../gateway/Gateway.kt:468-472` | Returns `Committed` with an empty undo token, so the UI offers an Undo that can never work. | Emit `Committed` without undo metadata when no event was written. |
| R-10 | HIGH | Data integrity | `app/.../export/AndroidExportManager.kt:296-306` | Import swap closes the helper and `copyTo`s over the live DB with no backup; a crash or throw mid-copy truncates the live DB (currently latent under B5, but the mechanism is the one that will land). | Copy live -> `.bak`, copy temp -> live, reopen, verify, then delete `.bak`. |
| R-11 | HIGH | Startup | `app/.../MineApp.kt:37` + `app/.../MainViewModel.kt:46` | `catch (Exception)` does not cover `Error`/OOM, so `sharedStorage` never completes and `await()` hangs forever with no error state. | `catch (Throwable)`, `completeExceptionally`, and render a fatal-error screen. |
| R-12 | MEDIUM | Reminders | `core/.../ui/ChatController.kt:203-212` | Cancel deletes the row, then calls `alarmScheduler.cancel`, then deletes the registry entry as three ungrouped steps; a throw in step 2 leaves an armed alarm with no row. | Reorder to cancel OS alarm first, then delete the row, and reconcile on failure. |
| R-13 | MEDIUM | UI | `app/.../MainActivity.kt:112-117` | `animateScrollToItem(totalItems - 1)` assumes list-size == count; guarded only by `totalItems > 0`. | Use `listState.layoutInfo.totalItemsCount` and clamp. |
| R-14 | MEDIUM | Crash | `core/.../nudge/CheckinBuilder.kt:39` | `dateResolver.format(it.dueAt!!)` inside a `getNodesDueBetween` loop; the query filters non-null but the `!!` is unguarded if the query changes. | Use `it.dueAt?.let { ... } ?: continue`. |
| R-15 | MEDIUM | Truthfulness | `app/.../MainActivity.kt:44-48` | POST_NOTIFICATIONS request has an empty result lambda; a denied permission silently disables all reminders with no in-app disclosure. | Persist the result and show a persistent banner ("reminders cannot notify"). |
| R-16 | MEDIUM | Provider | `core/.../provider/ProviderOrchestrator.kt:40,45` | On failover, `emit(StreamEvent.Clear)` wipes already-streamed text (`ChatController.kt:387-388`); the user sees the answer disappear and restart. | Only clear on the first failover, or keep the partial text and mark it superseded. |
| R-17 | MEDIUM | Time | `core/.../gateway/Gateway.kt:352-366` | Quiet-hours check compares minutes-of-day across midnight without a wrap branch, so a window such as 22:00-06:00 misclassifies. | Normalise to a wrap-aware `inQuietHours(nowMin, startMin, endMin)`. |
| R-18 | MEDIUM | Truthfulness | `core/.../gateway/Gateway.kt:764-786` | Confirmation matching is token-level, so a reply that mentions the affirmation word in a negating sentence confirms. | Require the affirmation to be the whole (short) message, or add explicit negation detection. |
| R-19 | MEDIUM | Resolver | `core/.../resolver/Resolver.kt:101` | A 0.30 text-match floor returns `NotFound` for an item the user can plainly see in the list, producing "I couldn't find that". | Lower the floor, or fall back to the top candidate as an in-chat clarification. |
| R-20 | MEDIUM | Quotes | `app/.../quotes/QuoteAlarmScheduler.kt:61` | `AlarmManager.set(RTC, ...)` is inexact and non-wakeup; under Doze the quote slot can fire hours late or not before the day rolls. | Use `setAndAllowWhileIdle(RTC_WAKEUP, ...)`. |
| R-21 | MEDIUM | Time | `core/.../time/DateResolver.kt:573-603` | Chip label computes `diffDays` by dividing raw millis by 86,400,000, which mislabels "Tomorrow" across a DST transition. | Compute calendar-day difference via `LocalDate` (or `DayRollover.computeDayKey`). |
| R-22 | LOW | Storage | `app/.../storage/AndroidStorage.kt:647-649` | `saveSoulFact` is an empty stub; callers believing it persisted get no error. Confirm by grepping for `saveSoulFact(` call sites. | Make it `saveSoulFactAndCommit`-equivalent or throw `UnsupportedOperationException`. |
| R-23 | LOW | Storage | `app/.../storage/AndroidStorage.kt:704` | `searchMemoryFts` catches all exceptions and returns empty, indistinguishable from "no results". Confirm by injecting a bad FTS query. | Return a typed error or log to `diag_log`. |
| R-24 | LOW | Storage | `app/.../storage/AndroidStorage.kt:332,389` | `IntentType.valueOf(...)` on deserialised strings throws on an unknown enum value (e.g. after downgrade). Confirm by inserting an unknown `intent`. | Use `runCatching { }` with a default. |
| R-25 | LOW | Storage | `app/.../storage/AndroidStorage.kt:293` | Uses `System.currentTimeMillis()` instead of the injected `Clock`, so tests/holiday skew diverge. | Inject `Clock`. |
| R-26 | LOW | Storage | `app/.../storage/AndroidStorage.kt:305,1016-1032` | `getPendingReminders` and the day/range queries have no `WHERE`/`LIMIT`; cost grows with history. Confirm by profiling at 10k reminders. | Bound with `LIMIT` and window predicates. |
| R-26 correction (2026-11-08) | — | Storage | `core/.../storage/Storage.kt`, `app/.../storage/AndroidStorage.kt` | Row above is partly wrong, verified against the tree. (a) `getRemindersBetween` already carried `WHERE fire_at >= ? AND fire_at <= ? ORDER BY fire_at ASC` — only the ceiling was missing, so it was a missing `LIMIT`, not a missing predicate. (b) The row omits a third unbounded read: `getActiveReminders(now)` had neither `LIMIT` nor `ORDER BY`, and it backs the Home "Active Reminders" list, so refresh order could shuffle. (c) `idx_reminder_fire` is created in `onCreate` only (`MineDatabaseHelper.kt:52`), never in `onUpgrade` — devices upgraded from v1 do not have the index that makes the new `ORDER BY fire_at` cheap. That last item is still open; it belongs with T19. | Closed by R-26 for the three reads: `LIMIT` added to all three, `ORDER BY fire_at ASC` added to `getActiveReminders`; truncation of the pending page is reported as `ScheduleResult.Failed("pending_reminders_truncated")` so a paged reconcile is never mistaken for a clean one. |
| R-27 | LOW | Gateway | `core/.../gateway/Gateway.kt:32-33` | Duplicate-call dedupe keyed on call hash also rejects two legitimately identical calls in one envelope (e.g. create two identical items). | Include index in the key, or allow N repeats up to the max call count. |
| R-28 | LOW | Gateway | `core/.../gateway/Gateway.kt:607-608` | `consumeProposal` + `saveProposal` are separate non-atomic calls outside the transaction. | Fold into the transactional path used by R12d. |
| R-29 | LOW | Gateway | `core/.../gateway/Gateway.kt:505-509,665-669` | Check-then-act on an existence probe before writing; a concurrent writer can slip in between. | Use the in-transaction CAS or a unique-constraint failure. |
| R-30 | LOW | Crash | `core/.../gateway/Gateway.kt:1033` | `effectiveDue!!` on a value computed earlier in the function; reachable only if the earlier branch changes. | Use a `requireNotNull` with a message. |
| R-31 | LOW | Undo | `core/.../gateway/Gateway.kt:1137` | Undo reopening a proposal compares timestamps; a backwards clock move can reopen an already-consumed proposal. | Compare `op_id`/state, not time. |
| R-32 | LOW | Crash | `core/.../gateway/Gateway.kt:1346-1351` | `Math.abs(Long.MIN_VALUE)` overflow on a duration. | Use `kotlin.math.absoluteValue` with saturation. |
| R-33 | LOW | Gateway | `core/.../gateway/Gateway.kt:165-166,250-251,283-284,859-860` | `priority`/`severity` are not clamped to documented ranges; an LLM-supplied 10^18 persists. | Clamp at parse time. |
| R-34 | LOW | Gateway | `core/.../gateway/Gateway.kt:914-971` | `resolvedNodeId` is computed but unused (dead code that suggests a dropped feature). | Delete or wire it. |
| R-35 | LOW | Gateway | `core/.../gateway/Gateway.kt:1320-1341` | BFS depth cap of 20 silently truncates graph traversal. | Return `Data(truncated = true)` so the caller/LLM knows. |
| R-36 | LOW | Concurrency | `core/.../provider/RateLimiter.kt:15-25` | `canRequest` and `recordRequest` are two separate mutex acquisitions. | Combine into one `tryAcquire()` under a single lock. |
| R-37 | LOW | Concurrency | `core/.../provider/RateLimiter.kt:19` | `stamps.removeAll { now - it > 60_000 }` never prunes if the clock moves backwards. | Also drop `it > now + skew`. |
| R-38 | LOW | Concurrency | `core/.../quotes/QuoteManager.kt:267` | `liveGenJob` check-then-act without a mutex can start two generators. | Guard with a `Mutex` or `compareAndSet`. |
| R-39 | LOW | Time | `core/.../quotes/QuoteManager.kt:107` | `endOfDayMs = startOfDayMs + 86400000L` is wrong on a DST day. | Compute via calendar arithmetic. |
| R-40 | LOW | Time | `core/.../time/DayRollover.kt:31-40` | `computeDayKey` adds a sampled offset then formats in UTC; a DST change between sampling and use shifts the key. | Derive the key with `LocalDate` in the rule's zone. |
| R-41 | LOW | Time | `core/.../time/DayRollover.kt:121-127` | An unknown `tz_id` silently falls back to `TimeZone.getDefault()`, changing the user's day boundary. | Clamp to the last known-good zone and record a reliability issue. |
| R-42 | LOW | Time | `core/.../time/DayRollover.kt:80-93` | The timezone-change branch can return a boundary earlier than `nextRolloverCurrent`. | Clamp `max(nextRolloverCurrent, newRuleBoundary)`. |
| R-43 | LOW | Tool calls | `core/.../gateway/EnvelopeParser.kt:57` | Arguments whose value is literally `"none"`/`"n/a"`/`"null"` are dropped, so a user-named item called "None" loses its arg. | Only drop `null`, not these strings. |
| R-44 | LOW | Network | `app/.../provider/AndroidHttpTransport.kt:23` | Unchecked cast to `HttpURLConnection` throws `ClassCastException` for a non-HTTP(s) URL. | `require(target.id.startsWith("https://"))` or handle `HttpsURLConnection`. |
| R-45 | LOW | Network | `app/.../provider/AndroidHttpTransport.kt:47` | `readLine()` on the response is unbounded; a malicious/huge body exhausts memory. | Cap by bytes read. |
| R-46 | LOW | Export | `app/.../export/AndroidExportManager.kt:358-364` | `exportTable` `when (cursor.getType(i))` has no BLOB branch and no `else`, so BLOB columns are silently omitted. | Add `FIELD_TYPE_BLOB` (base64) and an `else -> error(...)`. |
| R-47 | LOW | Context | `core/.../context/ContextBuilder.kt:115-122` | History loop `break`s on the first oversized message, discarding every older message. | `continue` instead of `break`. |
| R-48 | LOW | UI | `core/.../ui/ChatController.kt:40` | `DateResolver(clock, is24HourFormat = { false })` hardcodes 24h mode regardless of the user's locale. | Read the system setting and pass it through. |
| R-49 | LOW | Nudge | `core/.../nudge/NudgeEngine.kt:53` | `state.sentToday >= 40` cap depends on a reset site not visible in this file; if unwritten the cap persists across days. | Assert the reset in a test. |
| R-50 | LOW | Reset | `app/.../MainViewModel.kt:92-101` | Reset deletes the DB then the key then `killProcess`; a crash between the two steps leaves an encrypted DB without a key (unopenable). | Delete the key first (making the DB knowingly orphaned), then the DB. |
| R-51 | LOW | Quotes | `app/.../quotes/QuoteAlarmScheduler.kt:33` | `if (cal.timeInMillis > nowMs + 10_000L)` skips a slot if it fires slightly early. | Use `>=` or schedule for "now" when within 10s. |
| R-52 | LOW | Alarm state | `app/.../alarms/AndroidAlarmScheduler.kt:74-75` | `checkHealth()` returns `Armed` when `NotificationManager` is null, reporting healthy for an alarm that cannot notify. | Return `Failed("no_notification_manager")`. |
| R-53 | LOW | Alarm state | `app/.../alarms/AndroidAlarmScheduler.kt` (companion) | The `_lastFiredId` `StateFlow` is never cleared, so a stale fire id can be re-consumed by the next UI refresh. | Clear after the receiver has consumed it. |
| R-54 | LOW | Reminders | `app/.../alarms/AlarmReceiver.kt:87-113` | The catch-all fallback neither deletes the row nor records a fire, so the sweeper later emits a second, duplicate "Missed:" notification. | In the fallback, record `outcome = "errored"` before returning. |
| R-55 | LOW | Crash | `app/.../alarms/AlarmReceiver.kt:42` | Unchecked `as MineApp` cast. | `as? MineApp ?: return`. |

**Counts: CRITICAL 4, HIGH 7, MEDIUM 10, LOW 34 (55 total).**

---

## Top 10 in detail

### R-01 / R-02: a missed reminder produces no message at all
**Trigger.** User sets "pay rent at 9am" and never unlocks the phone that morning. The alarm fires, the notification is swiped away or the process is dead, and the user opens the app at 2pm.
**Experience.** They see the chat with no mention of the missed reminder. `AlarmReconciler.reconcile` filters `fireAtMillis >= now - 60000L` (`AlarmReconciler.kt:44`) so the past-due row is never re-armed or reported; the only "Missed:" producer, `ReliabilityManager.runSweeper` (`ReliabilityManager.kt:22`), is only called from `BootReceiver` and `MainViewModel` (`MainViewModel.kt:65`). If the app is opened days later, the sweep runs once, but if the device reboots into a `KEYSTORE_LOST` path (R-03) nothing runs at all.
**Smallest test.** `SharedFakeStorage` + `FakeClock`; insert a `Reminder(fireAt = now - 5min)` with no registry row and no fire; call `AlarmReconciler.reconcile(force = false)`; assert the reminder is returned in `results` or that `ReliabilityManager.runSweeper()` afterwards writes a `reminder_fire` with `outcome = "missed"`. Currently both assertions fail.

### R-03: a Keystore failure at boot silently disarms every reminder
**Trigger.** `DbKeyProvider` returns `KEYSTORE_LOST` (device factory-reset restore, Keystore invalidation, OEM key rotation). The device reboots.
**Experience.** `BootReceiver` (`BootReceiver.kt:26-54`) enters the `try`, `sharedStorage.await()` throws, the `finally` finishes the pending result, and reconcile/sweeper/quote rescheduling never happen. No notification, no diagnostic. All reminders stay unarmed until the user launches the app, and even then (R-11) the launch may hang.
**Smallest test.** Robolectric test: construct `MineApp` whose `sharedStorage` completes exceptionally with `KEYSTORE_LOST`; send `BOOT_COMPLETED`; assert `pendingResult.finish()` ran **and** that a reliability issue was recorded (currently the second assertion fails, and an exception is logged as uncaught).

### R-04: undo does not restore the title
**Trigger.** User says "rename 'project A' to 'project B'", gets a receipt, then says "undo".
**Experience.** `undoChangeAndCommit` (`AndroidStorage.kt:607-620`) restores every field except `title`. The node keeps the new name, but the after-snapshot JSON now matches what is in the DB only partially, which makes the snapshot-equality gate at `Gateway.kt:1212` refuse any further undo ("nothing to undo"). The user believes the rename was undone; it was not, and they cannot undo the next change either.
**Smallest test.** `SharedFakeStorage` (or `AndroidStorageTest`): `createNode(title="A")` -> `updateNode(title="B")` -> `undoChangeAndCommit(...)` -> assert `getNode(id)!!.title == "A"`.

### R-05: a receipt for an update that changed nothing
**Trigger.** Two devices / two rapid messages both edit the same node; the second edit races a completion that deleted (soft-deleted) the row.
**Experience.** `updateNodeAndCommit` (`AndroidStorage.kt:577-605`) runs `UPDATE node SET ... WHERE id = ?`, does not check `rowsAffected`, and then unconditionally inserts a `raw_event`. `ChatController` renders a receipt from `raw_event` (D-115), so the user sees "Updated ..." for a row that was not updated. Same root cause as R-04's broken undo chain.
**Smallest test.** Insert a soft-deleted node, call `updateNodeAndCommit` on the stale `Node`, assert the call throws / returns a typed failure and that `SELECT COUNT(*) FROM raw_event WHERE target_id = ?` is unchanged.

### R-06: orphaned OS alarms after `clearAlarmRegistry`
**Trigger.** Any code path that calls `clearAlarmRegistry` — used by data reset.
**Experience.** `AndroidStorage.kt:804-806` deletes registry rows only; the `PendingIntent`s registered with `AlarmManager` remain. Later a new reminder with the same id (`id.hashCode()` request code) hits `AndroidAlarmScheduler.schedule` and the old `PendingIntent` is reused; `cancel()` (`AndroidAlarmScheduler.kt:64-71`) uses `FLAG_NO_CREATE` so it sees the stale intent as "already armed" and reconciles to the wrong time.
**Smallest test.** Register two alarms, call `clearAlarmRegistry`, then `checkHealth()` on the first; assert `Failed`, not stale `Armed`.

### R-07: self-test leaves an unarmed reminder
**Trigger.** User presses "Run self-test" while a `setAlarmClock` is refused (exact-alarm permission revoked, OEM battery policy).
**Experience.** `ChatController.kt:219-229` saves the reminder row, `schedule()` returns `Failed`, the code records an issue but **does not delete the row**. The row sits in `getPendingReminders()` forever; the next boot reconcile tries again and, if still failing, records another issue. The user sees a phantom "Self Test Fired!" that never fires. The id `test_${clock.now()}` also collides on two presses within the same millisecond.
**Smallest test.** Fake scheduler returning `Failed("exact_alarm_denied")`; dispatch `RunSelfTest`; assert `storage.getPendingReminders()` is empty and one reliability issue is recorded.

### R-08: startup blank screen on any storage hiccup
**Trigger.** SQLCipher open failure, a corrupt FTS index, or a single `SQLiteException` on one of the ~10 reads in `ChatController` init (`ChatController.kt:89-137`).
**Experience.** The constructor throws inside `viewModelScope.launch`; `MainActivity` sees no state transition and renders an empty chat with no spinner, no retry, and no error. The user thinks the app was wiped.
**Smallest test.** `SharedFakeStorage` that throws on `getRecentMessages`; construct `ChatController`; assert `state.globalError != null` and that other reads still ran.

### R-09: an Undo button that cannot work
**Trigger.** Any `Committed` result produced without an event (e.g. the idempotent fast path).
**Experience.** `Gateway.kt:468-472` emits `Receipt(undoToken = "")`. `ReceiptFormatter` and the Undo UI check `undoToken.isNotBlank()`? They do not (see the fallback at `ReceiptFormatter.kt:140-142`). Pressing Undo sends an empty token; the server-side `hasUndo("")` returns false and the user gets "Cannot undo", contradicting the earlier success.
**Smallest test.** Call the fast path with a repeated identical call; assert the returned `ToolResult.Committed` has `undoToken == null`.

### R-10: import overwrites the live DB with no backup
**Trigger.** Import succeeds through verification, then the process dies (OOM, user force-stop, Doze kill) during `copyTo` (`AndroidExportManager.kt:296-306`). Currently latent because the UI is B5, but this is the mechanism that will land.
**Experience.** The live DB is truncated to a partial copy of the temp DB and the SQLCipher key still exists, so on next open the DB fails integrity check and the app either crashes or (per R-11) hangs; the user's history is gone with no `.bak`.
**Smallest test.** Inject a `File` copy hook that throws after 1 KiB; call import; assert the live DB is byte-identical to before and a `.bak` exists.

### R-11: `Error` during app init hangs every screen forever
**Trigger.** OOM on a large `getRecentMessages`, `StackOverflowError`, `NoClassDefFoundError` from a bad shrinker rule.
**Experience.** `MineApp.kt:31-42` and `MainViewModel.kt:43-84` only `catch (Exception)`; an `Error` leaves `sharedStorage` undone, so every later `await()` (`MainViewModel.kt:46`) suspends forever. The UI shows an empty screen and no error.
**Smallest test.** Fake init that throws `OutOfMemoryError`; `runTest` then `withTimeout(1s) { app.sharedStorage.await() }`; assert it throws rather than times out.

---

## Already handled well (do not "fix" these)

- **Transactional mutation paths** in `AndroidStorage` (`createNodeAndCommit`, `updateNodeAndCommit` inserts, `createReminderAndCommit`, `deleteReminderAndCommit`, `createEdgeAndCommit`, `deleteEdgeAndCommit`, lines 543-575, 622-634, 651-665, 892-947) — one SQLite transaction per operation; in-transaction proposal CAS (`consumeProposalInTx`, `ProposalAlreadyResolvedException`, R12d).
- **`ScheduleResult` is a sealed class**; `Armed` / `Failed` force callers to branch (no silent `Boolean`).
- **`ReliabilityIssueCodec`** (`AlarmReconciler.kt:115-154`) — bounded to 5, backward compatible with the legacy string and linked-list formats.
- **`ProviderOrchestrator`** — per-provider rate limiting, `Empty response from provider` detection, `CancellationException` rethrow, single surfaced "All providers failed".
- **`AndroidHttpTransport`** — 500-char error-body cap, `disconnect()` in `finally`, `runInterruptible`.
- **Export** — write to `.tmp`, verify with the importer, rename only on success (`AndroidExportManager.kt:104-155`); chunked AES-GCM with header-bound AAD + SHA-256 footer; `rebuildMsgChunks` is transactional.
- **Import** — KDF and chunk-size bounds, transaction rollback on decode failure (`AndroidExportManager.kt:334-335`).
- **`QuoteAlarmReceiver`** — defensive `as? MineApp`, `CancellationException` rethrow, unconditional re-arm in `finally`.
- **`ContextBuilder`** — explicit char/token budgets for nodes, proposals, soul facts, and history.
- **`NudgeEngine`** — backoff capped at 5, sent-today cap.
- **`PresetQuotes`** — `Math.floorMod` (D-118 fix).
- **`alarmDisclosureText`** (`ChatContract.kt:40`) — discloses the status-bar alarm icon honestly.
- **`DbKeyProvider`** — atomic tmp+rename, explicit `KEYSTORE_LOST` mode.
- **Schema** (`MineDatabaseHelper.kt`) — append-only triggers on `raw_message`/`raw_event`, FTS sync triggers created in `onCreate`/`onUpgrade`/`onOpen`, idempotent migrations.
- **`DateResolver.parseDuration`** — 366-day cap and `100_000` amount bound prevent overflow.
- **`PromptSanitizer`** / `EnvelopeParser` — fence stripping and `null` envelopes rejected (B8).
- **Home ranking** (`Storage.kt:151-162`) — deterministic tiebreak, no SQL ordering.
- **D-115 receipts** generated from the operation log, not from optimistic UI state.

---

## Note on overlap with known items

O-1 (CREATE silently fails for `set alarm ...`) shares the "silent no-op" shape with R-05 and R-09, but they are independent code paths: O-1 fails before a row is written, R-05/R-09 fail after a row is (or is not) written. Fixing O-1 will not fix R-05 or R-09. O-2 (append, not replace) is a model-provider issue and orthogonal.
