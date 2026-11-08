# HANDOFF (H-04 + T19 closed with device RED+GREEN; D-105 both halves captured, by-eye verdict outstanding)

## Current Work & Status
- **The device is reachable** — `moto g73 5G - 14` at `10.175.15.5:5555`.
- **#152 H-04** — DONE. RED and GREEN captured on device. See §2.
- **#153 T19** — DONE. RED and GREEN captured on device. See §2.
- **#121 D-105** — **ALARM half** proven by screenshots (Pair C). **GENTLE half CAPTURED 2026-11-08**
  (same-session `dumpsys alarm` + SystemUI + screenshot). The **by-eye verdict is yours** — see §5.1.
- **No commits this session.** The tree is dirty with many pre-existing changes.

## 1. What Changed
### This segment (docs + evidence only; no code)
- `docs/agent/evidence/d105-gentle-pending.png` (521897 B, 1080×2400, dark theme, corner `[10,16,20]`)
  — captured at device clock `07:51:41`, 51 s before the GENTLE alarm's `origWhen`.
- `docs/agent/evidence/d105-gentle-alarms-dump.txt` — the armed GENTLE alarm:
  `tag=*walarm*:com.anchor.mine/.alarms.AlarmReceiver`,
  `type=RTC_WAKEUP origWhen=2026-11-08 07:52:33.449 window=+1m29s998ms repeatInterval=0 count=0 flags=0x20`,
  **no `Alarm clock:` block**. (This dump is a filtered extract — it does not contain the
  `Next alarm clock information` header; the blank-`nextAlarmClock` claim is in
  `d105-evidence-fresh.md`, and the comparable Nov 5 file `d105-alarms-gentletier-pending.txt:188-189`
  shows the blank header for the same tier.)
- `docs/agent/evidence/d105-gentle-systemui.txt` and `d105-gentle-systemui-2.txt` — two samples,
  each with three `StatusBarIconView(slot='alarm_clock' …)` instances (lines 3/30/41 and 3/32/59),
  all reading `visibleState=2` (`HIDDEN`) and `iconAppearAmount=0.0`. Zero `visibleState=ICON`
  anywhere in either file.
- `docs/agent/evidence/d105-gentle-statusbar-crop.png` (x700–1080, y20–100, 3× NEAREST) and
  `d105-gentle-statusbar-left-crop.png` (x0–400, y20–100, 2×) — upscaled crops for your by-eye check.
- `docs/agent/evidence/d105-evidence-fresh.md` — old "blocked on O-1" line softened to
  "(as of 2026-11-06)"; new section `### 2026-11-08 — Gentle half CAPTURED …` with the verbatim dump,
  a three-column table (Gentle armed / nothing armed / ALARM armed), the dark-theme caveat, and a
  machine-checkable verdict line.
- `docs/agent/TASKS.md:30` (T-C) and `:82` (D-105) — Gentle-half clauses updated to "captured".
- `docs/agent/LEARNINGS.md` — new `## 2026-11-08 — D-105 Gentle capture` block (4 bullets).
- `docs/agent/STATE.md:2` and the `UNVERIFIED (from old chat / unproven)` D-105 bullet — updated.

### Earlier this session
- H-04: `app/.../export/AndroidExportManager.kt` — swap block final (md5 `1e69ac9ce4a8cc5f8c835539e58e7031`,
  backup `build/tmp/h04-seam-fixed.kt`): close live → copy live to `$liveDbName.bak` → copy temp over
  live → delete stale `-wal`/`-shm` → re-open and `PRAGMA quick_check` → on any exception restore
  `.bak` and rethrow. Both copies and the rollback go through the constructor seam
  `copyFile: (File, File) -> Unit` (default `src.copyTo(dst, overwrite = true)`). D-129 records why.
