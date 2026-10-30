# UI Contract

// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

Every screen in Mine is mapped to `UiState` and `UiAction`. The UI is completely passive. All logic, timing, and formatting are handled by the core module.

## 1. State (`UiState`)
The single source of truth for the UI at any given moment:
- `chatMessages`: List<ChatMessage> (The active session's chat bubbles)
- `isChatLoading`: Boolean (True when waiting for network or streaming)
- `activeReminders`: List<Reminder> (Reminders scheduled for the future)
- `selfTestStatus`: String (Status of the 10-second reliability probe)
- `providerEndpoint`: String (e.g., https://api.openai.com/v1/chat/completions)
- `providerKey`: String (The raw API key; UI masks this visually)
- `providerModel`: String (e.g., gpt-4o-mini)
- `debugStats`: String (Database row counts for debugging)
- `globalError`: String? (Non-null if storage/Keystore fails catastrophically at startup)

## 2. Actions (`UiAction`)
The only events the UI can trigger:
- `SendMessage(text: String)`: Appends user message and starts the AI turn.
- `Stop`: Instantly halts the AI stream and severs the network socket.
- `CancelReminder(id: String)`: Removes an active reminder and cancels its Android alarm.
- `RunSelfTest`: Schedules a 10-second test alarm to verify OEM background reliability.
- `RefreshStats`: Pulls the latest row counts from SQLite.
- `SaveProvider(endpoint: String, key: String, model: String)`: Persists AI settings.
- `ScheduleIn(minutes: Int, text: String)`: Debug action for quick reminder scheduling.

## 3. Backend Capabilities (Exposed via Storage Interface)
The UI layer can query the `Storage` interface directly for data visualization without triggering state mutations:
- **Day Paging**: Query `raw_message` grouped by `day_key` (monotonic strings like "2026-10-30") for endless scrolling history.
- **Calendar Heat-Map**: Query `daily_stat` and `day_block` to render activity counts per day natively.
- **Reminder Dates**: Extract `fire_at` from the `reminder` table to plot upcoming alerts.
- **Soul/Memory Reads**: Read `node` and `soul_fact` tables for the Memory Viewer and Trash screens. (Backend handles all mutations via `Gateway`).