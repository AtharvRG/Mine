# Titanium hardening — ordered, test-first remediation plan

Source: `robustness-audit.md` (55 findings, 2026-11-06) and `test-integrity-audit.md`
(17 divergences, D-01…D-17). **Every finding below was re-checked against the working tree
on 2026-11-07 before being scheduled.** The audit is a hypothesis list, not a work order.

Baseline at the time of writing (all measured, not assumed):

| Check | Command | Result |
|---|---|---|
| JVM core | `./gradlew :core:test` | 267 tests, 0 failures, 0 errors, 0 skipped |
| JVM app | `./gradlew :app:testDebugUnitTest` | 10 tests, 0 failures, 0 errors, 0 skipped |
| Device | `./gradlew :app:connectedDebugAndroidTest` + `…Verify` | `CONNECTED GATE: PASS - 63 test(s), 0 failed, 0 skipped (source: xml)` |

Gradle's own exit code on `connectedDebugAndroidTest` is **1 even when green** — AGP 9.4.0 /
UTP 32.4.0 defect, see D-123. The gate's XML verdict is the only truth.

---

## 1. Closed in this pass (proven failing-first on device)

All four share one root cause: `AndroidStorage` wrote an explicit UPDATE column list that did
not cover every mutable field, while `SharedFakeStorage` replaced the whole object. Core tests
therefore passed on mutations the real database silently dropped.

| ID | Defect | Red evidence (verbatim) | Fix |
|---|---|---|---|
| T-01 | `updateNodeAndCommit` UPDATE omits `type`, `title`, `local_only` | `ComparisonFailure: title must persist… expected:<[Renamed] title> but was:<[Original] title>` | Full column list; `executeUpdateDelete()` count guard |
| T-02 | `undoChangeAndCommit` UPDATE omits the same three columns | `ComparisonFailure: rename must land before undo can restore it expected:<[After] rename> but was:<[Before] rename>` | Full column list |
| D-03 | `getStaleNodes` has no `local_only` filter; the fake does | `AssertionError: local_only node must not leak through getStaleNodes` | `(local_only IS NULL OR local_only = 0)` |
| R-05 | A zero-row UPDATE still wrote a `raw_event`, yielding a receipt for a no-op | `AssertionError: update of a missing node must fail rather than report success` | `executeUpdateDelete()`; throw on `!= 1`; transaction rollback drops the event |

R-04 is the same defect as T-02 and is closed with it.

**Trap recorded for the next run:** the first R-05 fix used `db.rawQuery("SELECT changes()")`.
`SupportSQLiteDatabase` has no `rawQuery` — the build failed at `:app:compileDebugKotlin`, and
the connected run silently executed the **stale APK**, producing a confusing "only 1 test ran,
guard did not fire". `compileStatement(...).executeUpdateDelete()` is the correct API and
returns the count directly. Always confirm `:app:compileDebugKotlin` is `BUILD SUCCESSFUL`
before believing a device result.

---

## 2. Verified NOT a bug — do not schedule

| ID | Audit claim | What the code actually does |
|---|---|---|
| R-17 | Quiet-hours window misclassifies across midnight | There is **no** quiet-hours window in the codebase (`grep -ri quiet` on `core/src/main` + `app/src/main` finds only quote text and prompt strings). The cited lines are a within-day rule (`dayRule.startMinute`/`endMinute`), which is correct for a start/end window. |
| R-47 | `ContextBuilder` history loop `break`s on the first oversized message | Already fixed as H3: the loop `continue`s, newest-first, and always keeps the newest message. |
| R-07 | Self-test leaves an unarmed reminder row | Already fixed: the arm is attempted **before** `saveReminder`, and a `Failed` result cancels and writes no row (`ChatController.kt:228-250`). |
| R-22 | `saveSoulFact` silently does nothing | `saveSoulFact` is an explicit "obsolete stub" with **no production caller** (only one core test). Dead code, not a defect. |
| R-06 | `clearAlarmRegistry` orphans OS alarms | Confirmed in code, but **latent**: no production caller exists. Fix only when something calls it. |

---

## 3. Ordered work

Ordering rule: **never silently lose a reminder or data** first, then **never claim success
storage does not back**, then crashes/UI. Each item states the test that proves it — that test
must be written and seen RED before the fix.

### Tier 1 — CRITICAL: a reminder is silently lost

**H-01 · R-01 · Overdue reminders are excluded from reconcile**
`AlarmReconciler.kt:44` filters `fireAtMillis >= now - 60000L`, so a reminder that came due
while the device was off is never re-armed and never reported by reconcile.
*Test:* `AlarmReconcilerTest` — a pending reminder 10 minutes overdue must produce a
`ScheduleResult`/missed report, not be dropped. Currently RED.

**H-02 · R-02 · The missed-reminder sweeper only runs at boot or app open**
`runSweeper()` is called from exactly two places: `BootReceiver.kt:39` and
`MainViewModel.kt:65`. If the app is not opened after a miss, the user is never told.
*Test:* requires a periodic trigger (WorkManager or an alarm). Write the test against a
`SweeperScheduler` seam first: "a sweeper is registered with a period ≤ N minutes on app start
and on boot". Device test asserts an OS alarm/worker exists in `dumpsys`.

