@file:Suppress("EnumEntryName")

package com.mine.companion.store

import androidx.compose.runtime.Immutable

/*
 * Kotlin mirror of src/store/mineStore.tsx types. Enum entry names deliberately
 * keep the exact lowercase string-union values of the prototype so every piece of
 * copy derived from them ("Mine will sound like a cofounder now", "soul proposal")
 * reads identically.
 */

enum class ScreenId {
    S01, S02, S03, S04, S05, S06, S07, S08, S09, S10,
    S11, S12, S13, S14, S15, S16, S17, S18, S19, S20,
    S21, S22, S23, S24, S25, S26, S27, S28, S29, S30,
    S31, S32, S33, S34, S35, S36, S37, S38, S39, S40,
    S41,
    /** Teach Mine your voice (enrolment). */
    S42,
    /** What notifications Mine may read: every app, with its rule. */
    S43,
    /** On-device models: embedders, voices, speech. */
    S44,
    /** Onboarding: a bit about you (personal details). */
    S45,
}

enum class ModalId { M01, M02, M03, M04, M06, M07, M08, M09, M10, M16 }

enum class PersonaType { companion, cofounder, coach, chill, minimal, ultron }

enum class StatusChipState { normal, offline, rate_limited, reminders_at_risk, backup_model, no_ai }

enum class ContextState(val label: String) {
    AtHomeStill("at Home, still"),
    AtHomeWalking("at Home, walking"),
    AwayWalking("Away, walking"),
    AwayInVehicle("Away, in vehicle"),
}

enum class ProcrastinationMode { off, gentle, firm }

enum class ItemType { task, errand, habit }

enum class ItemStatus { todo, in_progress, done, paused, archived, trashed }

enum class TriggerType { time, leave_home }

enum class NoteAccessMode { read_only, read_write }

enum class RelationType(val label: String) {
    PartOf("Part of"), FollowsUp("Follows up"), Supports("Supports"), ConflictsWith("Conflicts with"),
}

@Immutable
data class Relation(val type: RelationType, val targetTitle: String)

@Immutable
data class MineItem(
    val id: String,
    val title: String,
    val type: ItemType,
    val status: ItemStatus,
    val dueLabel: String,
    /** e.g. ['Fri 20 Oct, 18:00', 'Sun 22 Oct, 21:00'] */
    val strikethroughHistory: List<String>,
    val slipCount: Int,
    val linkedNoteId: String? = null,
    val linkedNoteMode: NoteAccessMode? = null,
    val triggerType: TriggerType? = null,
    val fallbackTime: String? = null,
    val checklist: List<String>? = null,
    val streakDays: Int? = null,
    val bestStreakDays: Int? = null,
    val localOnly: Boolean? = null,
    val provenance: String,
    val relations: List<Relation>,
)

@Immutable
data class LivingNote(
    val id: String,
    val title: String,
    val updatedAt: String,
    val pinned: Boolean,
    val linkedTaskId: String? = null,
    val accessMode: NoteAccessMode,
    val bullets: List<String>,
)

enum class SoulCategory(val label: String) {
    Identity("Identity"), Routine("Routine"), Health("Health"),
    ReplyStyle("Reply style"), Dislikes("Dislikes"), Goals("Goals"),
    Preferences("Preferences"), People("People"), Other("Other"),
}

enum class SoulSource { chat, manual, inferred }

@Immutable
data class SoulFact(
    val id: String,
    val text: String,
    val category: SoulCategory,
    val confidence: Int, // 1 | 2 | 3
    val source: SoulSource,
    val pinned: Boolean,
    val localOnly: Boolean,
    val sensitive: Boolean? = null,
)

enum class ReceiptActionType { reschedule, note_update, reminder_add, wakeup_armed, ambiguity_resolved, created, completed, removed, memory, error }

