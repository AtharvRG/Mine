// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.mine.voice

import android.content.Context
import com.anchor.core.ambient.EnergySegmenter
import com.anchor.core.ambient.SpeechSegmenter
import com.anchor.core.ambient.VoiceEnrollment
import com.anchor.core.ambient.Voiceprint
import com.anchor.core.storage.Storage
import com.anchor.core.voice.SyntheticAudioFixtures
import com.mine.companion.store.EnrollmentClipUi
import com.mine.companion.store.EnrollmentUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Teach Mine your voice": the user reads [VoiceEnrollment.RECOMMENDED_CLIPS] short sentences.
 * Each clip is recorded in RAM, its speech cut out by the VAD, turned into an x-vector by the
 * speaker model, and measured for level, noise and clipping. [VoiceEnrollment.assess] then says
 * whether the voiceprint is good enough and what to redo. Audio never touches disk; only the
 * final averaged embedding is stored (device-local, never exported).
 */
object VoiceEnrollmentSession {
    private class Clip(val metrics: VoiceEnrollment.ClipMetrics, val embedding: FloatArray?)

    private val _ui = MutableStateFlow(EnrollmentUi())
    val ui: StateFlow<EnrollmentUi> = _ui.asStateFlow()

    private val clips = arrayOfNulls<Clip>(VoiceEnrollment.RECOMMENDED_CLIPS)
    private var job: Job? = null
    @Volatile private var stopRequested = false
    private var lastResult: VoiceEnrollment.Result? = null
    private var neural = false

    fun reset() {
        job?.cancel()
        clips.fill(null)
        lastResult = null
        _ui.value = EnrollmentUi()
    }

    /** Records prompt [index]; stops by itself 1.3 s after the user finishes speaking (12 s cap). */
    fun record(context: Context, scope: CoroutineScope, index: Int) {
        if (job?.isActive == true) return
        stopRequested = false
        _ui.update { it.copy(phase = "recording", prompt = index, level = 0f, verdict = null, verdictTitle = null, advice = emptyList(), saved = false) }
        job = scope.launch(Dispatchers.IO) {
            val recorder = AudioRecorderHelper(maxDurationSeconds = 15, isForegroundUserMic = true)
            if (!recorder.startRecording(this)) {
                _ui.update { it.copy(phase = "idle", advice = listOf("The microphone is busy or not allowed.")) }
                return@launch
            }
            val started = System.currentTimeMillis()
            var lastLoud = 0L
            var loudMs = 0L
            try {
                while (isActive && !stopRequested) {
                    delay(100)
                    val rms = recorder.currentRms
                    _ui.update { it.copy(level = (rms * 8f).coerceIn(0f, 1f)) }
                    val now = System.currentTimeMillis()
                    if (rms > 0.02f) { lastLoud = now; loudMs += 100 }
                    val spokeEnough = loudMs >= 1_000
                    if (spokeEnough && now - lastLoud > 1_300) break
                    if (now - started > 12_000) break
                }
            } finally {
                val samples = recorder.stopRecording()
                analyseClip(context, index, samples)
            }
        }
    }

    fun stop() { stopRequested = true }

    private suspend fun analyseClip(context: Context, index: Int, samples: FloatArray) = withContext(Dispatchers.Default) {
        _ui.update { it.copy(phase = "analysing", level = 0f) }
        val engine = SherpaVoiceEngine.getInstance(context)
        neural = engine?.isSpeakerExtractorAvailable == true
        val seg: SpeechSegmenter = engine?.newSegmenter() ?: EnergySegmenter()
        val speech = try {
            seg.accept(samples)
            seg.flush()
            seg.drain()
        } finally {
            (seg as? java.io.Closeable)?.close()
        }
        val speechSamples = speech.fold(FloatArray(0)) { acc, s -> acc + s.samples }
        val speechMs = speechSamples.size * 1000L / 16_000L
        val metrics = VoiceEnrollment.measure(samples, 16_000, speechMs)
        val embedding = if (speechSamples.size >= 16_000) {
            runCatching { engine?.extractSpeakerEmbedding(speechSamples) }.getOrNull()
                ?: SyntheticAudioFixtures.extractVoiceprintFromAudio(speechSamples)
        } else null
        clips[index] = Clip(metrics, embedding)
        val issues = VoiceEnrollment.issuesOf(metrics)
        _ui.update { st ->
            val others = st.clips.filter { it.index != index }
            st.copy(
                phase = "idle",
                clips = (others + EnrollmentClipUi(index, issues.map { it.message }, issues.isEmpty(), null)).sortedBy { it.index },
                prompt = nextPrompt(index),
                neural = neural,
            )
        }
        if (clips.all { it != null }) assess()
    }

    private fun nextPrompt(after: Int): Int {
        for (i in clips.indices) if (clips[i] == null && i != after) return i
        return after
    }

    private fun assess() {
        val done = clips.filterNotNull()
        val r = VoiceEnrollment.assess(done.map { it.metrics }, done.map { it.embedding }, neural)
        lastResult = r
        _ui.update {
            it.copy(
                phase = "done",
                clips = r.clips.map { c -> EnrollmentClipUi(c.index, c.issues.map { i -> i.message }, c.ok, c.similarity) },
                verdict = r.verdict.name.lowercase(),
                verdictTitle = r.verdict.title,
                advice = r.advice,
                consistency = r.consistency,
                neural = neural,
            )
        }
    }

    /** Throws away one clip so it can be read again; the verdict waits for the new take. */
    fun redo(index: Int) {
        if (index !in clips.indices) return
        clips[index] = null
        lastResult = null
        _ui.update { st -> st.copy(phase = "idle", prompt = index, verdict = null, verdictTitle = null, clips = st.clips.filter { it.index != index }) }
    }

    /** Stores the voiceprint and the thresholds tuned to it. Returns false when there is nothing good to save. */
    suspend fun save(storage: Storage): Boolean {
        val r = lastResult ?: return false
        val print = r.voiceprint ?: return false
        val now = System.currentTimeMillis()
        storage.saveVoiceprint(Voiceprint.fromFloatArray("vp-local", print, if (neural) "campplus" else "dsp-fallback", now))
        storage.saveSetting("ambient.t_lo", "%.2f".format(java.util.Locale.ROOT, r.tLo))
        storage.saveSetting("ambient.t_hi", "%.2f".format(java.util.Locale.ROOT, r.tHi))
        storage.saveSetting("ambient.voiceprint_quality", r.verdict.name.lowercase())
        _ui.update { it.copy(saved = true) }
        return true
    }
}
