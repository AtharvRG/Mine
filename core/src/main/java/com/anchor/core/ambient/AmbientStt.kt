// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.ambient

/**
 * Where ambient speech is transcribed (blueprint 61 §3.1, `ambient.stt.mode`).
 *
 *  - DEVICE: sherpa-onnx on the phone only. Nothing leaves the device.
 *  - CLOUD:  NVIDIA NIM Whisper (needs at least one NVIDIA key). Falls back to the phone when it
 *            can't be used, and says why.
 *  - HYBRID: NIM Whisper when a key, a network and budget are available, the phone otherwise.
 */
enum class AmbientSttMode(val wire: String, val label: String) {
    DEVICE("device", "On this phone"),
    CLOUD("cloud", "NVIDIA Whisper"),
    HYBRID("hybrid", "Whisper, phone as backup");

    companion object {
        const val SETTING = "ambient.stt.mode"
        fun fromWire(v: String?): AmbientSttMode = entries.firstOrNull { it.wire == v } ?: DEVICE
    }
}

enum class SttRoute { DEVICE, NIM_WHISPER, NONE }

data class SttPlan(
    val route: SttRoute,
    /** Why this route rather than the one asked for, for the listening status line. */
    val note: String? = null,
    /** Something the user should fix ("add an NVIDIA key"). Shown as a warning card. */
    val warning: String? = null,
)

object AmbientSttPlanner {
    const val NO_KEY_WARNING = "NVIDIA Whisper needs at least one NVIDIA API key. Add one in Settings → AI provider or below — until then Mine transcribes on this phone."
    const val NO_KEY_NO_DEVICE_WARNING = "NVIDIA Whisper needs an NVIDIA API key, and the on-phone speech model isn't installed. Listening can't transcribe anything yet."

    fun plan(
        mode: AmbientSttMode,
        deviceSttReady: Boolean,
        nimKeyCount: Int,
        online: Boolean,
        cloudSecondsToday: Long,
        cloudCapSeconds: Long,
    ): SttPlan {
        val device = if (deviceSttReady) SttRoute.DEVICE else SttRoute.NONE
        if (mode == AmbientSttMode.DEVICE) {
            return if (deviceSttReady) SttPlan(SttRoute.DEVICE)
            else SttPlan(SttRoute.NONE, "No on-phone speech model", "Install the speech-to-text model in Settings → Models, or switch listening to NVIDIA Whisper.")
        }
        val noKeyWarning = if (deviceSttReady) NO_KEY_WARNING else NO_KEY_NO_DEVICE_WARNING
        return when {
            nimKeyCount <= 0 -> SttPlan(device, "No NVIDIA key", noKeyWarning)
            !online -> SttPlan(device, if (deviceSttReady) "Offline — using the phone" else "Offline and no on-phone model")
            cloudSecondsToday >= cloudCapSeconds -> SttPlan(device, if (deviceSttReady) "Daily Whisper limit reached — using the phone" else "Daily Whisper limit reached")
            else -> SttPlan(SttRoute.NIM_WHISPER)
        }
    }
}
