<!-- SPDX-License-Identifier: GPL-3.0-or-later -->
<!-- Copyright (C) 2026 Mine contributors -->
<!-- Part of Mine. See LICENSE in the repository root. -->

# UI Contract
Every screen in Mine is mapped to `UiState` and `UiAction`. The UI is completely passive. All logic, timing, formatting, and persistence are handled by the core and background infrastructure modules.

## 1. State (`UiState`)
The single source of truth for the UI at any given moment:
- `chatMessages`: List<ChatMessage> (The active session's chat bubbles)
- `receipts`: List<ReceiptState> (Tool execution receipts and undo tokens, anchored by causeMsgId)
- `openProposals`: List<Proposal> (Disambiguation chips with titles and options)
- `proposedSoulFacts`: List<SoulFact> (Soul memory proposals for accept/reject)
- `staleItems`: List<Node> (Inactive nodes over 30 days old moved to inbox for review)
- `inboxCount`: Int (Total count of open proposals, proposed soul facts, and stale items)
- `urgentItems`: List<Node> (Top urgent tasks sorted by due date)
- `isChatLoading`: Boolean (True when waiting for network or streaming)
- `activeReminders`: List<Reminder> (Reminders scheduled for the future)
- `selfTestStatus`: String (Diagnostics test status)
- `providerEndpoint`, `providerKey`, `providerModel`: String (AI provider configuration)
- `debugStats`: String (Diagnostics rows and key metrics)
- `composerDraft`: String (Safely stores the user's input across config changes)
- `connectionError`: String? (Transient API/Network errors)
- `globalError`: String? (Catastrophic Storage/Keystore errors)
- `notificationsBlocked`: Boolean (True if POST_NOTIFICATIONS is denied by user or OS)
- `schedulingError`: String? (First alarm scheduling error reason if at_risk)
- `schedulingFailedCount`: Int (Count of failed alarm scheduling operations)
- `missedReminders`: List<ScheduleResult.Overdue> (Reminders whose fire instant passed while device was off)
- `showResetConfirm`: Boolean (Keystore reset confirmation state)
- `alarmDisclosureText`: String (System status bar disclosure for Alarm vs Gentle tiers)
- `selectedDayKey`: String? (Day key for paginated history view)
- `selectedDayMessages`: List<ChatMessage> (Messages loaded for selected day)
- `selectedDayReminders`: List<Reminder> (Reminders loaded for selected day)
- `homeQuote`: Quote? (Current daily/slot home quote)
- `atRisk`: Boolean (True when ReliabilityState.score == "at_risk"; schedulingError carries the reason)
- `activeThread`: ActiveThread? (The single open proactive thread: msgId + openedAt)
- `homePriorityList`: List<Node> (Code-ranked Home priority list; see D-121)
- `recentlyClosed`: List<RecentlyClosed> (Nodes closed within 24h; each carries undoValid and undoToken)
- `chatItems`: List<ChatItem> (Structured timeline composed from chatMessages, receipts, and open proposals)
- `memoryNodes`: List<Node> (Read-only active/inbox memory items from SQLite)
- `memorySoulFacts`: List<SoulFact> (Active and confirmed soul facts)
- `trashNodes`: List<Node> (Soft-deleted / archived nodes for trash recovery)
- `memoryLoaded`: Boolean (True when memory viewer data has been hydrated)
- `nudgeGate`: String? (Proactive tick verdict candidate)
- `onboardingDone`: Boolean (True if user has completed initial onboarding)
- `onboardingStep`: Int (Active onboarding step: 0 Welcome, 1 Consent, 2 Your day, 3 Soul seed)
- `onboardingAnswers`: Map<String, String> (Initial soul seed answers)
- `onboardingHealthLocalOnly`: Boolean (Local-only flag for health privacy)
- `onboardingError`: String? (Onboarding persistence error)
- `queuedChatIds`: List<String> (Outbox queued messages waiting for connectivity)
- `learnedPrefs`: List<LearnedPref> (Behavioral preferences learned by system)
- `whyNudgeText`: String? (User-visible proactive reasoning disclosure)
- `activePersonaId`, `activePersonaName`: String (Active personality profile)
- `appLockEnabled`, `isAppLocked`, `relockTimeoutSec`, `blockScreenshots`, `compactMode`: App lock and privacy settings
- `latestAmbientReport`: AmbientReport? (Today's generated ambient listening daily digest)
- `ambientReportItems`: List<AmbientReportItem> (Proposed commitments and facts from ambient sessions)
- `ambientEnabled`: Boolean (True if ambient microphone service is active)
- `voiceprintEnrolled`: Boolean (True if user's acoustic voiceprint embedding is enrolled)
- `latestInsightsReport`: FullInsightsReport? (Weekly/monthly review stats, 7-day activity bars, goal hierarchies, habit streaks, 24h heat strip, opt-in mood)
- `handsFreeState`: HandsFreeState (IDLE, LISTENING, PROCESSING, SPEAKING; half-duplex continuous voice)
- `webSearchEnabled`: Boolean (True when web search toggle 🌐 is active in composer)

## 1b. Structured timeline items (`ChatItem`, D-121)
`UiState.chatItems` is composed by `ChatTimelineBuilder.build(messages, receipts, openProposals)`:
- `DayHeader(dayKey, tsUtc)`: Inserted whenever a message's dayKey changes.
- `TimeDivider(tsUtc)`: Reserved for intra-day time dividers.
- `Message(id, role, content, tsUtc)`: A user/assistant bubble.
- `SystemEvent(text, tsUtc)`: A quiet lifecycle line (system role with non-blank content).
- `ReceiptItem(receipt)`: A ReceiptState attached directly under its cause message.
- `ProposalRow(proposal)`: An open disambiguation proposal rendered as a chip row at the end of the timeline.
- `CheckinCard(title, summary, tsUtc)`: Check-in snapshot card (populated by P1-E 25).
- `Nudge(content, tag, reason, tsUtc)`: A proactive nudge with tag chip and "Why?" explanation.
- `StatusNotice(text, isError, tsUtc)`: An inline status/error notice.

## 1c. Home data (D-121)
- `homePriorityList`: Fresh (non-stale, non-local-only, active/in_progress/paused) nodes ranked in code by `rankHomeNodes`: 1) overdue first; 2) in_progress; 3) pinned; 4) severity desc; 5) priority desc; 6) due soonest; 7) most recently updated; ties by id. Default limit 5.
- `recentlyClosed`: Nodes with status done/cancelled and closed_at within the last 24h, newest first. Each entry carries `undoValid` and `undoToken` when reversible.
- `activeThread`: Derived from `ProactiveState.openThreadMsgId/threadOpenedAt`; null when no thread is open.

