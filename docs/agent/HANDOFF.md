# HANDOFF (v1 audit §6 item 2 — `NudgeEngine` wired into the proactive path, unit + device verified, uncommitted)

Read this first. Facts only; every claim below has a command and an exit code behind it.

## 1. What changed

**One logical change: audit §6 item 2, "Wire `NudgeEngine` into the proactive path"** (blueprint 31, D-136).
Everything is **uncommitted**. Row 20 (D-135) from the previous session is also still uncommitted.

| File | Status | What |
|---|---|---|
| `core/src/main/java/com/anchor/core/ui/ChatContract.kt` | modified | `UiState.nudgeGate: String?`; `toString()` extended; `UiAction.TickProactive` |
| `core/src/main/java/com/anchor/core/ui/ChatController.kt` | modified | defaulted `nudgeEngine: NudgeEngine = NudgeEngine(clock)`; `TickProactive` branch (`processIgnore` → pick → `evaluate` → write `raw_message` → `onNudgeSent`); `pickNudgeCandidate`, `nudgeText`, `NUDGE_SCAN_PAGE = 200`; `sendText` calls `processReply` when a thread is open |
| `app/src/main/java/com/anchor/mine/MainActivity.kt` | modified | `LaunchedEffect(Unit) { onAction(UiAction.TickProactive) }` — one tick per foreground session |
| `core/src/test/java/com/anchor/core/ui/ProactiveTickTest.kt` | **new** | 7 cases, `NoTransport` throws if the proactive path ever calls the model |

**No storage change and no migration.** `proactive_state`, `nudge_log`, `nudge_pool`, `nudge_hold`
already exist (`MineDatabaseHelper.kt:41-44`); `getProactiveState`/`saveProactiveState` are already
implemented (`AndroidStorage.kt:831,849`).

**Two deviations from the audit's wording, stated plainly:**
- The engine is constructed in **`ChatController`, not `MainViewModel`**. Storage, the clock and
  `UiState` all already live in the controller; the view model only forwards actions.
- Delivery is a **`raw_message` row**, not a `ChatItem.Nudge`. The app renders `state.chatMessages`;
  `ChatItem.Nudge` and `UiState.chatItems` are computed but rendered nowhere, so delivering through
  them would have produced a nudge nobody can see.

Docs updated this session: `DECISIONS.md` (D-136, next free D-137), `TASKS.md` (V1-G5), `STATE.md`
(`Updated:` line + two new counts rows + a build-status bullet), `LEARNINGS.md` (4 entries).

## 2. What is verified

| Claim | Command | Result |
|---|---|---|
| Unit suites green | `./gradlew :core:test :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin --offline` | `BUILD SUCCESSFUL in 20s`, **EXIT=0**; XML recount **core 326/0/0/0**, **app 13/0/0/0** |
| New test actually ran | `core/build/test-results/test/TEST-com.anchor.core.ui.ProactiveTickTest.xml` | `tests="7" failures="0" errors="0" skipped="0"`, all seven names present |
| State advance is load-bearing (probe A) | replace `storage.saveProactiveState(nudgeEngine.onNudgeSent(...))` with a comment, `:core:test --tests ProactiveTickTest` | `7 tests completed, 3 failed`, **EXIT=1**: `tick_OverdueItemAboveThreshold_EmitsANudgeAndOpensAThread` (`:88`), `tick_SecondTickWhileTheThreadIsOpen_HoldsInsteadOfNudgingAgain` (`:105`), `sendText_ClosesTheOpenThreadAndResetsTheBackoff` (`:178`) |
| Reply handling is load-bearing (probe B) | `if (before.openThreadMsgId != null && false)` in `sendText` | `7 tests completed, 1 failed`, **EXIT=1**: exactly `sendText_ClosesTheOpenThreadAndResetsTheBackoff` (`:184`) |
| Restore is clean | `cp /tmp/ChatController.kt.bak … && cmp …` | `BYTE-IDENTICAL`, zero `RED PROBE` markers |
| Full connected suite green | `./gradlew :app:connectedDebugAndroidTest :app:connectedDebugAndroidTestVerify --continue` (no class filter), `moto g73 5G - 14` at `10.175.15.5:5555` | `CONNECTED GATE: PASS - 94 test(s), 0 failed, 0 skipped (source: xml)` (`build/tmp/item2-connected.log`); XML `tests="94" failures="0" errors="0" skipped="0"`, zero `<failure>` nodes |
| Gate alone | `./gradlew :app:connectedDebugAndroidTestVerify` | `BUILD SUCCESSFUL in 6s`, **EXIT=0** |

326 = 319 (row 20) + `ProactiveTickTest` 7. Connected stays **94** — this slice adds no connected test.

The AGP task printed `> Task :app:connectedDebugAndroidTest FAILED` and
`test-result-exit-code.txt` = `1` on the fully green run — the documented **D-123** defect, not a test failure.

## 3. NOT Verified

- **Nobody has seen a nudge on the phone.** No connected test drives `TickProactive` through the real
  app, and no one has looked at the Debug tab's `nudgeGate` value. All proof is core-unit.
- **The tick only runs when the app comes to the foreground.** `LaunchedEffect(Unit)` fires once per
  `MainActivity` composition. There is no background trigger, no WorkManager, no alarm-driven tick in
  this slice, so a user who never opens the app is never nudged.
