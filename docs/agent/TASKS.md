# TASKS (one at a time, smallest first). Status: [ ] open, [~] in progress, [x] done+reviewed
Specs for R1-R10: docs/agent/work/session1-remediation-spec.md. Read the task's section before starting.
## Blockers
[x] T1 Provider endpoint: saving strips /chat/completions but the transport posts as-is -> 404. Test: save provider, assert request URL.
[x] T2 Import: DB swap runs in finally, so a failed import overwrites live DB. Swap only after validated commit; tampered/truncated file leaves live DB unchanged. Test it.
[x] T3 Reminder id scheme half-migrated (r_<id> vs rem_...). Look up reminders by nodeId everywhere (confirm_change, undo); CREATE-with-time auto-reminder vs schedule_reminder must not double-arm. Tests: chip-cancel removes alarm; undo after reschedule.
[x] T4 Alarm registry treated as truth; force-stop loses alarms; reconcile not run at startup. Run reconcile at start/boot/time change; treat registry as a hint.
[x] T5 Export: hardcoded passphrase "password123", API keys always included, file in cacheDir, no picker. Build passphrase dialog + SAF picker or hide export.
## Important
[~] T6 Receipts: lifecycle, persist, render next to message, show failures; chip-confirmed changes need receipts; stop storing model "Done!" text.
[x] T7 Ambiguity chips show node ids; carry titles and due times.
[x] T8 Mixed commit+error turn hides the error from the user.
[x] T9 getUrgentNodes ORDER BY due_at puts NULLs first; fix + real-DB test.
[x] T10 Loop-limit branch re-inserts botId (PK violation); "Loop limit reached" fires wrongly.
[x] T11 Remove polling coroutine in AndroidAlarmScheduler (while(isActive) delay(100)).
[x] T12 MainActivity opens exact-alarm settings every launch; not needed for setAlarmClock.
[~] T13 Sweeper may log same missed reminder twice; run on start/boot/periodic.
[x] T14 Soul facts: proposed never promoted; add accept/reject path and inject confirmed facts into context.
[x] T15 Stop button in UI; remove dead ScheduleIn action.
[x] T16 ChatScreen: imePadding, auto-scroll.
[x] T17 Chunked AES stream edge case: footer read before final-flag chunk; ChunkedAesInputStream.read can return 0.
## Housekeeping
[x] T18 Real GPLv3 LICENSE text; delete stray file "a"; .idea device IP; update docs/ui-contract.md.
[x] T19 Release (R8) build smoke test; proguard keep rules for Node, Reminder, SoulFact.
[ ] Database trigger upgrades: MineDatabaseCallback.onUpgrade only runs createTriggers with CREATE TRIGGER IF NOT EXISTS; updated trigger bodies do not apply to existing databases without DROP TRIGGER IF EXISTS.
## Backend needed before UI (see UI spec E6)
[~] T20 Storage queries: day page by day_key, heat-map counts, reminder dates per month, Home priority list, structured chat items.
[~] T-A DateResolver 24h + ambiguity chips (D-103)
[~] T-B getActiveNodes paging + ContextBuilder budget + stale->Inbox (D-104)
[~] T-C Alarm tiers: Alarm (setAlarmClock) vs Gentle; tier stored on the reminder; icon disclosure text exposed to UI (D-105 - HUMAN GATED)
[~] T-D New edge type follow_up: migration + migration test + blueprint 13 patch + UiAction/Storage support (D-106)
[x] T-E Calendar queries: day exists with messages, reminder dates per month, heat counts per day, reminders for a day (tooltip) [unit-verified only]
[x] T-F Quote slots: table quote_slot(slot, day_key, text, generated_at, state_hash, source=preset|live), preset pool of generic quotes per slot, generation job (see Part 3, Home), setting flags [unit-verified only]

