// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.ambient

import com.anchor.core.voice.SyntheticAudioFixtures

/**
 * Evaluates speech segments against the enrolled voiceprint and manages
 * the user-presence gate per Blueprint 61 §3.3 & §3.4.
 *
 * Invariants:
 * 1. Audio and segments live strictly in RAM memory ring buffers.
 * 2. Window is uploaded to STT / processed ONLY if at least one user segment
 *    meets or exceeds the confidence threshold t_lo.
 * 3. Segments shorter than 1.5s are marked short and cannot reach t_hi alone.
 */
class PresenceGate(
    private val tHi: Float = 0.80f,
    private val tLo: Float = 0.60f,
    private val shortDurationSec: Float = 1.5f,
    private val gateUserPresence: Boolean = true
) {

    data class ScoredSegment(
        val tStartMs: Long,
        val tEndMs: Long,
        val audioSamples: FloatArray,
        val label: SpeakerLabel,
        val score: Float,
        val isShort: Boolean
    )

    /**
     * Scores an audio segment against an enrolled voiceprint.
     */
    fun scoreSegment(
        tStartMs: Long,
        tEndMs: Long,
        samples: FloatArray,
        segmentEmbedding: FloatArray?,
        voiceprintFloats: FloatArray?
    ): ScoredSegment {
        val durationSec = (tEndMs - tStartMs).coerceAtLeast(0L) / 1000.0f
        val isShort = durationSec < shortDurationSec

        if (segmentEmbedding == null || voiceprintFloats == null) {
            return ScoredSegment(
                tStartMs = tStartMs,
                tEndMs = tEndMs,
                audioSamples = samples,
                label = SpeakerLabel.OTHER,
                score = 0.0f,
                isShort = isShort
            )
        }

        val similarity = SyntheticAudioFixtures.cosineSimilarity(segmentEmbedding, voiceprintFloats)
        val label = if (similarity >= tLo) SpeakerLabel.USER else SpeakerLabel.OTHER

        return ScoredSegment(
            tStartMs = tStartMs,
            tEndMs = tEndMs,
            audioSamples = samples,
            label = label,
            score = similarity,
            isShort = isShort
        )
    }

    /**
     * Evaluates a list of dialogue window segments to determine if the presence gate opens.
     * Blueprint 61 §3.4: "A window of conversation is sent to STT only if it contains at least
     * one user segment at or above t_lo."
     *
     * If [manualBookmark] is true (Blueprint 61 §9, P-019), the presence gate is forcibly opened
     * by user intent to capture the recent exchange even if speaker confidence was low.
     */
    fun evaluateWindowGate(segments: List<ScoredSegment>, manualBookmark: Boolean = false): Boolean {
        if (manualBookmark) return true
        if (!gateUserPresence) return true
        if (segments.isEmpty()) return false

        // Check if there is at least one confident or standard user segment
        return segments.any { it.label == SpeakerLabel.USER && it.score >= tLo }
    }

    /**
     * Filters segments in the RAM ring buffer to keep context up to ringDurationMs (default 90s).
     */
    fun trimRingBuffer(
        buffer: MutableList<ScoredSegment>,
        maxRingDurationMs: Long = 90_000L,
        currentTimestampMs: Long
    ) {
        val cutoff = currentTimestampMs - maxRingDurationMs
        buffer.removeAll { it.tEndMs < cutoff }
    }
}