@Immutable
data class DeterministicReceipt(
    val id: String,
    val text: String,
    val timestamp: String,
    val actionType: ReceiptActionType,
    val linkedNoteId: String? = null,
    val linkedItemId: String? = null,
    val undone: Boolean? = null,
    /** Engine undo token. Null = this change cannot be undone, so no Undo button is shown. */
    val undoToken: String? = null,
    /** The engine reported a failure ("Not found: dinner") rather than a change. */
    val isError: Boolean = false,
)

@Immutable
data class AmbiguityOption(val id: String, val label: String, val resolutionReceiptText: String)

@Immutable
data class AmbiguityPrompt(
    val question: String,
    val resolvedOptionId: String? = null,
    val options: List<AmbiguityOption>,
    /** Engine proposal id; choosing an option confirms this proposal. */
    val proposalId: String? = null,
)

@Immutable
data class SearchSource(val title: String, val domain: String, val snippet: String)

enum class Sender { user, assistant }

@Immutable
data class ChatMessage(
    val id: String,
    val sender: Sender,
    val text: String,
    val timestamp: String,
    val partOfDay: String? = null,
    val thoughtSeconds: Int? = null,
    val thoughtProcess: String? = null,
    val searchQuery: String? = null,
    val searchSources: List<SearchSource>? = null,
    val receipts: List<DeterministicReceipt>? = null,
    val ambiguityPrompt: AmbiguityPrompt? = null,
    val isProcrastinationGate: Boolean? = null,
    val parkedTopicNoteTitle: String? = null,
    /** Epoch millis, for grouping and relative times. */
    val tsUtc: Long = 0L,
    /** Waiting in the offline queue; shown with a "will send" marker. */
    val queued: Boolean = false,
    /** A quiet system line (status, what's-new), not a bubble. */
    val isSystem: Boolean = false,
    /** The message this one replies to, shown as a quote inside the bubble. */
    val replyTo: ReplyQuote? = null,
    /** Mine started this conversation itself (check-in, nudge, wake-up, after a call). */
    val proactive: Boolean = false,
    /** A fired reminder, shown as a compact line. */
    val isReminder: Boolean = false,
)

@Immutable
data class ReplyQuote(val id: String, val preview: String, val fromAssistant: Boolean, val dayKey: String? = null)

enum class AlarmTierUi { ALARM, GENTLE }

enum class ReminderStatus { upcoming, missed, done }

enum class Recurrence(val label: String) { Once("Once"), Daily("Daily"), Weekdays("Weekdays"), Weekly("Weekly") }

@Immutable
data class ReminderEntry(
    val id: String,
    val title: String,
    val time: String,
    val tier: AlarmTierUi,
    val status: ReminderStatus,
    val recurrence: Recurrence? = null,
    val triggerType: TriggerType,
    val fallbackTime: String? = null,
)

enum class WakeupStatus { armed, fired }

@Immutable
data class AgentWakeupUi(
    val id: String,
    val scheduledFor: String,
    val reasonMotto: String,
    val sourceContext: String,
    val status: WakeupStatus,
)

enum class InboxKind { ambiguity, soul_proposal, duplicate, stale_30d, ambient }

@Immutable
data class InboxTriageItem(
    val id: String,
    val kind: InboxKind,
    val title: String,
    val subtitle: String,
    val primaryActionLabel: String,
    val secondaryActionLabel: String,
    /** Multi-option proposals (which dinner? which time?) list every choice here. */
    val choices: List<AmbiguityOption> = emptyList(),
)

enum class MemoryTab(val id: String) {
    Agenda("Agenda"), Items("Items"), Soul("Soul"), PeoplePlaces("People & Places"),
    Inbox("Inbox"), Learned("Learned"), Trash("Trash"),
}

enum class SettingKey {
    reasoningEnabled, contextSensing5GGuard, callNudgeEnabled, voiceRepliesEnabled,
    ambientListeningPaused, smartWebSearchEnabled, handsFreeActive, soulAutoMode,
    reducedMotion, devMode,
}

enum class HandsFreePhase { listening, speaking }

