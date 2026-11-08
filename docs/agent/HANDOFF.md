# HANDOFF (v1 audit §6 item 3 — onboarding + soul seeding, unit + device verified, uncommitted)

Read this first. Facts only; every claim below has a command and an exit code behind it.

## 1. What changed

**One logical change: audit §6 item 3, "Onboarding + soul seeding"** (blueprint 52 §3.1/§3.2, rows 4/21, D-137).
Everything is **uncommitted**. Row 20 (D-135) is `8d00864` and item 2 (D-136) is `34181d3` — both
already in `git log`, not part of this delta.

| File | Status | What |
|---|---|---|
| `core/src/main/java/com/anchor/core/ui/ChatContract.kt` | modified | `UiState.onboardingDone` / `onboardingStep` / `onboardingAnswers` / `onboardingHealthLocalOnly` / `onboardingError`; `toString()` extended; six `UiAction`s (`OnboardingNext`, `OnboardingBack`, `OnboardingAnswer`, `OnboardingHealthLocalOnly`, `OnboardingConfirmDay`, `OnboardingFinish`) |
| `core/src/main/java/com/anchor/core/ui/ChatController.kt` | modified | reads `onboarding.done` in `loadInitialState`; `persistOnboardingStep`; the six branches; `OnboardingFinish` seeds one `SoulFact` per non-blank answer then writes the done flag **last**; companion keys `onboarding.step` / `onboarding.done` |
| `app/src/main/java/com/anchor/mine/MainActivity.kt` | modified | gate `if (!state.onboardingDone) { OnboardingScreen(state, onAction); return }` (after the global-error branch, before the tabs); `OnboardingScreen` (consent → day → soul → done) |
| `app/src/androidTest/java/com/anchor/mine/ui/MemoryScreenTest.kt` | modified | passes `onboardingDone = true` |
| `app/src/androidTest/java/com/anchor/mine/ui/NotificationsBlockedBannerTest.kt` | modified | passes `onboardingDone = true` |
| `core/src/test/java/com/anchor/core/ui/OnboardingSeedTest.kt` | **new** | 9 cases |
| `app/src/androidTest/java/com/anchor/mine/ui/OnboardingGateTest.kt` | **new** | 6 Compose cases |
| `app/src/androidTest/java/com/anchor/mine/ui/OnboardingSeedConnectedTest.kt` | **new** | 3 cases on a real `AndroidStorage` (`mine_onboarding_test.db`) |

**No storage change and no migration — DB stays v5.** Progress is stored in `app_meta` settings keys, not
blueprint 52's `onboarding_state` table (D-137).

**Deviations, stated plainly:**
- Progress/completion in `app_meta` keys instead of an `onboarding_state` table (D-137).
- Answers are in-memory only: a process death mid-flow restores the *step* but not typed text.

Docs updated this session: `DECISIONS.md` (D-137, next free D-138), `TASKS.md` (V1-G6), `STATE.md`
(`Updated:` line + open question + two count rows), `LEARNINGS.md` (4 entries).

## 2. What is verified

| Claim | Command | Result |
|---|---|---|
| Unit suites green | `./gradlew :core:test :app:testDebugUnitTest --offline` | `BUILD SUCCESSFUL in 17s`, **EXIT=0**; XML recount **core 335/0/0/0**, **app 13/0/0/0** |
| Done flag is load-bearing (probe A) | delete `storage.saveSetting(ONBOARDING_DONE_KEY, "1")`, `:core:test --tests OnboardingSeedTest` | 2 cases red |
| Done flag is written **last** (probe B) | move the done-flag write above the fact loop | the same 2 cases red |
| UI gate is load-bearing (probe C) | `if (!state.onboardingDone)` → `if (false)`, `:app:connectedDebugAndroidTest -P…class=com.anchor.mine.ui.OnboardingGateTest` | `tests 6 failures 5 errors 0`; `finishedInstall_ShowsTheTabs_AndNoFlow` correctly stays green |
| Restore is clean | `cp /tmp/MainActivity.kt.bak … && cmp …` | `RESTORED_IDENTICAL`, md5 `8db5af97c4b3cc249cd865181e6a7f60` |
| Full connected suite green | `./gradlew :app:connectedDebugAndroidTest --offline` (no class filter), `moto g73 5G - 14` | XML `tests 103 failures 0 errors 0 skipped 0`; `:app:connectedDebugAndroidTestVerify` → `CONNECTED GATE: PASS - 103 test(s), 0 failed, 0 skipped (source: xml)`, `BUILD SUCCESSFUL in 7s` |
| Exit code 1 is pre-existing | same connected task on the **stashed (pre-slice)** tree | `tests 94 failures 0 errors 0`, `test-result-exit-code.txt` = `1`, `EXIT=1` (`build/tmp/baseline-connected.log`) |

335 = 326 (item 2) + `OnboardingSeedTest` 9. Connected 103 = 94 + `OnboardingGateTest` 6 +
`OnboardingSeedConnectedTest` 3.

## 3. NOT Verified

