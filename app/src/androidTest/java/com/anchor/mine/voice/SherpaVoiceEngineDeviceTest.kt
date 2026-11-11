// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.mine.voice

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SherpaVoiceEngineDeviceTest {

    private lateinit var context: Context
    private var engine: SherpaVoiceEngine? = null

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        val modelsDir = context.filesDir.resolve("models")
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        try {
            automation.executeShellCommand("mkdir -p ${modelsDir.absolutePath}").close()
            automation.executeShellCommand("cp -rf /data/local/tmp/models/* ${modelsDir.absolutePath}/").close()
            automation.executeShellCommand("chmod -R 777 ${modelsDir.absolutePath}").close()
            Thread.sleep(1000)
        } catch (_: Exception) {}
    }

    @After
    fun tearDown() {
        engine?.close()
        engine = null
    }

    @Test
    fun testOfflineVoiceEngine_InitializesAndSynthesizesSpeech_AndTranscribes() {
        val paths = VoiceModelPaths.resolve(context)
        assertNotNull("Voice model paths must be resolvable from app storage or /data/local/tmp/models", paths)
        assertTrue("All model files must exist on device", paths!!.isComplete)

        engine = SherpaVoiceEngine.create(context, paths)
        assertNotNull("SherpaVoiceEngine must initialize successfully with valid model paths", engine)

        val voice = engine!!
        assertTrue("Kokoro TTS must be available", voice.isTtsAvailable)
        assertTrue("Zipformer STT must be available", voice.isSttAvailable)
        assertTrue("Silero VAD must be available", voice.isVadAvailable)

        // 1. Synthesize offline speech using Kokoro
        val testText = "Mine is running fully offline."
        val samples = voice.synthesize(testText, sid = 0, speed = 1.0f)
        assertNotNull(samples)
        assertTrue("Synthesized PCM samples must not be empty", samples.isNotEmpty())
        assertTrue("TTS sample rate must be 24000 Hz", voice.ttsSampleRate() == 24000)

        // 2. Transcribe the speech samples (resampled or fed directly to STT)
        // Verify transcription pipeline accepts float waveform and executes offline without crashing
        val transcript = voice.transcribe(samples, sampleRate = voice.ttsSampleRate())
        assertNotNull("Transcription result must not be null", transcript)
    }
}
