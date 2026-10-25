# UI Contract

// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

Every screen in Mine is mapped to `UiState` and `UiAction`. The UI is completely passive.

## 1. Chat Screen
**State (`ChatState`)**:
- `messages`: List<ChatMessage>
- `isLoading`: Boolean
  **Actions (`ChatAction`)**:
- `SendMessage(text: String)`
- `Stop` (Halts generation instantly)

## 2. Reminders Screen
**State (`RemindersState`)**:
- `activeReminders`: List<Reminder>
- `testStatus`: String
  **Actions (`RemindersAction`)**:
- `RunSelfTest`
- `CancelReminder(id: String)`

## 3. Provider Settings
**State (`ProviderState`)**:
- `endpoint`, `apiKey`, `model`: String
  **Actions (`ProviderAction`)**:
- `SaveConfig(endpoint, key, model)`