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

    @Volatile
    private var currentBatteryPct: Int = 100

    private val presenceGate = PresenceGate(gateUserPresence = true)
    private val proposalExtractor = AmbientProposalExtractor()
    private val dialogueEngine = DialogueWindowEngine()
    private val recentTurns = mutableListOf<DialogueWindowEngine.Turn>()
    private val recentAudioSegments = java.util.Collections.synchronizedList(mutableListOf<PresenceGate.ScoredSegment>())
    private var currentSessionId: String? = null
    @Volatile
    private var sessionListenSeconds: Int = 0
    @Volatile
    private var sessionSpeechSeconds: Int = 0
    @Volatile
    private var sessionStartedAt: Long = 0L
    private var lastTickerTimeMs: Long = 0L
    private var listeningJob: Job? = null
    private var activeRecorder: AudioRecorderHelper? = null
    private val json = Json { ignoreUnknownKeys = true }
    private val extractionQueue = kotlinx.coroutines.channels.Channel<DialogueWindowEngine.Window>(
        capacity = 2,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )

    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent ?: return
            when (intent.action) {
                Intent.ACTION_BATTERY_CHANGED -> {
                    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                    val pct = if (level >= 0 && scale > 0) (level * 100) / scale else 100
                    currentBatteryPct = pct
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
        currentState = AmbientState.Sleeping(AmbientSleepReason.USER_PAUSED)
        AmbientStatusBus.publish(currentState)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, buildNotification(currentState), fgsType)
            } else {
                startForeground(NOTIFICATION_ID, buildNotification(currentState))
            }
        } catch (_: Throwable) {
            AmbientHeartbeatHelper.checkAndNotifyIfNeeded(this)
            stopSelf()
            return
        }

        serviceScope.launch(Dispatchers.Default) {
            for (window in extractionQueue) {
                runCatching { processWindowExtraction(window) }
            }
        }

        startStateTicker()
        serviceScope.launch { evaluateBlockers() }
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
                stopSelf()
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
            ACTION_REFRESH -> {
                serviceScope.launch { evaluateBlockers() }
                val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager
                manager?.notify(NOTIFICATION_ID, buildNotification(currentState))
            }
        }

        return START_NOT_STICKY
    }

    private fun startStateTicker() {
        tickerJob?.cancel()
        lastTickerTimeMs = System.currentTimeMillis()
        tickerJob = serviceScope.launch {
            while (isActive) {
                delay(10_000L) // Evaluate state & blockers every 10 seconds
                val now = System.currentTimeMillis()
                val deltaSec = ((now - lastTickerTimeMs) / 1000L).coerceAtLeast(0L).toInt()
                lastTickerTimeMs = now

                // P1-14: Increment and persist listen counters on wall-clock time
                if (currentState is AmbientState.Listening) {
                    sessionListenSeconds += deltaSec
                    val sId = currentSessionId ?: run {
                        val newId = "amb_sess_${UUID.randomUUID()}"
                        currentSessionId = newId
                        sessionStartedAt = now
                        newId
                    }
                    val app = application as? MineApp
                    if (app != null && app.sharedStorage.isCompleted) {
                        try {
                            val storage = app.sharedStorage.await()
                            storage.saveAmbientSession(
                                AmbientSession(
                                    id = sId,
                                    startedAt = sessionStartedAt,
                                    listenSeconds = sessionListenSeconds,
                                    speechSeconds = sessionSpeechSeconds,
                                    segmentsCount = recentAudioSegments.size
                                )
                            )
                        } catch (_: Throwable) {}
                    }
                }
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

        // 3. Hardware collision arbitration: in-app mic active
        if (AudioRecorderHelper.isForegroundMicActive) {
            updateState(AmbientState.Sleeping(AmbientSleepReason.MIC_BUSY))
            return
        }

        // 4. Check phone call active
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

        val app = application as? MineApp
        val storage = if (app != null && app.sharedStorage.isCompleted) {
            try { app.sharedStorage.await() } catch (_: Exception) { null }
        } else null

        // 5. Thermal pause
        val powerManager = getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
        val isThermalThrottling = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            (powerManager?.currentThermalStatus ?: 0) >= android.os.PowerManager.THERMAL_STATUS_SEVERE
        } else false
        if (isThermalThrottling || storage?.getSetting("ambient.thermal_pause") == "1") {
            updateState(AmbientState.Sleeping(AmbientSleepReason.THERMAL_PAUSE))
            return
        }

        // 6. Charging-only mode
        val chargingOnly = storage?.getSetting("ambient.charging_only") == "1"
        if (chargingOnly && !isCharging) {
            updateState(AmbientState.Sleeping(AmbientSleepReason.CHARGING_ONLY))
            return
        }

        // 7. Battery low check (< min_battery_pct and not charging)
        val minBatteryPct = storage?.getSetting("ambient.min_battery_pct")?.toIntOrNull() ?: 20
        if (currentBatteryPct < minBatteryPct && !isCharging) {
            updateState(AmbientState.Sleeping(AmbientSleepReason.BATTERY_LOW))
            return
        }

        // 8. Pause place check
        if (storage != null) {
            val currentPlaceId = storage.getSetting("place.current_id")
            if (currentPlaceId != null) {
                val place = storage.getPlaces().find { it.id == currentPlaceId }
                if (place != null && (place.kind.equals("pause", ignoreCase = true) || place.label.contains("pause", ignoreCase = true))) {
                    updateState(AmbientState.Sleeping(AmbientSleepReason.PAUSE_PLACE))
                    return
                }
            }
        }

        // 9. Daily cap reached
        if (storage != null) {
            val listenCapHours = storage.getSetting("ambient.daily_listen_cap_h")?.toIntOrNull()
                ?: (storage.getSetting("ambient.daily_cap_minutes")?.toIntOrNull()?.div(60) ?: 10)
            val sttCapMinutes = storage.getSetting("ambient.daily_stt_cap_min")?.toIntOrNull() ?: 90
            val dayRule = storage.getEffectiveDayRule(now)
            val dayKey = com.anchor.core.time.DayRollover.computeDayKey(now, dayRule)
            val todaySessions = storage.getRecentAmbientSessions(100).filter {
                com.anchor.core.time.DayRollover.computeDayKey(it.startedAt, dayRule) == dayKey
            }
            val todayListenSeconds = todaySessions.sumOf { it.listenSeconds.toLong() }
            val todaySpeechSeconds = todaySessions.sumOf { it.speechSeconds.toLong() }
            if (todayListenSeconds >= listenCapHours * 3600L || todaySpeechSeconds >= sttCapMinutes * 60L) {
                updateState(AmbientState.Sleeping(AmbientSleepReason.DAILY_CAP_REACHED))
                return
            }
        }

        // 10. Quiet hours / outside day window
        if (storage != null) {
            try {
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
            AmbientStatusBus.publish(newState)
            if (newState is AmbientState.Listening) {
                startListeningLoop()
            } else {
                stopListeningLoop()
                currentSessionId = null
            }
            val notification = buildNotification(newState)
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager
            manager?.notify(NOTIFICATION_ID, notification)
        }
    }

    private fun startListeningLoop() {
        if (listeningJob?.isActive == true) return
        listeningJob = serviceScope.launch(Dispatchers.IO) {
            val recorder = AudioRecorderHelper(isForegroundUserMic = false)
            activeRecorder = recorder
            val started = recorder.startRecording(this)
            if (!started) {
                activeRecorder = null
                return@launch
            }

            val sId = currentSessionId ?: run {
                val newSId = "amb_sess_${UUID.randomUUID()}"
                currentSessionId = newSId
                sessionStartedAt = System.currentTimeMillis()
                sessionListenSeconds = 0
                sessionSpeechSeconds = 0
                newSId
            }

            val pendingSegments = mutableListOf<PresenceGate.ScoredSegment>()
            var lastUserSpeechMs = 0L

            try {
                while (isActive && currentState is AmbientState.Listening) {
                    val sliceStart = System.currentTimeMillis()
                    delay(3000L) // 3-second continuous audio slice without tearing down AudioRecord
                    val samples = recorder.snapshotAndClear()
                    val sliceEnd = System.currentTimeMillis()

                    if (samples.isEmpty()) continue
                    // Half-duplex: never transcribe Mine's own voice (notification read-outs, replies).
                    if (AmbientStatusBus.overlapsOwnSpeech(sliceStart, sliceEnd)) continue

                    val voiceEngine = SherpaVoiceEngine.getInstance(applicationContext)

                    // 1. VAD check (zero allocation, in-memory)
                    val hasSpeech = (voiceEngine?.isSpeech(samples) ?: SyntheticAudioFixtures.isSpeechEnergy(samples, thresholdRms = 0.015f))
                    if (hasSpeech) sessionSpeechSeconds += 3

                    val app = application as? MineApp
                    if (app == null || !app.sharedStorage.isCompleted) continue
                    val storage = try { app.sharedStorage.await() } catch (_: Exception) { continue }

                    if (!hasSpeech) continue

                    // 2. Speaker Embedding & PresenceGate evaluation
                    val userVp = storage.getVoiceprint()
                    val userFloats = userVp?.toFloatArray()

                    val segmentEmb = voiceEngine?.extractSpeakerEmbedding(samples)
                        ?: SyntheticAudioFixtures.extractVoiceprintFromAudio(samples)

                    val scoredSegment = presenceGate.scoreSegment(
                        tStartMs = sliceStart,
                        tEndMs = sliceEnd,
                        samples = samples,
                        segmentEmbedding = segmentEmb,
                        voiceprintFloats = userFloats
                    )

                    pendingSegments.add(scoredSegment)
                    presenceGate.trimRingBuffer(pendingSegments, maxRingDurationMs = 90_000L, currentTimestampMs = sliceEnd)

                    synchronized(recentAudioSegments) {
                        recentAudioSegments.add(scoredSegment)
                        val cutoff = sliceEnd - 120_000L
                        recentAudioSegments.removeAll { it.tEndMs < cutoff }
                    }

                    // 3. Evaluate window gate per Blueprint 61 §3.4:
                    // Audio is sent to STT when user speaks or within 15s grace window for OTHER speaker
                    val userSpoke = pendingSegments.any { it.label == SpeakerLabel.USER }
                    if (userSpoke) {
                        lastUserSpeechMs = sliceEnd
                    }
                    val withinPostUserGrace = (lastUserSpeechMs > 0L && (sliceStart - lastUserSpeechMs) <= 15_000L)
                    val gateOpened = userSpoke || presenceGate.evaluateWindowGate(pendingSegments) || withinPostUserGrace
                    if (!gateOpened) {
                        // Keep accumulating up to 90s context in RAM ring buffer; do not burn battery running STT on stranger speech
                        continue
                    }

                    // 4. Presence gate open! Transcribe all pending segments in the dialogue window
                    val newTurns = mutableListOf<DialogueWindowEngine.Turn>()
                    for (seg in pendingSegments) {
                        val transcript = voiceEngine?.transcribe(seg.audioSamples)?.trim().orEmpty()
                        if (transcript.isNotBlank()) {
                            val scrubbedText = NumberScrubber.scrub(transcript)
                            val turn = DialogueWindowEngine.Turn(
                                segmentId = UUID.randomUUID().toString(),
                                tStartMs = seg.tStartMs,
                                tEndMs = seg.tEndMs,
                                label = seg.label,
                                text = scrubbedText,
                                score = seg.score,
                                isShort = seg.isShort
                            )
                            newTurns.add(turn)
                        }
                    }

                    // Reset pending audio segments since they are now transcribed
                    pendingSegments.clear()
                    if (newTurns.isEmpty()) continue

                    synchronized(recentTurns) {
                        recentTurns.addAll(newTurns)
                        val cutoff = sliceEnd - 120_000L
                        recentTurns.removeAll { it.tEndMs < cutoff }
                    }

                    // 5. Send newly transcribed turns to background extraction queue (decoupled from audio recording)
                    val liveWindow = DialogueWindowEngine.Window(
                        id = UUID.randomUUID().toString(),
                        turns = newTurns,
                        startedAtMs = newTurns.firstOrNull()?.tStartMs ?: sliceStart,
                        endedAtMs = sliceEnd
                    )
                    extractionQueue.trySend(liveWindow)

                    // Persist session progress every 60 seconds so daily caps (listen & speech caps) are accurately tracked
                    if (sessionListenSeconds % 60 == 0) {
                        try {
                            storage.saveAmbientSession(
                                AmbientSession(
                                    id = sId,
                                    startedAt = sessionStartedAt,
                                    listenSeconds = sessionListenSeconds,
                                    speechSeconds = sessionSpeechSeconds,
                                    segmentsCount = recentAudioSegments.size
                                )
                            )
                        } catch (_: Throwable) {}
                    }
                }
            } finally {
                recorder.stopRecording()
                activeRecorder = null
                // Save final session state on exit
                try {
                    val app = application as? MineApp
                    if (app != null && app.sharedStorage.isCompleted) {
                        val storage = app.sharedStorage.await()
                        storage.saveAmbientSession(
                            AmbientSession(
                                id = sId,
                                startedAt = sessionStartedAt,
                                listenSeconds = sessionListenSeconds,
                                speechSeconds = sessionSpeechSeconds,
                                segmentsCount = recentAudioSegments.size
                            )
                        )
                    }
                } catch (_: Throwable) {}
            }
        }
    }

    private suspend fun processWindowExtraction(liveWindow: DialogueWindowEngine.Window) {
        val app = application as? MineApp ?: return
        if (!app.sharedStorage.isCompleted) return
        val storage = try { app.sharedStorage.await() } catch (_: Throwable) { return }

        val existingNodes = storage.getActiveNodes(50)
        val llmResponseJson = try {
            val (sysPrompt, userPrompt) = dialogueEngine.buildExtractionPrompt(liveWindow)
            val prompt = """{"messages":[{"role":"system","content":${json.encodeToString(sysPrompt)}},{"role":"user","content":${json.encodeToString(userPrompt)}}]}"""
            val onDeviceEngine = com.anchor.mine.provider.AndroidOnDeviceLlmEngine(applicationContext)
            var rawText = ""
            onDeviceEngine.generateStream(prompt).collect { event ->
                if (event is com.anchor.core.provider.StreamEvent.TextDelta) {
                    rawText += event.text
                }
            }
            rawText.takeIf { it.isNotBlank() }
        } catch (_: Throwable) {
            null
        }

        val sliceEnd = liveWindow.endedAtMs
        val extracted = proposalExtractor.extract(
            window = liveWindow,
            llmResponseJson = llmResponseJson,
            existingNodes = existingNodes,
            nowMs = sliceEnd
        )

        val dayRule = storage.getEffectiveDayRule(sliceEnd)
        val currentDayKey = com.anchor.core.time.DayRollover.computeDayKey(sliceEnd, dayRule)
        val latestReport = storage.getLatestAmbientReport()?.takeIf { it.dayKey == currentDayKey }
        val existingSummaries = latestReport?.let {
            storage.getAmbientReportItems(it.id).map { item -> item.summary.trim().lowercase() }.toSet()
        } ?: emptySet()

        val newActionableProposals = extracted.filter {
            it.kind == "proposal" && it.proposedNode != null && it.summary.trim().lowercase() !in existingSummaries
        }

        val sId = currentSessionId ?: "amb_sess_${UUID.randomUUID()}"
        if (newActionableProposals.isNotEmpty()) {
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

            for (wTurn in liveWindow.turns) {
                storage.saveAmbientSegment(
                    AmbientSegment(
                        id = wTurn.segmentId,
                        windowId = liveWindow.id,
                        tStart = wTurn.tStartMs,
                        tEnd = wTurn.tEndMs,
                        label = wTurn.label.wire,
                        score = wTurn.score,
                        isShort = wTurn.isShort,
                        text = wTurn.text,
                        sttRef = "sherpa_offline",
                        expiresAt = sliceEnd + 48L * 3600 * 1000L
                    )
                )
            }

            val report = latestReport ?: run {
                val rep = AmbientReport(id = "amb_rep_${UUID.randomUUID()}", dayKey = currentDayKey, generatedAt = sliceEnd)
                storage.saveAmbientReport(rep)
                rep
            }

            for (item in newActionableProposals) {
                val pNode = item.proposedNode ?: continue
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

    private fun bookmarkLast2Minutes() {
        serviceScope.launch(Dispatchers.IO) {
            val now = System.currentTimeMillis()

            // Transcribe any buffered audio from the last 120s that hasn't been transcribed
            // (e.g. third-party speech where the presence gate stayed closed).
            val unTranscribed = synchronized(recentAudioSegments) {
                val cutoff = now - 120_000L
                recentAudioSegments.filter { it.tEndMs >= cutoff }
            }
            if (unTranscribed.isNotEmpty()) {
                val voiceEngine = SherpaVoiceEngine.getInstance(applicationContext)
                val existingStartTimes = synchronized(recentTurns) { recentTurns.map { it.tStartMs }.toSet() }
                val newlyTranscribed = mutableListOf<DialogueWindowEngine.Turn>()
                for (seg in unTranscribed) {
                    if (seg.tStartMs !in existingStartTimes) {
                        val transcript = voiceEngine?.transcribe(seg.audioSamples)?.trim().orEmpty()
                        if (transcript.isNotBlank()) {
                            val scrubbed = NumberScrubber.scrub(transcript)
                            newlyTranscribed.add(
                                DialogueWindowEngine.Turn(
                                    segmentId = UUID.randomUUID().toString(),
                                    tStartMs = seg.tStartMs,
                                    tEndMs = seg.tEndMs,
                                    label = seg.label,
                                    text = scrubbed,
                                    score = seg.score,
                                    isShort = seg.isShort
                                )
                            )
                        }
                    }
                }
                if (newlyTranscribed.isNotEmpty()) {
                    synchronized(recentTurns) {
                        recentTurns.addAll(newlyTranscribed)
                        val cutoff = now - 120_000L
                        recentTurns.removeAll { it.tEndMs < cutoff }
                    }
                }
            }

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
        val title = "Mine is listening"
        val readingNotifications = com.anchor.mine.notifications.NotificationReader.isReadingEnabledCached()
        val content = when (state) {
            is AmbientState.Listening -> if (readingNotifications) "Noting commitments · reading your notifications" else "Noting commitments as you talk"
            is AmbientState.Sleeping -> "Paused · ${state.reason.display}"
            is AmbientState.Processing -> "Making sense of what was said"
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
        AmbientStatusBus.publish(AmbientState.Off)
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
        AmbientStatusBus.publish(AmbientState.Off)
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
        const val ACTION_REFRESH = "com.anchor.mine.ambient.ACTION_REFRESH"

        /**
         * Re-evaluates blockers right away (e.g. the in-app mic was released) and re-posts the
         * status notification. A no-op when the service is not running.
         */
        fun refresh(context: Context) {
            if (!AmbientStatusBus.isRunning) return
            try {
                context.startService(Intent(context, AmbientForegroundService::class.java).apply { action = ACTION_REFRESH })
            } catch (_: Throwable) {
                // Background-start restrictions: the 10 s ticker will catch up.
            }
        }

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
            try {
                val intent = Intent(context, AmbientForegroundService::class.java).apply {
                    action = ACTION_STOP
                }
                context.startService(intent)
            } catch (_: Throwable) {
                try {
                    context.stopService(Intent(context, AmbientForegroundService::class.java))
                } catch (_: Throwable) {}
            }
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
