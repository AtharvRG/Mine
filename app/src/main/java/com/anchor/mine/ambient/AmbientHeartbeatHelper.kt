// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.mine.ambient

import android.app.ActivityManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.anchor.mine.alarms.NotificationChannels

/**
 * Checks ambient service health periodically (sweeper / boot) and posts
 * "Ambient stopped. Tap to resume." notification if killed by the OS.
 * Blueprint 61 §3.2.
 *
 * Invariant: Never restarts the microphone service silently in the background.
 * Requires user tap on the notification to resume.
 */
object AmbientHeartbeatHelper {

    const val HEARTBEAT_NOTIFICATION_ID = 4002

    fun isServiceRunning(context: Context): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
        @Suppress("DEPRECATION")
        val services = am.getRunningServices(50)
        return services.any { it.service.className == AmbientForegroundService::class.java.name }
    }

    fun checkAndNotifyIfNeeded(context: Context) {
        if (isServiceRunning(context)) return

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

        val resumeIntent = PendingIntent.getService(
            context,
            101,
            Intent(context, AmbientForegroundService::class.java).apply {
                action = AmbientForegroundService.ACTION_START
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, NotificationChannels.AMBIENT_HEARTBEAT)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Ambient Listening Paused")
            .setContentText("Ambient stopped. Tap to resume.")
            .setContentIntent(resumeIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setVibrate(longArrayOf(0))
            .build()

        manager.notify(HEARTBEAT_NOTIFICATION_ID, notification)
    }
}
