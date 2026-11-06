# HANDOFF (Part B TDD — B1..B5 ALL COMMITTED; Next: B6 Crash-Window Test)

## Current Work & Status
- Batch A (doc truth): **done and committed** — A1 test recount (raw evidence in `docs/agent/evidence/`, committed 2a2e9b1), A2 doc drift (d13153a), A3 D-102 restore + D-117 Rejected correction (5ee5eaa), A4 `test_live_models.py` supplementary (d13153a, deleted `test_live_mistral.py`), A5 D-114..D-117 pack-numbering note (5ee5eaa).
- Batch B (TDD): **B1..B5 all committed** — B1 `1194119` (ContextBuilder proposal option titles + test), B2 impl `3d42572`/`a3764f2` + test `d161ba0`, B3 `a3764f2`, B4 `bde78fd`, B5 `d359a7b` (BootReceiverIgnoreTest + evidence) with D-122 in `5ee5eaa`.
- Core test count is now **247** (242 + B1x1 + B2x1 + B3x3); forced-fresh run evidence `core-test-20261106-partb.txt` (UNIT_EXIT=0). Total project tests 309 (247 core / 9 app / 53 connected).
- B5 evidence resolution: this ROM's `dumpsys package` does not print per-component `exported=` ActivityInfo lines (only resolver tables), so after reinstalling the debug APK the exported flag was taken from `aapt2 dump xmltree` of the installed APK — `BootReceiver exported=true` with 4-action filter (BOOT_COMPLETED, MY_PACKAGE_REPLACED, TIME_SET, TIMEZONE_CHANGED). Device dumpsys confirms registration under all 4 actions + RECEIVE_BOOT_COMPLETED granted. Evidence: `docs/agent/evidence/b5-evidence.md`, `b5-manifest-dump.txt`, `b5-receiver-dump.txt`.

## 1. What Changed (files and commits)
**Committed this session:**
- `1194119` feat(context): include proposal option titles and due times in pending clarifications (B1) — `ContextBuilder.kt` + `ContextBuilderTest.kt`.
- `d161ba0` test(receipts): cap transientReceipts at 20 and expire after 24h (B2 test; impl already in 3d42572/a3764f2) — `R7ReceiptsTest.kt`.
- `d359a7b` test(alarms): verify BootReceiver action filter and exported flag on device (B5) — `BootReceiverIgnoreTest.kt` + `b5-evidence.md` + `b5-manifest-dump.txt` + `b5-receiver-dump.txt`.
- `d13153a` docs(scripts): re-sync live smoke prompt to P1-B 16 and mark it supplementary (A4) — `test_live_models.py`, deleted `test_live_mistral.py`, `ui-contract.md` 10s->5s (A2).
- `5ee5eaa` docs(decisions): restore D-102 text, correct D-117 Rejected line, add pack-numbering note and D-122 (A3/A5/B5) — `DECISIONS.md`.
- `2a2e9b1` docs(evidence): raw test evidence for A1 recount and Part B suites — 7 evidence txt files incl. new `core-test-20261106-partb.txt`.
- (Earlier this batch: `bde78fd` B4, `a3764f2` B3, `2175013` P1-D 20, `3d42572` P1-B 15/16/17, `a5b5838` verification docs.)

**Uncommitted working tree:** docs only (STATE/TASKS/HANDOFF/LEARNINGS/evidence-summary refresh reflecting the commits above).

