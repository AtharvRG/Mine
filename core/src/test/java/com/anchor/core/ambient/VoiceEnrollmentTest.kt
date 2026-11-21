// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.ambient

import com.anchor.core.voice.SyntheticAudioFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class VoiceEnrollmentTest {

    private val clean = VoiceEnrollment.ClipMetrics(durationMs = 4_000, speechMs = 3_500, speechDb = -20f, noiseDb = -55f, clippedRatio = 0f)

    /** Embeddings around one direction, with a little noise: one speaker, five takes. */
    private fun speaker(seed: Int, count: Int, spread: Float, dim: Int = 192): List<FloatArray> {
        val rnd = Random(seed)
        val base = FloatArray(dim) { rnd.nextFloat() - 0.5f }
        return List(count) { FloatArray(dim) { i -> base[i] + (rnd.nextFloat() - 0.5f) * spread } }
    }

    @Test fun fiveCleanConsistentClipsAreGood() {
        val r = VoiceEnrollment.assess(List(5) { clean }, speaker(1, 5, 0.3f), neuralModel = true)
        assertEquals(VoiceEnrollment.Verdict.GOOD, r.verdict)
        assertNotNull(r.voiceprint)
        assertEquals(1.0, r.voiceprint!!.sumOf { (it * it).toDouble() }, 1e-3) // unit length
        assertTrue(r.tLo in 0.45f..0.70f)
        assertTrue(r.tHi > r.tLo)
    }

    @Test fun noisyAndQuietClipsAreNamedAndExcluded() {
        val noisy = clean.copy(noiseDb = -28f)          // SNR 8 dB
        val quiet = clean.copy(speechDb = -45f, noiseDb = -60f)
        val r = VoiceEnrollment.assess(listOf(clean, noisy, quiet, clean, clean), speaker(2, 5, 0.3f), neuralModel = true)
        assertEquals(listOf(VoiceEnrollment.Issue.NOISY), r.clips[1].issues)
        assertEquals(listOf(VoiceEnrollment.Issue.TOO_QUIET), r.clips[2].issues)
        assertTrue(r.advice.first().contains("quiet") || r.advice.any { it.contains("background noise") })
    }

    @Test fun someoneElsesClipIsCaught() {
        val mine = speaker(3, 4, 0.25f)
        val theirs = speaker(99, 1, 0.25f)
        val r = VoiceEnrollment.assess(List(5) { clean }, mine + theirs, neuralModel = true)
        assertTrue(VoiceEnrollment.Issue.NOT_LIKE_OTHERS in r.clips[4].issues)
        assertEquals(VoiceEnrollment.Verdict.GOOD, r.verdict) // the four that agree still make a good print
    }

    @Test fun tooFewCleanClipsArePoorAndStoreNothing() {
        val r = VoiceEnrollment.assess(listOf(clean, clean.copy(speechMs = 900)), speaker(4, 2, 0.3f), neuralModel = true)
        assertEquals(VoiceEnrollment.Verdict.POOR, r.verdict)
        assertNull(r.voiceprint)
        assertTrue(r.advice.any { it.contains("at least 3") })
    }

    @Test fun withoutTheSpeakerModelTheBestIsOkay() {
        val r = VoiceEnrollment.assess(List(5) { clean }, speaker(5, 5, 0.05f), neuralModel = false)
        assertEquals(VoiceEnrollment.Verdict.OKAY, r.verdict)
        assertTrue(r.advice.any { it.contains("speaker model") })
    }

    @Test fun measureReadsLevelsFromAudio() {
        val sr = 16_000
        val talk = SyntheticAudioFixtures.generateTone(200f, 3f, amplitude = 0.2f)
        val gaps = SyntheticAudioFixtures.generateWhiteNoise(1f, amplitude = 0.002f)
        val m = VoiceEnrollment.measure(gaps + talk + gaps, sr)
        assertTrue("speech is loud enough, was ${m.speechDb}", m.speechDb > -25f)
        assertTrue("noise floor is low, was ${m.noiseDb}", m.noiseDb < -50f)
        assertTrue(m.snrDb > 25f)
        val clipped = VoiceEnrollment.measure(FloatArray(sr * 3) { if (it % 2 == 0) 1f else -1f }, sr)
        assertTrue(VoiceEnrollment.Issue.CLIPPING in VoiceEnrollment.issuesOf(clipped))
    }
}
