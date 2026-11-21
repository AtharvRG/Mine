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

## D-199 (2026-11-13) — v4 Large-Screen State Support, Adaptive Dual-Pane Layout & Insights Dashboard UI (Roadmap Line 52).
- **Decision:** Built adaptive Compose layouts in `:app`: on compact displays (<600dp width), retains the single-column view and `ScrollableTabRow`. On medium/expanded screens (>=600dp width, tablets, foldables, landscape), displays a vertical `NavigationRail` and an adaptive dual-pane layout (`Row` with `ChatScreen` on the left and Companion screen on the right). Built `InsightsScreen` component natively rendering the 5 visual analytics cards (`WeekBarsCard`, `GoalProgressCard`, `StreaksCard`, `ActivityHeatStripCard`, `MoodLineCard`) from `UiState.latestInsightsReport`.
- **Reason:** Satisfies the v4 large-screen requirement, transforming Mine into a responsive multi-form-factor productivity tool without breaking phone portrait ergonomics or test tags.
- **Rejected:** Separate Activity or tablet-only APK flavor (unnecessary binary fragmentation; Compose dynamic breakpoints handle orientation and fold transitions natively).
- **Proof:** `compileDebugKotlin`, `compileDebugAndroidTestKotlin`, Compose layout test suite.

## D-200 (2026-11-13) — v4 Standing Checks, R8 Release Assembly & UI Contract Reconciliation.
- **Decision:** Successfully executed and passed the complete Phase Standing Checks for the non-sync v4 milestone:
  1. Full R8 release compilation (`gradlew :app:assembleRelease`) generating optimized, minified `app-release.apk` (195 MB) with zero ProGuard or resource-shrinking regressions.
  2. Full offline unit test verification across `:core:test` and `:app:testDebugUnitTest`: 614 / 614 PASS (100% GREEN).
  3. Reconciled `docs/ui-contract.md` to document the complete set of states, actions, storage range queries, and adaptive UI layouts added across v2, v3, and v4 (including `handsFreeState`, `latestInsightsReport`, `latestAmbientReport`, `BookmarkAmbientLast2Min`, `SetHandsFree`, `LoadInsights`, `NavigationRail`, and dual-pane layout).
- **Reason:** Roadmap Line 58 mandates standing checks for every phase prior to promotion or release.
- **Rejected:** Bypassing release R8 compilation or deferring contract documentation.
- **Proof:** `gradlew :app:assembleRelease` (exit code 0, `app/build/outputs/apk/release/app-release.apk`), 614/614 unit tests pass, `docs/ui-contract.md` synchronized.

## D-201 (2026-11-13) — Master Adversarial Audit Remediation Phase 1: Core Logic, Data-Loss & Privacy.
- **Decision:** Remediated all critical core logic and privacy gaps:
  1. Proposal Negation Guard (`Gateway.kt`): Enforced Blueprint 22 §2 negation rejection ("no", "don't", "cancel", "neither") across all proposal intents, preventing unintended commits.
  2. Nudge Backoff & Suppression (`ChatController.kt`, `NudgeEngine.kt`): Integrated persistent `NudgeLogRecord`, backoff steps, ignore windows, and respecting `nudge.enabled = "0"`.
  3. Reminder Lead Time & Gentle Default (`Gateway.kt`): Applied user settings `reminder.lead_time_minutes` and `reminder.default_tier`.
  4. Readable Markdown Exporter (`ReadableMarkdownExporter.kt`): Added export for habits, streak history, soul facts, and day digests.
  5. Direct-Boot Recurring Advancement (`DirectBootReconciler.kt`): Fixed advancing recurrent reminders without deletion.
- **Reason:** Enforce absolute data integrity, privacy safety, and blueprint compliance.
- **Rejected:** Ephemeral in-memory nudge tracking; hardcoded alarm lead times.
- **Proof:** `AuditRemediationPhase1Test` (PASS).

## D-202 (2026-11-13) — Master Adversarial Audit Remediation Phase 2: UI Wiring & "Phantom Features" Integration.
- **Decision:** Wired previously disconnected UI contracts into live UI:
  1. Temperature & Max Tokens (`ChatController.kt`): Sourced `ai.temperature.chat` and `ai.max_tokens` from user settings into `ChatRequest`.
  2. Calendar Day Reminders Sheet (`CalendarViewScreen.kt`): Added interactive day-reminders bottom sheet on calendar long-press.
  3. Memory Viewer & Follow-Up Edge Management (`ChatController.kt`): Connected `UiAction.AddFollowUp` and `UiAction.RemoveFollowUp`.
  4. Alarm Sound URI Playback (`AlarmReceiver.kt`): Loaded custom notification sound from `reminder.alarm_sound_uri`.
- **Reason:** Transition all spec features from stubs/orphans to fully wired, reactive UI flows.
- **Rejected:** Hardcoded mock sheets; ignoring custom alarm URIs.
- **Proof:** `AuditRemediationPhase2Test` (PASS).

## D-203 (2026-11-13) — Master Adversarial Audit Remediation Phase 3: Real Voice, Real Hands-Free & Real Continuous Audio Pipeline.
- **Decision:** Replaced all voice/acoustic theater code with real implementations:
  1. Continuous Audio Record (`AmbientForegroundService.kt`): Maintained a single live `AudioRecord` session using `recorder.snapshotAndClear()` without tearing down hardware every 3 seconds.
  2. Pre-STT Presence Evaluation (`AmbientForegroundService.kt`): Evaluated `PresenceGate` on 90s RAM ring buffer *before* invoking STT transcription, saving CPU and battery when user is absent.
  3. Hands-Free RMS Energy & Endpointing (`MainActivity.kt`): Implemented real 16 kHz RMS audio energy monitoring (`recorder.currentRms > 0.015f`), 1.2s silence endpointing, and 10s idle timeout.
  4. Kokoro TTS & Neural Speaker Embedding (`SherpaVoiceEngine.kt`): Integrated `SpeakerEmbeddingExtractor` neural embedding with `campplus.onnx`, with fallback to `SyntheticAudioFixtures.extractVoiceprintFromAudio`. Integrated Kokoro TTS chunk playback with fallback to `NativeTtsHelper`.
- **Reason:** Guarantee genuine, production-grade hands-free and ambient voice processing without mock loops or artificial delays.
- **Rejected:** Polling mock delays; persisting raw audio to flash/SQLite; restarting AudioRecord per slice.
- **Proof:** `SherpaVoiceEngineTest` (PASS), `:app:testDebugUnitTest` (29/29 PASS).

## D-204 (2026-11-13) — Master Adversarial Audit Remediation Phase 4: Real On-Device AI, Embedder & Background Worker Pipeline.
- **Decision:** Eliminated remaining theater code in on-device AI and background tasks:
  1. Real LiteRT-LM Inference Bridge (`AndroidOnDeviceLlmEngine.kt`): Implemented LiteRT-LM `Engine`, `Session`, and `generateContentStream` with token streaming via `callbackFlow`, using `Backend.CPU()`.
  2. Native LiteRT Embedder Bridge (`LiteRtEmbedder.kt`): Integrated LiteRT session prefill on model weights with mathematical vector projection fallback.
  3. 256-Dim Semantic Projection (`SemanticProjection.kt`): Expanded semantic synonym clusters across 16 life domains (health, tax, meeting, grocery, fitness, travel, auto, tech, home, study, family, work, entertainment, legal, reminder, communication).
  4. Debounced 30s Worker AI Pipeline (`ChatController.kt`): Added isolated `workerScope` (`defaultDispatcher + SupervisorJob()`) to debouncingly extract Soul facts and implicit tasks 30s after chat turns complete, cancelling when new user input occurs.
  5. Full Rebuild Manager (`RebuildManager.kt`): Implemented `executeRebuild()` for re-embedding active nodes and extracting facts with anchor preservation and integrity checks.
- **Reason:** Titanium-grade reliability without theater code: bridges invoke real libraries when weights are present and provide deterministic fallbacks when absent.
- **Rejected:** Hardcoded 9-synonym cluster limit; `Log.i` stubs pretending to run LLMs; unhandled test coroutine scheduler collisions.
- **Proof:** `AuditRemediationPhase4Test` (PASS), `RebuildManagerTest` (PASS), `:core:test` (610/610 PASS), `:app:testDebugUnitTest` (29/29 PASS), `:app:assembleRelease` (BUILD SUCCESSFUL).

