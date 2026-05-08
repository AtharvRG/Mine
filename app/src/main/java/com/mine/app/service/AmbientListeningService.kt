package com.mine.app.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.mine.app.BuildConfig
import com.mine.app.ai.CuratorAgent
import com.mine.app.data.remote.NimWhisperClient
import com.mine.app.util.NetworkMonitor
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import javax.inject.Inject

@AndroidEntryPoint
class AmbientListeningService : Service() {

    @Inject lateinit var sherpaManager: SherpaManager
    @Inject lateinit var whisperClient: NimWhisperClient
    @Inject lateinit var curatorAgent: CuratorAgent
    @Inject lateinit var networkMonitor: NetworkMonitor

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var audioRecord: AudioRecord? = null
    private var isListening = false

    private val apiKey = BuildConfig.NVIDIA_API_KEY

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            stopListening()
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(1, buildNotification("Listening to your day..."))
        startListening()

        return START_STICKY // Tells Android to restart this service if it gets killed
    }

    @SuppressLint("MissingPermission")
    private fun startListening() {
        if (isListening) return
        isListening = true

        val sampleRate = 16000
        val bufferSize = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize * 2
        )

        audioRecord?.startRecording()

        serviceScope.launch {
            // Read in tiny 0.1 second chunks (3200 bytes) to save RAM
            val chunkBuffer = ByteArray(3200)

            while (isActive && isListening) {
                val read = audioRecord?.read(chunkBuffer, 0, chunkBuffer.size) ?: 0
                if (read > 0) {
                    // 1. Feed the tiny chunk to Sherpa
                    val result = sherpaManager.processLiveAudio(chunkBuffer.copyOfRange(0, read))

                    // 2. If Sherpa detected a complete sentence, it returns the clean audio!
                    if (result != null) {
                        val (cleanAudioBytes, speakerLabel) = result
                        processCompletedSentence(cleanAudioBytes, speakerLabel)
                    }
                }
            }
        }
    }

    private fun processCompletedSentence(audioBytes: ByteArray, speakerLabel: String) {
        serviceScope.launch {
            if (!networkMonitor.isOnline.value) {
                Log.d("AmbientService", "Offline. Dropping audio. (We will queue this in the future!)")
                return@launch
            }

            try {
                Log.d("AmbientService", "Sending clean audio to Whisper...")
                val transcript = whisperClient.transcribeAudio(apiKey, audioBytes)

                if (transcript.isNotBlank()) {
                    Log.d("AmbientService", "[$speakerLabel]: $transcript")

                    // Prepend the speaker label so the AI knows who is talking!
                    val labeledTranscript = "[$speakerLabel]: $transcript"

                    val result = curatorAgent.processTranscript(apiKey, labeledTranscript)
                    Log.d("AmbientService", "Curator: $result")
                }
            } catch (e: Exception) {
                Log.e("AmbientService", "Pipeline Error: ${e.message}")
            }
        }
    }

    private fun stopListening() {
        isListening = false
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        serviceScope.cancel()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopListening()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // --- NOTIFICATION BOILERPLATE ---
    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            "ambient_channel",
            "Ambient Listening",
            NotificationManager.IMPORTANCE_LOW // Low importance = silent, no vibration
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, "ambient_channel")
            .setContentTitle("MineAI")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now) // Default mic icon
            .setOngoing(true)
            .build()
    }
}
