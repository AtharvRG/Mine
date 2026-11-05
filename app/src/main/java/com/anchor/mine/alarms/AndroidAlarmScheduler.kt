// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.mine.alarms

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.anchor.core.alarms.AlarmScheduler
import com.anchor.core.alarms.ScheduleResult
import com.anchor.core.models.AlarmTier
import com.anchor.core.models.Reminder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// D4: Removed redundant GlobalAlarmState holder. Using AlarmReceiver.lastFiredId directly.

class AndroidAlarmScheduler(private val context: Context) : AlarmScheduler {
    companion object {
        private val _lastFiredId = MutableStateFlow<String?>(null)
        val firedFlow: StateFlow<String?> = _lastFiredId.asStateFlow()

        fun notifyFired(id: String) {
            _lastFiredId.value = id
        }
    }

    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    override val lastFiredId: StateFlow<String?> = firedFlow

    override fun schedule(reminder: Reminder): ScheduleResult {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            putExtra("EXTRA_ID", reminder.id)
            putExtra("EXTRA_TEXT", reminder.text)
            putExtra("EXTRA_TIER", reminder.tier.name)
        }
        val pendingIntent = PendingIntent.getBroadcast(context, reminder.id.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        return try {
            when (reminder.tier) {
                AlarmTier.ALARM -> {
                    val showIntent = Intent(context, com.anchor.mine.MainActivity::class.java)
                    val pendingShow = PendingIntent.getActivity(context, reminder.id.hashCode(), showIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                    alarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(reminder.fireAtMillis, pendingShow), pendingIntent)
                }
                AlarmTier.GENTLE -> {
                    alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, reminder.fireAtMillis, pendingIntent)
                }
            }
            ScheduleResult.Armed
        } catch (e: SecurityException) {
            ScheduleResult.Failed("SecurityException: ${e.message ?: "permission denied or exact alarm policy disallow"}")
        } catch (e: IllegalStateException) {
            ScheduleResult.Failed("IllegalStateException: ${e.message ?: "system state invalid for alarm scheduling"}")
        } catch (e: Exception) {
            ScheduleResult.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    override fun cancel(reminderId: String) {
        val intent = Intent(context, AlarmReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(context, reminderId.hashCode(), intent, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
        if (pendingIntent != null) {
            alarmManager.cancel(pendingIntent)
            pendingIntent.cancel()
        }
    }

    override fun checkHealth(): ScheduleResult {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager
            ?: return ScheduleResult.Armed
        if (!notificationManager.areNotificationsEnabled()) {
            return ScheduleResult.Failed("Notifications are disabled for Mine in system settings")
        }
        val alarmChannel = notificationManager.getNotificationChannel("mine_reminders_v3")
        if (alarmChannel != null && alarmChannel.importance == android.app.NotificationManager.IMPORTANCE_NONE) {
            return ScheduleResult.Failed("Notification channel 'Reminders' is blocked")
        }
        val gentleChannel = notificationManager.getNotificationChannel("mine_gentle_reminders_v3")
        if (gentleChannel != null && gentleChannel.importance == android.app.NotificationManager.IMPORTANCE_NONE) {
            return ScheduleResult.Failed("Notification channel 'Gentle Reminders' is blocked")
        }
        return ScheduleResult.Armed
    }
}