- **The threshold comparison is vacuous today.** `calculateThreshold` maxes at **30.0**
  (`lastContactAt == now`) and `NudgeCandidate("overdue_item").baseValue` is **35.0**, so the
  comparison in `pickNudgeCandidate` can never hold a nudge. It is wired and pinned at both ends of
  the formula by `tick_ThresholdIsConsulted_NotJustTheGates`, but the gates that actually hold are
  `open_thread`, `min_gap` and the daily fuse. Whether `baseValue` or the formula should move is a
  product decision.
- **`nudge_log`, `nudge_pool` and `nudge_hold` are still schema-only.** This slice writes
  `proactive_state` and a `raw_message`; it does not log a nudge to `nudge_log`, use the pool, or
  honour holds. The audit's "log-and-learn" stage is not implemented.
- **`NudgeCandidate.isCritical` is always `false`** — no candidate is ever classified critical, so
  any critical-specific path is unexercised.
- **`ChatItem.Nudge` / `UiState.chatItems` remain dead on the app side** (D-121 structured timeline).
- **`nudgeGate` is sticky, not live.** Every `_state.copy(...)` in `refreshUI`/`openHome` preserves
  it, so after a `"sent"` tick the Debug tab still reads `"sent"` after the user has answered. It is
  the last tick's verdict, not the engine's current hold reason. No test pins this either way.
- **`sendText`'s `processReply` runs before the `isChatLoading` join**, so two concurrent sends can
  set `lastContactAt` out of order; and its `catch` swallows without `globalError` (the thread then
  stays open and `open_thread` holds the next nudge — visible on the Debug tab). Both accepted in
  D-136, neither tested.
- Carried over unchanged from row 20: **rule 6 for soul facts** (local-only facts are *hidden*, not
  listed and locked); `MEMORY_VIEWER_PAGE = 200` is not exercised at its boundary; the Memory tab was
  never checked by eye.
- Carried over unchanged from E-17: the `rowid` tiebreak is still **not proven load-bearing** (D-134).

## 4. Current failing tests

None known. `:core:test` 326/0/0/0, `:app:testDebugUnitTest` 13/0/0/0, connected 94/0/0.

## 5. Open decisions needing you

- **Commit now?** Two logical changes are sitting uncommitted: row 20 (D-135) and item 2 (D-136).
  They touch overlapping files (`ChatContract.kt`, `ChatController.kt`, `MainActivity.kt`), so they
  cannot be split cleanly by path. Proposed: one commit for both, or tell me to stage item 2's hunks
  separately.
  `feat(core,app): wire NudgeEngine into a foreground proactive tick (audit §6 item 2, D-136)`.
- **Threshold vs base value (see §3).** Today the threshold can never hold a nudge. Do you want
  `baseValue` raised/lowered, the formula changed, or is a vacuous threshold acceptable for v1?
- **Next slice:** audit §6 item 3 (onboarding + soul seeding, rows 4/21) is the next open item;
  proof is *"Instrumented test: fresh DB → onboarding completion writes the seeded soul facts"*.
  Items 6 (offline queue, row 16) and 7 (release signing, row 22 — the release is signed with the
  **debug key** at `app/build.gradle.kts:61`) are still open.
- **D-105 by-eye verdict** is still yours (Gentle half captured; see `STATE.md`).
- **Rule 6 for soul facts** (row 20 §3) is still undecided.

## 6. Traps for the next run

- **A green connected run still prints `> Task :app:connectedDebugAndroidTest FAILED` /
  `BUILD FAILED`** with `test-result-exit-code.txt` = `1`. Read `CONNECTED GATE: …` and `TEST-*.xml`,
  never the task's status (D-123).
- **Never start a RED probe while a background connected run may still be alive.** Check
  `powershell -NoProfile -Command "Get-CimInstance Win32_Process | ? { $_.CommandLine -match 'gradle|utp|am instrument|adb logcat' }"`
  first — note that idle **Gradle and Kotlin daemons match `gradle`** and are harmless; look for
  `utp`, `am instrument` or `adb logcat` specifically. `cp file /tmp/file.bak` **before** editing.
- **After a probe, prove the restore with `cmp`**, not by eyeballing the diff.
- **`core` has no `compileDebugKotlin`.** Use `:core:compileKotlin --offline`.
- **Append tabs, don't insert them.** `when (selectedTab)` is positional and connected tests click
  by title then assume the index.
- **Recount XML per immediate child directory** of `build/test-results/`; a recursive glob
  double-counts the same suite. The connected recount must scope to `connected/debug/` —
  `connected/release/` holds a stale 6-test `ExportTest` XML.
- **Deliver through what the UI renders.** `chatItems` is produced but never drawn; `chatMessages` is
  what the chat list shows.

## 7. Exact next step

Commit row 20 + item 2 (see §5), then start **audit §6 item 3** (onboarding + soul seeding, rows
4/21) from `docs/agent/work/audit-2026-11-08-v1-scope.md` §6.

Optional, on an idle machine (no UTP/adb processes — see §6): the E-17 tiebreak RED probe.
```
cp app/src/main/java/com/anchor/mine/storage/AndroidStorage.kt /tmp/AndroidStorage.kt.bak
# remove ", rowid DESC" from getRecentMessages only, then:
./gradlew :app:connectedDebugAndroidTest :app:connectedDebugAndroidTestVerify --continue
# expect CONNECTED GATE: FAIL on getRecentMessages_TiedTimestamps_ReturnInWriteOrder; restore the .bak
```