## 2. Actions (`UiAction`)
The only events the UI can trigger:
- `OpenHome`: Triggers initial quote check and state sync when navigating to Home.
- `UpdateDraft(text: String)`: Updates the composer draft safely.
- `SendMessage`: Appends draft and starts the AI turn.
- `Stop`: Instantly halts the AI stream and cancels active coroutine job.
- `Undo(token: String)`: Instantly reverts an operation (bypasses LLM).
- `ConfirmProposal(proposalId, choiceId)`: Resolves ambiguity instantly (bypasses LLM).
- `AcceptSoulFact(factId: String)`: Confirms a proposed soul fact.
- `RejectSoulFact(factId: String)`: Rejects a proposed soul fact.
- `CancelReminder(id: String)`: Cancels active reminder and alarm.
- `LoadDay(dayKey: String)`: Paginates chat history for a specific day.
- `ShowDayReminders(dayKey, startOfDayMs, endOfDayMs)`: Loads reminders for a day.
- `ClearDaySelection`: Returns to recent chat mode.
- `SaveProvider(endpoint, key, model)`: Persists settings.
- `ImportProviderConfig(payload)`: Decodes and applies provider settings from JSON.
- `RunSelfTest`: Runs 5-second self-test alarm.
- `RefreshStats`: Refreshes debug statistics.
- `PromptResetDatabase`: Initiates keystore reset confirmation flow.
- `ConfirmResetDatabase(confirm: Boolean)`: Confirms or cancels database reset.
- `AddFollowUp(srcNodeId, dstNodeId)`: Links two nodes with a follow_up edge.
- `RemoveFollowUp(edgeId: String)`: Removes a follow_up edge link.
- `KeepStale(nodeId: String)`: Resets stale status and updates touch timestamp.
- `CancelStale(nodeId: String)`: Cancels a stale node and dismisses it from inbox.
- `LoadMemory`: Loads memory nodes and soul facts.
- `ArchiveNode(nodeId)`: Soft-deletes a node to trash.
- `DeleteNode(nodeId)`: Cancels/deletes a node.
- `RestoreNode(nodeId)`: Restores an item from trash.
- `EraseNode(nodeId)`: Permanently scrubs a node and its history from SQLite.
- `EraseSoulFact(factId)`: Permanently scrubs a soul fact from SQLite.
- `UpdateNodeStatus(nodeId, newStatus)`: Updates task status (evaluates habit recurrence streaks if recurring).
- `ReloadStorage`: Reloads all memory, receipts, and state after database rebind.
- `TickProactive`: Evaluates proactive nudge candidate.
- `OnboardingNext`, `OnboardingBack`, `OnboardingAnswer`, `OnboardingHealthLocalOnly`, `OnboardingConfirmDay`, `OnboardingFinish`: Onboarding wizard navigation and persistence.
- `DrainQueuedTurns`: Attempts immediate processing of offline queued outbox turns.
- `WhyNudge(msgId)`: Discloses why a proactive nudge was triggered.
- `LockLearnedPref`, `ResetLearning`: Manages behavioral adaptation prefs.
- `SetPersona(personaId)`: Activates companion persona profile.
- `UnlockApp`, `LockApp`, `SetAppLock`, `SetRelockTimeout`, `SetBlockScreenshots`, `SetCompactMode`: Security configuration.
- `AcceptAmbientItem(itemId)`: Converts ambient proposed commitment into active task or confirmed soul fact.
- `RejectAmbientItem(itemId)`: Dismisses ambient proposed item.
- `AcceptAllAmbientItems(reportId)`: Bulk-activates all ambient proposals from a report.
- `DeleteAllAmbientData`: Purges all ambient sessions, windows, segments, reports, items, and proposals.
- `BookmarkAmbientLast2Min`: Retroactively captures the last 120 seconds of ambient audio regardless of speaker gate.
- `LoadInsights(days)`: Recomputes and populates `latestInsightsReport` for analytics dashboard.
- `SetHandsFree(enabled)`: Activates or halts hands-free continuous voice conversation mode.
- `UpdateHandsFreeState(state)`: Updates half-duplex turn state (IDLE, LISTENING, PROCESSING, SPEAKING).
- `ToggleWebSearch`: Inverts active web search toggle state and updates gateway.
- `SetWebSearch(enabled)`: Sets explicit web search toggle state and updates gateway.
- `RetryNow`: Immediately clears provider 429 backoff cooldowns and triggers offline outbox queue draining.
- `ConfirmProviderImport`: Confirms deep-link provider configuration import, applying new endpoint, model, and clearing existing key on host change without key.
- `RejectProviderImport`: Rejects pending deep-link provider configuration import.
- `SetContextSensing(enabled)`: Toggles opportunistic walk and errand context sensing triggers.

