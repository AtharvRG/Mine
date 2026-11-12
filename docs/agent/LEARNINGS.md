- Tests whose fake model reads storage hid the ambiguity bug (proposal id never reached a real model).
- SQLite sorts NULLs first on ASC; fake storage must match.
- A schema change without version bump crashed a real phone ("no such column: status").
- Registry of armed alarms is not truth: force-stop clears all alarms.
- Hooks beat prompts: enforce rules with scripts.
- Model claimed tests passed without running them; only pasted output counts.
- Streaming envelope JSON shows raw braces; extract "reply" incrementally or buffer.
- SQLite parameter type affinity: `?` arguments passed as strings in raw SQL queries fail integer column comparisons unless explicitly wrapped with `CAST(? AS INTEGER)`.
- Keyset cursor pagination over nullable columns: `(due_at, id)` must separate `due_at IS NOT NULL` traversal from `due_at IS NULL` traversal to prevent dropped rows or infinite cursor loops.
- Android instrumented test type inference: mixed literals in `arrayOf(...)` trigger `TYPE_INTERSECTION_AS_REIFIED_ERROR`; pass explicit `arrayOf<Any>(...)`.
- Environmental branching in tests (`if (canExact) ...`) silently masks failures by skipping the assertion under test; tests must assert unconditionally.
- Dirty installs with stale AppOps or permissions hide missing manifest declarations; only clean uninstalls expose runtime permission failures.
- Android 14 (API 34+) `AlarmManager.setAlarmClock()` throws `SecurityException` unless `USE_EXACT_ALARM` or `SCHEDULE_EXACT_ALARM` is declared in the manifest.
- Partial column hydration in `Storage.getNode` (e.g., omitting `priority`, `severity`, `pinned`) corrupts state snapshots and breaks undo validation.
- Unbounded prompt additions (e.g., soul facts, proposals) threaten context token budgets; sections must have hard length/count bounds.
- Catching `Throwable` in application components masks fatal JVM errors; catch `Exception` and handle specific subclasses like `SecurityException`.
- Reliability state must be bounded (max 5 distinct recent issues) and recover to `healthy` on clean passes to avoid permanently stuck failure flags.
- `DateResolution.Exact` is future-only by contract (past input resolves to `PastTime`); a redundant `<= clock.now()` re-check after the when-branch is dead code and a compiler warning — trust the resolver's sealed hierarchy.
- Persisting the full change-set in `ProposalPayload` (not just the time) lets time-chip and ambiguity confirmations replay UPDATEs with zero model calls; every new proposal kind must carry `newTitle`/priority/severity/pinned/tier or the confirm path silently drops fields.
- Blank-string args shadow populated aliases via elvis (`blank ?: fallback` never reaches the fallback): normalize blank to null (`ifBlank { null }`) BEFORE combining. Caught by review in UPDATE `new_due_text` vs `due_text`.
- A change that would be a silent no-op must return an Error, not Committed: tier lives on the reminder, so tier-only UPDATE on an undated node (no reminder) reports false success. Guard "X-only change with nowhere to apply it" paths.
- Dead guards are not free documentation: the unreachable `deleteReminderId` branch in `applyNodeUpdate` was flagged by review and removed; the honest hook (`updateNodeAndCommit(deleteReminderId=...)`) is recorded in HANDOFF traps instead.
- The UI-visible op filter exists in two places (`uiVisibleOps` in SharedFakeStorage + the SQL `IN` list in AndroidStorage.getRecentEvents) and must be kept in lockstep with every ReceiptFormatter op branch. UPDATE was added to the formatter in P1-B 16 but missed in both filters, silently hiding all UPDATE receipts (D-115 regression caught in P1-D 20).
- Rules that must be identical across the SQLite and in-memory Storage implementations belong in a shared top-level Kotlin function (e.g. `rankHomeNodes` in `com.anchor.core.storage`), not in SQL ORDER BY — SQL would drift between implementations and cannot be unit-tested against the fake.
- After `val valid = x != null && ...`, use `x.field` — `x!!.field` triggers an "Unnecessary non-null assertion" warning; the zero-warning standard fails on it.
- Cross-package top-level helpers need an explicit import even between sibling packages: SharedFakeStorage (in `com.anchor.core`) calling `rankHomeNodes` requires `import com.anchor.core.storage.rankHomeNodes`; relative-package proximity does not resolve it.
- Test-helper freshness subtlety: helper nodes created with `createdAt = now` always pass the 30-day freshness filter; to test the stale exclusion you must set `updatedAt`/`createdAt` explicitly in the past.
- AGP uninstalls both APKs (app + test) after every connected test run; post-run adb inspection of the package fails with "Unable to find package" until reinstall. Worse, `adb shell dumpsys package <pkg>` prints that error to stdout with exit 0, so `> file` silently captures 41 bytes of error text — lost a receiver dump twice this way. Capture device dumps BEFORE the run, or reinstall first, and check file size after redirect.
- Judge connected-test pass/fail ONLY from the JUnit XML (`app/build/outputs/androidTest-results/connected/debug/TEST-*.xml`), never from the gradle exit code: AGP 9.4.0 / UTP 32.4.0 exits 1 on green runs because the flag is computed in the Gradle worker after instrumentation has already succeeded (D-123). It is NOT instrumentation teardown on this ROM — that earlier explanation was wrong and was retracted.
- `dumpsys window` on this ROM does NOT contain `StatusBarIconView` lines; the SystemUI icon state lives in `dumpsys activity service com.android.systemui/.SystemUIService` under `StatusBarIconControllerImpl` / `StatusBarIconList`. Use that for any status-bar evidence.
- `INSERT OR REPLACE` in SQLite drops the existing row and recreates it, wiping all omitted columns back to default or NULL values. Updates to persistent tables (`soul_fact`, `reliability_state`) must use parameterized `UPDATE ... WHERE id = ?` and fallback `INSERT` to avoid corrupting unmapped metadata columns.
- Direct Boot alarms fired before user PIN unlock cannot write to CE SQLCipher. Fired alarms must be recorded in Device-Protected storage (`direct_boot_fired`) and reconciled into CE SQLCipher upon user unlock before reconciler or sweeper runs, preventing duplicate "Missed: Reminder" notifications.
- Sliding-window rate limiters must track durations using monotonic time (`SystemClock.elapsedRealtime()`) rather than wall-clock epoch millis to prevent sliding windows from freezing when system clocks jump backwards via NTP or timezone shifts.
- To maximize LLM KV prompt caching prefix hits, static context components (`TOOL_INSTRUCTIONS`, system rules, Soul facts, active nodes) must precede volatile components (`Now: $nowStr`, dynamic check-ins, proposals).
- Trailing lambda syntax in Kotlin (e.g. `RateLimiter { clock() }`) binds to the last functional parameter when multiple parameters have default values; declare explicit single-parameter secondary constructors to maintain backwards-compatible call-sites.
- `adb shell screencap -p /sdcard/x.png` mangles the remote path under Git-Bash (usage error). Use `adb exec-out screencap -p > local.png` instead — no remote path, no pull step.
- `StatusBarIconView` alpha/iconAppearAmount can be caught mid-animation; the integer `visibleState=ICON|HIDDEN` and the `StatusBarIconHolder ... visible=true|false` are the stable fields. The `StatusIconContainer` left edge also shifts by exactly one icon slot (55 px at 1080p) — a second independent signal.
- The minified release build is silent: `adb logcat -s "Mine:*"` returns 0 lines and `run-as com.anchor.mine` fails with `package not debuggable`. Any defect found on the release APK can only be *characterised* from UI + AlarmManager state; root-causing requires reproducing on a debug build.
- `connectedAndroidTest` exit 1 is not a failure signal on this ROM (already known); the export suite recorded `tests 7 failures 0 errors 0` with `test-result-exit-code.txt: 1`, so always cite the parsed XML counts alongside the exit code in evidence reports.
- `slot='alarm_clock'` in SystemUI is app-agnostic: it reflects the system-wide next alarm clock from ANY app, including the user's own clock-app alarms. A `setAlarmClock` from `com.google.android.deskclock` keeps the icon at `visibleState=ICON` even with zero Mine reminders armed, so any "no alarm" control taken while any other alarm clock is pending is invalid. Verify `Next alarm clock information:` is blank AND the holder is `visible=false` before trusting an "absent" capture. Do not describe such alarms as "contamination" — on this device they were the user's own.
- `adb shell cmd alarm` exposes only `set-time`, `set-timezone`, `get-config-version` — there is no `cancel`. To clear another app's alarm clock, advance the clock past it with `cmd alarm set-time <epoch_ms>` and re-check `Next alarm clock information:`.
- `adb shell input text` `%s` semantics: ONE `%s` in the string becomes a single space, but multiple `%s` merge/mangle the input (`remind%sme%sto%sstretch%sin%s2%sminutes` produced `remind me to test the bannremind me to stretch in 2 minutes`). Use exactly one `%s` and literal spaces everywhere else: `remind%sme%to%stretch%sin%2%minutes`.
- Clearing a Compose text field: `input keyevent 67` repeated does not work; `input keycombination 113 29` (Ctrl+A) followed by one `input keyevent 67` does.
- The `StatusIconContainer` left edge drifts with the other visible status-bar icons, but the width delta between alarm-present and alarm-absent is a stable 55 px at 1080p (observed 295→240 and 252→197 at two different absolute positions). Cite the delta, not the absolute bounds.
- Status-bar glyph polarity on this ROM is LIGHT: bar background `(250,250,250)`, glyph `(255,255,255)`. A pixel scan for the icon must test `r >= 251`; testing for dark pixels finds nothing.
- `Run 5-sec Self Test` is the only LLM-free way to arm a deterministic ALARM-tier alarm, and it cannot produce a Gentle one. The Gentle instrumentation test uses a 1.5 s window (unscreenshotable) and the chat path fails silently (O-1), so a same-session Gentle capture is not achievable on the current release build.
- An alarm test that polls for "did it fire" measures the whole delivery path, not just AlarmManager: on a cold process the receiver has to open SQLCipher before it can set any flag, so a 1.5 s fire window is inside the noise. Widening the window (5 s fire, 20 s poll) is the honest fix; moving the flag earlier in `AlarmReceiver` would make a missing-DB-row delivery look like a success (and would break `AlarmReceiverTest.testAlarmReceiver_CancelledReminder_DoesNotFire`).
- A red Gradle task is not evidence of a failing test. On AGP 9.4.0 / UTP 32.4.0 `connectedDebugAndroidTest` exits 1 on green runs (D-123); always read `TEST-*.xml` and quote the gate, not the task exit code.
- UTP per-test logcat files (`logcat-<class>-<method>.txt`) are NOT sliced per test: each contains a window that includes neighbouring tests. Merge and time-sort every file in the run before making any ordering claim.
- Attribute a notification to a test by its TEXT (`NotificationFeature: Mine Mine <text>`), never by "nearest preceding `TestRunner started:`". The first flash event after an ALARM test starts is usually the PREVIOUS (Gentle) test's notification arriving late; proximity attribution reported an ALARM latency of 0.17 s that was really the Gentle one.
- No single logcat line is a reliable delivery signal on this device. Over 20 runs `FlashNotifController requestStartFlashNotification` was 2/2 every run, but `MediaFocusControl requestAudioFocus` dropped to 1/2 in 3 runs and `NotificationFeature` dropped to 1/2 in 3 others — each independently, while the test passed. Absence of one line is not evidence of a delivery failure.
- `AlarmReceiver` sets `lastFiredId` AFTER `manager.notify` and `deleteReminder`, so `AlarmSelfTest` passes on a signal emitted BEFORE the system finishes posting. The ALARM notification therefore lands within ±20 ms of the test's own finish line (and after it in 3/20 runs), and the GENTLE notification lands after the test finished in 20/20 runs. The test is a `lastFiredId` test, not a notification-delivery test — do not "fix" it by polling for the notification.
- The top-level `test-result.pb` IS populated on healthy runs (3 PASSED entries, verified 20/20). Any note saying it is always empty is wrong.
- `buildConfigField(...)` in `defaultConfig` applies to ALL variants, release included. Put credentials in `buildTypes { debug { ... } }` only.
- R8 removing an unreferenced `public static final String` is NOT a security guarantee. Proven 2026-11-07: with no live reference the release APK had 0 key hits; adding `Log.e("H1X", BuildConfig.LIVE_API_KEY + BuildConfig.LIVE_ENDPOINT)` to `MineApp.onCreate` put both the key and the endpoint into `classes.dex` (1 hit each). "The APK scan is clean" must never be cited as proof a credential is absent from the build config — check the generated `BuildConfig.java` for the release variant instead.
- A constant-folded guard (`if (BuildConfig.LIVE_API_KEY.length > 1_000_000) Log.e(...)`) is eliminated entirely by R8: 0 hits for the key AND 0 hits for the marker string. Use a genuinely live sink when testing whether a constant survives minification.
- Reminder ids are derived from node ids (`r_<nodeId>`), so the same reminder id can legitimately miss twice after a reschedule. Dedupe missed fires on the fire instance (`scheduledFor`), never on `(reminderId, "missed")`.
- `FakeClock` has no `advance()`; it is `class FakeClock(var currentTime: Long)`. Move time with `clock.currentTime += n`.
- A JVM unit test in the `app` module compiles against ONE variant (the one its task targets, e.g. `testDebugUnitTest` → debug), so it cannot assert the absence of a field in the release variant's BuildConfig. Verify release-side absence by reading the generated `buildConfig/release/.../BuildConfig.java` and scanning the APK, not by a unit test.
- `SupportSQLiteDatabase` has no `rawQuery(...)`. A guard written with it fails `:app:compileDebugKotlin`, and the connected run then silently executes the **stale APK** — which looks like "the guard didn't fire / only one test ran". Use `compileStatement(sql).executeUpdateDelete()`, which returns the affected-row count directly (no separate `SELECT changes()` round trip). Always confirm the Kotlin compile is BUILD SUCCESSFUL before believing a device result.
- A real-DB UPDATE with an explicit column list and a fake that replaces the whole object will disagree silently. `updateNodeAndCommit` and `undoChangeAndCommit` both omitted `type`, `title` and `local_only` for months while 267 core tests passed. When a storage method mutates a row, the fake must apply field-by-field, and a connected test must mutate through the real DB.
- A UI action that must act on its outcome (copy a finished export to a SAF URI, then delete the temp file) cannot poll `isBusy` — the poll races the cleanup. Add a suspending entry point that `job?.join()`s and returns success; the polling version was written and thrown away in the same session.
- `ExportManager.exportTo` derives `tmpFile = File(file.parentFile, file.name + ".tmp")`, so it needs a real `File` on a writable filesystem. A SAF `content://` URI cannot be passed through; write to `context.cacheDir` and copy.
- `AndroidExportManager(scheduler = null)` silently skips the post-import reconcile; an import then swaps the DB while the old alarms stay armed and fire for rows that no longer exist. Any construction site must pass a scheduler or a reconciler.
- A `@Composable` that must both early-return and be referenced by a sibling lambda (a dialog rendered outside the main layout) cannot scope its locals inside the layout lambda — the sibling cannot see them. Guard at function scope with a bare `return`, then bind the non-null value once.
- The robustness audit is a hypothesis list, not a work order. Re-checking its 55 findings against the working tree on 2026-11-07 found 4 that were not bugs at all (R-17 describes a quiet-hours window that does not exist in the code; R-47, R-07 and R-22 were already fixed or dead code). Verify the cited lines before scheduling a fix.
- Gradle UP-TO-DATE will happily serve a stale `build/test-results/test/TEST-*.xml` from a previous file content. A "failure" that names a choice id from code you already rewrote is a stale report, not a real regression: re-run with `--rerun-tasks` before believing it. Conversely, a green count read from XML is only trustworthy if the XML was written by the run you just did.
- `try/catch` is not allowed around composable invocations ("Try catch is not supported around composable function invocations"). To assert that a `LaunchedEffect` does not crash, let `compose.waitForIdle()` rethrow instead of wrapping the `setContent` call.
- `MainViewModel` is `final` and its `state` is final, so it cannot be subclassed or faked in a Compose test. Split the screen into a stateful `Foo(viewModel)` and a stateless `Foo(state, onAction)` overload and test the stateless one — this is also the only way to render a shell (tabs, banners) at an arbitrary `UiState`.
- A permission-derived UI flag must be computed in the ViewModel, not once in the Activity. The reset-database path recreates the Activity without re-asking for POST_NOTIFICATIONS, and a user returning from system settings changes the grant without any lifecycle event the Activity observes; deriving it on every controller state emission keeps the banner honest.
- `NotificationManagerCompat.areNotificationsEnabled()` is the right query for "will a reminder actually alert the user": it is a superset of the POST_NOTIFICATIONS runtime grant, so it also catches the user switching the app's notifications off in settings after granting.
- When no device is attached (`adb devices` prints only the header), a connected test can only be compiled. Record the fix as "RED: not demonstrated" in TASKS.md — a compile-clean connected test is not evidence that the defect existed or that the fix works.
- `LazyListState.animateScrollToItem` does NOT throw for an index past the end. `LazyListScrollPosition.update` only requires `index >= 0`, and `LazyLayoutScrollScope.animateScrollToItem` stops with "Hit end without finding the item" when `delta != consumed`. So "the hand-summed scroll target can be out of bounds and crash the coroutine" (H-09) was never true — an oversized target just lands at the bottom. Read the Compose sources before writing a test for a library-throws claim; the injected-defect run PASSED and that was the correct signal, not a broken test.
- `createComposeRule`'s harness installs a `RootForTest.UncaughtExceptionHandler` (`AndroidComposeUiTestEnvironment$withWindowRecomposer$…$uncaughtExceptionHandler$1`) that stores the throwable in `pendingThrowable` and `waitForIdle()` rethrows it. So a `LaunchedEffect` crash IS observable via `waitForIdle()` — if a test still passes with a crash injected, the injection did not crash, not the harness swallowing it.
- `ComposeIdlingResource is busy due to pending measure/layout` thrown from `setContent` means the composition never settled. On `ChatScreen` with 40 messages it reproduces every run; with 1 message the same run passes. Treat this as a product hang candidate (H-09b), not a test-harness quirk, until the 3-message isolation probe says otherwise.
- A connected run can die mid-flight with `DeviceException: No online devices found` if the phone drops (Wi-Fi adb). The previous run's XML stays on disk and will be quoted as if current — check `adb devices` shows `device` (not `offline`) before reading any result file.
- A "record the failure" fix cannot use the thing that failed to record it. `AlarmReceiver`'s fallback originally re-awaited `app.sharedStorage`, but that deferred is exactly what threw; the R-54 fix re-opens through `DbKeyProvider` + `DatabaseProvider.getHelper` + `AndroidStorage`, the same path `BootReceiver.recordStorageFailure` uses. When a catch block persists evidence of an exception, check what the exception came from first.
- Dedupe keys must be written by every producer of a "this fire was surfaced" event, not just the happy path. `AlarmReconciler` and `ReliabilityManager` dedupe on `(reminderId, outcome="missed", scheduledFor)`; a fallback that posts a notification without writing any row leaves the instance looking unreported, and the next sweep posts a second notification. Adding `outcome="errored"` to the dedupe is only half a fix — the fallback also has to know the fire instant, hence `EXTRA_SCHEDULED_FOR` on all three producers (`AndroidAlarmScheduler.schedule`, `MainViewModel`'s sweeper, `BootReceiver`'s sweeper). A `scheduledFor` of "now" would never match the reminder's `fireAtMillis` and the dedupe would silently do nothing.
- A `BroadcastReceiver` has no unit-test seam: `onReceive` builds its own coroutine scope and reads storage off `Application`. The receiver half of R-54 is therefore compile-only until a device is attached; split such fixes so the testable half (a pure core function) carries the RED/GREEN evidence and say plainly that the other half does not.
- **CORRECTION (2026-11-07): the two entries above about H-09b are WRONG and must not be cited.** There is no `ComposeNotIdleException` and no product hang. All three `ChatScrollBoundsTest` cases complete in ~1-2 s. The "busy due to pending measure/layout" record came from a run whose meaning was misread; the real defect is the one below.
- **A `LaunchedEffect` runs after the first composition commits but BEFORE the lazy list's first measure.** `listState.layoutInfo.totalItemsCount` is therefore `0` on entry, and any `if (lastIndex >= 0)` guard computed from it silently skips the scroll on the very first load — the list stays at item 0 forever. Instrumented log proof: `H09B: effect start total=41 lastIndex=-1`. Await the first non-empty layout instead: `snapshotFlow { listState.layoutInfo.totalItemsCount }.first { it > 0 } - 1`. After the fix the same log printed `first=22 total=41 visible=[22..40]`. Generalise: any effect that reads layout state must wait for layout, not assume composition implies measurement.
- **A lazy list fills its viewport, so `firstVisibleItemIndex` is the wrong "did it reach the bottom?" probe.** Scrolling to item 40 on a moto g73 leaves `firstVisibleItemIndex == 22` with `visible=[22..40]`. Assert `layoutInfo.visibleItemsInfo.last().index`; asserting the first index fails for a correct implementation and would have sent the next run hunting a phantom bug.
- **`MineApp.sharedStorage` is a `@Volatile var CompletableDeferred<Storage>`, so it IS injectable from an instrumented test.** The earlier claim that `AlarmReceiver`'s storage failure "cannot be tested without a device seam" was wrong in the other direction: no device seam is needed, only a swap of the deferred. `completeExceptionally(...)` then `receiver.onReceive(...)` reproduces the fallback path end-to-end. Always capture `val original = app.sharedStorage` before swapping and restore that *deferred* in `finally` — restoring the resolved `Storage` is a type error.
- **"Compile-only" is a conclusion to re-test, not a resting state.** R-54's receiver half sat as compile-only for a whole session on the belief that a `BroadcastReceiver` had no seam. Reading `MineApp` showed a public `@Volatile var` that the test could swap. Before accepting a compile-only verdict, check whether the dependency is reachable through a mutable field, a constructor parameter, or a static.
- **Doze verification has two distinct claims; only one is cheap.** `dumpsys alarm` under `dumpsys deviceidle force-idle` proves the alarm is *registered* with the allow-while-idle option (`type=RTC_WAKEUP`, `idle-options` containing `android:broadcast.temporaryAppAllowlistType=1`, `policyWhenElapsed … device_idle=-3s841ms`). It does NOT prove *delivery* while idle — that needs the alarm's own fire time to elapse. Say "registered for idle" and "delivered under idle" separately; never let the first stand in for the second. Always `dumpsys deviceidle unforce` afterwards and confirm `mState=ACTIVE`.
- **`--rerun-tasks` on the full connected suite takes ~3.5 min and can outlive a 10-minute tool timeout only if the shell is still running.** The Gradle process kept going after the tool call was cut off, and the gate line was already in the redirected log. When a long run is interrupted, read the redirected log before re-running — re-running burns another 3.5 min for output already on disk.
- **The connected results dir can hold a deliberately RED run.** After an injected-defect probe, `TEST-*.xml` shows `tests="4" failures="1"` for the probe and nothing distinguishes it from a real regression. Always run the full suite green after restoring before quoting counts; a stale RED XML is indistinguishable from a live failure by count alone.
- **`diag_log` existed as a table with zero writers.** `MineDatabaseHelper.kt:38` creates `diag_log (ts, level, tag, msg)`, but a repo-wide grep found no INSERT before R-23. Any audit remedy phrased "log to diag_log" is therefore a two-part task: define the write path first, then call it. Before assuming a diagnostics sink works, grep for its writers — a schema is not a mechanism.
- **A diagnostics write must never be able to fail the operation it is diagnosing.** `logDiagError` wraps its INSERT in `runCatching`. If the FTS index is broken because the DB is broken, the log write fails too; letting that propagate would turn "search returns nothing" into "search throws", which is strictly worse than the bug R-23 was fixing.
- **Prove the precondition before asserting on the failure path.** The R-23 test first asserts the FTS index finds a sentinel, *then* drops `msg_chunk_fts`, *then* asserts the error was logged. Without the precondition, an empty result could mean "the query never matched" and the test would pass or fail for the wrong reason. When a test forces a failure by mutating state, assert the healthy state first.
- **A RED probe must be produced by reverting the fix — running a new test against already-fixed code proves nothing.** The first R-24 attempt ran the test after the fix was in place and got `failures="0"`, which is indistinguishable from a test that can never fail. The real RED required backing up `AndroidStorage.kt`, reverting both call sites to `IntentType.valueOf(...)`, re-running (`IllegalArgumentException: No enum constant … IntentType.TELEPORT at AndroidStorage.kt:347`), then restoring from the backup and confirming the helper is back at both sites. Any "test passes" result obtained without first seeing it fail is unverified.
- **Audit line numbers drift; the finding usually survives.** R-23 cited `AndroidStorage.kt:704`, the catch was at `:748`; R-24 cited `:332,389`, the call sites were `:347,404`. Re-locate by grepping for the construct (`catch (_: Exception)`, `IntentType.valueOf`) rather than trusting the cited line, then record the real line in TASKS.md so the next run does not re-derive it.
- **Degrading to a default silently is a second bug.** R-24 could have been fixed with `runCatching { … }.getOrDefault(CREATE)`, but then a downgrade would quietly rewrite the user's intent to CREATE with no trace. Logging the degrade to `diag_log` makes the fallback observable. When a fix substitutes a default for unparseable data, record that the substitution happened.
- **An install/uninstall failure is not a test failure.** `DELETE_FAILED_INTERNAL_ERROR` + `adb: failed to run abb_exec. Error: closed` + `Could not find test-result.pb` means the device link dropped, not that a test failed. `adb reconnect` may empty the device list; `adb connect 10.175.15.5:5555` restores it. Read the tail of the Gradle log before reading the XML.
- **A single flaky failure in a filtered UTP subset is not suite health, in either direction.** A background run ended `CONNECTED GATE: FAIL - 18 test(s), 1 failed` naming `StatusBarAlarmIconTest.scheduleAlarmTierAndHold` with an **empty** `<failure>` body (G2, task #139). Re-running the class alone gave `tests="3" failures="0"`, `dumpsys alarm` showed no live `com.anchor.mine` alarm clock, and the next full run was 79/0. Record such an event as "intermittent, cause not established" with the isolated re-run as evidence — do not call it fixed, and do not call it a regression.
- **Gradle's configuration cache reuses a `-Pandroid.testInstrumentationRunnerArguments.class=…` filter across invocations.** After a filtered single-test run, a later `./gradlew :app:connectedDebugAndroidTest --rerun-tasks` still ran only the previously filtered classes (5 tests instead of 80) and the gate reported `FAIL - 5 test(s), 1 failed`. Neither `gradle.properties` nor `app/build.gradle.kts` sets a persistent filter; the property lived in the cached configuration. A combined class+package filter did not override it either — the class property wins. The only reliable full-suite invocation carries **no** filter property at all. Before quoting a connected count, check the XML's `tests=` against the expected total; a suspiciously small number means a stale filter, not a broken suite.
- **JUnit unit-test XML and connected-test XML order their attributes differently.** `core/build/test-results/test/TEST-*.xml` emits `<testsuite name= tests= skipped= failures= errors= …>`; the connected report emits `<testsuites tests= failures= errors= skipped= …>`. A single ordered regex (`tests="(\d+)" failures="(\d+)" …`) silently yields `failures=0` for every file because the group after `tests=` is `skipped`. Parse each attribute with its own independent regex. A recount script that reports `tests=0` or `failures=0` for everything should be suspected before the suite is.
- **A mechanical call-site migration must be checked against each call site's own notion of "now".** Migrating `getActiveReminders()` → `getActiveReminders(now)` and passing `System.currentTimeMillis()` at every site compiled cleanly and then failed `DuplicateCallGuardTest.testIdenticalCalls_SecondIsRejectedAsDuplicate` (`AssertionError at DuplicateCallGuardTest.kt:53`): that test's `FakeClock` is pinned to 2026-10-25 and the reminder is due "tomorrow at 9am", so the real wall clock (2026-11-07) put it in the past and the query returned nothing. The failure was the R-25 defect reproducing itself in `core`, not a test regression. When a function gains a clock parameter, ask at each site *whose* clock is authoritative — a `FakeClock`-bound test must pass `clock.now()`, a live on-device test may pass the wall clock.
- **A storage method that reads its own clock cannot be tested at all, and the fix is to make the test time-independent by construction.** The R-25 test places a reminder at a fixed `fireAt` and probes at `fireAt-1`, `fireAt`, `fireAt+1`. Under the old wall-clock implementation all three probes see the same real `now`, so at least one assertion is guaranteed to fail whenever the test runs, at any hour — no sleep, no clock manipulation, no luck. Prefer this shape over "set a reminder 5 s out and assert it appears": the latter passes under the buggy code whenever the test happens to run before the fire instant.
- **`Storage`'s clock convention is a `now: Long` parameter, not an injected `Clock`.** `getActiveNodeCounts(now, staleCutoffMs)`, `getStaleNodes(now, cutoffMs)` and `getRankedCandidates(now, staleCutoffMs, perTierLimit)` all take the instant explicitly; R-25 follows them. Callers that already hold a `Clock` (e.g. `ChatController`, whose constructor has `private val clock: Clock`) just pass `clock.now()`. Adding a `Clock` to the storage interface would have been a larger change with no benefit.
- **"No LIMIT" and "no WHERE" are different defects with different proofs.** The R-26 audit row said the day/range queries "have no `WHERE`/`LIMIT`"; the tree showed `getRemindersBetween` already carried `WHERE fire_at >= ? AND fire_at <= ? ORDER BY fire_at ASC`. Only the ceiling was missing. A missing predicate means the query scans the table; a missing ceiling means a *correct* predicate can still return an unbounded set. Read the SQL before accepting the audit's description of it, and write the correction into the audit file itself so the next run does not re-derive it.
- **The interesting half of a "bound the query" fix is what the caller does when the bound is hit.** `AlarmReconciler` computed truncation correctly and then put it in a *separate* `failures` list that only fed `reliability_state`, while the tests and the UI read `results` — so a paged reconcile reported itself clean to everyone who mattered. Two lists derived from the same condition will eventually disagree; derive the second from the first (`val failures = results.filterIsInstance<Failed>()`) so they cannot. When adding a bound, ask "who learns that the bound was hit?" and prove it with a test that reads the same value the product reads.
- **`limit` alone cannot distinguish "exactly a full page" from "more rows exist".** Request `limit + 1`, compute `truncated = size > limit`, and hand the caller only the first `limit`. The test must assert both halves: the full-page case reports truncation, and the partial-page case does not — a test that only checks the full page passes for an implementation that always reports truncation.
- **A fake that returns more than the device would makes every test above the bound pass for the wrong reason.** `SharedFakeStorage` diverged from `AndroidStorage` once before (D-06). Every new `LIMIT`/`ORDER BY` added to `AndroidStorage` was mirrored in the fake in the same edit, including the sort — the fake must reproduce the *order* of the page, not just its size, or a test asserting "the earliest rows survive" passes against a fake that never sorted.
- **`arrayOf(a: Long, b: Long, c: Int)` does not compile under AGP 9.4.0's Kotlin.** The inferred type is the intersection `Number & Comparable<*>`, and reifying an intersection is now an error (`TYPE_INTERSECTION_AS_REIFIED_ERROR`, `e: … AndroidStorage.kt:1127`), not a warning. `arrayOf<Any>(startMs, endMs, limit)` is the fix; it is the same shape `SupportSQLiteDatabase.query` already accepts. The compile failure is loud, but the *stale-APK trap* still applies: a failed `:app:compileDebugKotlin` followed by a connected run executes the previously installed APK and reports a plausible test count.
- **`app/build/outputs/androidTest-results/connected/` holds more than one variant.** `connected/release/` retains a 6-test `ExportTest` XML from 2026-11-05 next to the current `connected/debug/` run. A recursive `connected/**/*.xml` glob summed 82 + 6 = 88 and looked like a discrepancy against the gate's 82. The gate reads only `connected/debug` (`connectedResultsDir` in `app/build.gradle.kts:83`), so the gate was right and the recount was wrong. Scope every ad-hoc recount to the variant the gate reads.
- **A stale `release` result file is a latent false-confidence source for any future gate change.** If `connectedResultsDir` is ever widened, the 2026-11-05 `release` XML would be counted as part of the current run. Any change to the gate's results directory must also delete or exclude sibling variant directories.

## 2026-11-08 — H-02 (periodic sweeper)
- **A "blocked on user decision" line can be stale.** H-02 was recorded as blocked on WorkManager
  vs repeating alarm; the accepted batch already said repeating inexact alarm. Check the batch
  line before reporting a task as blocked.
- **`setInexactRepeating` is idempotent for a fixed PendingIntent** — re-arming on every boot and
  every app start replaces the registration instead of stacking a second one. That is what makes
  "arm it in `MineApp.onCreate` *and* in `BootReceiver`" safe rather than duplicate.
- **A repeating alarm still needs its action in the receiver's valid-actions list.** Arming is not
  delivery: `BootReceiver.onReceive` returns early for unlisted actions, so the alarm would fire
  into a no-op. The RED probe for H-02 was exactly this — arming present, delivery absent.
- **Prove delivery, not just arming.** The H-02 test asserts both halves: `sweepsArmed` increments
  (registration) *and* an `ACTION_SWEEP` broadcast drives the sweeper to a real missed-fire row
  (delivery). Arming alone would have passed with a receiver that ignored the action.
- **The RED probe must be a real assertion, not a compile error.** The first RED was `Unresolved
  reference 'sweepsArmed'` — that only proves the test names a symbol. The second RED removed
  `ACTION_SWEEP` from the valid-actions list and produced
  `expected:<r_h02_…> but was:<null>`, which proves the test can fail on behaviour.
- **A `--rerun-tasks` connected run can exceed a 10-minute Bash timeout.** Run it with
  `run_in_background` and read the exit code afterwards; the result XML is written when the run
  finishes, so a killed command may leave the *previous* run's XML in place — check the timestamp.

## 2026-11-08 — G2 (gate failure messages)
- **A verdict without a reason is half an artifact.** The gate printed `FAILED
  com.anchor.mine.alarms.AlarmSelfTest.testAlarmFires_SetsFlag` and stopped. The assertion text
  existed the whole time — in the XML body, and in the proto's `Error` field — and both parsers
  threw it away. When a tool's output is the only evidence a later reader gets, "which test" is
  not the question; "what did it say" is.
- **One defect can have two independent drop points, and fixing one leaves the other route blind.**
  `parse_xml` discarded `<failure>` bodies; `parse_proto` never decoded field 3. On the D-123 route
  (XML truncated, proto authoritative) only the second fix matters. Grep every read path, not the
  one that produced the symptom.
- **Transcribe proto field numbers from the `.proto` in the jar.** `unzip -o -q
  ~/.gradle/caches/…/test-result-proto-1.0.1.jar 'proto/*'` gives the authoritative schema. A
  guessed field number parses cleanly and returns zeros — the same silent-wrong-answer class the
  `TestStatus` enum already produced once (D-123's first draft).
- **A field that is only populated on failure is invisible on a green run.** All 83 results of the
  current `test-result.pb` carry fields 1/2/4 and no `Error`, exactly as `error.proto` documents.
  So "the proto has no failure text" is *not* evidence that UTP drops it — it is evidence that
  nothing failed. Do not conclude absence from an artifact that had nothing to record.
- **RED-probe each half of a two-part fix separately.** Reverting the printing alone gave 1/4 (three
  message cases fail); disabling the proto `Error` parse alone gave 3/4 (only the proto case fails).
  A single combined revert would have proven only that *something* was needed.
- **Fixture scripts in `scripts/tmp_*` rot.** The D-123 fixtures lived there and were re-runnable
  only by someone who knew the path; the message path stayed broken through two gate revisions.
  Moving them to `gradle/test_connected_results_gate.py` and wiring them into `:app:check` is what
  makes the fix stick. A `tasks.register<Exec>` with `commandLine(pythonExecutable(), …)` is
  configuration-cache safe (no `providers.exec` inside the action).
- **Restore the file from a byte-exact backup after a RED probe, and diff it.** `cp` the file to
  `build/tmp/`, revert in place, run, `cp` back, then `diff` — "identical" is the evidence that the
  GREEN run tested the real fix and not a half-restored file.
- **`copyTo` is a Kotlin extension on `File`, not a member — it cannot be overridden.** The audit's
  prescribed R-10 remedy ("inject a `File` copy hook that throws after 1 KiB") is not implementable:
  `override fun copyTo(...)` fails with `NOTHING_TO_OVERRIDE / 'copyTo' overrides nothing`. Check
  whether a prescribed seam is a member before designing a test around it.
- **Inject the fault below the code, not inside it.** The honest substitute for the impossible hook:
  park the live DB aside and `mkdir()` a *directory* at the live DB path, so the swap's
  `FileOutputStream` throws `FileNotFoundException` after the `.bak` is taken and before a byte of
  the live DB is written — the real crash window, with no dependency on overridable members.
  SQLCipher never opens app DBs through a `java.io.File`, so this does not disturb SQLite's own I/O.
- **A test that passes on unfixed code is worse than no test.** The first H-04 test (duplicate-PK
  injection) went 8/8 on the unfixed swap because the `INSERT` aborts inside the *temp* transaction,
  strictly before the swap. It was deleted rather than kept as an "anchor" — a green test that
  cannot fail for the defect it names reads as coverage.
- **A device can vanish mid-RED-probe.** `adb connect` → `10060`, `ping` → `Destination host
  unreachable`. Nothing in the repo can fix that. Back up the file under test byte-exactly *before*
  reverting it for the RED probe, so a lost device leaves a usable tree rather than a RED-probe state.

- **A `[x]` in TASKS.md that contradicts DECISIONS.md is a lie the next session will act on.** `TASKS.md:30` marked T-C `[x]` while `TASKS.md:82` marked D-105 `[~]` and `DECISIONS.md:14` said "T-C is not done". Cross-check a task's status against the decision line it cites before trusting the checkbox — the checkbox is the thing everyone reads first.
- **When a capture window is too short, look for a longer arm of the same code path before declaring the capture impossible.** The Gentle half of D-105 was recorded as "blocked on O-1 or a debug build" because the only known GENTLE route armed at 5 s. `AlarmSelfTest.testAlarmClock_NextAlarmClockVerification` already arms a GENTLE reminder at 120 s for an unrelated assertion — the capture was always reachable. Search the test suite for the *state* you need, not just the test named after it.

## 2026-11-08 — H-04 / T19 device verification

- **`File.copyTo(overwrite = true)` deletes a directory target recursively, and `File.delete()`
  succeeds on an empty directory.** Both file-system traps for H-04 therefore never threw:
  a directory at the live path was removed by the copy, a directory at the `.bak` path was removed
  by `bakFile.delete()` before the copy. On-device proof: `H04-DIAG SWAP-ENTRY … exists=true
  isDir=true` followed by a successful swap. Do not build an injection on top of `java.io.File`
  path semantics without first proving the operation actually fails.
- **The seam that works is a constructor parameter, not a subclass or a path trick.**
  `copyFile: (File, File) -> Unit = { src, dst -> src.copyTo(dst, overwrite = true) }` lets the test
  fail exactly the second copy while the first and the rollback stay real. It also makes the
  rollback path itself observable (`copies == 3`), which a path trick never could.
- **A rollback adds a copy; count it.** The first assertion `expected:<2> but was:<3>` was the test
  being wrong, not the product: backup + failed swap + rollback = 3.
- **Run `connectedDebugAndroidTest` and `connectedDebugAndroidTestVerify` in the same `--continue`
  invocation.** Verify alone reads the *previous* run's XML and reports `FAIL - 1 test(s)` for a run
  that never happened.
- **Comma-separated `-Pandroid.testInstrumentationRunnerArguments.class=A#m,B#n` silently selects
  only the first.** One class per invocation; check the `tests=` attribute in
  `app/build/outputs/androidTest-results/connected/debug/*.xml` before trusting a gate line.
- **`BUILD FAILED` next to `CONNECTED GATE: PASS` is expected on this toolchain** (AGP 9.4.0 writes
  exit code 1 on success, D-123). The gate line is the verdict; the Gradle exit code is not.

## 2026-11-08 — D-105 Gentle capture
- **A held test is the only window for a host-side screencap.** `AlarmSelfTest`'s `@After` cancels
  the alarm, so the status bar can only be inspected while the test body is still running. The
  opt-in `d105GentleHoldMs` argument exists for exactly that; a normal suite run pays nothing.
- **Never start two connected runs at once.** The second run failed with
  `FileSystemException: … logcat-…AlarmSelfTest-testAlarmClock_NextAlarmClockVerification.txt:
  The process cannot access the file because it is being used by another process` and the gate then
  reported `FAIL - no readable result artifact`. The XML left behind by the *first* run showed
  `tests="3" failures="0"`, which is easy to mistake for the second run's result. One run at a time.
- **A dark-theme screenshot invalidates a light-glyph pixel scan.** The Pair C discriminator
  (`r>=251`, x 600–760) returns nothing meaningful on a dark capture. Use the SystemUI
  `visibleState` plus `dumpsys alarm` as the differential, and say so in the evidence file.
- **`dumpsys alarm` distinguishes the tiers without SystemUI.** ALARM shows a
  `setAlarmClock` / `Next alarm clock information` entry; GENTLE shows only
  `tag=*walarm*` with `window=+…` and `flags=0x20` (the `setAndAllowWhileIdle` fingerprint,
  `AndroidAlarmScheduler.kt:54`).
- **A "filtered extract" is not a dump.** `d105-gentle-alarms-dump.txt` was written by a `grep` for the
  Mine alarm entry, so it never contained the `Next alarm clock information` header. Three docs
  initially claimed the header was "blank" in that file. It was absent, not blank — an absence of
  evidence read as evidence of absence. When a capture is filtered, state which claims it *can* support
  and source the rest elsewhere.

## 2026-11-08 — v1 scope audit + `search_history`
- **A green unit suite can certify dead code.** `NudgeEngineTest` passes against a class no
  production path constructs (`grep -rn "NudgeEngine" core/src/main app/src/main` outside its own
  file → 0 hits). Before citing a test as proof a feature works, grep for a *production* caller of
  the class under test. Same shape: `learned_pref`/`signal_log` tables nothing reads or writes.
- **A prompt line is part of the feature.** `search_history` storage code is unreachable until the
  tool is listed in `ChatController`'s prompt tool list. A tool that exists in the gateway but not
  in the prompt is dead code that looks finished. Ship the storage, the dispatch, the formatter,
  **and** the prompt line together.
- **`msg_chunk` links to `raw_message` by id string, not a column.** Columns are `id, day_key,
  ts_start, ts_end, msg_ids, text, extractor_version`; the join is `'chunk_' || raw_message.id`.
  `msg_ids` is NOT NULL, so a hand-inserted test chunk must set it.
- **Redaction safety belongs in the query, not in the chunk table.** A stale `msg_chunk` row can
  outlive its message's redaction. Re-checking `content IS NOT NULL AND redacted_at IS NULL` in the
  join is what keeps redacted text out — the test inserts a stale chunk on purpose to prove it.
- **"No device" is a recorded state, not a pass.** `adb devices` went from `10.175.15.5:5555
  offline` to an empty list mid-session; `adb connect` then timed out (10060) and there is no
  emulator installed. Write the test, compile it, and record it as unverified — never tick it green
  on a compile alone.
- **The Bash tool keeps its cwd between calls.** A `cd core/build/test-results/test` left a later
  `ls app/src/` reporting "No such file or directory". Use absolute paths.
- **A stale checkbox can be a duplicate row, not a stale fact.** `TASKS.md:25` (trigger upgrades)
  was a third copy of the schema-repair T19 that `:155` already records as done. Fold duplicates
  into the completed row and cross-reference, rather than ticking both.
- **An external-content FTS5 table resolves a NULL rowid to a content row.** `msg_chunk_fts` is
  `content='msg_chunk'`, so it stores no text of its own. The first `searchHistory` query joined
  `msg_chunk_fts.rowid = (SELECT rowid FROM msg_chunk WHERE id = 'chunk_' || raw_message.id)`; for a
  message with no chunk (every `system` message) the subquery is NULL, and SQLite then resolved that
  NULL to the content row at the same position — returning the system message **twice**. Join
  `raw_message -> msg_chunk -> msg_chunk_fts` by id instead of a correlated rowid subquery.
- **A device run of a new test is the only thing that proves it.** `testSearchHistory_…` compiled
  clean and passed review; it failed on the first real device run with a duplicate-row bug no
  compile or unit test could see. The `:core` fake storage cannot reproduce an FTS5
  external-content quirk — only real SQLite can.

## 2026-11-08 (cont.) — per-turn aliases, and splitting one file into two commits
- **Real ids share the alias prefixes, so `unknown_ref` cannot be raised at resolution.** Proposals
  are `p_<hex6>`, soul facts `s_<hex8>`, raw messages `mN`. A ref that looks like an alias but was
  never issued is indistinguishable from a real id until the storage lookup runs. Rejecting it in
  `resolveRef` would reject real ids; the lookup is the only place that can tell (D-130).
- **A per-turn map does not need a table.** Blueprint 23 §4 describes an `alias_map` table, but the
  aliases are per-turn and one `Gateway` instance exists per chat controller, so instance lifetime
  already equals alias lifetime. A table would have meant a v6 migration immediately after T19 for
  state that is meaningless across turns.
- **Entangled hunks need a rebuild, not path-level staging.** `formatSearchResults` is introduced by
  the `search_history` change and calls `aliasFor` from the alias change, so `git add <file>` cannot
  separate them. Extract `git show HEAD:<file>`, apply change-set A, commit, then re-apply B.
- **`git checkout -- <file>` is destructive to uncommitted work with no recovery.** It wiped the
  alias layer once this session; it was only recovered because the diffs had just been read. Commit
  or stash before any checkout, and prefer rebuilding from `git show HEAD:<file>` into a temp path.
- **Changing a return type from nullable to non-null leaves dead elvis operands.** `resolveRef` went
  `String?` → `String` and three call sites kept `?: targetRef` / `?: return ToolResult.Error(…)`.
  They compile — the elvis is just unreachable — so the compiler will not find them; grep the call
  sites after any nullability change.
- **A screenshot of the app is not a screenshot of the status bar.** The D-105 by-eye check was
  answered with frames of `RemindersScreen` (`MainActivity.kt:283-321`) — the in-app list — while
  the claim is about a ~25 px glyph in the *system* status bar. Before asking a human to eyeball
  evidence, say which surface the artifact covers and give them a crop that shows only that surface.
- **A 5 s alarm cannot be checked by eye.** `RunSelfTest` arms `fireAt = now + 5000L`; by the time
  the user looks up, the icon is gone. Any by-eye task whose subject exists for seconds needs a
  long-lived arm (the 120 s `d105GentleHoldMs` route) or a pre-taken crop, not a "set it and look".
- **`slot='alarm_clock'` is app-agnostic, so you do not need the app installed to test it.** A
  system `SET_ALARM` `setAlarmClock` from the deskclock exercises the same SystemUI path. The
  limitation is the reverse: it proves "a setAlarmClock is pending → icon appears", never
  "Mine did it".
- **`am start -a android.intent.action.SET_ALARM --ei android.intent.extra.ALARM_MINUTES N` only
  opens a time picker.** Nothing is armed until the picker's OK is tapped (dump the UI with
  `uiautomator dump` first; the OK bounds were `[770,1733][930,1853]`).
- **Git-Bash rewrites `/sdcard/...` in adb commands.** `adb shell "uiautomator dump /sdcard/x.xml"`
  writes to `/Files/Git/sdcard/x.xml` and the following `cat` returns nothing. Prefix the command
  with `MSYS_NO_PATHCONV=1`.
- **A container-width delta is not a glyph.** Prove the glyph with a pixel differential: compare
  bright-column runs between the two frames and require the *other* runs to be byte-identical. In
  D-105x the armed frame had an extra run at `(89,234)` that ASCII-rendered as a clock face.
- **The debug signing key is a deliberate v1 choice, not debt to fix.** User decision 2026-11-08: keep
  `signingConfig = signingConfigs.getByName("debug")` (`app/build.gradle.kts:50-62`) until the app is
  feature-complete toward v2 (memory, souls, and the rest). Do not re-raise it as a release blocker;
  the only real cost is Play upload and upgrade-over-debug, neither of which applies yet.

## 2026-11-08 — v1 row 19 (quick capture)

- **`RemoteInput.getResultsFromIntent` only reads one exact `ClipData` shape.** On SDK ≥ 20 it
  delegates to the platform class, which requires mime **`text/vnd.android.intent`** *and* label
  **`android.remoteinput.results`**, holding an `Intent` whose extras carry the results bundle
  (verified by `javap -p -c` on `androidx.core:core:1.16.0`). A `ClipData.newPlainText(...)` is
  silently rejected → `null` → a receiver's `?: return` drops the reply with no log and no error.
  **Never hand-build a reply intent in a test:** use
  `RemoteInput.addResultsToIntent(arrayOf(RemoteInput.Builder(key).build()), intent, bundle)`, which
  writes the platform's exact shape and cannot drift. The production receiver was correct all along;
  the RED test was the test's fault.
- **"Receiver never invoked" and "receiver dropped the payload" look identical from outside.** The
  `reply_StoresTheText…` failure said *never stored within 10000ms*; the receiver had run and
  returned at its null guard. Before blaming registration or dependencies, check what the receiver
  does with an unreadable payload — `?: return` is a silent drop. (Two wrong hypotheses were burned
  on this: manifest registration and a missing coroutines dependency; `./gradlew :app:dependencies
  --configuration debugCompileClasspath` disproved the second in one command.)
- **Kotlin extension functions need their own import.** `lifecycleScope` alone does not bring
  `kotlinx.coroutines.launch` into scope; the error surfaces as *Unresolved reference 'launch'* plus
  cascading *Suspend function … can only be called from a coroutine*. The dependency was already
  transitive — adding `lifecycle-viewmodel-ktx` changed nothing and was reverted. Add
  `import kotlinx.coroutines.launch`.
- **`connectedDebugAndroidTest` uninstalls the test APK at the end**, so a follow-up
  `adb shell am instrument` fails with "no instrumentation registered". Reinstall both APKs
  (`adb install -r` app + `-t` androidTest) before any manual `am instrument` debugging.
- **A package left `stopped=true` silently drops explicit broadcasts.** Force-stop (and some
  teardown paths) set the stopped flag; `am broadcast -n <pkg>/<receiver>` then reaches nothing until
  the activity is launched once. If a broadcast seems ignored, `adb shell dumpsys package <pkg> |
  grep stopped` first.
- **`ShortcutInfo.getIntents()` is nullable** (`Array<Intent!>?`) — a shortcut with no intents is
  legal. Assert non-null in the test rather than `!!`-ing into an NPE that reads like a missing
  shortcut.
- **A `PendingIntent` request code of 0 is shared.** Two armed reminders both building a reply
  `PendingIntent` with request code 0 are the *same* PendingIntent, so the second overwrites the
  first's extras. Key the request code to the notification id
  (`FLAG_UPDATE_CURRENT or FLAG_MUTABLE`).
- **Declared constants that nothing reads are a doc claim with no code behind it.** All four
  `QuickCapture` capture-setting constants were read by nothing (`grep` → 0 hits) while the docs
  said "off by default". Adding the two readers + two unit tests + the one production call site is
  what made the claim true — check that every documented setting has a reader before citing it.

## 2026-11-08 — E-17 drain completeness (D-134)

- **Gate the suspension point the code path actually traverses, not the one you assume.** The drain
  test first parked the live turn on `storage.getSetting("key")`. With `providerKey` already
  hydrated, `onAction(SendMessage)` takes the **synchronous** branch (`ChatController.kt:186-208`) and
  never reads the key — the gate never fired and the test hung on `liveTurnParked.await()`. Read the
  branch condition before choosing a seam. `HttpTransport.postStream` is the only suspension point
  both branches reach (`handleSendMessage` → `ProviderOrchestrator.chat` → `normalize(postStream(...))`).
- **Parking inside the fake transport is deterministic under `UnconfinedTestDispatcher`.** A `flow {}`
  collected unconfined runs inline, but a `CompletableDeferred.await()` inside it truly suspends — so
  control returns to the test with the turn in flight (`isChatLoading == true`, `currentStreamJob`
  alive) and no production API had to be widened. The earlier plan to add a
  `suspend () -> List<ChatMessage>` seam to `Storage` (17+ call sites) was reverted as unnecessary.
- **Do not share the test's dispatcher with the controller under test.** A drain send parks on
  `job.join()`; if the only thread that could resume the parked live turn is the one blocked in that
  join, it deadlocks. Give the controller its own `CoroutineScope(UnconfinedTestDispatcher())` and
  pass a separate `UnconfinedTestDispatcher()` as its `defaultDispatcher`.
- **A turn's assistant reply answers *every* capture stored before it.** `isUnanswered` is a
  timestamp comparison, so draining only the oldest pending capture marks all the others answered
  without the model seeing them. Any "retry pending" loop must send **all** of them, and the send path
  must queue behind an in-flight turn rather than drop. This is the whole of E-17.
- **`ORDER BY ts_utc DESC` is not a total order.** Two rows written in the same millisecond come back
  in arbitrary order, and code that reads the result as a timeline gets a scrambled one. Add
  `rowid DESC` as the tiebreak. It cannot be unit-tested through a fake storage — it needs a real
  SQLite DB, so it is a device-test-only guarantee.
- **A device test that rewrites an append-only table must restore the exact shipped trigger.** The
  `raw_message` table is guarded by `trg_raw_msg_no_update`; dropping it to force a `ts_utc` tie and
  then recreating a *weaker* trigger leaves the test DB less protected than production and can mask
  the very defect under test. Copy the DDL verbatim from `MineDatabaseHelper.kt` and say in a comment
  that the duplication is deliberate.
- **A green connected run still prints `> Task :app:connectedDebugAndroidTest FAILED` /
  "There were failing tests" / `BUILD FAILED`.** On AGP 9.4.0 / UTP 32.4.0 the task writes
  `test-result-exit-code.txt` = `1` for a fully green run (D-123). Read `CONNECTED GATE: …` and
  `TEST-*.xml`, never the task's exit status. This session it cost a real detour: the 90/90 run
  looked red.
- **Never start a RED probe while a background connected run may still be alive.** `run_in_background`
  reports the shell, not the Gradle daemon's work: a first full run was still driving `am instrument`
  and `adb logcat` when the probe started. The probe then failed twice for reasons that had nothing
  to do with the code — Kotlin incremental compilation (`shrunk-classpath-snapshot.bin` missing) and
  `java.nio.file.FileSystemException` on `logcat-com.anchor.mine.export.ExportTest-*.txt` held by the
  other run. Check with
  `powershell -NoProfile -Command "Get-CimInstance Win32_Process | ? { $_.CommandLine -match 'gradle|utp|am instrument|adb logcat' }"`
  before mutating a file for a probe, and restore from a saved copy (`cp file /tmp/file.bak` **before**
  editing) rather than from memory.

### 2026-11-08 — row 20 (read-only Memory slice)

- **The `core` module has no `compileDebugKotlin` task.** `./gradlew :core:compileDebugKotlin` fails
  with *"Cannot locate tasks that match ':core:compileDebugKotlin'"* — `core` is a plain Kotlin/JVM
  library, so the task is `:core:compileKotlin` (add `--offline`; it resolves fine from the cache).
  Only `:app` has the `compileDebug*` variants.
- **Append a new tab, never insert it.** `MainActivity`'s tab dispatch is a positional
  `when (selectedTab)` and several connected tests click a tab by title and then assume the index.
  Row 20's "Memory" went on the end (index 5) so `0..4` stayed put and no existing test needed editing.
  If a future slice must reorder tabs per blueprint 51 §2, that renumbering is its own commit with
  its own gate run — not a side effect of adding a screen.
- **A one-shot `LaunchedEffect(selectedTab)` is not one-shot.** `UiState` is a fresh object on every
  update, so a key on the tab alone re-fires on every state change. The guard is a state field
  (`memoryLoaded`) that the load itself sets, and it must be *proven* load-bearing — dropping
  `&& !state.memoryLoaded` failed exactly `memoryTab_FirstOpen_AsksForTheLoad_ExactlyOnce`, which is
  the whole point of that test.
- **Recount XML per directory, not by globbing every `test-results` tree.** A single python glob over
  `app/build/test-results/**/*.xml` listed the same `testDebugUnitTest` directory twice and reported
  26 app tests instead of 13. Sum per immediate child directory of `build/test-results/` and assert
  one directory per suite.
- **Deliver through the surface the app actually renders, not the one the model was designed for.**
  `ChatItem.Nudge` and `UiState.chatItems` exist and are computed on every update, but nothing in
  `MainActivity` renders `chatItems` — the chat list draws `state.chatMessages`. A nudge written as a
  `ChatItem` would have been invisible. Before picking a delivery surface, grep for where the state
  is *read by the UI*, not just where it is produced. (D-136)
- **A pipeline that is wired in one direction can be a dead end.** `NudgeEngine.onNudgeSent` was
  reachable, but nothing called `processReply` or `processIgnore`, so the first thread it opened
  would have stayed open forever and the engine's own `open_thread` gate would have blocked every
  later nudge. When wiring a state machine into production, wire **every** transition it needs, not
  just the one the task names — and prove each with a test that fails when its call is removed.
- **A gate that can never fire is not a gate.** `calculateThreshold` tops out at 30.0 while every
  `NudgeCandidate` starts at 35.0, so the threshold comparison in the proactive tick is currently
  vacuous. It is wired and pinned at both ends of the formula, but it is not what holds a nudge — say
  so in the test and the docs instead of letting the test name imply it is load-bearing.
- **Two probes on one file: back up once, restore with `cmp`, not with a re-edit.** Probe A and probe
  B both edited `ChatController.kt`; restoring from `/tmp/ChatController.kt.bak` and asserting `cmp`
  is silent (plus zero `RED PROBE` markers) is what makes "the green run is on the real code"
  a checkable claim rather than a promise.
- **A test that asserts an absence passes vacuously when the write never happens.** `finish_WhenTheWriteFails_
  LeavesTheFlowOpen` asserts `onboarding.done` is absent after a failed seed — which is equally true if the flag is
  never written at all. Deleting the write reddened only 1 of 8 tests. The fix is a second test that pins the
  *presence* (`finish_WritesTheDoneFlag_AsTheVeryLastWrite` asserts `soulFacts.size == 3` at the moment the key is
  written), so the ordering claim has a witness. Whenever a RED probe reddens fewer tests than expected, the missing
  red is a missing test, not a weak probe.
- **`onAction` returning does not mean the write landed.** `AndroidStorage` wraps every method in
  `withContext(Dispatchers.IO)`, and the controller's branches `launch(defaultDispatcher)`, so a connected test that
  reads state or tables right after `onAction` races the write. All three `OnboardingSeedConnectedTest` cases were red
  for this reason alone. Poll for the settled state (`awaitDone`) instead of reading blind — and when a test is red,
  prove *which* side is wrong with a throwaway debug action before touching production code.
- **`-Pandroid.testInstrumentationRunnerArguments.class=A,B` silently runs only A.** The comma-joined filter ran 6 of
  9 tests while the task reported FAILED. Run classes separately.
- **A `python` heredoc that fails mid-write truncates the target to 0 bytes.** `io.open(p,'w')` truncates before the
  encode error fires; `docs/agent/STATE.md` was emptied this way (recovered with `git checkout --`, which is exact
  because the file was unmodified at HEAD). Write the script to a file with the Write tool, run it, and assert on the
  result — never edit a large tracked file through an inline heredoc. Also: `STATE.md`'s "Updated:" line is a single
  7k-character line, so an edit that replaces it must carry the old tail forward verbatim or the session history in it
  is lost.
- **Connected tests constructing `ChatController` must manage and cancel `CoroutineScope`.** `OnboardingSeedConnectedTest.controller()` passed an untracked `CoroutineScope(Dispatchers.Unconfined)`. Background coroutines (`loadInitialState`, `QuoteManager.onHomeOpened`, `drainQueuedTurns`) outlive test methods and access the database after `@After teardown()` closes `dbHelper`. When the next test calls `context.deleteDatabase()`, the database is unlinked while open (`SQLiteLog: (28)`), causing subsequent queries to fail HMAC check on page 1 (`sqlcipher: ERROR CORE sqlcipher_page_cipher: hmac check failed for pgno=1`), throwing `SQLiteDiskIOException: disk I/O error (code 522)` on `SELECT COUNT(*) FROM sqlite_schema;` and crashing the instrumentation runner. Always track and cancel `CoroutineScope` before closing `dbHelper`.
- **SQLCipher WAL initialization belongs on `SupportOpenHelperFactory`, not inside `onConfigure`.** Calling `db.enableWriteAheadLogging()` inside `SupportSQLiteOpenHelper.Callback.onConfigure` causes connection pool reconfiguration from DELETE to WAL mode. If concurrent connections are active, `PRAGMA journal_mode=...` aborts with `statement aborts at 2: database is locked; [PRAGMA journal_mode=delete]`. Configure WAL mode directly via `SupportOpenHelperFactory(password, null, true)` at factory creation.
- **Int8 vector cosine similarity cancels scale factors completely.** In int8 quantized vectors `QuantVector(bytes, scale)` with scalar scaling $v_i = b_i \cdot S$, the scale factors $S_1$ and $S_2$ multiply every term in both the dot product and the norms: $\sum (b_{1, i} S_1)(b_{2, i} S_2) = S_1 S_2 \sum b_{1, i} b_{2, i}$, and $\sqrt{\sum (b_{1, i} S_1)^2} \sqrt{\sum (b_{2, i} S_2)^2} = S_1 S_2 \sqrt{\sum b_{1, i}^2} \sqrt{\sum b_{2, i}^2}$. Because $S_1 S_2$ cancels in the fraction, computing cosine similarity requires zero floating-point conversions during the vector dot loop: only integer multiply-accumulate on signed bytes, followed by a single `sqrt` and division.
- **Graph neighborhood traversal directionality impacts hop distance.** When traversing an edge graph for neighborhood expansion, treating edges as undirected means an incoming edge $C \to A$ connects $C$ to $A$ at 1 hop. In a chain $A \to B \to C \to D$, adding a cycle edge $C \to A$ makes $D$ reachable from $A$ in 2 hops ($A \leftarrow C \to D$) instead of 3. Traversal algorithms must distinguish between undirected relational expansion and directed dependency analysis ("why" vs "what depends on this").
- **Pure-Kotlin domain data sources maximize testability.** Keeping data sources such as `WidgetDataSource` and `AutoExportScheduler` in `:core` rather than `:app` decouples business logic from platform presentation (RemoteViews, SAF FileProviders), allowing thorough, instantaneous JVM unit testing against `SharedFakeStorage` without instrumentation overhead.

## 2026-11-12 — Bulletproof & Invisible Recovery Audit #3
- **Never perform synchronous SQLCipher native queries in constructors.** `AndroidStorage.init` queried `app_meta` on the thread constructing `AndroidStorage(dbHelper)`. That first query triggered native library loading, key derivation, and schema open hooks. Making `deviceId` a lazy, synchronized `@Volatile` property initialized inside `withContext(ioDispatcher)` ensures the constructor is an instantaneous 0ms pointer assignment with zero risk of main-thread ANR.
- **Do not drop and recreate triggers/indexes unconditionally on `onOpen`.** `onOpen` runs on every cold start and background wake. Dropping and recreating 13 triggers and 19 indexes rewrote SQLite schema metadata 32 times on every DB open. Guarding with `SELECT COUNT(*) FROM sqlite_master WHERE type = 'trigger' < 13` checks schema integrity in <0.1ms and avoids catalog churn.
- **Batch N+1 queries in UI state refresh.** `ChatController.refreshUI()` called `hasUndo(opId)` in loops over recent receipts and closed items (up to 40 sequential queries). Batching with `SELECT undo_of FROM raw_event WHERE undo_of IS NOT NULL AND ts_utc >= ?` via `getUndoneOpIds` collapsed 40 sequential disk and IPC round-trips into a single query.
- **Voice recording buffers must avoid boxing storms.** Using `mutableListOf<Float>()` allocates a 16-byte `java.lang.Float` object for every sample (960,000 objects per minute at 16 kHz). Switching to chunked `ArrayList<FloatArray>` primitive chunks completely eliminates GC pressure and audio recording micro-stutters.
- **Lenient JSON extraction tanks small-LLM syntax hallucinations.** Small models occasionally output prose alongside JSON, drop closing braces (`}`), or omit escaping on quotes inside `"reply"`. Lenient fallback parsing and silent 1-turn retry on action claims (`loops < 2`) prevent user-facing crashes and error bubbles.
- **Prune operational logs periodically to guarantee 90-day battery & storage armor.** Unbounded growth of `diag_log`, `reminder_fire`, and `outbox` degrades B-tree query performance over months. Calling `pruneOperationalLogs` from `runSweeper()` keeps diagnostics capped at 7 days, outbox at 7 days, and reminder fire history at 90 days.
- **`setExactAndAllowWhileIdle` requires permission guards on API 31+.** Calling `setExactAndAllowWhileIdle` without checking `canScheduleExactAlarms()` or catching `SecurityException` crashes if permission is revoked. Non-urgent background delays (like post-call nudges) should check `canScheduleExactAlarms()`, fall back to `setAndAllowWhileIdle`, and wrap in `try/catch`.
- **Auto-export must never use a fallback or hardcoded passphrase.** Writing unencrypted or default-passphrase backups to disk or SAF violates user privacy invariants. Auto-export must read the user's explicit passphrase and skip safely if unset.
- **Sensors and timers must be gated behind feature flags on cold boot.** Inactive background features must not register sensor event listeners or launch periodic coroutine loops. Check settings first and only register when explicitly enabled. Step detectors only fire on motion; an explicit inactivity timer is required to transition the state machine back to `STILL`.

## 2026-11-12 — v3 Ambient Architecture & Acoustic Testing
- **JUnit4 test methods must have void return type in Kotlin.** In Kotlin, writing `@Test fun myTest() = runBlocking { ... }` compiles to a method returning `Unit` (`kotlin.Unit`), which causes `AndroidJUnit4ClassRunner` to throw `org.junit.runners.model.InvalidTestClassError: Method ... should be void`. Test methods must use block bodies `@Test fun myTest() { runBlocking { ... } }` to ensure a `void` return type.
- **Cross-module Kotlin properties cannot be smart cast.** A public property declared in another Gradle module (e.g., `state.latestAmbientReport` from `:core` used in `:app`) cannot be smart-cast with `if (state.latestAmbientReport != null)` because the compiler cannot guarantee the getter won't return null on the next call. Always capture it in a local variable `val report = state.latestAmbientReport; if (report != null)`.
- **Synthetic acoustic fixtures eliminate flaky physical audio tests.** Real microphone and speaker tests in CI or automated device harnesses suffer from ambient room noise, hardware gain differences, and human presence requirements. Pure-math synthetic signal generators (16 kHz sine wave, vocalic formant speech bursts with syllabic modulation, white noise, and deterministic L2-normalized 192-dim voiceprints) provide 100% deterministic, mathematically verifiable VAD, RMS, and presence gate assertions.
- **Ambient speech must be strictly Proposal-Only.** Allowing ambient speech to mutate or close active items risks destructive actions caused by background conversation fragments, TV audio, or third-party speech. Every item extracted from ambient listening must have `status = "proposed"` and `origin = "ambient"`, requiring explicit user gesture (Accept / Accept All) to activate.
- **Biometric vectors must be excluded from backup exports.** Voiceprints and ambient staging transcripts contain highly sensitive biometric and conversational data. Excluding `voiceprint`, `ambient_session`, `ambient_window`, `ambient_segment`, `ambient_report`, and `ambient_report_item` from `AndroidExportManager.EXPORTED_TABLES` ensures that user backups remain strictly portable without ever leaking biometrics or private raw dialogue.
