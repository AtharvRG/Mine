# TASKS (one at a time, smallest first). Status: [ ] open, [~] in progress, [x] done+reviewed
Specs for R1-R10: docs/agent/work/session1-remediation-spec.md. Read the task's section before starting.
## Blockers
[x] T1 Provider endpoint: saving strips /chat/completions but the transport posts as-is -> 404. Test: save provider, assert request URL.
[x] T2 Import: DB swap runs in finally, so a failed import overwrites live DB. Swap only after validated commit; tampered/truncated file leaves live DB unchanged. Test it.
[x] T3 Reminder id scheme half-migrated (r_<id> vs rem_...). Look up reminders by nodeId everywhere (confirm_change, undo); CREATE-with-time auto-reminder vs schedule_reminder must not double-arm. Tests: chip-cancel removes alarm; undo after reschedule.
[x] T4 Alarm registry treated as truth; force-stop loses alarms; reconcile not run at startup. Run reconcile at start/boot/time change; treat registry as a hint.
[x] T5 Export: hardcoded passphrase "password123", API keys always included, file in cacheDir, no picker. Build passphrase dialog + SAF picker or hide export.
## Important
[x] T6 Receipts: lifecycle, persist, render next to message, show failures; chip-confirmed changes need receipts; stop storing model "Done!" text.
[x] T7 Ambiguity chips show node ids; carry titles and due times.
[x] T8 Mixed commit+error turn hides the error from the user.
[x] T9 getUrgentNodes ORDER BY due_at puts NULLs first; fix + real-DB test.
[x] T10 Loop-limit branch re-inserts botId (PK violation); "Loop limit reached" fires wrongly.
[x] T11 Remove polling coroutine in AndroidAlarmScheduler (while(isActive) delay(100)).
[x] T12 MainActivity opens exact-alarm settings every launch; not needed for setAlarmClock.
[x] T13 Sweeper may log same missed reminder twice; run on start/boot/periodic.
[x] T14 Soul facts: proposed never promoted; add accept/reject path and inject confirmed facts into context.
[x] T15 Stop button in UI; remove dead ScheduleIn action.
[x] T16 ChatScreen: imePadding, auto-scroll.
[x] T17 Chunked AES stream edge case: footer read before final-flag chunk; ChunkedAesInputStream.read can return 0.
## Housekeeping
[x] T18 Real GPLv3 LICENSE text; delete stray file "a"; .idea device IP; update docs/ui-contract.md.
[x] T19 Release (R8) build smoke test; proguard keep rules for Node, Reminder, SoulFact.
[x] Database trigger upgrades: MineDatabaseCallback.onUpgrade only runs createTriggers with CREATE TRIGGER IF NOT EXISTS; updated trigger bodies do not apply to existing databases without DROP TRIGGER IF EXISTS. **Done — this is the schema-repair item tracked as "T19 (schema-repair sense)" at line 155** (distinct from "T19 Release (R8)" at line 24). Device-verified 2026-11-08: RED `CONNECTED GATE: FAIL - 2 test(s), 1 failed` with the DROP loop removed, GREEN `CONNECTED GATE: PASS - 2 test(s), 0 failed, 0 skipped (source: xml)`.
## Backend needed before UI (see UI spec E6)
[x] T20 Storage queries: day page by day_key, heat-map counts, reminder dates per month, Home priority list, structured chat items.
[x] T-A DateResolver 24h + ambiguity chips (D-103)
[x] T-B getActiveNodes paging + ContextBuilder budget + stale->Inbox (D-104)
[x] T-C Alarm tiers: Alarm (setAlarmClock) vs Gentle; tier stored on the reminder; icon disclosure text exposed to UI (D-105) [code + unit-verified; ALARM-tier status-bar icon proven by screenshots (Pair C, 252 px vs 197 px; D-105x 186 px vs 242 px) and **closed by the user by eye 2026-11-08**; Gentle half captured (armed `setAndAllowWhileIdle` alarm + `visibleState=HIDDEN`) — see D-105 at line 82 and `DECISIONS.md:14`]
[x] T-D New edge type follow_up: migration + migration test + blueprint 13 patch + UiAction/Storage support (D-106)
[x] T-E Calendar queries: day exists with messages, reminder dates per month, heat counts per day, reminders for a day (tooltip) [unit-verified only]
[x] T-F Quote slots: table quote_slot(slot, day_key, text, generated_at, state_hash, source=preset|live), preset pool of generic quotes per slot, generation job (see Part 3, Home), setting flags [unit-verified only]

## Remediation Tasks (Session 1 Review)
[x] Architectural Logic Fix 1 (Item 2): Time chip pre-validation & untitled/label payload preservation (D-110, D-112): Gateway missing-title and duplicate checks run before NeedsClarification proposal generation; untitled and label preserved in ProposalPayload and applied on chip confirm [unit-verified]
[x] Architectural Logic Fix 2 (Item 3): RESCHEDULE blank/unusable time fast-fail: Gateway propose_change returns immediate ToolResult.Error("To when?") before candidate resolution or mutation [unit-verified]
[x] Architectural Logic Fix 3 (Item 1): Gentle reminders RTC_WAKEUP & disclosure: AndroidAlarmScheduler uses AlarmManager.RTC_WAKEUP, AlarmReceiver sets CATEGORY_ALARM/CATEGORY_REMINDER, alarmDisclosureText updated with Doze delay disclosure, AlarmSelfTest verifies gentle firing [unit + compile verified]
[x] Architectural Logic Fix 4 (Item 4): Resolver auto-cancel threshold & exact match preference: auto-commit threshold for CANCEL/COMPLETE raised to 0.80 (returns Ambiguous chip below 0.80), exact normalized title match preferred when multiple items contain hint [unit-verified]
[x] R1 Reschedule/cancel/complete after ambiguity (D-110, D-111): DB v5 proposal payload column, ProposalPayload model, RESCHEDULE null time in resolver, destination time in proposal payload, UPDATE/PAUSE/RESUME unsupported error, handleConfirmChange with target check & reminder tier preservation, prompt update, tests [unit + compile verified]
[x] R11 Compound duration parsing, date prompt enforcement, and untitled alarm fallbacks (D-112): parseDuration in DateResolver for multi-unit & seconds expressions, copy user due_text rule in prompt, untitled=true & label handling in Gateway CREATE with formatted fallback title and duplicate-title bypass, reviewer hygiene applied, TDD tests [unit + connected verified]
[x] R2 Candidate selection broken by 100-item default (T-B regression): limit required on getActiveNodes, page all active nodes up to 5000 in resolver, findActiveNodeByTitle for CREATE dedupe, getNodesDueBetween/getOverdueNodes for CheckinBuilder, getRankedCandidates per tier excluding stale in SQL for ContextBuilder, contract tests [unit + connected verified, approved]
[x] R3 Alarm truth, permission and silent failures (D-102, D-114): remove SCHEDULE_EXACT_ALARM from manifest & assertion, AlarmScheduler.schedule returns Armed/Failed and writes reliability/UiState, core AlarmReconciler(force: Boolean), AlarmReceiver Missed prefix on EXTRA_MISSED, fix AlarmSelfTest without early return and test nextAlarmClock, reviewer hardening applied [unit + connected verified on moto g73 5G, approved]
[x] R3b Session 2 Correction, Re-Verification, and Reliability State Hardening: Part 1 sections B, C, D, E fixes; clean-install connected run (31/31 passed on moto g73 5G); evidence files; STATE/HANDOFF honest rewrite; reviewer & blueprint-checker approved [clean-installed on moto g73 5G, approved]
[x] R4 Unreachable features: stale items and time chips (T-A, T-B): UiState.staleItems + KeepStale/CancelStale actions, Gateway uses resolve() and returns NeedsTime with proposal kind="time" and formatted chips, handleConfirmChange for time chips with undo, DateResolver night/12 edge cases and weekday names. [unit + connected verified on moto g73 5G]
[x] R5 Export/import hardening (security): ChunkedAesInputStream chunk length 16..chunkSize+16 check, Argon2 KDF bounds, table & column whitelist from current schema, configurable liveDbName, post-import AlarmReconciler call. [unit + connected verified on moto g73 5G]
[x] R6 Gentle tier is a dead end (T-C): propose_change tier argument (alarm|gentle), CREATE honours it, RESCHEDULE and confirm path preserve existing tier, time-chip payloads carry tier, undo restores tier. [unit + connected verified]
[x] R7 Receipts from operation log (T6): RawEvent causeMsgId, core ReceiptFormatter from raw_event with device time format, Storage.getRecentEvents(limit), UiState.receipts built from events with 24h undoValid and causeMsgId linkage. [unit + connected verified on moto g73 5G]
[x] R8 Quote slots live wiring (T-F, D-108, D-109): slot boundaries (MORNING 5..11, AFTERNOON 12..16, EVENING 17..20, NIGHT 21..4) and prepare hours (4, 11, 16, 20), regen rule (first live is regen 0, max 2 regens with state_hash changed), counts-only prompt, UiState.homeQuote, QuoteManager.onHomeOpened single entry, prepare_next_quote_early setting, rethrow CancellationException. [unit + connected verified on moto g73 5G, approved]
[x] R9 Privacy and injection: <data>...</data> delimiters for tool results and memory with escaping, sanitise titles/statements (collapse newlines, replace |, cap 80 chars), SoulFact/Node localOnly and sensitive fields, sensitive categories forced to proposed/sensitive=1/local_only=1, request capture test. [unit + connected verified on moto g73 5G, approved]
[~] R10 Smaller correctness items (R10g re-verification reopened in current batch):
    [x] R10a Day rollover single source of truth (day_rule effective_from per blueprint 50 s3.13) [unit + connected verified on moto g73 5G, approved]
    [x] R10b Stop during streaming null check before persisting [Interrupted] to raw_message [unit-verified, approved]
    [x] R10c follow_up node existence validation, atomic node+reminder+edge create, CREATE undo soft-deletes edge, AddFollowUp/RemoveFollowUp validation (no self-loop/duplicate, cycle check) with undo receipts [unit-verified, approved]
    [x] R10d getStaleNodes injected now parameter required (no default System.currentTimeMillis()) [unit-verified, approved]
    [x] R10e PresetQuotes Math.floorMod for slot index
    [x] R10f Weekday/vocabulary gaps from P1-B 14 in DateResolver [unit-verified, approved]
    [x] R10g Minified release APK test on physical phone (Nov 5 `r10g-evidence.md` superseded 2026-11-06 by `r10g-fresh-evidence.md` — fresh clean-install run on current code, 4/4 capabilities PASS)
    [x] R10h CheckinBuilder query scaling & stale exclusion: getNodesDueBetween/getOverdueNodes limit=500 cap removal/pagination and exclude stale items consistent with ContextBuilder
    [x] R10i Pinned/severity/priority persistence & getNode reading: wire into Gateway CREATE, getNode SQL query, and undo snapshot equality
    [x] R10j Chat history search indexing: populate msg_chunk_fts on message insert so searchMemoryFts searches conversation history [unit-verified, reviewed; connected-verified 2026-11-06 via testAndroidStorage_MsgChunkFts_SearchAndRedact in the 53/53 clean-install run]
    [x] R10k Prompt context budgeting: add token/char cap on Soul facts and open proposals in ContextBuilder.buildPack
    [x] R10l SharedFakeStorage keyset pagination parity: do not reset to head when cursor ID is absent
