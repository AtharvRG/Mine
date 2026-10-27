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
import com.anchor.mine.MineApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AlarmReceiver : BroadcastReceiver() {
    companion object {
        @Volatile
        var lastFiredId: String? = null
    }

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("EXTRA_ID") ?: return
        val text = intent.getStringExtra("EXTRA_TEXT") ?: "Reminder"
        
        // Safely acquire Wakelock/Timeout context (fails gracefully in unit tests)
        val pendingResult = try { goAsync() } catch (_: Exception) { null }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // If storage failed to init (e.g. Keystore corruption), await() throws.
                // The catch block below intercepts it instantly. (Fail fast, no hang).
                val storage = (context.applicationContext as MineApp).sharedStorage.await()
                
                // BP 30: Must check ALL pending reminders, not just future ones
                val pending = storage.getPendingReminders()
                
                // BP 30: If the node was closed or alarm cancelled, the row won't exist. Suppress.
                if (pending.none { it.id == id }) return@launch

                GlobalAlarmState.lastFired.value = id
                lastFiredId = id

                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                val channelId = "mine_reminders_v1"
                manager.createNotificationChannel(NotificationChannel(channelId, "Reminders", NotificationManager.IMPORTANCE_HIGH))

                val openIntent = Intent(context, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK }
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
                storage.deleteReminder(id) // Mark done
            } catch (_: Exception) {
                // Init failed or DB locked, fail fast
            } finally {
                pendingResult?.finish()
            }
        }
    }
}