- H-04 test: `ExportTest.testImport_SwapFailureRestoresLiveDbFromBak` injects through the seam
  (copy #2 throws), asserts `copies == 3`, the exception surfaced, the live DB is byte-identical, and
  `raw_message` still has 2 rows after reopening.
- T19: `app/.../storage/MineDatabaseHelper.kt` — md5 `afb8d83e46b610302d8bb8aa3fceda95` (backup
  `build/tmp/t19-fixed-backup.kt`): `INDEXES`/`createIndexes(db)` from **both** `onCreate` and
  `onUpgrade`; `FTS_TABLES`; all 13 triggers in `TRIGGER_NAMES` dropped before recreate in
  `createTriggers`.
- T19 test: `DatabaseMigrationTest.testOnUpgrade_RestoresTriggersAndIndexes`.
- Docs: `TASKS.md:149`/`:154` → `[x]` with RED/GREEN lines; `LEARNINGS.md` new section; `DECISIONS.md` D-129.

## 2. What Is Verified
| Claim | Command | Result |
|---|---|---|
| H-04 GREEN | `./gradlew :app:connectedDebugAndroidTest :app:connectedDebugAndroidTestVerify --continue -P…class=com.anchor.mine.export.ExportTest` | `CONNECTED GATE: PASS - 1 test(s), 0 failed, 0 skipped (source: xml)` — `build/tmp/h04-green3.log`, re-confirmed in `build/tmp/h04-t19-green.log` |
| H-04 RED is real | same command with the swap reverted to a single `copyTo` (`H04-RED-PROBE`) | `AssertionError: The failed copy must be followed by exactly one rollback copy expected:<3> but was:<1>` |
| T19 GREEN | `… --continue -P…class=com.anchor.mine.storage.DatabaseMigrationTest` | `CONNECTED GATE: PASS - 2 test(s), 0 failed, 0 skipped (source: xml)` — `build/tmp/t19-green.log`; XML `tests="2" failures="0" errors="0"` |
| T19 RED is real | same command with the `DROP TRIGGER IF EXISTS` loop removed (`T19-RED-PROBE`) | `CONNECTED GATE: FAIL - 2 test(s), 1 failed` — `AssertionError: T19: onUpgrade must replace the stale trigger body (got: … 'stale body that…` — `build/tmp/t19-red.log` |
| Both fixes restored after their RED probes | `md5sum` on both files; `grep -c "RED-PROBE"` | `1e69ac9c…` / `afb8d83e…`; `0` occurrences in each |
| No diagnostic logging left | `grep -rn "H04-DIAG\|T19-RED-PROBE\|H04-RED-PROBE" app/src/` | no matches |
| Full connected suite | `./gradlew :app:connectedDebugAndroidTest :app:connectedDebugAndroidTestVerify --continue` | `CONNECTED GATE: PASS - 85 test(s), 0 failed, 0 skipped (source: xml)` — `build/tmp/full-suite-h04-t19.log`; XML `tests="85" failures="0" errors="0"` |
| D-105 ALARM half — icon appears | Pair C screenshots | `visibleState=ICON` 252 px vs `visibleState=HIDDEN` 197 px, 55 px delta |
| D-105 GENTLE half — alarm armed and icon absent | `d105-gentle-alarms-dump.txt` + `d105-gentle-systemui.txt`/`-2.txt` | `*walarm*` + `window=+1m29s998ms` + `flags=0x20` (dump lines 3-4), `grep -c "Alarm clock:"` = **0**; 6 `slot='alarm_clock'` views across the 2 samples, all `visibleState=2` / `iconAppearAmount=0.0`, `grep -c "visibleState=ICON"` = 0 in both |
| D-105 GENTLE capture run was clean | `./gradlew :app:connectedDebugAndroidTest :app:connectedDebugAndroidTestVerify --continue -P…class=com.anchor.mine.alarms.AlarmSelfTest -P…d105GentleHoldMs=90000` | `CONNECTED GATE: PASS - 3 test(s), 0 failed, 0 skipped (source: xml)` — `build/tmp/d105-gentle-run3.log` |
| Pair C pixel discriminator still reproduces on its own controls | PIL scan `x 600–760, y 30–95, r>=251` on `d105-clean-no-alarm.png` / `d105-clean-alarm-tier.png` | start 742 / count 18 vs start 690 / count 50 — matches the numbers in `d105-evidence-fresh.md` |

## 3. What Is NOT Verified
- **D-105 by-eye verdict — both halves.** I cannot close a `[HUMAN-GATED]` item.
- **The Gentle PNG is not a differential.** It is dark-theme; the Pair C light-glyph scan is
  inapplicable, and there is no same-session dark "nothing armed" control. The Gentle proof is the
  `dumpsys alarm` + SystemUI pair, not the image. A dark-theme threshold (mean ≥ 120) finds glyphs at
  `747-779, 794-831, 890-920, 935-1027` — all ~30 px, the right-hand status-bar icons — with no
  extra 25 px gap-plus-glyph of the kind the alarm icon adds. Consistent, not conclusive.
- **T19 behavioural risk on real opens:** `createTriggers` drops and recreates all 13 triggers on
  **every** `onOpen`. The migration test exercises `onUpgrade` directly, not the drop-then-fail path.
  If a statement between drops and recreates fails, append-only enforcement is absent until next open.
- **H-04 seam residual:** `copyFile` is a production constructor parameter whose only non-default
  value is supplied by tests (D-129). The shipped path is the default value.

## 4. Current Failing Tests
None known. `BUILD FAILED` appears in `t19-green.log` alongside `CONNECTED GATE: PASS` — that is the
AGP 9.4.0 exit-code defect (D-123), not a failure.

## 5. Open Decisions Needing You
1. **D-105 final by-eye verdict is yours.** Preferred pair: `docs/agent/evidence/d105-clean-alarm-tier.png`
   vs `d105-clean-no-alarm.png` (Pair C — the only pair whose "absent" half is provably free of any
   other `setAlarmClock`, including your own deskclock alarms). Then look at
   `d105-gentle-statusbar-crop.png` for the Gentle half.
2. **Commit H-04 + T19 now?** They are device-verified and the full suite is 85/85. Nothing has been
   committed this session.
3. **T19's trigger-drop-on-every-open cost.** Deliberate trade for correctness. If you'd rather pay
   it only on upgrade, say so and I'll gate the drop behind a version comparison.

## 6. Exact Next Step (one command)
```
git status && git diff --stat
```
Then decide §5.2 (commit H-04 + T19). There is no remaining automated work on D-105 — it is waiting
on your eyes (§5.1).

## 7. Traps for the Next Run
- **Never run two connected invocations at once.** The second one dies with
  `java.nio.file.FileSystemException: …logcat-…AlarmSelfTest-testAlarmClock_NextAlarmClockVerification.txt:
  The process cannot access the file because it is being used by another process`, which the gate then
  reports as the phantom `CONNECTED GATE: FAIL - no readable result artifact`. Wait for the first
  invocation to finish; re-run cleanly rather than retrying the overlapping one.
- **A stale XML is not the failing run's result.** When a run dies before UTP writes, the XML on disk
  is the *previous* run's. The failure detail only exists in the gate line / `gate.json`
  (`"tests": 0`, `"source": null`).
- **A filtered extract is not a dump.** `d105-gentle-alarms-dump.txt` was produced by a `grep` and never
  contained the `Next alarm clock information` header; three docs briefly claimed it was "blank" there.
  It was absent. Say which claims a filtered capture can support.
- **A pixel-scan discriminator does not transfer across themes.** The Pair C scan (`r>=251`) is
  meaningless on a dark screenshot. Re-validate any scan on its own controls first.
- **`File.copyTo(overwrite = true)` deletes a directory target recursively; `File.delete()` removes
  an empty directory.** Every file-system injection for H-04 silently succeeded. Use the seam.
- **`copyTo` is a `kotlin.io` extension**, so `override fun copyTo` is `NOTHING_TO_OVERRIDE`.
- **A rollback adds a copy.** backup + failed swap + rollback = 3; `expected:<2>` was the test's bug.
- **Run `connectedDebugAndroidTest` and `…Verify` in the same `--continue` invocation.** Verify alone
  reads the previous run's XML and reports a phantom `FAIL - 1 test(s)`.
- **Comma-separated `class=A#m,B#n` silently selects only the first.** One class per invocation;
  check `tests=` in `app/build/outputs/androidTest-results/connected/debug/*.xml`.
- **`connectedDebugAndroidTest` exits 1 even on a green run (D-123).** Only the gate line counts.
- **The T19 identifier is overloaded.** `TASKS.md:24` is the completed "T19 Release (R8)" smoke test;
  this T19 is the schema-repair one from `robustness-audit.md:43`.
- **A `[x]` can be wrong.** Cross-check a checkbox against the decision line it cites.
- Never leave `-Pandroid.testInstrumentationRunnerArguments.class=…` on a full-suite run.
- `connected/` holds a stale `release/` variant; scope recounts to `connected/debug`.
