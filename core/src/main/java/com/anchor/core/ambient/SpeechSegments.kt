// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.ambient

import kotlin.math.sqrt

/**
 * One stretch of speech cut out of the microphone stream by a voice activity detector, with
 * the silence around it trimmed. Lives only in RAM (blueprint 61 §1 rule 2).
 *
 * [startSample] counts from the start of the stream the segmenter has seen, so segments from
 * one stream can be laid on one timeline.
 */
class VadSegment(val startSample: Long, val samples: FloatArray, val sampleRate: Int = 16_000) {
    val durationMs: Long get() = samples.size * 1000L / sampleRate
    val endSample: Long get() = startSample + samples.size
}

/**
 * Turns a continuous 16 kHz mono float stream into speech segments. Feed audio as it arrives
 * (any chunk size) with [accept], collect finished segments with [drain]. Implementations keep
 * only the audio of the segment being built; silence is dropped as it streams past.
 *
 * The production implementation wraps Silero VAD from sherpa-onnx (app module). [EnergySegmenter]
 * is the dependency-free fallback used when the VAD model is not installed, and in tests.
 */
interface SpeechSegmenter {
    val sampleRate: Int
    fun accept(samples: FloatArray)
    fun drain(): List<VadSegment>
    /** Ends the current segment as if silence followed. */
    fun flush()
    /** Forgets any partial segment and restarts the sample count. */
    fun reset()
}

/**
 * Frame-energy segmenter: a frame is speech when its RMS is well above the running noise floor.
 * A segment starts after [minSpeechMs] of speech frames and ends after [minSilenceMs] of
 * silence; segments longer than [maxSpeechMs] are cut so no single segment grows without bound.
 * Coarser than Silero (it can't tell a voice from a vacuum cleaner), which is why the speaker
 * check and the presence gate still sit after it.
 */
class EnergySegmenter(
    override val sampleRate: Int = 16_000,
    private val frameMs: Int = 32,
    private val minSpeechMs: Int = 250,
    private val minSilenceMs: Int = 500,
    private val maxSpeechMs: Int = 20_000,
    private val absoluteFloorRms: Float = 0.01f,
    private val snrFactor: Float = 3.0f,
    private val paddingMs: Int = 200,
) : SpeechSegmenter {
    private val frameLen = sampleRate * frameMs / 1000
    private val pending = FloatArrayBuilder()
    private var streamPos = 0L
    private var noise = absoluteFloorRms / 2
    private var inSpeech = false
    private var speechRun = 0
    private var silenceRun = 0
    private var segStart = 0L
    private val current = FloatArrayBuilder()
    private val preRoll = ArrayDeque<FloatArray>()
    private val done = ArrayList<VadSegment>()

    override fun accept(samples: FloatArray) {
        pending.add(samples)
        while (pending.size >= frameLen) {
            val frame = pending.take(frameLen)
            processFrame(frame)
            streamPos += frameLen
        }
    }

    private fun processFrame(frame: FloatArray) {
        var sum = 0.0
        for (v in frame) sum += v * v
        val rms = sqrt(sum / frame.size).toFloat()
        val speech = rms > maxOf(absoluteFloorRms, noise * snrFactor)
        if (!speech) noise = 0.95f * noise + 0.05f * rms // adapt to the room only on silence

        val padFrames = (paddingMs / frameMs).coerceAtLeast(1)
        if (!inSpeech) {
            preRoll.addLast(frame)
            while (preRoll.size > padFrames + minSpeechMs / frameMs) preRoll.removeFirst()
            if (speech) speechRun++ else speechRun = 0
            if (speechRun * frameMs >= minSpeechMs) {
                inSpeech = true
                silenceRun = 0
                segStart = streamPos + frameLen - preRoll.sumOf { it.size }
                preRoll.forEach { current.add(it) }
                preRoll.clear()
            }
        } else {
            current.add(frame)
            silenceRun = if (speech) 0 else silenceRun + 1
            val tooLong = current.size >= sampleRate.toLong() * maxSpeechMs / 1000
            if (silenceRun * frameMs >= minSilenceMs || tooLong) endSegment(trimTail = !tooLong)
        }
    }

    private fun endSegment(trimTail: Boolean) {
        val all = current.toArray()
        val keepTail = (paddingMs * sampleRate / 1000)
        val trimmed = if (trimTail) {
            val silenceSamples = silenceRun * frameLen
            val cut = (silenceSamples - keepTail).coerceAtLeast(0)
            all.copyOf((all.size - cut).coerceAtLeast(0))
        } else all
        if (trimmed.isNotEmpty()) done.add(VadSegment(segStart, trimmed, sampleRate))
        current.clear()
        inSpeech = false
        speechRun = 0
        silenceRun = 0
    }

    override fun drain(): List<VadSegment> {
        val out = done.toList()
        done.clear()
        return out
    }

    override fun flush() {
        if (inSpeech) endSegment(trimTail = true)
    }

    override fun reset() {
        pending.clear(); current.clear(); preRoll.clear(); done.clear()
        streamPos = 0; inSpeech = false; speechRun = 0; silenceRun = 0
    }
}

/** Minimal growable float buffer (no boxing). */
internal class FloatArrayBuilder {
    private var data = FloatArray(4096)
    var size = 0
        private set

    fun add(src: FloatArray) {
        if (size + src.size > data.size) data = data.copyOf(maxOf(data.size * 2, size + src.size))
        System.arraycopy(src, 0, data, size, src.size)
        size += src.size
    }

    /** Removes and returns the first [n] samples. */
    fun take(n: Int): FloatArray {
        val out = data.copyOfRange(0, n)
        System.arraycopy(data, n, data, 0, size - n)
        size -= n
        return out
    }

    fun toArray(): FloatArray = data.copyOf(size)
    fun clear() { size = 0 }
}
