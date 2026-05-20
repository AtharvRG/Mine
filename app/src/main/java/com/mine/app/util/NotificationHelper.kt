package com.mine.app.util

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
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
            NotificationManager.IMPORTANCE_HIGH // HIGH means it pops up on screen and makes a sound
        ).apply {
            description = "Proactive messages and urgent reminders from your Co-founder"
        }
        notificationManager.createNotificationChannel(channel)
    }

    @SuppressLint("MissingPermission")
    fun showNudgeNotification(message: String) {
        // When the user taps the notification, open MainActivity
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(context, "nudge_channel")
            .setSmallIcon(android.R.drawable.ic_dialog_alert) // Default alert icon
            .setContentTitle("MineAI")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message)) // Expands for long messages
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true) // Dismisses when tapped
            .setContentIntent(pendingIntent)
            .build()

        // Use a random ID so multiple nudges don't overwrite each other
        notificationManager.notify(System.currentTimeMillis().toInt(), notification)
    }
}
