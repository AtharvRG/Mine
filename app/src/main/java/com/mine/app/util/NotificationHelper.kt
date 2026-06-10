package com.mine.app.util

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import com.mine.app.MainActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotificationHelper @Inject constructor(@ApplicationContext private val context: Context) {

    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        createChannel()
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            "nudge_channel",
            "MineAI Nudges",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Proactive messages and urgent reminders"
        }
        notificationManager.createNotificationChannel(channel)
    }

    @SuppressLint("MissingPermission")
    fun showNudgeNotification(message: String) {
        val notifId = System.currentTimeMillis().toInt()

        // 1. Intent to open the app
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(context, notifId, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        // 2. Direct Reply Action
        val remoteInput = RemoteInput.Builder("nudge_reply").setLabel("Reply to MineAI...").build()
        val replyIntent = Intent(context, NudgeReplyReceiver::class.java).apply {
            putExtra("notif_id", notifId)
        }
        // FLAG_MUTABLE is strictly required for RemoteInput on Android 12+
        val replyPendingIntent = PendingIntent.getBroadcast(context, notifId, replyIntent, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val replyAction = NotificationCompat.Action.Builder(android.R.drawable.ic_menu_send, "Reply", replyPendingIntent)
            .addRemoteInput(remoteInput)
            .build()

        val notification = NotificationCompat.Builder(context, "nudge_channel")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("MineAI")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_MAX) // MAX forces Heads-Up
            .setDefaults(Notification.DEFAULT_ALL) // Sound and Vibrate required for Heads-Up
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .addAction(replyAction) // Add the reply box!
            .build()

        notificationManager.notify(notifId, notification)
    }

    @SuppressLint("MissingPermission")
    fun showCallReflectionNotification() {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = "com.mine.app.ACTION_CALL_REFLECTION"
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(context, 1, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        // Add a specific "Record" button to the notification
        val recordAction = NotificationCompat.Action.Builder(android.R.drawable.ic_btn_speak_now, "🎙️ Record Summary", pendingIntent).build()

        val notification = NotificationCompat.Builder(context, "nudge_channel")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Call Ended")
            .setContentText("Capture what mattered?")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setDefaults(Notification.DEFAULT_ALL)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .addAction(recordAction) // Add the button!
            .build()

        notificationManager.notify(System.currentTimeMillis().toInt(), notification)
    }
}
