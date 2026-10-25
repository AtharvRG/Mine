// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.mine.alarms

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.anchor.mine.MainActivity
import com.anchor.mine.storage.DatabaseProvider
import com.anchor.mine.storage.DbKeyProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AlarmReceiver : BroadcastReceiver() {

    companion object {
        var lastFiredId: String? = null // Kept purely for the instrumented test
    }

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("EXTRA_ID") ?: return
        val text = intent.getStringExtra("EXTRA_TEXT") ?: "Reminder"
        lastFiredId = id

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "mine_reminders_v1"
        manager.createNotificationChannel(NotificationChannel(channelId, "Reminders", NotificationManager.IMPORTANCE_HIGH))

        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingOpen = PendingIntent.getActivity(context, id.hashCode(), openIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Mine")
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingOpen)
            .setAutoCancel(true)
            .build()

        manager.notify(id.hashCode(), notification)

        // Mark done by removing from operational table
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val key = DbKeyProvider(context).getOrGenerateKey()
                val db = DatabaseProvider.getHelper(context, key).writableDatabase
                db.execSQL("DELETE FROM reminder WHERE id = ?", arrayOf(id))
            } catch (e: Exception) {
                // Ignore DB errors during broadcast
            } finally {
                pendingResult.finish()
            }
        }
    }
}