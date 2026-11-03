// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.mine.alarms

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.anchor.core.alarms.AlarmScheduler
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

    override fun schedule(reminder: Reminder) {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            putExtra("EXTRA_ID", reminder.id)
            putExtra("EXTRA_TEXT", reminder.text)
            putExtra("EXTRA_TIER", reminder.tier.name)
        }
        val pendingIntent = PendingIntent.getBroadcast(context, reminder.id.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        try {
            when (reminder.tier) {
                AlarmTier.ALARM -> {
                    val showIntent = Intent(context, com.anchor.mine.MainActivity::class.java)
                    val pendingShow = PendingIntent.getActivity(context, reminder.id.hashCode(), showIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
                        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, reminder.fireAtMillis, pendingIntent)
                    } else {
                        alarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(reminder.fireAtMillis, pendingShow), pendingIntent)
                    }
                }
                AlarmTier.GENTLE -> {
                    alarmManager.setAndAllowWhileIdle(AlarmManager.RTC, reminder.fireAtMillis, pendingIntent)
                }
            }
        } catch (e: SecurityException) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, reminder.fireAtMillis, pendingIntent)
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
}
