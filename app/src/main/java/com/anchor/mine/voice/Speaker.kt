// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.mine.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.anchor.core.voice.VoiceSpeechSanitizer
import com.anchor.mine.ambient.AmbientStatusBus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * The one voice Mine speaks with.
 *
 * Every spoken line — a notification, a reply, a hands-free turn — goes through this queue, so
 * two things never talk over each other. Lines are spoken in order; a conversation line
 * ([Kind.CONVERSATION]) pre-empts queued notification lines, never the reverse.
 *
 * Output uses the on-device Kokoro voice when its model is installed, and Android's TextToSpeech
 * otherwise. Notifications duck other audio for a moment instead of pausing it; replies take
 * transient focus. While speaking, [AmbientStatusBus.markSpeaking] tells ambient listening to
 * drop what its microphone hears, so Mine never transcribes itself.
 */
object Speaker {

    enum class Kind { CONVERSATION, NOTIFICATION }

    private data class Item(val id: String, val text: String, val kind: Kind, val done: CompletableDeferred<Boolean>, val generation: Long)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val queue = Channel<Item>(Channel.UNLIMITED)
    private val generation = AtomicLong(0)
    private val notificationGeneration = AtomicLong(0)
    private var worker: Job? = null

    private val _speaking = MutableStateFlow(false)
    /** True while a line is being spoken. */
    val speaking: StateFlow<Boolean> = _speaking.asStateFlow()

    private val _speakingId = MutableStateFlow<String?>(null)
    /** Caller-supplied id of the line being spoken (e.g. a chat message id), for "Stop" UI. */
    val speakingId: StateFlow<String?> = _speakingId.asStateFlow()

    @Volatile private var appContext: Context? = null
    @Volatile private var tts: TextToSpeech? = null
    @Volatile private var ttsReady = CompletableDeferred<Boolean>()
    private val ttsLock = Any()
    @Volatile private var currentUtterance: CompletableDeferred<Unit>? = null

    fun init(context: Context) {
        if (appContext == null) appContext = context.applicationContext
        synchronized(this) {
            if (worker == null) worker = scope.launch { runQueue() }
        }
    }

    /**
     * Queues [text]. Returns when it has been spoken (true), skipped or interrupted (false).
     * [id] identifies the line for [speakingId]; pass a chat message id to drive a stop button.
     */
    suspend fun speak(context: Context, text: String, kind: Kind = Kind.CONVERSATION, id: String? = null): Boolean {
        init(context)
        val clean = text.trim()
        if (clean.isEmpty()) return false
        if (kind == Kind.CONVERSATION) {
            // A reply matters more than any notification read-out, queued or in progress.
            notificationGeneration.incrementAndGet()
            if (_speakingKind == Kind.NOTIFICATION) interruptCurrent()
        }
        val item = Item(id ?: UUID.randomUUID().toString(), clean, kind, CompletableDeferred(), generationFor(kind))
        queue.send(item)
        return item.done.await()
    }

    /** Fire-and-forget variant for callers without a coroutine. */
    fun enqueue(context: Context, text: String, kind: Kind = Kind.NOTIFICATION, id: String? = null) {
        scope.launch { speak(context, text, kind, id) }
    }

    /** Stops the current line and drops everything queued. */
    fun stopAll() {
        generation.incrementAndGet()
        notificationGeneration.incrementAndGet()
        interruptCurrent()
    }

    /** Stops notification read-outs only (a call started, the user opened voice mode, …). */
    fun stopNotifications() {
        notificationGeneration.incrementAndGet()
        if (_speakingKind == Kind.NOTIFICATION) interruptCurrent()
    }

    @Volatile private var _speakingKind: Kind? = null

    private fun generationFor(kind: Kind): Long =
        if (kind == Kind.NOTIFICATION) (generation.get() shl 20) xor notificationGeneration.get() else generation.get() shl 20

    private fun isStale(item: Item): Boolean = item.generation != generationFor(item.kind)

    private fun interruptCurrent() {
        runCatching { SherpaVoiceEngine.getInstance(appContext ?: return@runCatching)?.stopPlaying() }
        synchronized(ttsLock) { runCatching { tts?.stop() } }
        currentUtterance?.complete(Unit)
        AmbientStatusBus.clearSpeaking()
    }

    private suspend fun runQueue() {
        for (item in queue) {
            if (isStale(item)) {
                item.done.complete(false)
                continue
            }
            val ctx = appContext
            if (ctx == null) {
                item.done.complete(false)
                continue
            }
            _speaking.value = true
            _speakingId.value = item.id
            _speakingKind = item.kind
            val ok = try {
                speakNow(ctx, item)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                false
            } finally {
                AmbientStatusBus.clearSpeaking()
                _speakingKind = null
            }
            item.done.complete(ok && !isStale(item))
            if (queue.isEmpty) {
                _speaking.value = false
                _speakingId.value = null
            }
        }
    }

