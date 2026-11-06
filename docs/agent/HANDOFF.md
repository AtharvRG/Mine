# HANDOFF (Current batch — R10g + D-105 evidence done; Final commit pending)

## Current Work & Status
- **R10g — DONE.** Clean-install minified release APK run on current code. 4/4 capabilities PASS. Report: `docs/agent/evidence/r10g-fresh-evidence.md` (supersedes Nov 5 `r10g-evidence.md`).
- **D-105 — ALARM half evidenced, GENTLE half blocked, NOT final.** Contamination-free Pair C captured (`d105-clean-alarm-tier.png` vs `d105-clean-no-alarm.png`; 55 px container delta, blank `Next alarm clock information` in the absent half). The Gentle half cannot be produced on this build (`Run 5-sec Self Test` is ALARM-only, the Gentle instrumentation test uses a 1.5 s window, and the chat path fails silently via O-1). Report: `docs/agent/evidence/d105-evidence-fresh.md`. **User must check the PNGs by eye**; task stays `[~]` until they do.
- **Final — in progress.** HANDOFF block + `03_CURRENT_TASK.md` + commits.

## 1. What Changed (files and commits)
**New evidence files (uncommitted, this session):**
- `docs/agent/evidence/r10g-fresh-evidence.md` — R10g report, 4/4 capabilities with file/byte citations.
- `docs/agent/evidence/d105-evidence-fresh.md` — D-105 report, alarm-tier vs no-alarm SystemUI pair.
- `docs/agent/evidence/d105-fresh-alarm-tier.png` (111531 B, 16:00) — alarm pending, icon present.
- `docs/agent/evidence/d105-fresh-no-alarm.png` (98694 B, 16:01) — alarm consumed, icon absent.
- `docs/agent/evidence/d105-fresh-systemui-alarm.txt` — SystemUI dump while armed.
- `docs/agent/evidence/d105-fresh-systemui-gentle.txt` — SystemUI dump after fire.
- Plus the large untracked `r10g-*` / `d105-*` raw capture set from this and the prior session (XML dumps, PNGs, alarm dumps).

**Docs updated (uncommitted):**
- `docs/agent/TASKS.md` — R10g `[x]`, D-105 `[~]` with evidence pointers.
- `docs/agent/STATE.md` — O-1 and O-2 added under "Known broken"; D-105 by-eye line under UNVERIFIED.
- `docs/agent/LEARNINGS.md` — 5 new entries (SystemUI dump location, `exec-out screencap`, stable icon fields, silent release build, exit-code citation rule).
- `docs/agent/HANDOFF.md` — this file.
- `03_CURRENT_TASK.md` — rewritten for this batch.

**No source code changed this session.**

## 2. What Is Verified (command + exit code)
- Build: `BUILD SUCCESSFUL in 5m 34s` (`docs/agent/evidence/r10g-build-release.txt`).
- Install: `Performing Streamed Install` / `Success` (`r10g-install.txt`).
- Provenance: `firstInstallTime == lastUpdateTime == 2026-11-06 15:06:40`, `versionName=1.0.0`, `versionCode=10000`, flags `[ HAS_CODE ALLOW_CLEAR_USER_DATA ]` (no DEBUGGABLE), `initiatingPackageName=com.android.shell` (`r10g-fresh-package.txt`).
- Storage opens: `r10g-fresh-01-storage-opened.png`.
- Message sent: live LLM reply in `r10g-reply.xml` via saved Mistral config (`r10g-chat-verified.xml`).
- Reminder fires: armed `setAlarmClock` (`window=0 flags=0x3`, `Alarm clock:` block) in `r10g-alarm-armed.txt`; notification `importance=4 channel=mine_reminders_v3 category=alarm` in `r10g-notification-fired.txt`; shade `r10g-notification-shade.xml`.
- Export verifies: `tests 7 failures 0 errors 0` (`r10g-export-test.txt`), gradle exit 1 = ROM teardown artifact.
- D-105 alarm tier: `slot='alarm_clock' visibleState=ICON iconAppearAmount=1.0`, holder `visible=true`, container `640,30-935,90`, `Next alarm clock information: ... time:1793961054005 = +3s226ms`.
- D-105 no alarm: `visibleState=HIDDEN iconAppearAmount=0.0`, holder `visible=false`, container `695,30-935,90`, `Next alarm clock information:` blank.

