// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.mine.alarms

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.anchor.core.alarms.AlarmScheduler
import com.anchor.core.models.Reminder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object GlobalAlarmState {
    val lastFired = MutableStateFlow<String?>(null)
}

class AndroidAlarmScheduler(private val context: Context) : AlarmScheduler {
    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    override val lastFiredId: StateFlow<String?> = GlobalAlarmState.lastFired.asStateFlow()

    override fun schedule(reminder: Reminder) {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            putExtra("EXTRA_ID", reminder.id)
            putExtra("EXTRA_TEXT", reminder.text)
        }
        val pendingIntent = PendingIntent.getBroadcast(context, reminder.id.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        // Item 13: Activity PendingIntent bypasses USE_EXACT_ALARM requirement
        val showIntent = Intent(context, com.anchor.mine.MainActivity::class.java)
        val pendingShow = PendingIntent.getActivity(context, reminder.id.hashCode(), showIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        try {
            alarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(reminder.fireAtMillis, pendingShow), pendingIntent)
        } catch (e: SecurityException) {
            // Android 14+ throws if SCHEDULE_EXACT_ALARM is denied.
            // Gracefully ignore; the Reliability Screen catches this state.
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
