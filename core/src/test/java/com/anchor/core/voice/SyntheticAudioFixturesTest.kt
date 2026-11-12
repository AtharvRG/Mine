// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class SyntheticAudioFixturesTest {

    @Test
    fun testGenerateTone_CorrectLengthAndAmplitude() {
        val samples = SyntheticAudioFixtures.generateTone(
            frequencyHz = 440f,
            durationSec = 0.5f,
            sampleRate = 16000,
            amplitude = 0.8f
        )
        assertEquals(8000, samples.size)
        val maxVal = samples.maxOrNull() ?: 0f
        val minVal = samples.minOrNull() ?: 0f
        assertTrue("Max should be close to 0.8", abs(maxVal - 0.8f) < 0.05f)
        assertTrue("Min should be close to -0.8", abs(minVal - (-0.8f)) < 0.05f)
        assertTrue(SyntheticAudioFixtures.isSpeechEnergy(samples, 0.02f))
    }

    @Test
    fun testGenerateSilence_HasZeroEnergy() {
        val samples = SyntheticAudioFixtures.generateSilence(1.0f, 16000)
        assertEquals(16000, samples.size)
        assertEquals(0.0f, SyntheticAudioFixtures.computeRmsEnergy(samples), 1e-6f)
        assertFalse(SyntheticAudioFixtures.isSpeechEnergy(samples, 0.01f))
    }

    @Test
    fun testGenerateSpeechLikeFormants_GeneratesVocalicEnergy() {
        val samples = SyntheticAudioFixtures.generateSpeechLikeFormants(
            durationSec = 2.0f,
            f0 = 130f
        )
        assertEquals(32000, samples.size)
        val rms = SyntheticAudioFixtures.computeRmsEnergy(samples)
        assertTrue("RMS energy should be noticeable: $rms", rms > 0.05f)
        assertTrue(SyntheticAudioFixtures.isSpeechEnergy(samples, 0.03f))
    }

    @Test
    fun testVoiceprintEmbeddings_DeterministicAndCosineSimilarity() {
        val userVoiceprint = SyntheticAudioFixtures.generateSyntheticVoiceprint(dimension = 192, seed = 12345L)
        val sameVoiceprint = SyntheticAudioFixtures.generateSyntheticVoiceprint(dimension = 192, seed = 12345L)
        val otherVoiceprint = SyntheticAudioFixtures.generateSyntheticVoiceprint(dimension = 192, seed = 99999L)

        // Exact match has similarity 1.0
        val selfSim = SyntheticAudioFixtures.cosineSimilarity(userVoiceprint, sameVoiceprint)
        assertEquals(1.0f, selfSim, 1e-4f)

        // Different seeds should have lower similarity
        val diffSim = SyntheticAudioFixtures.cosineSimilarity(userVoiceprint, otherVoiceprint)
        assertTrue("Different random voiceprints should have low similarity: $diffSim", diffSim < 0.5f)
    }

    @Test
    fun testExtractVoiceprintFromAudio_ZeroMeanSignedAndSimilarity() {
        val audio1 = SyntheticAudioFixtures.generateSpeechLikeFormants(durationSec = 2.0f, f0 = 120f)
        val audioSame = SyntheticAudioFixtures.generateSpeechLikeFormants(durationSec = 2.0f, f0 = 120f)
        val audioDiff = SyntheticAudioFixtures.generateSpeechLikeFormants(durationSec = 2.0f, f0 = 240f)

        val vp1 = SyntheticAudioFixtures.extractVoiceprintFromAudio(audio1)
        val vpSame = SyntheticAudioFixtures.extractVoiceprintFromAudio(audioSame)
        val vpDiff = SyntheticAudioFixtures.extractVoiceprintFromAudio(audioDiff)

        assertEquals(192, vp1.size)
        // Verify mean is approximately zero
        val mean = vp1.sum() / vp1.size
        assertEquals(0.0f, mean, 1e-4f)

        val simSame = SyntheticAudioFixtures.cosineSimilarity(vp1, vpSame)
        val simDiff = SyntheticAudioFixtures.cosineSimilarity(vp1, vpDiff)

        assertTrue("Same audio should have high similarity: $simSame", simSame > 0.99f)
        assertTrue("Different voice should have lower similarity than t_lo=0.60: $simDiff", simDiff < 0.60f)
    }
}
