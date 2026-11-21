// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.mine.alarms

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
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
            data = Uri.parse("mine://reminder/${reminder.id}")
            putExtra("EXTRA_ID", reminder.id)
            putExtra("EXTRA_TEXT", reminder.text)
            putExtra("EXTRA_TIER", reminder.tier.name)
            // R-54: the receiver's fallback path needs the fire instant to record a fire the
            // sweeper can dedupe against when storage is unreadable.
            putExtra("EXTRA_SCHEDULED_FOR", reminder.fireAtMillis)
        }
        val pendingIntent = PendingIntent.getBroadcast(context, reminder.id.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        return try {
            when (reminder.tier) {
                AlarmTier.ALARM -> {
                    val showIntent = Intent(context, com.anchor.mine.MainActivity::class.java).apply {
                        data = Uri.parse("mine://reminder/${reminder.id}")
                    }
                    val pendingShow = PendingIntent.getActivity(context, reminder.id.hashCode(), showIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                    alarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(reminder.fireAtMillis, pendingShow), pendingIntent)
                }
                AlarmTier.GENTLE -> {
                    alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, reminder.fireAtMillis, pendingIntent)
                }
            }
            // v1.1 Item 2: Sync to Device Protected storage safety net (NO private text)
            try {
                DirectBootAlarmStore(context).saveAlarm(reminder.id, reminder.fireAtMillis, reminder.tier, "Reminder")
            } catch (_: Exception) {}
            com.anchor.mine.widget.MineAppWidgetProvider.updateWidgets(context)
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
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            data = Uri.parse("mine://reminder/$reminderId")
        }
        val pendingIntent = PendingIntent.getBroadcast(context, reminderId.hashCode(), intent, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
        if (pendingIntent != null) {
            alarmManager.cancel(pendingIntent)
            pendingIntent.cancel()
        }
        try {
            DirectBootAlarmStore(context).removeAlarm(reminderId)
        } catch (_: Exception) {}
        com.anchor.mine.widget.MineAppWidgetProvider.updateWidgets(context)
    }

    override fun scheduleAgentWakeup(wakeupId: String, fireAtMillis: Long): ScheduleResult {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = AlarmReceiver.ACTION_AGENT_WAKEUP
            data = Uri.parse("mine://wakeup/$wakeupId")
            putExtra("EXTRA_WAKEUP_ID", wakeupId)
            putExtra("EXTRA_SCHEDULED_FOR", fireAtMillis)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            wakeupId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return try {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAtMillis, pendingIntent)
            ScheduleResult.Armed
        } catch (e: Exception) {
            ScheduleResult.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    override fun cancelAgentWakeup(wakeupId: String) {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = AlarmReceiver.ACTION_AGENT_WAKEUP
            data = Uri.parse("mine://wakeup/$wakeupId")
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            wakeupId.hashCode(),
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (pendingIntent != null) {
            alarmManager.cancel(pendingIntent)
            pendingIntent.cancel()
        }
    }

    /**
     * Schedules an alarm from a Direct Boot safety-net record when running
     * before first user unlock (CE storage unavailable).
     */
    fun scheduleDirectBoot(alarm: DirectBootAlarm): ScheduleResult {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            data = Uri.parse("mine://reminder/${alarm.id}")
            putExtra("EXTRA_ID", alarm.id)
            putExtra("EXTRA_TEXT", alarm.label)
            putExtra("EXTRA_TIER", alarm.tier.name)
            putExtra("EXTRA_SCHEDULED_FOR", alarm.fireAtMillis)
            putExtra("EXTRA_DIRECT_BOOT", true)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            alarm.id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return try {
            when (alarm.tier) {
                AlarmTier.ALARM -> {
                    val showIntent = Intent(context, com.anchor.mine.MainActivity::class.java).apply {
                        data = Uri.parse("mine://reminder/${alarm.id}")
                    }
                    val pendingShow = PendingIntent.getActivity(
                        context,
                        alarm.id.hashCode(),
                        showIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    alarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(alarm.fireAtMillis, pendingShow), pendingIntent)
                }
                AlarmTier.GENTLE -> {
                    alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, alarm.fireAtMillis, pendingIntent)
                }
            }
            ScheduleResult.Armed
        } catch (e: Exception) {
            ScheduleResult.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    override fun checkHealth(): ScheduleResult {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager
            ?: return ScheduleResult.Armed
        // Channels are created at app start; a missing one means notifications would be dropped
        // silently, so this is a real failure and not a healthy state.
        NotificationChannels.ensureCreated(context)
        if (!notificationManager.areNotificationsEnabled()) {
            return ScheduleResult.Failed("Notifications are disabled for Mine in system settings")
        }
        val alarmChannel = notificationManager.getNotificationChannel(NotificationChannels.ALARM)
            ?: return ScheduleResult.Failed("Notification channel 'Reminders' is missing")
        if (alarmChannel.importance == android.app.NotificationManager.IMPORTANCE_NONE) {
            return ScheduleResult.Failed("Notification channel 'Reminders' is blocked")
        }
        val gentleChannel = notificationManager.getNotificationChannel(NotificationChannels.GENTLE)
            ?: return ScheduleResult.Failed("Notification channel 'Gentle Reminders' is missing")
        if (gentleChannel.importance == android.app.NotificationManager.IMPORTANCE_NONE) {
            return ScheduleResult.Failed("Notification channel 'Gentle Reminders' is blocked")
        }
        return ScheduleResult.Armed
    }
}
