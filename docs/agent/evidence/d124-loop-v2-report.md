# D-124 — AlarmSelfTest 20× frequency report (loop v2)

Date: 2026-11-07
Harness: `scripts/tmp_alarmselftest_loop.py` (temporary, not part of the build)
Raw data: `docs/agent/evidence/d124-loop-v2/` (`summary.json`, `latency.json`, `runNN/`)
Console log: `docs/agent/evidence/d124-loop-v2-console.txt`

## 1. Failure rate

**0 failures in 20 runs.** Every run is a genuine green run, not a stale archive.

| measure | value |
|---|---|
| runs completed | 20 |
| runs with test results | 20 |
| runs with `staleArtifacts: true` | **0** |
| runs with any failing test | **0** |
| verdict source | `xml` in all 20 (XML recorded 3 tests, so the proto tie-breaker never engaged) |
| `TEST-*.xml` counts | `tests="3" failures="0" errors="0"` in all 20 |
| `test-result.pb` | 3 entries, status `[3, 3, 3]` (PASSED) in all 20 |
| `gradleExit` | `1` in all 20 |
| `test-result-exit-code.txt` | `1` in all 20 |
| wall time | 61.6 s – 75.4 s |

The three tests in every run are `testGentleReminderFires_SetsFlag`, `testAlarmFires_SetsFlag`,
`testAlarmClock_NextAlarmClockVerification`.

**D-123 reconfirmed 20× consecutively:** the Gradle task and the UTP exit-code file both say `1`
on runs whose XML and proto both say 3/0/0. The exit code carries no information about the tests.

**Upper bound on the flake rate:** 0/20 gives a 95% upper confidence bound of ~14% (rule of three,
3/20). The observed rate is *not* proven zero — it is proven below ~1 in 7. A 20-run loop cannot
distinguish "never happens" from "happens 1 in 30".

## 2. Where the time goes (per-tier fire latency)

All times are seconds measured from `TestRunner started: <test>` in the merged, time-sorted logcat.
"notif" is the app's `NotificationFeature` line for that specific reminder text (attributed by
text, never by nearest preceding start — see §4).

| tier | test duration (start→finish) | notification posted (start→notif) |
|---|---|---|
| GENTLE | 5.89 – 6.46, median 6.02 | 6.12 – 6.66, median 6.24 (n=19) |
| ALARM | 5.29 – 5.33, median 5.31 | 5.28 – 5.32, median 5.30 (n=18) |

Target fire time is `now + 5000 ms`, so:

- **ALARM fires at +280–320 ms over target**, with a spread of only 40 ms across 20 runs.
- **GENTLE fires at +1.12–1.66 s over target.** `setAndAllowWhileIdle` is the slow tier by ~1 s.
  run13's +1.66 s outlier coincides with a `PackageManager`/`RoleService` storm at `03:24:24.996`.

### 2a. The ALARM notification lands *at* the test's finish line

`A_marg` = (test finish) − (notification posted). Median **+7 ms**; range **−10 ms to +19 ms**.
In run04, run08 and run16 the notification was posted **after** the test had already finished.

This is the single most important structural fact in this report:

- `AlarmReceiver` posts the notification (line 76), deletes the row (77), **then** sets
  `lastFiredId` (82). The test polls `lastFiredId`, so it passes on a signal that is emitted
  *before* the system has finished posting the notification.
- Consequently the test's pass/fail signal is **not** the same event as "the notification appeared".
  A test that polled for the notification instead of `lastFiredId` would be measuring a different,
  later event and would be far more fragile.
- The GENTLE notification lands **after the test finished in all 20 runs** (5.89–6.46 s duration
  vs 6.12–6.66 s posting). The GENTLE test therefore *cannot* be validated from logcat at all — it
  only passes because it polls `lastFiredId`, which is set before the posting completes.

## 3. What this says about the old 1.5 s window (#131)

