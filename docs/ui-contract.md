<!-- SPDX-License-Identifier: GPL-3.0-or-later -->
<!-- Copyright (C) 2026 Mine contributors -->
<!-- Part of Mine. See LICENSE in the repository root. -->

# UI Contract
Every screen in Mine is mapped to `UiState` and `UiAction`. The UI is completely passive. All logic, timing, and formatting are handled by the core module.

## 1. State (`UiState`)
The single source of truth for the UI at any given moment:
- `chatMessages`: List<ChatMessage> (The active session's chat bubbles)
- `isChatLoading`: Boolean (True when waiting for network or streaming)
- `activeReminders`: List<Reminder> (Reminders scheduled for the future)
- `composerDraft`: String (Safely stores the user's input across config changes)
- `connectionError`: String? (Transient API/Network errors)
- `globalError`: String? (Catastrophic Storage/Keystore errors)
- `selfTestStatus`: String, `debugStats`: String (Diagnostics)
- `providerEndpoint`, `providerKey`, `providerModel`: String (AI config)

## 2. Actions (`UiAction`)
The only events the UI can trigger:
- `UpdateDraft(text: String)`: Updates the composer safely.
- `SendMessage`: Appends draft and starts the AI turn.
- `Stop`: Instantly halts the AI stream and severs the socket.
- `Undo(token: String)`: Instantly reverts an operation (bypasses LLM).
- `ConfirmProposal(proposalId, choiceId)`: Resolves ambiguity instantly (bypasses LLM).
- `CancelReminder(id: String)`: Cancels active reminder and alarm.
- `LoadDay(dayKey: String)`: Paginates chat history.
- `SaveProvider(endpoint, key, model)`: Persists settings.
- `RunSelfTest`, `RefreshStats`, `ScheduleIn`: Debug actions.

## 3. Backend Capabilities (Exposed via Storage Interface)
The UI layer can query the `Storage` interface directly for data visualization without triggering state mutations:
- **Day Paging**: Query `raw_message` grouped by `day_key`.
- **Calendar Heat-Map**: Query `daily_stat` and `day_block`.
- **Reminder Dates**: Extract `fire_at` from the `reminder` table.
- **Soul/Memory Reads**: Read `node` and `soul_fact` tables.