**H-03 · R-03 · A Keystore failure at boot disarms every reminder with no retry**
`BootReceiver.kt:26-54` is `try { … } finally { … }` with **no catch**. `sharedStorage.await()`
throwing (KEYSTORE_LOST) skips reconcile, sweeper and quote rescheduling for the whole boot.
*Test:* `BootReceiverTest` — a storage whose `await()` throws must still record a reliability
issue and schedule a bounded retry (assert the retry is scheduled). Currently RED.

**H-04 · R-10 · Import overwrites the live DB with no backup**
`AndroidExportManager.kt:296-306` closes the helper and `copyTo`s the temp file over the live
DB. A crash mid-copy truncates the live database. Latent under B5 (import UI not shipped), but
the mechanism is what will land.
*Test:* `ExportTest` — simulate a throw during the copy and assert the live DB is still
openable and row-count-identical. Currently RED.

### Tier 2 — HIGH: wrong state, or success claimed that storage does not back

**H-05 · R-09 · `Committed` returned with an empty undo token**
`Gateway.kt:468-472` — the `cancel` branch returns `ToolResult.Committed(…, "")`, so the UI
offers an Undo that can never work.
*Test:* `GatewayTest` — cancelling a proposal must yield either a usable undo token or a
`Committed` with no undo metadata. Currently RED.

**H-06 · R-11 · `catch (Exception)` misses `Error`, hanging every screen forever**
`MineApp.kt:37` and `MainViewModel.kt:46` catch `Exception` only; an `OutOfMemoryError` during
init leaves `sharedStorage` never completing, so every `await()` hangs with no error state.
*Test:* `MineAppTest` — force an `Error` from key generation and assert the flow completes
exceptionally and a fatal-error state is reachable. Currently RED.

**H-07 · R-08 · Startup blank screen on any storage hiccup**
`ChatController.kt:89-137` performs ~10 sequential storage reads in `init` with no try/catch.
*Test:* `ChatControllerTest` — a storage whose `getOpenProposals()` throws must leave the UI in
an error state, not blank. Currently RED.

**H-08 · R-12 · Cancel deletes the row before cancelling the OS alarm**
`ChatController.kt:208-219` deletes the row, then calls `alarmScheduler.cancel`. A throw in
step 2 leaves an armed alarm with no row. (The "cancel even when the row is gone" comment is
present, but the ordering is still row-first.)
*Test:* `ChatControllerTest` — a scheduler whose `cancel` throws must leave the reminder row
present (or reconcile it), never an armed alarm with no row. Currently RED.

### Tier 3 — MEDIUM: misbehaviour under a plausible condition

| ID | Finding | Test that must go RED first |
|---|---|---|
| H-09 | R-13 `animateScrollToItem(totalItems - 1)` assumes list size == count | Compose test: scroll with a mismatched item count must not throw |
| H-10 | R-14 `it.dueAt!!` in `CheckinBuilder.kt:39` | Unit test with a null `dueAt` row must not throw |
| H-11 | R-15 POST_NOTIFICATIONS denial is silent | `MainActivity` test: deny → a persistent banner is shown |
| H-12 | R-16 `StreamEvent.Clear` on every failover wipes streamed text | `ProviderOrchestratorTest`: failover after partial text must not erase it |
| H-13 | R-18 token-level confirmation matches negated sentences | `GatewayTest`: "no, don't set it" must not confirm |
| H-14 | R-19 0.30 text floor returns `NotFound` for a visible item | `ResolverTest`: a low-overlap but only candidate must ask, not say "not found" |
| H-15 | R-20 quote alarm uses inexact `set(RTC, …)` | Device: `dumpsys alarm` shows `RTC_WAKEUP` + `setAndAllowWhileIdle` |
| H-16 | R-21 `diffDays` by raw millis mislabels across DST | `DateResolverTest` across a DST boundary |

### Tier 4 — LOW: latent, trigger-specific, or cleanup

R-23, R-24, R-25, R-26, R-27, R-28, R-29, R-30, R-31, R-32, R-33, R-34, R-35, R-36, R-37,
R-38, R-39, R-40, R-41, R-42, R-43, R-44, R-45, R-46, R-48, R-49, R-50, R-51, R-52, R-53,
R-54, R-55. Each is a one-line assertion in an existing suite; batch them by file
(`AndroidStorage`, `Gateway`, `DayRollover`, `RateLimiter`, `AlarmReceiver`) so each batch is
one RED run.

---

## 4. Test-integrity follow-through (the real multiplier)

D-03 was the first of 17 fake-vs-real divergences actually closed. The remaining D-01…D-17 are
the reason core tests kept passing while the device dropped writes. **Each divergence must be
closed by a connected test that fails on the real DB**, not by editing the fake to agree.

Method that worked and should be repeated:

1. Grep the SQL in `AndroidStorage` for the method.
2. Write an `AndroidStorageTest` case that mutates through the real DB.
3. Run it on device; if it passes, the divergence is not real — record that.
4. Only then change the fake to match.

---

## 5. Open questions for the user

1. **H-02 needs a product decision**: periodic sweeper via WorkManager (needs a dependency and
   a manifest change) or a repeating inexact alarm (no new dependency, Doze-deferred)? The
   test seam differs.
2. **H-04 (import backup)**: ship the `.bak` swap now, or defer with B5 since import UI is not
   reachable? It is ~15 lines and removes a data-loss path.
3. **R-06**: delete `clearAlarmRegistry` (no caller) or implement the OS-cancel before
   deleting rows? Deleting is cheaper and honest.
