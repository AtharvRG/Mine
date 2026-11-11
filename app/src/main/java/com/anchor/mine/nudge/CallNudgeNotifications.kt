// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.mine.nudge

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import com.anchor.mine.MainActivity
import com.anchor.mine.R
import com.anchor.mine.alarms.NotificationChannels

/**
 * Builds post-call nudge notifications with Reply, Voice note, and Skip actions.
 * Blueprint 44 §2 / §3.
 *
 * Invariants:
 * - Absolute Zero Vibration: channel and notification have vibration disabled.
 * - Lock-screen / public version conceals contact name.
 */
object CallNudgeNotifications {

    const val RESULT_KEY = "com.anchor.mine.nudge.CALL_REPLY"
    const val ACTION_REPLY = "com.anchor.mine.nudge.ACTION_REPLY"
    const val ACTION_SKIP = "com.anchor.mine.nudge.ACTION_SKIP"
    const val ACTION_VOICE_NOTE = "com.anchor.mine.nudge.ACTION_VOICE_NOTE"
    const val EXTRA_CALL_EVENT_ID = "com.anchor.mine.nudge.EXTRA_CALL_EVENT_ID"
    const val EXTRA_NOTIFICATION_ID = "com.anchor.mine.nudge.EXTRA_NOTIFICATION_ID"

    fun show(
        context: Context,
        callEventId: String,
        contactName: String? = null
    ) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        NotificationChannels.ensureCreated(context)

        val notificationId = callEventId.hashCode()

        // 1. Reply action (inline remote input)
        val replyIntent = Intent(context, CallNudgeActionReceiver::class.java).apply {
            action = ACTION_REPLY
            putExtra(EXTRA_CALL_EVENT_ID, callEventId)
            putExtra(EXTRA_NOTIFICATION_ID, notificationId)
        }
        val pendingReply = PendingIntent.getBroadcast(
            context,
            notificationId,
            replyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        val remoteInput = RemoteInput.Builder(RESULT_KEY)
            .setLabel("Reply")
            .build()
        val replyAction = NotificationCompat.Action.Builder(
            R.drawable.ic_capture_reply,
            "Reply",
            pendingReply
        ).addRemoteInput(remoteInput).build()

        // 2. Voice note action (opens MainActivity to voice sheet)
        val voiceIntent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_VOICE_NOTE
            putExtra(EXTRA_CALL_EVENT_ID, callEventId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingVoice = PendingIntent.getActivity(
            context,
            notificationId + 1,
            voiceIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val voiceAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_btn_speak_now,
            "Voice note",
            pendingVoice
        ).build()

        // 3. Skip action
        val skipIntent = Intent(context, CallNudgeActionReceiver::class.java).apply {
            action = ACTION_SKIP
            putExtra(EXTRA_CALL_EVENT_ID, callEventId)
            putExtra(EXTRA_NOTIFICATION_ID, notificationId)
        }
        val pendingSkip = PendingIntent.getBroadcast(
            context,
            notificationId + 2,
            skipIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val skipAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_close_clear_cancel,
            "Skip",
            pendingSkip
        ).build()

        // Public version for lockscreen (never leaks contact name)
        val publicNotification = NotificationCompat.Builder(context, NotificationChannels.CALL_NUDGE)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Call done")
            .setContentText("Anything worth remembering?")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setVibrate(longArrayOf(0))
            .build()

        val title = if (!contactName.isNullOrBlank()) "Call with $contactName done" else "Call done"
        val notification = NotificationCompat.Builder(context, NotificationChannels.CALL_NUDGE)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText("Anything worth remembering?")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setVibrate(longArrayOf(0))
            .setAutoCancel(true)
            .setPublicVersion(publicNotification)
            .addAction(replyAction)
            .addAction(voiceAction)
            .addAction(skipAction)
            .build()

        manager.notify(notificationId, notification)
    }

    fun dismiss(context: Context, notificationId: Int) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        manager.cancel(notificationId)
    }
}