    private suspend fun speakNow(ctx: Context, item: Item): Boolean {
        val chunks = VoiceSpeechSanitizer.chunkSentences(item.text).ifEmpty { listOf(item.text) }
        val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val focus = requestFocus(audio, item.kind)
        try {
            val sherpa = SherpaVoiceEngine.getInstance(ctx)?.takeIf { it.isTtsAvailable && !hasNonLatinScript(item.text) }
            for (chunk in chunks) {
                if (isStale(item)) return false
                AmbientStatusBus.markSpeaking(estimateMs(chunk))
                if (sherpa != null) {
                    val samples = sherpa.synthesize(chunk)
                    if (samples.isEmpty()) {
                        if (!speakWithAndroid(ctx, chunk, item.kind)) return false
                    } else {
                        sherpa.playBlocking(samples)
                    }
                } else {
                    if (!speakWithAndroid(ctx, chunk, item.kind)) return false
                }
            }
            return !isStale(item)
        } finally {
            abandonFocus(audio, focus)
        }
    }

    // ---------------------------------------------------------------------------------------
    // Android TextToSpeech
    // ---------------------------------------------------------------------------------------

    private suspend fun ensureTts(ctx: Context): TextToSpeech? {
        synchronized(ttsLock) {
            if (tts == null) {
                ttsReady = CompletableDeferred()
                val ready = ttsReady
                tts = TextToSpeech(ctx) { status -> ready.complete(status == TextToSpeech.SUCCESS) }
            }
        }
        val ok = withTimeoutOrNull(4_000) { ttsReady.await() } ?: false
        if (!ok) {
            synchronized(ttsLock) {
                runCatching { tts?.shutdown() }
                tts = null
            }
            return null
        }
        return tts
    }

    private suspend fun speakWithAndroid(ctx: Context, text: String, kind: Kind): Boolean {
        val engine = ensureTts(ctx) ?: return false
        val utteranceId = UUID.randomUUID().toString()
        val done = CompletableDeferred<Unit>()
        currentUtterance = done
        synchronized(ttsLock) {
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) { if (id == utteranceId) done.complete(Unit) }
                @Deprecated("Deprecated in Java")
                override fun onError(id: String?) { if (id == utteranceId) done.complete(Unit) }
                override fun onError(id: String?, errorCode: Int) { if (id == utteranceId) done.complete(Unit) }
                override fun onStop(id: String?, interrupted: Boolean) { if (id == utteranceId) done.complete(Unit) }
            })
            engine.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(if (kind == Kind.NOTIFICATION) AudioAttributes.USAGE_ASSISTANCE_SONIFICATION else AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            val locale = localeFor(text)
            if (engine.isLanguageAvailable(locale) >= TextToSpeech.LANG_AVAILABLE) engine.language = locale
            val params = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1f) }
            val r = engine.speak(text, TextToSpeech.QUEUE_ADD, params, utteranceId)
            if (r != TextToSpeech.SUCCESS) return false
        }
        // A missing onDone callback (engine crash, OEM bug) must not wedge the queue forever.
        withTimeoutOrNull(estimateMs(text) * 3 + 5_000) { done.await() }
        currentUtterance = null
        return true
    }

    private fun localeFor(text: String): Locale = when {
        text.any { it in 'ऀ'..'ॿ' } -> Locale("hi", "IN")
        text.any { it in '؀'..'ۿ' } -> Locale("ar")
        text.any { it in 'ঀ'..'৿' } -> Locale("bn", "IN")
        text.any { it in '஀'..'௿' } -> Locale("ta", "IN")
        else -> Locale.getDefault()
    }

    /** Kokoro's bundled voice is English-only; other scripts go to the system engine. */
    private fun hasNonLatinScript(text: String): Boolean =
        text.any { Character.UnicodeScript.of(it.code).let { s -> s != Character.UnicodeScript.LATIN && s != Character.UnicodeScript.COMMON && s != Character.UnicodeScript.INHERITED } }

    /** ~14 characters per second of speech, plus a little lead-in. */
    private fun estimateMs(text: String): Long = 400L + text.length * 72L

    // ---------------------------------------------------------------------------------------
    // Audio focus
    // ---------------------------------------------------------------------------------------

    private fun requestFocus(audio: AudioManager?, kind: Kind): AudioFocusRequest? {
        audio ?: return null
        val attrs = AudioAttributes.Builder()
            .setUsage(if (kind == Kind.NOTIFICATION) AudioAttributes.USAGE_ASSISTANCE_SONIFICATION else AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val req = AudioFocusRequest.Builder(
            if (kind == Kind.NOTIFICATION) AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK else AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,
        ).setAudioAttributes(attrs).setOnAudioFocusChangeListener { }.build()
        return runCatching { if (audio.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) req else null }.getOrNull()
    }

    private fun abandonFocus(audio: AudioManager?, req: AudioFocusRequest?) {
        if (audio != null && req != null) runCatching { audio.abandonAudioFocusRequest(req) }
    }
}
