// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine Author
// Part of Mine. See LICENSE in the repository root.

package com.anchor.mine.alarms

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

class AlarmReceiver : BroadcastReceiver() {

    companion object {
        // Static flag strictly so our instrumented test can verify firing
        // without needing a complex UI Automator notification scanner.
        var lastFiredId: String?
            get() = com.anchor.core.alarms.AlarmReceiver.lastFiredId
            set(value) {
                com.anchor.core.alarms.AlarmReceiver.lastFiredId = value
            }
    }

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("EXTRA_ID") ?: return
        val text = intent.getStringExtra("EXTRA_TEXT") ?: "Reminder"

        lastFiredId = id

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "mine_reminders_v1"

        val channel = NotificationChannel(
            channelId,
            "Reminders",
            NotificationManager.IMPORTANCE_HIGH
        )
        manager.createNotificationChannel(channel)

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Mine")
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        manager.notify(id.hashCode(), notification)
    }
}