@Immutable
data class MineUiState(
    val currentScreen: ScreenId,
    val screenHistory: List<ScreenId>,
    val activeModal: ModalId?,
    val onboardingFastPath: Boolean,
    val consentAccepted: Boolean,
    val userName: String,
    val selectedLanguages: List<String>,
    val dayStart: String,
    val dayEnd: String,
    val persona: PersonaType,
    val statusChip: StatusChipState,
    val contextState: ContextState,
    val breakMinutesLeft: Int?,
    val procrastinationMode: ProcrastinationMode,
    val nudgesPausedUntil: String?,
    val reducedMotion: Boolean,
    val devMode: Boolean,

    // Toggles
    val reasoningEnabled: Boolean,
    val contextSensing5GGuard: Boolean,
    val callNudgeEnabled: Boolean,
    val voiceRepliesEnabled: Boolean,
    val ambientListeningPaused: Boolean,
    val smartWebSearchEnabled: Boolean,
    val handsFreeActive: Boolean,
    val handsFreePhase: HandsFreePhase,
    val soulAutoMode: Boolean,

    // Selected entity pointers
    val selectedItemId: String,
    val selectedNoteId: String,
    val selectedPastDate: String,
    val selectedHeatmapDate: String,
    val memoryActiveTab: MemoryTab,

    // Data collections
    val items: List<MineItem>,
    val recentlyClosedIds: List<String>,
    val livingNotes: List<LivingNote>,
    val soulFacts: List<SoulFact>,
    val reminders: List<ReminderEntry>,
    val todayMessages: List<ChatMessage>,
    val pastDayMessages: Map<String, List<ChatMessage>>,
    val agentWakeups: List<AgentWakeupUi>,
    val inboxItems: List<InboxTriageItem>,

    // Ephemeral UI feedback
    val toastMessage: String?,
    /** Action that reverses whatever the toast announced. Shown as an Undo button. */
    val toastUndo: MineAction?,
    val walkNotificationVisible: Boolean,

    /** +1 when going forward, -1 on back. Drives the horizontal screen transition. */
    val navDirection: Int,

    // Engine-backed system state (mirrors Kotlin UiState; see BackendContracts.kt)
    /** True until the first storage read lands. Screens show geometry-matched skeletons. */
    val isHydrating: Boolean,
    val reliability: ReliabilityScore,
    val handsFreeState: HandsFreeState,
    val ambientState: AmbientState,
    /** UiState.queuedChatIds: messages typed while offline, waiting to send. */
    val queuedChatCount: Int,
    /** UiState.providerCooldownExpiry (epoch ms). Drives a live countdown in the status chip. */
    val providerCooldownUntil: Long?,
    /** Reminders cancelled in the last few seconds, kept so Undo can restore them. */
    val recentlyCancelledReminders: List<ReminderEntry>,

    // ---- Live engine & device state (the single source of truth behind the slices above) ----
    /** The engine's full state. Screens that need more than the mapped slices read it directly. */
    val engine: com.anchor.core.ui.UiState = com.anchor.core.ui.UiState(),
    /** Permissions, voice, notification reading, backup progress: things only the device knows. */
    val platform: PlatformSnapshot = PlatformSnapshot(),
    /** Set when storage or the keystore could not start. Replaces the whole UI with a recovery screen. */
    val bootError: String? = null,
    /** The chat is waiting on the model (drives the send spinner and the typing indicator). */
    val chatBusy: Boolean = false,
    /** Plain-language chat problem with whether Retry / Settings make sense. */
    val chatError: ChatErrorUi? = null,
    /** Persona id as the engine knows it (companion, cofounder, coach, chill_friend, minimal). */
    val personaId: String = "companion",
    val onboardingDone: Boolean = true,
) {
    fun setting(key: SettingKey): Boolean = when (key) {
        SettingKey.reasoningEnabled -> reasoningEnabled
        SettingKey.contextSensing5GGuard -> contextSensing5GGuard
        SettingKey.callNudgeEnabled -> callNudgeEnabled
        SettingKey.voiceRepliesEnabled -> voiceRepliesEnabled
        SettingKey.ambientListeningPaused -> ambientListeningPaused
        SettingKey.smartWebSearchEnabled -> smartWebSearchEnabled
        SettingKey.handsFreeActive -> handsFreeActive
        SettingKey.soulAutoMode -> soulAutoMode
        SettingKey.reducedMotion -> reducedMotion
        SettingKey.devMode -> devMode
    }

    fun withSetting(key: SettingKey, value: Boolean): MineUiState = when (key) {
        SettingKey.reasoningEnabled -> copy(reasoningEnabled = value)
        SettingKey.contextSensing5GGuard -> copy(contextSensing5GGuard = value)
        SettingKey.callNudgeEnabled -> copy(callNudgeEnabled = value)
        SettingKey.voiceRepliesEnabled -> copy(voiceRepliesEnabled = value)
        SettingKey.ambientListeningPaused -> copy(ambientListeningPaused = value)
        SettingKey.smartWebSearchEnabled -> copy(smartWebSearchEnabled = value)
        SettingKey.handsFreeActive -> copy(handsFreeActive = value)
        SettingKey.soulAutoMode -> copy(soulAutoMode = value)
        SettingKey.reducedMotion -> copy(reducedMotion = value)
        SettingKey.devMode -> copy(devMode = value)
    }
}

