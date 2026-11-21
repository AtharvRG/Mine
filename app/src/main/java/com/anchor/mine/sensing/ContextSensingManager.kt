// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.mine.sensing

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import com.anchor.core.sensing.ContextEngine
import com.anchor.core.sensing.ContextTrigger
import com.anchor.core.sensing.MotionAxis
import com.anchor.core.sensing.OpportunisticErrandsEvaluator
import com.anchor.core.sensing.WalkCompanionEvaluator
import com.anchor.core.storage.Storage
import com.anchor.core.time.SystemClock
import com.anchor.mine.alarms.AndroidAlarmScheduler
import com.anchor.mine.notifications.ChatReplyNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Android Context Sensing Manager bridging system Wi-Fi and motion sensor signals
 * to the pure-Kotlin [ContextEngine] state machine (Blueprint 43 §3).
 *
 * Zero coordinates or precise location data are ever queried or retained.
 */
class ContextSensingManager(
    private val context: Context,
    val contextEngine: ContextEngine,
    private val storageProvider: suspend () -> Storage?
) {
    private val scope = CoroutineScope(Dispatchers.IO)
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var sensorListener: SensorEventListener? = null
    private var stepCounterListener: SensorEventListener? = null
    private var timeoutTickerJob: Job? = null
    private var cumulativeSteps: Long = 0L

    private val lifecycleMutex = Mutex()

    @Volatile
    private var isStarted = false

    @Volatile
    var lastStepTimeMs: Long = 0L

    fun start(force: Boolean = false) {
        scope.launch {
            lifecycleMutex.withLock {
                if (isStarted && !force) return@withLock
                if (!force) {
                    val storage = storageProvider()
                    if (storage != null) {
                        val flagSensing = storage.getSetting("flag.sensing") == "1"
                        val sensingEnabled = storage.getSetting("sensing.enabled") == "1"
                        if (!flagSensing || !sensingEnabled) {
                            Log.i(TAG, "Context sensing disabled in settings (flag.sensing=$flagSensing, sensing.enabled=$sensingEnabled); skipping sensor/network registration")
                            return@withLock
                        }
                    }
                }
                isStarted = true
                registerWifiObserver()
                registerMotionObserver()
                startTimeoutTicker()
                Log.i(TAG, "ContextSensingManager started")
            }
        }
    }

    fun checkAndApplySettings() {
        scope.launch {
            val storage = storageProvider()
            val flagSensing = storage?.getSetting("flag.sensing") == "1"
            val sensingEnabled = storage?.getSetting("sensing.enabled") == "1"
            if (flagSensing && sensingEnabled) {
                start(force = true)
            } else {
                stop()
            }
        }
    }

    fun stop() {
        scope.launch {
            lifecycleMutex.withLock {
                if (!isStarted) return@withLock
                isStarted = false

                unregisterWifiObserver()
                unregisterMotionObserver()
                timeoutTickerJob?.cancel()
                timeoutTickerJob = null
                Log.i(TAG, "ContextSensingManager stopped")
            }
        }
    }

    private fun registerWifiObserver() {
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()

            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    processWifiState(cm, network)
                }

                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                    processWifiState(cm, network)
                }

                override fun onLost(network: Network) {
                    scope.launch {
                        val storage = storageProvider()
                        val knownPlaces = storage?.getPlaceSignals() ?: emptyList()
                        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                        val scannedBssids = try {
                            wm?.scanResults?.mapNotNull { cleanBssid(it.BSSID) } ?: emptyList()
                        } catch (_: SecurityException) {
                            emptyList()
                        } catch (_: Throwable) {
                            emptyList()
                        }
                        val now = System.currentTimeMillis()
                        val triggers = contextEngine.onWifiSignal(
                            ssid = null,
                            bssids = scannedBssids,
                            knownPlaces = knownPlaces
                        )
                        handleTriggers(triggers, now)
                    }
                }
            }

            networkCallback = callback
            cm.registerNetworkCallback(request, callback)
        } catch (e: Throwable) {
            Log.w(TAG, "Could not register Wi-Fi network callback: ${e.message}")
        }
    }

    private fun unregisterWifiObserver() {
        try {
            val callback = networkCallback ?: return
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            cm?.unregisterNetworkCallback(callback)
            networkCallback = null
        } catch (e: Throwable) {
            Log.w(TAG, "Error unregistering Wi-Fi callback: ${e.message}")
        }
    }

    private fun processWifiState(cm: ConnectivityManager, network: Network) {
        scope.launch {
            try {
                var ssid: String? = null
                var bssid: String? = null

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val capabilities = cm.getNetworkCapabilities(network)
                    val wifiInfo = capabilities?.transportInfo as? WifiInfo
                    if (wifiInfo != null) {
                        ssid = cleanSsid(wifiInfo.ssid)
                        bssid = cleanBssid(wifiInfo.bssid)
                    }
                }

                if (ssid.isNullOrBlank()) {
                    val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                    val info = wm?.connectionInfo
                    if (info != null) {
                        ssid = cleanSsid(info.ssid)
                        bssid = cleanBssid(info.bssid)
                    }
                }

                val storage = storageProvider()
                val knownPlaces = storage?.getPlaceSignals() ?: emptyList()
                val bssids = if (!bssid.isNullOrBlank()) listOf(bssid) else emptyList()

                // If Wi-Fi is available/connected but SSID and BSSID were redacted (<unknown ssid>/02:00:00:00:00:00),
                // do not call onWifiSignal with null which would falsely mark the device as PlaceAxis.Away.
                if (ssid.isNullOrBlank() && bssids.isEmpty()) {
                    Log.d(TAG, "Wi-Fi is connected but SSID/BSSID are redacted; retaining place state")
                    return@launch
                }

                val now = System.currentTimeMillis()
                val triggers = contextEngine.onWifiSignal(
                    ssid = ssid,
                    bssids = bssids,
                    knownPlaces = knownPlaces
                )
                handleTriggers(triggers, now)
            } catch (e: Throwable) {
                Log.w(TAG, "Error processing Wi-Fi state: ${e.message}")
            }
        }
    }

    private fun cleanSsid(rawSsid: String?): String? {
        if (rawSsid.isNullOrBlank()) return null
        val cleaned = rawSsid.removeSurrounding("\"").trim()
        if (cleaned.equals("<unknown ssid>", ignoreCase = true) || cleaned.isEmpty()) return null
        return cleaned
    }

    private fun cleanBssid(rawBssid: String?): String? {
        if (rawBssid.isNullOrBlank()) return null
        val cleaned = rawBssid.trim().lowercase(java.util.Locale.US)
        if (cleaned == "02:00:00:00:00:00" || cleaned == "00:00:00:00:00:00") return null
        return rawBssid.trim()
    }

    private fun registerMotionObserver() {
        try {
            val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return

            // Cumulative step counter
            val stepCounter = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
            if (stepCounter != null) {
                val counterListener = object : SensorEventListener {
                    override fun onSensorChanged(event: SensorEvent?) {
                        val totalSteps = event?.values?.getOrNull(0)?.toLong() ?: return
                        val now = System.currentTimeMillis()
                        lastStepTimeMs = now
                        scope.launch {
                            val triggers1 = contextEngine.onMotionSignal(MotionAxis.WALKING, confidence = 0.9f)
                            val triggers2 = contextEngine.onStepCountSignal(totalSteps, now)
                            handleTriggers(triggers1 + triggers2, now)
                        }
                    }
                    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
                }
                stepCounterListener = counterListener
                sm.registerListener(counterListener, stepCounter, SensorManager.SENSOR_DELAY_NORMAL)
            } else {
                // Fallback to step detector / significant motion only if step counter is absent
                val stepSensor = sm.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
                    ?: sm.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)

                if (stepSensor != null) {
                    val listener = object : SensorEventListener {
                        override fun onSensorChanged(event: SensorEvent?) {
                            val now = System.currentTimeMillis()
                            lastStepTimeMs = now
                            cumulativeSteps++
                            scope.launch {
                                val triggers1 = contextEngine.onMotionSignal(MotionAxis.WALKING, confidence = 0.9f)
                                val triggers2 = contextEngine.onStepCountSignal(cumulativeSteps, now)
                                handleTriggers(triggers1 + triggers2, now)
                            }
                        }

                        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
                    }
                    sensorListener = listener
                    sm.registerListener(listener, stepSensor, SensorManager.SENSOR_DELAY_NORMAL)
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Could not register motion sensor listener: ${e.message}")
        }
    }

    private fun unregisterMotionObserver() {
        try {
            val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            sensorListener?.let { sm?.unregisterListener(it) }
            sensorListener = null
            stepCounterListener?.let { sm?.unregisterListener(it) }
            stepCounterListener = null
        } catch (e: Throwable) {
            Log.w(TAG, "Error unregistering sensor listener: ${e.message}")
        }
    }

    private fun startTimeoutTicker() {
        timeoutTickerJob?.cancel()
        timeoutTickerJob = scope.launch {
            while (isActive) {
                delay(30_000L) // Poll state machine debounce timeouts every 30s
                try {
                    val now = System.currentTimeMillis()
                    // Return to STILL if no steps have been detected for > 60 seconds
                    if (lastStepTimeMs > 0L && now - lastStepTimeMs > 60_000L && contextEngine.getState().motion == MotionAxis.WALKING) {
                        val motionTriggers = contextEngine.onMotionSignal(MotionAxis.STILL, confidence = 0.8f)
                        handleTriggers(motionTriggers, now)
                    }
                    val timeoutTriggers = contextEngine.checkTimeouts()
                    handleTriggers(timeoutTriggers, now)
                } catch (e: Throwable) {
                    Log.w(TAG, "Error running contextEngine.checkTimeouts: ${e.message}")
                }
            }
        }
    }

    private suspend fun handleTriggers(triggers: List<ContextTrigger>, now: Long) {
        if (triggers.isEmpty()) return
        val storage = storageProvider() ?: return

        val dayRule = storage.getEffectiveDayRule(now)
        if (!com.anchor.core.time.DayRollover.isInDayWindow(now, dayRule)) return
        if (contextEngine.getState().motion == MotionAxis.VEHICLE) return

        for (trigger in triggers) {
            when (trigger) {
                ContextTrigger.CONTEXT_LEFT_HOME -> {
                    try {
                        val clock = SystemClock()
                        val errands = OpportunisticErrandsEvaluator(storage, clock).evaluateLeaveHome(now)
                        // Silence on empty per D-087 / BP 43 §3.5: If no relevant errands, do NOT disturb user
                        if (errands.isEmpty()) continue

                        val errandChecklists = errands.joinToString("\n\n") { it.message }
                        val logicalDayStart = com.anchor.core.time.DayRollover.getLogicalDayStart(now, dayRule)
                        val windowLengthMs = ((dayRule.endMinute - dayRule.startMinute + 1440) % 1440) * 60_000L
                        val todayEnd = logicalDayStart + windowLengthMs
                        val upcomingTasks = storage.getNodesDueBetween(now, todayEnd, 5).filter { it.status == "active" }
                        val nextTask = upcomingTasks.minByOrNull { it.dueAt ?: Long.MAX_VALUE }
                        val due = nextTask?.dueAt
                        val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())

                        val finalMessage = if (nextTask != null && due != null) {
                            val nextTime = timeFormat.format(Date(due))
                            "You just left home. Don't forget ${errands.first().title}, and be back by $nextTime for '${nextTask.title}'.\n\n$errandChecklists"
                        } else {
                            "You just left home.\n\n$errandChecklists"
                        }

                        com.anchor.mine.notifications.ProactiveMessages.post(context, storage, finalMessage, source = "place")

                        // Cancel fallback wakeups and clear context trigger so it doesn't fire again
                        val scheduler = AndroidAlarmScheduler(context)
                        for (errand in errands) {
                            val fallbackWakeupId = "aw_errand_${errand.nodeId}"
                            scheduler.cancelAgentWakeup(fallbackWakeupId)
                            storage.markAgentWakeupState(fallbackWakeupId, "cancelled")
                            val node = storage.getNode(errand.nodeId)
                            if (node != null && node.contextTrigger != null) {
                                val updated = node.copy(contextTrigger = null, updatedAt = now)
                                val uId = "u_${java.util.UUID.randomUUID()}"
                                val event = com.anchor.core.models.RawEvent(uId, "UPDATE", "node", node.id, beforeJson = null, afterJson = null, tsUtc = now)
                                storage.updateNodeAndCommit(updated, event, deleteReminderId = null)
                            }
                        }
                    } catch (e: Throwable) {
                        Log.w(TAG, "Error handling CONTEXT_LEFT_HOME trigger: ${e.message}")
                    }
                }
                ContextTrigger.CONTEXT_WALKING_STARTED -> {
                    try {
                        val clock = SystemClock()
                        val walk = WalkCompanionEvaluator(storage, clock, AndroidAlarmScheduler(context)).evaluateWalkStarted(now)
                        val logicalDayStart = com.anchor.core.time.DayRollover.getLogicalDayStart(now, dayRule)
                        val windowLengthMs = ((dayRule.endMinute - dayRule.startMinute + 1440) % 1440) * 60_000L
                        val todayEnd = logicalDayStart + windowLengthMs
                        val upcomingTasks = storage.getNodesDueBetween(now, todayEnd, 5).filter { it.status == "active" }
                        val nextTask = upcomingTasks.minByOrNull { it.dueAt ?: Long.MAX_VALUE }
                        val due = nextTask?.dueAt
                        val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())

                        val finalMessage = if (nextTask != null && due != null) {
                            val nextTime = timeFormat.format(Date(due))
                            "Have a great walk! Be back by $nextTime for '${nextTask.title}'."
                        } else {
                            walk.message
                        }

                        com.anchor.mine.notifications.ProactiveMessages.post(context, storage, finalMessage, source = "place")
                    } catch (e: Throwable) {
                        Log.w(TAG, "Error handling CONTEXT_WALKING_STARTED trigger: ${e.message}")
                    }
                }
                ContextTrigger.CONTEXT_HOME_AGAIN -> {
                    try {
                        val pendingWakeups = storage.getPendingAgentWakeups(now + 2 * 3600_000L)
                            .filter { it.payloadJson?.contains("WALK_RETURN_CHECKIN") == true }
                        if (pendingWakeups.isNotEmpty()) {
                            val scheduler = AndroidAlarmScheduler(context)
                            pendingWakeups.forEach { w ->
                                scheduler.cancelAgentWakeup(w.id)
                                storage.markAgentWakeupState(w.id, "cancelled")
                            }
                            val welcomeMsg = "Welcome back home! Hope your walk was refreshing."
                            com.anchor.mine.notifications.ProactiveMessages.post(context, storage, welcomeMsg, source = "place")
                        }
                    } catch (e: Throwable) {
                        Log.w(TAG, "Error handling CONTEXT_HOME_AGAIN trigger: ${e.message}")
                    }
                }
                else -> {}
            }
        }
    }

    fun enrollCurrentWifiAsHome(): Boolean {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return false
        val info = wm.connectionInfo ?: return false
        val ssid = cleanSsid(info.ssid)
        val bssid = cleanBssid(info.bssid)
        if (ssid.isNullOrBlank() && bssid.isNullOrBlank()) return false
        val scannedBssids = try {
            wm.scanResults?.mapNotNull { cleanBssid(it.BSSID) } ?: emptyList()
        } catch (_: Throwable) { emptyList() }
        val allBssids = (listOfNotNull(bssid) + scannedBssids).distinct()
        val now = System.currentTimeMillis()
        val place = com.anchor.core.sensing.Place(
            id = "place_home",
            kind = "home",
            label = "Home",
            updatedAt = now
        )
        val bssidsJson = allBssids.joinToString(prefix = "[", postfix = "]", separator = ",") { "\"$it\"" }
        val wifiJson = """{"ssid":"${ssid ?: ""}","bssids":$bssidsJson}"""
        val signal = com.anchor.core.sensing.PlaceSignal(
            placeId = "place_home",
            wifiJson = wifiJson
        )
        scope.launch {
            storageProvider()?.savePlace(place, signal)
        }
        return true
    }

    companion object {
        private const val TAG = "ContextSensingManager"
    }
}