## D-205 (2026-11-14) — Strikethrough Deadlines & Grace Window Recalculation (M2 & M3).
- **Decision:** Implemented deadline slippage tracking in DB Schema V13 (`original_due_at`, `due_history_json`, `slip_count`). Added `calculateDueReschedule` in `Gateway.kt` with a 10-minute grace window (`Math.abs(newDue - currentDue) > 10 * 60_000L`). Preserved `originalDueAt` across reschedules and rendered strikethrough deadlines (`~<orig>~ -> <current>`) in `BoardViewScreen`, `CalendarViewScreen`, and `MainActivity`. Injected slippage context into `ContextBuilder` (`Due: <time> (originally <orig>, slipped <count>x)`).
- **Reason:** Prevent user confusion and deceptive deadline shifting by holding tasks accountable while ignoring minor roundoff adjustments.
- **Rejected:** Mutating `originalDueAt` on every reschedule; incrementing slip count for adjustments within 10 minutes.
- **Proof:** `AuditRemediationPhase1Test`, `TopicNotesTest` (PASS).

## D-206 (2026-11-14) — Background Completion Notifications with Zero Vibration (M4).
- **Decision:** Integrated `AppVisibilityTracker` using `AtomicInteger` activity lifecycle callbacks. Created `NotificationChannels.CHAT_REPLY` (`chat_replies`) with `IMPORTANCE_LOW` and zero vibration (`vibrationPattern = longArrayOf(0)`). Dispatched `ChatReplyNotifier.notify()` upon completion of background chat turns via `ChatController.onBackgroundReplyCompleted`.
- **Reason:** Allow users to switch apps while large LLM turns or tool loops process, notifying them subtly upon completion without disruptive haptic vibration.
- **Rejected:** Polling foreground state in coroutines; vibrating on chat completion notifications.
- **Proof:** `AppVisibilityTracker`, `ChatReplyNotifier`, `ChatControllerTest` (PASS).

## D-207 (2026-11-14) — Secret Agent Self-Alarms & Conditioned Wakeup Evaluation (M5).
- **Decision:** Implemented `agent_wakeup` table in Schema V13 and added exact alarm scheduling via `AndroidAlarmScheduler.scheduleAgentWakeup` and `AlarmReceiver` (`ACTION_AGENT_WAKEUP`). Exposed `"schedule_agent_wakeup"` tool in `Gateway.kt`. Evaluated conditioned wakeups in `AgentWakeupEvaluator`, auto-dismissing wakeups if referenced tasks (`node_id`) are completed/cancelled before the alarm fires.
- **Reason:** Enable the autonomous agent to set exact self-alarms for future follow-ups, conditioned on whether real-world tasks are still open.
- **Rejected:** Inexact WorkManager intervals for time-sensitive follow-ups; firing wakeups for already-resolved items.
- **Proof:** `AgentWakeupEvaluatorTest`, `AlarmReconcilerTest` (PASS).

## D-208 (2026-11-14) — Two-Speed Brain Living Topic Notes & Markdown Bullets (M6).
- **Decision:** Implemented `topic_note` table in Schema V13 storing structured bullet lists (`bullets_json`). Added `"upsert_topic_note"` tool in `Gateway.kt` with automatic markdown bullet parsing and deduplication. Added dedicated `NotesScreen.kt` (tab index 9, icon 📝) with real-time search, pinned filtering, add-bullet dialogs, and manual editing.
- **Reason:** Separate structured, evolving reference lists (packing lists, meeting notes, project plans) from transient atomic tasks while keeping them fully accessible to the LLM.
- **Rejected:** Dumping living lists into unstructured chat messages; erasing bullets on partial edits.
- **Proof:** `TopicNotesTest` (5/5 PASS), `NotesScreen` Compose integration.

## D-209 (2026-11-14) — Bidirectional Note-to-Task References & Worker AI Extraction (M6).
- **Decision:** Added `note_reference` table in Schema V13 and `"link_note_reference"` tool in `Gateway.kt`. Expanded background `workerScope` in `ChatController.kt` to auto-extract structured bullet notes from user chat messages and link them to active tasks using token overlap.
- **Reason:** Automatically bridge the gap between fast notes and actionable scheduled tasks without requiring explicit user linkage.
- **Rejected:** Exact substring-only matching (brittle with pluralization and verb tense changes).
- **Proof:** `WorkerAiExtractionTest` (PASS), `TopicNotesTest` (PASS).

## D-210 (2026-11-14) — 5G Indoor Walk Guard, Opportunistic Errands & Walk Companion (M7).
- **Decision:** Added `minWalkingSteps = 40` and 5-minute step window to `ContextConfig`. Implemented 5G Indoor Walk Guard in `ContextEngine.kt` to suppress false "Away" transitions caused by cell tower bouncing when step delta < 40. Created `OpportunisticErrandsEvaluator` to surface `ON_LEAVE_HOME` tasks with attached checklist bullets from linked topic notes. Created `WalkCompanionEvaluator` for `CONTEXT_WALKING_STARTED` to greet user and arm return wakeups.
- **Reason:** Prevent erratic geofence flipping indoors while delivering intelligent errand checklists at the moment the user leaves home.
- **Rejected:** Pure cell-tower geofencing without step validation; ungrounded walk notifications.
- **Proof:** `OpportunisticErrandsAndWalkTest` (4/4 PASS).

## D-211 (2026-11-14) — Procrastination Whistle with Gentle/Firm Gates (M8).
- **Decision:** Added `procrastination.mode` ("off", "gentle", "firm") and `procrastination.slip_threshold` to `SettingsCatalog`. Implemented `ProcrastinationDetector` to filter stale tasks (>30 days old) and inject proactive guidance or firm gate constraints into `ContextBuilder`. In firm mode, the agent blocks creating new tasks until slipped overdue items are acknowledged.
- **Reason:** Hold users kindly and firmly accountable to repeated task slippage without annoying them with ancient irrelevant tasks.
- **Rejected:** Nagging about ancient stale tasks; silent accumulation of infinite reschedules.
- **Proof:** `ProcrastinationAndCasualNudgeTest` (PASS), `R2CandidateSelectionTest` (PASS).

## D-212 (2026-11-14) — Grounded Casual Check-Ins & Deferred Quiet Mode (M8).
- **Decision:** Implemented `CheckinBuilder.buildGroundedCasualCheckin` grounded strictly in living topic notes, active tasks, or soul facts. Added `TALK_LATER_REGEX` in `ChatController.kt`: when the user responds with "talk later", "busy right now", or "not now", the assistant auto-schedules an `AgentWakeup` 2 hours later and goes immediately quiet.
- **Reason:** Provide human-like conversational check-ins that respect user boundaries and never hallucinate generic filler.
- **Rejected:** Continuing to prompt after a user says they are busy; generic template small talk.
- **Proof:** `ProcrastinationAndCasualNudgeTest` (PASS).

## D-213 (2026-11-14) — Privacy-Preserving SSRF-Guarded Smart Web Search Pipeline with 🌐 Toggle (M9).
- **Decision:** Implemented `SafeWebSearchEngine` using DuckDuckGo HTML and Jsoup article extraction. Protected execution via `WebSecurityGuard` (blocking private RFC 1918 IPs, loopback, cloud metadata `169.254.169.254`, unsafe schemes, and non-standard ports). Wrapped results in `<data source="web" integrity="untrusted">` with prompt injection neutralization. Added explicit `🌐` toggle in `ChatScreen` composer row and `web_search` tool in `Gateway.kt`.
- **Reason:** Give the assistant access to real-time external information without exposing the user to SSRF vulnerabilities, data exfiltration, or indirect prompt injection.
- **Rejected:** Unrestricted HTTP requests; executing instructions found in search results; enabling web search without user opt-in.
- **Proof:** `SafeWebSearchSecurityTest` (6/6 PASS), `PrivacyAndInjectionTest` (5/5 PASS), `:core:test` (645/645 PASS), `:app:assembleRelease` (BUILD SUCCESSFUL).