[x] R12 Turn lifecycle and confirm-path concurrency (findings A-01, A-04, A-09): atomic proposal CAS in Storage/AndroidStorage/SharedFakeStorage, Gateway handleConfirmChange pre-validation & single-winner CAS, accept paused nodes, terminal failure proposal consumption, ChatController currentBotId interruption isolation [unit-verified, live-endpoint verified, approved]
[x] R12c Corrections only: Items 1-8 complete (Item 1 Rollback fix; Item 2 Atomicity analysis; Item 3 Numbers reconciliation; Item 4 Decisions D-116/D-117/D-102b; Item 5 USE_EXACT_ALARM proof; Item 6 Doc drift & redaction; Item 7 server.js removal; Item 8 Test-auditor escape analysis) [approved]
[x] R12d Corrections: Transactional CAS inside effect & Guarded Rollback: D-118 in-transaction CAS, guarded rollback reopenProposal, strict expiry parity, failing-first revert-proof tests [approved; counts superseded 2026-11-06: 242 core, 9 app, 53 connected clean-install — see evidence-summary.md]

## Current batch (2026-11-06, post-R12d doc-truth + Part B TDD)
[x] A1: Test recount table + raw evidence + clean-install connected run (242 core / 9 app / 53 connected; evidence-summary.md rewritten)
[x] A2: Doc drift (HANDOFF title, STATE, TASKS, ui-contract 5s)
[x] A3: DECISIONS D-102 original text restored + D-117 Rejected correction (committed 5ee5eaa)
[x] A4: test_live_models.py supplementary; test_live_mistral.py deleted (committed d13153a)
[x] A5: D-114..D-117 pack numbering note (committed 5ee5eaa)
[x] B1: ContextBuilder proposal option titles (TDD) [committed 1194119]
[x] B2: Expire/cap transientReceipts (TDD) [impl in 3d42572/a3764f2; test committed d161ba0]
[x] B3: Unify ReliabilityIssue JSON + plain schedulingError (TDD) [committed a3764f2]
[x] B4: Rebuild msg_chunk after import (TDD) [committed bde78fd]
[x] B5: BootReceiver action filter + exported report — BootReceiverIgnoreTest 1/1 PASS on device; exported=true + 4-action filter confirmed from installed APK compiled manifest (aapt2) + device dumpsys registration; evidence b5-evidence.md + b5-manifest-dump.txt + b5-receiver-dump.txt; D-122 recorded [committed d359a7b; DECISIONS portion in 5ee5eaa]
[x] B6: Crash-window test + design sketch (b) — B6CrashWindowTest 2/2 (hard-kill window characterization + reachable near-match dedupe pair proof), core 249/249; evidence b6-crash-window.md + b6-test-run.txt + b6-core-run.txt; option (a)/(b) NOT picked, no DECISIONS.md change
[x] Reviewer + test-auditor reports on Part B changes — reviewer APPROVE (with notes, 0 blocking; 4 IMPORTANT follow-ups: ContextBuilder proposal-budget crowding, BootReceiverIgnoreTest flake hardening, B6 planned-obsolescence discipline, rebuildMsgChunks rule dedup); test-auditor PASS with notes (0 vacuous assertions; top gaps: B5 validActions coverage 1/4, B3 legacy codec branches 0 coverage, B1 budget interaction untested, B4 redact-after-import/re-import untested, B2 LRU/boundary, B6 on-device durability premise); reports partb-reviewer-report.md + partb-test-audit-report.md
[x] R10g: Clean-install minified release APK device run (fresh, on current code) — 4/4 capabilities PASS on build `BUILD SUCCESSFUL in 5m 34s`, clean install `firstInstallTime == lastUpdateTime == 2026-11-06 15:06:40`, `versionName=1.0.0`, no DEBUGGABLE. Evidence: `docs/agent/evidence/r10g-fresh-evidence.md` (supersedes Nov 5 `r10g-evidence.md`). Two defects recorded as observations: O-1 CREATE silently fails for `set alarm ...` phrasing; O-2 provider fields append instead of replace.
[x] D-105: Status-bar alarm icon screenshots (fresh, by-eye user check) — **CLOSED 2026-11-08 by user verdict:** "it neat and cleanly visible that the only difference is the alarm icon....in the armed.png it is visible and in the nothing.png it is not visible" (`d105x-armed-statusbar-crop.png` vs `d105x-nothing-armed-crop.png`). **ALARM half evidenced on a contamination-free Pair C**: `d105-clean-alarm-tier.png` (`visibleState=ICON`, container `678,30-930,90` = 252 px) vs `d105-clean-no-alarm.png` (`visibleState=HIDDEN`, blank `Next alarm clock information`, container `733,30-930,90` = 197 px); 55 px delta reproduced across two sessions (295→240, 252→197). Earlier Pair A/B retained but their "absent" half could not rule out the user's own `com.google.android.deskclock` alarms holding a global `setAlarmClock`. **GENTLE half CAPTURED 2026-11-08** (device `moto g73 5G - 14`, debug build): `AlarmSelfTest.testAlarmClock_NextAlarmClockVerification` with `d105GentleHoldMs=90000` + host `d105-gentle-capture.sh`. Armed GENTLE alarm in `d105-gentle-alarms-dump.txt` (`*walarm*`, `window=+1m29s998ms`, `flags=0x20`, no `Alarm clock:` block, `origWhen` = test's now+120 s; the dump is filtered and does **not** show the `Next alarm clock information` header — that claim is not evidenced by it); SystemUI `slot='alarm_clock'` = `visibleState=HIDDEN` in both samples (`d105-gentle-systemui.txt`, `-2.txt`); screenshot `d105-gentle-pending.png` (dark theme — the Pair C light-glyph scan does not apply). Same-session `AlarmSelfTest` gate `CONNECTED GATE: PASS - 3 test(s), 0 failed, 0 skipped` (`build/tmp/d105-gentle-run3.log`). By-eye crops: `d105-gentle-statusbar-crop.png` (3×, x700–1080), `d105-gentle-statusbar-left-crop.png` (2×, x0–400). Report `d105-evidence-fresh.md`. **NOT marked final — user checks by eye.** **2026-11-08 (later) D-105x:** the user's by-eye report was of the *in-app Reminders screen*, not the status bar (`tiered.png`/`cleanup.png` are `RemindersScreen` frames from `MainActivity.kt:283-321`; neither filename exists in `evidence/`). The self-test icon lives 5 s (`fireAt = now + 5000L`), so a 5 s/10 s/1 min/5 min manual comparison cannot see it. Re-derived in one session with Mine **uninstalled** (`pm list packages | grep -i anchor` empty) using a system `SET_ALARM` `setAlarmClock`, since `slot='alarm_clock'` is app-agnostic: nothing armed `HIDDEN`/`749,30-935,90` = **186 px**; armed `ICON`/`693,30-935,90` = **242 px**; deskclock 2 pm `ICON`/241 px; second `SET_ALARM` 241 px — **third reproduction of the 55-56 px invariant**. Pixel differential: bright-column runs `[(89,234),(317,480),(533,716)]` armed vs `[(317,480),(533,716)]` nothing — the `(89,234)` run is a clock face present only when armed; the two right-hand runs are byte-identical. Files `d105x-*.png` (7). **The user's Gentle observation is confirmed correct** (`visibleState=HIDDEN`, no glyph — Gentle never registers an alarm clock). Does **not** prove the self test specifically (Mine was uninstalled; Pair C-B covers that) and does **not** make the icon a Mine-only signal.
[x] Final: HANDOFF block + 03_CURRENT_TASK.md + commits (committed 0f2c18f — 146 files; HANDOFF.md, 03_CURRENT_TASK.md, STATE.md, TASKS.md, LEARNINGS.md +5, evidence)
[x] D-124: AlarmSelfTest status -1 diagnosis + 20x failure rate — **closed**. 0/20 failures, 0 stale archives, green by XML and proto in all 20; `gradleExit: 1` in all 20 (D-123 reconfirmed). Report `docs/agent/evidence/d124-loop-v2-report.md`; v1 loop marked SUPERSEDED. Receiver change + `reminder_fire` rows recorded as D-124.
[x] #131: AlarmSelfTest ALARM-tier flake — **closed as mitigated, unproven** (D-125). No reproduction exists; the widened window is retained on measured fire-latency grounds only. Reopen only with a real failure carrying `reminder_fire` rows.
[x] Gate gap: `connected-results-gate.py` now falls back to `results_dir/<device>/test-result.pb` when the top-level proto is absent, reading one copy only (D-125). Fixtures: device-only green PASS, device-only failing FAIL, both-copies 3 tests not 6, empty dir FAIL.

