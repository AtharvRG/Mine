// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.mine.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
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
    private val vad: Vad?,
    private val recognizer: OnlineRecognizer?,
    private val tts: OfflineTts?
) : Closeable {

    private var activeAudioTrack: AudioTrack? = null
    private val audioLock = Any()

    val isVadAvailable: Boolean get() = vad != null
    val isSttAvailable: Boolean get() = recognizer != null
    val isTtsAvailable: Boolean get() = tts != null

    /**
     * Transcribes 16kHz mono float audio samples to text.
     */
    fun transcribe(samples: FloatArray, sampleRate: Int = 16000): String {
        val rec = recognizer ?: throw IllegalStateException("STT recognizer is not initialized")
        val stream = rec.createStream()
        return try {
            stream.acceptWaveform(samples, sampleRate)
            stream.inputFinished()
            while (rec.isReady(stream)) {
                rec.decode(stream)
            }
            rec.getResult(stream).text.trim()
        } finally {
            stream.release()
        }
    }

    /**
     * Synthesizes text to speech PCM audio samples.
     */
    fun synthesize(text: String, sid: Int = 0, speed: Float = 1.0f): FloatArray {
        val engine = tts ?: throw IllegalStateException("TTS engine is not initialized")
        val clean = cleanTextForSpeech(text)
        if (clean.isBlank()) return FloatArray(0)
        val generated = engine.generate(clean, sid, speed)
        return generated.samples
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
     * Immediately stops and releases any active audio playback.
     */
    fun stopPlaying() {
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
        try { vad?.release() } catch (_: Exception) {}
        try { recognizer?.release() } catch (_: Exception) {}
        try { tts?.release() } catch (_: Exception) {}
    }

    companion object {
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

        fun create(context: Context, customPaths: VoiceModelPaths? = null): SherpaVoiceEngine? {
            val paths = customPaths ?: VoiceModelPaths.resolve(context) ?: return null
            if (!paths.isComplete) return null

            val vad = try {
                val vadConfig = VadModelConfig(
                    sileroVadModelConfig = SileroVadModelConfig(
                        model = paths.vadModel.absolutePath,
                        threshold = 0.5f,
                        minSilenceDuration = 0.5f,
                        minSpeechDuration = 0.25f,
                        windowSize = 512,
                        maxSpeechDuration = 20.0f
                    ),
                    sampleRate = 16000,
                    numThreads = 2,
                    provider = "cpu",
                    debug = false
                )
                Vad(null, vadConfig)
            } catch (_: Exception) {
                null
            }

            val recognizer = try {
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
            } catch (_: Exception) {
                null
            }

            val tts = try {
                val kokoroConfig = OfflineTtsKokoroModelConfig(
                    model = paths.ttsModel.absolutePath,
                    voices = paths.ttsVoices.absolutePath,
                    tokens = paths.ttsTokens.absolutePath,
                    dataDir = paths.ttsDataDir.absolutePath,
                    lexicon = if (paths.ttsLexicon.exists()) paths.ttsLexicon.absolutePath else "",
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
            } catch (_: Exception) {
                null
            }

            return SherpaVoiceEngine(paths, vad, recognizer, tts)
        }
    }
}
