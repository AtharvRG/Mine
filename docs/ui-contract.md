<!-- SPDX-License-Identifier: GPL-3.0-or-later -->
<!-- Copyright (C) 2026 Mine contributors -->
<!-- Part of Mine. See LICENSE in the repository root. -->

# UI Contract
Every screen in Mine is mapped to `UiState` and `UiAction`. The UI is completely passive. All logic, timing, and formatting are handled by the core module.

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
- `schedulingError`: String? (First alarm scheduling error reason if at_risk)
- `schedulingFailedCount`: Int (Count of failed alarm scheduling operations)
- `showResetConfirm`: Boolean (Keystore reset confirmation state)
- `alarmDisclosureText`: String (System status bar disclosure for Alarm vs Gentle tiers)
- `selectedDayKey`: String? (Day key for paginated history view)
- `selectedDayMessages`: List<ChatMessage> (Messages loaded for selected day)
- `selectedDayReminders`: List<Reminder> (Reminders loaded for selected day)
- `homeQuote`: Quote? (Current daily/slot home quote)
- `atRisk`: Boolean (True when ReliabilityState.score == "at_risk"; schedulingError carries the reason)
- `activeThread`: ActiveThread? (The single open proactive thread: msgId + openedAt, from ProactiveState.openThreadMsgId/threadOpenedAt)
- `homePriorityList`: List<Node> (Code-ranked Home priority list; see D-121)
- `recentlyClosed`: List<RecentlyClosed> (Nodes closed within 24h; each carries undoValid and undoToken)
- `chatItems`: List<ChatItem> (Structured timeline composed from chatMessages, receipts, and open proposals)

## 1b. Structured timeline items (`ChatItem`, D-121)
`UiState.chatItems` is composed by `ChatTimelineBuilder.build(messages, receipts, openProposals)`; the UI renders each item by type:
- `DayHeader(dayKey, tsUtc)`: Inserted whenever a message's dayKey changes.
- `TimeDivider(tsUtc)`: Reserved for intra-day time dividers (not yet emitted).
- `Message(id, role, content, tsUtc)`: A user/assistant bubble (from raw_message roles other than "system").
- `SystemEvent(text, tsUtc)`: A quiet lifecycle line (system role with non-blank content).
- `ReceiptItem(receipt)`: A ReceiptState attached directly under its cause message (matching causeMsgId); orphaned receipts (no cause or cause outside the loaded window) render at the end of the timeline.
- `ProposalRow(proposal)`: An open disambiguation proposal rendered as a chip row at the end of the timeline.
- `CheckinCard(title, summary, tsUtc)`: Check-in snapshot card (populated by P1-E 25).
- `Nudge(content, tag, reason, tsUtc)`: A proactive nudge with its tag chip and "Why?" reason (populated by P1-E 24).
- `StatusNotice(text, isError, tsUtc)`: An inline status/error notice.

## 1c. Home data (D-121)
- `homePriorityList`: fresh (non-stale, non-local-only, active/in_progress/paused) nodes ranked in code by `rankHomeNodes`: 1) overdue first; 2) in_progress; 3) pinned; 4) severity desc; 5) priority desc; 6) due soonest (most overdue first, undated last); 7) most recently updated; ties by id. Default limit 5.
- `recentlyClosed`: nodes with status done/cancelled and closed_at within the last 24h, newest first (default limit 10). Each entry's `undoValid` = `(now - latestClosingEvent.tsUtc < 24h) && !storage.hasUndo(event.opId)`; `undoToken` is the event's opId when valid, null otherwise.
- `activeThread`: derived from ProactiveState.openThreadMsgId/threadOpenedAt; null when no thread is open.

## 2. Actions (`UiAction`)
The only events the UI can trigger:
- `OpenHome`: Triggers initial quote check and state sync when navigating to Home.
- `UpdateDraft(text: String)`: Updates the composer safely.
- `SendMessage`: Appends draft and starts the AI turn.
- `Stop`: Instantly halts the AI stream and severs the socket.
- `Undo(token: String)`: Instantly reverts an operation (bypasses LLM).
- `ConfirmProposal(proposalId, choiceId)`: Resolves ambiguity instantly (bypasses LLM).
- `AcceptSoulFact(factId: String)`: Sets status to "active" (confirms a soul fact).
- `RejectSoulFact(factId: String)`: Rejects a proposed soul fact.
- `CancelReminder(id: String)`: Cancels active reminder and alarm.
- `LoadDay(dayKey: String)`: Paginates chat history for a specific day.
- `ShowDayReminders(dayKey, startOfDayMs, endOfDayMs)`: Loads reminders for a day.
- `ClearDaySelection`: Returns to recent chat mode.
- `SaveProvider(endpoint, key, model)`: Persists settings.
- `RunSelfTest`: Runs 10-second self-test alarm.
- `RefreshStats`: Refreshes debug statistics.
- `PromptResetDatabase`: Initiates keystore reset confirmation flow.
- `ConfirmResetDatabase(confirm: Boolean)`: Confirms or cancels database reset.
- `AddFollowUp(srcNodeId, dstNodeId)`: Links two nodes with a follow_up edge.
- `RemoveFollowUp(edgeId: String)`: Removes a follow_up edge link.
- `KeepStale(nodeId: String)`: Resets stale status and updates touch timestamp.
- `CancelStale(nodeId: String)`: Cancels a stale node and dismisses it from inbox.

## 3. Backend Capabilities (Exposed via Storage Interface)
The UI layer can query the `Storage` interface directly for data visualization without triggering state mutations:
- **Day Paging**: Query `getMessagesForDay(dayKey)` from `raw_message`.
- **Calendar Heat-Map**: Query `getHeatMapCounts(startDayKey, endDayKey)` and `getDaysWithMessages(startDayKey, endDayKey)` from `raw_message`.
- **Reminder Queries**: Query `getRemindersBetween(startMs, endMs)` and `getRemindersForDay(startOfDayMs, endOfDayMs)` from `reminder`.
- **Soul/Memory Reads**: Query active/stale nodes, node counts, and soul facts from `node` and `soul_fact`.
- **Quote Slots**: Query `getQuoteSlot(slot, dayKey)` from `quote_slot`.
- **Follow-up Graph**: Query `getEdgesForSource(nodeId)` / `getEdgesForDestination(nodeId)` with `type = 'follow_up'` from `edge`.
- **Home Queries (D-121)**: `getHomePriorityList(now, limit)` (fresh active nodes, code-ranked), `getRecentlyClosed(now, limit)` (closed within 24h), `getLatestEventForTarget(targetType, targetId)` (latest raw_event for undo validity). UPDATE is a UI-visible op type in `getRecentEvents` and renders as an "Updated:" receipt.