## 2. What Is Verified (command + exit code)
- `BootReceiverIgnoreTest` on device: gradle exit 1 (ROM teardown artifact) but JUnit XML = 1 test, 0 failures, 2.249s — **PASS** (`app/build/outputs/androidTest-results/connected/debug/TEST-moto g73 5G - 14.xml`).
- `adb install -r app/build/outputs/apk/debug/app-debug.apk` → Success (installed 12:57:20).
- `adb shell dumpsys package com.anchor.mine > docs/agent/evidence/b5-receiver-dump.txt` → 8652 bytes; BootReceiver registered under all 4 actions (filter cc5b617), RECEIVE_BOOT_COMPLETED granted=true.
- `aapt2 dump xmltree --file AndroidManifest.xml app/build/outputs/apk/debug/app-debug.apk > docs/agent/evidence/b5-manifest-dump.txt` → BootReceiver `exported=true`, 4-action filter; AlarmReceiver/QuoteAlarmReceiver `exported=false`.
- `cmd package query-receivers -a android.intent.action.BOOT_COMPLETED` → `com.anchor.mine/.alarms.BootReceiver` listed (Receiver #231).
- Post-commit core suite: `./gradlew :core:cleanTest :core:test` → BUILD SUCCESSFUL, `UNIT_EXIT=0`, XML **247 tests / 0 failures** (evidence `core-test-20261106-partb.txt`).
- B4: `ExportTest` 6/6 on device (committed, verified in the 53/53 clean-install run + B4 commit).
- B3: `B3ReliabilityIssuesTest` 3/3 in core suite (committed; evidence `docs/agent/evidence/b3-core-suite.txt`).
- Clean-install connected suite (A1): 53/53, 0 failures in JUnit XML; gradle exit 1 = documented ROM teardown artifact (STATE.md Test counts section).

## 3. What Is NOT Verified
- No independent reviewer/test-auditor pass on B1-B5 yet (task #119 pending).
- R10g fresh re-run on current code not done (Nov 5 evidence pre-dates D-118/P1-B/P1-D changes) — task #120.
- D-105 fresh by-eye/screenshots on current build not done — task #121.
- Connected suite has not been re-run since the B1 code change (last connected evidence: 53/53 clean-install + BootReceiverIgnoreTest 1/1, both 2026-11-06, pre-B1-commit build; B1 is prompt-text-only, compile-verified, unit-verified).

## 4. Current Failing Tests
- None. Core 247/247, app 9/9, connected 53/53 (clean-install) + BootReceiverIgnoreTest 1/1 all green per XML.

## 5. Open Decisions Needing User
- None new. D-122 recorded (B5); next free D-123. (A3's D-117 Rejected-line correction notes "decision text unchanged pending developer confirmation" — confirm when convenient.)

## 6. Exact Next Step
- Task **#118 (B6)**: crash-window test + design sketch (option b). See TASKS.md "Current batch" — write the failing test for the crash/kill window (turn interrupted mid-flight), then the design sketch for recovery. Await user approval before starting (one task per run).

## 7. Traps for the Next Run
- **AGP uninstalls both APKs after every connected run.** Any post-run adb inspection of `com.anchor.mine` fails with "Unable to find package" until you reinstall the APK. Capture dumps BEFORE the run, or reinstall first. `adb shell dumpsys package` on an uninstalled package prints the error to stdout with exit 0 — piping to a file silently captures 41 bytes of error text (lost the dump twice this way).
- **Android 14 ROM's `dumpsys package` does NOT print per-component `exported=` ActivityInfo lines** (only resolver tables). For exported-flag evidence use `aapt2 dump xmltree --file AndroidManifest.xml <apk>` on the installed APK — the compiled manifest is authoritative and shows `A: android:exported=true/false` per component.
- `cmd package query-receivers -a <ACTION>` lists ALL packages' receivers (301 for BOOT_COMPLETED); grep for `anchor` — but it returns nothing for an uninstalled package, which is not proof of absence.
- The receiver dump for BootReceiver lives under "Receiver Resolver Table" -> "Non-Data Actions" in `dumpsys package` output.
- B1 is now committed (1194119): `PromptSanitizer.sanitize(opt.title, 80)` — proposal option titles come from `ProposalOption(duplicate.id, "Update existing", dueStr)`; dueStr is already device-formatted, sanitize is for length/newlines only.
- `R7ReceiptsTest` has LF/CRLF warnings on commit — normal for this repo on Windows; `git add` handles it.
- Core test count moved 242 -> 247 with the Part-B TDD tests; docs (STATE header, Test counts table, evidence-summary ground-truth table) were refreshed to 247/309 as part of the docs commit.
- Connected gradle runs exit 1 even when all tests pass (ROM instrumentation teardown artifact, documented in STATE.md Test counts) — judge pass/fail ONLY from the JUnit XML, never from the gradle exit code.
- `docs/agent/evidence/evidence-summary.md` was rewritten (A1); keep it as the single index for all evidence files.

## Operating Rules
- Commit per task using `git commit -s`. Never commit failing tests. One task per run; stop and await user approval before proceeding to the next task.
