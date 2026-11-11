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
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