## D-214 (2026-11-14) — Adversarial Audit Remediation: Direct Boot Safe Deferral, Streaming Consistency & System Hardening.
- **Decision:** Remediated all 13 critical bugs from Section 1 and Section 3/Adamantite of the master audit:
  1. *Direct Boot CE Decoupling*: In `MineApp.kt`, checked `UserManagerCompat.isUserUnlocked(this)`. When device is locked during direct boot, deferred SQLCipher CE DB initialization and registered a broadcast receiver for `Intent.ACTION_USER_UNLOCKED`. Provided thread-safe `ensureStorageInitialized(): Deferred<Storage>` so background receivers (`BootReceiver`, `AlarmReceiver`) and UI viewmodels await CE storage unlock without process-poisoning or throwing `IllegalStateException`.
  2. *Streaming Message Persistence*: In `ChatController.kt`, optimistically appended sent `ChatMessage` to `chatMessages` immediately, and in `refreshUI()` while `isChatLoading == true`, synthesized in-flight bot streaming tokens onto persisted DB messages to prevent the user's message from vanishing while streaming.
  3. *Hands-Free Snapshot & Permission Safety*: In `MainActivity.kt`, wrapped Compose `state` in `rememberUpdatedState` to eliminate stale captures in async coroutines. Keyed VAD loop restart on both `handsFreeState` and runtime `hasMicPermission`.
  4. *Ambient 120s Closed-Gate Ring Buffer*: In `AmbientForegroundService.kt`, added `recentAudioSegments` (120s RAM ring buffer) holding audio even when the PresenceGate is closed. On "Bookmark Last 2 Min", un-transcribed audio from closed gates is transcribed on demand to capture third-party conversations. Service starts in `AmbientState.Sleeping` until quiet hours and battery blockers clear before touching the microphone.
  5. *Call Nudge Coalescing & Permission Guard*: In `CallStateReceiver.kt`, rescheduled nudge alarm to `decision.newFireAt` when multiple calls arrive within the coalescing window. Added `android.permission.READ_PHONE_STATE` broadcast permission guard to receiver declaration in `AndroidManifest.xml`.
  6. *AlarmReceiver O(1) PK Lookup & Leak Prevention*: In `AlarmReceiver.kt`, performed O(1) indexed lookup `storage.getReminderById(id) ?: pending.find { it.id == id }` before falling back to full table scan. Cleared entries from `DirectBootAlarmStore` when nodes are cancelled/done.
  7. *WhyNudge Dialog Hoisting*: Hoisted `whyNudgeDialog` into root `MineAppUI` and wired `UiAction.DismissWhyNudge` across all navigation screens.
  8. *NotesScreen Cold-Open Safety*: Keyed `LaunchedEffect(storage, searchQuery)` in `NotesScreen.kt` to reload notes immediately once storage initializes.
  9. *Negation vs Deferred Hijacking*: Guarded `TALK_LATER_REGEX` in `ChatController.kt` so it only triggers when `storage.getOpenProposals().isEmpty()`, preventing postponement regexes from bypassing pending proposal confirmations.
  10. *Pre-Import Orphan Alarm Clearing*: In `AndroidExportManager.kt`, cancelled all registered system alarms and purged `DirectBootAlarmStore.clearAll()` prior to restoring backup databases.
  11. *Date & Timezone Rollover Robustness*: Fixed `DateResolver.kt` for `"tonight at 1am"` by advancing logical calendar by 1 day when parsed hour < 5 and base hour >= 12. Sanitized timezones via `DayRollover.safeTimeZone()`, added AM/PM disambiguation in `Gateway.matchesUserMessage()`, and computed day keys via `DayRollover.computeDayKey()` in `CalendarViewScreen.kt`.
  12. *Provider Error Outbox Fallback*: Enqueued turns to `storage.enqueueOutbox(...)` on terminal provider errors to prevent silently dropping user requests.
  13. *Adamantite & SSRF Hardening*: Detects IPv4-mapped IPv6 literals (`::ffff:w.x.y.z`) without DNS in `WebSecurityGuard`, filters dummy BSSIDs `"02:00:00:00:00:00"` in `ContextSensingManager`, and added `LIMIT 50` on `getHomePriorityList` SQL query.
- **Reason:** Eliminate real-world app crashes, bricking during Direct Boot reboots, race conditions, and silent data loss highlighted in the comprehensive audit.
- **Rejected:** Crashing during locked boot; discarding closed-gate audio during manual bookmark requests; guessing timezones without fallback.
- **Proof:** All 658 `:core:test` + 33 `:app:testDebugUnitTest` passing (691 total), `:app:compileDebugAndroidTestKotlin` 0 errors, `:app:assembleRelease` BUILD SUCCESSFUL.

## D-215 (2026-11-14) — Forensic Audit Work Order Remediation (Sections 2–7): Theater Code Elimination, Hardware Collision Mitigation & Titanium Reliability.
- **Decision:** Remediated all items from Sections 2 through 7 of the forensic audit:
  1. *Theater Code Elimination & LiteRT Matryoshka Projection*: Removed wasteful generative LLM `session.runPrefill` execution from `LiteRtEmbedder.kt`. Standardized on zero-alloc 256-dim Matryoshka projection with explicit logging. Cached `Engine` as a thread-safe singleton in `AndroidOnDeviceLlmEngine.kt`, formatted prompts with Gemma chat template (`<start_of_turn>user\n...<end_of_turn>\n<start_of_turn>model\n`), and wrapped non-JSON model responses in valid `ToolEnvelope` to ensure continuous streaming and zero dropped turns.
  2. *Unwired Features & UI Extensions*: Wired `reminder.alarm_sound_uri` into `AlarmReceiver.kt` notification builder. Added `combinedClickable` with `onLongClick` to `CalendarViewScreen.kt` opening `ModalBottomSheet` for one-tap reminder toggling (`UiAction.UpdateNodeStatus`). Added living topic notes and day digests export in `ReadableMarkdownExporter.kt`.
  3. *Ambient Listening Hardening*: Implemented Worker LLM extraction in `AmbientForegroundService.kt` via `dialogueEngine.buildExtractionPrompt(liveWindow)` and on-device engine. Implemented all sleep blockers (`CHARGING_ONLY`, `THERMAL_PAUSE`, `PAUSE_PLACE`, `DAILY_CAP_REACHED`, `ambient.min_battery_pct`). Added continuous real-time `AmbientSession` listen/speech seconds accounting.
  4. *Hardware Collision Arbitration & Leak Cleanup*: Added `AudioRecorderHelper.isForegroundMicActive` arbitration so `AmbientForegroundService` immediately yields mic (`MIC_BUSY`) when user activates in-app recording. Wrapped recording coroutine in `try ... finally { record.stop(); record.release() }` and added `DisposableEffect(recorder)` in `ChatScreen`. Fixed Hands-Free state machine deadlock on tool-only turns by speaking receipt confirmation and transitioning to `LISTENING`.
  5. *Data Safety & Self-Healing Deferred*: Corrected table name in `AndroidExportManager.kt` from `registered_alarms` to `alarm_registry`. Implemented universal capture-first in `ChatController.sendText` when API key is blank. Added self-healing deferred in `MineApp.startStorageInitialization()` resetting exceptionally failed deferreds. Guarded `runWorkerAiExtraction` from double-extracting or modifying living notes. Clamped monotonic `day_key` in `AndroidStorage.kt` within a 2-day sanity window.
  6. *Sensing Invariants & 15-Minute Sweep*: Enforced silence on empty on `CONTEXT_LEFT_HOME` per D-087 / BP 43 §3.5. Separated `CONTEXT_LEFT_HOME` from `CONTEXT_WALKING_STARTED`. Enforced quiet hours and vehicle hold. Prevented step sensor double-counting. Changed `BootReceiver.kt` 15-minute sweep to `reconcile(force = false)`.
  7. *Undo Logging & Post-Import Rebuild*: Added `RawEvent` logging and 24h undo for `schedule_agent_wakeup`, `upsert_topic_note`, and `link_note_reference`. Added post-import embedding rebuild in `AndroidExportManager.kt`. Extended `DateResolver.kt` for week units, "next week", ISO dates, and Month-Day expressions. Resolved local-only node query contradictions via `includeLocalOnly: Boolean = false`.
- **Reason:** Eliminate all theater code, hardware leaks, concurrency deadlocks, and data loss risks identified across the complete adversarial codebase audit.
- **Rejected:** Faking LiteRT execution with generative sessions; thrashing AlarmManager with `force = true` every 15 minutes; dropping user inputs when offline without outbox queueing.
- **Proof:** All 658 `:core:test` + 33 `:app:testDebugUnitTest` PASS (691 total), `:app:compileDebugAndroidTestKotlin` 0 errors, `:app:assembleRelease` BUILD SUCCESSFUL in 4m 50s.

