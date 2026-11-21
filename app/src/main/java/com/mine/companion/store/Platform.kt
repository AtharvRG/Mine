package com.mine.companion.store

import androidx.compose.runtime.Immutable
import com.anchor.core.notifications.HeardNotification

/** Everything the UI needs from the device itself, refreshed by [MineStore]. */
@Immutable
data class PlatformSnapshot(
    val micGranted: Boolean = false,
    val notificationsGranted: Boolean = true,
    /** Notification access (listener) granted in system settings. */
    val notificationAccess: Boolean = false,
    val listenerConnected: Boolean = false,
    val heard: List<HeardNotification> = emptyList(),
    val heardPendingCatchUp: Int = 0,
    /** Apps that posted recently (package → label), for the per-app picker. */
    val recentApps: Map<String, String> = emptyMap(),
    val voice: VoiceUi = VoiceUi(),
    /** Id of the message being read aloud right now, if any. */
    val speakingId: String? = null,
    val offlineSttInstalled: Boolean = false,
    val offlineTtsInstalled: Boolean = false,
    val systemSttAvailable: Boolean = false,
    val backup: BackupUi = BackupUi(),
    val is24Hour: Boolean = true,
    val lowPower: Boolean = false,
    /** "At home · still", "Away · walking"; null when place sensing is off or unknown. */
    val contextLabel: String? = null,
    /** Text handed to Mine from the share sheet, waiting in the composer. */
    val composerPrefill: String? = null,
    /** Increments when something (the launcher shortcut, a share) asks for the composer. */
    val composerFocusTick: Int = 0,
    /** What the ambient pipeline is doing: STT route, warnings, live speaker score. No content. */
    val ambientPipeline: com.anchor.mine.ambient.AmbientStatusBus.Pipeline = com.anchor.mine.ambient.AmbientStatusBus.Pipeline(),
    /** Neural speaker model installed (real x-vectors) vs the signal-processing fallback. */
    val speakerModelInstalled: Boolean = false,
    /** Silero VAD installed. */
    val vadInstalled: Boolean = false,
    /** Launchable apps (package → label) for the notification-sources picker. Loaded on demand. */
    val installedApps: Map<String, String> = emptyMap(),
    /** The message the composer is replying to (WhatsApp-style quote), if any. */
    val replyTo: ReplyDraft? = null,
    /** Model manager: every catalog entry's install state, keyed by catalog id. */
    val models: Map<String, ModelUi> = emptyMap(),
    /** Voice enrolment in progress or finished. */
    val enrollment: EnrollmentUi = EnrollmentUi(),
)

/** A quoted message above the composer. [dayLabel] is set when it comes from another day. */
@Immutable
data class ReplyDraft(val messageId: String, val sender: Sender, val preview: String, val dayLabel: String? = null)

@Immutable
data class ModelUi(
    val state: String = "absent", // absent | downloading | ready | failed
    val progress: Float = 0f,
    val bytesOnDisk: Long = 0L,
    val error: String? = null,
    val active: Boolean = false,
)

@Immutable
data class EnrollmentClipUi(val index: Int, val issues: List<String>, val ok: Boolean, val similarity: Float?)

@Immutable
data class EnrollmentUi(
    /** idle | recording | analysing | done */
    val phase: String = "idle",
    /** Which prompt is being read (0-based). */
    val prompt: Int = 0,
    val level: Float = 0f,
    val clips: List<EnrollmentClipUi> = emptyList(),
    val verdict: String? = null,
    val verdictTitle: String? = null,
    val advice: List<String> = emptyList(),
    val consistency: Float = 0f,
    val neural: Boolean = false,
    val saved: Boolean = false,
)

enum class VoicePhase { idle, listening, thinking, speaking, paused }

@Immutable
data class VoiceUi(
    val phase: VoicePhase = VoicePhase.idle,
    val heard: String = "",
    val reply: String = "",
    val problem: String? = null,
)

@Immutable
data class BackupUi(
    val busy: Boolean = false,
    val message: String? = null,
    val isError: Boolean = false,
    val lastExportAt: Long? = null,
)
