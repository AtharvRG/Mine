// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.ui

import com.anchor.core.models.ChatMessage
import com.anchor.core.models.Reminder

data class ReceiptState(
    val id: String,
    val message: String,
    val undoToken: String?
)

data class UiState(
    val chatMessages: List<ChatMessage> = emptyList(),
    val receipts: List<ReceiptState> = emptyList(),
    val openProposals: List<com.anchor.core.models.Proposal> = emptyList(),
    val proposedSoulFacts: List<com.anchor.core.models.SoulFact> = emptyList(),
    val inboxCount: Int = 0,
    val urgentItems: List<com.anchor.core.models.Node> = emptyList(),
    val isChatLoading: Boolean = false,
    val activeReminders: List<Reminder> = emptyList(),
    val selfTestStatus: String = "Idle",
    val providerEndpoint: String = "",
    val providerKey: String = "",
    val providerModel: String = "",
    val debugStats: String = "Rows: 0",
    val composerDraft: String = "",
    val connectionError: String? = null,
    val globalError: String? = null,
    val showResetConfirm: Boolean = false
)

sealed interface UiAction {
    data class UpdateDraft(val text: String) : UiAction
    data object SendMessage : UiAction
    data object Stop : UiAction
    data class Undo(val token: String) : UiAction
    data class ConfirmProposal(val proposalId: String, val choiceId: String) : UiAction
    data class AcceptSoulFact(val factId: String) : UiAction
    data class RejectSoulFact(val factId: String) : UiAction
    data class CancelReminder(val id: String) : UiAction
    data class LoadDay(val dayKey: String) : UiAction
    data class SaveProvider(val endpoint: String, val key: String, val model: String) : UiAction
    data object RunSelfTest : UiAction
    data object RefreshStats : UiAction
    data object PromptResetDatabase : UiAction
    data class ConfirmResetDatabase(val confirm: Boolean) : UiAction
    data object ExportData : UiAction
    data object ImportData : UiAction
}