- **Nobody has walked the flow on the phone by eye.** The gate and screen are proven by Compose tests
  against a fake state; no one has tapped through consent → day → soul on the device.
- **The seeded facts are not shown anywhere in the UI.** `OnboardingSeedConnectedTest` reads them back
  through `getActiveSoulFacts()` and a raw query, but the Memory tab is never opened in a test.
- **Rule 6 is still open and now bites the onboarding:** a local-only health seed is written with
  `local_only = 1` and is **filtered out** by `getActiveSoulFacts`, so it is invisible in the Memory
  tab rather than shown with a lock. Whether that is intended is the user's call (STATE.md open question).
- **Answers are lost on process death mid-flow** (in-memory only); the step is restored, the text is not.
- Carried over unchanged: threshold-vs-base-value is vacuous (D-136); `nudge_log`/`nudge_pool`/`nudge_hold`
  are schema-only; `nudgeGate` is sticky; `ChatItem.Nudge` is dead; the E-17 `rowid` tiebreak is not
  proven load-bearing (D-134); `MEMORY_VIEWER_PAGE = 200` is not exercised at its boundary.

## 4. Current failing tests

None known. `:core:test` 335/0/0/0, `:app:testDebugUnitTest` 13/0/0/0, connected 103/0/0.

## 5. Open decisions needing you

- **Commit now?** One logical change is uncommitted (onboarding + soul seeding, D-137).
  Proposed message: `feat(core,app): add first-run onboarding and soul seeding (audit §6 item 3, D-137)`.
- **Rule 6 for soul facts.** A local-only fact is hidden, not listed-and-locked. Relax
  `getActiveSoulFacts` to let local-only facts through with a lock, or accept "hidden" as the rule?
- **Threshold vs base value (D-136).** The threshold can never hold a nudge today.
- **Next slice:** audit §6 items 6 (offline queue, row 16) and 7 (release signing, row 22 — the release
  is signed with the **debug key** at `app/build.gradle.kts:61`) are the remaining open items.
- **D-105 by-eye verdict** is still yours (Gentle half captured; see `STATE.md`).

## 6. Traps for the next run

- **A green connected run still prints `> Task :app:connectedDebugAndroidTest FAILED` /
  `BUILD FAILED`** with `test-result-exit-code.txt` = `1`. Read `CONNECTED GATE: …` and `TEST-*.xml`,
  never the task's status (D-123). This is now proven pre-existing on a pre-slice tree.
- **The `CONNECTED GATE:` line comes from `:app:connectedDebugAndroidTestVerify`**, a separate task.
  Run it after the suite; it reads the XML already on disk.
- **`onAction` returning does not mean the write landed.** `AndroidStorage` writes on
  `Dispatchers.IO`; connected tests must poll (`awaitDone`) rather than read state right after.
- **`-Pandroid.testInstrumentationRunnerArguments.class=A,B` runs only A.** Run classes separately.
- **Never start a RED probe while a background connected run may still be alive.** Check
  `powershell -NoProfile -Command "Get-CimInstance Win32_Process | ? { $_.CommandLine -match 'gradle|utp|am instrument|adb logcat' }"`
  first — idle **Gradle and Kotlin daemons match `gradle`** and are harmless; look for `utp`,
  `am instrument` or `adb logcat` specifically. `cp file /tmp/file.bak` **before** editing.
- **After a probe, prove the restore with `cmp`**, not by eyeballing the diff.
- **Do not edit large tracked docs with an inline `python` heredoc.** A mid-write encode error
  truncates the file to 0 bytes (it happened to `STATE.md` this session; recovered with
  `git checkout --`). Write the script with the Write tool, run it, assert on the result.
- **`STATE.md`'s `Updated:` line is one ~7k-character line.** Replacing it must carry the old tail
  forward verbatim or the session history in it is lost.
- **`core` has no `compileDebugKotlin`.** Use `:core:compileKotlin --offline`.
- **Append tabs, don't insert them.** `when (selectedTab)` is positional and connected tests click
  by title then assume the index.
- **Recount XML per immediate child directory** of `build/test-results/`; a recursive glob
  double-counts the same suite. The connected recount must scope to `connected/debug/` —
  `connected/release/` holds a stale 6-test `ExportTest` XML.

## 7. Exact next step

Commit the onboarding slice (see §5), then start **audit §6 item 6** (offline queue, v1 row 16) from
`docs/agent/work/audit-2026-11-08-v1-scope.md` §6.

Optional, on an idle machine (no UTP/adb processes — see §6): the E-17 tiebreak RED probe.
```
cp app/src/main/java/com/anchor/mine/storage/AndroidStorage.kt /tmp/AndroidStorage.kt.bak
# remove ", rowid DESC" from getRecentMessages only, then:
./gradlew :app:connectedDebugAndroidTest :app:connectedDebugAndroidTestVerify --continue
# expect CONNECTED GATE: FAIL on getRecentMessages_TiedTimestamps_ReturnInWriteOrder; restore the .bak
```