## Hardening batch (2026-11-07, audit findings H1-H4)
[x] H3: ContextBuilder drops newest user message at 3000-char budget — newest message always included (`isNewest` bypass); 2 TDD tests in `ContextBuilderTest.kt`; core 267/0.
[x] H2: local_only titles leak via proposal option titles — filter moved into `Storage.getAllActiveNodesPaged` so every caller (incl. the Ambiguous→ProposalOption path) is safe; `H2LocalOnlyLeakTest` 3/3 (leak, resolver non-match, ordinary-node guard).
[x] H4: second missed reminder swallowed by dedupe — `ReliabilityManager.runSweeper` now dedupes on the fire instance (`getReminderFire(...)?.scheduledFor != fireAtMillis`) instead of `(reminderId, outcome)`; reminder ids are `r_<nodeId>`, so a rescheduled node missed twice was silently swallowed. Failing-first at `ReliabilityManagerTest.kt:63`; 2 new tests (distinct miss swept, same instance still deduped).
[x] H1: API key in release BuildConfig — `LIVE_*` fields moved from `defaultConfig` to `buildTypes { debug { … } }`. **R8 elimination proven, not assumed**: a temporary live reference to `BuildConfig.LIVE_API_KEY` put the key in `classes.dex` (1 hit); without it, 0 hits. The previously "clean" APK was an accident of minification. Release `BuildConfig.java` now has no `LIVE_*`; release APK dex scan 0 hits for key/`LIVE_*`/`mistral`; guard test `BuildConfigCredentialScopeTest` (debug half). Evidence: `docs/agent/work/h1-api-key-scope.md`. Residual: key remains in debug artifacts and `local.properties` by design.

## Gate hardening (2026-11-07, discovered while starting #130)
[x] G1: `connectedDebugAndroidTestVerify` failed open — it declared no inputs, so it was UP-TO-DATE
    after its first run and re-printed a stale PASS. Observed: 5 testcases / 1 `<failure>` on disk
    while the task said `UP-TO-DATE` / exit 0 and `gate.json` still held `tests: 3, failures: 0`;
    the same script by hand on the same dir said `FAIL - 5 test(s), 1 failed`. Fix: `inputs.dir(resultsDir)`.
    Failing-first: green fixture PASS → UP-TO-DATE → XML swapped for a failing one → re-runs and FAILS.
    Evidence `docs/agent/work/gate-fail-open.md`; D-127.
[x] G2: Connected-test failures were undiagnosable from their artifacts. Two independent drop points,
    both in `gradle/connected-results-gate.py`: `parse_xml` threw away the `<failure>`/`<error>` body
    (it returned counts and names only), and `parse_proto` never decoded `TestResult.error` (field 3;
    `Error { error_message = 1; error_type = 2; stack_trace = 3 }` per `test-result-proto-1.0.1.jar`).
    So a red run printed `FAILED <name>` and nothing else on **either** route — including the D-123
    route where the XML is truncated and the proto is the only readable copy. Fixed: `parse_xml`
    returns a 6th `failure_details` value; `parse_proto` decodes field 3 and falls back to
    `stack_trace` when `error_message` is blank; the report gains `failureDetails` plus a note
    counting message-less failures; the FAIL branch prints each failing test's message indented, or
    `(no failure message recorded in the <source>)` when UTP genuinely recorded nothing. Fixtures
    moved from `scripts/tmp_gate_fixture.py` into `gradle/test_connected_results_gate.py` (4 cases:
    xml route, proto route, empty body, green) and wired into `:app:check` as
    `connectedGateFixtures` — a gate nobody tests is how the message got dropped. [RED probes, both
    from reverting the fix: (a) message printing reverted → `G2 gate fixtures: 1/4`, the 3 message
    cases fail, stdout shows `FAILED …` with no text — the original defect verbatim; (b) proto
    `Error` parsing disabled → `3/4`, only `proto_failure_message_reaches_stdout_and_report` fails.
    GREEN: `4/4`, `./gradlew :app:connectedGateFixtures` `BUILD SUCCESSFUL`; `:app:check`
    `BUILD SUCCESSFUL in 1m 18s` with `G2 gate fixtures: 4/4 passed` in the log; verdict regression
    `scripts/tmp_gate_fixture.py` `0/4` failures; real artifacts still
    `CONNECTED GATE: PASS - 83 test(s), 0 failed, 0 skipped (source: xml)` and `gate.json` now
    carries `"failureDetails": []`.] **Not proven:** that UTP populates field 3 on a real failure —
    the green proto has no `Error` on any of its 83 results (as the schema says it should not), and
    the original red artifact is gone. The proto route is pinned against a synthetic proto built to
    the schema, not against a captured UTP failure.

## v1 finish (2026-11-07, observations O-1/O-2/O-3 + B5)
[x] O-2: Provider fields appended instead of replaced — root cause was a `key()` on `ProviderScreen`
    remounting it mid-edit; removed, and the screen re-seeds from `state` via `LaunchedEffect`.
    [ProviderScreenTest green; connected-verified]
