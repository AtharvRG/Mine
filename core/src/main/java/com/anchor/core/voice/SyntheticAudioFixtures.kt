// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.voice

import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt
import java.util.Random

/**
 * Deterministic synthetic audio fixtures and acoustic math utilities.
 * Allows testing speech recognition, voice activity detection (VAD), speaker
 * verification, and presence gating without requiring physical human speech.
 * Pure Kotlin, zero Android framework dependencies.
 */
object SyntheticAudioFixtures {

    const val DEFAULT_SAMPLE_RATE = 16000 // 16 kHz standard for Sherpa-ONNX & Silero VAD

    /**
     * Generates a pure sinusoidal tone at the given frequency.
     */
    fun generateTone(
        frequencyHz: Float,
        durationSec: Float,
        sampleRate: Int = DEFAULT_SAMPLE_RATE,
        amplitude: Float = 0.5f
    ): FloatArray {
        val totalSamples = (durationSec * sampleRate).toInt().coerceAtLeast(1)
        val result = FloatArray(totalSamples)
        val angularFrequency = 2.0 * PI * frequencyHz / sampleRate
        for (i in 0 until totalSamples) {
            result[i] = (amplitude * sin(angularFrequency * i)).toFloat()
        }
        return result
    }

    /**
     * Generates a block of digital silence (zeroes).
     */
    fun generateSilence(
        durationSec: Float,
        sampleRate: Int = DEFAULT_SAMPLE_RATE
    ): FloatArray {
        val totalSamples = (durationSec * sampleRate).toInt().coerceAtLeast(1)
        return FloatArray(totalSamples)
    }

    /**
     * Generates deterministic pseudorandom white noise.
     */
    fun generateWhiteNoise(
        durationSec: Float,
        sampleRate: Int = DEFAULT_SAMPLE_RATE,
        amplitude: Float = 0.05f,
        seed: Long = 42L
    ): FloatArray {
        val totalSamples = (durationSec * sampleRate).toInt().coerceAtLeast(1)
        val result = FloatArray(totalSamples)
        val random = Random(seed)
        for (i in 0 until totalSamples) {
            val sample = (random.nextFloat() * 2.0f - 1.0f) * amplitude
            result[i] = sample
        }
        return result
    }

    /**
     * Generates speech-like synthetic audio by combining a voiced fundamental frequency (F0)
     * with harmonic formant resonances and periodic syllabic amplitude modulation (~3-4 Hz).
     * This reliably triggers energy-based and neural VAD speech detectors.
     */
    fun generateSpeechLikeFormants(
        durationSec: Float,
        f0: Float = 130.0f, // Typical human pitch fundamental
        formants: List<Float> = listOf(700.0f, 1220.0f, 2600.0f), // Vowel /a/ formants
        sampleRate: Int = DEFAULT_SAMPLE_RATE,
        amplitude: Float = 0.4f
    ): FloatArray {
        val totalSamples = (durationSec * sampleRate).toInt().coerceAtLeast(1)
        val result = FloatArray(totalSamples)
        val twoPi = 2.0 * PI

        for (i in 0 until totalSamples) {
            val t = i.toDouble() / sampleRate

            // Syllabic envelope modulation at 3.5 Hz (natural speaking rate ~3.5 syllables/sec)
            val envelope = (0.5 + 0.5 * sin(twoPi * 3.5 * t)).coerceIn(0.1, 1.0)

            // Fundamental voice pulse
            var sample = 0.5 * sin(twoPi * f0 * t)

            // Add formants with decaying harmonics
            var weight = 0.3
            for (f in formants) {
                sample += weight * sin(twoPi * f * t)
                weight *= 0.6
            }

            result[i] = (sample * envelope * amplitude).toFloat().coerceIn(-1.0f, 1.0f)
        }
        return result
    }

    /**
     * Generates a deterministic L2-normalized synthetic speaker embedding vector.
     */
    fun generateSyntheticVoiceprint(
        dimension: Int = 192,
        seed: Long = 1001L
    ): FloatArray {
        val random = Random(seed)
        val raw = FloatArray(dimension) { random.nextFloat() * 2.0f - 1.0f }
        return normalizeL2(raw)
    }

    /**
     * Computes the Root Mean Square (RMS) energy of an audio buffer.
     */
    fun computeRmsEnergy(samples: FloatArray): Float {
        if (samples.isEmpty()) return 0.0f
        var sumSquares = 0.0
        for (s in samples) {
            sumSquares += s * s
        }
        return sqrt(sumSquares / samples.size).toFloat()
    }

    /**
     * Evaluates whether an audio buffer contains speech-level energy.
     */
    fun isSpeechEnergy(samples: FloatArray, thresholdRms: Float = 0.02f): Boolean {
        return computeRmsEnergy(samples) >= thresholdRms
    }

    /**
     * Computes the cosine similarity between two float vectors.
     */
    fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size || a.isEmpty()) return 0.0f
        var dot = 0.0f
        var normA = 0.0f
        var normB = 0.0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        val denom = sqrt(normA) * sqrt(normB)
        return if (denom > 1e-9f) (dot / denom).coerceIn(-1.0f, 1.0f) else 0.0f
    }

    /**
     * L2 normalizes a vector in-place or returns a normalized copy.
     */
    fun normalizeL2(vector: FloatArray): FloatArray {
        var sumSquares = 0.0f
        for (v in vector) sumSquares += v * v
        val norm = sqrt(sumSquares)
        if (norm <= 1e-9f) return vector.copyOf()
        val normalized = FloatArray(vector.size)
        for (i in vector.indices) {
            normalized[i] = vector[i] / norm
        }
        return normalized
    }
}
