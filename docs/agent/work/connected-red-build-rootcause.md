# Root cause: `connectedDebugAndroidTest` writes exit code `1` while all tests pass

Date: 2026-11-06
Verdict: **AGP 9.4.0 / UTP 32.4.0 defect, not a Mine defect.** The task's success flag is
derived from a `Map<String,Boolean>` that the test engine never populates on this device,
and a missing key is coerced to `false`.

## 1. Observed signature (fresh copy, this session)

| Artifact | Content |
|---|---|
| `app/build/outputs/androidTest-results/connected/debug/TEST-moto g73 5G - 14.xml` | `<testsuites tests="59" failures="0" errors="0" skipped="0">`, 59 `<testcase>`, 0 `<failure>`, 0 `<error>` |
| `app/build/reports/androidTests/connected/debug/index.html` | 100% successful |
| `app/build/outputs/androidTest-results/connected/debug/test-result-exit-code.txt` | 1 byte, `od -c` → `1` |
| Gradle | `Execution failed for task ':app:connectedDebugAndroidTest'` … `There were failing tests.` |

Two independent reporters disagree with the build gate. The gate is authoritative
(`DeviceProviderInstrumentTestTask` throws on the flag), so the flag is what must be explained.

## 2. The gate reads exactly one integer

```java
// com/android/build/gradle/internal/tasks/DeviceProviderInstrumentTestTask.java:449-458
if (!success) { … throw new GradleException(message); }
```

```kotlin
// com/android/build/gradle/internal/testing/utp/UtpTestUtils.kt:186
return testResultExitCodeFile.exists() && testResultExitCodeFile.isFile &&
       testResultExitCodeFile.readText().trim().toInt() == 0
```

The XML is never consulted. So: who writes `test-result-exit-code.txt`?

## 3. Writer: `AndroidTestEngineRunner.execute` (gradle-work-action-32.4.0.jar)

Three assignments to the local `success` flag (local 7):

1. **Map lookup loop, offsets 455-487**
   ```
   455: aload 10                  // Map<String,Boolean> from the launcher executor
   457: aload 16                  // deviceSerialNumber
   459: Map.get(Object)
   464: checkcast Boolean
   468: ifnull  477                // key absent
   471: Boolean.booleanValue()
   474: goto    479
   477: pop
   478: iconst_0                  // ← absent key treated as false
   479: ifne    426
   482: iconst_0
   487: istore 7
   ```
2. Launch failure `catch` (492-507) → logs `"Failed to execute launcher"`, sets false.
3. Merge failure `catch` (1123-1139) → logs `"Failed to merge test results"`, sets false.

Then:
```
1142-1176: iload 7; ifeq 1172; ldc "0"; goto 1175; 1172: ldc "1"
```

**Log forensics rule out (2) and (3).** In `%TEMP%/utp-debug.log` (8113 lines) neither
`Failed to execute launcher`, `Failed to merge test results`, nor
`Could not find test-result.pb for device:` appears. The merged `test-result.pb` exists
(19695 B) and the XML was generated (`7746 [INFO] […report.XmlTestRunListener] XML test result file generated at …`).
Therefore the only live path is **(1): the map lookup missed**.

## 4. The map has exactly one producer

`_init_$lambda$0` (default `launcherExecutor`; `UtpActionImpl.run` passes null, so this is the one in use):

```
14: new DeviceTrackingListener → astore_2
22-50: LauncherConfig.builder()
         .enableTestExecutionListenerAutoRegistration(false)
         .addTestExecutionListeners([listener, deviceTracker]).build()
53-63: LauncherFactory.create(config).execute(request)
68-72: return deviceTracker.getPerDeviceAllTestsPassed()
```

`DeviceTrackingListener` (implements `org.junit.platform.launcher.TestExecutionListener`) has one
field, `ConcurrentHashMap<String,Boolean> perDeviceAllTestsPassed`, written in only two places:

- `executionStarted(TestIdentifier)` 19-58: `getDeviceId(...)`; `isContainer()` (`ifeq 59`);
  `length() > 0` (`ifle 42`) → `putIfAbsent(deviceId, TRUE)`.
