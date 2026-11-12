// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.ambient

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * State machine for the Ambient Listening subsystem.
 * Blueprint 61 §3.2.
 */
sealed class AmbientState {
    object Off : AmbientState()
    data class Sleeping(val reason: AmbientSleepReason) : AmbientState()
    object Listening : AmbientState()
    object Processing : AmbientState()
}

enum class AmbientSleepReason(val wire: String, val display: String) {
    USER_PAUSED("user_paused", "Paused by user"),
    OUTSIDE_WINDOW("outside_window", "Outside listening window"),
    BATTERY_LOW("battery_low", "Battery low (< 20%)"),
    THERMAL_PAUSE("thermal_pause", "Phone too warm"),
    CHARGING_ONLY("charging_only", "Charging only mode"),
    MIC_BUSY("mic_busy", "Microphone in use"),
    PAUSE_PLACE("pause_place", "In a private location"),
    NO_STT("no_stt", "No speech-to-text path"),
    DAILY_CAP_REACHED("daily_cap_reached", "Daily listening/STT limit reached");

    companion object {
        fun fromWire(value: String): AmbientSleepReason =
            entries.firstOrNull { it.wire.equals(value, ignoreCase = true) } ?: USER_PAUSED
    }
}

/**
 * Device-local enrolled speaker voiceprint embedding vector.
 * Blueprint 61 §3.3 & §4.
 * NEVER exported, NEVER synced, NEVER stored outside device encrypted DB.
 */
data class Voiceprint(
    val id: String,
    val embedding: ByteArray,
    val modelRef: String,
    val createdAt: Long
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Voiceprint) return false
        return id == other.id && embedding.contentEquals(other.embedding) &&
                modelRef == other.modelRef && createdAt == other.createdAt
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + embedding.contentHashCode()
        result = 31 * result + modelRef.hashCode()
        result = 31 * result + createdAt.hashCode()
        return result
    }

    fun toFloatArray(): FloatArray {
        val buffer = ByteBuffer.wrap(embedding).order(ByteOrder.LITTLE_ENDIAN)
        val floats = FloatArray(embedding.size / 4)
        for (i in floats.indices) {
            floats[i] = buffer.float
        }
        return floats
    }

    companion object {
        fun fromFloatArray(id: String, floats: FloatArray, modelRef: String, createdAt: Long): Voiceprint {
            val buffer = ByteBuffer.allocate(floats.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            for (f in floats) {
                buffer.putFloat(f)
            }
            return Voiceprint(id, buffer.array(), modelRef, createdAt)
        }
    }
}

enum class SpeakerLabel(val wire: String) {
    USER("user"),
    OTHER("other");

    companion object {
        fun fromWire(value: String): SpeakerLabel =
            entries.firstOrNull { it.wire.equals(value, ignoreCase = true) } ?: OTHER
    }
}

/**
 * Operational, no-content session log for ambient listening.
 * Blueprint 61 §4.
 */
data class AmbientSession(
    val id: String,
    val startedAt: Long,
    val endedAt: Long? = null,
    val endReason: String? = null,
    val listenSeconds: Int = 0,
    val speechSeconds: Int = 0,
    val uploadedSeconds: Int = 0,
    val segmentsCount: Int = 0
)

/**
 * Group of speech segments within a conversation window.
 * Blueprint 61 §3.5 & §4.
 */
data class AmbientWindow(
    val id: String,
    val sessionId: String,
    val startedAt: Long,
    val endedAt: Long,
    val state: String = "open", // open | transcribed | extracted | expired
    val gateOpened: Boolean = false
)

/**
 * Short-lived staging table for transcribed speech segments.
 * Blueprint 61 §3.6 & §4.
 * Transcripts expire in 48 hours or upon review.
 */
data class AmbientSegment(
    val id: String,
    val windowId: String,
    val tStart: Long,
    val tEnd: Long,
    val label: String, // user | other
    val score: Float? = null,
    val isShort: Boolean = false,
    val text: String? = null,
    val sttRef: String? = null,
    val expiresAt: Long
)

/**
 * Daily ambient report.
 * Blueprint 61 §3.8 & §4.
 */
data class AmbientReport(
    val id: String,
    val dayKey: String,
    val generatedAt: Long,
    val state: String = "pending" // pending | reviewed | expired
)

/**
 * Individual keypoint or proposed node extracted from an ambient dialogue window.
 * Blueprint 61 §3.8 & §4.
 */
data class AmbientReportItem(
    val id: String,
    val reportId: String,
    val windowId: String? = null,
    val kind: String, // keypoint | proposal
    val summary: String,
    val nodeId: String? = null,
    val flags: String? = null, // attribution_uncertain, possible_duplicate
    val state: String = "pending", // pending | accepted | edited | rejected | expired
    val reviewedAt: Long? = null
)