/** Every reducer action from mineStore.tsx, 1:1. */
sealed interface MineAction {
    data class Navigate(val screen: ScreenId) : MineAction
    data class SelectPastDate(val date: String) : MineAction
    data object GoBack : MineAction
    data class OpenModal(
        val modal: ModalId?,
        val itemId: String? = null,
        val noteId: String? = null,
        val dateLabel: String? = null,
    ) : MineAction
    data object CloseModal : MineAction
    data class SetOnboardingFastPath(val fast: Boolean) : MineAction
    data class SetConsent(val accepted: Boolean) : MineAction
    data class SetUserName(val name: String) : MineAction
    data class ToggleLanguage(val lang: String) : MineAction
    data class SetDayBounds(val start: String, val end: String) : MineAction
    data class SetPersona(val persona: PersonaType) : MineAction
    data class SetStatusChip(val status: StatusChipState) : MineAction
    data class SetContextState(val context: ContextState) : MineAction
    data class StartBreak(val minutes: Int) : MineAction
    data object EndBreak : MineAction
    data class SetProcrastinationMode(val mode: ProcrastinationMode) : MineAction
    data class PauseNudges(val until: String?) : MineAction
    data class ToggleSetting(val key: SettingKey) : MineAction
    data class CompleteItem(val itemId: String) : MineAction
    data class UndoCompleteItem(val itemId: String) : MineAction
    data class RescheduleItem(val itemId: String, val newDue: String) : MineAction
    data class UndoRescheduleItem(val itemId: String) : MineAction
    data class UpdateItemStatus(val itemId: String, val status: ItemStatus) : MineAction
    data class ToggleItemLocalOnly(val itemId: String) : MineAction
    data class QuickAddOfflineItem(val rawText: String) : MineAction
    data class TogglePinNote(val noteId: String) : MineAction
    data class ToggleNoteAccessMode(val noteId: String) : MineAction
    data class AddNoteBullet(val noteId: String, val bullet: String) : MineAction
    data class CreateLivingNote(val title: String, val firstBullet: String) : MineAction
    data class ResolveAmbiguity(val messageId: String, val optionId: String) : MineAction
    data class UndoReceipt(val messageId: String, val receiptId: String) : MineAction
    data class SendChatMessage(val text: String) : MineAction
    /** Quote a message in the composer (swipe / long-press → Reply). Null clears it. */
    data class SetReplyTo(val draft: ReplyDraft?) : MineAction
    /** Long-press on a past day's message: open today's chat with it quoted, ready to talk about. */
    data class TalkAbout(val message: ChatMessage, val dayLabel: String?) : MineAction
    /** Ambient settings changed: re-evaluate the listening service now. */
    data object RefreshAmbient : MineAction
    /** Saves NVIDIA keys used only for Whisper transcription (empty list clears them). */
    data class SaveWhisperKeys(val keys: List<String>) : MineAction
    /** Loads every launchable app for the notification-sources screen. */
    data object LoadInstalledApps : MineAction
    // Model manager
    data class DownloadModel(val id: String) : MineAction
    data class CancelModel(val id: String) : MineAction
    data class DeleteModel(val id: String) : MineAction
    /** Makes an installed embedder or TTS engine the active one (re-indexes for embedders). */
    data class ActivateModel(val id: String) : MineAction
    // Voice enrolment
    data class EnrollRecord(val index: Int) : MineAction
    data object EnrollStop : MineAction
    data class EnrollRedo(val index: Int) : MineAction
    data object EnrollSave : MineAction
    data object EnrollReset : MineAction
    // About-you interview chat (isolated: no web, no voice, its own history)
    data class AboutYouSend(val text: String) : MineAction
    data object AboutYouSuggest : MineAction
    data object AboutYouReset : MineAction
    data object TriggerProcrastinationGate : MineAction
    data class TriggerWalkNotification(val visible: Boolean) : MineAction
    data class SetMemoryTab(val tab: MemoryTab) : MineAction
    data class AddReminder(
        val title: String,
        val time: String,
        val tier: AlarmTierUi,
        val triggerType: TriggerType,
    ) : MineAction
    data class SnoozeReminder(val reminderId: String, val minutes: Int) : MineAction
    data class CancelReminder(val reminderId: String) : MineAction
    data class ResolveInboxItem(val inboxId: String, val accept: Boolean) : MineAction
    data class ToggleSoulLocalOnly(val soulId: String) : MineAction
    data class ShowToast(val message: String, val undo: MineAction? = null) : MineAction
    data object DismissToast : MineAction
    data object RunToastUndo : MineAction
    data object Hydrated : MineAction
    data class RestoreReminder(val reminderId: String) : MineAction
    data class SetReliability(val score: ReliabilityScore) : MineAction
    data class SetProviderCooldown(val until: Long?) : MineAction