## 3. What Is NOT Verified
- **D-105 by-eye verdict** — user has not looked at the two PNGs. Do not mark final.
- **O-1 root cause** — the release build logs nothing; the CREATE defect is characterised from UI + alarm state only.
- **O-2 scope** — only one reproduction captured.
- Core/app/connected test suites were NOT re-run this session (no source changed).

## 4. Current Failing Tests
- None known. No test suite was executed this session; last recorded green state: 247 core / 9 app / 53 connected (Part B), export 7/7 on device.

## 5. Open Decisions Needing User
- **D-105:** confirm by eye — `d105-fresh-alarm-tier.png` vs `d105-fresh-no-alarm.png`.
- **O-1 triage:** should the `set alarm ...` CREATE failure block release? It is user-visible and silent.
- Confirm D-117 Rejected-line correction (carried over from Part B).
- Test-count discrepancy to reconcile: TASKS line 66 says "242 core, 9 app, 53 connected", the Part B HANDOFF said 247 core / 309 total, Part B reviewer reported 249/249. Needs one recount run before the next evidence summary.

## 6. Exact Next Step
- Commit the evidence + docs (see §8), then ask the user to check `d105-fresh-alarm-tier.png` and `d105-fresh-no-alarm.png` by eye.
- After that: triage O-1 — reproduce `set alarm for drink water in 3 minutes` on a **debug** build and capture logcat, since the release build cannot be traced.

## 7. Traps for the Next Run
- **SystemUI icon state is NOT in `dumpsys window`.** Use `dumpsys activity service com.android.systemui/.SystemUIService` and grep `StatusBarIconControllerImpl` / `StatusBarIconList`.
- **`adb shell screencap -p /sdcard/x.png` fails under Git-Bash** (path mangling → usage error). Use `adb exec-out screencap -p > local.png`.
- **The self-test alarm lives ~5 s.** Arm it (`Reminders` tab at `405 177`, then `Run 5-sec Self Test` at `267 337`) and capture the dump + screenshot in the SAME command chain; a separate round trip loses the window.
- **Icon alpha is animated** — rely on integer `visibleState` and the holder's `visible=`, plus the 55 px container delta.
- **Minified release build is silent** — `run-as` fails (`package not debuggable`), `logcat -s "Mine:*"` returns 0 lines. Root-causing anything found on it requires a debug build.
- **`connectedAndroidTest` exits 1 even when green** — always cite the parsed XML counts next to the exit code.
- **`input keyevent 4` on the Reminders tab exits to the launcher** — return with `am start -n com.anchor.mine/.MainActivity`.
- **`adb shell input text` mangles multi-`%s` strings** — use exactly ONE `%s` (which becomes a space) and literal spaces elsewhere (`remind%sme%to%stretch%sin%2%minutes`); multiple `%s` merge the text. Verify field text via a dump before tapping Send.
- **`slot='alarm_clock'` is app-agnostic** — `com.google.android.deskclock` holding a `setAlarmClock` keeps the icon lit with zero Mine alarms. An "absent" capture is only valid when `Next alarm clock information:` is blank AND the holder reads `visible=false`. `cmd alarm` has no `cancel`; advance the clock with `cmd alarm set-time <epoch_ms>` instead.
- **The 55 px `StatusIconContainer` delta is the invariant, not the absolute bounds** — the left edge drifts with other status-bar icons (observed 295→240 and 252→197). Glyph polarity is LIGHT: scan for `r >= 251`, not dark pixels.
- **AGP uninstalls both APKs after every connected run** — capture device dumps before the run or reinstall first.

## 8. Commit Plan
One commit for the batch:
```
git add docs/agent/evidence/ docs/agent/TASKS.md docs/agent/STATE.md docs/agent/LEARNINGS.md docs/agent/HANDOFF.md 03_CURRENT_TASK.md
git commit -s -m "docs(evidence): fresh R10g clean-install run and D-105 status-bar pair; record O-1/O-2 defects"
```
`docs/agent/evidence/` includes ~140 untracked capture files — intended, they are the raw provenance for both reports.

## Operating Rules
- Commit per task using `git commit -s`. Never commit failing tests. One task per run; stop and await user approval before proceeding to the next task.
