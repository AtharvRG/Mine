# Test and Build Verification Evidence (recounted 2026-11-06)

This directory stores verification evidence files, command transcripts, and hardware validation records.
Older files (Nov 5 and earlier) are retained for history; the counts below are the current ground truth.

## Ground Truth Test Counts (2026-11-06, from JUnit XML result files)

| Suite | Tests | Skipped | Failures | Errors | Command | Exit code |
|---|---|---|---|---|---|---|
| `:core:test` | 247 | 0 | 0 | 0 | `./gradlew :core:cleanTest :core:test` | 0 |
| `:app:testDebugUnitTest` | 9 | 0 | 0 | 0 | `./gradlew :app:cleanTestDebugUnitTest :app:testDebugUnitTest` | 0 |
| Connected clean-install (moto g73 5G - 14) | 53 | 0 | 0 | 0 | `./gradlew :app:uninstallDebug :app:uninstallDebugAndroidTest` + `./gradlew :app:connectedDebugAndroidTest` | 1* |

Total: 309 tests, 0 failures per JUnit XML/HTML reports.
Core count history: 242 (A1 recount, before Part-B TDD tests) -> 247 after
B1 (1 test), B2 (1 test), B3 (3 tests) were added; forced-fresh run
`core-test-20261106-partb.txt` (UNIT_EXIT=0, XML tests=247 failures=0).

\* Connected exit-1 annotation: gradle prints "There were failing tests", but the JUnit XML
(`app/build/outputs/androidTest-results/connected/debug/TEST-moto g73 5G - 14.xml`) and the HTML
report (`app/build/reports/androidTests/connected/debug/index.html`) both record 53 tests,
0 failures, 0 errors, 0 skipped, 100% success. The nonzero exit is the instrumentation
teardown signature of this device ROM: the Nov 5 direct `am instrument` run ended with
`OK (49 tests)` followed by `INSTRUMENTATION_CODE: -1` (see `connected-tests-runner.txt`),
and both 2026-11-06 clean-install gradle runs show the same pattern (all-green results,
nonzero process exit). The logcat crash buffer shows no app crash in the run windows;
teardown is a normal "finished inst" force-stop followed by AGP uninstall cleanup.
Not retried a third time (AGENTS.md forbids identical reruns; two runs establish it as
deterministic, not flaky).

## Evidence Files (2026-11-06)

- `unit-tests-20261106-fresh.txt` — forced fresh core+app unit run (`cleanTest`/`cleanTestDebugUnitTest`
  then `test`/`testDebugUnitTest`), BUILD SUCCESSFUL, `UNIT_EXIT=0`, 242 core + 9 app from XML.
- `connected-clean-install-20261106.txt` — connected run 1: uninstall of both APKs (clean install),
  then `:app:connectedDebugAndroidTest`; 53/53 green in XML/HTML, gradle exit 1 (teardown artifact).
- `connected-clean-install-20261106-run2.txt` — connected run 2 (retry): identical signature,
  53/53 green in XML/HTML, gradle exit 1.
- `core-test-20261106.txt`, `app-test-20261106.txt` — earlier same-day unit runs (UP-TO-DATE
  configuration-cache reruns; superseded by `unit-tests-20261106-fresh.txt`).
- `b5-manifest-dump.txt` — `aapt2 dump xmltree` of the installed debug APK's compiled manifest:
  BootReceiver `exported=true` with 4-action filter (BOOT_COMPLETED, MY_PACKAGE_REPLACED,
  TIME_SET, TIMEZONE_CHANGED); AlarmReceiver/QuoteAlarmReceiver `exported=false`.
- `b5-receiver-dump.txt` — device `dumpsys package com.anchor.mine` after reinstall: BootReceiver
  registered under all 4 actions (filter cc5b617), RECEIVE_BOOT_COMPLETED granted=true.
- `b5-evidence.md` — B5 report consolidating manifest dump + device registration +
  BootReceiverIgnoreTest 1/1 PASS (non-intended broadcasts are no-ops; BOOT_COMPLETED sweeps
  a stale canary). Decision: D-122.
- `core-test-20261106-partb.txt` — forced fresh `:core:test` run after the B1/B2 test
  additions (and B3 already committed): BUILD SUCCESSFUL, `UNIT_EXIT=0`, XML
  tests=247 failures=0 (242 + B1 x1 + B2 x1 + B3 x3).

## Connected Test Breakdown (53 tests, 11 classes — from run-2 XML)

- `AlarmReceiverTest`: 3 passed
- `AlarmSelfTest`: 3 passed
- `BootReceiverTest`: 1 passed
- `ManifestPermissionTest`: 1 passed
- `StatusBarAlarmIconTest`: 3 passed
- `ExportTest`: 6 passed
- `QuoteAlarmReceiverTest`: 2 passed
- `LiveSmoke15Test`: 1 passed
- `AndroidStorageTest`: 30 passed
- `DatabaseMigrationTest`: 1 passed
- `StorageTest`: 2 passed

Artifacts: `app/build/reports/androidTests/connected/debug/index.html`,
`app/build/outputs/androidTest-results/connected/debug/TEST-moto g73 5G - 14.xml`.

## Historical (Nov 5 and earlier — superseded counts)

- `core-test.txt`, `app-testDebugUnitTest.txt`, `connected-tests.txt`, `connected-tests-runner.txt`:
  R12d-era runs (197 core, 9 app, 49 connected). The connected count grew to 53 after
  StatusBarAlarmIconTest (3) and LiveSmoke15Test (1) were added.
- `security-exception.txt`: Android 14 `USE_EXACT_ALARM` SecurityException reproduction (R3).
- `r10g-*`, `d105-*`, `smoke_*`, `alarms_scroll*.xml`, `*dump*.xml`, `mine_chat*.xml`,
  `current_phone_screen.png`: UI dumps and screenshots from earlier device sessions
  (R10g release smoke, D-105 status-bar icon evidence, 15-utterance live smoke).
