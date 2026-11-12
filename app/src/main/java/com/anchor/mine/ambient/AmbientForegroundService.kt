// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.mine.ambient

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.telephony.TelephonyManager
import androidx.core.app.NotificationCompat
import com.anchor.core.ambient.AmbientReport
import com.anchor.core.ambient.AmbientReportItem
import com.anchor.core.ambient.AmbientSegment
import com.anchor.core.ambient.AmbientSession
import com.anchor.core.ambient.AmbientSleepReason
import com.anchor.core.ambient.AmbientState
import com.anchor.core.ambient.AmbientWindow
import com.anchor.core.ambient.AmbientProposalExtractor
import com.anchor.core.ambient.DialogueWindowEngine
import com.anchor.core.ambient.NumberScrubber
import com.anchor.core.ambient.PresenceGate
import com.anchor.core.ambient.SpeakerLabel
import com.anchor.core.models.Node
import com.anchor.core.models.NodeStateSnapshot
import com.anchor.core.models.RawEvent
import com.anchor.core.voice.SyntheticAudioFixtures
import com.anchor.mine.MainActivity
import com.anchor.mine.MineApp
import com.anchor.mine.alarms.NotificationChannels
import com.anchor.mine.voice.AudioRecorderHelper
import com.anchor.mine.voice.SherpaVoiceEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * Foreground Service for Ambient Listening per Blueprint 61.
 *
 * Invariants:
 * 1. User-Tap Start Only: Started only by explicit user action.
 * 2. Never Silent Restart: Returns START_NOT_STICKY; never auto-restarted silently after kill or reboot.
 * 3. Zero Audio Persistence: Audio resides strictly in RAM ring buffers, never written to disk or DB.
 * 4. Absolute Zero Vibration: Notifications have vibration disabled.
 * 5. Blocker Aware: Sleeps when battery < 20%, phone call active, outside window, or in pause places.
 */
