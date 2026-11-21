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
import com.anchor.core.ambient.AmbientPipeline
import com.anchor.core.ambient.AmbientSttMode
import com.anchor.core.ambient.AmbientSttPlanner
import com.anchor.core.ambient.EnergySegmenter
import com.anchor.core.ambient.SpeechSegmenter
import com.anchor.core.ambient.SttPlan
import com.anchor.core.ambient.SttRoute
import com.anchor.core.provider.KeyPool
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
                                    segmentsCount = pipeline?.stats?.let { it.userSegments + it.otherSegments } ?: 0
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

        // 11. No speech-to-text path: stay "Listening" so notification reading keeps working, but
        // the loop never opens the mic (audio is never kept to wait for STT, 61 §3.2).
        val sttNowReady = currentPlan(storage).route != SttRoute.NONE
        if (sttNowReady != sttReady) {
            sttReady = sttNowReady
            (getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager)?.notify(NOTIFICATION_ID, buildNotification(currentState))
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

    // =========================================================================================
    // Listening loop: mic → VAD segments → speaker check → pipeline (ring, window, gate) → STT
    // =========================================================================================

    /** Settings the loop reads when it starts. A change applies at the next Listening. */
    private data class ListenConfig(
        val mode: AmbientSttMode,
        val nimKeys: List<String>,
        val nimFunctionId: String,
        val language: String,
        val tLo: Float,
        val tHi: Float,
        val gateUserPresence: Boolean,
        val windowGapMs: Long,
        val batchMs: Long,
        val cloudCapSeconds: Long,
        val extractWithAi: Boolean,
    )

    private suspend fun loadListenConfig(storage: com.anchor.core.storage.Storage?): ListenConfig {
        val get: suspend (String) -> String? = { k -> try { storage?.getSetting(k) } catch (_: Exception) { null } }
        return ListenConfig(
            mode = AmbientSttMode.fromWire(get(AmbientSttMode.SETTING)),
            nimKeys = nimKeys(storage),
            nimFunctionId = get(KEY_NIM_FUNCTION)?.takeIf { it.isNotBlank() } ?: NimWhisperClient.DEFAULT_FUNCTION_ID,
            language = get(KEY_STT_LANGUAGE)?.takeIf { it.isNotBlank() } ?: "en",
            tLo = get("ambient.t_lo")?.toFloatOrNull()?.coerceIn(0.3f, 0.9f) ?: 0.60f,
            tHi = get("ambient.t_hi")?.toFloatOrNull()?.coerceIn(0.4f, 0.95f) ?: 0.80f,
            gateUserPresence = get("ambient.gate_user_presence") != "0",
            windowGapMs = (get("ambient.window_gap_s")?.toLongOrNull() ?: 90L).coerceIn(10L, 600L) * 1000L,
            batchMs = (get("ambient.batch_s")?.toLongOrNull() ?: 30L).coerceIn(5L, 60L) * 1000L,
            cloudCapSeconds = (get("ambient.daily_stt_cap_min")?.toLongOrNull() ?: 90L) * 60L,
            extractWithAi = get(KEY_EXTRACT_WITH_AI) == "1",
        )
    }

    /** Ambient's own NVIDIA keys, or the chat provider's when that provider is NVIDIA NIM. */
    private suspend fun nimKeys(storage: com.anchor.core.storage.Storage?): List<String> {
        if (storage == null) return emptyList()
        val own = KeyPool.decode(try { storage.getSetting(KEY_NIM_KEYS) } catch (_: Exception) { null })
        if (own.isNotEmpty()) return own
        val ep = try { storage.getSetting("ep") } catch (_: Exception) { null }.orEmpty()
        val host = runCatching { java.net.URI(ep).host.orEmpty() }.getOrDefault("")
        if (!host.endsWith("api.nvidia.com")) return emptyList()
        val main = try { storage.getSetting("key") } catch (_: Exception) { null }.orEmpty()
        val extra = KeyPool.decode(try { storage.getSetting(com.anchor.core.ui.ChatController.KEY_EXTRA_KEYS) } catch (_: Exception) { null })
        return KeyPool.sanitize(listOf(main) + extra)
    }

    /** The chat engine's limiter when the graph is up, so NVIDIA keys share one budget across chat and Whisper. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun sharedLimiter(app: MineApp): com.anchor.core.provider.RateLimiter =
        app.appGraph.takeIf { it.isCompleted && it.getCompletionExceptionOrNull() == null }?.getCompleted()?.rateLimiter ?: app.rateLimiter

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun sharedOrchestrator(app: MineApp): com.anchor.core.provider.ProviderOrchestrator =
        app.appGraph.takeIf { it.isCompleted && it.getCompletionExceptionOrNull() == null }?.getCompleted()?.orchestrator ?: app.orchestrator

    private fun isOnline(): Boolean = try {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
        caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
    } catch (_: Throwable) { false }

    private suspend fun cloudSecondsToday(storage: com.anchor.core.storage.Storage?, dayKey: String): Long {
        val raw = try { storage?.getSetting(KEY_CLOUD_SECONDS) } catch (_: Exception) { null } ?: return 0L
        val (day, secs) = raw.split(':', limit = 2).let { (it.getOrNull(0) ?: "") to (it.getOrNull(1)?.toLongOrNull() ?: 0L) }
        return if (day == dayKey) secs else 0L
    }

    private suspend fun addCloudSeconds(storage: com.anchor.core.storage.Storage, seconds: Long) {
        val now = System.currentTimeMillis()
        val dayKey = com.anchor.core.time.DayRollover.computeDayKey(now, storage.getEffectiveDayRule(now))
        val total = cloudSecondsToday(storage, dayKey) + seconds
        try { storage.saveSetting(KEY_CLOUD_SECONDS, "$dayKey:$total") } catch (_: Exception) {}
    }

    /** The STT route for right now, published so the Listening screen can show it and its warning. */
    private suspend fun currentPlan(storage: com.anchor.core.storage.Storage?): SttPlan {
        val cfg = loadListenConfig(storage)
        val now = System.currentTimeMillis()
        val dayKey = storage?.let { com.anchor.core.time.DayRollover.computeDayKey(now, it.getEffectiveDayRule(now)) } ?: ""
        val deviceReady = SherpaVoiceEngine.getInstance(applicationContext)?.isSttAvailable == true
        val plan = AmbientSttPlanner.plan(cfg.mode, deviceReady, cfg.nimKeys.size, isOnline(), cloudSecondsToday(storage, dayKey), cfg.cloudCapSeconds)
        AmbientStatusBus.publishPipeline(
            AmbientStatusBus.pipeline.value.copy(
                sttRoute = plan.route.name.lowercase(), sttNote = plan.note, sttWarning = plan.warning,
            )
        )
        return plan
    }

    private val pipelineLock = Any()
    @Volatile private var pipeline: AmbientPipeline? = null
    private val transcribeQueue = kotlinx.coroutines.channels.Channel<AmbientPipeline.ReadyWindow>(
        capacity = 4,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )
    private val windowSttRef = java.util.concurrent.ConcurrentHashMap<String, String>()
    @Volatile private var transcriberStarted = false

    @Volatile private var sttReady = true

    private fun startListeningLoop() {
        if (listeningJob?.isActive == true) return
        startTranscriberOnce()
        listeningJob = serviceScope.launch(Dispatchers.IO) {
            // Without any STT route the mic stays closed; re-check every 30 s (a model download or
            // a new NVIDIA key brings it up without restarting the service).
            while (isActive && currentState is AmbientState.Listening) {
                val st = (application as? MineApp)?.let { a -> if (a.sharedStorage.isCompleted) runCatching { a.sharedStorage.await() }.getOrNull() else null }
                if (currentPlan(st).route != SttRoute.NONE) break
                sttReady = false
                delay(30_000L)
            }
            if (!isActive || currentState !is AmbientState.Listening) return@launch
            sttReady = true
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

            val app = application as? MineApp
            val storage0 = if (app != null && app.sharedStorage.isCompleted) runCatching { app.sharedStorage.await() }.getOrNull() else null
            val cfg = loadListenConfig(storage0)
            val engine = SherpaVoiceEngine.getInstance(applicationContext)
            val silero = engine?.newSegmenter()
            val segmenter: SpeechSegmenter = silero ?: EnergySegmenter()
            val gate = PresenceGate(tHi = cfg.tHi, tLo = cfg.tLo, gateUserPresence = cfg.gateUserPresence)
            val pipe = AmbientPipeline(
                AmbientPipeline.Config(
                    ringMs = 120_000L,
                    windowGapMs = cfg.windowGapMs,
                    batchMs = cfg.batchMs,
                    tLo = cfg.tLo,
                    gateUserPresence = cfg.gateUserPresence,
                )
            )
            synchronized(pipelineLock) { pipeline = pipe }

            var userPrint: FloatArray? = storage0?.let { runCatching { it.getVoiceprint() }.getOrNull() }?.toFloatArray()
            var printLoadedAt = System.currentTimeMillis()
            // Sample index → wall clock, so a segment's start (counted in samples) gets a real time.
            val anchors = ArrayDeque<Pair<Long, Long>>()
            var fed = 0L
            var lastPersistAt = System.currentTimeMillis()

            fun wallOf(sample: Long): Long {
                val a = anchors.lastOrNull { it.first <= sample } ?: anchors.firstOrNull() ?: return System.currentTimeMillis()
                return a.second + (sample - a.first) * 1000L / 16_000L
            }

            fun hand(windows: List<AmbientPipeline.ReadyWindow>) {
                for (w in windows) transcribeQueue.trySend(w)
            }

            fun absorb(segs: List<com.anchor.core.ambient.VadSegment>) {
                for (seg in segs) {
                    if (seg.durationMs < 300) continue // clicks and bumps
                    sessionSpeechSeconds += (seg.durationMs / 1000L).toInt().coerceAtLeast(1)
                    val t0 = wallOf(seg.startSample)
                    val t1 = t0 + seg.durationMs
                    // Speaker check per whole utterance: an x-vector needs ~0.7 s of voice to mean anything.
                    val emb = if (seg.durationMs >= 700 && userPrint != null) {
                        runCatching { engine?.extractSpeakerEmbedding(seg.samples) }.getOrNull()
                            ?: SyntheticAudioFixtures.extractVoiceprintFromAudio(seg.samples)
                    } else null
                    val scored = gate.scoreSegment(t0, t1, seg.samples, emb, userPrint)
                    val ready = synchronized(pipelineLock) {
                        pipe.add(
                            AmbientPipeline.Segment(
                                id = UUID.randomUUID().toString(),
                                tStartMs = t0, tEndMs = t1, samples = seg.samples,
                                label = scored.label, score = scored.score, isShort = scored.isShort,
                            )
                        )
                    }
                    publishStats(pipe, scored.score.takeIf { emb != null }, scored.label.wire)
                    hand(ready)
                }
            }

            try {
                while (isActive && currentState is AmbientState.Listening) {
                    delay(250L)
                    val now = System.currentTimeMillis()
                    val chunk = recorder.snapshotAndClear()
                    if (chunk.isNotEmpty()) {
                        val chunkStart = now - chunk.size * 1000L / 16_000L
                        anchors.addLast(fed to chunkStart)
                        while (anchors.size > 600) anchors.removeFirst() // ~150 s of anchors
                        // Half-duplex: Mine's own voice becomes silence, so the timeline stays whole.
                        val audio = if (AmbientStatusBus.overlapsOwnSpeech(chunkStart, now)) FloatArray(chunk.size) else chunk
                        segmenter.accept(audio)
                        fed += audio.size
                        absorb(segmenter.drain())
                    }
                    hand(synchronized(pipelineLock) { pipe.tick(now) })

                    // A re-enrolment mid-session takes effect within five minutes.
                    if (now - printLoadedAt > 5 * 60_000L) {
                        printLoadedAt = now
                        val st = if (app != null && app.sharedStorage.isCompleted) runCatching { app.sharedStorage.await() }.getOrNull() else null
                        st?.let { s -> runCatching { s.getVoiceprint() }.getOrNull()?.let { userPrint = it.toFloatArray() } }
                    }

                    if (now - lastPersistAt >= 60_000L && app != null && app.sharedStorage.isCompleted) {
                        lastPersistAt = now
                        try {
                            app.sharedStorage.await().saveAmbientSession(
                                AmbientSession(sId, sessionStartedAt, listenSeconds = sessionListenSeconds, speechSeconds = sessionSpeechSeconds, segmentsCount = pipe.stats.userSegments + pipe.stats.otherSegments)
                            )
                        } catch (_: Throwable) {}
                    }
                }
            } finally {
                // You pressed Pause or Stop: what was being said is dropped from RAM, untranscribed.
                // Anything else (a call started, battery, the window closed): finish that conversation.
                val userStopped = currentState is AmbientState.Off ||
                    (currentState as? AmbientState.Sleeping)?.reason == AmbientSleepReason.USER_PAUSED
                if (userStopped) {
                    synchronized(pipelineLock) { pipe.clear() }
                } else {
                    runCatching { segmenter.flush(); absorb(segmenter.drain()) }
                    hand(synchronized(pipelineLock) { pipe.flush() })
                }
                silero?.close()
                recorder.stopRecording()
                activeRecorder = null
                try {
                    if (app != null && app.sharedStorage.isCompleted) withContext(kotlinx.coroutines.NonCancellable) {
                        app.sharedStorage.await().saveAmbientSession(
                            AmbientSession(sId, sessionStartedAt, listenSeconds = sessionListenSeconds, speechSeconds = sessionSpeechSeconds, segmentsCount = pipe.stats.userSegments + pipe.stats.otherSegments)
                        )
                    }
                } catch (_: Throwable) {}
            }
        }
    }

    private fun publishStats(pipe: AmbientPipeline, score: Float?, label: String?) {
        val st = pipe.stats
        val cur = AmbientStatusBus.pipeline.value
        AmbientStatusBus.publishPipeline(
            cur.copy(
                userSegments = st.userSegments, otherSegments = st.otherSegments,
                windowsTranscribed = st.windowsTranscribed, windowsDropped = st.windowsDropped,
                lastScore = score ?: cur.lastScore, lastLabel = label ?: cur.lastLabel,
            )
        )
    }

    /** One worker turns gated windows into text, so slow STT never stalls the microphone loop. */
    private fun startTranscriberOnce() {
        if (transcriberStarted) return
        transcriberStarted = true
        serviceScope.launch(Dispatchers.IO) {
            for (w in transcribeQueue) {
                runCatching {
                    val turns = transcribeWindow(w)
                    if (turns.isEmpty()) return@runCatching
                    synchronized(recentTurns) {
                        recentTurns.addAll(turns)
                        val cutoff = System.currentTimeMillis() - 120_000L
                        recentTurns.removeAll { it.tEndMs < cutoff }
                    }
                    extractionQueue.trySend(
                        DialogueWindowEngine.Window(id = w.id, turns = turns, startedAtMs = w.startedAtMs, endedAtMs = w.endedAtMs)
                    )
                }
            }
        }
    }

    /**
     * Transcribes one window by the current route. Cloud: one request per speaker run (fewer
     * requests, NIM keys rotated). Phone: one pass per segment. A cloud failure falls back to the
     * phone when its model is installed; otherwise the window is dropped (audio is never stored
     * to wait for later).
     */
    private suspend fun transcribeWindow(w: AmbientPipeline.ReadyWindow): List<DialogueWindowEngine.Turn> {
        val app = application as? MineApp ?: return emptyList()
        val storage = if (app.sharedStorage.isCompleted) runCatching { app.sharedStorage.await() }.getOrNull() else null
        val plan = currentPlan(storage)
        val engine = SherpaVoiceEngine.getInstance(applicationContext)

        suspend fun onDevice(): List<DialogueWindowEngine.Turn> {
            val e = engine?.takeIf { it.isSttAvailable } ?: return emptyList()
            windowSttRef[w.id] = "sherpa_offline"
            return w.segments.mapNotNull { seg ->
                val text = runCatching { e.transcribe(seg.samples) }.getOrNull()?.trim().orEmpty()
                if (text.isBlank()) null
                else DialogueWindowEngine.Turn(seg.id, seg.tStartMs, seg.tEndMs, seg.label, NumberScrubber.scrub(text), seg.score, seg.isShort)
            }
        }

        return when (plan.route) {
            SttRoute.NONE -> emptyList()
            SttRoute.DEVICE -> onDevice()
            SttRoute.NIM_WHISPER -> {
                val cfg = loadListenConfig(storage)
                val client = NimWhisperClient(sharedLimiter(app), functionId = cfg.nimFunctionId)
                val out = ArrayList<DialogueWindowEngine.Turn>()
                var fellBack = false
                for (b in w.batches) {
                    val audio = b.joinedSamples()
                    when (val r = client.transcribe(audio, cfg.nimKeys, cfg.language)) {
                        is NimWhisperClient.Result.Text -> {
                            storage?.let { addCloudSeconds(it, audio.size / 16_000L) }
                            if (r.transcript.isNotBlank()) {
                                out += DialogueWindowEngine.Turn(
                                    b.segments.first().id, b.tStartMs, b.tEndMs, b.label,
                                    NumberScrubber.scrub(r.transcript), b.score, b.isShort,
                                )
                            }
                        }
                        else -> {
                            val reason = when (r) {
                                NimWhisperClient.Result.AllKeysBusy -> "All NVIDIA keys are rate-limited — using the phone"
                                is NimWhisperClient.Result.AuthFailed -> "NVIDIA rejected the key — check it in Listening settings"
                                is NimWhisperClient.Result.Failed -> "Whisper failed (${r.message}) — using the phone"
                                else -> null
                            }
                            AmbientStatusBus.publishPipeline(AmbientStatusBus.pipeline.value.copy(sttNote = reason))
                            fellBack = true
                            break
                        }
                    }
                }
                if (fellBack) onDevice() else { windowSttRef[w.id] = "nim_whisper"; out }
            }
        }
    }

    /** Extraction prompt for a cloud model: same rules as on-device, streamed SSE like chat. */
    private suspend fun extractWithProvider(storage: com.anchor.core.storage.Storage, sys: String, user: String): String? {
        val app = application as? MineApp ?: return null
        val ep = storage.getSetting("ep")?.takeIf { it.isNotBlank() } ?: return null
        val key = storage.getSetting("key")?.takeIf { it.isNotBlank() } ?: return null
        val model = storage.getSetting("mod").orEmpty()
        val extra = KeyPool.decode(storage.getSetting(com.anchor.core.ui.ChatController.KEY_EXTRA_KEYS))
        val rpm = storage.getSetting("provider.rpm_default")?.toIntOrNull() ?: 20
        val targets = KeyPool.targets(ep, listOf(key) + extra, model, rpm, sharedLimiter(app), limiterPrefix = "bg:")
        val body = """{"messages":[{"role":"system","content":${json.encodeToString(sys)}},{"role":"user","content":${json.encodeToString(user)}}],"stream":true,"temperature":0.2}"""
        val sb = StringBuilder()
        return try {
            sharedOrchestrator(app).chat(targets, body, envelopeMode = true).collect { ev ->
                when (ev) {
                    is com.anchor.core.provider.StreamEvent.TextDelta -> sb.append(ev.text)
                    is com.anchor.core.provider.StreamEvent.Clear -> sb.clear()
                    else -> Unit
                }
            }
            sb.toString().takeIf { it.isNotBlank() }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Throwable) {
            null
        }
    }

    private suspend fun processWindowExtraction(liveWindow: DialogueWindowEngine.Window) {
        val app = application as? MineApp ?: return
        if (!app.sharedStorage.isCompleted) return
        val storage = try { app.sharedStorage.await() } catch (_: Throwable) { return }

        val existingNodes = storage.getActiveNodes(50)
        val (sysPrompt, userPrompt) = dialogueEngine.buildExtractionPrompt(liveWindow)
        val llmResponseJson = if (storage.getSetting(KEY_EXTRACT_WITH_AI) == "1" && isOnline()) {
            extractWithProvider(storage, sysPrompt, userPrompt)
        } else try {
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
        val sttRef = windowSttRef.remove(liveWindow.id) ?: "sherpa_offline"
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
                        sttRef = sttRef,
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

            // Everything heard in the last two minutes, gated or not: the user asked for it.
            val heard = synchronized(pipelineLock) { pipeline?.recent(now, 120_000L).orEmpty() }
            val existingStarts = synchronized(recentTurns) { recentTurns.map { it.tStartMs }.toSet() }
            val missing = heard.filter { it.tStartMs !in existingStarts }
            if (missing.isNotEmpty()) {
                val bookmarkWin = AmbientPipeline.ReadyWindow(
                    id = UUID.randomUUID().toString(),
                    segments = missing,
                    batches = synchronized(pipelineLock) { pipeline?.batchRuns(missing) } ?: emptyList(),
                    startedAtMs = missing.first().tStartMs,
                    endedAtMs = missing.last().tEndMs,
                )
                val turns = runCatching { transcribeWindow(bookmarkWin) }.getOrDefault(emptyList())
                windowSttRef.remove(bookmarkWin.id)
                if (turns.isNotEmpty()) {
                    synchronized(recentTurns) {
                        recentTurns.addAll(turns)
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
        synchronized(pipelineLock) { pipeline?.clear(); pipeline = null }
        try {
            activeRecorder?.cancelRecording()
        } catch (_: Exception) {}
        activeRecorder = null
    }

    private fun buildNotification(state: AmbientState): Notification {
        val title = "Mine is listening"
        val readingNotifications = com.anchor.mine.notifications.NotificationReader.isReadingEnabledCached()
        val content = when (state) {
            is AmbientState.Listening -> when {
                !sttReady && readingNotifications -> "Reading your notifications · speech-to-text not set up"
                !sttReady -> "Waiting for a speech-to-text model or NVIDIA key"
                readingNotifications -> "Noting commitments · reading your notifications"
                else -> "Noting commitments as you talk"
            }
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

        /** NVIDIA keys for Whisper, one per line. Empty = reuse the chat provider's keys when it is NIM. */
        const val KEY_NIM_KEYS = "ambient.stt.nim_keys"
        const val KEY_NIM_FUNCTION = "ambient.stt.nim_function_id"
        const val KEY_STT_LANGUAGE = "ambient.stt.language"
        const val KEY_CLOUD_SECONDS = "ambient.stt.cloud_seconds_today"
        /** "1" = send transcripts of gated windows to the chat provider for extraction. */
        const val KEY_EXTRACT_WITH_AI = "ambient.extract_with_ai"

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
