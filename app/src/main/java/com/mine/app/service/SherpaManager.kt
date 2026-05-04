package com.mine.app.service

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SherpaManager @Inject constructor(private val context: Context) {

    private val sampleRate = 16000

    // 1. VAD (Voice Activity Detection) - Updated to 1.13.3 class names
    private val vad: Vad

    // 2. Denoiser
    private val denoiser: OfflineSpeechDenoiser

    // 3. Speaker ID
    private val speakerExtractor: SpeakerEmbeddingExtractor
    private val speakerManager: SpeakerEmbeddingManager

    init {
        Log.d("SherpaManager", "Initializing Sherpa-ONNX Models from Assets...")

        // Initialize VAD
        val sileroConfig = SileroVadModelConfig(
            model = "silero_vad.onnx",
            threshold = 0.5f,
            minSilenceDuration = 1.0f,
            minSpeechDuration = 0.5f,
            windowSize = 512
        )

        // We instantiate it empty, then set the properties.
        val vadConfig = VadModelConfig()
        vadConfig.sampleRate = sampleRate
        vadConfig.numThreads = 1
        vadConfig.debug = false

        // Try setting the silero config.
        // If 'sileroVad' is red, delete it, type 'silero', and press Ctrl+Space to let Android Studio autocomplete the exact name!
        vadConfig.sileroVadModelConfig = sileroConfig

        vad = Vad(assetManager = context.assets, config = vadConfig)


        // Initialize Denoiser
        val denoiserConfig = OfflineSpeechDenoiserConfig(
            model = OfflineSpeechDenoiserModelConfig(
                gtcrn = OfflineSpeechDenoiserGtcrnModelConfig(model = "gtcrn_simple.onnx"),
                provider = "cpu",
                numThreads = 1
            )
        )
        denoiser = OfflineSpeechDenoiser(assetManager = context.assets, config = denoiserConfig)

        // Initialize Speaker ID
        val extractorConfig = SpeakerEmbeddingExtractorConfig(
            model = "3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx",
            numThreads = 1,
            debug = false
        )
        speakerExtractor = SpeakerEmbeddingExtractor(assetManager = context.assets, config = extractorConfig)
        speakerManager = SpeakerEmbeddingManager(speakerExtractor.dim())

        Log.d("SherpaManager", "Sherpa-ONNX Initialization Complete!")
    }

    suspend fun enrollUserVoice(pcmBytes: ByteArray): Boolean = withContext(Dispatchers.Default) {
        val floats = pcmBytesToFloatArray(pcmBytes)
        val stream = speakerExtractor.createStream()
        stream.acceptWaveform(sampleRate = sampleRate, samples = floats)
        stream.inputFinished()

        val embedding = speakerExtractor.compute(stream)
        stream.release()

        val success = speakerManager.add(name = "User", embedding = embedding)
        Log.d("SherpaManager", "Voice Enrollment Success: $success")
        return@withContext success
    }

    suspend fun processLiveAudio(pcmBytes: ByteArray): Pair<ByteArray, String>? = withContext(Dispatchers.Default) {
        val floats = pcmBytesToFloatArray(pcmBytes)

        // 1. Feed to VAD
        vad.acceptWaveform(floats)

        // 2. Check if VAD has detected a complete speech segment
        if (!vad.empty()) {

            val segment = vad.front()
            val speechFloats = segment.samples
            vad.pop() // CRITICAL: Free C++ memory!

            Log.d("SherpaManager", "VAD detected speech! Length: ${speechFloats.size / sampleRate} seconds.")

            // 3. Denoise the speech
            val cleanAudio = denoiser.run(speechFloats, sampleRate)
            val cleanFloats = cleanAudio.samples

            // 4. Identify the Speaker
            val stream = speakerExtractor.createStream()
            stream.acceptWaveform(sampleRate = sampleRate, samples = cleanFloats)
            stream.inputFinished()
            val embedding = speakerExtractor.compute(stream)
            stream.release()

            // Threshold 0.60f is recommended for WeSpeaker/3D-Speaker
            val matchedName = speakerManager.search(embedding = embedding, threshold = 0.60f)
            val speakerLabel = if (matchedName.isNotBlank()) matchedName else "Speaker 2"

            Log.d("SherpaManager", "Speaker Identified as: [$speakerLabel]")

            // 5. Convert back to 16-bit PCM ByteArray for Whisper
            val cleanBytes = floatArrayToPcmBytes(cleanFloats)

            return@withContext Pair(cleanBytes, speakerLabel)
        }

        return@withContext null
    }

    // --- MATH UTILITIES ---
    private fun pcmBytesToFloatArray(bytes: ByteArray): FloatArray {
        val floats = FloatArray(bytes.size / 2)
        for (i in floats.indices) {
            val low = bytes[i * 2].toInt() and 0xFF
            val high = bytes[i * 2 + 1].toInt() shl 8
            val sample = (high or low).toShort()
            floats[i] = sample / 32768.0f
        }
        return floats
    }

    private fun floatArrayToPcmBytes(floats: FloatArray): ByteArray {
        val bytes = ByteArray(floats.size * 2)
        for (i in floats.indices) {
            var sample = (floats[i] * 32768.0f).toInt()
            if (sample > 32767) sample = 32767
            if (sample < -32768) sample = -32768
            bytes[i * 2] = (sample and 0xFF).toByte()
            bytes[i * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
        }
        return bytes
    }

    fun release() {
        vad.release()
        denoiser.release()
        speakerExtractor.release()
        speakerManager.release()
    }
}
