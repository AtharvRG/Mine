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
import com.anchor.core.sensing.MotionAxis
import com.anchor.core.storage.Storage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

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
    private var timeoutTickerJob: Job? = null

    @Volatile
    private var isStarted = false

    fun start() {
        if (isStarted) return
        isStarted = true

        registerWifiObserver()
        registerMotionObserver()
        startTimeoutTicker()
        Log.i(TAG, "ContextSensingManager started")
    }

    fun stop() {
        if (!isStarted) return
        isStarted = false

        unregisterWifiObserver()
        unregisterMotionObserver()
        timeoutTickerJob?.cancel()
        timeoutTickerJob = null
        Log.i(TAG, "ContextSensingManager stopped")
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
                        contextEngine.onWifiSignal(
                            ssid = null,
                            bssids = emptyList(),
                            knownPlaces = knownPlaces
                        )
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
                        bssid = wifiInfo.bssid
                    }
                }

                if (ssid.isNullOrBlank()) {
                    val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                    val info = wm?.connectionInfo
                    if (info != null) {
                        ssid = cleanSsid(info.ssid)
                        bssid = info.bssid
                    }
                }

                val storage = storageProvider()
                val knownPlaces = storage?.getPlaceSignals() ?: emptyList()
                val bssids = if (!bssid.isNullOrBlank()) listOf(bssid) else emptyList()

                contextEngine.onWifiSignal(
                    ssid = ssid,
                    bssids = bssids,
                    knownPlaces = knownPlaces
                )
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

    private fun registerMotionObserver() {
        try {
            val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return
            val stepSensor = sm.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
                ?: sm.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)

            if (stepSensor != null) {
                val listener = object : SensorEventListener {
                    private var lastStepTimeMs = 0L

                    override fun onSensorChanged(event: SensorEvent?) {
                        val now = System.currentTimeMillis()
                        lastStepTimeMs = now
                        contextEngine.onMotionSignal(MotionAxis.WALKING, confidence = 0.9f)
                    }

                    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
                }
                sensorListener = listener
                sm.registerListener(listener, stepSensor, SensorManager.SENSOR_DELAY_NORMAL)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Could not register motion sensor listener: ${e.message}")
        }
    }

    private fun unregisterMotionObserver() {
        try {
            val listener = sensorListener ?: return
            val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            sm?.unregisterListener(listener)
            sensorListener = null
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
                    contextEngine.checkTimeouts()
                } catch (e: Throwable) {
                    Log.w(TAG, "Error running contextEngine.checkTimeouts: ${e.message}")
                }
            }
        }
    }

    companion object {
        private const val TAG = "ContextSensingManager"
    }
}
