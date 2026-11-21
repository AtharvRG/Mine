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
    /** More keys for the same provider, rotated with [providerKey] by KeyPool (at most 9 here + the primary). */
    val providerExtraKeys: List<String> = emptyList(),
    /** Requests per minute allowed per key ("provider.rpm_default"). NVIDIA NIM's free tier is 40. */
    val providerRpm: Int = 20,
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
    val trashNodes: List<com.anchor.core.models.Node> = emptyList(),
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
    val onboardingError: String? = null,
    // Blueprint 41 §2: user messages saved but not yet answered because the device had no network.
    // Derived on every refresh from the `outbox` rows of type `chat_turn` — blueprint 11:83 forbids
    // storing delivery state on `raw_message`, so this is a view of the queue, never a column.
    val queuedChatIds: List<String> = emptyList(),
    // Blueprint 25 §1 & §2: behavioral learning state visible to user
    val learnedPrefs: List<com.anchor.core.models.LearnedPref> = emptyList(),
    val whyNudgeText: String? = null,
    // Blueprint 24 §5: active persona
    val activePersonaId: String = "companion",
    val activePersonaName: String = "Companion",
    // Blueprint 16 §3.2 & Blueprint 52 §3.4: App lock and privacy UI gating
    val appLockEnabled: Boolean = false,
    val isAppLocked: Boolean = false,
    val relockTimeoutSec: Int = 60,
    val blockScreenshots: Boolean = false,
    val compactMode: Boolean = false,
    // Blueprint 61 §3.8: Ambient report & proposals
    val latestAmbientReport: com.anchor.core.ambient.AmbientReport? = null,
    val ambientReportItems: List<com.anchor.core.ambient.AmbientReportItem> = emptyList(),
    val ambientEnabled: Boolean = false,
    val voiceprintEnrolled: Boolean = false,
    // Blueprint 32 §3.5-§3.6 / P-007, P-008: Insights & Analytics report
    val latestInsightsReport: com.anchor.core.insights.FullInsightsReport? = null,
    // Blueprint 60 §9 / P-018: Hands-Free Conversation Mode
    val handsFreeState: com.anchor.core.voice.HandsFreeState = com.anchor.core.voice.HandsFreeState.IDLE,
    // Companion Brain / D-213: Smart Web Search toggle
    val webSearchEnabled: Boolean = false,
    val lastWebSearchQuery: String? = null,
    // Companion Brain / D-205 & D-211: Procrastination Whistle mode ("off", "gentle", "firm")
    val procrastinationMode: String = "gentle",
    // Companion Brain / D-206: Context Sensing (Walk & Errand triggers)
    val contextSensingEnabled: Boolean = false,
    val postCallNudgesEnabled: Boolean = true,
    val onDeviceFallbackEnabled: Boolean = false,
    val moodTrackingEnabled: Boolean = false,
    val hideNotificationContent: Boolean = false,
    val soulAutoAcceptMode: Boolean = false,
    val fallbackEndpoint: String = "",
    val fallbackKey: String = "",
    val fallbackModel: String = "",
    val providerCooldownExpiry: Long? = null,
    val autoBackupEnabled: Boolean = false,
    val autoBackupUri: String = "",
    val pendingProviderImport: com.anchor.core.provider.ImportedProviderConfig? = null,
    // ---- Companion UI surface (new UI integration) ----
    /** Every open item (active / in progress / paused), newest-ranked first. Home, Memory and Board read this. */
    val activeNodes: List<com.anchor.core.models.Node> = emptyList(),
    val topicNotes: List<com.anchor.core.models.TopicNote> = emptyList(),
    val noteReferences: List<com.anchor.core.models.NoteReference> = emptyList(),
    /** Agent self-wakeups still armed (break timers, deferred check-ins, conditioned follow-ups). */
    val pendingWakeups: List<com.anchor.core.models.AgentWakeup> = emptyList(),
    val profile: UserProfile = UserProfile(),
    val notificationReading: com.anchor.core.notifications.NotificationReadSettings = com.anchor.core.notifications.NotificationReadSettings(),
    /** The user message whose turn failed without an answer; `RetryLastMessage` re-sends it. */
    val lastFailedMsgId: String? = null,
    val connectionErrorRetryable: Boolean = false,
    val connectionErrorNeedsSettings: Boolean = false,
    /** Result of the latest direct (non-chat) action, so the UI can confirm it and offer Undo. */
    val lastActionResult: ActionResult? = null,
    /** Messages per day key (yyyy-MM-dd) over the last 60 days, for the date dial and heat map. */
    val chatDayCounts: Map<String, Int> = emptyMap(),
    /** True once the first full read from storage has landed (the UI stops showing skeletons). */
    val initialLoadComplete: Boolean = false
) {
    /** Blueprint 41 §2: the banner the user sees while messages wait. Null when nothing is queued. */
    val offlineQueuedText: String?
        get() = if (queuedChatIds.isEmpty()) null else "Offline: your messages are saved."

    override fun toString(): String {
        val redactedKey = if (providerKey.isBlank()) "" else "***"
        return "UiState(chatMessages=$chatMessages, receipts=$receipts, openProposals=$openProposals, proposedSoulFacts=$proposedSoulFacts, staleItems=$staleItems, inboxCount=$inboxCount, urgentItems=$urgentItems, isChatLoading=$isChatLoading, activeReminders=$activeReminders, selfTestStatus=$selfTestStatus, providerEndpoint=$providerEndpoint, providerKey=$redactedKey, providerModel=$providerModel, debugStats=$debugStats, composerDraft=$composerDraft, connectionError=$connectionError, globalError=$globalError, schedulingError=$schedulingError, schedulingFailedCount=$schedulingFailedCount, missedReminders=$missedReminders, showResetConfirm=$showResetConfirm, alarmDisclosureText=$alarmDisclosureText, selectedDayKey=$selectedDayKey, selectedDayMessages=$selectedDayMessages, selectedDayReminders=$selectedDayReminders, homeQuote=$homeQuote, atRisk=$atRisk, activeThread=$activeThread, homePriorityList=$homePriorityList, recentlyClosed=$recentlyClosed, chatItems=$chatItems, memoryNodes=$memoryNodes, memorySoulFacts=$memorySoulFacts, memoryLoaded=$memoryLoaded, nudgeGate=$nudgeGate, onboardingDone=$onboardingDone, onboardingStep=$onboardingStep, onboardingAnswers=$onboardingAnswers, onboardingHealthLocalOnly=$onboardingHealthLocalOnly, onboardingError=$onboardingError, queuedChatIds=$queuedChatIds)"
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
    /**
     * [extraKeys] null leaves the stored extra keys alone; an empty list clears them.
     * [rpm] null leaves the per-key request limit alone.
     */
    data class SaveProvider(
        val endpoint: String,
        val key: String,
        val model: String,
        val extraKeys: List<String>? = null,
        val rpm: Int? = null,
    ) : UiAction {
        override fun toString(): String = "SaveProvider(endpoint=$endpoint, key=***, model=$model, extraKeys=${extraKeys?.size}, rpm=$rpm)"
    }
    data class ImportProviderConfig(val payload: String) : UiAction
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
    /** Blueprint 51 §3.1 / §3.3: Memory viewer action - archive an item (soft delete). */
    data class ArchiveNode(val nodeId: String) : UiAction
    /** Blueprint 51 §3.1 / §3.3: Memory viewer action - delete an item (cancel/remove). */
    data class DeleteNode(val nodeId: String) : UiAction
    /** Blueprint 51 §3.1 / §3.9: Memory viewer action - restore an item from trash. */
    data class RestoreNode(val nodeId: String) : UiAction
    /** Blueprint 51 §3.1 / §3.9: Memory viewer action - permanently erase/scrub an item. */
    data class EraseNode(val nodeId: String) : UiAction
    /** Blueprint 51 §3.1 / §3.9: Memory viewer action - permanently erase/scrub a soul fact. */
    data class EraseSoulFact(val factId: String) : UiAction
    /** Blueprint 51 §50: Board / Kanban status update. */
    data class UpdateNodeStatus(val nodeId: String, val newStatus: String) : UiAction
    /** Reload all memory, receipts, and state from storage after an in-place DB rebind/import. */
    data object ReloadStorage : UiAction
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

    /**
     * Blueprint 41 §3.5: try to answer the queued offline messages now. Sent when the app opens;
     * the retry timer and a live network callback are not wired in this slice (D-138).
     */
    data object DrainQueuedTurns : UiAction

    // Blueprint 25 §2: Behavioral learning actions
    data class WhyNudge(val msgId: String? = null) : UiAction
    data object DismissWhyNudge : UiAction
    data class LockLearnedPref(val key: String, val scope: String, val locked: Boolean) : UiAction
    data class ResetLearning(val preserveLocked: Boolean = true) : UiAction

    // Blueprint 24 §5: Persona selection
    data class SetPersona(val personaId: String) : UiAction

    // Blueprint 16 §3.2 & Blueprint 52 §3.4: App lock & privacy actions
    data object UnlockApp : UiAction
    data object LockApp : UiAction
    data class SetAppLock(val enabled: Boolean) : UiAction
    data class SetRelockTimeout(val seconds: Int) : UiAction
    data class SetBlockScreenshots(val enabled: Boolean) : UiAction
    data class SetCompactMode(val enabled: Boolean) : UiAction

    // Blueprint 61: Ambient Listening actions
    data class AcceptAmbientItem(val itemId: String) : UiAction
    data class RejectAmbientItem(val itemId: String) : UiAction
    data class AcceptAllAmbientItems(val reportId: String) : UiAction
    data object DeleteAllAmbientData : UiAction
    data object BookmarkAmbientLast2Min : UiAction

    // Blueprint 32 §3.5-§3.6 / P-007, P-008: Insights & Analytics actions
    data class LoadInsights(val days: Int = 7) : UiAction

    // Blueprint 60 §9 / P-018: Hands-Free Conversation actions
    data class SetHandsFree(val enabled: Boolean) : UiAction
    data class UpdateHandsFreeState(val state: com.anchor.core.voice.HandsFreeState) : UiAction

    // Companion Brain / D-213: Smart Web Search actions
    data object ToggleWebSearch : UiAction
    data class SetWebSearch(val enabled: Boolean) : UiAction

    // Companion Brain / D-205 & D-211: Procrastination Whistle actions
    data class SetProcrastinationMode(val mode: String) : UiAction

    // Companion Brain / D-206: Context Sensing actions
    data class SetContextSensing(val enabled: Boolean) : UiAction
    data object SetCurrentWifiAsHome : UiAction

    // Companion Brain / D-207: Living Notes actions with audit and undo
    data class CreateTopicNote(val topic: String, val bullets: List<String>, val pinned: Boolean = false) : UiAction
    data class AddTopicNoteBullet(val id: String, val bullet: String) : UiAction
    data class TogglePinTopicNote(val id: String) : UiAction
    data class DeleteTopicNote(val id: String) : UiAction

    // Audit remediation missing controls
    data class SetPostCallNudges(val enabled: Boolean) : UiAction
    data class SetOnDeviceFallback(val enabled: Boolean) : UiAction
    data class SetMoodTracking(val enabled: Boolean) : UiAction
    data class SetHideNotificationContent(val enabled: Boolean) : UiAction
    data class SetSoulAutoAcceptMode(val enabled: Boolean) : UiAction
    data class SaveFallbackProvider(val endpoint: String, val key: String, val model: String = "") : UiAction
    data class SetFallbackModel(val model: String) : UiAction
    data object RetryNow : UiAction
    data class SetAutoBackup(val enabled: Boolean) : UiAction
    data class SetAutoBackupUri(val uri: String) : UiAction
    data object ConfirmProviderImport : UiAction
    data object RejectProviderImport : UiAction

    // ---- Direct actions used by the companion UI. Deterministic: no model call. ----
    /** Sends [text] as a chat turn without going through the composer draft. */
    data class SendText(val text: String, val replyToId: String? = null) : UiAction
    /** Something wrote to the chat outside a turn (see ChatEvents): re-read it. */
    data object ExternalMessagesChanged : UiAction
    /** Re-sends the last user message whose turn failed (see `UiState.lastFailedMsgId`). */
    data object RetryLastMessage : UiAction
    data object DismissConnectionError : UiAction
    /** Sends a one-word test request to [endpoint] and reports latency or a plain-language error as an [ActionResult]. */
    data class TestProvider(val endpoint: String, val key: String, val model: String, val tag: String? = "provider-test") : UiAction

    /** Marker for actions handled by `CompanionActions`. Each may carry a [tag] echoed in the result. */
    sealed interface Direct : UiAction { val tag: String? }

    /** "gym at 7" → a task (with a reminder when a time is given), parsed on device. */
    data class QuickAdd(val text: String, override val tag: String? = null) : Direct
    data class CompleteNode(val nodeId: String, override val tag: String? = null) : Direct
    data class RescheduleNode(val nodeId: String, val whenText: String, override val tag: String? = null) : Direct
    data class SetNodeLocalOnly(val nodeId: String, val localOnly: Boolean, override val tag: String? = null) : Direct
    data class SetSoulFactLocalOnly(val factId: String, val localOnly: Boolean, override val tag: String? = null) : Direct
    data class SetNoteReferenceMode(val noteId: String, val mode: String, override val tag: String? = null) : Direct
    /** A reminder from the UI sheet. [leaveHome] arms it on leaving home with [fallbackWhenText] as the backstop. */
    data class CreateReminder(
        val title: String,
        val whenText: String,
        val gentle: Boolean,
        val leaveHome: Boolean = false,
        val fallbackWhenText: String? = null,
        val recurrence: String? = null,
        override val tag: String? = null
    ) : Direct
    data class SnoozeReminder(val reminderId: String, val minutes: Int, override val tag: String? = null) : Direct
    /** Cancels a reminder and returns an undo token (unlike [CancelReminder], which predates undo). */
    data class CancelReminderUndoable(val reminderId: String, override val tag: String? = null) : Direct
    data class StartBreak(val minutes: Int, override val tag: String? = null) : Direct
    data class EndBreak(override val tag: String? = null) : Direct
    /** Pauses proactive nudges until [untilMs]; null resumes them. */
    data class PauseNudges(val untilMs: Long?, override val tag: String? = null) : Direct
    data class SetUserName(val name: String, override val tag: String? = null) : Direct
    data class SetLanguages(val languages: List<String>, override val tag: String? = null) : Direct
    data class SetDayWindow(val startMinute: Int, val endMinute: Int, override val tag: String? = null) : Direct
    /** Stores a UI preference. Only keys under `ui.`, `voice.`, `chat.`, `consent.` and `ambient.` are accepted. */
    data class SetPref(val key: String, val value: String, override val tag: String? = null) : Direct
    data class SaveNotificationReading(val settings: com.anchor.core.notifications.NotificationReadSettings, override val tag: String? = null) : Direct
    /** Turns an item from the "heard while listening" feed into a review suggestion. */
    data class SaveNotificationSuggestion(val suggestion: com.anchor.core.notifications.NotificationSuggestion, override val tag: String? = null) : Direct
}

/** Who the user is and how they like Mine to behave. Read from settings on every refresh. */
data class UserProfile(
    val name: String = "",
    val languages: List<String> = listOf("English"),
    val dayStartMinute: Int = 7 * 60,
    val dayEndMinute: Int = 22 * 60,
    val breakUntilMs: Long? = null,
    val nudgesPausedUntilMs: Long? = null,
    /** `ui.*`, `voice.*`, `chat.*`, `consent.*` preferences. */
    val prefs: Map<String, String> = emptyMap()
) {
    fun pref(key: String, default: Boolean): Boolean = prefs[key]?.let { it == "1" || it == "true" } ?: default
}

/** Outcome of one [UiAction.Direct]. [seq] increases on every result so equal messages still re-trigger. */
data class ActionResult(
    val seq: Long,
    val tag: String?,
    val message: String,
    val undoToken: String? = null,
    val isError: Boolean = false,
    /** The action needs a choice from the user (time chips, duplicate, which item); see open proposals. */
    val needsChoice: Boolean = false,
    val targetId: String? = null
)
