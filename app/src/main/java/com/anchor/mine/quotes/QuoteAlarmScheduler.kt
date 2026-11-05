// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.mine.quotes

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.util.Calendar
import java.util.TimeZone

object QuoteAlarmScheduler {
    const val ACTION_PREPARE_QUOTE = "com.anchor.mine.quotes.ACTION_PREPARE_QUOTE"
    private const val REQUEST_CODE_QUOTE_PREPARE = 9108

    // D-109: Prepare-ahead fires 1 hour before each boundary: 04:00, 11:00, 16:00, 20:00
    val PREPARE_HOURS = listOf(4, 11, 16, 20)

    fun getNextPrepareTimeMillis(
        nowMs: Long = System.currentTimeMillis(),
        timeZone: TimeZone = TimeZone.getDefault()
    ): Long {
        for (hour in PREPARE_HOURS) {
            val cal = Calendar.getInstance(timeZone).apply {
                timeInMillis = nowMs
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            if (cal.timeInMillis > nowMs + 10_000L) {
                return cal.timeInMillis
            }
        }

        // None left today; first prepare hour tomorrow at 04:00
        return Calendar.getInstance(timeZone).apply {
            timeInMillis = nowMs
            add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, PREPARE_HOURS.first())
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    fun scheduleNext(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = Intent(context, QuoteAlarmReceiver::class.java).apply {
            action = ACTION_PREPARE_QUOTE
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE_QUOTE_PREPARE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val triggerAtMs = getNextPrepareTimeMillis()
        alarmManager.set(AlarmManager.RTC, triggerAtMs, pendingIntent)
    }
}
