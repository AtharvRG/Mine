package com.mine.app.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
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
    @Inject lateinit var graphDao: com.mine.app.data.local.db.dao.GraphDao
    @Inject lateinit var queueDao: com.mine.app.data.local.db.dao.QueueDao
    @Inject lateinit var notificationHelper: com.mine.app.util.NotificationHelper
    @Inject lateinit var serviceStateHolder: ServiceStateHolder

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var audioRecord: AudioRecord? = null
    private var isListening = false
    private var isPausedByCall = false

    private val apiKey = BuildConfig.NVIDIA_API_KEY_WHISPER

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

        // Force the notification to show instantly (Android 12+)
        val notification = buildNotification("Listening to your day...")
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            startForeground(1, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(1, notification)
        }
        
        serviceStateHolder.setRunning(true)
        startListening()
        return START_STICKY
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

        // --- NEW: PHONE STATE LISTENER ---
        val telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as android.telephony.TelephonyManager
        serviceScope.launch {
            var lastState = telephonyManager.callState
            while (isActive && isListening) {
                val currentState = telephonyManager.callState
                
                if (currentState == android.telephony.TelephonyManager.CALL_STATE_OFFHOOK || 
                    currentState == android.telephony.TelephonyManager.CALL_STATE_RINGING) {
                    isPausedByCall = true
                } else if (currentState == android.telephony.TelephonyManager.CALL_STATE_IDLE) {
                    if (isPausedByCall && lastState == android.telephony.TelephonyManager.CALL_STATE_OFFHOOK) {
                        Log.d("AmbientService", "Call ended! Triggering Reflection Notification.")
                        notificationHelper.showCallReflectionNotification()
                    }
                    isPausedByCall = false
                }
                
                lastState = currentState
                delay(1000) // Check every second
            }
        }

        serviceScope.launch {
            // Read in tiny 0.1 second chunks (3200 bytes) to save RAM
            val chunkBuffer = ByteArray(3200) 

            while (isActive && isListening) {
                if (isPausedByCall) {
                    delay(500) // Sleep the CPU while on a call!
                    continue
                }

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
                Log.d("AmbientService", "Offline! Saving audio to local SQLite queue.")
                queueDao.insertMemory(
                    com.mine.app.data.local.db.entity.MemoryQueueEntity(
                        audioBlob = audioBytes,
                        speakerLabel = speakerLabel,
                        source = "ambient",
                        status = "pending"
                    )
                )
                return@launch
            }

            try {
                Log.d("AmbientService", "Sending clean audio to Whisper...")
                val transcript = whisperClient.transcribeAudio(apiKey, audioBytes)

                if (transcript.isNotBlank()) {
                    Log.d("AmbientService", "[$speakerLabel]: $transcript")

                    // --- NEW: SAVE TO TRANSCRIPT VAULT ---
                    graphDao.insertTranscript(
                        com.mine.app.data.local.db.entity.TranscriptEntity(
                            text = transcript,
                            speaker = speakerLabel
                        )
                    )

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
        serviceStateHolder.setRunning(false)
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
        // Intent to stop the service directly from the notification
        val stopIntent = Intent(this, AmbientListeningService::class.java).apply { action = "STOP" }
        val stopPendingIntent = PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stopAction = NotificationCompat.Action.Builder(android.R.drawable.ic_delete, "🛑 Stop Listening", stopPendingIntent).build()

        return NotificationCompat.Builder(this, "ambient_channel")
            .setContentTitle("MineAI Ambient")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .addAction(stopAction) // Add the stop button!
            .build()
    }
}
