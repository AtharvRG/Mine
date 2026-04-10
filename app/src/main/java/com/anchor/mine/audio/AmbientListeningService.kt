package com.anchor.mine.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.anchor.mine.MainActivity
import com.anchor.mine.brain.MemoryNodeL2
import com.anchor.mine.brain.MineDatabase
import com.anchor.mine.brain.TranscriptNode
import com.anchor.mine.network.GroqClient
import com.anchor.mine.network.NimClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class AmbientListeningService : Service() {

    companion object {
        const val CHANNEL_ID = "ambient_listening_channel"
        const val NUDGE_CHANNEL_ID = "nudge_channel" // <-- NEW
        const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "com.anchor.mine.ACTION_STOP"
    }


    private val micRecorder = AmbientMicRecorder()
    private val groqClient by lazy { GroqClient(applicationContext) }
    private val nimClient by lazy { NimClient(applicationContext) }
    private val keyManager by lazy { com.anchor.mine.security.KeyManager(applicationContext) }
    private lateinit var voiceEngine: MineVoiceEngine

    // --- NEW: The Call Observer ---
    private lateinit var callObserver: com.anchor.mine.sensors.CallStateObserver



    // Concurrency & State
    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private var recordingJob: Job? = null

    // Prevents the "Stop" logic from firing twice when Android kills the service
    private var isConsolidating = false

    private var isNudgeTalksEnabled = true


    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        voiceEngine = MineVoiceEngine(assets) { speechFloatArray ->
            processSpeechWithGroq(speechFloatArray)
        }

        // --- NEW: Initialize the Call Observer ---
        callObserver = com.anchor.mine.sensors.CallStateObserver(applicationContext) {
            // THIS FIRES WHEN YOU HANG UP THE PHONE!
            firePostCallNudge()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopListeningAndConsolidate()
            return START_NOT_STICKY
        }

        // Android 14 Strict Microphone Foreground Service Requirement
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }

        startListening()

        return START_STICKY
    }

    private fun startListening() {
        recordingJob?.cancel()
        recordingJob = serviceScope.launch {
            micRecorder.startRecording().collect { audioChunk ->
                voiceEngine.processAudioChunk(audioChunk)
            }
        }
        callObserver.startObserving()
    }

    private fun processSpeechWithGroq(speechArray: FloatArray) {
        serviceScope.launch {
            Log.d("GroqPipeline", "Encoding ${speechArray.size} floats to WAV...")
            val wavBytes = WavEncoder.encodeToWav(speechArray)

            Log.d("GroqPipeline", "Uploading to Groq...")
            val transcript = groqClient.transcribeAudio(wavBytes)

            if (transcript.isNotBlank()) {
                Log.d("GroqPipeline", "✅ GROQ TRANSCRIPT: $transcript")

                // 1. Save to L1 Memory (Always happens)
                val db = MineDatabase.getDatabase(applicationContext)
                val memoryNode = TranscriptNode(text = transcript)
                db.memoryDao().insertTranscript(memoryNode)

                // 2. NUDGE TALKS LOGIC (If enabled in UI)
                if (keyManager.isNudgeActive()) { // <-- READS FROM TOGGLE INSTANTLY
                    val nudgeReply = nimClient.generateNudgeInsight(transcript)

                    if (nudgeReply != "IGNORE" && nudgeReply.isNotBlank()) {
                        Log.d("NudgeTalks", "💡 Insight Generated: $nudgeReply")
                        fireLiveNudgeNotification(nudgeReply)
                    } else {
                        Log.d("NudgeTalks", "Silently ignored background noise.")
                    }
                }

            } else {
                Log.e("GroqPipeline", "❌ Groq returned empty or failed.")
            }
        }
    }

    private fun stopListeningAndConsolidate() {
        if (isConsolidating) return
        isConsolidating = true

        Log.d("AmbientService", "Stopping Mic and Consolidating Memory...")

        recordingJob?.cancel()
        micRecorder.stopRecording()
        voiceEngine.destroy()

        // Stop watching for phone calls
        callObserver.stopObserving()

        // 2. UPDATE the notification so Android keeps us alive while NIM thinks!
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val processingNotification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MineAI is Thinking")
            .setContentText("Consolidating your ambient session...")
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setOngoing(true)
            .build()
        manager.notify(NOTIFICATION_ID, processingNotification)

        // 3. Process the L1 memories into a clean L2 Summary
        serviceScope.launch {
            val db = MineDatabase.getDatabase(applicationContext)
            val rawNodes = db.memoryDao().getUnprocessedTranscripts()

            if (rawNodes.isNotEmpty()) {
                val rawTextList = rawNodes.map { it.text }
                val cleanSummary = nimClient.cleanAndSummarize(rawTextList)

                db.memoryDao().markAllAsProcessed()

                val l2Node = MemoryNodeL2(summaryText = cleanSummary)
                db.memoryDao().insertL2Memory(l2Node)

                // 4. Drop the Nudge Notification!
                showReviewNudgeNotification()
            }

            // 5. NOW we can safely remove the foreground state and kill the service
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopListeningAndConsolidate()
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, AmbientListeningService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MineAI is Listening")
            .setContentText("Ambient mode active. Tap to open.")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun showReviewNudgeNotification() {
        // Use applicationContext so the notification survives even if the Service dies 1 millisecond later
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            putExtra("OPEN_REVIEW", true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }

        val pendingIntent = PendingIntent.getActivity(
            applicationContext, 2, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(applicationContext, NUDGE_CHANNEL_ID)
            .setContentTitle("Ambient Session Complete")
            .setContentText("Tap to review and commit your new memories.")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_MAX) // MAX priority
            .setDefaults(NotificationCompat.DEFAULT_VIBRATE) // FORCE the drop-down!
            .build()

        manager.notify(2, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            val silentChannel = NotificationChannel(CHANNEL_ID, "Ambient Listening", NotificationManager.IMPORTANCE_LOW)
            manager.createNotificationChannel(silentChannel)

            val nudgeChannel = NotificationChannel(NUDGE_CHANNEL_ID, "Session Alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                enableVibration(true) // Force channel vibration
                vibrationPattern = longArrayOf(0, 250, 250, 250) // A nice double-buzz
            }
            manager.createNotificationChannel(nudgeChannel)
        }

    }

    private fun firePostCallNudge() {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // When tapped, it opens the Review Screen so you can type out what happened on the call
        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            putExtra("OPEN_REVIEW", true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }

        val pendingIntent = PendingIntent.getActivity(
            applicationContext, 3, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(applicationContext, NUDGE_CHANNEL_ID)
            .setContentTitle("Call Ended")
            .setContentText("Tap to record a summary of that conversation.")
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setDefaults(NotificationCompat.DEFAULT_VIBRATE)
            .build()

        manager.notify(3, notification)
    }

    // The Live Nudge Notification (Pops up instantly while you are talking)
    private fun fireLiveNudgeNotification(insightText: String) {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val notification = NotificationCompat.Builder(applicationContext, NUDGE_CHANNEL_ID)
            .setContentTitle("MineAI Insight")
            .setContentText(insightText)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setStyle(NotificationCompat.BigTextStyle().bigText(insightText)) // Allows multi-line text
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setDefaults(NotificationCompat.DEFAULT_VIBRATE)
            .build()

        // Use a random ID so multiple nudges can stack up if you are brainstorming
        manager.notify((System.currentTimeMillis() % 10000).toInt(), notification)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}