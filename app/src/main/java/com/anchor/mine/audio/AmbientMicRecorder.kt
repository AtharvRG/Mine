package com.anchor.mine.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive

class AmbientMicRecorder {

    companion object {
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    private var audioRecord: AudioRecord? = null
    private val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)

    @SuppressLint("MissingPermission") // We already check permissions before starting the service
    fun startRecording(): Flow<FloatArray> = flow {
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            bufferSize
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e("MicRecorder", "AudioRecord failed to initialize!")
            return@flow
        }

        audioRecord?.startRecording()
        Log.d("MicRecorder", "Microphone started listening...")

        // A ShortArray to hold the raw 16-bit PCM data
        val audioBuffer = ShortArray(bufferSize)

        // Loop continuously while the Coroutine is active
        while (currentCoroutineContext().isActive) {
            val readResult = audioRecord?.read(audioBuffer, 0, audioBuffer.size) ?: 0

            if (readResult > 0) {
                // Sherpa-ONNX requires Float arrays (values between -1.0 and 1.0)
                val floatArray = FloatArray(readResult)
                for (i in 0 until readResult) {
                    // Convert 16-bit Short to Float
                    floatArray[i] = audioBuffer[i] / 32768.0f
                }

                // Emit the chunk of audio to whoever is listening
                emit(floatArray)
            }
        }
    }.flowOn(Dispatchers.IO) // Run this strictly on a background thread

    fun stopRecording() {
        Log.d("MicRecorder", "Microphone stopped.")
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
    }
}