[x] B5 (export/import UI): `BackupScreen.kt` (SAF pickers + temp-file copy; all flow rules stay in
    `ExportController`), new Backup tab in `MainActivity`, `PassphraseDialog` (internal, uses the
    controller's own `MIN_PASSPHRASE_LENGTH`), suspending `runExport`/`runImport`/`runVerify` +
    `reportFailure` in `ExportController` so the caller never polls `state`. **Defect fixed while
    wiring:** `MineApp` built `AndroidExportManager` with no `scheduler`, so the post-import
    reconcile was skipped and an import left the OLD alarms armed for rows the user no longer had.
    Tests: `ExportControllerTest` 11 (4 new), `BackupScreenTest` 5 on device. [unit + connected verified]

## Titanium hardening (plan: docs/agent/work/titanium-hardening-plan.md)
[x] T-01 `updateNodeAndCommit` UPDATE omitted `type`/`title`/`local_only`; real DB dropped the mutation while the fake replaced the whole object. [RED: `ComparisonFailure: title must persist…`; GREEN on device]
[x] T-02 `undoChangeAndCommit` omitted the same three columns (this is R-04). [RED: `ComparisonFailure: rename must land before undo…`; GREEN on device]
[x] D-03 `getStaleNodes` had no `local_only` filter; the fake did. [RED: `local_only node must not leak through getStaleNodes`; GREEN on device]
[x] R-05 Zero-row UPDATE still wrote a `raw_event` → receipt for a no-op. Now `executeUpdateDelete()` count guard; throw rolls back the event. [RED: `update of a missing node must fail rather than report success`; GREEN on device]
[x] H-01 R-01 Overdue reminders filtered out of reconcile (`AlarmReconciler.kt:44`) — never re-armed, never reported. Now emits `ScheduleResult.Overdue`, cancels the stale registration, dedupes on the fire instance against the sweeper's `missed` row, and surfaces via `UiState.missedReminders` → Reminders screen. [RED: `Unresolved reference 'Overdue'`; then `Unresolved reference 'missedReminders'`; GREEN: core 284/0, app 10/0]
[x] H-02 R-02 Sweeper only ran at boot/app-open, so a miss while the app stayed closed was never reported. Fixed with a repeating inexact alarm (no new dependency): `BootReceiver.ACTION_SWEEP` + `REQUEST_CODE_SWEEP = 9109` + `SWEEP_INTERVAL_MS = 15 min`, armed by `schedulePeriodicSweep()` via `setInexactRepeating(RTC_WAKEUP, now + interval, interval, …)`; `ACTION_SWEEP` added to `onReceive`'s valid actions (so it re-enters the same reconcile → sweeper → notify → quote path) and to the manifest intent-filter; armed from `MineApp.onCreate`'s startup coroutine and re-armed on every boot/app start (idempotent — same PendingIntent replaces the registration). Observation channel is `sweepsArmed`, same reason as `retriesArmed`: a repeating inexact alarm is invisible to the app and `dumpsys alarm` needs the signature DUMP permission. Test `BootReceiverTest.testH02_Sweeper_IsScheduledPeriodically_AndItsBroadcastSweeps`. [RED (real assertion, not a compile error): with `ACTION_SWEEP` removed from the valid-actions list, `tests="3" failures="1"`, `AssertionError: H-02: the periodic sweep broadcast must report the missed reminder expected:<r_h02_…> but was:<null>` at `BootReceiverTest.kt:143`. GREEN on device 2026-11-08: `BootReceiverTest` 3/3, H-02 case 1.249 s; full suite `CONNECTED GATE: PASS - 83 test(s), 0 failed, 0 skipped`]
[x] H-03 R-03 `BootReceiver` has no `catch`; a Keystore failure skips reconcile+sweeper for the whole boot. Fixed: `onReceive`'s coroutine now has `catch (Exception)` → `recordStorageFailure` (re-opens storage, writes `at_risk` + a `Storage unavailable at boot: …` issue) + `scheduleStorageRetry` (inexact `RTC_WAKEUP`, 15 min, `ACTION_STORAGE_RETRY` re-enters the same valid-actions path). Test `BootReceiverTest.testBootReceiver_StorageFails_StillArmsBoundedRetry` (completes `app.sharedStorage` exceptionally, asserts `retriesArmed` +1 and the recorded issue, restores the deferred in `finally`). [RED: not demonstrated — the catch did not exist, so the
 test had nothing to fail against before the fix; the failure path is proven by the test's own
 assertion that `retriesArmed` increments. GREEN on device 2026-11-07: `BootReceiverTest` 2/2 on
 `moto g73 5G - 14`, including `testBootReceiver_StorageFails_StillArmsBoundedRetry` (1.546 s)]
[x] H-04 R-10 Import copied over the live DB with no `.bak`. **The "blocked on user decision (ship now vs defer with B5)" premise was STALE — B5 shipped, so the import path is live code.** Fixed and **device-verified 2026-11-08** on `moto g73 5G - 14`.
    - Fix (`app/.../export/AndroidExportManager.kt:296-348`): the swap is now `close live` → copy live to `$liveDbName.bak` → copy temp over live → delete stale `-wal`/`-shm` → **re-open the swapped DB and `PRAGMA quick_check`** → on any exception restore the `.bak` and rethrow; the `.bak` is deleted only after the integrity check passes. An old `.bak` from a previous interrupted swap is deleted only immediately before a fresh one is taken.
    - Test (`ExportTest.testImport_SwapFailureRestoresLiveDbFromBak`): injects the failure through a **constructor seam** — `AndroidExportManager(copyFile = …)` defaults to `File.copyTo(overwrite = true)`; the test lets the first copy (live → `.bak`) run for real and throws `IOException` on the second (temp → live), the exact crash window the rollback exists for. Asserts exactly 3 copies ran (backup, failed swap, rollback), the exception surfaced, the live DB is **byte-identical** to the pre-import copy, and `SELECT COUNT(*) FROM raw_message` == 2 after reopening.
    - **File-system injection does not work on a real device** (all three tried, all failed to throw): (1) a *directory* at the live path — `copyTo(overwrite = true)` deletes a directory target recursively, so the copy succeeds; (2) a directory at the `.bak` path — `bakFile.delete()` removes an empty directory before the copy; (3) a `File` subclass — `copyTo` is the `kotlin.io` extension, not a member, so `override fun copyTo` is `NOTHING_TO_OVERRIDE`. The audit's prescribed "inject a `File` copy hook" remedy is not implementable as written; the constructor seam is.
    - **The first test written for this task was not RED.** `testImport_FailureLeavesLiveDbByteIdenticalAndNoStaleBak` (duplicate-primary-key injection) **passed on unfixed code** (8/8) because the duplicate-PK `INSERT` aborts inside the *temp* transaction, strictly before the swap. **It was removed** (it proved nothing about the swap and would have read as coverage).
    - [RED: with the swap reverted to a single `copyTo` (`H04-RED-PROBE`), device reported `AssertionError: The failed copy must be followed by exactly one rollback copy expected:<3> but was:<1>` — one copy, no `.bak`, no rollback. GREEN on device 2026-11-08: `CONNECTED GATE: PASS - 1 test(s), 0 failed, 0 skipped (source: xml)` (`build/tmp/h04-green3.log`, re-confirmed in `build/tmp/h04-t19-green.log`)]
