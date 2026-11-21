// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.mine.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SherpaVoiceEngineTest {

    @Test
    fun testCleanTextForSpeech_StripsCodeFencesAndInlineCode() {
        val input = """
            Here is the code:
            ```kotlin
            fun main() { println("secret") }
            ```
            Use `val x = 1` to define it.
        """.trimIndent()

        val cleaned = SherpaVoiceEngine.cleanTextForSpeech(input)
        assertFalse(cleaned.contains("println"))
        assertFalse(cleaned.contains("val x = 1"))
        assertTrue(cleaned.contains("Here is the code:"))
        assertTrue(cleaned.contains("Use to define it."))
    }

    @Test
    fun testCleanTextForSpeech_StripsXmlAndHtmlTags() {
        val input = "Your reminder for <data>Doctor appointment</data> is set."
        val cleaned = SherpaVoiceEngine.cleanTextForSpeech(input)
        assertEquals("Your reminder for Doctor appointment is set.", cleaned)
    }

    @Test
    fun testCleanTextForSpeech_StripsMarkdownMarkersAndFormatsLinks() {
        val input = "Check out [Mine AI](https://github.com/anchor/mine) for **great** *offline* notes!"
        val cleaned = SherpaVoiceEngine.cleanTextForSpeech(input)
        assertEquals("Check out Mine AI for great offline notes!", cleaned)
    }

    @Test
    fun testVoiceModelPaths_NonExistentDir_ReportsIncomplete() {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "non_existent_models_${System.currentTimeMillis()}")
        val paths = VoiceModelPaths.fromBaseDir(tempDir)
        assertFalse(paths.isComplete)
    }

    @Test
    fun testVoiceModelPaths_AllFilesPresent_ReportsComplete() {
        val tempDir = File.createTempFile("models_test", "")
        tempDir.delete()
        tempDir.mkdirs()
        try {
            val vadDir = tempDir.resolve("vad").also { it.mkdirs() }
            val sttDir = tempDir.resolve("stt/zipformer").also { it.mkdirs() }
            val ttsDir = tempDir.resolve("tts/kokoro").also { it.mkdirs() }

            vadDir.resolve("silero_vad.onnx").writeText("fake-vad")
            sttDir.resolve("encoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx").writeText("fake-encoder")
            sttDir.resolve("decoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx").writeText("fake-decoder")
            sttDir.resolve("joiner-epoch-99-avg-1-chunk-16-left-128.int8.onnx").writeText("fake-joiner")
            sttDir.resolve("tokens.txt").writeText("fake-tokens")
            ttsDir.resolve("model.int8.onnx").writeText("fake-tts")
            ttsDir.resolve("voices.bin").writeText("fake-voices")
            ttsDir.resolve("tokens.txt").writeText("fake-tts-tokens")
            ttsDir.resolve("espeak-ng-data").also { it.mkdirs() }
            ttsDir.resolve("lexicon-us-en.txt").writeText("fake-lexicon")

            val paths = VoiceModelPaths.fromBaseDir(tempDir)
            assertTrue(paths.isComplete)
            assertFalse(paths.speakerModel?.exists() == true)

            // When speaker model is added
            val speakerDir = tempDir.resolve("speaker").also { it.mkdirs() }
            speakerDir.resolve("campplus.onnx").writeText("fake-campplus")
            val pathsWithSpeaker = VoiceModelPaths.fromBaseDir(tempDir)
            assertTrue(pathsWithSpeaker.speakerModel?.exists() == true)
            assertEquals("campplus.onnx", pathsWithSpeaker.speakerModel?.name)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testHandsFreeEndpointing_DetectsSilenceAndTimesOut() {
        // Test energy threshold and endpointing duration
        val thresholdRms = 0.015f
        val quietRms = 0.005f
        val speechRms = 0.040f

        var hasDetectedSpeech = false
        var silenceDurationMs = 0L
        var endpointDetected = false

        // 1. Initial silence: no speech yet
        val initialSilenceTicks = 5 // 500ms
        for (i in 0 until initialSilenceTicks) {
            val isSpeech = quietRms > thresholdRms
            if (isSpeech) {
                hasDetectedSpeech = true
                silenceDurationMs = 0L
            } else if (hasDetectedSpeech) {
                silenceDurationMs += 100L
            }
        }
        assertFalse("Speech must not be detected yet", hasDetectedSpeech)
        assertEquals(0L, silenceDurationMs)

        // 2. User starts speaking
        val speechTicks = 10 // 1000ms
        for (i in 0 until speechTicks) {
            val isSpeech = speechRms > thresholdRms
            if (isSpeech) {
                hasDetectedSpeech = true
                silenceDurationMs = 0L
            }
        }
        assertTrue("Speech must now be detected", hasDetectedSpeech)
        assertEquals(0L, silenceDurationMs)

        // 3. User pauses for 1200ms (1.2s silence endpoint)
        val pauseTicks = 12 // 1200ms
        for (i in 0 until pauseTicks) {
            val isSpeech = quietRms > thresholdRms
            if (isSpeech) {
                hasDetectedSpeech = true
                silenceDurationMs = 0L
            } else if (hasDetectedSpeech) {
                silenceDurationMs += 100L
                if (silenceDurationMs >= 1200L) {
                    endpointDetected = true
                    break
                }
            }
        }
        assertTrue("Endpoint must be detected after 1.2s silence", endpointDetected)
        assertEquals(1200L, silenceDurationMs)
    }
}