## 3. Adaptive UI & Layout Contract (D-199, D-208, D-213)
- **Compact Form Factor (< 600dp width or < 400dp height)**:
  - Single-column layout with top `PrimaryScrollableTabRow` across all 10 navigation tabs (`Chat`, `Reminders`, `Provider`, `Backup`, `Debug`, `Memory`, `Board`, `Calendar`, `Insights`, `Notes`).
  - `ChatScreen` composer row features the `🌐` web search toggle button (`Modifier.testTag("webSearchToggleButton")`) alongside hands-free, text input, audio recording, and send.
- **Large-Screen / Tablet / Foldable Form Factor (>= 600dp width && >= 400dp height)**:
  - Vertical `NavigationRail` pinned to the start edge with category icons and labels across all 10 tabs (`Chat 💬`, `Reminders ⏰`, `Provider 🤖`, `Backup 💾`, `Debug 🛠️`, `Memory 🧠`, `Board 📋`, `Calendar 📅`, `Insights 📊`, `Notes 📝`).
  - Dual-Pane Productivity Workspace:
    - Left Pane (48% width): `ChatScreen` with hands-free banner, web search toggle, and interactive composer.
    - Right Pane (52% width): Companion Workspace corresponding to the active tab (defaults to `InsightsScreen` or `BoardViewScreen` when Chat is active).
  - Preserves full touch targets and layout stability across orientation changes.