- `executionFinished(TestIdentifier, TestExecutionResult)` 0-80: `getDeviceId(...)`; `isTest()`
  (`ifeq 80`); `length() > 0` (`ifle 48`); `status == FAILED` (`if_acmpne 80`) → `put(deviceId, FALSE)`.

`getDeviceId` 10-51: `getUniqueId().substringAfterLast("[device:", "")`, then
`substringBefore("]", …)`; `null` if empty.

Consequence: the listener can only ever write `false` for a **genuinely FAILED** test — and
there are none (`failures="0"`). So the map is either empty or holds `true`; the `false` cannot
come from the listener. It comes from the **read** (`ifnull 477 → iconst_0`).

## 5. Why the key is missing: the engine never adds children

The request carries **no configuration parameters** — `execute` offsets 193-235 build only:

```
197: LauncherDiscoveryRequestBuilder.request()
200-226: new Filter[1]; EngineFilter.includeEngines(["android-test-engine"])
229: .filters(...)
232: .build()
```

`AndroidTestEngine.discover(EngineDiscoveryRequest, UniqueId)`:
```java
return new AndroidTestEngineDescriptor(uniqueId);
```
No `addChild` anywhere; a search of all 73 classes in `android-test-engine-1.0.1.jar` for
`addChild` / `getChildren` returns nothing.

`AndroidTestEngineDescriptor.execute` 169-180 builds the device id:
```
169-180: getUniqueId().append("device", serial)   // → [device:10.175.15.5:5555]
224-260: new AndroidDeviceDescriptor(...)
262-268: 19.setParent(this)
271-277: dynamicTestExecutor.execute(19)          // ★ DynamicTestExecutor, NOT addChild
```
`AndroidDeviceDescriptor$execute$4.invokeSuspend` 110-131 likewise delivers each test via
`$dynamicTestExecutor.execute(newTest.getDescriptor())`.

So container descriptors are never registered as children of the engine. If the hierarchical
engine therefore never emits `executionStarted` for the device container, `perDeviceAllTestsPassed`
stays empty, `Map.get("10.175.15.5:5555")` returns `null`, `ifnull` forces `false`, and `"1"` is
written — while the XML and HTML report 59/59 green. This reproduces the observed signature exactly.

## 6. Corroborating artifact evidence

| File | Serial present? |
|---|---|
| `…/moto g73 5G - 14/device-info.pb` (102 B) | **yes** — contains `10.175.15.5:5555` and `motorola:` |
| `…/test-result.pb` (1081 B, per-device) | **no** — 0 occurrences of `10.175`, `5555`, `device:`, `[device`, `emulator`; all three test names present |

Correction (2026-11-06): an earlier draft claimed the merged proto carried no test names. It does —
what it lacks is the device serial, which is exactly the key the reader looks up. The merged copy
(`connected/debug/test-result.pb`, 1083 B) is the per-device copy (1081 B) prefixed with an empty
field 1 (`0a 00`).

The writer keys on the serial; the reader looks up the serial; the merged proto carries only the
display name. `PathSafety.sanitizeDisplayName` (`Regex→_`, `isBlank→"device"`, `trim`) explains the
on-disk folder name `moto g73 5G - 14` and the `10.175.15.5_5555` form seen earlier.

## 7. What this means for Mine

- **No Mine test is failing.** 59/59 pass; instrumentation ran with the correct runner args
  (`utp-debug.log:7550` shows `am instrument … -e notAnnotation com.anchor.mine.smoke.LiveTest …`,
  proving the hermetic/live split works).
- **The red build is a false signal** produced by the toolchain. It is exactly the class of
  false signal the hardening directive targets: a gate that says "failing" while its own report
  says "100% successful" makes every other verification claim untrustworthy.
- **The previous `STATE.md` annotation ("device-ROM teardown artifact", `INSTRUMENTATION_CODE: -1`)
  is wrong.** The teardown has nothing to do with it; the flag is computed from a JUnit-platform map
  inside the Gradle worker, after instrumentation has already finished successfully.

## 8. Options (not yet decided — needs a decision line)

