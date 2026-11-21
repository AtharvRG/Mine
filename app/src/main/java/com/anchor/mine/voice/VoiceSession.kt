// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.mine.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.anchor.core.ui.ChatController
import com.anchor.core.ui.UiAction
import com.anchor.core.voice.HandsFreeState
import com.anchor.core.voice.HandsFreeStateMachine
import com.anchor.core.voice.VoiceSpeechSanitizer
import com.anchor.mine.ambient.AmbientForegroundService
import com.anchor.mine.notifications.NotificationReader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.max

/**
 * The hands-free conversation loop: listen → transcribe → send → speak the answer → listen.
 *
 * It used to live inside a Composable, where recomposition, rotation or leaving the screen
 * could leave the microphone open or the loop half-way. Here it is a single coroutine owned by
 * the process, with one entry ([start]) and one exit ([stop]); every path out releases the mic,
 * stops speech and tells the engine the session ended.
 *
 * Speech-to-text prefers the offline Sherpa model and falls back to Android's speech recogniser,
 * so voice works on any device that has either.
 */
object VoiceSession {

    enum class Phase { IDLE, LISTENING, THINKING, SPEAKING, PAUSED }

    data class Ui(
        val phase: Phase = Phase.IDLE,
        /** What Mine heard the user say this turn (live while listening, when the recogniser streams). */
        val heard: String = "",
        /** What Mine is saying (or said) back. */
        val reply: String = "",
        /** Mic level 0..1 for the waveform; 0 when not listening. */
        val level: Float = 0f,
        /** A plain-language problem the user can act on, e.g. no speech engine. */
        val problem: String? = null,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loop: Job? = null
    private var appContext: Context? = null
    private var controller: ChatController? = null

    private val _ui = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _ui.asStateFlow()

    private val level = java.util.concurrent.atomic.AtomicReference(0f)
    /** Read every frame by the waveform; cheap and lock-free. */
    fun currentLevel(): Float = level.get()

    private const val SILENCE_END_MS = 1_100L
    private const val IDLE_END_MS = 15_000L
    private const val MAX_UTTERANCE_MS = 60_000L
    private const val REPLY_TIMEOUT_MS = 90_000L
    private const val MAX_MISSES = 3

    fun hasMicPermission(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Starts (or resumes) the session. Call only after the RECORD_AUDIO permission is granted. */
    fun start(context: Context, chat: ChatController) {
        appContext = context.applicationContext
        controller = chat
        if (loop?.isActive == true) {
            if (_ui.value.phase == Phase.PAUSED) resume()
            return
        }
        if (!hasMicPermission(context)) {
            _ui.value = Ui(problem = "Mine needs microphone access for voice. Allow it and try again.")
            return
        }
        chat.onAction(UiAction.SetHandsFree(true))
        NotificationReader.setConversationBusy(true, context)
        _ui.value = Ui(phase = Phase.LISTENING)
        loop = scope.launch { runLoop() }
    }

    fun pause() {
        if (loop?.isActive != true) return
        Speaker.stopAll()
        _ui.update { it.copy(phase = Phase.PAUSED, level = 0f) }
        level.set(0f)
    }

    fun resume() {
        if (_ui.value.phase == Phase.PAUSED) _ui.update { it.copy(phase = Phase.LISTENING, problem = null) }
    }

    /** Ends the session from any state. Safe to call repeatedly. */
    fun stop() {
        loop?.cancel()
        loop = null
        Speaker.stopAll()
        level.set(0f)
        _ui.value = Ui()
        controller?.onAction(UiAction.SetHandsFree(false))
        appContext?.let {
            NotificationReader.setConversationBusy(false, it)
            AmbientForegroundService.refresh(it)
        }
    }

    /** Reads one chat message aloud (the speaker button under a reply). */
    fun speakMessage(context: Context, id: String, text: String) {
        val spoken = VoiceSpeechSanitizer.chunkSentences(text).joinToString(" ").ifBlank { text }
        if (Speaker.speakingId.value == id) {
            Speaker.stopAll()
            return
        }
        Speaker.stopAll()
        Speaker.enqueue(context, spoken, Speaker.Kind.CONVERSATION, id = id)
    }

    // ---------------------------------------------------------------------------------------

    private suspend fun runLoop() {
        val ctx = appContext ?: return
        val chat = controller ?: return
        var misses = 0
        try {
            while (scope.isActive) {
                // Paused: wait until resumed or stopped.
                if (_ui.value.phase == Phase.PAUSED) {
                    ui.first { it.phase != Phase.PAUSED }
                    continue
                }
                _ui.update { it.copy(phase = Phase.LISTENING, heard = "", problem = null) }
                chat.onAction(UiAction.UpdateHandsFreeState(HandsFreeState.LISTENING))

                val heard = listenOnce(ctx) ?: break // null = idle timeout or fatal problem
                if (_ui.value.phase == Phase.PAUSED) continue
                if (heard.isBlank()) {
                    misses++
                    if (misses >= MAX_MISSES) break
                    continue
                }
                misses = 0
                if (HandsFreeStateMachine.isStopWord(heard)) break

                _ui.update { it.copy(phase = Phase.THINKING, heard = heard, reply = "", level = 0f) }
                level.set(0f)
                chat.onAction(UiAction.UpdateHandsFreeState(HandsFreeState.PROCESSING))

                val before = chat.state.value.chatMessages.lastOrNull()?.id
                chat.onAction(UiAction.SendText(heard))
                val reply = awaitReply(chat, before)

                _ui.update { it.copy(phase = Phase.SPEAKING, reply = reply) }
                chat.onAction(UiAction.UpdateHandsFreeState(HandsFreeState.SPEAKING))
                Speaker.speak(ctx, reply, Speaker.Kind.CONVERSATION, id = "voice-reply")
                delay(250)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            _ui.update { it.copy(problem = "Voice stopped unexpectedly. Tap the orb to try again.") }
        } finally {
            withContext(kotlinx.coroutines.NonCancellable) {
                level.set(0f)
                if (_ui.value.problem == null) _ui.value = Ui() else _ui.update { it.copy(phase = Phase.IDLE, level = 0f) }
                chat.onAction(UiAction.SetHandsFree(false))
                NotificationReader.setConversationBusy(false, ctx)
                AmbientForegroundService.refresh(ctx)
            }
            loop = null
        }
    }

    /** Waits for the turn started after [beforeId] to finish and returns what should be spoken. */
    private suspend fun awaitReply(chat: ChatController, beforeId: String?): String {
        // Let the turn start before waiting for it to end.
        withTimeoutOrNull(3_000) { chat.state.first { it.isChatLoading } }
        val done = withTimeoutOrNull(REPLY_TIMEOUT_MS) { chat.state.first { !it.isChatLoading } } ?: return "Sorry, that took too long. Let's try again."
        val msgs = done.chatMessages
        val startIdx = msgs.indexOfFirst { it.id == beforeId }.let { if (it < 0) 0 else it + 1 }
        val fresh = msgs.drop(startIdx)
        val lastReply = fresh.lastOrNull { it.role == "assistant" && !it.content.isNullOrBlank() }?.content
        return when {
            lastReply != null -> lastReply
            done.openProposals.isNotEmpty() -> "I need you to pick one option. It's on your screen."
            done.receipts.isNotEmpty() -> done.receipts.last().message
            done.connectionError != null -> done.connectionError!!
            else -> "Done."
        }
    }

    /**
     * Records one utterance and returns its transcript: "" when nothing intelligible was said,
     * null when the session should end (long silence or no speech engine).
     */
    private suspend fun listenOnce(ctx: Context): String? {
        val sherpa = SherpaVoiceEngine.getInstance(ctx)?.takeIf { it.isSttAvailable }
        if (sherpa != null) return listenWithSherpa(ctx, sherpa)
        if (SpeechRecognizer.isRecognitionAvailable(ctx)) return listenWithSystem(ctx)
        _ui.update { it.copy(problem = "No speech recognition is available on this phone. Install the offline voice model in Settings → Voice.") }
        return null
    }

    private suspend fun listenWithSherpa(ctx: Context, engine: SherpaVoiceEngine): String? {
        val recorder = AudioRecorderHelper(isForegroundUserMic = true)
        if (!recorder.startRecording(scope)) {
            _ui.update { it.copy(problem = "The microphone is busy. Close other apps using it and try again.") }
            return null
        }
        AmbientForegroundService.refresh(ctx) // ambient yields the mic right away
        try {
            var noiseFloor = 0.006f
            var heardSpeech = false
            var silentMs = 0L
            var elapsed = 0L
            while (true) {
                delay(80)
                elapsed += 80
                if (_ui.value.phase == Phase.PAUSED) {
                    recorder.cancelRecording()
                    return ""
                }
                val rms = recorder.currentRms
                level.set((rms * 14f).coerceIn(0f, 1f))
                // Adaptive threshold: a noisy café should not count as speech forever.
                if (!heardSpeech) noiseFloor = noiseFloor * 0.96f + rms * 0.04f
                val threshold = max(0.012f, noiseFloor * 2.6f)
                if (rms > threshold) {
                    heardSpeech = true
                    silentMs = 0
                } else if (heardSpeech) {
                    silentMs += 80
                    if (silentMs >= SILENCE_END_MS) break
                } else if (elapsed >= IDLE_END_MS) {
                    recorder.cancelRecording()
                    return null
                }
                if (elapsed >= MAX_UTTERANCE_MS) break
            }
            val samples = recorder.stopRecording()
            level.set(0f)
            _ui.update { it.copy(phase = Phase.THINKING) }
            return runCatching { engine.transcribe(samples).trim() }.getOrDefault("")
        } finally {
            recorder.cancelRecording()
            level.set(0f)
        }
    }

    /** Android's recogniser must run on the main thread; results stream into [Ui.heard]. */
    private suspend fun listenWithSystem(ctx: Context): String? = withContext(Dispatchers.Main) {
        val result = CompletableDeferred<String?>()
        val recognizer = SpeechRecognizer.createSpeechRecognizer(ctx)
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) { level.set(((rmsdB + 2f) / 12f).coerceIn(0f, 1f)) }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { _ui.update { it.copy(phase = Phase.THINKING) } }
            override fun onError(error: Int) {
                when (error) {
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> result.complete(null)
                    SpeechRecognizer.ERROR_NO_MATCH -> result.complete("")
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                        _ui.update { it.copy(problem = "Mine needs microphone access for voice.") }
                        result.complete(null)
                    }
                    else -> result.complete("")
                }
            }
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                result.complete(text)
            }
            override fun onPartialResults(partial: Bundle?) {
                val text = partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!text.isNullOrBlank()) _ui.update { it.copy(heard = text) }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, SILENCE_END_MS)
        try {
            recognizer.startListening(intent)
            withTimeoutOrNull(MAX_UTTERANCE_MS + 5_000) { result.await() } ?: ""
        } finally {
            runCatching { recognizer.cancel() }
            runCatching { recognizer.destroy() }
            level.set(0f)
        }
    }
}
