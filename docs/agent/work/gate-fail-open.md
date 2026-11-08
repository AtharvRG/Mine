# D-123 gate failed open — task had no inputs

Status: **fixed and verified** (2026-11-07)

## Defect

`connectedDebugAndroidTestVerify` in `app/build.gradle.kts` declared only
`outputs.file(reportFile)`. It declared **no inputs at all** — in particular not the results
directory it reads. Two consequences:

1. **No dependency on the test task.** `./gradlew :app:connectedDebugAndroidTestVerify --dry-run`
   listed `:app:connectedDebugAndroidTestVerify` and nothing else. The gate has no ordering
   relationship with the run that produces its input.
2. **Permanently UP-TO-DATE.** A task with no inputs and one output file is up-to-date after its
   first run, whatever happens to the directory it reads.

## Observed failure (fail-open)

A connected run produced a real failure. The gate reported PASS.

| Source | Verdict |
|---|---|
| Gradle, `:app:connectedDebugAndroidTestVerify` | `UP-TO-DATE`, `BUILD SUCCESSFUL`, exit **0** |
| `gate.json` on disk | `"source": "xml", "tests": 3, "failures": 0` |
| `TEST-moto g73 5G - 14.xml` on disk | **5 testcases, 1 `<failure>`** (`AlarmSelfTest.testAlarmFires_SetsFlag`) |
| Same script, same directory, run by hand | `FAIL - 5 test(s), 1 failed`, exit **1** |

The script is correct. The task never re-ran it.

## Reproduction (deterministic, no device needed)

Built a 3-test / 0-failure fixture from the real XML (the `AlarmReceiverTest` suite only — exactly
the numbers `gate.json` had recorded), then:

| Step | Expected | Observed |
|---|---|---|
| Gate on full settled dir (5 tests, 1 failure) | FAIL | `FAIL - 5 test(s), 1 failed`, exit 1 |
| Gate on partial fixture (3 tests, 0 failures) | PASS | `PASS - 3 test(s), 0 failed`, exit 0 |
| `:app:connectedDebugAndroidTestVerify` on a green dir | PASS | `CONNECTED GATE: PASS`, `BUILD SUCCESSFUL` |
| Same task again, unchanged | UP-TO-DATE | `UP-TO-DATE` |
| **Same dir, XML replaced with a failing one** | **re-run and FAIL** | **`FAILED`, `CONNECTED GATE: FAIL - 5 test(s), 1 failed`, exit 1** |

The last row is the failing-first proof: before the fix it reported `UP-TO-DATE` / exit 0 on a
directory containing a failure.

## Fix

One line added to the task:

```kotlin
inputs.dir(resultsDir).withPropertyName("connectedResults")
```

Any change to the results directory now invalidates the task and re-runs the gate.

## Verification

| Check | Result |
|---|---|
| `./gradlew :app:connectedDebugAndroidTestVerify` on the failing dir | `FAILED`, `CONNECTED GATE: FAIL - 5 test(s), 1 failed`, exit 1 |
| Green dir → PASS, unchanged → UP-TO-DATE | `PASS - 3 test(s)`; `UP-TO-DATE` |
| Green dir, XML swapped for a failing one | `FAILED`, `CONNECTED GATE: FAIL - 5 test(s), 1 failed`, exit 1 |
| Fresh device run of `AlarmSelfTest` (3/3 green) | `CONNECTED GATE: PASS - 3 test(s), 0 failed`, exit 0 |

The probe used a temporary `-PgateResultsDir=` override to point the task at a mutable fixture
directory. **It has been removed**; `connectedResultsDir` is back to
`layout.buildDirectory.dir("outputs/androidTest-results/connected/debug")`.

## Not fixed here

- **No `dependsOn("connectedDebugAndroidTest")`.** The task still does not *run* the tests; it only
  reads whatever is on disk. Adding `dependsOn` would make a red AGP run abort before the gate
  executes (the reason it was not wired in the first place, see the comment at
  `app/build.gradle.kts:78-79`). The input declaration is the correct fix for the fail-open; the
  ordering question is separate and still open.
- **The `<failure>` element is empty.** The test calls
  `org.junit.Assert.fail("ALARM tier did not fire within ${elapsed}ms. lastFiredId=… …")`, but the
  XML recorded `<failure></failure>` with no message and no stack trace, and the message is absent
  from `test-result.pb` as well. The failure was therefore **undiagnosable from its own artifact**.
  This is a reporting defect independent of the gate.

## The failure itself

`AlarmSelfTest.testAlarmFires_SetsFlag` failed once (`time="5.072"`) and passed on immediate re-run
(`time="5.313"`). D-124 measured healthy runs finishing at ~5.31 s, so 5.072 s is short of the
healthy figure, not merely slow. Its per-test logcat stops at exactly 5.000 s and contains
`started:` but **no `finished:`** line, with no crash, ANR or process kill in the window. The
mechanism is not established. D-125 closed #131 as "mitigated, unproven"; a real failure now exists,
but it carries no diagnostic payload, so it cannot be used to reopen the analysis as-is.
