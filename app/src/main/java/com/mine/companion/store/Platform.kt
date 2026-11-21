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