    // ---- Engine-backed actions added with the integration ----
    /** Any engine action, for screens that talk to the engine directly (provider, self-test…). */
    data class Engine(val action: com.anchor.core.ui.UiAction) : MineAction
    data object RetryFailedMessage : MineAction
    data object DismissChatError : MineAction
    data object StopReply : MineAction
    data class SpeakMessage(val messageId: String, val text: String) : MineAction
    data object StartVoice : MineAction
    data object StopVoice : MineAction
    data object PauseVoice : MineAction
    data object ResumeVoice : MineAction
    data class Undo(val token: String, val confirmation: String = "Undone") : MineAction
    data class SetNotificationReading(val settings: com.anchor.core.notifications.NotificationReadSettings) : MineAction
    data object OpenNotificationAccess : MineAction
    data object ClearHeardFeed : MineAction
    data object TestNotificationVoice : MineAction
    data class SaveHeardSuggestion(val heardId: String) : MineAction
    /** Asks the Activity to show a runtime permission prompt (RECORD_AUDIO, POST_NOTIFICATIONS, …). */
    data class RequestPermission(val permission: String) : MineAction
    data object RefreshPlatform : MineAction
    data class ExportBackup(val uri: android.net.Uri, val passphrase: String, val includeKeys: Boolean) : MineAction
    data class ImportBackup(val uri: android.net.Uri, val passphrase: String, val verifyOnly: Boolean) : MineAction
    data class ExportMarkdown(val uri: android.net.Uri) : MineAction
    data class SetAutoBackupFolder(val uri: android.net.Uri, val passphrase: String) : MineAction
    data class SetAmbientListening(val on: Boolean) : MineAction
    data object FinishOnboarding : MineAction
    data class SetDayWindow(val startMinute: Int, val endMinute: Int) : MineAction
}

@Immutable
data class ChatErrorUi(val message: String, val canRetry: Boolean, val needsSettings: Boolean)
