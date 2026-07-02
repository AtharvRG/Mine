package com.mine.app.service

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream

class AudioRecorderManager(private val context: Context) {

    private var audioRecord: AudioRecord? = null
    var isRecording = false
        private set

    private var outputStream: ByteArrayOutputStream? = null

    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

    @SuppressLint("MissingPermission")
    fun startRecording() {
        if (isRecording) return // <-- PREVENT DOUBLE THREAD CRASH

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

        // Background thread to constantly drain the mic buffer into our byte array
        Thread {
            val buffer = ByteArray(bufferSize)
            while (isRecording) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                if (read > 0) {
                    outputStream?.write(buffer, 0, read)
                }
            }
        }.start()
    }

    fun stopAndGetAudio(): ByteArray {
        isRecording = false
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null

        return outputStream?.toByteArray() ?: ByteArray(0)
    }
}
