# DECISIONS (new ones only; old ones live in docs/blueprints/03-decisions.md; never edit old)
D-100 Node is the truth; Reminder is the alarm attached by node_id column. Node+reminder+raw_event in one transaction; arm only after commit; cancel/complete cancels alarm; firing marks reminder done.
D-101 Redaction: UPDATE of raw_message allowed only if old redacted_at IS NULL, new set, content replaced per blueprint 11, all other columns unchanged; second redaction aborts; writes raw_event.
D-102 Manifest: no VIBRATE, WAKE_LOCK, USE_EXACT_ALARM, SCHEDULE_EXACT_ALARM (setAlarmClock needs none). [Original text restored 2026-11-06; amended by D-102b, which supersedes this line on USE_EXACT_ALARM.]
D-103 DateResolver accepts 12-hour and 24-hour input. Accepted forms: "15:30", "1530", "15h", "at 15", "3pm", "3:30 pm", "3 in the evening", "tonight at 9". Devanagari and other non-Latin digits are normalised before parsing. Hours 13-23 and "00:xx" are unambiguous. A bare 1-12 with no am/pm and no day-part word ("at 5", "5:30") is AMBIGUOUS -> the resolver returns NeedsClarification with two chips (05:30 / 17:30). It never guesses (CLAUDE.md rule 2). Every echoed time is formatted by the device setting (DateFormat.is24HourFormat) and always includes the weekday and date, e.g. "Tue 27 Oct, 17:30". Tests: all forms above, ambiguous cases, 4am day rollover, midnight, Hindi digits, "tomorrow at 5".
D-104 ContextBuilder never loads all open nodes. Storage.getActiveNodes becomes getActiveNodes(limit, cursor, order) with an index on (status, due_at). ContextBuilder takes the top K by a deterministic score computed in code, not by the model: overdue > in progress > due within 48 h > pinned > severity > recency. K is limited by the token budget of blueprint 20. If that file gives no number, use 25 items. Always append one line: "N more open items not shown (M overdue)". The model can read more only through the read-only tools search_memory / list_items(cursor). Items with no activity for 30 days leave the context and appear in Inbox as "stale". The truncation is visible in the "Show me what is sent to the AI" screen. Tests: 500 and 5000 nodes -> prompt stays under budget, order is deterministic, overdue items are always included, the count line is correct, the pagination cursor has no gaps or duplicates.
D-105 Alarm-tier reminders keep setAlarmClock, and the status-bar alarm icon is accepted. [HUMAN-GATED] The icon is system behaviour. The app cannot hide it, and its look varies by phone maker. It shows only while an alarm-tier reminder is pending. Disclose it on the Permissions screen and in Settings > Reminders. Add a "Gentle" tier (notification only, no alarm clock) for low-importance reminders and nudges. The developer confirms on the real phone (adb screenshot) before this decision is final.
D-106 (2026-11-02): New edge type `follow_up` (not `depends_on`). Represents subsequent follow-up actions between nodes. Reason: Developer directive / UI spec alignment. Rejected: `depends_on` or overloading existing edge types.
D-107 (2026-11-02): Calendar navigation: tap on a date with conversation opens that day's conversation; long-press/hold shows its reminders tooltip/sheet. Reason: Developer UI spec E6. Rejected: Single tap for reminders or requiring modal navigation.
D-108 (2026-11-02): Home quotes: four time slots (morning, afternoon, evening, night) by clock, independent of day window. Preset generic quote displayed first from a pool, live LLM-generated quote swaps in asynchronously. Generated ~1h before slot change. Setting allows early preparation. At most 2 regenerations per slot after significant state changes. Reason: Blueprint 40/Home UI spec. Rejected: Dynamic hourly LLM regeneration or blocking quote display on model output.
D-109 (2026-11-03): Quote slots: MORNING 05-12, AFTERNOON 12-17, EVENING 17-21, NIGHT 21-05 (local time), independent of the day window. Prepare-ahead fires 1 hour before each boundary: 04:00, 11:00, 16:00, 20:00. Supersedes slot boundary hours in D-108.
D-110 (2026-11-03): Reschedule after ambiguity is implemented properly. proposal gets a nullable payload TEXT (JSON) column (DB v5). The payload carries what the final action needs (destination time, title/type, follow_up_to, tier). Reason: a chip tap must complete the user's real request without calling the model.
D-111 (2026-11-03): Intents UPDATE, PAUSE, RESUME are NOT supported in v1. They return an explicit error before any commit or proposal (never a fake "Applied" receipt). Remove UPDATE from the model prompt.
(Note: D-105 stays HUMAN-GATED/pending: status-bar alarm icon check on the phone is postponed. T-C is not done.)
D-112 (2026-11-03): Compound duration parsing, prompt date compliance, and untitled alarm fallbacks.
- DateResolver replaces single-unit IN_* regexes with an unanchored compound duration scanner (parseDuration) supporting seconds, minutes, hours, days, optional prefixes (in, after, within), and conjunctions (and, commas). Single-letter units (e.g. 15h) without prefix are preserved for D-103 time-of-day parsing.
- Time authority (Rule 4): Prompt explicitly forbids LLM from calculating clock timestamps or reusing "Now:" format; model must copy user temporal expressions verbatim into due_text.
- Untitled timers/alarms: Model asks "For what?" if no subject is provided. When user confirms no subject, model calls propose_change CREATE with untitled=true and label in [alarm, reminder, notification]. Gateway synthesizes deterministic title: <MM:SS> <Label> for durations < 1h (e.g. 02:32 Alarm), <H:MM:SS> <Label> for durations >= 1h (e.g. 1:05:00 Alarm), or device-formatted time-of-day (e.g. 17:00 Alarm) for absolute times. Auto-generated titles bypass duplicate title deduplication. Label is frozen at creation.
Reason: Prevent model date hallucinations, handle multi-unit timer durations (e.g. "in 2 min 32 seconds", "in 10 secs"), and provide reliable title fallbacks without creating duplicate-title errors.
Rejected: Model generating timer labels or clock timestamps; regex-guessing hallucinated date strings in code; code-enforced NeedsTitle tool flow in v1.
D-113 (2026-11-03): Keyset candidate pagination, multi-tier ranking, and 5000 hard-cap.
- Storage active node queries use cursor-based keyset pagination on (due_at IS NULL, due_at ASC, id ASC) in chunks of 200.
- Gateway.handleResolveChange loads paged items up to a 5000-node hard cap. Exceeding 5000 throws TooManyActiveNodesException, caught and reported gracefully to the user.
- ContextBuilder uses getRankedCandidates querying 6 priority tiers directly in SQL/storage with non-stale filtering, preventing candidate starvation.
- Duplicate title check uses targeted findActiveNodeByTitle(title) directly in SQL with COLLATE NOCASE.
Reason: Eliminate silent 100-item query cutoffs (T-B regression) and starvation of pinned/high-severity tasks, while preventing OOM crashes at scale.
Rejected: In-memory sorting of all nodes; unbounded database queries; silently dropping items above 100.
D-114 (2026-11-03): Alarm reconciliation semantics (reconcile(force: Boolean)) and zero-silent-failure contract.
- Force reconciliation (force = true) unconditionally re-arms all pending reminders within 60s grace (fireAtMillis >= now - 60000L). Triggered on device boot, app launch / ChatController init, timezone/time changes, and database import.
- Selective reconciliation (force = false) reconciles only reminders whose registry fire time differs from DB or is missing, and cancels and purges orphaned registry entries. Used during post-commit and UI refresh to avoid thrashing system alarms.
- AlarmScheduler.schedule returns ScheduleResult (Armed or Failed(reason)). AndroidAlarmScheduler returns Failed on SecurityException or system rejection rather than silently falling back.
- On scheduling failure, reliability_state is updated to score = "at_risk" with issues_json containing error details and timestamp, and UiState.schedulingError is exposed to notify the user.
Reason: Eliminate silent alarm drops and permission desynchronization; unify disparate scheduling loops across BootReceiver and ChatController into a single tested core reconciler.
Rejected: Silently degrading ALARM to GENTLE on SecurityException; swallowing schedule failures; re-registering unchanged alarms on every UI event.
D-115 (2026-11-04): Receipts sourced from raw_event operation log (T6).
- raw_event table's existing cause_msg_id column is populated on all mutations: ChatController passes userMsgId to executeTool, while chip, undo, and stale actions pass null cause.
- Core ReceiptFormatter deterministically transforms raw_event (op_type, before/after JSON) into device-formatted receipt strings (using DateResolver) without model prose.
- Storage.getRecentEvents(limit) queries UI-visible operations where undo_of IS NULL in chronological order.
- UiState.receipts is dynamically reconstructed from raw_event; undoToken is exposed if and only if undoValid = (clock.now() - event.tsUtc < 24h) && !storage.hasUndo(event.opId). Undone receipts retain their message but omit the undo token. Receipts preserve causeMsgId for message anchoring.
Reason: Eliminate ephemeral in-memory receipts, preserve undoability across app restarts/controller recreation within the 24h window, ensure deterministic device-formatted receipt presentation, and anchor receipts to their causing user messages.
Rejected: Storing LLM-generated prose in receipts; persisting receipts in a separate non-log table; losing undo capability upon process death.
(Next free: D-116. Add: date, decision, reason, rejected.)
D-102b (2026-11-05): Manifest exact-alarm permissions and empirical API 34 verification.
- AlarmManager.setAlarmClock() on Android 14 (API 34, targetSdk 35, minSdk 34) strictly requires either android.permission.SCHEDULE_EXACT_ALARM or android.permission.USE_EXACT_ALARM. Without either declared in the manifest, setAlarmClock() throws java.lang.SecurityException: Caller com.anchor.mine needs to hold android.permission.SCHEDULE_EXACT_ALARM or android.permission.USE_EXACT_ALARM to set exact alarms. (empirically reproduced on physical moto g73 5G - Android 14 in R12c).
- USE_EXACT_ALARM is declared in AndroidManifest.xml. It is a normal, install-time auto-granted permission for alarm/reminder applications, avoiding the disruptive user-facing AppOps toggle required by SCHEDULE_EXACT_ALARM.
- SCHEDULE_EXACT_ALARM, VIBRATE, and WAKE_LOCK remain strictly forbidden and absent from the manifest. Zero-vibration hardware safety protocol strictly enforced.
Reason: Factual OS requirement on Android 14+; prevents app crash/SecurityException when arming ALARM-tier reminders; preserves zero-vibration and privacy invariants.
Rejected: Declaring SCHEDULE_EXACT_ALARM (requires manual user toggle in system settings); omitting USE_EXACT_ALARM (causes fatal SecurityException on setAlarmClock).
D-116 (2026-11-04): Chat history search indexing via msg_chunk and msg_chunk_fts (R10j).
- insertRawMessage atomically populates msg_chunk for non-blank user and assistant messages (excluding system messages) within the write transaction.
- External content sync triggers (trg_msg_chunk_fts_insert, trg_msg_chunk_fts_delete, trg_msg_chunk_fts_update) keep msg_chunk_fts in strict sync with msg_chunk.
- searchMemoryFts queries node_fts, soul_fts, and msg_chunk_fts, returning unified SearchResult with kind = "chat", title = day_key, details = text.
- Redaction via redactMessage removes the corresponding msg_chunk row, triggering automatic cleanup in msg_chunk_fts.
- SharedFakeStorage mirrors this behavior for deterministic in-memory testing.
Reason: Fulfill memory search covering past conversation history; ensure immediate consistency and full privacy compliance upon redaction.
Rejected: Asynchronous background indexing worker (lag breaks immediate search testability); multi-message chunking (complicates single-message redaction).
D-117 (2026-11-05): Proposal CAS and confirm-path concurrency semantics (R12 / R12c).
- Proposal consumption uses atomic Compare-And-Swap (CAS) directly in storage: UPDATE proposal SET status='consumed' WHERE id=? AND status='open'. The first caller receives rowsAffected=1 and proceeds; concurrent or duplicate callers receive rowsAffected=0 and are rejected with "Proposal already resolved".
- Two-phase execution with status rollback: Gateway.handleConfirmChange performs atomic CAS first. If node mutation, reminder arming, or option validation subsequently fails (or throws), a compensating rollback invokes updateProposalStatus(id, "open").
- updateProposalStatus updates only the status column, strictly preserving the proposal's immutable created_at timestamp. This prevents resetting the 24-hour expiration window upon rollback.
- Terminal entity failures ("Node not found", "Node is no longer active", expired timestamps) leave the proposal "consumed" to clear dead UI chips.
- ChatController suppresses error receipts/banners for losing concurrent taps when the winning tap succeeds.
Reason: Prevents race conditions and duplicate node/alarm creation across rapid UI double-taps; preserves 24-hour proposal expiration integrity; provides graceful error recovery without leaking dead proposals.
Rejected: in-memory Mutex (does not protect cross-process or DB-level concurrency). [Correction 2026-11-06: the original "Rejected" line named "Transactional CAS inside effect" — that reasoning was wrong; consumeProposalId already exists on the commit methods, and the real gap was a conditional UPDATE with a rows-affected check inside the transaction. D-118 later adopted exactly that in-transaction CAS (Option b). Line corrected; decision text unchanged pending developer confirmation.]

### Note on Knowledge Pack Numbering Differences
The Session 1 remediation pack cited decision numbers that differed from the chronological sequence recorded in DECISIONS.md:
- Pack "D-112" referred to Architectural Logic Fix 1 (Time chip pre-validation & untitled/label payload preservation), whereas in this repo D-112 is "Compound duration parsing, prompt date compliance, and untitled alarm fallbacks" (R11).
- Pack "D-114" referred to Architectural Logic Fix 3 (Gentle reminders RTC_WAKEUP & Doze disclosure), whereas in this repo D-114 is "Alarm reconciliation semantics (reconcile(force: Boolean)) and zero-silent-failure contract" (R3).
- Pack cited "Next free: D-113" before R2 was merged, while R2 recorded D-113 for keyset candidate pagination and 5000-item cap.
- The same pack drift extends to D-115..D-117: in this repo D-115 is "Receipts sourced from raw_event operation log" (R7), D-116 is "Chat history search indexing via msg_chunk_fts" (R10j), and D-117 is "Proposal CAS and confirm-path concurrency semantics" (R12). Any pack text attributing these numbers to other topics is describing its own numbering, not this log.
Repo DECISIONS.md is the authoritative source of truth.
(Next free: D-118. Add: date, decision, reason, rejected.)

D-118 (2026-11-05): Transactional Proposal CAS inside Effect Transaction (R12d).
- Atomicity Option (b) (developer confirmed): For node and reminder mutations (CREATE time-chip, CANCEL, COMPLETE, RESCHEDULE, UPDATE-reminder), proposal consumption is embedded directly inside the database effect write transaction via consumeProposalId.
- In-transaction CAS: Storage implementations (AndroidStorage and SharedFakeStorage) execute UPDATE proposal SET status = 'consumed' WHERE id = ? AND status = 'open' inside the transaction. If rowsAffected == 0, the entire effect transaction is aborted (rolling back all node, reminder, edge, and event mutations) and ProposalAlreadyResolvedException is thrown.
- Single-winner semantics: Two concurrent confirms on the same proposal yield exactly one committed effect; the losing caller's transaction rolls back with 0 changes and receives ToolResult.Error("Proposal already resolved").
- Gateway orchestration: Gateway keeps pre-check validation (status == 'open', valid choice option, user message match), but no longer calls consumeProposalCas before invoking effect transactions for these paths.
- Terminal vs Non-terminal failures: Terminal failures where the underlying entity is permanently unusable ("Node not found", "Node is no longer active", target node missing) explicitly consume the proposal (marking status='consumed') to clear dead UI chips. Non-terminal failures (user choice invalid, past time, duplicate active title) leave the proposal open.
- Guarded rollback: Storage.reopenProposal(id) executes UPDATE proposal SET status = 'open' WHERE id = ? AND status = 'consumed' (preserving created_at and 24h expiration), replacing blind status updates and preventing accidental reopening of proposals consumed by other turns.
Reason: Ensures strict database-level atomicity without crash-window vulnerabilities between proposal CAS and effect commit; eliminates orphan consumed proposals on mid-effect failure; preserves single-winner concurrency semantics.
Rejected: Option (a) Two-phase CAS before effect with compensating rollback (leaves crash-window vulnerability if process is killed or power cut between CAS and commit); blind updateProposalStatus("open") rollback (can corrupt state if called on an already-resolved proposal).

