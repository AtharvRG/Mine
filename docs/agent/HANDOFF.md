# HANDOFF (E-17 drain completeness — unit + device verified, uncommitted)

## Current Work & Status
- **Row 19 quick capture was committed** as `dd984ba` (`feat(capture): implement v1 row 19 quick capture`,
  19 files, +959/−161) and was device-verified at 89/89 at that time.
- **That commit FAILED independent verification** on one defect, **E-17**: with the app dead, two
  captures stored before the next launch were not both sent. The startup drain sent only the
  **oldest** pending capture; the turn's single assistant reply is written *after* both stored rows,
  so `QuickCapture.isUnanswered` then reported the second capture as answered without the model ever
  seeing it.
- **Fix is written, unit-proven, and device-proven.** Full connected suite is green at **90/90** on
  `moto g73 5G - 14` (`10.175.15.5:5555`). One honest gap: the `rowid` tiebreak has not been shown to
  *fail* without it (§3).
- Everything below is **uncommitted**.

## 1. What Changed (all uncommitted)

### Core
- **`core/src/main/java/com/anchor/core/ui/ChatController.kt`** — `sendText` now **queues** instead of
  dropping. A send that arrives while a turn is streaming joins `currentStreamJob` in a
  `while (_state.value.isChatLoading) { val job = currentStreamJob ?: break; job.join() }` loop, then
  starts its own turn. A dropped send would be marked answered by the previous turn's reply.
- **`core/src/main/java/com/anchor/core/storage/Storage.kt`** — KDoc only: `getRecentMessages` is
  documented as "newest `limit`, **oldest first**".
- **`core/src/test/java/com/anchor/core/ui/QuickCaptureDrainTest.kt`** (new) —
  `twoPendingCaptures_AreEachSentAsTheirOwnTurn_InWriteOrder`. Parks the live turn inside the fake
  `HttpTransport.postStream` (the one suspension point both SendMessage branches reach), issues the
  drain while it is in flight, asserts only 1 prompt has been posted, releases, then asserts 3 prompts
  in write order and `pending()` empty.
- **`core/src/test/java/com/anchor/core/SharedFakeStorage.kt`** — temporary `beforeGetSetting` hook
  was added during debugging and **reverted**; `getSetting` is the original one-liner.

### App
- **`app/src/main/java/com/anchor/mine/MainViewModel.kt`** — the startup drain iterates **every** id
  from `QuickCapture.pending(...)` and calls `controller.sendText(text, captureId)` for each (was
  `pendingCaptures.firstOrNull()`).
- **`app/src/main/java/com/anchor/mine/storage/AndroidStorage.kt`** — `getRecentMessages` orders by
  `ts_utc DESC, rowid DESC`; the `rowid` tiebreak keeps two captures stored in the same millisecond in
  write order.
- **`app/src/androidTest/java/com/anchor/mine/storage/AndroidStorageTest.kt`** — new
  `getRecentMessages_TiedTimestamps_ReturnInWriteOrder`. Forces the tie by rewriting `ts_utc` with the
  append-only trigger dropped, then restores the **exact shipped trigger DDL** (duplicated on purpose
  so the test cannot leave a weaker schema behind).

### Docs
- **`DECISIONS.md`** — **D-134** (every pending capture is its own turn; `sendText` queues; `rowid`
  tiebreak). Next free: **D-135**.
- **`TASKS.md`** — `[x] V1-G3b E-17 drain completeness (D-134)` with the RED probe verbatim and
  "Device half NOT yet run".
- **`STATE.md`**, **`LEARNINGS.md`**, this file.

## 2. Verified (command + exit code)
| What | Command | Result |
|---|---|---|
| Core + app unit + androidTest compile | `./gradlew :core:test :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin --offline` | `BUILD SUCCESSFUL in 46s`, **EXIT=0**; XML recount **core 317 / 0 / 0 / 0**, **app 13 / 0 / 0 / 0** |
| The new test | `QuickCaptureDrainTest` XML | `tests="1" skipped="0" failures="0" errors="0" time="0.433"` |
| RED probe | queue replaced with `if (_state.value.isChatLoading) return@launch` | `java.lang.AssertionError: each capture is its own turn expected:<3> but was:<1>` at `QuickCaptureDrainTest.kt:112`, **EXIT=1**; restored → GREEN |
| New device test compiles | `./gradlew :app:compileDebugAndroidTestKotlin` | `BUILD SUCCESSFUL in 16s`, **EXIT=0** |
| Full connected suite | `./gradlew :app:connectedDebugAndroidTest :app:connectedDebugAndroidTestVerify --continue` (no class filter) | `CONNECTED GATE: PASS - 90 test(s), 0 failed, 0 skipped (source: xml)` (`build/tmp/e17-full-final.log`); XML `tests="90" failures="0"` |
| Gate alone | `./gradlew :app:connectedDebugAndroidTestVerify` | `BUILD SUCCESSFUL in 11s`, **exit 0** |
| New device test | `AndroidStorageTest.getRecentMessages_TiedTimestamps_ReturnInWriteOrder` | present in the XML, **no `<failure>`**, 0.737 s (0.751 s in the earlier run `build/tmp/e17-connected.log`) |

