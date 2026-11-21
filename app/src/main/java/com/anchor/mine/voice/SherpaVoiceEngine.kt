// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.mine.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKittenModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import com.anchor.core.voice.SyntheticAudioFixtures
import java.io.Closeable

/**
 * On-device offline voice engine integrating:
 * 1. Silero VAD (voice activity detection)
 * 2. Streaming Zipformer transducer (ASR / speech-to-text)
 * 3. Kokoro TTS (speech synthesis)
 *
 * Implements Blueprint 60 §3 (Offline Speech Pipeline).
 */
class SherpaVoiceEngine private constructor(
    private val paths: VoiceModelPaths,
    val vad: Vad?,
    val recognizer: OnlineRecognizer?,
    val tts: OfflineTts?,
    val speakerExtractor: SpeakerEmbeddingExtractor? = null,
    private val audioManager: AudioManager? = null
) : Closeable {

    private var activeAudioTrack: AudioTrack? = null
    private val audioLock = Any()
    private val sttLock = Any()
    private val ttsLock = Any()
    private val vadLock = Any()
    private val speakerLock = Any()
    private var focusRequest: AudioFocusRequest? = null

    val isVadAvailable: Boolean get() = vad != null
    val isSttAvailable: Boolean get() = recognizer != null
    val isTtsAvailable: Boolean get() = tts != null
    val isSpeakerExtractorAvailable: Boolean get() = speakerExtractor != null

    /**
     * A fresh Silero VAD for one audio stream (ambient listening, enrolment). Each stream gets its
     * own instance so their states never mix; the caller closes it. Null when the VAD model is
     * not installed — use [com.anchor.core.ambient.EnergySegmenter] then.
     */
    fun newSegmenter(): SileroSegmenter? {
        if (!paths.vadPresent) return null
        return try {
            SileroSegmenter(Vad(null, sileroConfig(paths)))
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Extracts speaker embedding using neural SpeakerEmbeddingExtractor (campplus)
     * if available, otherwise gracefully falls back to deterministic autocorrelation + DCT.
     */
    fun extractSpeakerEmbedding(samples: FloatArray, sampleRate: Int = 16000): FloatArray = synchronized(speakerLock) {
        val extractor = speakerExtractor ?: return@synchronized SyntheticAudioFixtures.extractVoiceprintFromAudio(samples)
        val stream = try {
            extractor.createStream()
        } catch (_: Throwable) {
            return@synchronized SyntheticAudioFixtures.extractVoiceprintFromAudio(samples)
        }
        return@synchronized try {
            stream.acceptWaveform(samples, sampleRate)
            stream.inputFinished()
            extractor.compute(stream)
        } catch (_: Throwable) {
            SyntheticAudioFixtures.extractVoiceprintFromAudio(samples)
        } finally {
            try { stream.release() } catch (_: Throwable) {}
        }
    }

    private fun requestFocus(): Boolean {
        val am = audioManager ?: return true
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val playbackAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(playbackAttributes)
                    .setAcceptsDelayedFocusGain(false)
                    .setOnAudioFocusChangeListener { focusChange ->
                        if (focusChange == AudioManager.AUDIOFOCUS_LOSS ||
                            focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                        ) {
                            stopPlaying()
                        }
                    }
                    .build()
                focusRequest = request
                am.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            } else {
                @Suppress("DEPRECATION")
                am.requestAudioFocus(
                    { focusChange ->
                        if (focusChange == AudioManager.AUDIOFOCUS_LOSS ||
                            focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                        ) {
                            stopPlaying()
                        }
                    },
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            }
        } catch (_: Throwable) {
            true
        }
    }

    private fun abandonFocus() {
        val am = audioManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                focusRequest?.let { am.abandonAudioFocusRequest(it) }
                focusRequest = null
            } else {
                @Suppress("DEPRECATION")
                am.abandonAudioFocus(null)
            }
        } catch (_: Throwable) {}
    }

    /**
     * Transcribes 16kHz mono float audio samples to text.
     */
    fun transcribe(samples: FloatArray, sampleRate: Int = 16000): String = synchronized(sttLock) {
        val rec = recognizer ?: throw IllegalStateException("STT recognizer is not initialized")
        val stream = rec.createStream()
        return@synchronized try {
            stream.acceptWaveform(samples, sampleRate)
            // Tail padding (0.66s of silence) to prevent dropping last word in streaming ASR
            val tailPadding = FloatArray((0.66f * sampleRate).toInt())
            stream.acceptWaveform(tailPadding, sampleRate)
            stream.inputFinished()
            while (rec.isReady(stream)) {
                rec.decode(stream)
            }
            rec.getResult(stream).text.trim()
        } finally {
            try { stream.release() } catch (_: Throwable) {}
        }
    }

    /**
     * Evaluates whether the given audio samples contain active human speech using Silero VAD.
     * Uses a sliding window across the buffer and resets VAD state properly.
     */
    fun isSpeech(samples: FloatArray, window: Int = 512): Boolean = synchronized(vadLock) {
        val v = vad ?: return@synchronized false
        return@synchronized try {
            v.reset()
            var any = false
            var i = 0
            while (i + window <= samples.size) {
                v.acceptWaveform(samples.copyOfRange(i, i + window))
                if (v.isSpeechDetected()) any = true
                i += window
            }
            v.flush()
            if (!v.empty()) any = true
            while (!v.empty()) v.pop()
            any
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Synthesizes text to speech PCM audio samples.
     */
    fun synthesize(text: String, sid: Int = 0, speed: Float = 1.0f): FloatArray = synchronized(ttsLock) {
        val engine = tts ?: throw IllegalStateException("TTS engine is not initialized")
        val clean = cleanTextForSpeech(text)
        if (clean.isBlank()) return@synchronized FloatArray(0)
        val generated = engine.generate(clean, sid, speed)
        return@synchronized generated.samples
    }

    /**
     * Gets the native TTS sample rate (typically 24000 Hz for Kokoro).
     */
    fun ttsSampleRate(): Int {
        return tts?.sampleRate() ?: 24000
    }

    /**
     * Plays synthesized speech samples on the device speaker.
     */
    fun play(samples: FloatArray, sampleRate: Int = ttsSampleRate()) {
        if (samples.isEmpty()) return
        stopPlaying()
        requestFocus()

        synchronized(audioLock) {
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()

            val audioFormat = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()

            val bufferSize = maxOf(
                AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT),
                samples.size * 4
            )

            val track = AudioTrack.Builder()
                .setAudioAttributes(audioAttributes)
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()

            track.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
            track.play()
            activeAudioTrack = track
        }
    }

    /**
     * Plays synthesized speech samples on the device speaker, suspending until
     * playback finishes to allow sequential chunked sentence playback.
     */
    suspend fun playBlocking(samples: FloatArray, sampleRate: Int = ttsSampleRate()) {
        if (samples.isEmpty()) return
        stopPlaying()
        requestFocus()

        try {
            synchronized(audioLock) {
                val audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()

                val audioFormat = AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()

                val bufferSize = maxOf(
                    AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT),
                    samples.size * 4
                )

                val track = AudioTrack.Builder()
                    .setAudioAttributes(audioAttributes)
                    .setAudioFormat(audioFormat)
                    .setBufferSizeInBytes(bufferSize)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()

                track.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
                track.play()
                activeAudioTrack = track
            }

            val estimatedDurationMs = (samples.size * 1000L) / sampleRate
            val startTime = System.currentTimeMillis()
            while (System.currentTimeMillis() - startTime < estimatedDurationMs + 250L) {
                val isPlaying = synchronized(audioLock) {
                    activeAudioTrack?.playState == AudioTrack.PLAYSTATE_PLAYING &&
                        (activeAudioTrack?.playbackHeadPosition ?: 0) < samples.size
                }
                if (!isPlaying) break
                kotlinx.coroutines.delay(40L)
            }
        } finally {
            synchronized(audioLock) {
                try { activeAudioTrack?.stop() } catch (_: Throwable) {}
                try { activeAudioTrack?.release() } catch (_: Throwable) {}
                activeAudioTrack = null
            }
            abandonFocus()
        }
    }

    /**
     * Immediately stops and releases any active audio playback.
     */
    fun stopPlaying() {
        abandonFocus()
        synchronized(audioLock) {
            activeAudioTrack?.let { track ->
                try {
                    if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                        track.stop()
                    }
                    track.release()
                } catch (_: Exception) {
                } finally {
                    activeAudioTrack = null
                }
            }
        }
    }

    override fun close() {
        stopPlaying()
        synchronized(vadLock) {
            try { vad?.release() } catch (_: Exception) {}
        }
        synchronized(sttLock) {
            try { recognizer?.release() } catch (_: Exception) {}
        }
        synchronized(ttsLock) {
            try { tts?.release() } catch (_: Exception) {}
        }
        synchronized(speakerLock) {
            try { speakerExtractor?.release() } catch (_: Exception) {}
        }
    }

    companion object {
        @Volatile
        private var instance: SherpaVoiceEngine? = null
        @Volatile
        private var nextRetryAt = 0L

        /** Catalog id of the TTS engine the user picked ("tts-kokoro-int8" / "tts-kitten-nano"). Set from settings. */
        @Volatile
        var preferredTts: String? = null

        fun getInstance(context: Context, customPaths: VoiceModelPaths? = null): SherpaVoiceEngine? {
            instance?.let { return it }
            return synchronized(this) {
                instance?.let { return it }
                if (System.currentTimeMillis() < nextRetryAt) return null
                val created = try {
                    create(context.applicationContext, customPaths)
                } catch (_: Throwable) {
                    null
                }
                if (created == null || (!created.isSttAvailable && !created.isTtsAvailable && !created.isVadAvailable)) {
                    try { created?.close() } catch (_: Throwable) {}
                    nextRetryAt = System.currentTimeMillis() + 10 * 60_000L
                    return null
                }
                instance = created
                created
            }
        }

        fun resetInstanceForTesting() {
            synchronized(this) {
                instance?.close()
                instance = null
                nextRetryAt = 0L
            }
        }
        /**
         * Cleans message text for speech synthesis according to Blueprint 60 §3.3:
         * Strips markdown fences, formatting, chips, and receipts.
         */
        fun cleanTextForSpeech(text: String): String {
            return text
                .replace(Regex("""```[\s\S]*?```"""), "") // Strip code fences
                .replace(Regex("""`[^`]+`"""), "")        // Strip inline code
                .replace(Regex("""<[^>]+>"""), "")        // Strip XML/HTML tags
                .replace(Regex("""\[(.*?)\]\(.*?\)"""), "$1") // Strip links, keep anchor text
                .replace(Regex("""[*_~#>]+"""), "")       // Strip markdown markers
                .replace(Regex("""\s+"""), " ")           // Collapse whitespace
                .trim()
        }

        /** Silero settings shared by every VAD instance: 0.5 s of silence ends a segment, 20 s caps one. */
        internal fun sileroConfig(paths: VoiceModelPaths): VadModelConfig = VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = paths.vadModel.absolutePath,
                threshold = 0.5f,
                minSilenceDuration = 0.5f,
                minSpeechDuration = 0.25f,
                windowSize = 512,
                maxSpeechDuration = 20.0f
            ),
            sampleRate = 16000,
            numThreads = 1,
            provider = "cpu",
            debug = false
        )

        /**
         * Forgets the cached engine so newly downloaded models are picked up on next use. The old
         * engine is not closed here: a listening loop may still hold it, and releasing native
         * memory under it would crash. It is freed when the last holder lets go.
         */
        fun reload() {
            synchronized(this) {
                instance = null
                nextRetryAt = 0L
            }
        }

        fun create(context: Context, customPaths: VoiceModelPaths? = null): SherpaVoiceEngine? {
            val paths = customPaths ?: VoiceModelPaths.resolve(context) ?: return null
            if (!paths.vadPresent && !paths.sttComplete && !paths.anyTtsComplete && !paths.speakerPresent) return null

            val vad = if (paths.vadPresent) try {
                Vad(null, sileroConfig(paths))
            } catch (_: Throwable) {
                null
            } else null

            val recognizer = if (paths.sttComplete) try {
                val modelConfig = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        encoder = paths.sttEncoder.absolutePath,
                        decoder = paths.sttDecoder.absolutePath,
                        joiner = paths.sttJoiner.absolutePath
                    ),
                    tokens = paths.sttTokens.absolutePath,
                    numThreads = 2,
                    debug = false,
                    provider = "cpu"
                )
                val featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80)
                val recConfig = OnlineRecognizerConfig(
                    featConfig = featConfig,
                    modelConfig = modelConfig
                )
                OnlineRecognizer(null, recConfig)
            } catch (_: Throwable) {
                null
            } else null

            // Kokoro or Kitten, as picked in Settings → Models; whichever is installed otherwise.
            val wantKitten = preferredTts == "tts-kitten-nano"
            val useKitten = paths.kittenComplete && (wantKitten || !paths.ttsComplete)
            val tts = if (useKitten) try {
                val kitten = OfflineTtsKittenModelConfig(
                    model = paths.kittenModel!!.absolutePath,
                    voices = paths.kittenVoices!!.absolutePath,
                    tokens = paths.kittenTokens!!.absolutePath,
                    dataDir = paths.kittenDataDir!!.absolutePath,
                    lengthScale = 1.0f
                )
                OfflineTts(null, OfflineTtsConfig(model = OfflineTtsModelConfig(kitten = kitten, numThreads = 2, debug = false, provider = "cpu")))
            } catch (_: Throwable) {
                null
            } else if (paths.ttsComplete) try {
                val kokoroConfig = OfflineTtsKokoroModelConfig(
                    model = paths.ttsModel.absolutePath,
                    voices = paths.ttsVoices.absolutePath,
                    tokens = paths.ttsTokens.absolutePath,
                    dataDir = paths.ttsDataDir.absolutePath,
                    lexicon = if (paths.ttsLexicon.exists()) paths.ttsLexicon.absolutePath else "",
                    dictDir = paths.ttsDictDir?.absolutePath ?: "",
                    lengthScale = 1.0f
                )
                val ttsModelConfig = OfflineTtsModelConfig(
                    kokoro = kokoroConfig,
                    numThreads = 2,
                    debug = false,
                    provider = "cpu"
                )
                val ttsConfig = OfflineTtsConfig(model = ttsModelConfig)
                OfflineTts(null, ttsConfig)
            } catch (_: Throwable) {
                null
            } else null

            val speakerExtractor = if (paths.speakerPresent) try {
                val modelFile = paths.speakerModel
                if (modelFile != null && modelFile.exists()) {
                    val speakerConfig = SpeakerEmbeddingExtractorConfig(
                        model = modelFile.absolutePath,
                        numThreads = 2,
                        debug = false,
                        provider = "cpu"
                    )
                    SpeakerEmbeddingExtractor(null, speakerConfig)
                } else {
                    null
                }
            } catch (_: Throwable) {
                null
            } else null

            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            return SherpaVoiceEngine(paths, vad, recognizer, tts, speakerExtractor, am)
        }
    }
}
