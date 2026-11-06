# DECISIONS (new ones only; old ones live in docs/blueprints/03-decisions.md; never edit old)
D-100 Node is the truth; Reminder is the alarm attached by node_id column. Node+reminder+raw_event in one transaction; arm only after commit; cancel/complete cancels alarm; firing marks reminder done.
D-101 Redaction: UPDATE of raw_message allowed only if old redacted_at IS NULL, new set, content replaced per blueprint 11, all other columns unchanged; second redaction aborts; writes raw_event.
D-102 Manifest: no VIBRATE, WAKE_LOCK, SCHEDULE_EXACT_ALARM. USE_EXACT_ALARM is declared per Android 14 requirements (setAlarmClock throws SecurityException on Android 14 without USE_EXACT_ALARM or SCHEDULE_EXACT_ALARM; USE_EXACT_ALARM is auto-granted normal permission for alarm apps). Zero-vibration hardware safety protocol strictly enforced.
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
Rejected: Transactional CAS inside effect (would require passing proposal ID down into every individual storage mutation and coupling Gateway orchestration into SQLite transactions); in-memory Mutex (does not protect cross-process or DB-level concurrency).

### Note on Knowledge Pack Numbering Differences
The Session 1 remediation pack cited decision numbers that differed from the chronological sequence recorded in DECISIONS.md:
- Pack "D-112" referred to Architectural Logic Fix 1 (Time chip pre-validation & untitled/label payload preservation), whereas in this repo D-112 is "Compound duration parsing, prompt date compliance, and untitled alarm fallbacks" (R11).
- Pack "D-114" referred to Architectural Logic Fix 3 (Gentle reminders RTC_WAKEUP & Doze disclosure), whereas in this repo D-114 is "Alarm reconciliation semantics (reconcile(force: Boolean)) and zero-silent-failure contract" (R3).
- Pack cited "Next free: D-113" before R2 was merged, while R2 recorded D-113 for keyset candidate pagination and 5000-item cap.
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

(Next free: D-122. Add: date, decision, reason, rejected.)