The AGP task printed `FAILED` / *"There were failing tests"* with `test-result-exit-code.txt` = `1` on
this fully green run — the documented **D-123** defect, not a test failure.

## 3. NOT Verified
- **The `rowid` tiebreak is not shown to be load-bearing.** The test passes *with* it; removing
  `, rowid DESC` to get a RED was blocked twice by environment errors (Kotlin
  `shrunk-classpath-snapshot.bin` incremental-cache failure, then `FileSystemException` on a UTP
  logcat file held by a still-running background connected run). `AndroidStorage.kt` was restored
  byte-identically from `/tmp/AndroidStorage.kt.bak`; `git diff` shows only the intended hunk, and the
  90/90 run is on the restored code. Repeat the probe on an idle machine.
- **Queueing is proven only through the synchronous SendMessage branch** (hydrated `providerKey`). The
  A12 branch (`providerKey` blank → async `getSetting`) is not exercised by the drain test.
- **No real process-kill test** of "two captures stored while dead, both answered on next launch".
- Carried from row 19: the optional persistent capture notification is **not posted** (only its
  off-by-default switch exists); `TAG_QUICK_REPLY` is defined and unread (D-132).

## 4. Current failing tests
None known. `:core:test` 317/0, `:app:testDebugUnitTest` 13/0, connected 90/0/0.

## 5. Open decisions needing you
- **Commit E-17 now?** It is one logical change (drain loop + `sendText` queue + `rowid` tiebreak +
  two tests + docs) and is device-green; the only caveat is the unproven tiebreak RED, recorded in
  D-134 and TASKS.
- **Next slice:** rows 20 (memory viewer/editor, Trash and Erase) and 21 (onboarding, settings
  catalog, Quick setup), in that order.
- **D-105 by-eye verdict** is still yours (unchanged).
- **Should the capture notification actually be posted?** Row 19 lists it as "optional"; the switch
  exists but nothing reads it.

## 6. Traps for the next run
- **A green connected run still prints `> Task :app:connectedDebugAndroidTest FAILED` / "There were
  failing tests" / `BUILD FAILED`** with `test-result-exit-code.txt` = `1`. Read `CONNECTED GATE: …`
  and `TEST-*.xml`, never the task's status (D-123).
- **Never start a RED probe while a background connected run may still be alive.** `run_in_background`
  reports the shell, not the daemon's work. Check
  `powershell -NoProfile -Command "Get-CimInstance Win32_Process | ? { $_.CommandLine -match 'gradle|utp|am instrument|adb logcat' }"`
  first, and `cp file /tmp/file.bak` **before** editing anything for a probe.
- **Gate the suspension point the code path actually traverses.** The drain test first parked on
  `storage.getSetting("key")`; with a hydrated `providerKey` the controller takes the *synchronous*
  branch and never reads the key, so the gate never fired and the test hung. `HttpTransport.postStream`
  is the only point both branches reach.
- **Do not share the test's dispatcher with the controller.** A drain send parks on `job.join()`; if
  the only thread that could resume the live turn is the one blocked in that join, it deadlocks. The
  test gives the controller its own `UnconfinedTestDispatcher` scope.
- **`SharedFakeStorage.getSetting` must stay a one-liner** — the debug hook was reverted; do not
  reintroduce a seam into production test doubles for a single test.
- **The `while … currentStreamJob ?: break` loop** exits if `isChatLoading` is true with no job (a
  transient state); it cannot spin because `join()` suspends and the `finally` in `handleSendMessage`
  clears the flag.
- **`git checkout -- <file>` is destructive to uncommitted work** — everything above is uncommitted.
- Carried from row 19: `RemoteInput.getResultsFromIntent` accepts only `text/vnd.android.intent`;
  `connectedDebugAndroidTest` uninstalls the test APK; a `stopped=true` package drops explicit
  broadcasts; reply `PendingIntent` request code must be the notification id.

## 7. Exact next step
Commit E-17 as one logical change (code + tests + docs), then start **row 20** from
`docs/agent/work/audit-2026-11-08-v1-scope.md`. Optionally, before committing, re-attempt the
tiebreak RED probe on an idle machine (no Gradle/UTP/adb processes running):
```
cp app/src/main/java/com/anchor/mine/storage/AndroidStorage.kt /tmp/AndroidStorage.kt.bak
# remove ", rowid DESC" from getRecentMessages only, then:
./gradlew :app:connectedDebugAndroidTest :app:connectedDebugAndroidTestVerify --continue
# expect CONNECTED GATE: FAIL on getRecentMessages_TiedTimestamps_ReturnInWriteOrder; restore the .bak
```
