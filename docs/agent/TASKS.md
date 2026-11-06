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
[ ] Database trigger upgrades: MineDatabaseCallback.onUpgrade only runs createTriggers with CREATE TRIGGER IF NOT EXISTS; updated trigger bodies do not apply to existing databases without DROP TRIGGER IF EXISTS.
## Backend needed before UI (see UI spec E6)
[x] T20 Storage queries: day page by day_key, heat-map counts, reminder dates per month, Home priority list, structured chat items.
[x] T-A DateResolver 24h + ambiguity chips (D-103)
[x] T-B getActiveNodes paging + ContextBuilder budget + stale->Inbox (D-104)
[x] T-C Alarm tiers: Alarm (setAlarmClock) vs Gentle; tier stored on the reminder; icon disclosure text exposed to UI (D-105) [unit + connected verified, human-gated status bar check]
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
[~] D-105: Status-bar alarm icon screenshots (fresh, by-eye user check) — **ALARM half evidenced on a contamination-free Pair C**: `d105-clean-alarm-tier.png` (`visibleState=ICON`, container `678,30-930,90` = 252 px) vs `d105-clean-no-alarm.png` (`visibleState=HIDDEN`, blank `Next alarm clock information`, container `733,30-930,90` = 197 px); 55 px delta reproduced across two sessions (295→240, 252→197). Earlier Pair A/B retained but their "absent" half could not rule out `com.google.android.deskclock`'s global `setAlarmClock`. **GENTLE half NOT captured and not reachable on this build** (self test is ALARM-only, Gentle instrumentation test window is 1.5 s, chat path blocked by O-1). Report `d105-evidence-fresh.md`. **NOT marked final — user checks by eye.**
[x] Final: HANDOFF block + 03_CURRENT_TASK.md + commits (committed 0f2c18f — 146 files; HANDOFF.md, 03_CURRENT_TASK.md, STATE.md, TASKS.md, LEARNINGS.md +5, evidence)

## Roadmap items to be tackled post-remediation
[x] P1-B 16 UPDATE and RESCHEDULE intents through gateway and resolver, with undo (RESCHEDULE from R1; UPDATE new: title/due/priority/severity/pinned/tier, ambiguity + time chips, reminder sync, undo snapshots) [unit-verified: 231 core, 9 app; review APPROVE + verification PASS; 3 MEDIUM review fixes applied with regression tests]
[x] P1-B 15 Reminder policy per blueprint 30 (lead times, reminder only when asked or per default) (D-120) [unit-verified, approved]
[x] P1-B 17 Dedupe near-match (same title near same due time -> needs_confirm) (D-119) [unit + connected verified on moto g73 5G, approved]
[x] DateResolver: Weekday names ("on friday", "next friday", "friday at 5pm") [covered by R10f, unit-verified, approved]
[x] P1-D 20 Remainder: Home priority list (code-ranked), at-risk flag, active threads, recently-closed with undo validity, structured chat items (bubble, receipt, chips, nudge+reason, check-in snapshot, system line) (D-121) [unit-verified: 242 core, 9 app]
[ ] P1-E 21 day_rule/day_key monotonic, rollover (11, 50 s3.13)
[ ] P1-E 22 Layers: raw/derived/operational, rebuild jobs skeleton (11)
[ ] P1-E 23 FTS5 keyword search: multi-token AND queries, status filter, rank order, limit cap; Embedder interface stub (21)
[ ] P1-E 24 Nudge engine wired: triggers, scoring, gates, one open thread, ignore-backoff, hidden fuse, pause, long-silence mode, templates, why/reason_json (31)
[ ] P1-E 25 Check-ins: morning/evening/weekly facts blocks, validation against facts, template narration, snapshot cards (32)
[ ] P1-E 26 Learning stats: signal_log, learned_pref, decay, exploration, reset (25)
[ ] P1-E 27 Reliability: score, sweeper heartbeat, latency telemetry, self-test (42)
[ ] P1-E 28 Offline outbox, batching of offline messages, templates, stale-job handling (41)
[ ] P1-E 29 Personas incl. custom, core rules layering (24)
[ ] P1-E 30 Memory viewer backend: edits as user_edit anchors, Trash (30 days), Erase scrub, merge people, inbox union query (51)
[ ] P1-E 31 Settings catalog backend + onboarding_state (52)
[ ] P1-E 32 Context pack full budget behaviour, compact mode (20)
[ ] P1-E 33 Resolver: scale consistent with 22 s3.4, Unicode-aware tokens, stopwords/command words
[ ] P1-E 34 Prompt-injection and privacy audit test (E-15, E-16 in 93)