[x] T19 (schema-repair sense — distinct from the completed "T19 Release (R8)" at line 24) An upgrade repaired columns but never triggers or indexes. Every trigger was `CREATE TRIGGER IF NOT EXISTS`, so a device that had already created a trigger kept the **old body forever** — editing the trigger SQL never reached an upgraded install; and `idx_reminder_fire` (the index that makes R-26's `ORDER BY fire_at` cheap) was created in `onCreate` only, so a device upgraded from v1 never got it. Fixed and **device-verified 2026-11-08**.
    - Fix (`app/.../storage/MineDatabaseHelper.kt`): the seven indexes moved into `INDEXES` + `createIndexes(db)`, called from **both** `onCreate` and `onUpgrade`; the three FTS tables moved into `FTS_TABLES`; all 13 triggers are listed in `TRIGGER_NAMES` and **dropped before being recreated** in `createTriggers(db)`, which makes that function authoritative on fresh installs and upgrades alike (it already ran from `onOpen`).
    - Test (`DatabaseMigrationTest.testOnUpgrade_RestoresTriggersAndIndexes`): drops `idx_reminder_fire`, replaces `trg_raw_msg_no_delete` with a stale no-op body, runs `MineDatabaseCallback().onUpgrade(db, 1, 5)`, then asserts the index is back, the trigger body is the current one, and a `DELETE FROM raw_message` is actually refused.
    - [RED: with the `DROP TRIGGER IF EXISTS` loop removed (`T19-RED-PROBE`), device reported `CONNECTED GATE: FAIL - 2 test(s), 1 failed` — `AssertionError: T19: onUpgrade must replace the stale trigger body (got: CREATE TRIGGER trg_raw_msg_no_delete BEFORE DELETE ON raw_message BEGIN SELECT RAISE(ABORT, 'stale body that…`. GREEN on device 2026-11-08: `CONNECTED GATE: PASS - 2 test(s), 0 failed, 0 skipped (source: xml)` (`build/tmp/t19-green.log`; the task's own `BUILD FAILED` there is the AGP 9.4.0 exit-code defect, D-123)]
[x] H-05 R-09 `Committed` with empty undo token (`Gateway.kt:472`). A cancel wrote no event, so
    `""` reached `MainActivity`'s `undoToken != null` check and rendered an Undo button whose only
    outcome was "Undo token not found"; the `ConfirmProposal` handler also dropped `Committed`
    entirely, so the confirmation vanished on the next refresh. Now the handler emits the receipt
    with `undoToken.ifBlank { null }` (cancel only — every other choice is already rebuilt from
    `raw_event`, so adding one would duplicate it). [RED: `testChipConfirmedCancel_HasNoUndoAffordance`
    timed out waiting for the receipt that never arrived; GREEN: R7 8/0, core 285/0, app 10/0]
[x] H-06 R-11 `catch (Exception)` misses `Error`; init hangs forever (`MineApp.kt`). Both the
    `MineApp` startup and `MainViewModel` now catch `Throwable` (rethrow `CancellationException`)
    through `runSettlingStartup`/`startupFailureMessage` (`StartupFailure.kt`), so an `Error` still
    settles both deferreds and the view model shows the banner instead of a blank screen.
    [RED: `StartupFailureTest` could not compile against the missing seam; GREEN: app 13/0, core 286/0.
    The hang itself is proven by `withTimeout` around `await()`, not by a poll loop.]
[x] H-07 R-08 `ChatController` init had no try/catch → blank screen. The init body is now
    `loadInitialState()` behind `catch (Throwable)` (rethrows `CancellationException`), which sets
    `globalError` — the full-screen message `MainActivity` already renders — and clears
    `isChatLoading`. [RED: `testStartupFailure_SurfacesErrorInsteadOfBlankScreen` timed out waiting
    for `globalError`; GREEN: R7 9/0, core 286/0, app 10/0]
[x] H-08 R-12 Cancel deletes the row before cancelling the OS alarm. Row-first left an armed
    alarm with no row when `cancel()` threw, and reconcile could never find it: it only cancels an
    orphan whose *registration* is still recorded, and the same pass deleted the registration. The
    reminder then fired for something the user had cancelled and could no longer see. The handler
    now cancels the OS alarm first, in a `try` that rethrows `CancellationException` and otherwise
    reports `schedulingError` and returns — so a failed cancel leaves row + registration intact and
    the next reconcile retries the orphan. [RED: `testCancelReminder_AlarmCancelThrows_KeepsRowAndRegistration`
    failed with "A failed OS cancel must leave the row so the alarm is still reachable" under a
    surgical row-first revert of only this handler; GREEN: core 287/0, app 13/0]
[x] H-10 R-14 `CheckinBuilder` NPEs on a null `dueAt` (`it.dueAt!!`). Both storages filter nulls
    today, so no such row exists — but the builder is the only thing between a null and a crash of
    every context build. An undated row now degrades to a title-only agenda line. [RED:
    `CheckinBuilderNullDueTest` threw a genuine `NullPointerException` (the fake filters nulls, so
    the row must be injected by overriding `getNodesDueBetween`); GREEN: core 288/0]
[x] H-12 R-16 `StreamEvent.Clear` on every failover wipes streamed text. Clear is the signal that
    empties the consumer's buffer (`ChatController.kt:463`, `QuoteManager.kt:211`), so a provider
    that streamed half an answer and then failed made the user watch text they had already read
    disappear before the fallback's first token. The orchestrator now tracks whether the attempt
    streamed any `TextDelta` and emits Clear only when it did not — a failed attempt is not a
    correction of the previous one. [RED: `FailoverPartialTextTest` got
    `[TextDelta(Partial answer), Clear, TextDelta( and the rest)]`; `ProviderTroubleTest`'s old
    "Should clear partial stream" assertion encoded the defect and was narrowed to assert the
    opposite for streamed text; GREEN: core 290/0]
[x] H-16 R-21 `diffDays` by raw millis mislabels across DST — **not a bug, evidence recorded**. The
    audit assumed a 23-hour day between the two midnights on spring-forward. Probed on this JVM
    (17.0.20.1, tzdata bundled): America/New_York 2027-03-13 00:00 EST and 2027-03-14 00:00 EDT are
    exactly 86_400_000 ms apart, cross-checked independently in Python; `formatChipLabel` returns
    "Tomorrow 9:00 AM" and the fall-back case returns "Tomorrow"/"Mon" correctly. Any zone whose
    transition is at midnight would break the arithmetic, but none of the probed zones do, and the
    existing label tests already pin the DST-adjacent output. No code change; the probe test was
    deleted rather than kept as a tautology.
[x] H-14 R-19 The 0.30 text floor returned `NotFound` for a visible item. The floor ran before
    the single-candidate branch, so a hint that overlapped the *only* candidate below 30% came back
    as `Resolution.NotFound`, which the gateway turns into `ToolResult.NotFound` (`Gateway.kt:1064`)
    — the user is told the item does not exist while looking at it. With exactly one candidate
    there is nothing to disambiguate against, so the branch now runs first and returns the chip
    (`Ambiguous(listOf(top))`) for any non-zero overlap; a zero-overlap candidate still gets
    `NotFound`, because an unrelated hint is not evidence for the item. The floor is unchanged for
    the multi-candidate path. [RED: `ResolverSingleLowMatchChipTest` got `NotFound` for hint
    "quarterly report draft review" against sole candidate "Report" (1/4 tokens = 0.25);
    GREEN: core 292/0, including the pin that a zero-overlap sole candidate stays `NotFound`]
[x] H-13 R-15 Token-level confirmation matched negations. `matchesUserMessage` accepted the
    confirmation if *any* user token appeared in the option title or the choice, so "no, don't set
    the 5 pm one" confirmed the "5:00 PM" chip through the shared token "pm" and the reminder was
    created — the opposite of what the user said. `handleConfirmChange` now runs an `isNegated`
    gate *before* the overlap check: contractions are expanded ("don't" → "do not"), then any
    negation token rejects, whatever the overlap. A reply that is exactly the option's own title is
    exempt, so an item named with a negative word stays confirmable. [RED: with the gate removed,
    `ConfirmNegationTest` committed `Created: Dinner with Alice (Due: Wed 28 Oct, 5:00 PM)` for both
    negated replies; GREEN: core 296/0]
[x] H-09 R-13 **PREMISE DISPROVED 2026-11-07.** The defect as stated — "an out-of-bounds index
    throws out of the `LaunchedEffect`" — is not real. Read from the Compose 1.12.1 sources:
    `LazyListScrollPosition.update` only requires `index >= 0` (no upper bound), and
    `LazyLayoutScrollScope.animateScrollToItem` stops with "Hit end without finding the item" when
    `delta != consumed`. An oversized index therefore lands at the bottom instead of throwing.
    Confirmed empirically: injecting the old `animateScrollToItem(totalItems + 5)` into `ChatScreen`
    still PASSED the connected run (2026-11-07, `--rerun-tasks`, 74/74 executed). The clamped
    `layoutInfo.totalItemsCount - 1` scroll is harmless but fixes no defect.
    **H-09b — the real defect, found and fixed 2026-11-07.** `ChatScreen` never scrolled to the
    last item on first load. Root cause measured, not inferred: an instrumented log in the effect
    printed `H09B: effect start total=41 lastIndex=-1`. A `LaunchedEffect` starts after the first
    composition commits but **before** the `LazyColumn`'s first measure, so
    `listState.layoutInfo.totalItemsCount` is 0 on entry; the `if (lastIndex >= 0)` guard then
    silently skipped the scroll and the list stayed at item 0. Fix: await the first non-empty
    layout — `snapshotFlow { listState.layoutInfo.totalItemsCount }.first { it > 0 } - 1` — before
    computing the target (`MainActivity.kt:157-171`). After the fix the same log printed
    `first=22 total=41 visible=[22..40]`.
    **The earlier "hang" record was wrong.** There was never a `ComposeNotIdleException`; all three
    `ChatScrollBoundsTest` cases complete in ~1-2 s each. The prior claim that the 40-message case
    "never reaches idle" is retracted here and in STATE.md/HANDOFF.md.
    [RED: reverting the effect to `layoutInfo.totalItemsCount - 1` makes
    `testScrollLandsOnLastRenderedItem` fail — connected XML `tests="3" failures="1"`,
    `AssertionError: Failed: assertExists`; GREEN: restored, `:app:connectedDebugAndroidTestVerify`
    `CONNECTED GATE: PASS`, XML `tests="3" failures="0" errors="0"`, 2026-11-07]
[x] H-11 R-15 POST_NOTIFICATIONS denial was silent. `UiState.notificationsBlocked` (set by
    `MainViewModel` from `NotificationManagerCompat.areNotificationsEnabled()`, which covers both the
    runtime grant and the system toggle) now renders a persistent error-container banner above the
    tab row in `MineAppUI`, visible on every tab. `MineAppUI` was split into a ViewModel overload
    and a stateless `MineAppUI(state, onAction)` so the shell is testable. [RED: not demonstrated —
    the banner and the field did not exist. GREEN on device 2026-11-07: `NotificationsBlockedBannerTest`
    2/2 passed on `moto g73 5G - 14` (JUnit XML `tests="2" failures="0" errors="0`); core 296/0,
    app unit 13/0]
[x] H-15 R-20 `QuoteAlarmScheduler.scheduleNext` used `set(RTC, …)` — inexact and non-wakeup, so
    under Doze the prepare slot could fire after the boundary it precedes. Now
    `setAndAllowWhileIdle(RTC_WAKEUP, …)` (the app holds `USE_EXACT_ALARM`), and the AlarmManager
    call is wrapped so a `SecurityException`/`IllegalStateException` is logged instead of escaping
    `MineApp`'s startup coroutine or `BootReceiver`. [RED: not demonstrated — the old `set(RTC, …)`
    never registered a wakeup, so there was no failing assertion to write, only an absent
    registration. GREEN on device 2026-11-07, `moto g73 5G - 14`: `adb shell dumpsys alarm` lists
    `tag=*walarm*:com.anchor.mine.quotes.ACTION_PREPARE_QUOTE` / `type=RTC_WAKEUP
    origWhen=2026-11-08 04:00:00.000 flags=0x20` with `idle-options` containing
    `android:broadcast.temporaryAppAllowlistType=1` — the allow-while-idle registration.
    **Doze re-checked the same day:** with `dumpsys deviceidle force-idle` (`mState=IDLE`), the same
    alarm is still listed with `policyWhenElapsed … device_idle=-3s841ms` and the
    `temporaryAppAllowlistType=1` idle-option intact; `dumpsys deviceidle unforce` restored
    `mState=ACTIVE`. NOT proven: that the alarm actually *delivers* while idle — that needs the
    04:00 prepare slot to be observed firing, which a forced-idle session at another hour cannot show.]
[~] R-55 `AlarmReceiver.onReceive` cast `context.applicationContext as MineApp` inside the
    coroutine; a process not started by `MineApp` threw `ClassCastException`, which the catch-all
    turned into a blind "Mine (Fallback)" notification for a reminder nobody could resolve. Now
    `as? MineApp ?: return@launch`. [RED: not demonstrated — no unit seam for a BroadcastReceiver
    and no device; GREEN: `:app:compileDebugKotlin` exit 0, `:app:compileDebugAndroidTestKotlin`
    exit 0, 2026-11-07]
[~] R-54 `AlarmReceiver`'s catch-all fallback posted a notification but left no `reminder_fire`
    row, so the next sweeper saw an unreported instance and emitted a SECOND notification for one
    fire. Two halves:
    (a) the fallback now records `outcome = "errored"` against the reminder's own `fireAtMillis`.
    It cannot use `sharedStorage` (that deferred is what just failed), so it re-opens via
    `DbKeyProvider` + `DatabaseProvider.getHelper` + `AndroidStorage` — the same path
    `BootReceiver.recordStorageFailure` uses. The fire instant comes from `EXTRA_SCHEDULED_FOR`,
    newly added to `AndroidAlarmScheduler.schedule`, `MainViewModel`'s sweeper broadcast and
    `BootReceiver`'s sweeper broadcast (falling back to now when absent).
    (b) `AlarmReconciler` treats an `"errored"` fire for the same `scheduledFor` as already
    reported, alongside `"missed"`.
    [RED: demonstrated 2026-11-07 — removing the `errored` arm from `AlarmReconciler` makes
    `AlarmReconcilerTest.testReconcile_OverdueErroredInstance_IsNotReportedAgain` fail (exactly 1
    failure), then restored. GREEN: that test passes, `AlarmReconcilerTest` 12/0, core 298/0,
    app unit 13/0, `:app:compileDebugAndroidTestKotlin` exit 0.
    **Receiver half (a) is now device-proven, not compile-only.** `MineApp.sharedStorage` is a
    `@Volatile var CompletableDeferred<Storage>`, so an instrumented test can swap in a
    failed deferred — the earlier "no seam" claim was wrong. New
    `AlarmReceiverTest.testAlarmReceiver_StorageFails_RecordsErroredFireAndSweeperDedupes` completes
    `app.sharedStorage` exceptionally, sends `EXTRA_ID` + `EXTRA_SCHEDULED_FOR`, then asserts the
    `errored` fire's `scheduledFor` equals the reminder's own instant, the row survives, and
    `AlarmReconciler.reconcile(force = false)` reports no `Overdue` for it. RED: with the `errored`
    arm deleted the same test fails with `AssertionError: R-54: an errored fire must dedupe the
    overdue report, got [Overdue(reminderId=r_errored_…, overdueByMs=121538)]`. GREEN on device
    2026-11-07: `AlarmReceiverTest` 4/4 on `moto g73 5G - 14`, `CONNECTED GATE: PASS`]
[x] R-23 `searchMemoryFts` swallowed every exception as "no results" (audit cited `AndroidStorage.kt:704`; the catch was actually at `:748` — the finding is real, the line number was stale) [RED/GREEN on device 2026-11-07. `diag_log` existed in the schema (`MineDatabaseHelper.kt:38`) but had **zero writers** anywhere in the repo, so the audit's "log to diag_log" remedy required inventing the write path: new `logDiagError(msg)` wraps the INSERT in `runCatching` so a failing log can never turn a working query into a failing one. Test `AndroidStorageTest.testSearchMemoryFts_QueryFails_IsLoggedNotSilentlyEmpty` asserts the index finds a sentinel **first** (so the later assertion is unambiguously about the failure path), then `DROP TABLE msg_chunk_fts` to force a real SQLite error, then asserts a `tag='AndroidStorage'` `diag_log` row mentioning `searchMemoryFts`. RED: `AssertionError: R-23: an FTS failure must be recorded in diag_log, not swallowed. Got []` at `AndroidStorageTest.kt:1557`. GREEN: `tests="1" failures="0"`]
[x] R-24 `IntentType.valueOf` threw on an unknown stored intent (audit cited `:332,389`; actual call sites `:347` and `:404`) [RED/GREEN on device 2026-11-07. New `parseIntentOrCreate(raw)` degrades an unknown value to `IntentType.CREATE` and records the degrade in `diag_log`, so a downgrade is observable rather than silent. Both read paths (`getProposal`, `getOpenProposals`) route through it. Test `AndroidStorageTest.testProposalRead_UnknownStoredIntent_DoesNotThrow` inserts `intent='TELEPORT'` by raw SQL and asserts both readers return the row. RED probe: temporarily reverting both call sites to `IntentType.valueOf(...)` gave `tests="1" failures="1"`, `java.lang.IllegalArgumentException: No enum constant com.anchor.core.models.IntentType.TELEPORT at AndroidStorage.kt:347`; restored from `/tmp/AndroidStorage.kt.r24` and re-ran → `tests="1" failures="0"`. Note: the first attempt ran the test against already-fixed code and passed, which proves nothing — the RED had to be produced by reverting the fix.]
[x] R-25 `getActiveReminders` read `System.currentTimeMillis()` inside the storage layer instead of taking the caller's clock (audit cited `AndroidStorage.kt:293`; the method was actually at `:308` — the finding is real, the line number was stale) [RED/GREEN on device 2026-11-07. Signature changed to `getActiveReminders(now: Long)` on `Storage`, `AndroidStorage` and `SharedFakeStorage`, following the existing precedent of `getActiveNodeCounts(now, …)` / `getStaleNodes(now, …)` / `getRankedCandidates(now, …)` (a `Long` parameter, not an injected `Clock`). 7 call sites migrated: `ChatController.kt:158,281,762` (pass `clock.now()` — the constructor already held a `Clock`), `AndroidStorageTest.kt:97`, `LiveSmoke15Test.kt:150`, `O1SetAlarmLiveTest.kt:109`, `DuplicateCallGuardTest.kt:60,61`. Test `AndroidStorageTest.testR25_GetActiveReminders_UsesCallerClockNotWallClock` is time-independent by construction: the reminder sits at a fixed `fireAt` and the probes are at `fireAt-1`/`fireAt`/`fireAt+1`, so under the old wall-clock implementation all three see the same `now` and at least one assertion must fail whenever it runs. RED (produced by reverting the fix to `arrayOf(System.currentTimeMillis().toString())`, backup at `/tmp/AndroidStorage.kt.r25`): `AssertionError: R-25: a reminder 1ms in the caller's future is active. Got []` at `AndroidStorageTest.kt:1608`, `tests="1" failures="1"`, `2026-11-07T17:06:51`. GREEN after restoring: `tests="1" failures="0"`, `2026-11-07T17:08:24`. The migration also exposed the defect live in `core`: `DuplicateCallGuardTest.testIdenticalCalls_SecondIsRejectedAsDuplicate` failed (`AssertionError at DuplicateCallGuardTest.kt:53`) when handed `System.currentTimeMillis()` because its `FakeClock` is pinned to 2026-10-25 and the reminder is due "tomorrow at 9am" — the wall clock put it in the past. Fixed by passing `clock.now()`. Full green: unit 311/0 (core 298, app 13), connected 80/0, `CONNECTED GATE: PASS - 80 test(s), 0 failed, 0 skipped (source: xml)`]
[x] R-26 reminder reads had no ceiling: `getPendingReminders` read the whole table, `getActiveReminders` had neither `LIMIT` nor `ORDER BY`, `getRemindersBetween`/`getRemindersForDay` had the range predicate but no `LIMIT` (audit cited `AndroidStorage.kt:305,1016-1032`; the audit row is partly wrong — see the correction row in `docs/agent/work/robustness-audit.md`) [RED/GREEN on device 2026-11-08. Three interface signatures gained a default-parameter bound so every existing call site stayed source-compatible: `getPendingReminders(limit = PENDING_REMINDER_PAGE_LIMIT /*500*/)`, `getActiveReminders(now, limit = ACTIVE_REMINDER_PAGE_LIMIT /*1000*/)`, `getRemindersBetween`/`getRemindersForDay(…, limit = REMINDER_RANGE_PAGE_LIMIT /*1000*/)`. `AndroidStorage` now issues `ORDER BY fire_at ASC LIMIT ?` (pending, active) and `LIMIT ?` (range); `SharedFakeStorage` mirrors all three so the fake cannot return more than the device would. **The defect that mattered was not the SQL:** `AlarmReconciler.reconcile` computed truncation into a *separate* `failures` list that fed only `reliability_state`, so a full page was reported to nobody who reads `results` — a paged reconcile looked clean. Fixed by appending `ScheduleResult.Failed(ISSUE_PENDING_TRUNCATED)` to `results` first and deriving `failures` from `results`, so the two cannot diverge again. The reconciler requests `limit + 1` because a full page alone cannot distinguish "exactly one page" from "more rows exist". Tests: `AlarmReconcilerTest.testReconcile_FullPendingPage_ReportsTruncationInsteadOfSilentlyDroppingTail` / `…PartialPendingPage_DoesNotReportTruncation` (core, 14/0/0/0), `AndroidStorageTest.testR26_GetPendingReminders_IsBoundedInFireOrder_AndByIdStillFindsTheTail` (0.811s) and `AndroidStorageTest.testR26_ActiveAndRangeReminderQueries_AreBoundedInFireOrder` (0.757s) — both confirmed present by name in the connected XML. Page sizes passed explicitly (5/4/3/2) because the defect is the missing bound, not the constant. Full green: core 300/0/0/0 (49 files), app unit 13/0/0/0, `CONNECTED GATE: PASS - 82 test(s), 0 failed, 0 skipped (source: xml)`. Compile trap hit and fixed: `arrayOf(startMs, endMs, limit)` infers `Number & Comparable<*>` and AGP 9.4.0 rejects the reified intersection (`TYPE_INTERSECTION_AS_REIFIED_ERROR`) — `arrayOf<Any>(…)`. **Still open, out of scope:** `idx_reminder_fire` is created in `onCreate` only and never in `onUpgrade`, so devices upgraded from v1 lack the index that makes the new `ORDER BY fire_at` cheap — belongs with T19.]
[ ] Tier 4: R-26…R-53 minus the verified-not-a-bug set (R-17, R-47, R-07, R-22) and R-06 (latent, no caller). R-26, H-02, G2, **H-04** (line 149) and **T19 schema-repair** (line 155) are all done and device-verified 2026-11-08. The accepted batch is exhausted; the next work is the v1-scope gap list in `docs/agent/work/audit-2026-11-08-v1-scope.md` §6, which awaits the user's answer to that report's three questions.
## v1 scope audit (2026-11-08)
[x] V1-G1 v1 row 7, `search_history` half: the tool now exists end-to-end — prompt line in `ChatController.buildJsonPayload`, dispatch branch in `Gateway.executeTool`, `Storage.searchHistory` on the interface, real SQLite implementation in `AndroidStorage`, and the fake in `SharedFakeStorage`. Built on the existing `msg_chunk`/`msg_chunk_fts` tables (one row per message, id `chunk_<msgId>`), so **no schema version bump and no migration** (T19 territory untouched). Blueprint 23 §5 shape: `m4 | Tue 14 Oct 21:10 | U: …`, newest first, limit default 5 capped at 10, blank query is an error. Redaction is defended twice: chunks are deleted on redaction, and the query re-checks `raw_message.content IS NOT NULL AND redacted_at IS NULL`, so a stale chunk cannot leak redacted text. [core unit-verified: `./gradlew :core:test --tests "com.anchor.core.gateway.GatewayTest"` EXIT=0, `tests="13" skipped="0" failures="0" errors="0"` with the three new cases present by name; full `:core:test` EXIT=0, 303 tests / 0 failures / 0 errors / 0 skipped across 49 XML files; `:app:compileDebugAndroidTestKotlin` EXIT=0. **Device half DONE 2026-11-08** (phone returned as `10.175.15.5:5555 device`), and it found a real bug: the first full run was **RED** — `CONNECTED GATE: FAIL - 86 test(s), 1 failed` (`build/tmp/full-suite-search-history.log`), `AssertionError` at `AndroidStorageTest.kt:1384` ("system messages must not be returned"); a probe showed `msg_hist_sys` returned **twice**. Root cause: `msg_chunk_fts` is an external-content FTS5 table (`content='msg_chunk'`), so a correlated subquery on `msg_chunk_fts.rowid` yields NULL for an unchunked message and SQLite resolves that NULL to the content row at the same position. Fixed by joining `raw_message -> msg_chunk (id = 'chunk_' || raw_message.id) -> msg_chunk_fts (rowid = msg_chunk.rowid)`. **GREEN:** `AndroidStorageTest` `CONNECTED GATE: PASS - 40 test(s), 0 failed, 0 skipped (source: xml)` (`build/tmp/hist-fix.log`) and the full suite `CONNECTED GATE: PASS - 86 test(s), 0 failed, 0 skipped (source: xml)` (`build/tmp/full-suite-search-history-fixed.log`); `:core:test` re-run EXIT=0, 303/0/0/0.]
[x] V1-G2 v1 row 7, **alias half** (blueprint 23 §2.2/§4): tool results now carry a short alias instead of a real row id, and the gateway maps it back on the way in. Committed separately as `78efc25`. Implementation: `Gateway.aliasMap` (`ConcurrentHashMap`, one per `Gateway` instance = one per chat controller) + `aliasFor(prefix, id)` emitting `n_<id>`/`s_<id>`/`m_<id>`/`p_<id>`/`r_<id>`/`u_<id>`; `resolveRef(ref, prefix)` maps an issued alias back and passes an unissued alias-shaped ref through unchanged; `MAX_ALIASES = 500` bounds the map. `unknown_ref` is raised at the storage lookup (`Gateway.kt:317,439,459`), not at resolution, because **real ids share the alias prefixes** — proposals are `p_<hex6>`, soul facts are `s_<hex8>`, raw messages are `mN` — so an alias-shaped ref cannot be rejected before the lookup without rejecting real ids. `formatSearchResults` emits aliases for all four arms (`soul_fact`, `chat`, `user`/`assistant`, node). **Deviation from §4 as written:** no `alias_map` table and no schema bump — the map is in memory for the turn that produced the results and is replaced next turn, which is the same lifetime the blueprint's per-turn aliases need. Recorded as D-130. [core unit-verified: `./gradlew :core:test` EXIT=0, `tests=305 skipped=0 failures=0 errors=0`; device-verified: full connected suite `CONNECTED GATE: PASS - 86 test(s), 0 failed, 0 skipped (source: xml)` (`build/tmp/full-suite-aliases.log`). New cases: `testGateway_ScheduleReminder_UnknownAlias_IsUnknownRef`, `testGateway_ConfirmChange_UnknownAlias_IsUnknownRef`, and `testGateway_SearchHistory_…` now asserts the leading token is an issued alias and not the real id (`alias0 == "m3"` refutation).]
[x] V1-G3 v1 row 19 (UI): quick capture — notification reply, launcher shortcut, and the capture-notification switch. Audit rated the row **ABSENT** (`AlarmReceiver.kt` had no `RemoteInput`, no reply action, no `ShortcutManager`). Shipped: `core/.../capture/QuickCapture.kt` (single write path `store()` → `q_<uuid4>` user row; `isUnanswered`/`pending` for the E-17 retry; `isCaptureNotificationEnabled`/`isShortcutEnabled`), `app/.../capture/CaptureNotifications.kt` (`replyAction` with `FLAG_MUTABLE or FLAG_UPDATE_CURRENT` and **request code = notification id**, so two armed reminders never share a PendingIntent; `showSending`/`showFailed` re-post the same id), `app/.../capture/QuickCaptureReceiver.kt` (store **first**, then "Sending…", then the turn — a process killed between any two steps leaves a stored row, never a lost sentence), reply action on **both** the normal and the storage-failure fallback notification in `AlarmReceiver.kt`, `ChatController.sendText(text, userMsgId)` (sends the already-stored `q_` row as the user turn, so nothing is duplicated; reads the provider key from storage because a capture can be the first thing after a cold start), `MainViewModel` retries `QuickCapture.pending()` oldest-first on start, `MineApp.captureController` + `captureNotificationIds` + the `QuickCapture.onCaptureFailed` callback, `shortcuts.xml` + `MainActivity.onNewIntent`/`handleShortcutIntent` (a counter, not a boolean, so two taps both focus; honours `capture.shortcut`). Deviations recorded as **D-132** (`q_` prefix instead of a `raw_message.tags` column) and **D-133** (setting defaults). [unit-verified: `:core:test` EXIT=0, **316/0/0/0** (314 + 2 new `QuickCaptureTest` setting cases); `:app:testDebugUnitTest` 13/0/0/0. **Device-verified 2026-11-08** (`moto g73 5G - 14`): `QuickCaptureDeviceTest` 3/3 — `alarmNotification_CarriesInlineReplyAction` (the audit's stated proof for the row: the posted alarm notification carries a `RemoteInput` action whose `resultKey` is `CaptureNotifications.RESULT_KEY`), `reply_StoresTheTextAsAnUnansweredCapture_Immediately` (a reply broadcast lands a `q_` row in SQLCipher that `QuickCapture.pending` still lists), `launcherShortcut_IsRegistered` (`new_note` present, intent action `ACTION_VIEW`). Full suite `CONNECTED GATE: PASS - 89 test(s), 0 failed, 0 skipped (source: xml)` (`:app:connectedDebugAndroidTestVerify` EXIT=0). **The first device run was RED** — `The reply was never stored within 10000ms`, 5/5 reproductions — and the cause was the **test**, not the receiver: `RemoteInput.getResultsFromIntent` (verified by disassembling `androidx.core:core:1.16.0`) only reads a `ClipData` whose mime is `text/vnd.android.intent` **and** whose label is `android.remoteinput.results`, and the test had built a plain `text/plain` ClipData. Fixed by building the intent with `RemoteInput.addResultsToIntent(...)`, which writes the platform's exact shape. The production receiver was correct throughout — it drops a reply it cannot read rather than storing junk. **Still open, out of scope:** the optional persistent capture notification is not posted (only its off-by-default switch exists); `TAG_QUICK_REPLY` is unused (D-132).]
## Roadmap items to be tackled post-remediation
[x] P1-B 16 UPDATE and RESCHEDULE intents through gateway and resolver, with undo (RESCHEDULE from R1; UPDATE new: title/due/priority/severity/pinned/tier, ambiguity + time chips, reminder sync, undo snapshots) [unit-verified: 231 core, 9 app; review APPROVE + verification PASS; 3 MEDIUM review fixes applied with regression tests]
[x] P1-B 15 Reminder policy per blueprint 30 (lead times, reminder only when asked or per default) (D-120) [unit-verified, approved]
[x] P1-B 17 Dedupe near-match (same title near same due time -> needs_confirm) (D-119) [unit + connected verified on moto g73 5G, approved]
[x] DateResolver: Weekday names ("on friday", "next friday", "friday at 5pm") [covered by R10f, unit-verified, approved]
[x] P1-D 20 Remainder: Home priority list (code-ranked), at-risk flag, active threads, recently-closed with undo validity, structured chat items (bubble, receipt, chips, nudge+reason, check-in snapshot, system line) (D-121) [unit-verified: 242 core, 9 app]
[ ] P1-E 21 day_rule/day_key monotonic, rollover (11, 50 s3.13)
[ ] P1-E 22 Layers: raw/derived/operational, rebuild jobs skeleton (11)
[ ] P1-E 23 FTS5 keyword search: multi-token AND queries, status filter, rank order, limit cap; Embedder interface stub (21) — **audit 2026-11-08:** the `search_history` half of v1 row 7 shipped (see the V1-G1 row below); multi-token AND, status filter, rank order and the Embedder stub are still open, and v1 row 9 (EmbeddingGemma/hybrid retrieval) is ABSENT.
[ ] P1-E 24 Nudge engine wired: triggers, scoring, gates, one open thread, ignore-backoff, hidden fuse, pause, long-silence mode, templates, why/reason_json (31) — **audit 2026-11-08:** `core/.../nudge/NudgeEngine.kt` implements threshold/gap/ignore/reply, but `grep -rn "NudgeEngine" core/src/main app/src/main` outside its own file returns 0 hits — **no production caller**. `NudgeEngineTest` is green against a class nothing constructs, so it is a false-confidence test. Wiring is the whole of v1 row 13's gap.
[ ] P1-E 25 Check-ins: morning/evening/weekly facts blocks, validation against facts, template narration, snapshot cards (32) — **audit 2026-11-08:** only `CheckinBuilder.buildMorningFacts()` is wired (`ContextBuilder.kt:104`). No evening check-in, no post-task review, no weekly review (`grep -rni weekly` → 0 hits), no charts. v1 row 14 is PARTIAL.
[ ] P1-E 26 Learning stats: signal_log, learned_pref, decay, exploration, reset (25)
[ ] P1-E 27 Reliability: score, sweeper heartbeat, latency telemetry, self-test (42)
[ ] P1-E 28 Offline outbox, batching of offline messages, templates, stale-job handling (41)
[ ] P1-E 29 Personas incl. custom, core rules layering (24)
[ ] P1-E 30 Memory viewer backend: edits as user_edit anchors, Trash (30 days), Erase scrub, merge people, inbox union query (51)
[ ] P1-E 31 Settings catalog backend + onboarding_state (52)
[ ] P1-E 32 Context pack full budget behaviour, compact mode (20)
[ ] P1-E 33 Resolver: scale consistent with 22 s3.4, Unicode-aware tokens, stopwords/command words
[ ] P1-E 34 Prompt-injection and privacy audit test (E-15, E-16 in 93)
