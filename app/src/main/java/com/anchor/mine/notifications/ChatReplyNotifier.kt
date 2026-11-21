// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.mine.notifications

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.anchor.core.storage.Storage
import com.anchor.mine.MainActivity
import com.anchor.mine.MineApp
import com.anchor.mine.alarms.NotificationChannels
import com.anchor.mine.capture.CaptureNotifications

object ChatReplyNotifier {
    const val NOTIFICATION_ID = 202611

    /** Storage provider for testing or direct access */
    var storageProvider: (() -> Storage?)? = null

    fun notify(context: Context, reply: String) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val snippet = reply.trim().take(120)
        val channelId = NotificationChannels.CHAT_REPLY
        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(com.anchor.mine.R.drawable.ic_notification)
            .setContentTitle("Mine")
            .setContentText(snippet)
            .setStyle(NotificationCompat.BigTextStyle().bigText(reply.trim()))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .addAction(CaptureNotifications.replyAction(context, NOTIFICATION_ID))
            .setAutoCancel(true)
            .setVibrate(null)

        val storage = storageProvider?.invoke() ?: run {
            val app = context.applicationContext as? MineApp
            if (app != null && app.sharedStorage.isCompleted) {
                try {
                    kotlinx.coroutines.runBlocking { app.sharedStorage.await() }
                } catch (_: Throwable) {
                    null
                }
            } else null
        }

        val hideContent = if (storage != null) {
            try {
                kotlinx.coroutines.runBlocking { storage.getSetting("privacy.hide_notification_content") == "1" }
            } catch (_: Throwable) {
                false
            }
        } else false
        if (hideContent) {
            val publicNotification = NotificationCompat.Builder(context, channelId)
                .setSmallIcon(com.anchor.mine.R.drawable.ic_notification)
                .setContentTitle("Mine")
                .setContentText("New message")
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()
            builder.setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            builder.setPublicVersion(publicNotification)
        }

        manager.notify(NOTIFICATION_ID, builder.build())
    }
}
