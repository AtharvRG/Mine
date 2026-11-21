// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.mine.nudge

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import com.anchor.core.models.ProactiveState
import com.anchor.core.nudge.CallNudgeConfig
import com.anchor.core.nudge.CallNudgeDecision
import com.anchor.core.nudge.CallNudgeEngine
import com.anchor.core.nudge.NudgeEngine
import com.anchor.core.time.SystemClock
import com.anchor.mine.MineApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Listens for telephony state broadcasts (off-hook -> idle) and feeds the CallNudgeEngine.
 * Blueprint 44 §2 / §3.1.
 *
 * Invariants:
 * - Shipped dark behind flag.call_nudge and callnudge.enabled.
 * - Zero call audio recorded, zero phone numbers stored.
 * - Calls < 45s dropped without notification.
 * - Coalesces rapid calls within 10 minutes.
 */
class CallStateReceiver : BroadcastReceiver() {

    companion object {
        private const val PREFS_NAME = "mine_call_nudge_prefs"
        private const val KEY_OFFHOOK_TS = "call_offhook_ts"

        @androidx.annotation.VisibleForTesting
        @Volatile
        var lastDecision: CallNudgeDecision? = null

        /**
         * P2-11 (Patch T): Dynamically enable or disable the CallStateReceiver component in PackageManager
         * based on flag.call_nudge and callnudge.enabled.
         */
        fun setCallReceiverEnabled(context: Context, enabled: Boolean) {
            try {
                val pm = context.packageManager
                val component = android.content.ComponentName(context, CallStateReceiver::class.java)
                val newState = if (enabled) {
                    android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                } else {
                    android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                }
                pm.setComponentEnabledSetting(component, newState, android.content.pm.PackageManager.DONT_KILL_APP)
            } catch (t: Throwable) {
                android.util.Log.w("CallStateReceiver", "Failed to update component state: ${t.message}")
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        val stateStr = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
        val appContext = context.applicationContext

        val pendingResult = try { goAsync() } catch (_: Exception) { null }
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val app = appContext as? MineApp ?: return@launch
                val storage = app.sharedStorage.await()

                // Dark-ship and user setting guards
                val flagEnabled = storage.getSetting("flag.call_nudge") == "1" || storage.getSetting("flag.call_nudges") == "1"
                val callNudgeEnabled = storage.getSetting("callnudge.enabled") == "1"
                if (!flagEnabled || !callNudgeEnabled) return@launch

                val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

                when (stateStr) {
                    TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                        // Call started (off-hook)
                        prefs.edit().putLong(KEY_OFFHOOK_TS, System.currentTimeMillis()).apply()
                    }
                    TelephonyManager.EXTRA_STATE_IDLE -> {
                        // Call ended (off-hook -> idle transition)
                        val startedAt = prefs.getLong(KEY_OFFHOOK_TS, 0L)
                        if (startedAt <= 0L) return@launch // Missed/rejected call (never went offhook)
                        prefs.edit().remove(KEY_OFFHOOK_TS).apply()

                        val endedAt = System.currentTimeMillis()
                        val clock = SystemClock()
                        val nudgeEngine = NudgeEngine(clock)
                        val minDuration = storage.getSetting("callnudge.min_duration_s")?.toIntOrNull() ?: 45
                        val delayS = storage.getSetting("callnudge.delay_s")?.toIntOrNull() ?: 20
                        val coalesceMin = storage.getSetting("callnudge.coalesce_min")?.toIntOrNull() ?: 10
                        val expireH = storage.getSetting("callnudge.expire_h")?.toIntOrNull() ?: 3
                        val callConfig = CallNudgeConfig(minDurationS = minDuration, delayS = delayS, coalesceMin = coalesceMin, expireH = expireH)
                        val callEngine = CallNudgeEngine(
                            storage = storage,
                            clock = clock,
                            nudgeEngine = nudgeEngine,
                            configProvider = { callConfig }
                        )

                        val decision = callEngine.onCallEnded(startedAt = startedAt, endedAt = endedAt)
                        lastDecision = decision

                        when (decision) {
                            is CallNudgeDecision.ScheduleAfterDelay -> {
                                scheduleNudgeAlarm(appContext, decision.eventId, decision.fireAt)
                            }
                            is CallNudgeDecision.CoalesceWithExisting -> {
                                scheduleNudgeAlarm(appContext, decision.existingId, decision.newFireAt)
                            }
                            else -> {
                                // Short call dropped or held
                            }
                        }
                    }
                }
            } catch (t: Throwable) {
                android.util.Log.e("CallStateReceiver", "Call state handling failed: ${t.message}", t)
            } finally {
                pendingResult?.finish()
            }
        }
    }

    private fun scheduleNudgeAlarm(appContext: Context, eventId: String, fireAt: Long) {
        val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        val nudgeIntent = Intent(appContext, CallNudgeActionReceiver::class.java).apply {
            action = CallNudgeActionReceiver.ACTION_EVALUATE_CALL_NUDGE
            putExtra(CallNudgeNotifications.EXTRA_CALL_EVENT_ID, eventId)
        }
        val pending = PendingIntent.getBroadcast(
            appContext,
            eventId.hashCode(),
            nudgeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S && alarmManager?.canScheduleExactAlarms() == true) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    fireAt,
                    pending
                )
            } else {
                alarmManager?.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    fireAt,
                    pending
                )
            }
        } catch (e: SecurityException) {
            alarmManager?.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                fireAt,
                pending
            )
        } catch (_: Throwable) {
            // Suppress unexpected scheduler errors
        }
    }
}
