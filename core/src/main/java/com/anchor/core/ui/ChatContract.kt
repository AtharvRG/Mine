// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.ui

import com.anchor.core.models.ChatMessage
import com.anchor.core.models.Reminder

data class ReceiptState(
    val id: String,
    val message: String,
    val undoToken: String?,
    val isError: Boolean = false,
    val causeMsgId: String? = null,
    val timestamp: Long = 0L
)

data class UiState(
    val chatMessages: List<ChatMessage> = emptyList(),
    val receipts: List<ReceiptState> = emptyList(),
    val openProposals: List<com.anchor.core.models.Proposal> = emptyList(),
    val proposedSoulFacts: List<com.anchor.core.models.SoulFact> = emptyList(),
    val staleItems: List<com.anchor.core.models.Node> = emptyList(),
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
    // H-11 (R-15): POST_NOTIFICATIONS denied (or notifications switched off for the app). Set by
    // the app layer, which is the only place that can query the permission; the UI shows a banner.
    val notificationsBlocked: Boolean = false,
    val schedulingError: String? = null,
    val schedulingFailedCount: Int = 0,
    // H-01 (R-01): reminders whose fire instant passed while the device was off. Not a failure —
    // the user is told what they missed instead of the reminder vanishing from reconcile.
    val missedReminders: List<com.anchor.core.alarms.ScheduleResult.Overdue> = emptyList(),
    val showResetConfirm: Boolean = false,
    val alarmDisclosureText: String = "Urgent/alarm reminders use Android Alarm Clock (setAlarmClock) to guarantee delivery in Doze mode. This causes an alarm icon to appear in the system status bar while pending. Gentle reminders use notification-only scheduling (RTC_WAKEUP) without showing the status bar alarm icon, but may be delayed by Doze mode.",
    val selectedDayKey: String? = null,
    val selectedDayMessages: List<ChatMessage> = emptyList(),
    val selectedDayReminders: List<Reminder> = emptyList(),
    val homeQuote: com.anchor.core.models.Quote? = null,
    // P1-D 20 (D-121)
    val atRisk: Boolean = false,
    val activeThread: ActiveThread? = null,
    val homePriorityList: List<com.anchor.core.models.Node> = emptyList(),
    val recentlyClosed: List<RecentlyClosed> = emptyList(),
    val chatItems: List<ChatItem> = emptyList()
) {
    override fun toString(): String {
        val redactedKey = if (providerKey.isBlank()) "" else "***"
        return "UiState(chatMessages=$chatMessages, receipts=$receipts, openProposals=$openProposals, proposedSoulFacts=$proposedSoulFacts, staleItems=$staleItems, inboxCount=$inboxCount, urgentItems=$urgentItems, isChatLoading=$isChatLoading, activeReminders=$activeReminders, selfTestStatus=$selfTestStatus, providerEndpoint=$providerEndpoint, providerKey=$redactedKey, providerModel=$providerModel, debugStats=$debugStats, composerDraft=$composerDraft, connectionError=$connectionError, globalError=$globalError, schedulingError=$schedulingError, schedulingFailedCount=$schedulingFailedCount, missedReminders=$missedReminders, showResetConfirm=$showResetConfirm, alarmDisclosureText=$alarmDisclosureText, selectedDayKey=$selectedDayKey, selectedDayMessages=$selectedDayMessages, selectedDayReminders=$selectedDayReminders, homeQuote=$homeQuote, atRisk=$atRisk, activeThread=$activeThread, homePriorityList=$homePriorityList, recentlyClosed=$recentlyClosed, chatItems=$chatItems)"
    }
}

sealed interface UiAction {
    data object OpenHome : UiAction
    data class UpdateDraft(val text: String) : UiAction
    data object SendMessage : UiAction
    data object Stop : UiAction
    data class Undo(val token: String) : UiAction
    data class ConfirmProposal(val proposalId: String, val choiceId: String) : UiAction
    data class AcceptSoulFact(val factId: String) : UiAction
    data class RejectSoulFact(val factId: String) : UiAction
    data class CancelReminder(val id: String) : UiAction
    data class LoadDay(val dayKey: String) : UiAction
    data class ShowDayReminders(val dayKey: String, val startOfDayMs: Long, val endOfDayMs: Long) : UiAction
    data object ClearDaySelection : UiAction
    data class SaveProvider(val endpoint: String, val key: String, val model: String) : UiAction
    data object RunSelfTest : UiAction
    data object RefreshStats : UiAction
    data object PromptResetDatabase : UiAction
    data class ConfirmResetDatabase(val confirm: Boolean) : UiAction
    data class AddFollowUp(val srcNodeId: String, val dstNodeId: String) : UiAction
    data class RemoveFollowUp(val edgeId: String) : UiAction
    data class KeepStale(val nodeId: String) : UiAction
    data class CancelStale(val nodeId: String) : UiAction
}
