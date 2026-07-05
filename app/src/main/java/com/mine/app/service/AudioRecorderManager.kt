package com.mine.app.service

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream

class AudioRecorderManager(private val context: Context) {

    private var audioRecord: AudioRecord? = null
    var isRecording = false
        private set

    private var outputStream: ByteArrayOutputStream? = null
    private var recordingJob: Job? = null // <-- NEW: Track the recording loop
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

    @SuppressLint("MissingPermission")
    fun startRecording() {
        if (isRecording) return
        
        outputStream = ByteArrayOutputStream()
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            channelConfig,
            audioFormat,
            bufferSize
        )

        audioRecord?.startRecording()
        isRecording = true

        // --- FIX: Use a Coroutine Job instead of a raw Thread ---
        recordingJob = scope.launch {
            val buffer = ByteArray(bufferSize)
            while (isActive && isRecording) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                if (read > 0) {
                    outputStream?.write(buffer, 0, read)
                }
            }
        }
    }

    suspend fun stopAndGetAudio(): ByteArray = withContext(Dispatchers.IO) {
        isRecording = false
        
        // --- FIX: Wait for the reading loop to completely stop BEFORE releasing hardware ---
        recordingJob?.cancelAndJoin() 
        
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null

        return@withContext outputStream?.toByteArray() ?: ByteArray(0)
    }
}
