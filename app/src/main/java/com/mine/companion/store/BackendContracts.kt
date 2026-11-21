@file:Suppress("EnumEntryName", "unused")

package com.mine.companion.store

import androidx.compose.runtime.Immutable

/*
 * Kotlin mirror of src/store/backendContracts.ts (which itself mirrors the engine's
 * core/ui, core/models, core/insights, core/ambient, core/voice, core/rebuild,
 * core/provider and core/sensing contracts). Times are epoch millis.
 */

/* ---------- Memory graph ---------- */
@Immutable
data class Node(
    val id: String,
    val type: String,       // task | errand | habit | goal | event | …
    val title: String,
    val status: String,     // todo | in_progress | done | paused | archived | trashed | cancelled | …
    val dueAt: Long?,
    val createdAt: Long,
    val updatedAt: Long,
    val closedAt: Long?,
    val priority: Int,
    val severity: Int,
    val pinned: Boolean,
    val localOnly: Boolean,
    val sensitive: Boolean,
    val origin: String,     // user_edit | chat | quick_add | ambient …
    val recurrenceRule: String?,
    val streakCurrent: Int,
    val streakBest: Int,
    val lastCompletedAt: Long?,
    val originalDueAt: Long?,
    val dueHistoryJson: String?, // → strikethrough history
    val slipCount: Int,
    val contextTrigger: String?, // CONTEXT_LEFT_HOME …
    val fallbackFireAt: Long?,
)

@Immutable
data class TopicNote(
    val id: String,
    val topic: String,
    val content: String,
    val bulletsJson: String,
    val pinned: Boolean,
    val lastEditedAt: Long,
    val createdAt: Long,
)

@Immutable
data class NoteReference(
    val id: String,
    val noteId: String,
    val nodeId: String,
    val createdAt: Long,
    val mode: NoteAccessMode,
)

enum class AlarmTier { ALARM, GENTLE }

@Immutable
data class Reminder(
    val id: String,
    val text: String,
    val fireAtMillis: Long,
    val nodeId: String?,
    val tier: AlarmTier,
    val rrule: String?,
    val iteration: Int,
)

/** ScheduleResult.Overdue. */
@Immutable
data class OverdueReminder(val reminderId: String, val text: String, val scheduledFor: Long, val overdueByMs: Long)

enum class WakeupState { pending, fired, cancelled }

@Immutable
data class AgentWakeup(
    val id: String,
    val fireAt: Long,
    val reason: String,
    val payloadJson: String?,
    val state: WakeupState,
    val createdAt: Long,
)

@Immutable
data class EngineSoulFact(
    val id: String,
    val category: String,
    val statement: String,
    val confidence: Float, // 0..1
    val source: String,
    val origin: String,
    val status: String,    // proposed | accepted | rejected | …
    val createdAt: Long,
    val localOnly: Boolean,
    val sensitive: Boolean,
)

enum class IntentType { CREATE, UPDATE, COMPLETE, CANCEL, RESCHEDULE, PAUSE, RESUME }

@Immutable
data class ProposalOption(val id: String, val title: String, val dueTime: String?)

@Immutable
data class Proposal(
    val id: String,
    val kind: String,       // disambiguation | duplicate | …
    val intent: IntentType,
    val options: List<ProposalOption>,
    val status: String,     // open | resolved | expired | …
    val payload: String?,
)

/* ---------- Chat timeline ---------- */
@Immutable
data class ReceiptState(
    val id: String,
    val message: String,
    /** null = this action cannot be undone. Show Undo only when present. */
    val undoToken: String?,
    val isError: Boolean,
    val causeMsgId: String?,
    val timestamp: Long,
)

enum class ChatRole { user, assistant, system }

/** ChatItems.kt — the engine already interleaves these. */
sealed interface ChatItem {
    val tsUtc: Long
    data class DayHeader(val dayKey: String, override val tsUtc: Long) : ChatItem
    data class TimeDivider(override val tsUtc: Long) : ChatItem
    data class Message(val id: String, val role: ChatRole, val content: String?, override val tsUtc: Long) : ChatItem {
        /** `content == null` means the message was redacted (local-only). */
        val isRedacted: Boolean get() = content == null
    }
    data class SystemEvent(val text: String, override val tsUtc: Long) : ChatItem
    data class ReceiptItem(val receipt: ReceiptState, override val tsUtc: Long) : ChatItem
    data class ProposalRow(val proposal: Proposal, override val tsUtc: Long) : ChatItem
    data class CheckinCard(val title: String, val summary: String, override val tsUtc: Long) : ChatItem
    data class Nudge(val content: String, val tag: String?, val reason: String?, override val tsUtc: Long) : ChatItem
    data class StatusNotice(val text: String, val isError: Boolean, override val tsUtc: Long) : ChatItem
}

@Immutable
data class RecentlyClosed(val node: Node, val undoValid: Boolean, val undoToken: String?)

/* ---------- System health ---------- */
enum class ReliabilityScore { healthy, at_risk, degraded }