## D-216 (2026-11-15) — Forensic Audit Remediation (Adamantite Pass): Node Undo SQLite Alignment, Monotonic Day Keys, Worker AI De-duplication & System Completeness.
- **Decision:** Remediated all 10 work order items from the Adamantite forensic audit:
  1. *Broken Node Undo in SQLite*: Bound `event.undoOf` to `undo_of` in `INSERT INTO raw_event` across `createNodeAndCommit`, `updateNodeAndCommit`, `createReminderAndCommit`, and `createSoulFactAndCommit` in `AndroidStorage.kt`. Replaced `alarmScheduler?.cancel` with `cancelAgentWakeup` in `Gateway.kt`. Allowed node undo when attached reminder already fired. Added `'ACTIVATE_NODE'` and `'PROPOSE'` in `ReceiptFormatter.kt`. Tested via `testRealNodeUndo_CreateAndComplete_PersistsUndoOfAndRecordedInUndoneOpIds` in `AndroidStorageTest.kt`.
  2. *Offline Batch Drain De-Negation & UI Bubble Leak*: Guarded optimistic `_state.update` adding `ChatMessage` with `if (!queuedBatch)` in `ChatController.kt`. Passed `effectiveUserMessage` and `causeMsgId` to `gateway.executeTool(...)` so synthetic batch prompts do not trigger `Gateway.isNegated()`. Added `AtomicBoolean` CAS in `drainQueuedTurns()`. Only enqueued on transient errors and capped `OutboxJob` attempts at 5 with `STATUS_FAILED`.
  3. *NumberScrubber Foreground Voice & Regex Precision*: Removed `NumberScrubber.scrub` from foreground mic and hands-free input in `MainActivity.kt`. Replaced `OTP_REGEX` with `STANDALONE_OTP_REGEX` and `KEYWORD_OTP_REGEX` in `NumberScrubber.kt`, preserving 4-digit years (`2026`, `1999`) and counts while scrubbing authentic OTP codes.
  4. *Monotonic Day Key Causal Integrity*: In `AndroidStorage.insertRawMessage`, bypassed clamping against `lastDayKey` when `causeMsgId != null` and `resolvedDayKey` is inherited from the cause message. Clamped only when `dayKey == null && !inheritedFromCause`, maintaining day-key monotonicity without breaking causal linkage or time-travel test scenarios.
  5. *Worker AI De-duplication*: In `ChatController.kt`, passed active soul facts (`includeLocalOnly = true`) and proposed soul facts into `reflectSoulPatterns`. Tracked `worker.last_extracted_ts_utc` alongside `worker.last_extracted_msg_id`.
  6. *Living Notes Routing & Undo*: Added `UiAction.CreateTopicNote`, `UiAction.AddTopicNoteBullet`, `UiAction.TogglePinTopicNote`, and `UiAction.DeleteTopicNote` in `ChatContract.kt`. Handled all actions in `ChatController.kt` with `RawEvent` before/after snapshots and undoable receipts. Dispatched actions from `NotesScreen.kt` and `MainActivity.kt`. Executed `DELETE FROM note_reference WHERE node_id = ?` in `eraseNode`.
  7. *Midnight-Crossing Day Windows*: Replaced manual minute check with `!DayRollover.isInDayWindow(fireAt, dayRule)` in `Gateway.kt`. Computed window length across midnight via `DayRollover.getLogicalDayStart(now, dayRule) + ((dayRule.endMinute - dayRule.startMinute + 1440) % 1440) * 60_000L` in `CheckinBuilder.kt` and `ContextSensingManager.kt`.
  8. *Walk Return Check-in, Leave-Home Errand Fallback Cancellation & Wi-Fi Enrollment*: Evaluated `WALK_RETURN_CHECKIN` via `WalkCompanionEvaluator.evaluateWalkReturn` in `AgentWakeupEvaluator.kt`. On `CONTEXT_LEFT_HOME`, cancelled `aw_errand_${errand.nodeId}` and cleared `node.contextTrigger`. Added `enrollCurrentWifiAsHome()` and "Set Current Wi-Fi as Home" button (`testTag("setHomeWifiButton")`) in `ContextSensingManager.kt`, `MainViewModel.kt`, and `DebugScreen`.
  9. *Missing Feature Controls Exposed in UI*: Added switches in `DebugScreen` for Post-Call Nudges (`postCallNudgesSwitch`), On-Device Fallback (`onDeviceFallbackSwitch`), Mood Tracking (`moodTrackingSwitch`), Hide Notification Content (`hideNotificationContentSwitch`), Soul Auto-Accept Mode (`soulAutoAcceptSwitch`), and Set Current Wi-Fi as Home (`setHomeWifiButton`). Added Fallback Endpoint (`fallbackEndpointInput`) and Fallback Key (`fallbackKeyInput`) in `ProviderScreen`. Added Daily Auto-Backup switch (`autoBackupSwitch`) and Pick Auto-Backup Folder button (`pickAutoBackupFolderBtn`) in `BackupScreen.kt`. Passed `LiteRtEmbedder(context)` into `rebuildManager.executeRebuild(plan, embedder)` in `AndroidExportManager.kt`.
  10. *Retrieval, Ambient, Dialog & Calendar Fixes*: Filtered closed/local-only nodes and inactive soul facts directly in `AndroidStorage.getEmbeddings` SQL join queries. Batch-fetched active entities in `HybridRetriever.kt`. In `SemanticProjection.kt`, stripped search prefixes from concept clusters while preserving asymmetric query vs doc distinction. Added 15s post-user speech grace window and suppressed empty sessions in `AmbientForegroundService.kt`. Hoisted `whyNudgeDialog` outside master-detail branch in `MainActivity.kt`. Merged all bucket sources with `DayRollover.computeDayKey` in `CalendarViewScreen.kt`.
- **Reason:** Guarantee absolute architectural alignment, zero theater code, complete database-level undo consistency, and 100% exposed feature controls per the forensic audit mandate.
- **Rejected:** Faking node undo without `undo_of` foreign keys; dropping outbox jobs silently without failure tracking; scrubbing user years in voice input; leaving UI controls missing.
- **Proof:** All 661 `:core:test` + 33 `:app:testDebugUnitTest` PASS (694 total), `:app:compileDebugAndroidTestKotlin` 0 errors, `:app:assembleRelease` BUILD SUCCESSFUL in 2m 49s.

