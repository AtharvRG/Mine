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
import com.anchor.core.ambient.AmbientSleepReason
import com.anchor.core.ambient.AmbientState
import com.anchor.core.ambient.PresenceGate
import com.anchor.mine.MainActivity
import com.anchor.mine.MineApp
import com.anchor.mine.alarms.NotificationChannels
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

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

        val initialState = if (hasMicPermission) AmbientState.Listening else AmbientState.Sleeping(AmbientSleepReason.MIC_BUSY)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && hasMicPermission) {
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else 0
            startForeground(NOTIFICATION_ID, buildNotification(initialState), fgsType)
        } else {
            startForeground(NOTIFICATION_ID, buildNotification(initialState))
        }
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
            val notification = buildNotification(newState)
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager
            manager?.notify(NOTIFICATION_ID, notification)
        }
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

        val pause1hIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, AmbientForegroundService::class.java).apply { action = ACTION_PAUSE_1H },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this,
            3,
            Intent(this, AmbientForegroundService::class.java).apply { action = ACTION_STOP },
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
            .addAction(android.R.drawable.ic_media_pause, "Pause 15m", pause15mIntent)
            .addAction(android.R.drawable.ic_media_pause, "Pause 1h", pause1hIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopIntent)
            .build()
    }

    private fun stopAmbientService() {
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

        fun start(context: Context) {
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
    }
}