@Immutable
data class ReliabilityState(
    val lastCheckAt: Long?,
    val score: ReliabilityScore?,
    val issuesJson: String?,
    val sweeperHeartbeat: Long?,
    val p95LatencyMs: Long?,
)

/** Provider failure modes from provider/Models.kt — each needs its own UI copy. */
sealed interface ProviderFault {
    data object Offline : ProviderFault
    data class RateLimited(val retryAfterSeconds: Int?) : ProviderFault
    data object Timeout : ProviderFault
    data class Http(val code: Int, val transient: Boolean) : ProviderFault
    data object AllFailed : ProviderFault
    data object OnDeviceFallback : ProviderFault
}

/* ---------- Voice ---------- */
enum class HandsFreeState { IDLE, LISTENING, PROCESSING, SPEAKING }

enum class AmbientSleepReason(val display: String) {
    user_paused("Paused by you"),
    outside_window("Outside listening hours"),
    battery_low("Battery below 20%"),
    thermal_pause("Phone is too warm"),
    charging_only("Listens only while charging"),
    mic_busy("Microphone is in use"),
    pause_place("Private place: not listening"),
    no_stt("No speech-to-text available"),
    daily_cap_reached("Daily listening limit reached"),
}

sealed interface AmbientState {
    data object Off : AmbientState
    data class Sleeping(val reason: AmbientSleepReason) : AmbientState
    data object Listening : AmbientState
    data object Processing : AmbientState
}

@Immutable
data class AmbientReportItem(
    val id: String,
    val reportId: String,
    val windowId: String?,
    val kind: String,   // keypoint | proposal
    val summary: String,
    val nodeId: String?,
    val flags: String?, // attribution_uncertain, possible_duplicate
    val state: String,  // pending | accepted | edited | rejected | expired
    val reviewedAt: Long?,
)

/* ---------- Insights ---------- */
@Immutable data class DayBucket(val dayKey: String, val createdCount: Int, val completedCount: Int)
@Immutable data class HabitStreakSummary(val habitNodeId: String, val habitTitle: String, val currentStreak: Int, val bestStreak: Int, val lastCompletedAt: Long?, val dueAt: Long?)
@Immutable data class GoalProgress(val goalNodeId: String, val goalTitle: String, val totalSubTasks: Int, val completedSubTasks: Int, val progressPercent: Float)
@Immutable data class ActivityHeatStrip(val hourlyCounts: List<Int>, val peakHour: Int, val totalActions: Int)
@Immutable data class MoodPoint(val dayKey: String, val score: Float, val note: String?, val recordedAt: Long)
@Immutable data class WeeklyReview(val startMs: Long, val endMs: Long, val createdCount: Int, val completedCount: Int, val cancelledCount: Int, val carriedOverCount: Int, val completionRate: Float, val dailyBars: List<DayBucket>, val wins: List<String>)
@Immutable data class MonthlyReview(val startMs: Long, val endMs: Long, val isMonthToDate: Boolean, val createdCount: Int, val completedCount: Int, val cancelledCount: Int, val carriedOverCount: Int, val completionRate: Float, val weeklyCompletions: List<Int>, val topHabitWins: List<String>)
@Immutable data class FullInsightsReport(
    val generatedAt: Long,
    val weeklyReview: WeeklyReview,
    val monthlyReview: MonthlyReview,
    val goalProgress: List<GoalProgress>,
    val habitStreaks: List<HabitStreakSummary>,
    val activityHeatStrip: ActivityHeatStrip,
    val moodTrends: List<MoodPoint>,
)

/* ---------- Home quote, learning, rebuild ---------- */
enum class QuoteSlot { MORNING, AFTERNOON, EVENING, NIGHT }
enum class QuoteSource { PRESET, LIVE }
@Immutable data class Quote(val slot: QuoteSlot, val dayKey: String, val text: String, val generatedAt: Long, val stateHash: String?, val source: QuoteSource, val regenCount: Int)

@Immutable data class LearnedPref(val key: String, val scope: String, val valueJson: String, val samples: Int, val confidence: Float, val locked: Boolean, val updatedAt: Long)

enum class RebuildStatus { IDLE, PLANNING, RUNNING, VALIDATING, COMPLETED, FAILED, PAUSED }
enum class RebuildTarget { DERIVED_NODES, DERIVED_EDGES, EMBEDDINGS, FTS, FULL }
@Immutable data class RebuildProgress(val target: RebuildTarget, val processedMessages: Int, val totalMessages: Int, val status: RebuildStatus, val error: String?)

enum class CallNudgeState { pending, nudged, answered, skipped, expired, ignored }

/** core/sensing/ContextModels.kt — two independent axes, not one 4-value enum. */
enum class MotionAxis { UNKNOWN, STILL, WALKING, VEHICLE }
sealed interface PlaceAxis {
    data object Unknown : PlaceAxis
    data object Home : PlaceAxis
    data object Away : PlaceAxis
    data class SavedPlace(val id: String, val label: String) : PlaceAxis
}
@Immutable data class EngineContextState(val place: PlaceAxis, val motion: MotionAxis, val placeSinceUtc: Long, val motionSinceUtc: Long)