## Remediation Tasks (Session 1 Review)
[x] R1 Reschedule/cancel/complete after ambiguity (D-110, D-111): DB v5 proposal payload column, ProposalPayload model, RESCHEDULE null time in resolver, destination time in proposal payload, UPDATE/PAUSE/RESUME unsupported error, handleConfirmChange with target check & reminder tier preservation, prompt update, tests [unit + compile verified]
[x] R11 Compound duration parsing, date prompt enforcement, and untitled alarm fallbacks (D-112): parseDuration in DateResolver for multi-unit & seconds expressions, copy user due_text rule in prompt, untitled=true & label handling in Gateway CREATE with formatted fallback title and duplicate-title bypass, reviewer hygiene applied, TDD tests [unit + connected verified]
[x] R2 Candidate selection broken by 100-item default (T-B regression): limit required on getActiveNodes, page all active nodes up to 5000 in resolver, findActiveNodeByTitle for CREATE dedupe, getNodesDueBetween/getOverdueNodes for CheckinBuilder, getRankedCandidates per tier excluding stale in SQL for ContextBuilder, contract tests [unit + connected verified, approved]
[x] R3 Alarm truth, permission and silent failures (D-102, D-114): remove SCHEDULE_EXACT_ALARM from manifest & assertion, AlarmScheduler.schedule returns Armed/Failed and writes reliability/UiState, core AlarmReconciler(force: Boolean), AlarmReceiver Missed prefix on EXTRA_MISSED, fix AlarmSelfTest without early return and test nextAlarmClock, reviewer hardening applied [unit + connected verified on moto g73 5G, approved]
[x] R3b Session 2 Correction, Re-Verification, and Reliability State Hardening: Part 1 sections B, C, D, E fixes; clean-install connected run (31/31 passed on moto g73 5G); evidence files; STATE/HANDOFF honest rewrite; reviewer & blueprint-checker approved [clean-installed on moto g73 5G, approved]
[ ] R4 Unreachable features: stale items and time chips (T-A, T-B): UiState.staleItems + KeepStale/CancelStale actions, Gateway uses resolve() and returns NeedsTime with proposal kind="time" and formatted chips, handleConfirmChange for time chips with undo, DateResolver night/12 edge cases and weekday names.
[ ] R5 Export/import hardening (security): ChunkedAesInputStream chunk length 16..chunkSize+16 check, Argon2 KDF bounds, table & column whitelist from current schema, configurable liveDbName, post-import AlarmReconciler call.
[ ] R6 Gentle tier is a dead end (T-C): propose_change tier argument (alarm|gentle), CREATE honours it, RESCHEDULE and confirm path preserve existing tier, time-chip payloads carry tier, undo restores tier.
[ ] R7 Receipts from operation log (T6): RawEvent causeMsgId, core ReceiptFormatter from raw_event with device time format, Storage.getRecentEvents(limit), UiState.receipts built from events with 24h undoValid and causeMsgId linkage.
[ ] R8 Quote slots live wiring (T-F, D-108, D-109): slot boundaries (MORNING 5..11, AFTERNOON 12..16, EVENING 17..20, NIGHT 21..4) and prepare hours (4, 11, 16, 20), regen rule (first live is regen 0, max 2 regens with state_hash changed), counts-only prompt, UiState.homeQuote, QuoteManager.onHomeOpened single entry, prepare_next_quote_early setting, rethrow CancellationException.
[ ] R9 Privacy and injection: <data>...</data> delimiters for tool results and memory with escaping, sanitise titles/statements (collapse newlines, replace |, cap 80 chars), SoulFact/Node localOnly and sensitive fields, sensitive categories forced to proposed/sensitive=1/local_only=1, request capture test.
[ ] R10 Smaller correctness items:
    [ ] R10a Day rollover single source of truth (day_rule effective_from per blueprint 50 s3.13)
    [ ] R10b Stop during streaming null check before persisting [Interrupted] to raw_message
    [ ] R10c follow_up node existence validation, atomic node+reminder+edge create, CREATE undo soft-deletes edge, AddFollowUp/RemoveFollowUp validation (no self-loop/duplicate, cycle check) with undo receipts
    [ ] R10d getStaleNodes injected now parameter required (no default System.currentTimeMillis())
    [ ] R10e PresetQuotes Math.floorMod for slot index
    [ ] R10f Weekday/vocabulary gaps from P1-B 14 in DateResolver
    [ ] R10g Minified release APK test on physical phone when device returns (NOT VERIFIED until then)
    [x] R10h CheckinBuilder query scaling & stale exclusion: getNodesDueBetween/getOverdueNodes limit=500 cap removal/pagination and exclude stale items consistent with ContextBuilder
    [x] R10i Pinned/severity/priority persistence & getNode reading: wire into Gateway CREATE, getNode SQL query, and undo snapshot equality
    [ ] R10j Chat history search indexing: populate msg_chunk_fts on message insert so searchMemoryFts searches conversation history
    [x] R10k Prompt context budgeting: add token/char cap on Soul facts and open proposals in ContextBuilder.buildPack
    [x] R10l SharedFakeStorage keyset pagination parity: do not reset to head when cursor ID is absent

## Roadmap items to be tackled post-remediation
[ ] P1-B 15 Reminder policy per blueprint 30 (lead times, reminder only when asked or per default)
[ ] P1-B 17 Dedupe near-match (same title near same due time -> needs_confirm)
[ ] DateResolver: Weekday names ("on friday", "next friday", "friday at 5pm")
[ ] P1-D 20 Remainder: Home priority list (code-ranked), at-risk flag, active threads, recently-closed with undo validity, structured chat items (bubble, receipt, chips, nudge+reason, check-in snapshot, system line)
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
