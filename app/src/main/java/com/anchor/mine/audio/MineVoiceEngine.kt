package com.anchor.mine.audio

import android.content.res.AssetManager
import android.util.Log
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig

class MineVoiceEngine(
    private val assetManager: AssetManager,
    private val onSpeechDetected: (FloatArray) -> Unit // The callback to send audio to Groq
) {

    private var recognizer: OnlineRecognizer? = null
    private var stream: OnlineStream? = null

    // This holds the raw audio chunks while you are speaking
    private val audioBuffer = mutableListOf<FloatArray>()

    init {
        initModel()
    }

    private fun initModel() {
        Log.d("MineVoiceEngine", "Initializing Sherpa-ONNX VAD...")

        val transducerConfig = OnlineTransducerModelConfig(
            encoder = "model/encoder-epoch-99-avg-1.onnx",
            decoder = "model/decoder-epoch-99-avg-1.onnx",
            joiner = "model/joiner-epoch-99-avg-1.onnx"
        )

        val modelConfig = OnlineModelConfig(
            transducer = transducerConfig,
            tokens = "model/tokens.txt",
            numThreads = 1, // Reduced to 1 thread since it's just doing VAD now!
            debug = false
        )

        val featureConfig = FeatureConfig(
            sampleRate = 16000,
            featureDim = 80
        )

        // VAD Rules: This is what triggers the "Endpoint"
        val endpointConfig = EndpointConfig(
            rule1 = EndpointRule(false, 2.0f, 0.0f), // Stop if silence is 2.0s
            rule2 = EndpointRule(true, 0.8f, 0.0f),  // Stop if trailing silence is 0.8s (Snappy!)
            rule3 = EndpointRule(false, 0.0f, 20.0f) // Hard cut off at 20s to prevent memory overflow
        )

        val config = OnlineRecognizerConfig(
            modelConfig = modelConfig,
            featConfig = featureConfig,
            endpointConfig = endpointConfig,
            enableEndpoint = true,
            decodingMethod = "greedy_search",
            maxActivePaths = 4
        )

        recognizer = OnlineRecognizer(assetManager, config)
        stream = recognizer?.createStream()
    }

    fun processAudioChunk(audioChunk: FloatArray) {
        val currentStream = stream ?: return
        val currentRecognizer = recognizer ?: return

        // 1. Save the chunk to our memory buffer
        audioBuffer.add(audioChunk)

        // 2. Feed it to Sherpa to check for speech/silence
        currentStream.acceptWaveform(audioChunk, sampleRate = 16000)

        while (currentRecognizer.isReady(currentStream)) {
            currentRecognizer.decode(currentStream)
        }

        // 3. Has the user stopped talking?
        if (currentRecognizer.isEndpoint(currentStream)) {
            val text = currentRecognizer.getResult(currentStream).text

            // If Sherpa hallucinated at least SOME text, we know it wasn't just wind/noise
            if (text.isNotBlank()) {
                Log.d("MineVoiceEngine", "VAD Triggered! Packaging audio for Groq...")

                // Flatten the list of chunks into one single FloatArray
                val totalSize = audioBuffer.sumOf { it.size }
                val flatArray = FloatArray(totalSize)
                var offset = 0
                for (chunk in audioBuffer) {
                    System.arraycopy(chunk, 0, flatArray, offset, chunk.size)
                    offset += chunk.size
                }

                // Send the perfect audio cut to the Service
                onSpeechDetected(flatArray)
            }

            // Reset the buffer and Sherpa for the next sentence
            audioBuffer.clear()
            currentRecognizer.reset(currentStream)
        }
    }

    fun destroy() {
        stream?.release()
        recognizer?.release()
    }
}