## 4. Backend Capabilities (Exposed via Storage Interface)
The UI layer can query the `Storage` interface directly for data visualization without triggering state mutations:
- **Living Topic Notes & References (D-208, D-209)**:
  - `getTopicNotes()`, `getTopicNote(id)`, `getTopicNoteByTopic(topic)`, `saveTopicNote(note)`, `deleteTopicNote(id)`
  - `getReferencesForNode(nodeId)`, `getReferencesForNote(noteId)`, `linkNoteReference(ref)`, `deleteNoteReference(id)`
- **Deadlines & Rescheduling Slippage (D-205)**:
  - `original_due_at`, `due_history_json`, `slip_count` on `node`.
  - Strikethrough deadlines rendered across board and calendar views.
- **Conditioned Wakeups (D-207)**:
  - `getPendingAgentWakeups(now)`, `getAgentWakeup(id)`, `saveAgentWakeup(wakeup)`, `deleteAgentWakeup(id)`
- **Smart Web Search Pipeline (D-213)**:
  - DuckDuckGo HTML and Jsoup article extraction with SSRF blocklist and untrusted data wrapping `<data source="web" integrity="untrusted">`.
- **Day Paging**: Query `getMessagesForDay(dayKey)` from `raw_message`.
- **Calendar Heat-Map**: Query `getHeatMapCounts(startDayKey, endDayKey)` and `getDaysWithMessages(startDayKey, endDayKey)` from `raw_message`.
- **Reminder Queries**: Query `getRemindersBetween(startMs, endMs)` and `getRemindersForDay(startOfDayMs, endOfDayMs)` from `reminder`.
- **Soul/Memory Reads**: Query active/stale nodes, node counts, and soul facts from `node` and `soul_fact`.
- **Quote Slots**: Query `getQuoteSlot(slot, dayKey)` from `quote_slot`.
- **Follow-up Graph**: Query `getEdgesForSource(nodeId)` / `getEdgesForDestination(nodeId)` with `type = 'follow_up'` from `edge`.
- **Home Queries (D-121)**: `getHomePriorityList(now, limit)` (fresh active nodes, code-ranked), `getRecentlyClosed(now, limit)` (closed within 24h), `getLatestEventForTarget(targetType, targetId)`.
- **Insights & Analytics Range Queries (D-196)**:
  - `getFullInsightsReport(now, days, includeMood)`
  - `getNodesCreatedBetween(startMs, endMs)`
  - `getNodesClosedBetween(startMs, endMs)`
  - `getCancelledEventsBetween(startMs, endMs)`
  - `getGoalNodes()`, `getHabitNodes()`
  - `getActionTimestampsBetween(startMs, endMs)`
- **Ambient Staging & Proposals (D-195)**:
  - `getLatestAmbientReport()`, `getAmbientReportItems(reportId)`
  - `pruneAmbientStaging(now)` (enforces 48h transcript / 7d proposal TTL)
- **Voiceprint Biometrics (D-194)**:
  - `getVoiceprint()`, `saveVoiceprint(voiceprint)`, `deleteVoiceprint()` (device-local, excluded from export allowlist).
- **On-Device LLM Fallback (D-198)**:
  - `provider.ondevice.enabled` ("0" or "1"), `provider.ondevice.model_path`
  - Fallback target `Target("local://ondevice", "local", 120)` evaluated when offline or on cloud failure.
