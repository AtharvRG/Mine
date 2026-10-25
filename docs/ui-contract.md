# UI Contract

// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine Author
// Part of Mine. See LICENSE in the repository root.

The UI is entirely driven by `UiState` and emits `UiAction`. It never touches IO.

## 1. Chat Screen
**State:**
- `messages`: List of chat messages (role, content)
- `isProviderReady`: Boolean
- `isLoading`: Boolean

**Actions:**
- `SendMessage(text)`
- `ClearChat`

## 2. Reminders Screen
**State:**
- `activeReminders`: List of scheduled items (id, text, time)
- `testStatus`: String (e.g., "Waiting...", "Fired!")

**Actions:**
- `ScheduleIn(minutes, text)`
- `CancelReminder(id)`
- `RunSelfTest`

## 3. Provider Settings
**State:**
- `endpoint`: String
- `apiKey`: String
- `model`: String
- `statusMessage`: String

**Actions:**
- `SaveConfig(endpoint, key, model)`

## 4. Debug Screen
**State:**
- `dbRowCount`: Int
- `lastError`: String

**Actions:**
- `RefreshStats`