(Next free: D-119. Add: date, decision, reason, rejected.)

D-119 (2026-11-05): Near-Match Deduplication and needs_confirm Proposal Protocol (P1-B 17 / Task #103).
- Near-Match Check: When creating an active item (`propose_change CREATE`), Gateway queries `storage.findActiveNodesByTitle(title)`.
  - If existing and new item both have due times, they are near-matches if `Math.abs(existing.dueAt - newDueAt) <= DEDUPE_TIME_WINDOW_MS` (2 hours).
  - If both are undated, or if one is dated and the other is undated, they are treated as near-matches.
  - If both are dated and scheduled > 2 hours apart, they are treated as distinct events and committed immediately.
  - Untitled alarms (`untitled = true`, e.g. "01:00 Alarm") bypass deduplication entirely.
- Proposal Generation (`kind = "needs_confirm"`):
  - Returns `ToolResult.NeedsConfirm(proposalId, message, options)` where options are `["Update existing", "Add anyway", "Cancel"]`.
  - Persists `Proposal` with options `ProposalOption(duplicate.id, "Update existing", dueStr)`, `ProposalOption("add_new", "Add anyway")`, `ProposalOption("cancel", "Cancel")`.
  - Embeds `ProposalPayload` preserving targetAtMillis, title, type, followUpTo, priority, severity, pinned, tier, and targetId.
- Confirmation Resolution (`handleConfirmChange`):
  - `choice == "cancel"`: Atomically consumes proposal via CAS (`storage.consumeProposalCas`) with 0 entity mutations, returning `ToolResult.Committed("CANCEL", ...)`.
  - `choice == "add_new"`: Invokes `handleCreate` with `consumeProposalId = proposal.id`, executing in-transaction proposal CAS (D-118) to atomically commit the new node and consume the proposal.
  - `choice in [duplicate.id, "update"]`: Updates existing node's `dueAt`, updates/reschedules reminder, commits `RawEvent` with before/after snapshots, and executes in-transaction proposal CAS (D-118).
  - Concurrency: Atomic CAS ensures single-winner semantics; concurrent taps yield exactly one committed action while losing taps receive "Proposal already resolved".
  - Two-Stage Transition: When an item has an ambiguous time ("at 5"), Gateway first issues a `time` clarification proposal (`NeedsTime`). Once the user selects a candidate chip, the resolved timestamp is checked against existing items for near-match deduplication; if a near match exists, it transitions seamlessly into `NeedsConfirm`.
Reason: Conforms strictly to blueprint 22 s3.8 and blueprint 23 s6; prevents duplicate alarms/tasks while giving the user flexible options to update existing items or add anyway; maintains transactional atomicity and concurrency safety.
Rejected: Rejecting duplicate creation with a hard error; silently creating duplicate items without confirmation; overwriting existing items without user consent.

D-120 (2026-11-05): Reminder Policy per Blueprint 30 (P1-B 15 / Task #104).
- Sections Cited: Blueprint 30 sections 3.1, 3.2, 3.3, 3.4, 3.9; Blueprint 52 line 174; D-035.
- Creation Policy (s3.1, s3.3, D-035):
  - Explicit user temporal commands create reminders directly (lead time = 0 at `dueAt` for dated tasks); undated items do not automatically generate alarms.
  - AI-inferred reminders check setting `reminder.ai_suggest` (default `ask`): in `ask` mode, Gateway emits `ToolResult.NeedsConfirm` with chips `[Set <time>, Change, Cancel]`; in `auto` mode, creates immediately with undo capability.
- Vague Day-Part Windows & Severity Positioning (s3.4):
  - Resolves vague day-part expressions deterministically using standard windows:
    - Morning: 07:00 to 11:00 (240 min)
    - Afternoon: 12:00 to 16:00 (240 min)
    - Evening: 17:00 to 20:00 (180 min)
    - Night: 20:00 to 23:00 (180 min, clamped to day end)
    - Soon: `now + 30 min`
    - Later: `now + 2 hours`
  - Position inside window is determined by node severity:
    - Sev 3 (critical): 10% into window
    - Sev 2 (high): 35% into window
    - Sev 1 (normal): 60% into window
    - Sev 0 (low/default): 75% into window
    - Unspecified: window default/midpoint.
- Relative Lead Times & Offsets (s3.2, s3.4, Blueprint 23 s6):
  - `schedule_reminder` accepts `offset_text` (e.g., "15 minutes before", "1 hour before") anchored to `node.dueAt` (`fireAt = node.dueAt - offsetMillis`).
  - Errors if node is missing or undated, or if calculated fire time is in the past.
- Near-Duplicate Reminder Policy (s3.3):
  - If a reminder for the same node already exists within 30 minutes (`|existing.fireAt - newFireAt| <= 30 min`), `schedule_reminder` returns `ToolResult.NeedsConfirm` ("A reminder is already set for <existing>. Update it to <new>, or cancel?") with options `[Update existing, Cancel]` instead of silently overwriting.
  - User can confirm "update" to adjust existing reminder or "cancel" to dismiss.
- Quiet Hours / Day Window Policy (s3.3, D-035):
  - Explicit user reminders are requested-class and ring even outside the day window.
  - AI-inferred reminders outside `dayRule.startMinute..dayRule.endMinute` are clamped to `day_start + 30 min`.
Reason: Fully enforces deterministic code-side reminder scheduling rules without LLM guessing (Rule 1 & Rule 4); respects quiet hours and user autonomy; prevents duplicate alarm spam while allowing multi-offset reminders (> 30 min apart).
Rejected: Silently overwriting reminders within 30 minutes; moving explicit user alarms outside quiet hours; guessing times for undated items without user request.

D-121 (2026-11-06): Home data queries and structured chat timeline (P1-D 20).
- Home priority list is ranked in code, not SQL: `rankHomeNodes(nodes, now)` sorts by 1) overdue first; 2) in_progress; 3) pinned (explicit user boost); 4) severity desc; 5) priority desc; 6) due soonest (most overdue first, undated last); 7) most recently updated; ties by id. `getHomePriorityList(now, limit = 5)` filters to fresh (non-stale per the D-104 rule), non-local-only, active/in_progress/paused nodes first.
- Recently closed: `getRecentlyClosed(now, limit = 10)` returns done/cancelled nodes with closed_at within the last 24h, newest first. Undo validity mirrors D-115 receipts: `(now - latestClosingEvent.tsUtc < 24h) && !storage.hasUndo(event.opId)`; the token is that event's opId. ChatController resolves validity per node via new `getLatestEventForTarget(targetType, targetId)`.
- At-risk flag: `UiState.atRisk = (ReliabilityState.score == "at_risk")`; the existing schedulingError carries the reason. Active thread: `ActiveThread(msgId, openedAt)` derived from ProactiveState.openThreadMsgId/threadOpenedAt.
- Structured timeline: `ChatTimelineBuilder.build(messages, receipts, openProposals)` composes a `ChatItem` list (DayHeader, TimeDivider, Message, SystemEvent, ReceiptItem, ProposalRow, CheckinCard, Nudge, StatusNotice); receipts attach under their cause message, orphaned receipts and proposal rows render at the end. Exposed as `UiState.chatItems`.
- UPDATE is now UI-visible: added to the `getRecentEvents` op filter in both AndroidStorage and SharedFakeStorage, and ReceiptFormatter gains an "Updated: <title> (<due>)" branch reading the after-snapshot — fixes P1-B 16 UPDATE receipts being invisible (D-115 regression).
Reason: Blueprint 50 s3.2/s3.5 Home/timeline structure; code-side ranking keeps the rule testable and consistent across SQLite and fake storage; parity of the two Storage implementers is a project invariant.
Rejected: SQL ORDER BY ranking (untestable rule, drifts between implementations); separate receipt table (D-115 already chose raw_event); exposing raw message+receipt lists to the UI without composition.

D-122 (2026-11-06): BootReceiver manifest filter and exported flag (B5).
- BootReceiver stays `exported=true` with a 4-action intent-filter: BOOT_COMPLETED, MY_PACKAGE_REPLACED, TIME_SET, TIMEZONE_CHANGED. All four are protected broadcasts (system-sender-only; MY_PACKAGE_REPLACED additionally only ever targets the app itself), so exported=true is not a spoofable entry point on stock Android.
- `exported=true` is required on API 31+ for any manifest receiver with an intent-filter to receive system broadcasts; the in-code guard `action !in validActions` (BootReceiver.kt:20-21) is defence-in-depth and matches the manifest set exactly. AlarmReceiver and QuoteAlarmReceiver remain `exported=false` (no filter, explicit-intent-only).
- Evidence: installed-APK compiled manifest (`aapt2 dump xmltree`, b5-manifest-dump.txt) shows exported=true + 4 actions; device `dumpsys package` (b5-receiver-dump.txt) shows the receiver registered under all 4 actions with RECEIVE_BOOT_COMPLETED granted; `BootReceiverIgnoreTest` 1/1 PASS on device proves non-intended broadcasts (custom action, ACTION_POWER_CONNECTED, empty action) are no-ops while BOOT_COMPLETED sweeps a stale canary. Full report: docs/agent/evidence/b5-evidence.md.
Reason: Manifest receivers for protected system broadcasts must be exported on API 31+; the action allow-list keeps the trust boundary explicit and testable on device.
Rejected: exported=false (system broadcasts would not be delivered, breaking boot/time-change reconciliation); dropping the in-code guard (manifest-only filtering leaves no defence-in-depth and no testable contract); adding a custom action to the filter (would be spoofable by any app).

D-123 (2026-11-06): Connected-test truth comes from UTP's result artifacts, not its exit code.
- `connectedDebugAndroidTest` on AGP 9.4.0 / UTP 32.4.0 reports failure for green runs: `UtpTestUtils.kt:186` derives task success solely from `test-result-exit-code.txt`, and `AndroidTestEngineRunner.execute` writes `1` because `DeviceTrackingListener`'s `Map<String,Boolean>` is looked up by device serial that the engine never registers as a container (root cause: docs/agent/work/connected-red-build-rootcause.md).
- New authoritative gate: `./gradlew :app:connectedDebugAndroidTestVerify` runs `gradle/connected-results-gate.py`, which reads `TEST-*.xml` (JUnit, from `XmlTestRunListener`) and falls back to `test-result.pb` (UTP proto). It exits 0 only when at least one test ran and none failed; unreadable or absent evidence is a failure, never a pass.
- The gate counts `<testcase>` elements, never the suite's `tests`/`failures` summary attributes — a summary it cannot cross-check is the same class of evidence this decision exists to replace. Proto statuses are transcribed from `proto/api/core/test_status.proto` in `test-result-proto-1.0.1.jar` (3=PASSED, 4=IGNORED, 8=SKIPPED are non-failures; UNSPECIFIED/STARTED/FAILED/ERROR/ABORTED/CANCELLED/IN_PROGRESS all fail).
- The task is NOT wired into `check`: a red connected run would fail every offline build, and the AGP task still aborts the run before this one executes. Run both with `--continue`.
- Evidence: gate PASS 3/3 on the real artifact while the exit-code file says `1`; synthetic red XML makes the Gradle task exit 1 and the restored green artifact exit 0; 20+ fixture cases (XML case failure/error, lying summary attributes, malformed XML, truncated proto, every proto status value 0-9, empty dir) each produce the intended verdict.
Reason: an unfalsifiable red build makes every other verification claim untrustworthy; a gate that reads real evidence is the only form that keeps a genuine failure detectable.
Rejected: (1) accept-and-annotate (build stays red, CI stays red, no signal); (3) upgrade/downgrade AGP (unverified against release notes, out of scope for a hardening pass).

D-124 (2026-11-07): "Fired" means delivered, and delivery latency is recorded.
- `AlarmReceiver` sets `lastFiredId` and calls `AndroidAlarmScheduler.notifyFired(id)` **after** `storage.deleteReminder(id)`, inside the same `try`, and only after `manager.notify(...)` has returned. A run that found the row but then failed before the delete does not report a fire.
- The same block records a `reminder_fire` row with `outcome = "delivered"`, `scheduledFor = rem.fireAtMillis`, `firedAt = System.currentTimeMillis()` and `latencyMs = firedAt - scheduledFor` (clamped at 0 for early deliveries), matching the shape `ReliabilityManager.runSweeper` already writes for `outcome = "missed"`. Blueprints 30 and 42 already require a latency record per delivery; until now only the missed path produced one.
- `lastFiredId` / `firedFlow` therefore mean "delivered", not "attempted". `AlarmReceiverTest.testAlarmReceiver_ValidReminder_FiresAndDeletesRow` asserts the row is already gone when the flag appears, so it can only pass if the flag really does follow the delete.
- The missing-row path (`rem == null && !isMissed`) still returns before any of this, so `testAlarmReceiver_CancelledReminder_DoesNotFire` keeps its meaning: a cancelled reminder never reports a fire.
- The alarm-test flake is **unexplained**, not fixed. Widening the fire window (1.5 s → 5 s, 10 s → 20 s poll) is a robustness change with no demonstrated causal link to the observed failure; the delivered-latency rows are what turn the next occurrence into data.
Reason: a completion signal that precedes the durable write reports success for work that has not happened, and the test that checks the row is deleted was racing against it; recording latency is the only way a future flake can be diagnosed instead of re-guessed.
Rejected: setting the flag before the storage read (a missing-row delivery would look like success and break the cancelled-reminder test); setting it after the read but before the delete (keeps the race); an in-memory-only latency counter (lost on process death, which is exactly the case being measured).

D-125 (2026-11-07): D-124 close-out — #131 closed as "mitigated, unproven"; the gate reads the per-device proto as a fallback.
- **#131 is closed as mitigated, not fixed.** A 20-run loop of `AlarmSelfTest` produced 0 failures (0 stale archives, green by XML *and* proto in all 20; `gradleExit: 1` in all 20, D-123 reconfirmed). 0/20 gives a 95% upper bound of ~14%, so the flake is proven rarer than ~1 in 7, not proven absent. No transcript of a real failure exists, so the 1.5 s → 5 s / 10 s → 20 s widening cannot be shown to fix anything; it is retained because it removes the measured fire-latency component (ALARM +0.28–0.32 s, GENTLE +1.12–1.66 s over target) from a budget that was ~1.2 s total. The D-124 `reminder_fire` rows are the instrument that turns the next real occurrence into data; reopening #131 requires such a row, not a hunch.
- **The gate now falls back to `results_dir/<device>/test-result.pb`** when no top-level `test-result.pb` exists. It reads one or the other, never both, so a run that writes both copies is not double-counted. XML discovery is unchanged (top level only).
- Evidence: `docs/agent/evidence/d124-loop-v2-report.md` (20-run data in `d124-loop-v2/`); gate fixtures — device-only proto green → PASS/exit 0 (source: proto); device-only proto with one status patched PASSED→ERROR → FAIL/exit 1 naming the test; both copies present → 3 tests, not 6; empty results dir → FAIL/exit 1.
Reason: a mitigation with no reproduction must be recorded as a mitigation, or the next reader assumes a fix they can rely on; and a gate that reports "no readable artifact" while a readable artifact exists is the same unfalsifiable-evidence class D-123 exists to remove.
Rejected: closing #131 as "fixed" (no reproduction to verify against); reverting the widened window as unjustified (it costs ~4 s per run and removes a measured latency component); globbing `**/test-result.pb` and summing every copy (double-counts when both exist); leaving the gate gap documented-but-unfixed when the fixture cost was one line.