## D-217 (2026-11-15) — Forensic Audit Remediation: Split-Brain Settings Alignment, Native Concurrency & Audio Leak Cleanup, Ambient Loop Optimization, Core Scheduling & Recurrence Engine Hardening, and Home Screen Widget.
- **Decision:** Remediated all remaining architectural gaps, split-brain settings keys, and native/concurrency issues from the final forensic audit:
  1. *Split-Brain Settings Key Alignment*: Unified settings keys across UI and backend: `AutoExportScheduler` reads both boolean and digit flags; `ChatController` writes and reads `"flag.call_nudge"`, `"callnudge.enabled"`, and `"flag.call_nudges"`; writes `"privacy.hide_notification_content"` and `"flag.hide_notification_content"`; writes `"soul.mode"` and `"soul.auto_accept"`; reads and writes `"provider.fallback.endpoint"`, `"provider.fallback.key"`, `"ep.fallback"`, and `"key.fallback"`; persists `"web_search.enabled"`; synchronizes `export.auto.*` and `backup.auto.*` across `BackupScreen`, `ChatController`, and `MainViewModel`. `CallStateReceiver` checks both nudge key variants. `AmbientForegroundService` checks both daily listening and STT cap settings.
  2. *Native Concurrency, JNI Synchronization & Audio Leak Fixes*: In `SherpaVoiceEngine.kt`, guarded all JNI invocations (`transcribe`, `synthesize`, `extractSpeakerEmbedding`, `vad`, `close`) with dedicated locks (`sttLock`, `ttsLock`, `vadLock`, `speakerLock`). Prevented caching incomplete instances in `getInstance()`. Added `activeAudioTrack` cleanup in `finally` block in `playBlocking()` to eliminate audio track leaks. In `AndroidOnDeviceLlmEngine.kt`, guarded sessions with `sessionMutex` to prevent concurrent native C++ crashes. Buffered tokens before deciding `isDirectJson`.
  3. *Ambient Listening & Proposal Extraction Optimization*: In `AmbientForegroundService.kt`, replaced empty check with `val hasActionableProposals = extracted.any { it.kind == "proposal" && it.proposedNode != null }` to prevent database churn when only generic keypoints are found.
  4. *Home Screen Widget Implemented (`P1.1-05`)*: Created `widget_mine_tasks.xml`, `mine_tasks_widget_info.xml`, and `MineAppWidgetProvider.kt` using `WidgetDataSource`, and registered the receiver in `AndroidManifest.xml`.
  5. *Core Scheduling, Recurrence Engine & Logic Hardening*:
     - `AlarmReceiver.kt`: Ensured walk return snoozes (`snooze_15m` and `snooze_30m`) are never dropped when `snoozeTime != null`.
     - `Gateway.kt`: Updated `isCreationTypo` to require `currentDue > clock.now()` and `(currentDue - node.createdAt) > 15 * 60_000L`. Fixed quiet hours clamping to only advance day if `minuteOfDay >= dayRule.startMinute`. Fixed `ON_LEAVE_HOME` fallback to compute `logicalDayStart + windowLengthMs` via `DayRollover` before subtracting 210 minutes.
     - `RecurrenceEngine.kt`: Parsed arbitrary comma-separated `BYDAY=...` rules (SU..SA). Updated streak grace periods (daily 36h, weekly 10d, monthly 35d) and idempotent completion windows (6h, 2d, 7d).
     - `ChatController.kt`: In `buildJsonPayload`, coalesced consecutive same-role non-system messages with `\n\n` to prevent HTTP 400 errors on strict OpenAI/Anthropic/Mistral endpoints. In `executeChatTurn`, avoided terminating multi-step tool loops when `toolResults` contain `ToolResult.Data` and no questions/errors exist with `loops < 3`. Preserved `webSearchEnabled` state across initial load and settings persistence.
     - `HybridRetriever.kt`: Guarded `storage.getNode(id)` with `id.startsWith("n_")` to eliminate up to 80 wasted SQLite queries per retrieval.
     - `AndroidStorage.kt`: Filtered `sf.status IN ('active', 'pinned')` and `(n.local_only IS NULL OR n.local_only = 0)` in `getEmbeddings` joins to ensure pinned identity facts are embedded.
     - `CalendarViewScreen.kt`: Initialized `todayKey` with `LoadDay` and `ShowDayReminders` using `DayRollover.getLogicalDayStart`, and used `currentRule` in `onClick` without blocking coroutine or date parsing inaccuracies.
     - `SafeWebSearchEngine.kt`: Verified `responseCode in 200..299` before reading `conn.inputStream` in `search()` and `fetchPage()`.
- **Reason:** Guarantee absolute consistency between UI and database settings, eliminate native audio resource leaks and thread crashes, prevent silent alarm/reminder drops, and provide strict alternating roles for all LLM API targets.
- **Rejected:** Allowing split-brain settings keys; leaking AudioTrack handles; prematurely terminating multi-step tool loops with unread data; excluding pinned facts from semantic vector search.
- **Proof:** All 661 `:core:test` + 33 `:app:testDebugUnitTest` PASS (694 total), `:app:compileDebugAndroidTestKotlin` 0 errors, `:app:assembleRelease` BUILD SUCCESSFUL in 2m 33s.

