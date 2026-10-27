// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.ui

import com.anchor.core.models.ChatMessage
import com.anchor.core.models.Reminder

data class UiState(
    val chatMessages: List<ChatMessage> = emptyList(),
    val isChatLoading: Boolean = false,
    val activeReminders: List<Reminder> = emptyList(),
    val selfTestStatus: String = "Idle",
    val providerEndpoint: String = "",
    val providerKey: String = "",
    val providerModel: String = "",
    val debugStats: String = "Rows: 0",
    val globalError: String? = null
)

sealed interface UiAction {
    data class SendMessage(val text: String) : UiAction
    data object Stop : UiAction
    data class CancelReminder(val id: String) : UiAction
    data object RunSelfTest : UiAction
    data object RefreshStats : UiAction
    data class SaveProvider(val endpoint: String, val key: String, val model: String) : UiAction

    data class ScheduleIn(val minutes: Int, val text: String) : UiAction
}