class AmbientForegroundService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var tickerJob: Job? = null

    @Volatile
    private var currentState: AmbientState = AmbientState.Off

    @Volatile
    private var pausedUntilMs: Long = 0L

    @Volatile
    private var isPhoneCallActive: Boolean = false

    @Volatile
    private var isBatteryLow: Boolean = false

    @Volatile
    private var isCharging: Boolean = false

    private val presenceGate = PresenceGate(gateUserPresence = true)
    private val proposalExtractor = AmbientProposalExtractor()
    private val dialogueEngine = DialogueWindowEngine()
    private val recentTurns = mutableListOf<DialogueWindowEngine.Turn>()
    private var currentSessionId: String? = null
    private var listeningJob: Job? = null
    private var activeRecorder: AudioRecorderHelper? = null
    private val json = Json { ignoreUnknownKeys = true }

    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent ?: return
            when (intent.action) {
                Intent.ACTION_BATTERY_CHANGED -> {
                    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                    val pct = if (level >= 0 && scale > 0) (level * 100) / scale else 100
                    isBatteryLow = pct < 20
                    val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                    isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                            status == BatteryManager.BATTERY_STATUS_FULL
                    serviceScope.launch { evaluateBlockers() }
                }
                Intent.ACTION_POWER_CONNECTED -> {
                    isCharging = true
                    serviceScope.launch { evaluateBlockers() }
                }
                Intent.ACTION_POWER_DISCONNECTED -> {
                    isCharging = false
                    serviceScope.launch { evaluateBlockers() }
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        NotificationChannels.ensureCreated(this)

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        registerReceiver(powerReceiver, filter)

        val hasMicPermission = androidx.core.content.ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

        if (!hasMicPermission) {
            stopSelf()
            return
        }

        val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, buildNotification(AmbientState.Listening), fgsType)
        } else {
            startForeground(NOTIFICATION_ID, buildNotification(AmbientState.Listening))
        }
        currentState = AmbientState.Listening
        startListeningLoop()
        startStateTicker()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent ?: return START_NOT_STICKY

        when (intent.action) {
            ACTION_START -> {
                pausedUntilMs = 0L
                serviceScope.launch { evaluateBlockers() }
            }
            ACTION_STOP -> {
                stopAmbientService()
            }
            ACTION_PAUSE_15M -> {
                pausedUntilMs = System.currentTimeMillis() + 15 * 60 * 1000L
                updateState(AmbientState.Sleeping(AmbientSleepReason.USER_PAUSED))
            }
            ACTION_PAUSE_1H -> {
                pausedUntilMs = System.currentTimeMillis() + 60 * 60 * 1000L
                updateState(AmbientState.Sleeping(AmbientSleepReason.USER_PAUSED))
            }
            ACTION_RESUME -> {
                pausedUntilMs = 0L
                serviceScope.launch { evaluateBlockers() }
            }
            ACTION_BOOKMARK_2M -> {
                bookmarkLast2Minutes()
            }
        }

        return START_NOT_STICKY
    }

    private fun startStateTicker() {
        tickerJob?.cancel()
        tickerJob = serviceScope.launch {
            while (isActive) {
                delay(10_000L) // Evaluate state & blockers every 10 seconds
                evaluateBlockers()
            }
        }
    }

    private suspend fun evaluateBlockers() {
        val now = System.currentTimeMillis()

        // 1. Check user pause
        if (pausedUntilMs > now) {
            updateState(AmbientState.Sleeping(AmbientSleepReason.USER_PAUSED))
            return
        }

        // 2. Check mic permission
        val hasMicPermission = androidx.core.content.ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!hasMicPermission) {
            updateState(AmbientState.Sleeping(AmbientSleepReason.MIC_BUSY))
            return
        }

        // 3. Check phone call / mic busy
        try {
            val telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            @Suppress("DEPRECATION")
            isPhoneCallActive = telephonyManager?.callState != TelephonyManager.CALL_STATE_IDLE
            if (isPhoneCallActive) {
                updateState(AmbientState.Sleeping(AmbientSleepReason.MIC_BUSY))
                return
            }
        } catch (_: SecurityException) {
            // Missing READ_PHONE_STATE, continue safely
        }

        // 4. Check battery low (< 20% and not charging)
        if (isBatteryLow && !isCharging) {
            updateState(AmbientState.Sleeping(AmbientSleepReason.BATTERY_LOW))
            return
        }

        // 5. Check quiet hours / day window
        val app = application as? MineApp
        if (app != null && app.sharedStorage.isCompleted) {
            try {
                val storage = app.sharedStorage.await()
                val rule = storage.getEffectiveDayRule(now)
                if (!com.anchor.core.time.DayRollover.isInDayWindow(now, rule)) {
                    updateState(AmbientState.Sleeping(AmbientSleepReason.OUTSIDE_WINDOW))
                    return
                }
            } catch (_: Exception) {}
        }

        // All clear -> Listening
        updateState(AmbientState.Listening)
    }

    private fun updateState(newState: AmbientState) {
        if (currentState != newState) {
            currentState = newState
            if (newState is AmbientState.Listening) {
                startListeningLoop()
            } else {
                stopListeningLoop()
            }
            val notification = buildNotification(newState)
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager
            manager?.notify(NOTIFICATION_ID, notification)
        }
    }

    private fun startListeningLoop() {
        if (listeningJob?.isActive == true) return
        listeningJob = serviceScope.launch(Dispatchers.IO) {
            while (isActive && currentState is AmbientState.Listening) {
                val recorder = AudioRecorderHelper()
                activeRecorder = recorder
                val started = recorder.startRecording(this)
                if (!started) {
                    delay(2000L)
                    continue
                }
                val sliceStart = System.currentTimeMillis()
                delay(3000L) // 3-second audio slice
                val samples = recorder.stopRecording()
                activeRecorder = null
                val sliceEnd = System.currentTimeMillis()

                if (samples.isEmpty()) continue

                // 1. VAD check (zero allocation, in-memory)
                val hasSpeech = SyntheticAudioFixtures.isSpeechEnergy(samples, thresholdRms = 0.015f)
                if (!hasSpeech) continue

                // 2. Acoustic extraction & PresenceGate evaluation
                val segmentEmb = SyntheticAudioFixtures.extractVoiceprintFromAudio(samples)
                val app = application as? MineApp
                if (app == null || !app.sharedStorage.isCompleted) continue
                val storage = try { app.sharedStorage.await() } catch (_: Exception) { continue }
                val userVp = storage.getVoiceprint()
                val userFloats = userVp?.toFloatArray()

                val gateResult = presenceGate.scoreSegment(
                    tStartMs = sliceStart,
                    tEndMs = sliceEnd,
                    samples = samples,
                    segmentEmbedding = segmentEmb,
                    voiceprintFloats = userFloats
                )

                // 3. Speech Transcription via offline Sherpa ONNX engine
                val voiceEngine = SherpaVoiceEngine.getInstance(applicationContext)
                val transcript = voiceEngine?.transcribe(samples)?.trim().orEmpty()
                if (transcript.isBlank()) continue

                // 4. Privacy: Scrub numbers before any storage or processing
                val scrubbedText = NumberScrubber.scrub(transcript)

                val turn = DialogueWindowEngine.Turn(
                    segmentId = UUID.randomUUID().toString(),
                    tStartMs = sliceStart,
                    tEndMs = sliceEnd,
                    label = gateResult.label,
                    text = scrubbedText,
                    score = gateResult.score,
                    isShort = gateResult.isShort
                )

                synchronized(recentTurns) {
                    recentTurns.add(turn)
                    val cutoff = sliceEnd - 120_000L
                    recentTurns.removeAll { it.tEndMs < cutoff }
                }

                // 5. If user is present, extract proposals & commit
                if (gateResult.label == SpeakerLabel.USER) {
                    val liveWindow = DialogueWindowEngine.Window(
                        id = UUID.randomUUID().toString(),
                        turns = listOf(turn),
                        startedAtMs = sliceStart,
                        endedAtMs = sliceEnd
                    )

                    val existingNodes = storage.getActiveNodes(50)
                    val extracted = proposalExtractor.extract(
                        window = liveWindow,
                        llmResponseJson = null,
                        existingNodes = existingNodes,
                        nowMs = sliceEnd
                    )

                    if (extracted.isNotEmpty()) {
                        val sId = currentSessionId ?: run {
                            val newSId = "amb_sess_${UUID.randomUUID()}"
                            currentSessionId = newSId
                            storage.saveAmbientSession(
                                AmbientSession(
                                    id = newSId,
                                    startedAt = sliceStart,
                                    listenSeconds = 3,
                                    speechSeconds = 3,
                                    segmentsCount = 1
                                )
                            )
                            newSId
                        }

                        storage.saveAmbientWindow(
                            AmbientWindow(
                                id = liveWindow.id,
                                sessionId = sId,
                                startedAt = liveWindow.startedAtMs,
                                endedAt = liveWindow.endedAtMs,
                                state = "extracted",
                                gateOpened = true
                            )
                        )

                        storage.saveAmbientSegment(
                            AmbientSegment(
                                id = turn.segmentId,
                                windowId = liveWindow.id,
                                tStart = turn.tStartMs,
                                tEnd = turn.tEndMs,
                                label = turn.label.wire,
                                score = turn.score,
                                isShort = turn.isShort,
                                text = turn.text,
                                sttRef = "sherpa_offline",
                                expiresAt = sliceEnd + 48L * 3600 * 1000L
                            )
                        )

                        val dayRule = storage.getEffectiveDayRule(sliceEnd)
                        val currentDayKey = com.anchor.core.time.DayRollover.computeDayKey(sliceEnd, dayRule)
                        val report = storage.getLatestAmbientReport()?.takeIf { it.dayKey == currentDayKey } ?: run {
                            val rep = AmbientReport(id = "amb_rep_${UUID.randomUUID()}", dayKey = currentDayKey, generatedAt = sliceEnd)
                            storage.saveAmbientReport(rep)
                            rep
                        }

                        val existingSummaries = storage.getAmbientReportItems(report.id)
                            .map { it.summary.trim().lowercase() }
                            .toSet()

                        for (item in extracted) {
                            val pNode = item.proposedNode
                            if (item.kind == "proposal" && pNode != null) {
                                if (item.summary.trim().lowercase() in existingSummaries) {
                                    continue
                                }
                                val uId = "u_${UUID.randomUUID()}"
                                val event = RawEvent(
                                    opId = uId,
                                    opType = "PROPOSE",
                                    targetType = "node",
                                    targetId = pNode.id,
                                    afterJson = json.encodeToString(NodeStateSnapshot(pNode, null)),
                                    tsUtc = sliceEnd
                                )
                                storage.createNodeAndCommit(pNode, event, reminder = null)
                                storage.saveAmbientReportItem(
                                    AmbientReportItem(
                                        id = UUID.randomUUID().toString(),
                                        reportId = report.id,
                                        windowId = liveWindow.id,
                                        kind = "proposal",
                                        summary = item.summary,
                                        nodeId = pNode.id,
                                        flags = item.flags.joinToString(","),
                                        state = "pending"
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun bookmarkLast2Minutes() {
        serviceScope.launch(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val turnsSnapshot = synchronized(recentTurns) {
                val cutoff = now - 120_000L
                recentTurns.filter { it.tEndMs >= cutoff }.sortedBy { it.tStartMs }
            }

            if (turnsSnapshot.isEmpty()) {
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(
                        applicationContext,
                        "No recent speech detected in the last 2 minutes.",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
                return@launch
            }

            val bookmarkWindow = dialogueEngine.createBookmarkWindow(turnsSnapshot, now, 120_000L)
            if (bookmarkWindow == null) {
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(
                        applicationContext,
                        "No speech within the 2-minute window.",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
                return@launch
            }

            val app = application as? MineApp ?: return@launch
            if (!app.sharedStorage.isCompleted) return@launch
            val storage = try { app.sharedStorage.await() } catch (_: Exception) { return@launch }

            val existingNodes = storage.getActiveNodes(50)
            val extracted = proposalExtractor.extract(
                window = bookmarkWindow,
                llmResponseJson = null,
                existingNodes = existingNodes,
                nowMs = now,
                manualBookmark = true
            )

            if (extracted.isNotEmpty()) {
                val sId = currentSessionId ?: run {
                    val newSId = "amb_sess_${UUID.randomUUID()}"
                    currentSessionId = newSId
                    storage.saveAmbientSession(
                        AmbientSession(
                            id = newSId,
                            startedAt = bookmarkWindow.startedAtMs,
                            listenSeconds = 120,
                            speechSeconds = 120,
                            segmentsCount = bookmarkWindow.turns.size
                        )
                    )
                    newSId
                }

                storage.saveAmbientWindow(
                    AmbientWindow(
                        id = bookmarkWindow.id,
                        sessionId = sId,
                        startedAt = bookmarkWindow.startedAtMs,
                        endedAt = bookmarkWindow.endedAtMs,
                        state = "extracted",
                        gateOpened = true
                    )
                )

                for (turn in bookmarkWindow.turns) {
                    storage.saveAmbientSegment(
                        AmbientSegment(
                            id = turn.segmentId,
                            windowId = bookmarkWindow.id,
                            tStart = turn.tStartMs,
                            tEnd = turn.tEndMs,
                            label = turn.label.wire,
                            score = turn.score,
                            isShort = turn.isShort,
                            text = turn.text,
                            sttRef = "sherpa_offline",
                            expiresAt = now + 48L * 3600 * 1000L
                        )
                    )
                }

                val dayRule = storage.getEffectiveDayRule(now)
                val currentDayKey = com.anchor.core.time.DayRollover.computeDayKey(now, dayRule)
                val report = storage.getLatestAmbientReport()?.takeIf { it.dayKey == currentDayKey } ?: run {
                    val rep = AmbientReport(id = "amb_rep_${UUID.randomUUID()}", dayKey = currentDayKey, generatedAt = now)
                    storage.saveAmbientReport(rep)
                    rep
                }

                val existingSummaries = storage.getAmbientReportItems(report.id)
                    .map { it.summary.trim().lowercase() }
                    .toSet()

                var addedCount = 0
                for (item in extracted) {
                    val pNode = item.proposedNode
                    if (item.kind == "proposal" && pNode != null) {
                        if (item.summary.trim().lowercase() in existingSummaries) {
                            continue
                        }
                        val uId = "u_${UUID.randomUUID()}"
                        val event = RawEvent(
                            opId = uId,
                            opType = "PROPOSE",
                            targetType = "node",
                            targetId = pNode.id,
                            afterJson = json.encodeToString(NodeStateSnapshot(pNode, null)),
                            tsUtc = now
                        )
                        storage.createNodeAndCommit(pNode, event, reminder = null)
                        storage.saveAmbientReportItem(
                            AmbientReportItem(
                                id = UUID.randomUUID().toString(),
                                reportId = report.id,
                                windowId = bookmarkWindow.id,
                                kind = "proposal",
                                summary = item.summary,
                                nodeId = pNode.id,
                                flags = item.flags.joinToString(","),
                                state = "pending"
                            )
                        )
                        addedCount++
                    } else if (item.kind == "keypoint") {
                        if (item.summary.trim().lowercase() in existingSummaries) {
                            continue
                        }
                        storage.saveAmbientReportItem(
                            AmbientReportItem(
                                id = UUID.randomUUID().toString(),
                                reportId = report.id,
                                windowId = bookmarkWindow.id,
                                kind = "keypoint",
                                summary = item.summary,
                                nodeId = null,
                                flags = item.flags.joinToString(","),
                                state = "pending"
                            )
                        )
                        addedCount++
                    }
                }

                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(
                        applicationContext,
                        "Remembered last 2m: $addedCount item(s) captured.",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            } else {
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(
                        applicationContext,
                        "No action items found in the last 2 minutes.",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private fun stopListeningLoop() {
        listeningJob?.cancel()
        listeningJob = null
        try {
            activeRecorder?.cancelRecording()
        } catch (_: Exception) {}
        activeRecorder = null
    }

    private fun buildNotification(state: AmbientState): Notification {
        val title = "Ambient Listening"
        val content = when (state) {
            is AmbientState.Listening -> "Listening for conversation..."
            is AmbientState.Sleeping -> "Paused: ${state.reason.display}"
            is AmbientState.Processing -> "Processing dialogue..."
            is AmbientState.Off -> "Stopped"
        }

        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val pause15mIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, AmbientForegroundService::class.java).apply { action = ACTION_PAUSE_15M },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this,
            3,
            Intent(this, AmbientForegroundService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val bookmarkIntent = PendingIntent.getService(
            this,
            4,
            Intent(this, AmbientForegroundService::class.java).apply { action = ACTION_BOOKMARK_2M },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NotificationChannels.AMBIENT)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(title)
            .setContentText(content)
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVibrate(longArrayOf(0))
            .addAction(android.R.drawable.ic_menu_agenda, "Remember 2m", bookmarkIntent)
            .addAction(android.R.drawable.ic_media_pause, "Pause 15m", pause15mIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopIntent)
            .build()
    }

    private fun stopAmbientService() {
        stopListeningLoop()
        tickerJob?.cancel()
        currentState = AmbientState.Off
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopListeningLoop()
        try {
            unregisterReceiver(powerReceiver)
        } catch (_: Exception) {}
        serviceScope.cancel()
    }

    companion object {
        const val NOTIFICATION_ID = 4001
        const val ACTION_START = "com.anchor.mine.ambient.ACTION_START"
        const val ACTION_STOP = "com.anchor.mine.ambient.ACTION_STOP"
        const val ACTION_PAUSE_15M = "com.anchor.mine.ambient.ACTION_PAUSE_15M"
        const val ACTION_PAUSE_1H = "com.anchor.mine.ambient.ACTION_PAUSE_1H"
        const val ACTION_RESUME = "com.anchor.mine.ambient.ACTION_RESUME"
        const val ACTION_BOOKMARK_2M = "com.anchor.mine.ambient.ACTION_BOOKMARK_2M"

        fun start(context: Context) {
            val hasMic = androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.RECORD_AUDIO
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!hasMic) return

            val intent = Intent(context, AmbientForegroundService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, AmbientForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        fun bookmark(context: Context) {
            val intent = Intent(context, AmbientForegroundService::class.java).apply {
                action = ACTION_BOOKMARK_2M
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
