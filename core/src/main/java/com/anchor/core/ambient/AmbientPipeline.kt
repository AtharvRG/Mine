// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.ambient

import java.util.UUID

/**
 * The middle of the ambient pipeline (blueprint 61 §3.1), between "speech segment with a speaker
 * label" and "transcribe this":
 *
 *   VAD segment → speaker check (user / other) → [add] → RAM ring + open dialogue window
 *     → window closes (silence gap, or the 60 s / 12-segment cap) → presence gate
 *     → gate open: [ReadyWindow] with the segments, batched by speaker run, ready for STT
 *     → gate shut: the window is dropped from RAM; its audio never reaches any STT.
 *
 * Everything here is RAM only. Audio is dropped from the ring after [ringMs] and from a window
 * as soon as it is handed out. Not thread-safe: one listening loop owns it.
 */
class AmbientPipeline(
    private val config: Config = Config(),
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    data class Config(
        /** How much audio the ring keeps for "remember the last 2 minutes" (`ambient.ring_s`). */
        val ringMs: Long = 120_000L,
        /** Silence that ends a conversation window (`ambient.window_gap_s`). */
        val windowGapMs: Long = 90_000L,
        val maxWindowMs: Long = 60_000L,
        val maxSegmentsPerWindow: Int = 12,
        /** Most speech sent to STT in one request (`ambient.batch_s`). */
        val batchMs: Long = 30_000L,
        /** A window is transcribed only if a user segment scores at least this (`ambient.t_lo`). */
        val tLo: Float = 0.60f,
        /** Off = every detected window is transcribed (power-user switch, consent says so). */
        val gateUserPresence: Boolean = true,
    )

    data class Segment(
        val id: String,
        val tStartMs: Long,
        val tEndMs: Long,
        val samples: FloatArray,
        val label: SpeakerLabel,
        val score: Float,
        val isShort: Boolean,
    ) {
        val durationMs: Long get() = tEndMs - tStartMs
    }

    /** Consecutive segments of one speaker, joined with short silences, up to [Config.batchMs]. */
    class Batch(val label: SpeakerLabel, val segments: List<Segment>, val sampleRate: Int = 16_000) {
        val tStartMs: Long get() = segments.first().tStartMs
        val tEndMs: Long get() = segments.last().tEndMs
        /** Best user score in the run, or the run's average for other speakers. */
        val score: Float get() = if (label == SpeakerLabel.USER) segments.maxOf { it.score } else segments.map { it.score }.average().toFloat()
        val isShort: Boolean get() = segments.all { it.isShort }

        /** The run's audio with [gapMs] of silence between segments, for one STT request. */
        fun joinedSamples(gapMs: Int = 300): FloatArray {
            val gap = sampleRate * gapMs / 1000
            val total = segments.sumOf { it.samples.size } + gap * (segments.size - 1).coerceAtLeast(0)
            val out = FloatArray(total)
            var pos = 0
            segments.forEachIndexed { i, s ->
                if (i > 0) pos += gap
                System.arraycopy(s.samples, 0, out, pos, s.samples.size)
                pos += s.samples.size
            }
            return out
        }
    }

    data class ReadyWindow(
        val id: String,
        val segments: List<Segment>,
        val batches: List<Batch>,
        val startedAtMs: Long,
        val endedAtMs: Long,
    )

    /** What happened to windows so far: shown in the listening stats, never their content. */
    data class Stats(val windowsTranscribed: Int = 0, val windowsDropped: Int = 0, val userSegments: Int = 0, val otherSegments: Int = 0)

    private val ring = ArrayDeque<Segment>()
    private val open = ArrayList<Segment>()
    var stats = Stats()
        private set

    /** Adds one labeled segment. Returns any window this closed (at most one). */
    fun add(segment: Segment): List<ReadyWindow> {
        stats = if (segment.label == SpeakerLabel.USER) stats.copy(userSegments = stats.userSegments + 1)
        else stats.copy(otherSegments = stats.otherSegments + 1)

        ring.addLast(segment)
        trimRing(segment.tEndMs)

        val out = ArrayList<ReadyWindow>(1)
        if (open.isNotEmpty()) {
            val gap = segment.tStartMs - open.last().tEndMs
            if (gap >= config.windowGapMs) closeWindow()?.let { out += it }
        }
        open += segment
        val span = open.last().tEndMs - open.first().tStartMs
        if (span >= config.maxWindowMs || open.size >= config.maxSegmentsPerWindow) closeWindow()?.let { out += it }
        return out
    }

    /** Call regularly: closes the open window once [Config.windowGapMs] of silence has passed. */
    fun tick(nowMs: Long): List<ReadyWindow> {
        trimRing(nowMs)
        if (open.isEmpty()) return emptyList()
        return if (nowMs - open.last().tEndMs >= config.windowGapMs) listOfNotNull(closeWindow()) else emptyList()
    }

    /** Closes the open window now (listening stopped or paused). */
    fun flush(): List<ReadyWindow> = listOfNotNull(closeWindow())

    /** Segments heard in the last [withinMs], gated or not: the "remember the last 2 minutes" button. */
    fun recent(nowMs: Long, withinMs: Long): List<Segment> = ring.filter { it.tEndMs >= nowMs - withinMs }

    /** Wipes all audio from RAM (stop, privacy delete). */
    fun clear() {
        ring.clear()
        open.clear()
    }

    fun gateOpens(segments: List<Segment>): Boolean =
        !config.gateUserPresence || segments.any { it.label == SpeakerLabel.USER && it.score >= config.tLo }

    private fun closeWindow(): ReadyWindow? {
        if (open.isEmpty()) return null
        val segs = open.toList()
        open.clear()
        if (!gateOpens(segs)) {
            stats = stats.copy(windowsDropped = stats.windowsDropped + 1)
            return null
        }
        stats = stats.copy(windowsTranscribed = stats.windowsTranscribed + 1)
        return ReadyWindow(newId(), segs, batchRuns(segs), segs.first().tStartMs, segs.last().tEndMs)
    }

    fun batchRuns(segments: List<Segment>): List<Batch> {
        val out = ArrayList<Batch>()
        var run = ArrayList<Segment>()
        var runMs = 0L
        for (s in segments) {
            val sameLabel = run.isEmpty() || run.last().label == s.label
            if (!sameLabel || (run.isNotEmpty() && runMs + s.durationMs > config.batchMs)) {
                out += Batch(run.first().label, run)
                run = ArrayList()
                runMs = 0
            }
            run += s
            runMs += s.durationMs
        }
        if (run.isNotEmpty()) out += Batch(run.first().label, run)
        return out
    }

    private fun trimRing(nowMs: Long) {
        while (ring.isNotEmpty() && ring.first().tEndMs < nowMs - config.ringMs) ring.removeFirst()
    }
}