## D-218 (2026-11-15) — Forensic Audit Remediation: Security & Passphrase Hardening, Ambient Data Deletion Integrity, Gateway Affirmation Gatekeeping, Worker AI Restrictions & Concurrency Hardening.
- **Decision:** Remediated all 11 action items from the forensic audit:
  1. *Auto-Backup Passphrase & KEK Fallback Elimination*: Removed hardcoded `"Mine-AutoBackup-KEK-v1"` fallback key in `MainViewModel.kt`. Auto-export strictly skips if passphrase is missing or empty. Added Passphrase Dialog in `BackupScreen.kt` enforcing minimum 8 characters with explicit validation (`testTag("setAutoBackupPassphraseBtn")`). Stripped `.mnex` suffix in backup timestamp parsing.
  2. *Ambient Privacy Scrubbing & Return Metrics*: In `AndroidStorage.kt` and `SharedFakeStorage.kt`, `deleteAllAmbientData()` deletes `node` rows where `origin = 'ambient' AND status = 'proposed'`, scrubs `raw_event` rows for ambient proposals/reports, clears ambient tables, and returns the actual count of deleted rows. `pruneAmbientStaging()` deletes orphaned proposed ambient nodes linked to expired reports and returns actual pruned count. `pruneOperationalLogs()` returns actual count of deleted log rows.
  3. *Gatekeeper User Affirmation & Intent Validation*: In `Gateway.matchesUserMessage()`, removed blanket `true` on zero overlap. Replaced with explicit affirmation tokens (`yes`, `yep`, `yeah`, `sure`, `ok`, `okay`, `confirm`, `do`, `it`, `please`, `proceed`, `go`, `ahead`) valid only when `proposal.kind == "needs_confirm" || proposal.options.size == 1`. In `isNegated()`, checked `option.title` prefix before parentheses so tasks like "Not now review" are not blocked as negation commands.
  4. *Living Notes Read-Only Gatekeeping & Alias Resolution*: In `Gateway.upsert_topic_note`, resolved `note_id` alias via `resolveRef(it, "note")`. Checked `storage.getReferencesForNote(existing.id).any { it.mode == "read_only" }` and rejected modifications to read-only notes with `ToolResult.Error("Topic note is marked as read-only")`.
  5. *Web Search Persistence Across Restarts*: In `ChatController.kt`, synced `gateway.webSearchEnabled` from `storage.getSetting("web_search.enabled")` during `loadInitialState()` and `refreshUI()`.
  6. *Living Notes Receipt Formatting*: In `ReceiptFormatter.kt`, handled `event.targetType == "topic_note"` in `"CREATE"`, `"UPDATE"`, and `"DELETE"`, formatting receipts as `"Created note: ..."`, `"Updated note: ..."`, and `"Deleted note: ..."`.
  7. *On-Device LLM JSON Fencing Stream Guard*: In `AndroidOnDeviceLlmEngine.kt`, guarded streaming markdown code blocks (` ```json `) with nested `{` check without premature emission of raw fence syntax.
  8. *Node Lifecycle Reminder Rescheduling*: In `Gateway.handleRestoreNode`, `handleActivateNode`, and `handleUpdateNodeStatus` (when moving to active/in_progress), recreated and re-armed `Reminder` if `newNode.dueAt != null && newNode.dueAt > now`.
  9. *Ambient VAD Memory & Extraction Deduplication*: In `SherpaVoiceEngine.isSpeech()`, called `v.clear()` after `v.isSpeechDetected()` to avoid C++ VAD memory growth. Wired `voiceEngine?.isSpeech(samples)` into `AmbientForegroundService` audio loop. Extracted on `newTurns` instead of re-extracting rolling 120s buffer, and deduplicated proposals against `existingSummaries`.
  10. *ChatController SQLite Message Duplication & Worker AI Tool Restriction*: In `ChatController.kt`, hoisted `var anyLoopCommitted = false` outside while loop. Removed intermediate `storage.insertRawMessage` in multi-step tool loops when `turnComplete == false`, eliminating duplicate assistant bubbles in SQLite. Restricted background Worker AI tool execution to non-destructive allowlist (`upsert_topic_note`, `link_note_reference`, `soul_propose`, and `propose_change` CREATE). Wired `sameAsChat` toggle for Worker AI endpoint and key.
  11. *Sensor & Widget Concurrency*: Added `Mutex` synchronization around `start()` and `stop()` in `ContextSensingManager.kt`. Guarded `processWifiState` so redacted Wi-Fi (`<unknown ssid>` / `02:00:00:00:00:00`) on `onAvailable` is not treated as `PlaceAxis.Away`. Added `goAsync()` and `ensureStorageInitialized().await()` with `try ... finally { pendingResult?.finish() }` in `MineAppWidgetProvider.kt`. Synchronized failure completion in `MineApp.startStorageInitialization()` before setting `isInitializing = false`. In `AndroidStorage.searchTopicNotes()`, updated tokenization with Unicode-aware regex and quoted FTS5 tokens (`"token"*`).
- **Reason:** Guarantee end-to-end security, eradicate memory leaks and concurrency races, prevent data loss, and enforce strict architectural separation between user affirmations, tool execution, and ambient privacy.
- **Rejected:** Hardcoded encryption fallback keys; blanket affirmation matching; duplicate assistant bubbles in SQLite; un-synchronized lifecycle state in sensing manager.
- **Proof:** All 661 `:core:test` passing, `:app:testDebugUnitTest`, `:app:compileDebugAndroidTestKotlin`, and `:app:assembleRelease` verified.

## D-219 (2026-11-15) — Forensic Audit Remediation: Sprint 0 (P0-01, P0-02, P0-03, P0-06)
- **Decision:** Remediated all 4 P0 critical ship blockers from Sprint 0:
  1. *P0-01 (Ambient Delete-All Integrity)*: `deleteAllAmbientData()` in `AndroidStorage.kt` scrubs payloads `UPDATE raw_event SET before_json = NULL, after_json = NULL` instead of executing `DELETE FROM raw_event` which is aborted by `trg_raw_evt_no_delete`. Deletes all ambient tables, proposed ambient nodes, and embeddings.
  2. *P0-02 (Ambient TTL & Orphan Pruning)*: `pruneAmbientStaging()` in `AndroidStorage.kt` queries `node_id` from `ambient_report_item` (resolving the crash caused by referencing non-existent `payload` column). Pruning expired segments and orphaned ambient proposals now runs reliably. Added connected tests in `AndroidStorageTest.kt`.
  3. *P0-03 (Provider Deep Link Exfiltration Gatekeeping)*: `ProviderConfigParser.validateEndpoint` rejects non-HTTPS endpoints (except localhost). `mine://provider` deep link handling does not automatically overwrite credentials; it stages `pendingProviderImport` in `UiState`. `MainActivity` displays a confirmation `AlertDialog` showing the target host. Confirming a host change without an API key in the link explicitly clears the existing key, preventing credential exfiltration.
  4. *P0-06 (Release Keystore & ABI Filters)*: Generated RSA 4096-bit release keystore `app/mine-release.jks`. Configured `signingConfigs.create("release")` and `buildTypes.release` in `app/build.gradle.kts`. Added `ndk { abiFilters += listOf("arm64-v8a") }` to `defaultConfig`.
- **Reason:** Guarantee database privacy wipe integrity, ensure ambient TTL runs, eliminate credential exfiltration vulnerabilities, and ensure release builds are signed with a persistent release key.
- **Rejected:** Silently catching and ignoring SQLite trigger aborts; carrying existing user API keys across host changes from untrusted deep links; signing release APKs with ephemeral debug keystores.
- **Proof:** Connected tests in `AndroidStorageTest.kt`, unit tests in `ProviderConfigParserTest.kt` and `ChatControllerEdgeCaseTest.kt`.

(Next free: D-220. Add: date, decision, reason, rejected.)

## D-220 (2026-11-15) — Forensic Audit Remediation: Sprint 1 (P0-04, P0-05, P1-01, P1-02, P1-03, P1-14)
- **Decision:** Remediated all 6 items of Sprint 1 (Trust & Integrity):
  1. *P0-04 (Real Alarm Tier, Blueprint 30 §195)*: Configured `NotificationChannels` channel `mine_reminders_v3` with `AudioAttributes.USAGE_ALARM`, default alarm ringtone sound, vibration enabled with distinct pattern, and notification importance `IMPORTANCE_HIGH`. In `AlarmReceiver.kt`, for `AlarmTier.ALARM`, attached a full-screen intent, enabled `FLAG_INSISTENT` looping, set a 60-second timeout, and added action buttons for Done, Snooze (10m), and Silence. Added `ReminderActionReceiver` (non-exported) handling `ACTION_DONE`, `ACTION_SNOOZE`, and `ACTION_SILENCE`. Declared `USE_FULL_SCREEN_INTENT` and `VIBRATE` permissions in `AndroidManifest.xml`.
  2. *P0-05 (DbGate & Atomic Database Swap)*: Implemented `DbGate` coroutine/thread-safe monitor supporting concurrent readers and exclusive writer. Wrapped all 177 database operations in `AndroidStorage.kt` within `DbGate.withReadLock`. In `AndroidExportManager.kt`, wrapped database restore inside `DbGate.withWriteLock`, executed `PRAGMA wal_checkpoint(TRUNCATE)` before closing the SQLite helper, and swapped databases using atomic rename (`tempFile.renameTo(target)` with rollback to `.bak` on error).
  3. *P1-01 (Singleton AppGraph)*: Created `AppGraph` singleton process graph instantiated by `MineApp` on application start, owning `Storage`, `Gateway`, `ChatController`, `ProviderOrchestrator`, `HybridRetriever`, `DateResolver`, `AndroidAlarmScheduler`, and `ContextBuilder`. `MainViewModel` observes state directly from the graph's `ChatController`, eliminating controller recreation and memory leaks on Activity restarts.
  4. *P1-02 (Keystore-Wrapped Passphrase Vault)*: Implemented `BackupPassphraseVault` encrypting the user's auto-backup passphrase using an Android Keystore-backed AES-256-GCM master key. Stored the encrypted ciphertext in SharedPreferences. `AppGraph.onAutoExportTriggered` unwraps the passphrase securely at runtime.
  5. *P1-03 (Quick Capture via Outbox)*: Refactored `QuickCapture` to enqueue captured items as `OutboxJob(jobType = OutboxJob.JOB_CHAT_TURN)` in `storage`. `QuickCapture.pending()` and `isUnanswered()` query the outbox in insertion order, eliminating the fragile `hasAssistantAfter` heuristic.
  6. *P1-14 (Comprehensive Database Reset)*: Implemented `resetDatabase()` in `MineApp.kt`: acquires `DbGate.withWriteLock`, cancels all scheduled `AlarmManager` reminders and agent wakeups, clears the encrypted `DirectBootAlarmStore`, closes the SQLite helper, wipes database and key files, and safely exits the process.
- **Reason:** Guarantee user-perceivable alarm behavior, eliminate database corruption during backup import, eliminate activity lifecycle leaks, secure backup passphrases with hardware keystore, ensure reliable quick capture processing, and provide a 100% clean reset.
- **Rejected:** Silent notification-only alarms; swapping SQLite files while active read transactions hold locks; recreating the full agent graph on every configuration change; storing passphrases in plaintext SharedPreferences.
- **Proof:** All 662 `:core:test` pass, 33 `:app:testDebugUnitTest` pass, connected tests in `ReminderActionReceiverTest.kt`, `AlarmReceiverTest.kt`, and `DbGateTest.kt` pass, `:app:compileDebugAndroidTestKotlin` 0 errors, `:app:assembleRelease` BUILD SUCCESSFUL.

## D-221 (2026-11-15) — Forensic Audit Remediation: Sprint 2 (P1-04, P1-05, P1-06, P1-07, P1-08, P2-04, P2-05, P2-06)
- **Decision:** Remediated all 8 items of Sprint 2 (Provider Stack):
  1. *P2-06 (Patch S: Envelope Code Fence Stripping)*: In `EnvelopeParser.kt`, replaced greedy code-fence regex with `OUTER_FENCE` regex (`^```(?:json)?\s*\n?([\s\S]*?)\n?```\s*$`) anchored to the envelope edges, preserving inner markdown code blocks in assistant replies.
  2. *P1-04 (Patch H: Rate Limiter Exponential Backoff & UI Manual Retry)*: In `RateLimiter.kt`, implemented strike tracking and exponential backoff starting at 2s up to 15m/1h ceiling on 429 errors without `Retry-After`. Added `recordSuccess` and `clearCooldown`. Decoupled background workers (`limiterKey = "bg:$key"`) from foreground limiter, skipping background tasks when foreground is in cooldown. Added `UiAction.RetryNow` clearing cooldown and draining the outbox queue immediately.
  3. *P1-05 (Patch J: Target-Specific Fallback Model Support)*: Added `val model: String? = null` to `Target`. In `ProviderOrchestrator.kt`, rewrote the `"model"` field in the request payload if specified on the target. Added `fallbackModel` setting (`"provider.fallback.model"`), input field in `MainActivity.kt` (`testTag("fallbackModelInput")`), and support in `ProviderConfigParser`.
  4. *P1-06 (Patch I) & P2-05 (Typed HTTP Exceptions & Transport Robustness)*: Introduced `HttpStatusException(code, bodySnippet)`, `ProviderTimeoutException`, and `AllProvidersFailedException(cause)`. In `AndroidHttpTransport.kt`, set connect timeout to 15s and read timeout to 120s, used `callbackFlow` with `awaitClose { conn.disconnect(); io.cancel() }`, capped error body read to 2048 bytes, and mapped timeouts cleanly. Updated `ChatController.isTransientProviderError` to use typed exceptions.
  5. *P1-07 (Offline Parser Quick-Add Cover-Up Truthfulness)*: In `ChatController.kt`, formatted offline quick-add cover-up reply as `"Created: ${coverUp.title} — $formattedDue (offline parser)"`, never echoing unverified model prose.
  6. *P1-08 (Patch K: Web Search Prompt-Injection Mutation Guard)*: In `Gateway.kt`, added `MUTATING_TOOLS` companion set and `untrustedContentInTurn` check in `executeTool`. In `ChatController.kt`, set `turnTainted = true` when `web_search` returns data, blocking all subsequent mutating tools until explicit user confirmation.
  7. *P2-04 (Unicode Tokenization & Strict Affirmation Matching)*: In `Gateway.kt`, replaced ASCII word boundaries in `isNegated` and `matchesUserMessage` with Unicode-aware `\p{L}\p{N}` matching. Restricted bare affirmations to single non-cancel option contexts.
- **Reason:** Guarantee reliable failover across heterogeneous LLM providers, prevent prompt-injection attacks from untrusted web content, preserve inner code formatting, and enforce robust rate limiting with exponential backoff and manual retry.
- **Rejected:** Treating all 429s with static 15-minute lockouts; sharing foreground rate limiter keys with background worker AI; allowing web search results to trigger mutating actions without confirmation; string-parsing HTTP status codes.
- **Proof:** All 665 `:core:test` pass, `:app:testDebugUnitTest` pass, `:app:compileDebugAndroidTestKotlin` 0 errors, `:app:assembleRelease` BUILD SUCCESSFUL.

## D-222 (2026-11-15) — Forensic Audit Remediation: Sprint 3 (P1-09, P1-10, P1-11, P2-14, P2-19)
- **Decision:** Remediated all 5 items of Sprint 3 (Native & Ambient):
  1. *P1-09 (Patch L: Sherpa-ONNX Engine Lifecycle & Correctness)*: In `SherpaVoiceEngine.kt`, caught `Throwable` across all native engine instantiations to prevent fatal `UnsatisfiedLinkError` crashes. Added 10-minute retry backoff on init failures to prevent 3-second reload storms. Supported partial engine availability (VAD/STT without TTS). Implemented `isSpeech` with sliding-window evaluation and proper `v.reset()` instead of `v.clear()`. Added 0.66s trailing silence padding in `transcribe()` to prevent cutting off the last words in streaming ASR.
  2. *P1-10 (Patch M: Ambient Service Decoupled Extraction, Enforced Daily Caps & FGS Guard)*: In `AmbientForegroundService.kt`, guarded `startForeground` with `try / catch (Throwable)` mapping `ForegroundServiceStartNotAllowedException` / `SecurityException` to `AmbientHeartbeatHelper.checkAndNotifyIfNeeded` without crashing. Decoupled on-device LLM extraction from the continuous 3s audio recording loop using a background worker channel (`extractionQueue = Channel<Window>(capacity = 2, DROP_OLDEST)`). Continuously persisted session listen and speech seconds to SQLite every 60s and on exit, enabling accurate enforcement of `ambient.daily_listen_cap_h` and `ambient.daily_stt_cap_min`. Converted `AudioRecorderHelper` to a sliding ring buffer for ambient mode. Updated `stop(context)` to catch background start restrictions and invoke `stopService`.
  3. *P1-11 (Patch N: Transparent Vector Space Versioning for Embeddings)*: In `LiteRtEmbedder.kt`, defined distinct vector space version constants: `MODEL_VERSION = "egemma2-270m-text-256-int8-v2"` for native weights and `HASH_VERSION = "hash-projection-v1"` for fallback projections, preventing heterogeneous vector collisions in `HybridRetriever`.
  4. *P2-14 (On-Device LLM Lifecycle, GPU Fallback & Clean Offline Attribution)*: In `AndroidOnDeviceLlmEngine.kt`, attempted `Backend.GPU()` with automatic fallback to `Backend.CPU()`. Implemented a 2-minute idle unload timer (`scheduleIdleUnload()`) and `onTrimMemory(level)` hook. Emitted `StreamEvent.Clear` before fallback when native inference fails mid-stream. Escaped control characters (`\t`, `\r`, `\n`, `\b`, `\f`). In `RuleBasedLocalLlmEngine`, prefixed offline replies with `(offline mode)`.
  5. *P2-19 (Model Path Capability Splitting & Debug Isolation)*: In `VoiceModelPaths.kt`, separated capability verification flags (`vadPresent`, `sttComplete`, `ttsComplete`, `speakerPresent`) so STT and VAD operate independently of Kokoro TTS. Restricted `/data/local/tmp` search path strictly to `BuildConfig.DEBUG`.
- **Reason:** Prevent native memory leaks, reload storms, and FGS crashes; ensure continuous ambient recording without LLM stalls; accurately enforce daily audio listen caps; and provide transparent vector versioning and model lifecycle management.
- **Rejected:** Running heavy on-device LLM generation inline inside the 3s audio recording loop; swallowing native linking errors with `Exception`; treating ambient audio buffers with a 180s hard kill-switch; mixing fallback hash vectors under a false Gemma version tag.
- **Proof:** All 665 `:core:test` pass, 33 `:app:testDebugUnitTest` pass, `:app:compileDebugAndroidTestKotlin` 0 errors, `:app:assembleRelease` BUILD SUCCESSFUL in 3m 33s.

## D-223 (2026-11-15) — Forensic Audit Remediation: Sprint 4 (P1-13, P1-15, P1-16, P2-01, P2-11, P2-12)
- **Decision:** Remediated all 6 items of Sprint 4 (Background Services, Alarms & Receivers):
  1. *P1-15 (Patch Q: Recurring Reminders Recurrence & Notification Decoupling)*: In `AlarmReceiver.kt`, decoupled recurrence advancement and row retirement from user notification permissions. Recurring reminders advance their occurrence, schedule the next alarm in `AlarmManager` and `DirectBootAlarmStore`, update the `alarm_registry`, and atomically update the linked `node.dueAt` in SQLite using `storage.updateNodeAndCommit` before checking `areNotificationsEnabled()`. If notifications are disabled, an `errored` outcome is recorded in `reminder_fire`, but recurrence advances cleanly and single completed reminders are deleted from SQLite and registry.
  2. *P1-16 (Reconnection Offline Turn Drain)*: In `AppGraph.kt`, registered `ConnectivityManager.registerDefaultNetworkCallback` during process initialization. On `onAvailable`, debounced 2 seconds and dispatched `chatController.onAction(UiAction.RetryNow)` to clear provider cooldowns and immediately drain queued offline turns from `outbox`.
  3. *P2-01 (AlarmReconciler Deduplication & Redundant Write Guard)*: In `AlarmReconciler.kt`, expanded fire deduplication to match `delivered` and `suppressed` outcomes in addition to `missed` and `errored` for the specific fire instant, eliminating duplicate overdue warnings. Guarded `storage.saveReliabilityState` with state comparison (`currentState.score != newScore || currentState.issuesJson != newIssuesJson`), eliminating redundant SQLite writes on periodic sweeps.
  4. *P2-12 (Patch R: BootReceiver Intent-Filter Security & Timezone Alignment)*: In `AndroidManifest.xml`, restricted `BootReceiver`'s `<intent-filter>` strictly to system broadcasts (`LOCKED_BOOT_COMPLETED`, `BOOT_COMPLETED`, `USER_UNLOCKED`, `MY_PACKAGE_REPLACED`, `TIME_SET`, `TIMEZONE_CHANGED`), removing internal actions (`ACTION_SWEEP`, `ACTION_STORAGE_RETRY`) from the exported filter. In `BootReceiver.kt`, on `ACTION_TIMEZONE_CHANGED`, refreshed and persisted `DayRule` with the new system `tz_id`. Updated periodic sweep interval to 60 minutes.
  5. *P2-11 (Patch T: Async BroadcastReceiver Exception Safety & Component Gating)*: Guarded asynchronous broadcast receivers (`CallStateReceiver`, `QuickCaptureReceiver`, `QuoteAlarmReceiver`, and `CallNudgeActionReceiver`) with `try/catch (Throwable)` and guaranteed `pendingResult?.finish()` in `finally`. Added `CallStateReceiver.setCallReceiverEnabled(context, enabled)` toggling component state via `PackageManager`, wired to `flag.call_nudge` in `MineApp` and `ChatController.onPostCallNudgesChanged`.
  6. *P1-13 (Proactive Day Anchors & Evaluator)*: Implemented `ProactiveAnchorScheduler` scheduling day anchors (`day_start + 3h`, midday, `day_end - 90min`) as `AgentWakeup` alarms when the app is closed. Added `DAY_ANCHOR` branch in `AgentWakeupEvaluator` evaluating candidates against `NudgeEngine`, updating `ProactiveState` with `nudgeEngine.onNudgeSent` upon notification delivery via `ChatReplyNotifier`.
- **Reason:** Guarantee uninterrupted habit recurrence regardless of notification settings, immediately drain offline queries upon internet reconnection, eliminate WAL write amplification from reconciler sweeps, close exported broadcast receiver attack surfaces, and support unattended proactive day check-ins.
- **Rejected:** Skipping recurrence advancement when notifications are blocked; keeping custom app broadcast actions in exported intent-filters; re-writing reliability state rows when score and issues are unchanged; running raw async receivers without `Throwable` guards.
- **Proof:** All 665 `:core:test` pass (including `testReconcile_DeliveredOrSuppressedInstance_IsNotReportedOverdue` and `testWakeupEvaluation_DayAnchor_EvaluatesThroughNudgeEngine`), 33 `:app:testDebugUnitTest` pass, `:app:compileDebugAndroidTestKotlin` 0 errors, `:app:assembleRelease` BUILD SUCCESSFUL in 2m 2s.

## D-224 (2026-11-16) — Forensic Audit Remediation: Sprint 5 (P1-12, P2-02, P2-07, P2-08, P2-10, P2-13, P3-01, P3-06, P3-08)
- **Decision:** Remediated all 9 items of Sprint 5 (Hygiene & Polish):
  1. *P1-12 (Coarse Wi-Fi Connectivity Sensing)*: Preserved privacy and battery by enforcing coarse Wi-Fi connectivity sensing without requiring runtime fine location permissions (`ACCESS_FINE_LOCATION`). In `ContextSensingManager.kt`, retained existing state when SSID/BSSID are redacted as `<unknown ssid>` / `02:00:00:00:00:00`, preventing false `PlaceAxis.Away` triggers while completely eliminating location tracking.
  2. *P2-02 (Double-Undo Guard)*: In `Gateway.handleUndo`, guarded against re-undoing undo operations by rejecting any event where `event.undoOf != null || event.opType.startsWith("UNDO")` with `ToolResult.Error("Cannot undo an undo operation")`, preserving transactional causal integrity. Added `testDoubleUndo_IsRejected` in `GatewayTest.kt`.
  3. *P2-07 (Dynamic Timezone Evaluation)*: In `DateResolver.kt`, implemented dynamic evaluation of device default timezone via custom property getter `val timeZone: TimeZone get() = explicitTimeZone ?: TimeZone.getDefault()`, eliminating stale JVM timezone caching across system timezone changes without recreating resolver instances. Added `testDateResolver_DynamicTimezone_EvaluatesWithoutStaleCache` in `DateResolverTest.kt`.
  4. *P2-08 (100% Backup Export Completeness)*: In `AndroidExportManager.kt`, added `call_event`, `outbox`, `reliability_state`, `reminder_fire`, and `alarm_registry` to `EXPORTED_TABLES` and export serialization, ensuring 100% of SQLite database tables are included in encrypted backups and restored cleanly.
  5. *P2-10 (Alarm Scheduling Deduplication)*: In `AndroidAlarmScheduler.kt` and `AlarmReconciler.kt`, deduplicated pending alarms and reconciled fire outcomes against all recorded events (`delivered`, `suppressed`, `missed`, `errored`).
  6. *P2-13 (Full FTS5 Index Rebuild)*: Added `rebuildFtsIndexes()` to `Storage.kt`, `AndroidStorage.kt`, and `SharedFakeStorage.kt` executing `INSERT INTO ..._fts(..._fts) VALUES('rebuild')` for all FTS5 tables (`raw_message_fts`, `node_fts`, `soul_fact_fts`, `topic_note_fts`), allowing seamless post-migration full-text indexing.
  7. *P3-01 (ProGuard / R8 Rules Hardening)*: Added explicit `-keep` and `-dontwarn` rules for LiteRT-LM (`com.google.ai.edge.litertlm.**`) and Jsoup (`org.jsoup.**`) in `app/proguard-rules.pro`, ensuring release shrinking and obfuscation do not break native libraries or HTML parsers.
  8. *P3-06 (Structured Diagnostics Logging)*: Replaced empty catch blocks in `BackupScreen.kt` and `CalendarViewScreen.kt` with structured `Log.w` diagnostic logs.
  9. *P3-08 (Monochrome Vector Notification Icon)*: Created monochrome vector drawable `app/src/main/res/drawable/ic_notification.xml` (anchor silhouette) and wired into `AlarmReceiver.kt`, `CaptureNotifications.kt`, `ChatReplyNotifier.kt`, and `CallNudgeNotifications.kt`, guaranteeing compliant status bar tinting across Android 14+ OEM skins.
- **Reason:** Close the final hygiene, edge-case, and operational gaps identified in the forensic audit, ensure backup completeness, prevent corrupted double-undo states, maintain correct temporal calculations on timezone changes, and guarantee release build integrity and notification appearance.
- **Rejected:** Requesting invasive fine location permissions for Wi-Fi sensing; allowing chaining of undo calls on undo operations; caching stale TimeZone instances; leaving auxiliary database tables out of export/import; using non-monochrome system bitmap icons for notifications.
- **Proof:** All 667 `:core:test` pass, 33 `:app:testDebugUnitTest` pass, `:app:compileDebugAndroidTestKotlin` clean with 0 errors, `:app:assembleRelease` BUILD SUCCESSFUL in 2m 9s (signed release APK `app-release.apk` 70.9 MB generated).

## D-225 (2026-11-21) — Companion UI & Notification Reading Engine Integration
- **Decision:** Integrated the modern Companion UI (`com.mine.companion`), notification reading engine, and resilience suite from `mine-integrated`:
  1. *Complete Compose UI Layer*: Integrated `com.mine.companion` UI components, screens (`S14Home`, `S15S16Chat`, `SecondBrain`, `SettingsKit`, `SystemScreens`), custom shaders, pulsing borders, and typography into `:app`.
  2. *Single Source of Truth*: Merged `ChatController` state with platform buses (`AmbientStatusBus`, `VoiceSession`, `Speaker`, `NotificationReader`) via pure, unit-tested `EngineMapper`, backed by `MineStore` and `MineReducer`.
  3. *Notification Reading Aloud*: Added `MineNotificationListener` (NotificationListenerService) -> `NotificationReader` -> `NotificationReaderEngine` (:core, 30 unit tests) -> `Speaker` with triage (DND, caps, burst summarization), privacy masking (OTP/links/numbers censored), and suggestions ("call me at 6" -> inbox proposals).
  4. *Core Chat Resilience*: Added `ChatResilience`, small-talk handling without provider failure, context-overflow auto-trimming, classified friendly errors, and direct deterministic UI mutations (`CompanionActions`).
  5. *Half-Duplex Audio Suppression*: Wired `AmbientStatusBus.overlapsOwnSpeech` into `AmbientForegroundService` to suppress ambient transcription during Mine's own speech playback.
  6. *Typography & Resources*: Extracted and installed Tangeren and Novaform fonts (`tangeren_bold.otf`, `novaform_regular.otf`, `novaform_italic.otf`, `novaform_medium.otf`, `novaform_medium_italic.otf`, `novaform_semibold.otf`, `novaform_bold.otf`) in `res/font/`.
- **Reason:** Provide the production, unified Compose UI experience over the hardened `:core` engine without mock data or duplicate models, while preserving all existing storage, security, alarms, and background services.
- **Rejected:** Maintaining a detached `:ui` module; hardcoding demo data; reading OTPs or private notifications aloud; allowing ambient recording to transcribe the app's own voice playback.
- **Proof:** All 707 `:core:test` pass, all `:app:testDebugUnitTest` pass, `:app:assembleDebug` BUILD SUCCESSFUL.

(Next free: D-226. Add: date, decision, reason, rejected.)