1. **Accept and annotate**: document the AGP defect, treat the XML as ground truth, keep the
   evidence file. Zero code change, but the build stays red and any CI would stay red.
2. **Gate on the XML instead of the exit code**: add a verification task that parses
   `TEST-*.xml` and fails on `failures>0 || errors>0`, and document that `connectedDebugAndroidTest`'s
   own exit code is untrustworthy on this toolchain. Restores a *true* signal.
3. **Upgrade/downgrade AGP** to a version where `AndroidTestEngineDescriptor` registers children
   (would need to be verified against release notes; not attempted).

Recommendation: (2), because it converts an unfalsifiable red build into a named, checked hatch —
and it is the only option that keeps a real failure detectable.

## 9. Reproduction commands

```
./gradlew :app:connectedDebugAndroidTest
od -c app/build/outputs/androidTest-results/connected/debug/test-result-exit-code.txt   # → 1
python -c "import re;d=open(r'app/build/outputs/androidTest-results/connected/debug/TEST-moto g73 5G - 14.xml','rb').read();print(re.search(rb'<testsuites[^>]*>',d).group(0))"
```

## 10. Resolved: D-123 gate (2026-11-06)

Option (2) implemented. `gradle/connected-results-gate.py` + `:app:connectedDebugAndroidTestVerify`.

### Artifact schema, verified against the shipped proto

`proto/api/core/{test_result,test_case,test_status}.proto` inside
`test-result-proto-1.0.1.jar` (Gradle cache) is authoritative:

```
TestResultProto { repeated TestResult results = 3; ... }
TestResult      { TestCase test_case = 1; TestStatus test_status = 2; Error error = 3;
                  repeated Artifact output_artifact = 4; repeated TestDetailsEntry details = 5 }
TestCase        { string test_class = 1; string test_package = 2; string test_method = 3; ... }
TestStatus      { 0 UNSPECIFIED, 1 STARTED, 2 FAILED, 3 PASSED, 4 IGNORED, 5 ERROR,
                  6 ABORTED, 7 CANCELLED, 8 SKIPPED, 9 IN_PROGRESS }
```

Confirmed against this device's per-device `test-result.pb` (1081 B): three `TestResult`
entries, each with field 1 (case: class/package/method), field 2 = **3 (PASSED)**, field 4
(artifacts). Status bytes at offsets 81 / 377 / 682.

**A first draft of the gate guessed this enum and had it inverted** (2=PASSED, 3=FAILED). The real
artifact caught it: a green run parsed as 3 failures. The enum is now transcribed from the proto,
not inferred.

### What the gate trusts

| Evidence | Rule |
|---|---|
| `TEST-*.xml` present | authoritative; counts come from `<testcase>` elements, never from the suite's `tests`/`failures` attributes |
| no XML, `test-result.pb` present | fallback; PASSED/IGNORED/SKIPPED are non-failures, everything else fails |
| unreadable file | FAIL, named per file (never reported as "zero tests") |
| no artifact at all | FAIL |
| zero tests parsed | FAIL |
| `test-result-exit-code.txt` | read and reported, **never** used for the verdict |

### Verification

- Real artifact: `CONNECTED GATE: PASS - 3 test(s), 0 failed, 0 skipped (source: xml)`, plus
  `note: UTP exit-code file says '1'`; Gradle exit 0.
- Synthetic red XML (one `<failure>` injected): Gradle exit 1, `FAILED com.anchor.mine.alarms.AlarmSelfTest.testGentleReminderFires_SetsFlag`; restored artifact → exit 0.
- Fixture matrix, all as intended: XML case failure, XML case error, summary attributes lying
  (`failures="9"` with all cases passing → PASS), malformed XML, truncated proto, every proto
  status 0-9, XML-preferred-over-proto in both directions, empty directory.
- Configuration cache: entry stored and reused; the task action uses `ProcessBuilder` because
  `providers.exec` inside `doLast` captures the build script and the cache rejects it.

### Usage

```
./gradlew :app:connectedDebugAndroidTest :app:connectedDebugAndroidTestVerify --continue
```