The ALARM tier fires at ~5.30 s against a 5 s target and the test finishes at ~5.31 s. Set against
the **old** 1.5 s fire time, the same +300 ms of lateness would put the fire at ~1.8 s inside a
1.5 s window — i.e. **the old window had only ~1.2 s of slack and the observed lateness is ~0.3 s**,
so the old window was not obviously too small on wake-up latency alone.

What the old window *did* leave exposed is the post-fire work the poll measures: `sharedStorage.await()`
(which on a cold process opens SQLCipher), `getPendingReminders()`, `notify`, `deleteReminder`,
`recordReminderFire`. None of that is visible in logcat because the app writes no `Log.*` lines.
**The flake is therefore still not explained** — but the 5 s / 20 s widening is justified on the
grounds that it removes the fire-latency component (~0.3 s ALARM, ~1.2 s GENTLE) from a budget that
was ~1.2 s total. It is *not* evidence that the old config failed.

## 4. Traps this loop exposed (logcat is a lossy, mis-attributing source)

**(a) Per-test logcat files are not sliced per test — they overlap.** Each
`logcat-<class>-<method>.txt` contains a window that includes neighbouring tests. All files for a
run must be merged and time-sorted before any ordering claim is made.

**(b) Nearest-preceding-start attribution is wrong.** The first `FlashNotifController` event after
`started: testAlarmFires_SetsFlag` normally carries the text **"Gentle Test Reminder"** — it is the
*previous* test's notification arriving late, not the ALARM one. Attribution must be by
notification **text** (`NotificationFeature: Mine Mine <text>`), not by proximity to a test start.
An earlier draft of this analysis attributed both notifications to the nearest start and produced
an ALARM latency of 0.17 s, which is simply the previous test's notification.

**(c) No single logcat line is a reliable delivery signal.** Across 20 runs:

| signal | source | present in |
|---|---|---|
| `FlashNotifController requestStartFlashNotification` | system_server | 2/2 in all 20 runs |
| `MediaFocusControl requestAudioFocus` | system_server | 2/2 in 17 runs; **1/2 in run08, run13, run17** |
| `NotificationFeature: Mine Mine <text>` | system_server on behalf of app | 2/2 in 17 runs; **1/2 in run07, run18, run19** |

run07 is the clearest case: the ALARM notification **was** posted (flash at `03:17:31.260`, audio
focus at `03:17:31.329`, and `NuPlayerDriver … created, clientPid(2298)` immediately after — the
notification's sound started playing), yet its `NotificationFeature` text line is absent. The
notification was delivered; the log line was lost. `dumpsys logd` is empty on this ROM and UTP's
files carry no `chatty`/`dropped` marker, so the loss cannot be attributed to a specific cause —
only observed.

**Rule: absence of any one logcat line is not evidence of a delivery failure.** Only
`FlashNotifController` was present 2/2 in every run, and even that is a single source.

**(d) The top-level `test-result.pb` is populated on healthy runs.** It held 3 PASSED entries in all
20 runs. Any earlier note claiming it is always empty is wrong.

## 5. Gate gap found (not fixed here)

`gradle/connected-results-gate.py` globs `TEST-*.xml` and `test-result.pb` at the **top level** of
the results dir only. UTP also writes `results_dir/<device>/test-result.pb`. On this toolchain the
top-level artifacts were sufficient in all 20 runs, so the gap did not bite — but if a future
toolchain writes only the per-device copy, the gate would report "no readable result artifact"
rather than reading it. Candidate follow-up, not actioned.

## 6. Verdict

- **D-124 frequency question answered:** 0/20 failures; 95% upper bound ~14%. Every run green by
  XML *and* proto; no stale archives.
- **#131 remains open.** The flake is not reproduced and therefore not explained. The widening is
  defensible but unproven as a fix. The `reminder_fire` rows added under D-124 are what would turn
  the next real occurrence into data.
- **No change to production code is warranted by this evidence.** The one thing this loop proves
  about the test is structural: it passes on `lastFiredId`, which is set before the notification is
  posted, so it is deliberately not a notification-delivery test.
