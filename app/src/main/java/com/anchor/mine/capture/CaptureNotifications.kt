// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.mine.capture

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import com.anchor.core.capture.QuickCapture
import com.anchor.mine.R

/**
 * Blueprint 50 §3.14: the inline reply action that turns a reminder notification into a capture
 * entry point, and the in-place updates that report what happened to the captured text.
 *
 * The action is built here rather than in `AlarmReceiver` so the reminder notification and the
 * "Sending..." replacement are the same shape. A reply that cannot be delivered to the same
 * notification id would leave the user with two rows for one sentence.
 */
object CaptureNotifications {

    /** The key `RemoteInput.getResultsFromIntent` reads back. Private to the app. */
    const val RESULT_KEY = "com.anchor.mine.capture.REPLY"

    /**
     * The reply action for the notification posted as [notificationId]. The request code is the
     * same as the notification id, so two reminders armed at once cannot share one PendingIntent
     * and deliver the second reply to the first notification.
     */
    fun replyAction(context: Context, notificationId: Int): NotificationCompat.Action {
        val replyIntent = Intent(context, QuickCaptureReceiver::class.java).apply {
            putExtra(QuickCaptureReceiver.EXTRA_NOTIFICATION_ID, notificationId)
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
        return NotificationCompat.Action.Builder(
            R.drawable.ic_capture_reply,
            "Reply",
            pendingReply
        ).addRemoteInput(remoteInput).build()
    }

    /**
     * Re-posts the notification [notificationId] as [title] / [text] with no reply action.
     *
     * The original notification was posted with `setAutoCancel(true)` and the reply does not open
     * the activity, so it is still on screen; posting the same id replaces it in place.
     */
    fun replace(context: Context, notificationId: Int, title: String, text: String) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val notification = NotificationCompat.Builder(context, com.anchor.mine.alarms.NotificationChannels.ALARM)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .build()
        manager.notify(notificationId, notification)
    }

    /** Blueprint 50 §3.14: the reply arrived and the turn is running. */
    fun showSending(context: Context, notificationId: Int) =
        replace(context, notificationId, "Mine", "Sending…")

    /**
     * Blueprint 50 §3.14: the turn could not run — no provider, or the request failed. The text
     * is stored and will be retried on the next start, so the notification says so rather than
     * implying it was lost.
     */
    fun showFailed(context: Context, notificationId: Int) =
        replace(context, notificationId, "Mine", "Saved — will send when you next open Mine.")
}
