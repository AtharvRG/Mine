// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.voice

/**
 * Operational, device-local representation of an asynchronous voice transcription or synthesis job.
 * Blueprint 60 §4.
 */
data class VoiceJob(
    val id: String,
    val kind: VoiceJobKind,
    val state: VoiceJobState = VoiceJobState.QUEUED,
    val msgId: String? = null,
    val audioPath: String? = null,
    val targetRef: String? = null,
    val attempts: Int = 0,
    val createdAt: Long,
    val updatedAt: Long = createdAt,
    val error: String? = null
)

enum class VoiceJobKind(val wire: String) {
    STT("stt"),
    TTS("tts");

    companion object {
        fun fromWire(value: String): VoiceJobKind =
            entries.firstOrNull { it.wire.equals(value, ignoreCase = true) } ?: STT
    }
}

enum class VoiceJobState(val wire: String) {
    QUEUED("queued"),
    RUNNING("running"),
    DONE("done"),
    FAILED("failed"),
    EXPIRED("expired");

    companion object {
        fun fromWire(value: String): VoiceJobState =
            entries.firstOrNull { it.wire.equals(value, ignoreCase = true) } ?: QUEUED
    }
}

/**
 * Device-local registry of downloadable or on-device AI model assets.
 * Blueprint 60 §3.4 / §4.
 */
data class ModelAsset(
    val id: String,
    val kind: ModelAssetKind,
    val name: String,
    val version: String? = null,
    val sizeBytes: Long? = null,
    val sha256: String? = null,
    val licenseRef: String? = null,
    val path: String? = null,
    val state: ModelAssetState = ModelAssetState.READY,
    val updatedAt: Long
)

enum class ModelAssetKind(val wire: String) {
    EMBED("embed"),
    VAD("vad"),
    STT("stt"),
    TTS("tts"),
    SPEAKER("speaker");

    companion object {
        fun fromWire(value: String): ModelAssetKind =
            entries.firstOrNull { it.wire.equals(value, ignoreCase = true) } ?: EMBED
    }
}

enum class ModelAssetState(val wire: String) {
    ABSENT("absent"),
    DOWNLOADING("downloading"),
    READY("ready"),
    FAILED("failed");

    companion object {
        fun fromWire(value: String): ModelAssetState =
            entries.firstOrNull { it.wire.equals(value, ignoreCase = true) } ?: READY
    }
}