D-126 (2026-11-07): Live-provider credentials are debug-scoped; a clean release-APK scan is not evidence of absence.
- **`LIVE_API_KEY` / `LIVE_ENDPOINT` / `LIVE_MODEL` are declared in `buildTypes { debug { … } }` only**, never in `defaultConfig`. `defaultConfig` applies to every variant, so the three fields were generated into the release `BuildConfig.java` as `public static final String` and compiled into `javac/release/.../BuildConfig.class`.
- **The pre-fix release APK scanned clean and that proved nothing.** Three scans of `app-release.apk` found 0 hits for the key, `LIVE_API_KEY`, `api.mistral.ai` and `mistral-small-latest`, and `mapping.txt` had no `com.anchor.mine.BuildConfig` entry — because the only reader (`LiveSmoke15Test`) is androidTest-scoped, so R8 eliminated the unreferenced field. Proven by experiment, not assumed: a temporary release-reachable `Log.e("H1X", BuildConfig.LIVE_API_KEY + BuildConfig.LIVE_ENDPOINT)` in `MineApp.onCreate` put `key=1 api.mistral.ai=1 H1X=1` into `classes.dex`. The experiment was reverted; `MineApp.kt` retains only its pre-existing `NotificationChannels.ensureCreated` diff.
- **A constant-folded guard is not a weaker version of the experiment, it is a different result.** `if (BuildConfig.LIVE_API_KEY.length > 1_000_000) Log.e(...)` produced 0 hits for the key *and* 0 for the marker string — R8 removed the whole provably-false branch. Only a genuinely live sink tests survival.
- **Verification is source-level and dex-level, not test-level.** Release `BuildConfig.java` now contains only `DEBUG/APPLICATION_ID/BUILD_TYPE/VERSION_CODE/VERSION_NAME`; debug keeps all three marked `// Field from build type: debug`; rebuilt release APK (18,839,007 B) scans `key=0 LIVE_API_KEY=0 LIVE_ENDPOINT=0 mistral=0`; `mapping.txt` has 0 `BuildConfig` entries. `BuildConfigCredentialScopeTest` (app unit, `tests=1 failures=0`) can assert only the debug half — a JVM unit test compiles against one variant and cannot see the release `BuildConfig`.
- **Accepted residual:** the key remains in git-ignored `local.properties` and in every debug artifact (debug `BuildConfig`, debug jars, debug APK). That is intended — `LiveSmoke15Test` needs it. Debug builds must not be distributed. If any release APK built before this fix was distributed, rotate the key (not done; needs the user's call).
- Evidence: `docs/agent/work/h1-api-key-scope.md`; full suite after the fix — core `tests=267 failures=0 errors=0 skipped=0`, app `tests=10 failures=0 errors=0 skipped=0`.
Reason: a credential in a variant it is not needed in is a latent injection, and the only reason it never shipped was an optimiser decision that any future reference would silently reverse; a scan of the minified output cannot distinguish "absent from the build" from "removed by R8".
Rejected: relying on R8 elimination (it is not a security property, and it fails open on the first live reference); a ProGuard `-keep` rule for `BuildConfig` (would have guaranteed the key shipped); a release-only empty-string override (leaves the field present and one edit away from a real value); asserting release-side absence in a JVM unit test (impossible in the same compilation — replaced by generated-source and dex checks).

D-127 (2026-11-07): The D-123 gate task must declare the results directory as an input; a gate with no inputs fails open.
- **`connectedDebugAndroidTestVerify` now declares `inputs.dir(resultsDir)`.** It previously declared only `outputs.file(reportFile)`. A task with no inputs and one output is up-to-date after its first run, so the gate re-printed a stale PASS regardless of what the results directory contained.
- **Observed fail-open, not hypothesised.** A connected run left `TEST-moto g73 5G - 14.xml` with 5 testcases and 1 `<failure>`. The Gradle task reported `UP-TO-DATE` / `BUILD SUCCESSFUL` / exit 0 while `gate.json` still held `"tests": 3, "failures": 0` from an earlier run; the same script run by hand on the same directory reported `FAIL - 5 test(s), 1 failed`, exit 1. The parser was never wrong — it was never re-run.
- **The task also has no ordering relationship with the test run.** `--dry-run` lists `:app:connectedDebugAndroidTestVerify` alone. This is deliberate and unchanged: adding `dependsOn("connectedDebugAndroidTest")` would let a red AGP task abort before the gate executes, and the AGP exit code is not trustworthy (D-123). The input declaration is the correct fix; ordering remains open.
- **Failing-first proof:** a green fixture directory reports PASS and then UP-TO-DATE; replacing its XML with a failing one makes the task re-run and fail (`CONNECTED GATE: FAIL - 5 test(s), 1 failed`, exit 1). Before the fix the same substitution produced `UP-TO-DATE` and exit 0.
- **A gate whose verdict cannot change is not evidence.** Same class as D-123 itself: the untrustworthy exit code was replaced by an artifact reader, but the artifact reader was then cached against nothing.
- Evidence: `docs/agent/work/gate-fail-open.md`.
Reason: every connected-test claim in STATE/TASKS/HANDOFF is sourced from this gate; a gate that can report PASS without re-reading its input makes all of those claims unfalsifiable.
Rejected: `dependsOn("connectedDebugAndroidTest")` (a red AGP run aborts before the gate runs — the ordering problem is real but this is the wrong lever); relying on `--rerun-tasks` (a manual flag the documented command line does not use, so the default path would still fail open); making the gate script itself detect staleness (it has no baseline — only Gradle knows whether the directory changed since the last verdict).

D-128 (2026-11-08): The connected-test gate must carry the failure message, and its message path is pinned by fixtures wired into `check`.
- **Both of the gate's read paths discarded the assertion text.** `parse_xml` counted `<failure>`/`<error>` children but returned only a label list, so the body — the only record of what the assertion said — was dropped. `parse_proto` never looked at `TestResult.error` (field 3), so on the D-123 route (XML truncated, proto authoritative) the text was dropped there too. A red run therefore printed `FAILED <fully.qualified.Test.method>` and nothing else on either route.
- **The proto schema is transcribed from the jar, not guessed.** `test-result-proto-1.0.1.jar` → `proto/api/core/error.proto` gives `Error { string error_message = 1; string error_type = 2; string stack_trace = 3 }`, and `test_result.proto` gives `TestResult { TestCase = 1; TestStatus = 2; Error = 3; … }`. The doc comment on `Error` says it is used only when the test ends in an Error or Failure — which is why a green proto has no field 3 on any of its 83 results (verified by decoding the on-disk artifact, and by a raw-byte scan for the failure strings: 0 hits).
- **The gate says "no message" when there is no message.** A `<failure></failure>` (UTP has been observed writing one) or a proto result with no `Error` now prints `(no failure message recorded in the <source>)` and is counted in a `notes` entry. Silence is labelled as UTP's silence, never as the gate's.
- **The fixtures are permanent and non-rottable.** They live in `gradle/test_connected_results_gate.py` (moved out of `scripts/tmp_*`), cover the xml route, the proto route, the empty-body case and a green run, and run under `:app:connectedGateFixtures`, which `:app:check` depends on. A gate nobody tests is how the message got dropped in the first place.
- **Both halves were RED-probed by reverting the fix, not by deleting the test.** (a) Message printing reverted → 1/4, the three message cases fail, and the captured stdout is the original defect verbatim (`FAILED …` with no text). (b) Proto `Error` parsing disabled → 3/4, only the proto case fails. GREEN 4/4; `:app:check` `BUILD SUCCESSFUL`; verdict regression `scripts/tmp_gate_fixture.py` 0/4 failures; real artifacts still `PASS - 83 test(s)`.
- **Accepted residual:** whether UTP *populates* field 3 on a real failure is unproven. The proto route is pinned against a synthetic proto built from the schema; no captured UTP failure artifact exists. If a future red run shows `(no failure message recorded in the proto)` while the XML is truncated, the next step is to capture that `test-result.pb` and decode it — the fixtures will then need a real-artifact case.
Reason: the gate is the only artifact a reader has after a red device run (D-123), and a verdict without a reason turns every failure into a bisect; the two drop points were independent, so fixing one would have left the D-123 route blind.
Rejected: printing only the first line of the message (the diagnostic fields `AlarmSelfTest` emits are on the same line as the sentence, and multi-line traces carry the frame that identifies the call site); reading the message from logcat instead of the artifacts (logcat is not archived with the run and the gate has no device access); leaving the fixtures in `scripts/tmp_*` (they were re-runnable only by someone who knew the path, which is how the message stayed dropped through two gate revisions); failing the gate when a message is absent (the absence is UTP's, and a gate that fails on its own missing evidence would be indistinguishable from a real red run).

## D-129 — H-04 failure injection goes through a `copyFile` constructor seam (2026-11-08)
- **Decision:** `AndroidExportManager` takes `copyFile: (src: File, dst: File) -> Unit` defaulting to `src.copyTo(dst, overwrite = true)`. The H-04 test overrides it to let the live→`.bak` copy run for real and throw on the temp→live copy, then assert exactly three copies ran (backup, failed swap, rollback) plus a byte-identical live DB.
- **Reason:** the crash window H-04 exists for is "the process dies after the live DB is truncated and before the new DB is verified". That window is only reachable by failing the second copy specifically, and no file-system arrangement on a real device produces that failure: `copyTo(overwrite = true)` deletes a directory target recursively, `File.delete()` removes an empty directory at the `.bak` path, and `copyTo` is the `kotlin.io` extension so a `File` subclass cannot override it (`NOTHING_TO_OVERRIDE`).
- **Rejected:** the audit's "inject a `File` copy hook" (not implementable — see above); directory-at-the-live-path (proven non-throwing on device, `H04-DIAG SWAP-ENTRY … isDir=true`); duplicate-primary-key injection (aborts inside the temp transaction, strictly before the swap — it passed 8/8 on unfixed code and was deleted); a debug-only `BuildConfig` flag (would test a different code path from the one that ships).
- **Accepted residual:** the seam is a production constructor parameter whose only non-default value is supplied by tests. It is a plain function default with no branching in the swap, so the shipped path is the default value; the cost is one extra parameter on a class that already takes eight.

## D-130 — Blueprint 23 aliases are in-memory per gateway instance, not an `alias_map` table (2026-11-08)
- **Decision:** `Gateway` holds `aliasMap: ConcurrentHashMap<String, String>` and emits `n_<id>`/`s_<id>`/`m_<id>`/`p_<id>`/`r_<id>`/`u_<id>` in tool results; `resolveRef` maps an issued alias back and passes an unissued alias-shaped ref through; `unknown_ref` is raised at the storage lookup. No `alias_map` table, no schema version bump.
- **Reason:** blueprint 23 §4 describes an `alias_map` table, but the aliases it defines are per-turn — a new turn replaces them — and one `Gateway` instance exists per chat controller, so the instance lifetime already equals the alias lifetime. A table would add a v6 migration immediately after the T19 schema repair, for state that is meaningless across turns. Second reason: real row ids share the alias prefixes (proposals `p_<hex6>`, soul facts `s_<hex8>`, raw messages `mN`), so an alias-shaped ref that the map never issued cannot be distinguished from a real id at resolution time; rejecting it there would reject real ids. The lookup is the only place that can tell.
- **Rejected:** a durable `alias_map` table (schema churn for per-turn state); rejecting unissued alias-shaped refs in `resolveRef` (breaks `p_<hex6>` proposal ids and `s_<hex8>` soul-fact ids); real ids in tool output (blueprint 23 §2.2 forbids it — the model must never see a row id).
- **Accepted residual:** aliases do not survive a process restart mid-turn; a model that echoes an alias from a previous turn gets `unknown_ref` and must search again. That is the intended failure, not a bug.

## D-131 — Release builds stay signed with the debug keystore until v2 feature-complete (2026-11-08)
- **Decision:** `app/build.gradle.kts` keeps `signingConfig = signingConfigs.getByName("debug")` on the `release` block. No upload keystore is generated now.
- **Reason:** developer directive 2026-11-08 — the app is expected to stay in development until it is feature-complete toward v2 (memory, souls, and the remaining v1 scope). Neither cost applies yet: there is nothing to upload to Play, and there is no debug install to upgrade over on a user device. The debug keystore is public knowledge, so a release build signed with it carries no secret — this is a distribution/upgrade-integrity question, not a key-leak.
- **Rejected:** generating an upload keystore now (a lost or rotated keystore is unrecoverable, and there is no benefit before submission); treating it as a v1 release blocker (it is not one until Play submission).
- **Accepted residual:** a release APK cannot be uploaded to Play and cannot upgrade over a debug install without an uninstall. Revisit at the v2 / Play-submission boundary. Also closes audit question (c).
- **Supersedes:** the v1 scope audit 2026-11-08 open question (c) and the `STATE.md` "debug key" open item.

## D-132 — Quick capture: `q_` id prefix instead of a `raw_message.tags` column (2026-11-08)
- **Decision:** a captured message is identified by its id prefix `q_<uuid4>` (`QuickCapture.newMessageId()`), not by a `quick_reply` tag stored in `raw_message`. `raw_message` is unchanged; no schema version bump.
- **Reason:** blueprint 50 §4 assigns the tag `quick_reply`, but `raw_message` has no tags column and adding one is a migration for a value that is derivable from the id. The id is generated at the single write path (`QuickCapture.store`), so a `q_` prefix is as reliable as a stored tag and cannot drift from it. `pending()` therefore needs no join: `m.id.startsWith("q_")` plus "no assistant message after it" is the whole predicate.
- **Rejected:** a `raw_message.tags` column (schema churn for a derivable value, immediately after the T19 repair); a separate `capture` table (a second write that can fail after the row was written, which is exactly the E-17 lost-sentence case).
- **Accepted residual:** `TAG_QUICK_REPLY` exists as a constant but nothing writes it. A future export that must carry the tag has to derive it from the prefix. Real ids must never start with `q_` — `u_`/`a_`/`m` are the existing prefixes, so the namespace is free.

## D-133 — The capture notification is gated off by default; the shortcut is gated on (2026-11-08)
- **Decision:** `QuickCapture.isCaptureNotificationEnabled` returns true only when `capture.notification == "true"`; `isShortcutEnabled` returns true unless `capture.shortcut == "false"`. Both are read from `Storage` settings.
- **Reason:** blueprint 50 §3.14 says the capture notification is optional and off by default, and blueprint 52 §12 says the shortcut is on by default. "Off by default" has to mean the absence of the key, not a stored `false` that a fresh install never writes — otherwise the default depends on whether some seeding path ran.
- **Rejected:** storing `false` at first launch (a write that can fail, and a default that is then indistinguishable from a user's explicit choice); reading the setting inside `QuickCapture.store` (the store must never be conditional — E-17 requires the text to be written regardless of presentation settings).
- **Accepted residual:** the launcher shortcut is declared in `shortcuts.xml`, which cannot be removed at runtime, so `capture.shortcut=false` is honoured in `MainActivity.handleShortcutIntent` (the app opens, the composer is not focused) rather than by unregistering the shortcut. The persistent capture notification itself is still **not posted** — only the switch that will gate it exists.

## D-134 — E-17 drain completeness: every pending capture is its own turn, `sendText` queues (2026-11-08)
- **Decision:** `MainViewModel`'s startup drain sends **every** id from `QuickCapture.pending()`, not `firstOrNull()`. `ChatController.sendText` no longer drops a send while a turn is streaming: it launches, joins `currentStreamJob` while `isChatLoading`, then sends. `getRecentMessages` orders by `ts_utc DESC, rowid DESC`. Pinned by `QuickCaptureDrainTest` (core) and `AndroidStorageTest.getRecentMessages_TiedTimestamps_ReturnInWriteOrder` (device).
- **Reason:** the verification agent found E-17 still open after `dd984ba`. A turn's single assistant reply is written *after* every capture that preceded it, so `QuickCapture.isUnanswered` goes false for **all** of them. Draining only the oldest therefore marks the rest answered without ever showing them to the model, and they are never retried — the exact lost-sentence failure E-17 exists to prevent. Dropping a send because a turn was busy has the identical effect on the second capture. The `rowid` tiebreak exists because two captures written in one millisecond are the normal case for a cold-start drain, and `pending()` reads this order as the timeline.
- **Rejected:** draining the oldest and relying on the next start for the rest (the rest are already marked answered, so there is no next start); a `suspend () -> List<ChatMessage>` constructor seam on `ChatController` to make the turn parkable (17+ test call sites construct `ContextBuilder`; a test-only concern must not widen a production API — reverted); dropping the second capture and re-queueing it (the row is already the user turn; a re-send would duplicate it).
- **Accepted residual:** the drain's own sends are serialised through `currentStreamJob`, so a queued capture waits for the previous turn *and* for any turn the user starts in between. That is the intended order, not a bug. `rowid` is not a schema contract — it is only a tiebreak within one append-only table, so it cannot reorder across a redaction. The queue is proven by a RED probe (drop-if-busy → 1 prompt instead of 3) and GREEN (3/3); the tiebreak is proven on device only, because the in-memory fake cannot model a `ts_utc` tie. **Device run 2026-11-08:** full suite `CONNECTED GATE: PASS - 90 test(s), 0 failed, 0 skipped (source: xml)` with `getRecentMessages_TiedTimestamps_ReturnInWriteOrder` passing in 0.737 s. **Not proven:** that the test fails without the tiebreak — the removal probe was blocked twice by environment errors (Kotlin incremental cache, then a UTP file lock from a concurrent run), so the test is GREEN-with and unproven-WITHOUT. A future run should repeat the probe on an idle machine.

- **D-135 (2026-11-08) — Row 20 ships as a read-only Memory tab appended at index 5, loading lazily on first open.** Decision: the v1 slice of blueprint 51 is **Items + Soul + the Inbox count**, rendered from `getActiveNodes(200)` and `getActiveSoulFacts()` with **no model call** (§3.1 rule 1); local-only rows carry a lock mark (rule 6) — **on the Items half only in practice**: `getActiveSoulFacts` already filters `local_only`, so a local-only fact never reaches the screen and the fact lock is currently unreachable from real data (open question recorded in HANDOFF §3/§5). The tab is *appended* to `tabs` (index 5) rather than inserted second as §2 describes, so the positional `when (selectedTab)` and every existing tab-click test keep their indices; a one-shot `LaunchedEffect(selectedTab, state.memoryLoaded)` fires `UiAction.LoadMemory`, and `memoryLoaded` is what makes it one-shot — `UiState` is a new object on every update, so keying on the tab alone would re-read on every state change. Reason: the audit's ordered step 4 is the S-sized read-only slice; Remove/Erase/Trash, editing through the gateway (rules 2–3), Agenda/People/Inbox/Timeline/Learned and the Memory badge are later slices, and no `Storage` method for tombstones exists yet. Rejected: inserting the tab at index 1 (renumbers four existing tabs and every positional test for no user-visible gain in this slice); loading memory inside `refreshUI()` (reads two tables on every chat turn for a screen the user may never open); a new storage query (the existing two already satisfy rule 1). Proof: `MemoryViewerLoadTest` (2 cases) and `MemoryScreenTest` (4 cases); both RED-probed — filtering the load to `status == "active"` fails `MemoryViewerLoadTest.kt:87`, and dropping the `!state.memoryLoaded` guard fails `memoryTab_FirstOpen_AsksForTheLoad_ExactlyOnce`.

- **D-136 (2026-11-08) — `NudgeEngine` is constructed and driven from `ChatController`, not `MainViewModel`; a delivered nudge is a `raw_message` row.** Decision: `ChatController` takes a defaulted `nudgeEngine: NudgeEngine = NudgeEngine(clock)` and gains `UiAction.TickProactive`, which runs `processIgnore` → pick the most-overdue non-local item → `evaluate` → on `Pass` write an assistant `raw_message` (`n_<uuid>`, text `"Still on your list: <title>"`) then `onNudgeSent`. `sendText` calls `processReply` when a thread is open. `MainActivity` fires one tick per foreground session via `LaunchedEffect(Unit)`. The tick's verdict lands in `UiState.nudgeGate` (`sent` / `no_candidate` / `open_thread` / …) so a held nudge is observable on the Debug tab. Reason: the audit's step 2 says "constructed in `MainViewModel`", but the storage, the clock and the `UiState` all already live in `ChatController`; putting the engine in the view model would need a new seam to reach any of them, and the view model's job is to forward actions, not to own the pipeline. Delivery has to be a `raw_message` row because the app renders `state.chatMessages` — `ChatItem.Nudge` and `UiState.chatItems` exist and are computed but are rendered nowhere, so delivering through them would produce a nudge the user never sees. Rejected: constructing in `MainViewModel` (no storage/clock there); rendering `ChatItem.Nudge` (that is the D-121 structured-timeline slice, not this one); a `nudge_log` row as the delivery surface (it is a log, not a chat message); gating the tick on a state key (re-ticks on every `UiState` update).
- **Accepted residual (D-136):** `calculateThreshold` maxes at **30.0** and `NudgeCandidate("overdue_item").baseValue` is **35.0**, so the threshold comparison in `pickNudgeCandidate` can never currently hold a nudge. It is wired and pinned by `tick_ThresholdIsConsulted_NotJustTheGates` at both ends of the formula, but it is **vacuous today** — the real gates are `open_thread`, `min_gap` and the daily fuse. Whether `baseValue` or the threshold formula should move is a product decision, not made here. Also: the tick runs **once per foreground session**, so a nudge is only ever attempted when the app is opened — there is no background trigger in this slice. **`nudgeGate` is a sticky last-verdict, not a live gate state:** every `_state.copy(...)` in `refreshUI`/`openHome` preserves it, so after a `"sent"` tick the Debug tab keeps reading `"sent"` even once the user has answered and the thread is closed. That is deliberate (it answers "what did the last tick decide?"), but it must not be read as "the engine is currently holding this nudge". Two smaller, accepted boundaries: `sendText` calls `processReply` **before** the `while (isChatLoading) job.join()` loop, so two concurrent sends can set `lastContactAt` in reverse order (single-user app, sub-second window); and that `processReply` call swallows exceptions without setting `globalError`, unlike the other `TickProactive` branches — a failed reply-reset leaves the thread open, which the `open_thread` gate will then hold, which is visible on the Debug tab.

- **D-137 (2026-11-09) — Onboarding progress and completion live in `app_meta` settings keys (`onboarding.step`, `onboarding.done`), not in blueprint 52's named `onboarding_state` table; no schema bump.** Decision: `ChatController` persists the step after every advance (`persistOnboardingStep`), and `UiAction.OnboardingFinish` writes the six soul seeds via `createSoulFactAndCommit` and then writes `onboarding.done = "1"` **as the very last write**. `onboardingDone` is read once in `loadInitialState`, so a finished install never renders the flow, not even for a frame. Reason: blueprint 52 §3.1 requires "progress is saved after every step", which is a durability requirement, not a schema requirement; `app_meta` already exists at DB v5 and is already the home of every other small persisted preference (`capture.notification`, `capture.shortcut`, D-133), so a new table would be a migration whose only benefit is the blueprint's table name. Writing the done flag last is what makes a crash mid-seed recoverable: the flow reopens and re-seeds, rather than marking done over a partial Soul. Rejected: a new `onboarding_state` table + migration (schema churn for two scalar values, immediately after the R-series storage hardening); writing `onboarding.done` before the facts (a crash then leaves a done flow with a partial or empty Soul and no way back into the flow); a `Boolean` column on some existing table (same migration cost for one flag). **Accepted residual:** the flow is not resumable at the *answer* level — `onboardingAnswers` is in-memory only, so a process death mid-flow restores the step but not typed text; the user retypes the current step's answers. A local-only health seed is written with `local_only = 1` and `sensitive = 1`, which means `getActiveSoulFacts` filters it out — it is invisible in the Memory tab rather than shown with a lock (the same rule-6 gap D-135 records; open question in HANDOFF §5). Proof: `OnboardingSeedTest` (9 core cases, incl. `finish_WritesTheDoneFlag_AsTheVeryLastWrite`), `OnboardingSeedConnectedTest` (3 device cases on a real `AndroidStorage`), `OnboardingGateTest` (6 Compose cases). RED-probed: dropping the done-flag write reddens 2 core cases; moving it above the fact loop reddens the same 2; replacing the UI gate with `if (false)` reddens 5 of 6 gate cases (`finishedInstall_ShowsTheTabs_AndNoFlow` correctly stays green — it asserts the opposite direction).

(Next free: D-138. Add: date, decision, reason, rejected.)


- **D-138 (2026-11-09) — Offline queue: `chat_turn` rows in `outbox`, a claim-by-re-enqueue window, and a drain triggered on start and app open only.** Decision: a message composed with no network is written to `raw_message` by the caller as always, then `ChatController.enqueueChatTurn` records one `outbox` row (`job_type = chat_turn`, id `ob_<msgId>`, `idem_key = <msgId>`, payload `{"msg_id": …}`). `drainQueuedTurns()` answers every due row in **one** turn whose instruction is injected into the pack as the turn's own message (never stored), and marks the rows done only after the assistant reply is stored. A claimed job is re-enqueued with `next_try_at = now + 60 s` instead of getting a `running` status. The queued mark and the banner are derived on every `refreshUI()` from the pending rows (`UiState.queuedChatIds` / `offlineQueuedText`). The drain runs from `ChatController.init` (after the first frame) and from `MainActivity.onResume`; there is no `ConnectivityManager` callback and no retry timer. Reason: blueprint 41 §3.3 requires jobs to be idempotent with a deterministic key — the message id is that key, so the notification receiver and the startup drain queueing the same capture are one row. The claim-by-re-enqueue keeps the queued mark true across a crash mid-turn and makes a second drain inside the window a no-op, without a `running` state the schema does not have. Injecting the instruction rather than storing it keeps the raw log to words the user actually typed. Deriving the mark satisfies blueprint 11:83 ("delivery state is never a column on `raw_message`"). Rejected: a `running` status (schema churn and a lease timeout to get wrong); storing the batch instruction as a `system` row (it would be read back into every later pack as history); a live network callback (it cannot be unregistered while the process lives, it fires on every Wi-Fi↔mobile handover, and `onResume` already covers every moment the user can see the queue); a stored `queued` flag on the message.
- **Accepted residual (D-138):** the queue is answered **when the app is opened**, not the instant the network returns — a user who reconnects and never reopens the app keeps the messages queued until they do, and there is no WorkManager job or retry timer yet (blueprint 41 §3.4 names both). The banner says "Offline: your messages are saved." even when the device is online again but the provider is down, because it is derived from the queue, not from the connectivity state. `isChatLoading` is deliberately **not** set by `drainQueuedTurns` (the turn clears it before the request is made, which would let a second drain double-send). A `chat_turn` batch that ends in the loop-limit path is marked answered; one whose reply is blank is not, so it is retried. Proof: `OfflineQueueTest` (6 core cases: queue-without-request, one-turn reconnect, still-offline drain, claim window, 14-hour backlog, duplicate enqueue). RED-probed by replacing the offline branch in `sendText` with `if (false)` — 5 of 6 cases red (requests attempted while offline; 4 bodies for one backlog; `bodies.single()` throwing). The claim re-enqueue is **not** red-probed and cannot be with this harness: `OfflineQueueTest` runs the controller on an unconfined dispatcher, so the first drain's turn completes and `markBatchAnswered` settles `ob_<msgId>` inside that same drain (traced: `outbox=[ob_q_1/…/done]` after one drain), leaving a second drain nothing due whether or not the claim exists. The claim is defence-in-depth for the window a test cannot reach — the process stopped after claiming but before the reply is stored — and the claim-window case documents that reasoning rather than proving it. **Not proven on device** — the airplane-mode round trip is blueprint 41 §8 test 1 and has not been run.

(Next free: D-139. Add: date, decision, reason, rejected.)

- **D-139 (2026-11-09) — WAL mode enabled at factory construction (`SupportOpenHelperFactory`), removed from `onConfigure`; test coroutines bound to test supervisor jobs.** Decision: `DatabaseProvider.getHelper` passes `enableWriteAheadLogging = true` directly to `SupportOpenHelperFactory(password, null, true)`, and `db.enableWriteAheadLogging()` is removed from `MineDatabaseCallback.onConfigure`. `OnboardingSeedConnectedTest` scopes controllers to a test `SupervisorJob` cancelled in `@After teardown()`, and `relaunch_AfterFinishing_DoesNotShowTheFlowAgain` explicitly cancels `first`'s job before creating `relaunched`. Reason: calling `enableWriteAheadLogging()` inside `onConfigure` causes SQLCipher to start in DELETE mode and attempt dynamic pool reconfiguration while connection initialization is in-flight, which blocks and logs `PRAGMA journal_mode=delete` lock failures under concurrent access. Furthermore, untracked test coroutines outlived test methods, accessing database files while `@Before setup()` unlinked them, leading to SQLCipher page 1 HMAC check failures (`SQLiteDiskIOException (code 522)`). Pre-enabling WAL on the open helper ensures the initial connection flags carry `ENABLE_WRITE_AHEAD_LOGGING` before the database opens. Rejected: disabling WAL (violates blueprints 10 & 14 for SQLCipher concurrency); leaving coroutine scopes unbounded in connected tests. Proof: device-verified on `moto g73 5G - 14`, full connected suite `CONNECTED GATE: PASS - 103 test(s), 0 failed, 0 skipped (source: xml)`.
- **D-140 (2026-11-09) — Rule 6 for local-only soul facts: `getActiveSoulFacts(includeLocalOnly: Boolean = false)` exposes facts to the Memory viewer with a `🔒` mark, keeping model context builders strictly filtered.** Decision: `Storage.getActiveSoulFacts`, `AndroidStorage.getActiveSoulFacts`, and `SharedFakeStorage.getActiveSoulFacts` gain default parameter `includeLocalOnly: Boolean = false`. Model context builders (`ContextBuilder`, `CheckinBuilder`) use the default `false` (or explicit `false`) to ensure local-only facts are never sent off-device in model prompts. `ChatController.LoadMemory` passes `includeLocalOnly = true`, and `MemoryScreen` renders `🔒` next to any fact where `fact.localOnly == true`, satisfying Blueprint 51 §3.1 Rule 6. Reason: Closes the gap where local-only soul facts (such as sensitive health facts seeded during onboarding or learned locally) were completely hidden from the user in the Memory viewer. Blueprint 51 Rule 6 dictates that local-only items are visible to the user on-device with an explicit lock marker while strictly forbidden from egressing to external LLM providers. Rejected: Exposing local facts in model prompts (violates privacy protocol and Rule 6); keeping local facts hidden from the user in the Memory viewer (violates user transparency and inspection of on-device soul facts). Proof: `MemoryViewerLoadTest.loadMemory_IncludesLocalOnlySoulFacts_WithLockMarkerForViewer` (RED-probed then GREEN), `:core:test` 342/0/0/0, `:app:testDebugUnitTest` 13/0/0/0.
- **D-141 (2026-11-09) — Evening and weekly check-in reflection facts in `CheckinBuilder` (Blueprint 32 §3.4/§3.5, Row 14, P1-E 25).** Decision: `CheckinBuilder` gains `buildEveningFacts()` and `buildWeeklyFacts()`. `buildEveningFacts()` gathers completed items for the logical day via `storage.getRecentlyClosed(now)`, still open backlog and overdue counts via `storage.getActiveNodeCounts(logicalTodayStart)`, top remaining priority items via `storage.getHomePriorityList(now)`, and tomorrow preview via `storage.getNodesDueBetween(logicalTodayEnd, logicalTomorrowEnd)`. `buildWeeklyFacts()` gathers recent completions and open backlog. All methods strictly filter out `localOnly` items, adhering to Rule 6 and privacy protocols. Reason: Fulfills Blueprint 32 requirements for evening briefings ("Done today", "Still open", "Tomorrow preview") and weekly reviews, providing factual agenda context while preventing external model hallucination of tasks or dates. Rejected: Relying on the model to calculate completion counts or remember past tasks; leaking local-only nodes into briefings. Proof: `CheckinBuilderTest` 4 core unit cases (`buildMorningFacts`, `buildEveningFacts`, local-only exclusion, and `buildWeeklyFacts` — all verified RED then GREEN).
- **D-142 (2026-11-09) — FTS5 multi-token AND matching in `SharedFakeStorage` & `Embedder` core contract (Blueprint 21 §3.1, P1-E 23).** Decision: `SharedFakeStorage.searchMemoryFts` and `searchHistory` tokenize queries via `Regex("\\s+")` after non-alphanumeric sanitization and require `tokens.all { ... }` across nodes, soul facts, and message content, matching `AndroidStorage`'s SQLite FTS5 `AND` semantics. Defined `Embedder` and `QuantVector` core interfaces in `com.anchor.core.retrieval` without platform dependencies. Reason: Eliminates discrepancy where `SharedFakeStorage` previously only matched exact contiguous substrings, causing multi-token or out-of-order queries to fail on the test fake while succeeding on device SQLite. Preserves core module purity while establishing the contract for on-device embeddings. Rejected: Bringing in LiteRT/TFLite dependencies into `:core` (violates core purity); keeping substring-only matching in `SharedFakeStorage`. Proof: `SearchFtsParityTest` 3/3 cases (RED then GREEN), `:core:test` 349/0/0/0, `:app:testDebugUnitTest` 13/0/0/0.

## D-143 (2026-11-10) — Monotonic day_key, reply day_key inheritance from cause message, and Gradle test stability (Blueprint 50 §3.13, P1-E 21).
- **Decision:**
  1. `Storage.insertRawMessage` gains `causeMsgId: String? = null` and `dayKey: String? = null` parameters (with backward-compatible 3-arg overload).
  2. In `AndroidStorage` and `SharedFakeStorage`, when `causeMsgId` is provided (e.g. streaming assistant reply), `day_key` is inherited directly from the cause message (`raw_message.day_key`), guaranteeing that a reply streaming across midnight / rollover stays anchored to the day the user spoke.
  3. Monotonicity: when computing a new `day_key`, `day_key` is bounded to be >= the previous message's `day_key`, preventing clock regression or timezone shifts from moving chat history backward in the timeline.
  4. Test interception parity: `SharedFakeStorage` routes 5-parameter insertions through its 3-parameter `insertRawMessage(id, role, content)` via `currentDayKeyOverride`, ensuring anonymous test subclass hooks (such as `R12ConcurrencyLifecycleTest`) are triggered reliably without latch hangs.
  5. Build stability: pinned `org.gradle.java.home` to JDK 17 in `gradle.properties` and added `testLogging` in `core/build.gradle.kts` to eliminate Java 25 daemon IPC mismatch.
- **Reason:** Blueprint 50 §3.13 explicitly specifies: "Day rollover while a reply is streaming: The reply belongs to the day of the message that triggered it (day_key is copied from the cause message)" and "day_key is monotonic: a new message's day_key is never earlier than the previous message's. This protects against clock changes and travel."
- **Rejected:** Recomputing day_key at stream completion time (causes assistant reply to land in the next day's bucket when streaming across midnight); relying on external clock stability without monotonic enforcement.
- **Proof:** `DayKeyMonotonicityTest` (3/3 core unit cases: clock regression non-regression, cause message day_key inheritance across rollover, explicit day_key honoring; RED-first then GREEN). Full `:core:test` 352/0/0/0, `:app:testDebugUnitTest` 13/0/0/0, BUILD SUCCESSFUL in 30s.

## D-144 (2026-11-10) — CheckinBuilder dynamic time-of-day dispatch, offline narration template, and ContextBuilder integration (Blueprint 32 §3.1-§3.5, P1-E 25).
- **Decision:**
  1. `CheckinBuilder` gains `buildCurrentFacts(now: Long = clock.now())` routing dynamically to `buildEveningFacts()` when `currentMin >= dayRule.endMinute - 90 min` (or pre-rollover night hours), `buildWeeklyFacts() + buildEveningFacts()` on Sunday evenings before Monday rollover, or `buildMorningFacts()` during morning/daytime hours.
  2. `CheckinBuilder` gains `buildTemplateNarration(facts: String)` providing deterministic, offline conversational text summaries of fact blocks with persona phrasing per Blueprint 32 §3.2.
  3. `ContextBuilder.kt` line 105 calls `checkinBuilder.buildCurrentFacts(now)` instead of hardcoded `buildMorningFacts()`.
- **Reason:** Fulfills Blueprint 32 briefing lifecycle requirements and eliminates prompt-context hallucinations caused by morning agenda facts injected during late-night sessions.
- **Rejected:** Separate independent morning/evening builder classes (adds boilerplate for tightly-coupled briefing queries); model-only narration without deterministic offline fallback.
- **Proof:** `CheckinBuilderTest` (8/8 core unit tests passed: morning, evening, Sunday evening weekly review, local-only exclusion, and offline persona narration; RED-first then GREEN).

## D-145 (2026-11-10) — ReliabilityManager systematic score evaluation, p95 latency telemetry, sweeper heartbeat staleness, and startup gap detection (Blueprint 42 §3.4/§3.6/§3.8, P1-E 27).
- **Decision:**
  1. Reliability score standardized as "healthy" | "at_risk" | "degraded" (preserving `UiState.atRisk` compatibility while supporting BP 42's 3 tiers).
  2. `Storage`, `AndroidStorage`, and `SharedFakeStorage` gain `getRecentReminderFires(limit: Int = 20)`.
  3. `ReliabilityManager` gains `computeP95LatencyMs(sampleSize: Int = 20)` calculated from `reminder_fire.latency_ms`.
  4. `ReliabilityManager` gains `evaluateReliabilityScore()` which sets "degraded" on missed fires, "at_risk" if p95 latency > 2 min (120,000ms), sweeper heartbeat is stale (> 45 min), or active scheduling errors exist, and "healthy" otherwise.
  5. `ReliabilityManager` gains `detectStartupGap()` which detects force-stop gaps when sweeper heartbeat is stale (> 45 min) and pending reminders are overdue.
- **Reason:** Makes reliability metrics truthfully measured and observable rather than relying on ad-hoc reconcile exception catches.
- **Rejected:** Color string codes "green"|"amber"|"red" in database (breaks existing D-114/D-121 code contracts expecting "at_risk"); unbounded latency calculation across entire database history.
- **Proof:** `ReliabilityManagerTest` (11/11 core unit tests passed: p95 latency calculation, degraded score on missed reminders, at_risk on latency/heartbeat staleness, healthy on clean pass, force stop startup gap detection; RED-first then GREEN).

## D-146 (2026-11-10) — Memory Viewer soft-delete and archive actions with undo receipts (Blueprint 51 §3.1/§3.3, P1-E 30).
- **Decision:**
  1. `ChatContract.kt` gains `UiAction.ArchiveNode(nodeId)` and `UiAction.DeleteNode(nodeId)`.
  2. `Gateway` implements `handleArchiveNode(nodeId)` (sets status "archived", cancels reminders, logs RawEvent "ARCHIVE_NODE") and `handleDeleteNode(nodeId)` (sets status "cancelled", cancels reminders, logs RawEvent "DELETE_NODE") with before/after state snapshots.
  3. `ChatController` handles both actions by invoking Gateway, adding an undoable transient `ReceiptState`, and refreshing memory items.
  4. `MainActivity.kt` `MemoryScreen` provides "Archive" and "Delete" actions on each memory node item with backward-compatible default onAction parameter.
- **Reason:** Blueprint 51 §3.1 requires that all memory viewer mutations flow through the gateway with full undoability, without calling external models.
- **Rejected:** Hard-deleting rows (violates D-002 and Blueprint 51 Rule 4); direct database updates bypassing Gateway and RawEvent operation log.
- **Proof:** `MemoryViewerLoadTest` (5/5 core unit tests passed: archiving removes item from viewer with undo receipt, undo restores to active; deleting cancels item with undo receipt, undo restores to active; local-only facts preserved with lock; RED-first then GREEN).

## D-147 (2026-11-10) — Behavioral learning engine, signal_log, learned_pref, decay, exploration, and reset (Blueprint 25 §1-§5, P1-E 26).
- **Decision:**
  1. `SignalLog` and `LearnedPref` models created in `:core`; `Storage`, `AndroidStorage`, and `SharedFakeStorage` implement `recordSignal`, `getSignals`, `getLearnedPref`, `getAllLearnedPrefs`, `saveLearnedPref`, and `resetLearning`.
  2. `LearningEngine` implemented in `:core` (`com.anchor.core.learning`):
     - Binary response rates (e.g. nudge reply vs. ignore) modeled via Beta distribution ($\alpha, \beta$) with Laplace prior ($\alpha_0=1, \beta_0=1$).
     - Exponential decay applied with default 60-day half-life ($w_i = 2^{-\Delta t / T_{1/2}}$) clamped to $\Delta t \ge 0$ against clock skew.
     - Minimum samples threshold ($N = 8$) enforced: under 8 samples, confidence = 0.0 and defaults apply.
     - Exploration rate ($10\%$) implemented via `shouldExplore()` to prevent echo chambers and local minima.
     - User-locked preferences (`locked = true`) preserved across recompute and reset.
     - Reset epoch recorded in `app_meta` (`learning_reset_epoch`) to prevent resurrection of cleared preferences on subsequent recompute.
     - Plain-English explainability (`explainPreference`, `explainWhy`) formatting response rates, snooze habits, and trigger rationales.
  3. `ChatController` wired: records `SIGNAL_NUDGE_IGNORED` when backoff increments in `TickProactive`; records `SIGNAL_NUDGE_REPLY` when user answers an open thread in `sendText`; handles `UiAction.WhyNudge`, `LockLearnedPref`, and `ResetLearning`; surfaces `learnedPrefs` and `whyNudgeText` in `UiState`.
- **Reason:** Fulfills Blueprint 25 §1 ("all learning is visible, explainable, bounded and resettable", "plain statistics in code, no LLM") and provides a concrete feedback loop from user responses into proactive timing.
- **Rejected:** LLM calls for behavioral learning (violates Blueprint 25 §1); unbounded learning weights without decay; silent preference mutations without user explainability.
- **Proof:** `LearningEngineTest` (13/13 passed) and `ProactiveLearningIntegrationTest` (4/4 passed). Full unit suite: 382 core, 13 app tests passing in 6s.

## D-148 (2026-11-10) — Rebuild jobs skeleton, anchor preservation, and layer validation (Blueprint 11 §4-§5, P1-E 22).
- **Decision:**
  1. `RebuildContracts.kt` defines `RebuildTarget` (`DERIVED_NODES`, `DERIVED_EDGES`, `EMBEDDINGS`, `FTS`, `FULL`), `RebuildStatus` (`IDLE`, `PLANNING`, `RUNNING`, `VALIDATING`, `COMPLETED`, `FAILED`, `PAUSED`), `RebuildPlan`, `RebuildProgress`, and `RebuildValidationResult`.
  2. `RebuildManager` implemented in `:core` (`com.anchor.core.rebuild`):
     - `planRebuild`: calculates raw message count and identifies anchor rows where `origin == "approved" || origin == "user_edit"`.
     - `validateAnchors`: strictly verifies that approved and user-edited nodes are preserved intact across any rebuild candidate (enforcing D-028 and Blueprint 11 §4 Rule 4: a rebuild NEVER overwrites anchors).
     - `validateIntegrity`: detects orphan edges (edges whose endpoints do not exist) and blank titles before allowing atomic swap.
     - Progress tracked in `app_meta` keys (`rebuild.status`, `rebuild.target`, `rebuild.processed`, `rebuild.total`, `rebuild.last_error`) ensuring resumability across process death.
     - `resumeOrDiscard`: automatically pauses an interrupted rebuild on app restart so the user or background job can cleanly resume or discard without corrupting derived state.
  3. `Node` data class in `Memory.kt` gains `val origin: String = "user_edit"` with default value preserving 100% backward compatibility for all existing call sites.
- **Reason:** Separates the append-only raw layer from derived projections and guarantees that user edits and approved AI extractions are never wiped out during background re-extractions or model upgrades.
- **Rejected:** Overwriting user edits during re-extraction (violates D-028 and core trust principle); in-memory progress tracking that loses rebuild position on process kill.
- **Proof:** `RebuildManagerTest` (4/4 passed: plan generation, anchor corruption detection, orphan edge integrity validation, and progress persistence across simulated process death). Full unit suite: 386 core, 13 app tests passing in 7s.

## D-149 (2026-11-10) — Personas, Core Rules Layering & Tone Safety (Blueprint 24, P1-E 29) and Offline Outbox & Template Engine (Blueprint 41, P1-E 28).
- **Decision:**
  1. `Persona.kt` and `PersonaPresets.kt` define 5 standard presets (`COMPANION`, `COFOUNDER`, `COACH`, `CHILL_FRIEND`, `MINIMAL`) with `findById(id, customJson)` support.
  2. `PersonaEngine.kt` implements prompt layering: Layer 1 Core Rules (privacy, safety, deterministic tool use), Layer 2 Persona style & tone rules, Layer 3 Soul facts communication style injection. Added `detectDistress(text)` tone safety override: distress/crisis automatically disables persona banter/coaching and forces calm, direct, supportive demeanor.
  3. `TemplateEngine.kt` created in `:core/persona` supporting all 8 triggers (`REMINDER_START`, `REMINDER_CHECK`, `REMINDER_DEADLINE`, `NUDGE_OVERDUE`, `NUDGE_STALE`, `CHECKIN_MORNING`, `CHECKIN_EVENING`, `CHECKIN_WEEKLY`), 5 persona tones, slot interpolation, and `Math.floorMod` index rotation.
  4. `OutboxJob` extended with dropped status and priority classes. `Storage.dropStaleOutbox(now)` implemented across `SharedFakeStorage` and `AndroidStorage`.
- **Reason:** Blueprint 24 §1 requires clean separation of invariant safety rules from persona variations, and Blueprint 41 requires reliable offline communication without remote LLMs.
- **Rejected:** Hardcoding persona prompts into the chat envelope; allowing personas to override core security or tone rules during distress; discarding outbox jobs silently without a dedicated dropped status.
- **Proof:** `PersonaEngineTest` (5/5), `PersonaIntegrationTest` (4/4), `TemplateEngineTest` (6/6), `OutboxStaleTest` (1/1) all passed.

## D-150 (2026-11-10) — Settings Catalog Backend (Blueprint 52 §3.4, P1-E 31) and Context Pack Compact Mode Budgeting (Blueprint 20 §3.4, P1-E 32).
- **Decision:**
  1. `SettingSection.kt`, `SettingDef.kt`, `SettingsCatalog.kt`, and `SettingsManager.kt` created in `:core/settings`. Authoritative definitions, types, defaults, and validators for all 12 Blueprint 52 sections. Section reset (`resetSectionToDefaults`) strictly restricted to sections 4-8 per Blueprint 52 §3.4.
  2. `ContextBuilder.kt` gains `compactMode` parameter (budget reduction: Active nodes 700 -> 300 tokens, Soul facts 1250 -> 625 chars, Recent chat 1500 -> 600 tokens). Minimum 6-message preservation guarantee for non-oversized messages, while preserving the H3 invariant that oversized messages do not crowd out normal messages.
- **Reason:** Centralizes app configuration truth in code with type-safe validation, and ensures tight prompt budgeting under memory or network constraints.
- **Rejected:** Unbounded settings keys; allowing section resets on destructive categories (e.g. data or cloud sync) without explicit safeguards.
- **Proof:** `SettingsCatalogTest` (5/5) and `ContextBuilderBudgetTest` (3/3) passed.

## D-151 (2026-11-10) — Resolver Scoring Scale & Unicode Normalization (Blueprint 22 §3.3/§3.4, P1-E 33).
- **Decision:**
  1. Candidate scoring in `Resolver.kt` normalized to a strict 0.0..1.0 scale with weights aligned to Blueprint 22 §3.4 (Text 0.35, Time 0.25, Recency 0.20, Status fit 0.10, Type fit 0.10). Active signals are dynamically normalized so scores accurately reflect confidence across missing features.
  2. Candidate pool filters open/active nodes first for closing intents (`CANCEL`, `COMPLETE`) per Blueprint 22 §3.3.
  3. Added `recentlyDiscussedNodeIds: List<String> = emptyList()` default parameter for coreference tie-breaking.
  4. Standardized tokenization on `Locale.ROOT` and expanded command word and stopword filtering while preserving the exact title match fast-path and H-14 single-candidate chip guarantee.
- **Reason:** Blueprint 22 §3.4 defines unambiguous code-level disambiguation preventing accidental auto-cancellations or model-hallucinated similarity edits.
- **Rejected:** Unbounded integer score boosts; locale-dependent `Locale.getDefault()` lowercasing.
- **Proof:** `ResolverScaleAndUnicodeTest` (5/5), `WhichDinnerTest` (12/12), `ResolverSingleLowMatchChipTest` (2/2) all passed.

## D-152 (2026-11-10) — Automated Security & Privacy Scenarios E-15/E-16 Audit Harness (Blueprint 93 §3.2, P1-E 34).
- **Decision:**
  1. `PromptInjectionE15Test.kt` created covering Scenario E-15: direct injection prompts, notification quick-reply injection, delimiter escape attempts (`</data><data>`), and non-destructive execution.
  2. `PrivacyAuditE16Test.kt` created covering Scenario E-16: multi-turn capturing proxy intercepting all wire requests, validating zero API key leaks, zero `local_only` leaks, proper delimiter enclosure (`<data>...</data>`), and alias mapping enforcement.
- **Reason:** Satisfies release gate X-01 end-to-end automation requirements for privacy and injection safety.
- **Rejected:** Manual-only audit tests; relying on LLM self-reporting for privacy boundaries.
- **Proof:** `PromptInjectionE15Test` (3/3), `PrivacyAuditE16Test` (1/1), `PrivacyAndInjectionTest` (5/5) all passed in `:core:test`.

## D-153 (2026-11-10) — App Lock UI Gate, Security Controls, About & Updates (Blueprint 16 §3.2, Blueprint 52 §3.4, Blueprint 90 Row 1 & 22, P1-E 35).
- **Decision:**
  1. `UiState` and `UiAction` in `:core` extended with:
     - `appLockEnabled`, `isAppLocked`, `relockTimeoutSec`, `blockScreenshots`, `compactMode`.
     - `UnlockApp`, `LockApp`, `SetAppLock`, `SetRelockTimeout`, `SetBlockScreenshots`, `SetCompactMode`.
  2. `ChatController` handles state transitions and setting persistence (`privacy.app_lock`, `privacy.relock_timeout_sec`, `privacy.block_screenshots`, `ai.compact_mode`). On cold start, if `privacy.app_lock == "1"`, the app initializes in the locked state (`isAppLocked = true`).
  3. App lock enforces D-030 invariant: it is strictly a UI gate and does NOT bind SQLCipher database key encryption to user authentication. Background alarms, sweepers, boot reconciler, and notifications continue uninterrupted while the UI is locked.
  4. `MainActivity` integrates native `android.hardware.biometrics.BiometricPrompt` with `BIOMETRIC_STRONG or DEVICE_CREDENTIAL` (API 34 native, zero new external dependencies), observes `blockScreenshots` to toggle `WindowManager.LayoutParams.FLAG_SECURE` (protecting screenshots and task switcher recents preview), and checks background duration on `onResume` against `relockTimeoutSec`.
  5. `LockScreen` composable prevents access to all tabs and private data when locked.
  6. `DebugScreen` expanded to expose Privacy & Security controls (App lock toggle, relock timeout chips [0s, 60s, 300s, 1800s], screenshot blocking toggle), AI & Performance (Compact mode toggle), About Mine metadata (app version, build code, schema 6, export format 1, GPL-3.0-or-later license, and "Check for updates" button), and storage stats.
- **Reason:** Satisfies Blueprint 90 Row 1 (App lock UI gate) and Row 22 (About & updates) requirements while guaranteeing privacy and physical device safety.
- **Rejected:** Tying database encryption to biometric auth (would break background alarms and boot reconciliation); adding `androidx.biometric:biometric` dependency (redundant since `minSdk = 34`).
- **Proof:** `AppLockStateTest` (8/8 in `:core:test`), `AppLockGateTest` and `DebugScreenTest` (in `:app` androidTest), `:app:compileDebugAndroidTestKotlin` and `:app:testDebugUnitTest` green.

## D-154 (2026-11-10) — Outbox Deadline Null Preservation (Batch 1 Hardening, B1-01).
- **Decision:** In `AndroidStorage.kt` `insertOutboxJob`, pass `job.deadlineAt` directly as `null` or epoch timestamp via `arrayOf<Any?>`, instead of coercing `null` to `""`. In `toOutboxJob()`, parse empty or null column 8 as `null`.
- **Reason:** Writing `""` to an INTEGER column caused SQLite / type converters to coerce empty strings to `0L` (epoch 1970), causing `dropStaleOutbox` to erroneously drop jobs with indefinite deadlines immediately.
- **Rejected:** Treating `0L` as indefinite deadline (conflicts with Blueprint 41 outbox contract where `deadline_at IS NULL` represents non-expiring jobs).
- **Proof:** `AndroidStorageTest.testOutboxDeadlineAt_NullPreservedThroughStorage` passing.

## D-155 (2026-11-10) — GlobalError Boundary & Transient Receipt Isolation (Batch 1 Hardening, B1-03, B1-05).
- **Decision:** Reserve `UiState.globalError` strictly for unrecoverable startup/database open failures. Background ticks (`TickProactive`, `DrainQueuedTurns`, `SaveProvider`, `LoadMemory`) and all UI actions in `ChatController.kt` catch non-fatal exceptions, emit structured `Receipt(kind = "error", ...)` into `transientReceipts`, and refresh UI without setting `globalError`. In `loadInitialState()`, wrap `queuedChatIds()` in `runCatching` so an outbox query failure cannot brick initial startup.
- **Reason:** Previously, any transient network, LLM, or outbox error during background ticks painted the entire app in full-screen red error state, locking the user out of all 7 tabs.
- **Rejected:** Silent swallow of errors; full app bricking for non-startup failures.
- **Proof:** `TitaniumBatch1Test.testBackgroundFailure_DoesNotSetGlobalError` and `testUiAction_StorageException_EmitsErrorReceiptWithoutCrash` passing.

## D-156 (2026-11-10) — Composer Send Offline Enqueue Boundary (Batch 1 Hardening, B1-04).
- **Decision:** In `ChatController.kt:1348`, when an `OfflineException` occurs mid-flight or at the start of sending a composer message, check `activeUserMsgId != null` instead of `existingUserMsgId != null` when determining whether to write an outbox turn.
- **Reason:** A newly typed message in the composer has `existingUserMsgId == null`. If connectivity dropped during stream initiation, the previous code skipped enqueueing it to the outbox, dropping the user's message silently.
- **Rejected:** Forcing the user to manually retype or copy-paste their message when connectivity fluctuates.
- **Proof:** `TitaniumBatch1Test.testComposerSend_DropsOfflineMidStream_EnqueuesToOutbox` passing.

## D-157 (2026-11-10) — NudgeEngine Day Rollover & Locale Parsing Hardening (Batch 2 Hardening, B2-06, B2-07).
- **Decision:**
  1. `NudgeEngine.evaluate` and `onNudgeSent` accept `currentDayKey: String? = null`. When `currentDayKey != state.dayKey`, `sentToday` is reset to 0 before evaluating limits.
  2. `DateResolver.kt` duration parsing lowercases candidate text using `Locale.ROOT` instead of `Locale.getDefault()`.
- **Reason:** Without day rollover reset, `sentToday` accumulated monotonically until hitting the 40-nudge hard fuse, permanently disabling proactive nudges forever. In Turkish (`tr-TR`) and Azerbaijani (`az-AZ`) locales, `Locale.getDefault().lowercase()` turns `I` into dotless `ı`, breaking `"minutes"` / `"mınutes"` parsing.
- **Rejected:** System clock-dependent day checks without explicit `dayKey`; relying on default device locale for internal keywords.
- **Proof:** `TitaniumBatch2Test.testNudgeEngine_DayRollover_ResetsSentToday` and `testDateResolver_TurkishLocale_ParsesRelativeDurations` passing.

## D-158 (2026-11-10) — High-Frequency SQLite Indexes & FTS Migration Rebuild (Batch 3 Hardening, B3-12, B3-13).
- **Decision:**
  1. Add 5 performance indexes to `MineDatabaseHelper.kt`: `idx_reminder_node_id ON reminder(node_id)`, `idx_node_status_closed ON node(status, closed_at)`, `idx_soul_fact_status ON soul_fact(status)`, `idx_signal_log_kind_ts ON signal_log(kind, ts_utc)`, and `idx_proposal_status_created ON proposal(status, created_at)`.
  2. In `MineDatabaseHelper.kt:onUpgrade`, execute `INSERT INTO <fts>(<fts>) VALUES('rebuild')` for all external-content FTS tables (`node_fts`, `msg_chunk_fts`, `soul_fact_fts`).
- **Reason:** Queries filtering active nodes, soul facts, reminder lookup by node ID, and signal logs perform full table scans without indexes. External FTS content tables can lose synchronization across table rebuilds during schema migrations.
- **Rejected:** Unindexed foreign key lookups; skipping FTS index rebuilds during migration.
- **Proof:** `DatabaseMigrationTest.testDatabaseMigration_V5toV6_CreatesOutboxTableAndIndex` passing.

## D-159 (2026-11-11) — Titanium Punch-List Time-Bombs Remediation (TB-1 through TB-6).
- **Decision:**
  1. **Full UUID Entropy (TB-1):** Removed all `.take(6)` and `.take(8)` truncations on `UUID.randomUUID()` across `Gateway.kt`, `ChatController.kt`, `AlarmReceiver.kt`, `ReliabilityManager.kt`, and `DayRollover.kt`.
  2. **Atomic Outbox Claim (TB-2):** Added `Storage.claimOutbox(id, nextTryAt, now)` executing `UPDATE outbox SET next_try_at = ?, updated_at = ? WHERE id = ?`. Replaced buggy `enqueueOutbox` (which used `INSERT OR IGNORE` and silently dropped claims).
  3. **Outbox Tool-Only Reply Drain (TB-3):** In `ChatController.kt`, call `markBatchAnswered(batchIds)` when `hasCommitted && batchIds.isNotEmpty()` even when generic assistant replies like `"Done!"` are suppressed.
  4. **Live DB Rebind on Backup Import (TB-4):** In `AndroidExportManager.kt` and `AndroidStorage.kt`, rebind `MineApp.sharedStorage` and `AndroidStorage.rebind(newHelper)` after file swap or rollback, preventing `attempt to re-open an already-closed object`.
  5. **Single Unified System Prompt (TB-5):** Consolidated static tool instructions, temporal authority, and `PersonaEngine` system prompt into `ContextBuilder`'s single system message. Read `provider.rpm_default` setting in `ChatController.kt`.
  6. **Safe Fallback DB Helper Close (TB-6):** Enclosed fallback database helpers in `AlarmReceiver.kt` and `BootReceiver.kt` within `try ... finally { db.close() }` blocks.
- **Reason:** Eliminates silent time bombs: 16-bit Birthday paradox ID collisions, double-send outbox drain race conditions, outbox amnesia loops on tool calls, SQLite closed-handle app bricking, and native file descriptor leaks.
- **Rejected:** Suppressing exceptions on closed DB; keeping two separate system messages per turn.
- **Proof:** `TitaniumPunchListTest` (6/6 in `:core:test`), `AlarmReceiverGuardTest` in `:app:testDebugUnitTest`.

## D-160 (2026-11-11) — Memory Erase & 30-Day Trash Lifecycle (Feature Gap 2, Gate E-12).
- **Decision:** Added `getTrashNodes(now, limit)` and `eraseNode(nodeId)` to `Storage`, `AndroidStorage`, and `SharedFakeStorage`. Added `handleRestoreNode` and `handleEraseNode` to `Gateway`. Added `UiAction.RestoreNode` and `UiAction.EraseNode` to `ChatContract`, and exposed `trashNodes` in `UiState`.
- **Reason:** Blueprint 51 §3.2 and Gate `E-12` require soft-deleted items to remain recoverable for 30 days (`archived_at >= now - 30 days`), and permanent `Erase` to scrub content and edges from SQLite, FTS, and memory.
- **Rejected:** Permanent deletion on first archive action without trash recovery period.
- **Proof:** `TitaniumPunchListTest.testFeatureGap2_TrashAndErase` passing.

## D-161 (2026-11-11) — RateLimiter Cooldown Persistence Across Process Death (Feature Gap 3, Gate X-07).
- **Decision:** Created `RateLimitPersistence` and `StorageRateLimitPersistence(storage)` storing 429 backoff timestamps in `app_meta` under keys `ratelimit.cooldown.<targetKey>`. Wired into `RateLimiter` secondary constructor and initialized in `MainViewModel`.
- **Reason:** Gate `X-07` requires 429 cooldowns to survive process termination, preventing immediate API hammering on app restart.
- **Rejected:** In-memory-only maps without durable storage.
- **Proof:** `TitaniumPunchListTest.testFeatureGap3_RateLimiter429SurvivesProcessDeath` passing.

## D-162 (2026-11-11) — Direct-Boot Alarm Safety Net (Roadmap P1.1 Item 2, Blueprint 42).
- **Decision:** Implemented `DirectBootAlarmStore` in Device-Protected (DE) storage (`direct_boot_alarms.db`). Registered `AlarmReceiver` and `BootReceiver` with `android:directBootAware="true"` and `Intent.ACTION_LOCKED_BOOT_COMPLETED`.
- **Reason:** Blueprint 42 requires scheduled alarms to ring even if the device restarts before the user enters their PIN or passcode (Direct-Boot locked state).
- **Constraint (Invariant 1):** DE database stores ZERO private text (only generic `"Reminder"` label, `fire_at` timestamp, `tier`). Full decrypted reminders remain protected inside Credential-Encrypted (CE) SQLCipher.
- **Proof:** `DirectBootSafetyNetTest` passing on physical device moto g73 5G.

## D-163 (2026-11-11) — Recurrence Rules & Habit Streaks Schema Migration (V6 -> V7, v1.1 Item 3).
- **Decision:** Bumped `DATABASE_VERSION` from 6 to 7. Idempotently migrated `node` table to add `recurrence_rule` (TEXT), `streak_current` (INTEGER NOT NULL DEFAULT 0), `streak_best` (INTEGER NOT NULL DEFAULT 0), and `last_completed_at` (INTEGER). Migrated `reminder` table to add `rrule` (TEXT) and `iteration` (INTEGER NOT NULL DEFAULT 0). Created index `idx_node_habit ON node(type, streak_current)`. Implemented pure-Kotlin `RecurrenceEngine` in `:core` and wired habit completion in `Gateway`.
- **Reason:** Blueprint 13 & 90 require habits and recurring reminders to be first-class citizens. Completing a habit calculates the next occurrence (> `now`), advances the streak (with 36h grace window for daily habits), and keeps the habit active instead of terminating it.
- **Rejected:** Keeping recurrence solely inside serialized JSON `attrs`, which prevented indexing and efficient streak queries.
- **Proof:** `RecurrenceEngineTest` (9/9 pass in `:core`), `DatabaseMigrationTest.testDatabaseMigration_V6toV7_AddsRecurrenceAndStreakColumns` passing on device.

## D-164 (2026-11-11) — Offline Speech Pipeline Integration (Roadmap v1.1 Item 4, Blueprint 60).
- **Decision:** Integrated on-device speech processing runtime (`com.k2fsa.sherpa.onnx:sherpa-onnx:2.0.1` AAR) in `:app/src/main/java/com/anchor/mine/voice/`:
  1. `SherpaVoiceEngine` encapsulating Silero VAD (`Vad`), streaming Zipformer transducer ASR (`OnlineRecognizer`), and Kokoro TTS (`OfflineTts`).
  2. Fixed sherpa-onnx v2.0 Kokoro tokenization by providing `lexicon-us-en.txt` for grapheme-to-phoneme mapping.
  3. `SherpaVoiceEngine.cleanTextForSpeech` strips code fences, markdown, and XML delimiters before synthesis per Blueprint 60 §3.3.
  4. Audio playback via Android `AudioTrack` with `AudioFormat.ENCODING_PCM_FLOAT` at 24000 Hz.
  5. Kept `:core` strictly pure Kotlin (no Android audio or JNI framework dependencies). Added `RECORD_AUDIO` to `AndroidManifest.xml`.
- **Reason:** Blueprint 60 requires offline, on-device voice transcription and speech output without network dependencies.
- **Rejected:** Runtime compilation of sherpa-onnx from C++ source (AAR provides pre-compiled arm64/x86 JNI binaries); committing model weights or AAR binaries to git (`*.aar` and `models/` git-ignored).
- **Proof:** `SherpaVoiceEngineTest` (5/5 unit tests pass), `SherpaVoiceEngineDeviceTest` passing on physical device `moto g73 5G - Android 14`.

## D-165 (2026-11-11) — Multi-Target Fallback Chain in ChatController (Roadmap v1.1 Item 5, Blueprint 40).
- **Decision:** In `ChatController.kt` (line 1288), replaced hardcoded single-element `listOf(target)` with a dynamic fallback chain:
  `chain = [Primary Target, Fallback Target(s)]` configured from settings (`ep`, `key`, `provider.rpm_default`, and `provider.fallback.endpoint`, `provider.fallback.key`, `provider.fallback.rpm`).
  Registered fallback settings in `SettingsCatalog.kt`.
- **Reason:** Blueprint 40 §3 requires slots to have ordered fallback chains. When the primary provider encounters a 429 rate-limit, 5xx error, or connection failure, `ProviderOrchestrator` automatically retries across the secondary fallback targets in the chain rather than failing the turn or dropping queued messages.
- **Rejected:** Hardcoding a single target list; looping manually in the UI layer (orchestrator handles chain navigation and buffer clears).
- **Proof:** `ProviderFallbackChainTest` (1/1 pass in `:core:test`), verified transparent fallback to secondary endpoint on 429.

## D-166 (2026-11-11) — Direct-Boot Post-Unlock Reconciliation & DE Store WAL Singleton.
- **Decision:** Added `direct_boot_fired` table to DE storage (`direct_boot_alarms.db`) to record alarms fired while device was locked. Created `DirectBootReconciler` in `:core` to retire pending reminders and record `ReminderFire` (`delivered`) in decrypted CE SQLCipher upon user unlock, preventing duplicate missed alerts from `ReliabilityManager.runSweeper()`. Configured `DirectBootAlarmStore` with WAL mode and singleton open helper.
- **Reason:** Reminders fired in direct boot could not write to CE storage while encrypted. On first unlock, sweeper treated overdue reminders as missed, emitting duplicate "Missed: Reminder" notifications.
- **Rejected:** Skipping sweeper on unlock (sweeper must still catch reminders missed while the device was completely powered off).
- **Proof:** `DirectBootReconcilerTest` (2/2 pass in `:core`), `DirectBootSafetyNetTest` in `app/src/androidTest`.

## D-167 (2026-11-11) — RateLimiter Monotonic Clock Isolation & Cooldown Sanity.
- **Decision:** Decoupled sliding-window RPM rate-limiting from wall-clock shifts using monotonic clock (`SystemClock.elapsedRealtime()`). Implemented a 1-hour maximum ceiling (`MAX_COOLDOWN_MS = 3600_000L`) on persisted 429 cooldowns, automatically discarding corrupted or clock-skewed timestamps that would otherwise lock out the user.
- **Reason:** System wall-clock jumps backwards (NTP sync/timezone) caused sliding window stamps to freeze for minutes; forward clock jumps persisted multi-year bans in `app_meta`.
- **Rejected:** Storing monotonic clock timestamps across process reboots (elapsedRealtime resets on device reboot; cooldown persistence requires wall-clock with sanity clamping).
- **Proof:** `RateLimiterTest` (3/3 pass in `:core`).

## D-168 (2026-11-11) — Non-Destructive Column Updates in AndroidStorage.
- **Decision:** Replaced `INSERT OR REPLACE INTO soul_fact` and `INSERT OR REPLACE INTO reliability_state` with parameterized `UPDATE ... WHERE id = ?` and fallback `INSERT`.
- **Reason:** SQLite `INSERT OR REPLACE` deletes and recreates rows, resetting omitted schema columns (`attrs`, `pinned_core`, `valid_from`, `valid_to`, `last_force_stop_detected`) to NULL/defaults on every update.
- **Rejected:** Adding dummy values to `INSERT OR REPLACE` (would still overwrite existing DB attributes with blank defaults).
- **Proof:** `AndroidStorageTest.testSaveSoulFact_PreservesMetadataColumns_AttrsAndPinnedCore` and `testSaveReliabilityState_PreservesLastForceStopDetected`.

## D-169 (2026-11-11) — Prompt-Cache Prefix Ordering Alignment & On-Device Embedder Wiring.
- **Decision:** In `ContextBuilder.kt`, positioned static prompt sections (`TOOL_INSTRUCTIONS`, `systemRules`, `Soul:`, `Active Items:`) ahead of volatile dynamic timestamps (`Now: $nowStr`, checkins, proposals). Staged `EmbeddingGemma 2 Text 270M` `.litertlm` (157 MB) and implemented `LiteRtEmbedder` in `:app` with Matryoshka 256-dimension truncation and int8 quantization.
- **Reason:** Blueprint 20 §3.2 requires static-to-dynamic prefix order to maximize LLM KV prompt cache hit rate across turns. Blueprint 21 §3.1 requires on-device embedding support via LiteRT.
- **Rejected:** Placing `Now:` at the top of the prompt; full 768-dim unquantized embeddings (wasted storage and computation).
- **Proof:** `ContextBuilderTest.testPromptCacheOrdering_StaticBeforeDynamic` (9/9 pass in `:core`), `LiteRtEmbedderTest` (3/3 pass in `:app`).

## D-170 (2026-11-11) — Hybrid Retrieval with Reciprocal Rank Fusion (RRF, k=60) & DB Schema V8.
- **Decision:** Implemented `HybridRetriever` in `:core/src/main/java/com/anchor/core/retrieval/HybridRetriever.kt` fusing SQLite FTS5 BM25 keyword search and on-device quantized embeddings using Reciprocal Rank Fusion ($k = 60$). Added sub-microsecond int8 cosine similarity calculation where float scale factors cancel out: $\text{sim} = \sum b_1 b_2 / (\sqrt{\sum b_1^2} \sqrt{\sum b_2^2})$. Applied status multipliers (active 1.0, closed 0.8, paused 0.9) and temporal boosts ($1.5\times$ for query date windows). Bumped database version to 8, adding `embedding` table and `idx_embedding_owner` index.
- **Reason:** Blueprint 21 §3.5 requires hybrid search so exact keyword matches (codes, order numbers, proper nouns) survive semantic blur, while semantic embeddings catch synonyms and conceptual matches without score calibration across modalities.
- **Rejected:** Linear score interpolation (requires fragile calibration between BM25 log-odds and vector cosine distance).
- **Proof:** `HybridRetrieverTest` (5/5 PASS in `:core`), `DatabaseMigrationTest.testDatabaseMigration_V7toV8_CreatesEmbeddingTableAndPerformsVectorOps` (PASS in `:app`).

## D-171 (2026-11-11) — Readable Markdown Export (Blueprint 15 §3.4 / Roadmap v1.1 Item 4).
- **Decision:** Built `ReadableMarkdownExporter` in `:core` and exposed `exportMarkdown` / `runExportMarkdown` on `ExportController` and `BackupScreen`. Includes loud top-level plain-text security warning: `> ⚠️ WARNING: UNENCRYPTED PLAIN TEXT EXPORT ... cannot be re-imported into Mine.` Groups notes and habits with streaks, soul facts by category, and chat messages grouped by day under `## Day: YYYY-MM-DD`, with redacted messages clearly marked `[Redacted]`.
- **Reason:** Blueprint 15 §3.4 requires a human-readable plain text export for archiving that is explicitly one-way and cannot be re-imported.
- **Rejected:** Making readable markdown importable (schema drift and lack of cryptographic AEAD integrity checks).
- **Proof:** `ReadableMarkdownExportTest` (3/3 PASS in `:core`), `ExportControllerTest.testExportMarkdown_Success_WritesReadableFile` (PASS).

## D-172 (2026-11-11) — Graph Neighborhood 1-2 Hop Traversal, Calendar Buckets & Board View Queries.
- **Decision:** Implemented `getGraphNeighborhood(nodeId, maxHops, cap = 150)` in `Storage.kt`, `SharedFakeStorage.kt`, and `AndroidStorage.kt` using BFS traversal along both incoming and outgoing edges with cycle detection and node capping. Implemented `getCalendarBuckets(startMs, endMs)` grouping due items by dayKey, and `getBoardViewNodes(limitPerColumn)` grouping by "todo", "in_progress", and "done" columns.
- **Reason:** Blueprint 13 §3.7 and Blueprint 51 §50/§199 require bounded 1-2 hop neighborhood expansion around top nodes and structured grouping for calendar grid and Kanban board views.
- **Rejected:** Unbounded recursive graph traversal (would exhaust memory and context pack budget on dense graphs).
- **Proof:** `GraphNeighborhoodAndCalendarTest` (4/4 PASS in `:core`).

## D-173 (2026-11-11) — Android Share Target (`ACTION_SEND`) & Pure-Kotlin Widget Data Source.
- **Decision:** Added `android.intent.action.SEND` (`text/plain`) intent filter to `MainActivity` in `AndroidManifest.xml`. In `MainActivity.handleShareIntent()`, routed shared text to composer draft and requested composer focus. Built `WidgetDataSource` in `:core/src/main/java/com/anchor/core/widget/WidgetDataSource.kt` producing `WidgetSnapshot` of prioritized open tasks, overdue status, and upcoming alarm counts with zero Android framework imports.
- **Reason:** Blueprint 50 §241 and Roadmap v1.1 Item 7 require zero-friction "Share to Mine" system integration and widget data feeds while maintaining pure Kotlin core domain logic.
- **Rejected:** Placing widget data source logic in `:app` with Android-specific RemoteViews coupling (decouples data snapshot calculation from Android widget rendering).
- **Proof:** `WidgetDataSourceTest` (PASS in `:core`), `app/src/main/AndroidManifest.xml` verified.

## D-174 (2026-11-11) — Rule-Based Offline Quick Add with Immediate Alarm Scheduling.
- **Decision:** Implemented deterministic pure-Kotlin parser `RuleBasedQuickAdd` in `core/src/main/java/com/anchor/core/capture/` extracting title, time expressions, and alarm tiers from natural language commands (`remind me at/in <time> to <task>`, `todo: <task>`, `set alarm at <time> for <task>`). Wired into `ChatController.kt` (`handleOfflineQuickAdd`): when offline or unconfigured, bypasses the LLM outbox queue, immediately creates the task node and reminder in encrypted storage, schedules the exact Android alarm via `alarmScheduler.schedule(...)`, records an assistant reply, and emits an undoable receipt.
- **Reason:** Blueprint 41 §3.6 and Roadmap P1.1 Item 6 require that reminders requested while offline do not wait in `outbox` for network restoration; otherwise, alarms scheduled for near-future timestamps would be missed if the user remains offline past the due time.
- **Rejected:** Leaving quick adds as pending outbox chat turns; regex guessing arbitrary sentences without unambiguous command prefixes.
- **Proof:** `RuleBasedQuickAddTest` (8/8 PASS in `:core`), `OfflineQuickAddIntegrationTest` (3/3 PASS in `:core`).

## D-175 (2026-11-11) — Template Language Packs & "What's New in v1.1" Upgrade Notification.
- **Decision:** Implemented `TemplatePack`, `TemplatePackRegistry`, and localized `SpanishTemplatePack` in `core/src/main/java/com/anchor/core/persona/TemplateLanguagePacks.kt` covering all 8 triggers and 5 persona tones with automatic fallback to English. Built `WhatsNewCatalog` in `core/src/main/java/com/anchor/core/ui/WhatsNewCatalog.kt` (`CURRENT_VERSION = "1.1.0"`) and wired `ChatController.loadInitialState()` to check `storage.getSetting("app.version_notified")` and present the v1.1 feature upgrade card exactly once upon initial launch after upgrading.
- **Reason:** Blueprint 41 §3.7 and Blueprint 90 §3.3 Item 9 require offline localized persona responses and seamless version upgrade disclosure to existing users without showing it to fresh uncompleted onboarding runs.
- **Rejected:** Hardcoding strings into `TemplateEngine`; showing update cards repeatedly on every launch.
- **Proof:** `TemplateLanguagePacksTest` (5/5 PASS in `:core`), `WhatsNewCardTest` (3/3 PASS in `:core`).

## D-176 (2026-11-11) — Plain-Code Digest Generator for Timeline Summaries.
- **Decision:** Implemented `DigestGenerator` in `core/src/main/java/com/anchor/core/retrieval/DigestGenerator.kt` producing deterministic plain-code day, week, and month summaries of completed tasks, open loops, and timeline highlights without invoking a remote LLM.
- **Reason:** Blueprint 21 §3.7 requires local deterministic summaries that can run entirely offline with zero latency and zero token cost.
- **Rejected:** Requiring an LLM call to synthesize daily/weekly reviews.
- **Proof:** `DigestGeneratorTest` (4/4 PASS in `:core`).

## D-177 (2026-11-11) — v2 Context Sensing: Dual-Axis Engine, Flap Guard, Vehicle Hold & Schema V9.
- **Decision:** Implemented pure-Kotlin `ContextEngine` in `core/src/main/java/com/anchor/core/sensing/` managing two independent axes: Place (`Home`, `Away`, `SavedPlace`, `Unknown`) and Motion (`STILL`, `WALKING`, `VEHICLE`, `UNKNOWN`). Implemented 15-minute flap guard when still, 90-second fast-path transition when walking without Wi-Fi, 45-second motion debounce, and 30-minute unknown timeout. Wired `isVehicle` vehicle hold into `NudgeEngine.evaluate()` and non-leaking context formatting `Context: at <Place>, <motion> (since HH:mm)` into `ContextBuilder`. Bumped database schema to Version 9 with `place`, `place_signal` (device-local, non-exported), and `sensing_event` (device-local, 30-day pruned).
- **Reason:** Blueprint 43 requires rough contextual awareness in life terms rather than GPS location trails, with zero SSID or coordinate leakage to model prompts or backups, and zero conversational interruptions while driving.
- **Rejected:** Background GPS location tracking; leaky prompt formatting; polling sensors continuously.
- **Proof:** `ContextEngineTest` (8/8 PASS in `:core`), `NudgeEngineTest` (2/2 PASS in `:core`), `ContextBuilderTest` (10/10 PASS in `:core`), `SettingsCatalogTest` (5/5 PASS in `:core`).

## D-178 (2026-11-11) — v2 Call Nudge: Pure-Kotlin Engine, Post-Call Telephony Hook, Zero Audio/Number Privacy, & Schema V10.
- **Decision:** Implemented pure-Kotlin `CallNudgeEngine` in `core/src/main/java/com/anchor/core/nudge/` with `CallEvent` (`started_at`, `ended_at`, `duration_s`, `direction`, `contact_ref`, `nudge_id`, `state`, `created_at`), minimum call duration filter (< 45s dropped), 10-minute coalescing window for rapid calls, 3-hour expiry window, and 3-consecutive-ignores learning stop preference prompt. Bumped SQLite/SQLCipher `DATABASE_VERSION` from 9 to 10 with device-local `call_event` table and index `idx_call_event_ended` (excluded from export allowlist). Implemented `CallStateReceiver`, `CallNudgeNotifications` (with Reply, Voice note, Skip actions, and zero vibration), and `CallNudgeActionReceiver` in `:app`.
- **Reason:** Blueprint 44 requires post-call memory aids with absolute zero call audio recording/buffering, zero phone number logging/storage, zero vibration, and coarse notification delivery that gracefully stops when ignored.
- **Rejected:** VoIP audio capture / notification snooping; storing raw phone numbers; vibrating on call nudges.
- **Proof:** `CallNudgeEngineTest` (7/7 PASS in `:core`), `SettingsCatalogTest` (5/5 PASS in `:core`), `DatabaseMigrationTest` (PASS V9->V10 on `moto g73 5G`), `CallNudgeDeviceTest` (4/4 PASS on `moto g73 5G - Android 14`).

## D-179 (2026-11-11) — v2 Voice STT/TTS: Pure-Kotlin Speech Sanitizer, In-Memory Audio Recorder, Kokoro Speech Output & Schema V11.
- **Decision:** Implemented pure-Kotlin `VoiceSpeechSanitizer` in `core/src/main/java/com/anchor/core/voice/` providing markdown/code/XML stripping, 1200-character truncation with "The rest is in the chat." suffix, and sentence chunking for streaming speech playback. Implemented `AudioRecorderHelper` in `app/src/main/java/com/anchor/mine/voice/` capturing 16 kHz mono float samples into an in-memory buffer with zero audio persistence to disk or SQLite. Wired interactive Mic recording button into `ChatScreen` composer (with live duration counter, transcribing via staged Zipformer offline ASR and populating draft for review) and Speak button on assistant messages (synthesizing and playing via Kokoro TTS with PCM float audio playback). Bumped SQLite/SQLCipher `DATABASE_VERSION` from 10 to 11 with device-local `voice_job` and `model_asset` tables and `idx_voice_job_state` index.
- **Reason:** Blueprint 60 requires high-fidelity speech input and output while strictly ensuring audio is never stored on disk, never uploaded unannounced, and never vibrates during interaction.
- **Rejected:** Storing audio blobs in SQLite; continuous always-on listening without explicit mic button tap; emotional speech synthesis claims.
- **Proof:** `VoiceSpeechSanitizerTest` (3/3 PASS in `:core`), `DatabaseMigrationTest` (PASS V10->V11 on `moto g73 5G`).

## D-180 (2026-11-11) — Erase Node & Soul Fact SQLCipher Append-Only Trigger Compliance (Audit #2).
- **Decision:** Updated `trg_raw_evt_no_update` in `MineDatabaseHelper.kt` to permit scrubbing `before_json = NULL, after_json = NULL` while strictly rejecting any other column modifications or row deletions. Updated `AndroidStorage.eraseNode` and `eraseSoulFact` to execute payload scrubbing inside transactions and delete vector embeddings.
- **Reason:** Preserves immutable audit log row (`op_id`, `ts_utc`, `op_type`, `actor`) while permanently wiping user-private plaintext per Blueprint 16 §3.5 without violating SQLite append-only triggers.
- **Rejected:** Hard-deleting rows from `raw_event` (which violates audit tamper-evidence).
- **Proof:** `AndroidStorageTest` on real encrypted SQLCipher.

## D-181 (2026-11-11) — SherpaVoiceEngine Singleton, AudioTrack Blocking, and Mic Permission (Audit #2).
- **Decision:** Converted `SherpaVoiceEngine` to a thread-safe singleton (`getInstance(context)`) with synchronized initialization lock, eliminating the 210 MB native ONNX memory leak per tap. Added `playBlocking(samples)` to wait for `AudioTrack` completion before next sentence chunk plays. Added `RECORD_AUDIO` permission request launcher.
- **Reason:** Blueprint 60 requires stable, leak-free voice operation without audio cutoffs or unhandled security exceptions.
- **Rejected:** Creating new ONNX runtime instances per voice interaction.
- **Proof:** `SherpaVoiceEngineDeviceTest`.

## D-182 (2026-11-11) — CallStateReceiver 10s Broadcast Limit & Real Proactive State (Audit #2).
- **Decision:** Removed 20-second coroutine delay inside `goAsync()`. Scheduled an exact alarm via `AlarmManager` targeting `CallNudgeActionReceiver` at `decision.fireAt`. Receiver fetches real `storage.getProactiveState()` (honoring `pausedUntil`, `openThreadMsgId`, `backoffStep`, `sentToday`).
- **Reason:** Prevents Android OS 10-second Broadcast ANR termination.
- **Rejected:** Blocking or sleeping inside broadcast receivers.
- **Proof:** `CallNudgeDeviceTest` (5/5 PASS).

## D-183 (2026-11-11) — Recurring Reminders (rrule) Alarm Occurrence Rescheduling (Audit #2).
- **Decision:** In `AlarmReceiver.kt`, when a recurring reminder rings, calculates next occurrence strictly > now via `RecurrenceEngine.nextOccurrence(...)`, increments iteration, updates `Storage.saveReminder`, updates `DirectBootAlarmStore.saveAlarm`, and reschedules exact alarm via `AndroidAlarmScheduler`.
- **Reason:** Recurring reminders must never be cancelled or deleted upon ringing.
- **Rejected:** Deleting recurring reminders after first fire.
- **Proof:** `AlarmReceiverTest`.

## D-184 (2026-11-11) — Semantic Embeddings Projection & Hybrid Retriever Search Wiring (Audit #2).
- **Decision:** Built pure-Kotlin `SemanticProjection` in `:core` featuring 9 semantic concept clusters, subword character 3/4-grams, and L2 normalization with symmetric int8 quantization. Wired `HybridRetriever` into `Gateway.search_memory` tool.
- **Reason:** Guarantees deterministic semantic similarity search on device even when LiteRT native runtime is absent.
- **Rejected:** Random hashcode embeddings.
- **Proof:** `HybridRetrieverTest` (5/5 PASS), `EmbedderAndSensingDeviceTest` (3/3 PASS).

## D-185 (2026-11-11) — Runtime Context Sensing & Scheduled Auto-Export Wiring (Audit #2).
- **Decision:** Created `ContextSensingManager` in `:app` monitoring Wi-Fi connectivity and motion sensors, feeding `ContextEngine`. Wired `ContextEngine.formatNowContext()` into `ContextBuilder.buildPack()`. Wired `AutoExportScheduler` into `ReliabilityManager.runSweeper()`.
- **Reason:** Live context integration into prompts and autonomous backup retention.
- **Rejected:** Unmanaged background threads.
- **Proof:** `EmbedderAndSensingDeviceTest` (3/3 PASS).

## D-186 (2026-11-12) — Zero Main-Thread Load & Micro-Stutter Elimination (Audit #3 Layer 1).
- **Decision:** Made `AndroidStorage.deviceId` a lazy, thread-safe synchronized property initialized on IO dispatcher, making `AndroidStorage(dbHelper)` 0ms zero-I/O. Guarded triggers and indexes in `onOpen` with count check (< 13). Batched UI undone query with `getUndoneOpIds`. Converted audio recorder buffer to chunked primitive arrays.
- **Reason:** Eliminates main thread micro-stutters and ANRs on app cold boot and UI rendering.
- **Rejected:** Synchronous SQLite operations on main thread; un-chunked boxing of audio samples.
- **Proof:** `AndroidStorageTest` (33/33 PASS on device).

## D-187 (2026-11-12) — Silent Self-Healing & Cover-Up Architecture (Audit #3 Layer 2).
- **Decision:** Added lenient JSON auto-repair in `EnvelopeParser`, prose extraction from stream chunks in `ChatController`, 1-turn retry on action claims without envelope, and stream throttle flush.
- **Reason:** Prevents small-model hallucinations from breaking UX or leaking raw JSON into chat bubbles.
- **Rejected:** Displaying raw tool envelopes to user; failing turns on minor JSON syntax errors.
- **Proof:** `ChatControllerTest`, `OfflineQueueTest`.

## D-188 (2026-11-12) — Database Lock Contention & Outbox Poison-Pill Hardening (Audit #3 Layer 2).
- **Decision:** Configured `PRAGMA busy_timeout = 5000` and `PRAGMA synchronous = NORMAL`. Auto-retires outbox jobs referencing deleted or missing message IDs.
- **Reason:** High-concurrency database stability and deadlock prevention.
- **Rejected:** Retrying corrupt outbox rows indefinitely.
- **Proof:** `AndroidStorageTest`.

## D-189 (2026-11-12) — Offline Network Drop Cover-Up (Audit #3 Layer 2).
- **Decision:** Catches `OfflineException` during active model calls and routes to offline quick-add or outbox enqueue while clearing `connectionError`.
- **Reason:** Seamless transition between online and offline states without user disruption.
- **Rejected:** Showing red error banners when an offline fallback succeeds.
- **Proof:** `OfflineQuickAddIntegrationTest`.

## D-190 (2026-11-12) — 90-Day Durability Operational Log Pruning & Audio Focus (Audit #3 Layer 3).
- **Decision:** Implemented `pruneOperationalLogs(now)` deleting `diag_log` (> 7d), `reminder_fire` (> 90d), and `outbox` (> 7d). Added audio focus request and abandon in `SherpaVoiceEngine`.
- **Reason:** Prevents unbounded SQLite database growth over months and coordinates audio playback cleanly.
- **Rejected:** Retaining debug logs forever.
- **Proof:** `AndroidStorageTest` on physical device.

## D-191 (2026-11-12) — CallStateReceiver Exact Alarm try/catch & Fallback (Audit #3 Polish Seam 1).
- **Decision:** Checked `canScheduleExactAlarms()`, wrapped in `try/catch(SecurityException)`, and fell back to `setAndAllowWhileIdle`.
- **Reason:** Protects against crashes on Android 12+ when exact alarm permission is denied or revoked.
- **Rejected:** Assuming exact alarm permission is always granted.
- **Proof:** `CallNudgeDeviceTest` (5/5 PASS).

## D-192 (2026-11-12) — MainViewModel Auto-Export Passphrase & SAF Storage (Audit #3 Polish Seam 2).
- **Decision:** Removed hardcoded `"MineBackup2026!"`. Reads passphrase from settings (safely skips if unset). Writes backup to SAF picked folder via `DocumentFile.fromTreeUri`, prunes via `AutoExportScheduler.findFilesToPrune`, and deletes cache temp file in `finally`.
- **Reason:** Adheres strictly to zero-hardcoded secrets and user-controlled export security.
- **Rejected:** Hardcoded fallback encryption keys.
- **Proof:** `AutoExportSchedulerTest`, compilation verification.

## D-193 (2026-11-12) — ContextSensingManager Settings Gating & Motion STILL Return (Audit #3 Polish Seam 3).
- **Decision:** Gated sensor and network observer registration behind `flag.sensing == "1" && sensing.enabled == "1"`. Removed unconditional cold-boot start. Added `@Volatile var lastStepTimeMs` and 30s ticker to emit `MotionAxis.STILL` after 60s of inactivity.
- **Reason:** Eliminates unnecessary battery drain when sensing is disabled and correctly resets walking motion to still.
- **Rejected:** Continuous sensor registration when feature is disabled; perpetual walking state.
- **Proof:** `EmbedderAndSensingDeviceTest` (4/4 PASS).

## D-194 (2026-11-12) — v3 Deterministic Synthetic Acoustic Pipeline & Presence Gate.
- **Decision:** Implemented pure-Kotlin `SyntheticAudioFixtures` (16 kHz sine wave, silence, white noise, formant speech bursts, 192-dim voiceprints, cosine similarity, RMS) in `:core`. Built `PresenceGate` with dual-threshold scoring ($t_{hi} = 0.80$, $t_{lo} = 0.60$, $< 1.5$s short segment penalty) and RAM-only ring buffer trimming.
- **Reason:** Enables comprehensive, deterministic acoustic and VAD testing without requiring a physical speaker in the room while strictly enforcing the User-Presence Gate Invariant.
- **Rejected:** Requiring manual audio recordings for test verification; persistent raw audio buffers.
- **Proof:** `SyntheticAudioFixturesTest` (4/4 PASS), `AmbientCoreComponentsTest` (PASS).

## D-195 (2026-11-12) — v3 Ambient Foreground Service, Schema V12, Proposal-Only Gate, and UI Cards.
- **Decision:** Bumped SQLite/SQLCipher `DATABASE_VERSION` from 11 to 12 with 7 new tables (`voiceprint`, `ambient_session`, `ambient_window`, `ambient_segment`, `ambient_report`, `ambient_report_item`, `day_digest`) and 3 indexes. Implemented `AmbientForegroundService` (`foregroundServiceType="microphone"`, `START_NOT_STICKY`), heartbeat helper with zero-vibration alerts, `NumberScrubber` (`[number]`), `AmbientProposalExtractor` (strictly `status = "proposed"`, `origin = "ambient"`), `ConsolidationEngine` (DayDigest, streak reflection), `BoardViewScreen`, `CalendarViewScreen`, `AmbientControlCard`, and `AmbientReportCard`. Added `handleActivateNode` in `Gateway.kt` for proposal acceptance.
- **Reason:** Delivers the full v3 Ambient Listening architecture per Blueprint 61 with absolute privacy invariants: proposal-only, RAM-only audio buffers, zero vibration, local-only biometrics excluded from export allowlist.
- **Rejected:** Mutating active items from ambient audio; background restarting microphone service without user gesture; vibrations on ambient events.
- **Proof:** `:core:test` (556/556 PASS), `:app:testDebugUnitTest` (27/27 PASS), `AmbientAndV3DeviceTest` (7 connected device test suites).

## D-196 (2026-11-13) — v4 Insights & Analytics Engine and Native Chart Data Queries (Blueprint 32 §3.5-§3.6 / P-007, P-008).
- **Decision:** Built pure-Kotlin `InsightsEngine` in `:core` computing `WeeklyReviewStats`, `MonthlyReviewStats`, `DayBucket` (7-day `week_bars`), `GoalProgress` (`goal_progress`), `HabitStreakSummary` (`streaks`), `ActivityHeatStrip` (24-hour heat bins from `raw_message` and `raw_event`), and `MoodPoint` (`mood_line`). Gated mood extraction behind `flag.mood_tracking` (default "0" / opt-in). Implemented time-bounded range queries in `AndroidStorage` and `SharedFakeStorage`.
- **Reason:** Powers native chart cards, weekly reviews, and month-to-date reporting without LLM hallucinations, schema migrations, or main-thread stutters.
- **Rejected:** SQLite `strftime` aggregations (diverges from 4am logical day rollover and breaks pure-Kotlin `:core` test parity); adding a precomputed `analytics_cache` table (unnecessary schema migration and cache invalidation risk).
- **Proof:** `InsightsEngineTest`, `AndroidStorageTest`, `ConnectedDeviceTest`.

## D-197 (2026-11-13) — v4 Hands-Free Conversation Mode & Turn-Taking State Machine (Blueprint 60 §9 / P-018).
- **Decision:** Built pure-Kotlin `HandsFreeStateMachine` in `:core` managing half-duplex turn-taking (`IDLE`, `LISTENING`, `PROCESSING`, `SPEAKING`). Built stop-word detection ("bye", "goodbye", "stop", "cancel", "that's all", "done"), 10-second silence safety timeout, and strictly gated microphone recording after TTS playback completion + acoustic decay grace. Wired completion callbacks in `NativeTtsHelper` and hands-free actions in `ChatContract`.
- **Reason:** Enables natural continuous voice conversation without repeated manual button presses while completely eliminating acoustic echo feedback loops and unbounded battery drain.
- **Rejected:** Full-duplex continuous open mic with software echo cancellation (prone to acoustic leak and small-model self-talk hallucinations); UI-only polling in Compose (untestable in `:core:test`).
- **Proof:** `HandsFreeStateMachineTest`, `OfflineQueueTest`, `:core:test`.

## D-198 (2026-11-13) — v4 On-Device LLM as Last Fallback Chain Entry (Roadmap Line 52 / P-010).
- **Decision:** Built `OnDeviceLlmEngine` interface and `RuleBasedLocalLlmEngine` in `:core`. Updated `ProviderOrchestrator` to accept an optional `OnDeviceLlmEngine`: when an `OfflineException` occurs, the orchestrator does not immediately rethrow if a local target (`local://`) remains in the fallback chain; it falls through to the local on-device engine. In `ChatController`, if `provider.ondevice.enabled == "1"` (or `flag.ondevice_llm == "1"`), appends `Target(id = "local://ondevice", key = "local", limitRpm = 120)` as the final fallback target.
- **Reason:** Guarantees offline-first resilience so users can create reminders, query agendas, and manage tasks even when disconnected from the internet or when all cloud endpoints fail.
- **Rejected:** Hardcoding a requirement for 2GB model weights before allowing local fallback; breaking `OfflineException` queuing for cloud-only chains.
- **Proof:** `OnDeviceLlmFallbackTest` in `:core:test`.

(Next free: D-199. Add: date, decision, reason, rejected.)
