# HANDOFF (v1 row 20 — read-only Memory slice, unit + device verified, uncommitted)

Read this first. Facts only; every claim below has a command and an exit code behind it.

## 1. What changed

**One logical change: v1 row 20, the read-only Memory tab** (blueprint 51 §3.1 rules 1 + 6, D-135).
The audit's ordered step 4, *"Compose test: viewer lists nodes/soul facts"*. Everything is
**uncommitted**.

| File | Status | What |
|---|---|---|
| `core/src/main/java/com/anchor/core/ui/ChatContract.kt` | modified | `UiState.memoryNodes`, `memorySoulFacts`, `memoryLoaded`; `toString()` extended with all three; `UiAction.LoadMemory` |
| `core/src/main/java/com/anchor/core/ui/ChatController.kt` | modified | `is UiAction.LoadMemory` branch: `getActiveNodes(MEMORY_VIEWER_PAGE)` + `getActiveSoulFacts()`, no model call; `memoryLoaded` stays false on a throw; `MEMORY_VIEWER_PAGE = 200` |
| `app/src/main/java/com/anchor/mine/MainActivity.kt` | modified | `MemoryScreen` composable; "Memory" **appended** at tab index 5; `5 -> MemoryScreen(state)`; one-shot `LaunchedEffect(selectedTab, state.memoryLoaded)` |
| `core/src/test/java/com/anchor/core/ui/MemoryViewerLoadTest.kt` | **new** | 2 cases: reads open items + active facts without calling the model; empty tables still mark loaded |
| `app/src/androidTest/java/com/anchor/mine/ui/MemoryScreenTest.kt` | **new** | 4 cases: lists items/facts/inbox count; local-only rows carry 🔒; empty tables say so; first open asks for the load exactly once |

**No storage change.** `getActiveNodes(limit, cursor)` and `getActiveSoulFacts()` already existed on
`Storage`, `AndroidStorage` and `SharedFakeStorage` — rule 1 was already satisfiable.

Docs updated this session: `DECISIONS.md` (D-135, next free D-136), `TASKS.md` (V1-G4), `STATE.md`
(`Updated:` line + two new counts rows), `LEARNINGS.md` (4 entries).

## 2. What is verified

| Claim | Command | Result |
|---|---|---|
| Unit suites green | `./gradlew :core:test :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin --offline` | `BUILD SUCCESSFUL in 14s`, **EXIT=0**; XML recount **core 319/0/0/0**, **app 13/0/0/0** |
| New core test actually ran | `core/build/test-results/test/*MemoryViewerLoadTest*.xml` | `tests="2" skipped="0" failures="0" errors="0" time="0.278"` |
| Load is RED-provable (1) | filter the load to `status == "active"`, re-run `:core:test` | `MemoryViewerLoadTest > loadMemory_ReadsOpenItemsAndActiveFacts_WithoutCallingTheModel FAILED / java.lang.AssertionError at MemoryViewerLoadTest.kt:87`, `BUILD FAILED in 10s`, **EXIT=1**; restored → `BUILD SUCCESSFUL in 10s` |
| One-shot guard is load-bearing (2) | drop `&& !state.memoryLoaded` from the `LaunchedEffect`, run `MemoryScreenTest` on device | `CONNECTED GATE: FAIL - 4 test(s), 1 failed, 0 errored (source: xml)`, exactly `memoryTab_FirstOpen_AsksForTheLoad_ExactlyOnce`; restored (`MainActivity.kt:139`), `ChatController.kt` proven byte-identical to its backup |
| Full connected suite green | `./gradlew :app:connectedDebugAndroidTest :app:connectedDebugAndroidTestVerify --continue` (no class filter), `moto g73 5G - 14` at `10.175.15.5:5555` | `CONNECTED GATE: PASS - 94 test(s), 0 failed, 0 skipped (source: xml)` (`build/tmp/row20-connected.log`); XML `tests="94" failures="0" errors="0" skipped="0"`, zero `<failure>` nodes |
| Gate alone | `./gradlew :app:connectedDebugAndroidTestVerify` | `BUILD SUCCESSFUL in 6s`, **EXIT=0** |

94 = 90 (E-17 baseline) + `MemoryScreenTest` 4. 319 = 317 + `MemoryViewerLoadTest` 2.

The AGP task printed `> Task :app:connectedDebugAndroidTest FAILED`, *"There were failing tests"* and
`BUILD FAILED` with `app/build/outputs/androidTest-results/connected/debug/test-result-exit-code.txt`
= `1` on the fully green run — the documented **D-123** defect, not a test failure.

