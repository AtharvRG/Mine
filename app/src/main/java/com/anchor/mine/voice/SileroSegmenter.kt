// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.mine.voice

import com.anchor.core.ambient.SpeechSegmenter
import com.anchor.core.ambient.VadSegment
import com.k2fsa.sherpa.onnx.Vad
import java.io.Closeable

/**
 * [SpeechSegmenter] over sherpa-onnx's Silero VAD. Audio goes in as it arrives; Silero emits
 * whole utterances (speech start to 0.5 s of silence, capped at 20 s), so words are never cut at
 * an arbitrary slice boundary the way fixed 3-second slices cut them.
 *
 * One instance per stream, owned by one thread. Not shared with [SherpaVoiceEngine.isSpeech].
 */
class SileroSegmenter(private val vad: Vad, override val sampleRate: Int = 16_000) : SpeechSegmenter, Closeable {
    private val window = 512 // Silero's frame at 16 kHz
    private var pending = FloatArray(window * 8)
    private var pendingSize = 0
    private val done = ArrayList<VadSegment>()

    override fun accept(samples: FloatArray) {
        if (pendingSize + samples.size > pending.size) pending = pending.copyOf(maxOf(pending.size * 2, pendingSize + samples.size))
        System.arraycopy(samples, 0, pending, pendingSize, samples.size)
        pendingSize += samples.size
        var off = 0
        while (pendingSize - off >= window) {
            vad.acceptWaveform(pending.copyOfRange(off, off + window))
            off += window
            collect()
        }
        if (off > 0) {
            System.arraycopy(pending, off, pending, 0, pendingSize - off)
            pendingSize -= off
        }
    }

    private fun collect() {
        while (!vad.empty()) {
            val s = vad.front()
            done += VadSegment(s.start.toLong(), s.samples.copyOf(), sampleRate)
            vad.pop()
        }
    }

    override fun drain(): List<VadSegment> {
        val out = done.toList()
        done.clear()
        return out
    }

    override fun flush() {
        vad.flush()
        collect()
    }

    override fun reset() {
        vad.reset()
        pendingSize = 0
        done.clear()
    }

    override fun close() {
        try { vad.release() } catch (_: Throwable) {}
    }
}
