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
    val chatItems: List<ChatItem> = emptyList(),
    // Row 20 (memory viewer, read-only slice): read straight from the node and soul_fact tables —
    // blueprint 51 §3.1 rule 1, no model call. `memoryLoaded` is what makes the one-shot load
    // one-shot: UiState is a fresh object on every update, so a LaunchedEffect keyed on the tab
    // alone would re-fire on every state change.
    val memoryNodes: List<com.anchor.core.models.Node> = emptyList(),
    val memorySoulFacts: List<com.anchor.core.models.SoulFact> = emptyList(),
    val memoryLoaded: Boolean = false,
    // Blueprint 31: the proactive tick's verdict for the last candidate it looked at, so the Debug
    // tab can say why nothing was sent ("min_gap", "open_thread", …) instead of staying silent.
    // Null until the first tick. Not a user-facing surface; it exists so a held nudge is observable.
    val nudgeGate: String? = null,
    // Blueprint 52 §3.1: the guided first-run flow. `onboardingDone` is read from storage when the
    // controller starts (the `onboarding_state` row's `done` flag), so a fresh install shows the
    // flow and a finished one never does. `onboardingStep` is where the user is: 0 Welcome,
    // 1 Consent, 2 Your day, 3 Soul seed. `onboardingError` is set when a step's write failed.
    val onboardingDone: Boolean = false,
    val onboardingStep: Int = 0,
    // Blueprint 52 §3.2: the light questions that seed Soul, keyed by category. Free text and chip
    // answers alike; the health answer carries the "keep this on this phone only" switch beside it.
    val onboardingAnswers: Map<String, String> = emptyMap(),
    val onboardingHealthLocalOnly: Boolean = false,
    val onboardingError: String? = null
) {
    override fun toString(): String {
        val redactedKey = if (providerKey.isBlank()) "" else "***"
        return "UiState(chatMessages=$chatMessages, receipts=$receipts, openProposals=$openProposals, proposedSoulFacts=$proposedSoulFacts, staleItems=$staleItems, inboxCount=$inboxCount, urgentItems=$urgentItems, isChatLoading=$isChatLoading, activeReminders=$activeReminders, selfTestStatus=$selfTestStatus, providerEndpoint=$providerEndpoint, providerKey=$redactedKey, providerModel=$providerModel, debugStats=$debugStats, composerDraft=$composerDraft, connectionError=$connectionError, globalError=$globalError, schedulingError=$schedulingError, schedulingFailedCount=$schedulingFailedCount, missedReminders=$missedReminders, showResetConfirm=$showResetConfirm, alarmDisclosureText=$alarmDisclosureText, selectedDayKey=$selectedDayKey, selectedDayMessages=$selectedDayMessages, selectedDayReminders=$selectedDayReminders, homeQuote=$homeQuote, atRisk=$atRisk, activeThread=$activeThread, homePriorityList=$homePriorityList, recentlyClosed=$recentlyClosed, chatItems=$chatItems, memoryNodes=$memoryNodes, memorySoulFacts=$memorySoulFacts, memoryLoaded=$memoryLoaded, nudgeGate=$nudgeGate, onboardingDone=$onboardingDone, onboardingStep=$onboardingStep, onboardingAnswers=$onboardingAnswers, onboardingHealthLocalOnly=$onboardingHealthLocalOnly, onboardingError=$onboardingError)"
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
    /** Row 20: (re)read the Items and Soul lists from storage for the Memory tab. */
    data object LoadMemory : UiAction
    /** Blueprint 31: run one proactive pass — pick a candidate, score it, gate it, deliver or hold. */
    data object TickProactive : UiAction

    /** Blueprint 52 §3.1: move the first-run flow forward one step, persisting progress. */
    data object OnboardingNext : UiAction
    /** Blueprint 52 §3.1: move the first-run flow back one step. */
    data object OnboardingBack : UiAction
    /** Blueprint 52 §3.2: one Soul seed answer, keyed by the fact category it will be stored under. */
    data class OnboardingAnswer(val category: String, val text: String) : UiAction
    /** Blueprint 52 §3.2 / 16 §6: the "keep this on this phone only" switch beside the health answer. */
    data class OnboardingHealthLocalOnly(val localOnly: Boolean) : UiAction
    /** Blueprint 52 §3.1 step 4: confirm the day window; writes the first `day_rule`. */
    data class OnboardingConfirmDay(val startMinute: Int, val endMinute: Int) : UiAction
    /** Blueprint 52 §3.1: the last step — writes the seeded Soul facts, marks onboarding done. */
    data object OnboardingFinish : UiAction
}