## 3. NOT Verified

- **Nothing about the Memory tab was checked by eye on the phone.** All four connected tests drive
  Compose and assert on the semantics tree; nobody has looked at the screen. Layout, the 🔒 glyph
  rendering, and scrolling a long Items list are unverified visually.
- **Remove / Erase / Trash / editing do not exist.** They are later slices; no `Storage` method for
  `deleted_at` tombstones exists yet. The tab is read-only by construction — `MemoryScreen` takes no
  callback and emits no action.
- **The other five Memory areas** (Agenda, People & places, Inbox list, Timeline, Learned) and the
  Memory badge are not built. Only the Inbox *count* is shown.
- **The `rowid` tiebreak from E-17 is still unproven as load-bearing** (D-134). Unchanged by this
  session; the RED probe recipe is in §7.
- **`MEMORY_VIEWER_PAGE = 200` is not exercised at its boundary.** No test loads 200+ items; the cap
  is a browse-surface choice, not a proven truncation contract.
- **Rule 6's lock is only reachable on the Items half.** `getActiveSoulFacts` filters
  `local_only IS NULL OR local_only = 0` (`AndroidStorage.kt:461`; fake at `SharedFakeStorage.kt:347`),
  so no local-only soul fact can ever reach `MemoryScreen` — the `🔒` on facts is dead code today and
  `memoryScreen_LocalOnlyRows_CarryTheLockMarker` proves it only through a hand-built `UiState`. The
  Items half is real (`getActiveNodes` does not filter `local_only`). Either rule 6 means "local-only
  rows are never listed" (then the fact lock should be deleted) or it means "they are listed and
  marked" (then `getActiveSoulFacts` must change) — that is a product decision, not made here.

## 4. Current failing tests

None known. `:core:test` 319/0/0/0, `:app:testDebugUnitTest` 13/0/0/0, connected 94/0/0.

## 5. Open decisions needing you

- **Commit row 20 now?** It is one logical change (3 modified + 2 new files, plus docs) and is
  device-green. Proposed message:
  `feat(memory): add read-only Memory tab over the real tables (row 20, D-135)`.
- **Next slice:** row 21 (onboarding, settings catalog, Quick setup) per
  `docs/agent/work/audit-2026-11-08-v1-scope.md` §6, unless you want row 20's editor half first.
- **D-105 by-eye verdict** is still yours (Gentle half captured; see `STATE.md`).
- **Rule 6 for soul facts (see §3).** Today local-only facts are *hidden*, not *listed and locked*.
  Decide which one blueprint 51 §3.1 rule 6 means before the Memory editor slice touches this.

## 6. Traps for the next run

- **A green connected run still prints `> Task :app:connectedDebugAndroidTest FAILED` / "There were
  failing tests" / `BUILD FAILED`** with `test-result-exit-code.txt` = `1`. Read `CONNECTED GATE: …`
  and `TEST-*.xml`, never the task's status (D-123).
- **Never start a RED probe while a background connected run may still be alive.** `run_in_background`
  reports the shell, not the daemon's work. Check
  `powershell -NoProfile -Command "Get-CimInstance Win32_Process | ? { $_.CommandLine -match 'gradle|utp|am instrument|adb logcat' }"`
  first, and `cp file /tmp/file.bak` **before** editing anything for a probe.
- **`core` has no `compileDebugKotlin`.** Use `:core:compileKotlin --offline`.
- **Append tabs, don't insert them.** `when (selectedTab)` is positional and connected tests click
  by title then assume the index.
- **A `LaunchedEffect` keyed on a tab is not one-shot** — `UiState` is a new object every update.
  Guard with a state field the load sets.
- **Recount XML per immediate child directory** of `build/test-results/`; a recursive glob
  double-counts the same suite.

## 7. Exact next step

Commit row 20 as one logical change, then start **row 21** from
`docs/agent/work/audit-2026-11-08-v1-scope.md` §6.

Optional, on an idle machine (no Gradle/UTP/adb processes — see §6): re-attempt the E-17 tiebreak RED
probe.
```
cp app/src/main/java/com/anchor/mine/storage/AndroidStorage.kt /tmp/AndroidStorage.kt.bak
# remove ", rowid DESC" from getRecentMessages only, then:
./gradlew :app:connectedDebugAndroidTest :app:connectedDebugAndroidTestVerify --continue
# expect CONNECTED GATE: FAIL on getRecentMessages_TiedTimestamps_ReturnInWriteOrder; restore the .bak
```
