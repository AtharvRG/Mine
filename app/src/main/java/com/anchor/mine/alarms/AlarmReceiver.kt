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
import com.anchor.core.models.AlarmTier
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
        val tierStr = intent.getStringExtra("EXTRA_TIER") ?: "ALARM"
        val fallbackTier = if (tierStr.equals("GENTLE", ignoreCase = true)) AlarmTier.GENTLE else AlarmTier.ALARM

        // Safely acquire Wakelock/Timeout context (fails gracefully in unit tests)
        val pendingResult = try { goAsync() } catch (_: Exception) { null }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // If storage failed to init (e.g. Keystore corruption), await() throws.
                // The catch block below intercepts it instantly. (Fail fast, no hang).
                val storage = (context.applicationContext as MineApp).sharedStorage.await()

                // BP 30: Must check ALL pending reminders, not just future ones
                val pending = storage.getPendingReminders()
                val rem = pending.find { it.id == id } ?: return@launch

                // B15: Use text from DB row
                val safeText = rem.text
                val tier = rem.tier

                lastFiredId = id
                AndroidAlarmScheduler.notifyFired(id)

                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                val (channelId, importance, priority) = if (tier == AlarmTier.GENTLE) {
                    Triple("mine_gentle_reminders", NotificationManager.IMPORTANCE_DEFAULT, NotificationCompat.PRIORITY_DEFAULT)
                } else {
                    Triple("mine_reminders_v1", NotificationManager.IMPORTANCE_HIGH, NotificationCompat.PRIORITY_HIGH)
                }
                val channelName = if (tier == AlarmTier.GENTLE) "Gentle Reminders" else "Reminders"
                manager.createNotificationChannel(NotificationChannel(channelId, channelName, importance))

                // B15: Removed FLAG_ACTIVITY_CLEAR_TASK to prevent killing active chats
                val openIntent = Intent(context, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
                val pendingOpen = PendingIntent.getActivity(context, id.hashCode(), openIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

                val notification = NotificationCompat.Builder(context, channelId)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle("Mine")
                    .setContentText(safeText)
                    .setPriority(priority)
                    .setContentIntent(pendingOpen)
                    .setAutoCancel(true)
                    .build()

                manager.notify(id.hashCode(), notification)
                storage.deleteReminder(id) // Mark done
            } catch (_: Exception) {
                // A4: If storage fails (locked out, crash), do NOT fail silent.
                // Fall back to a blind notification instantly so the user doesn't miss the alarm.
                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                val (channelId, importance, priority) = if (fallbackTier == AlarmTier.GENTLE) {
                    Triple("mine_gentle_reminders", NotificationManager.IMPORTANCE_DEFAULT, NotificationCompat.PRIORITY_DEFAULT)
                } else {
                    Triple("mine_reminders_v1", NotificationManager.IMPORTANCE_HIGH, NotificationCompat.PRIORITY_HIGH)
                }
                val channelName = if (fallbackTier == AlarmTier.GENTLE) "Gentle Reminders" else "Reminders"
                manager.createNotificationChannel(NotificationChannel(channelId, channelName, importance))

                val notification = NotificationCompat.Builder(context, channelId)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle("Mine (Fallback)")
                    .setContentText(text)
                    .setPriority(priority)
                    .setAutoCancel(true)
                    .build()

                manager.notify(id.hashCode(), notification)
            } finally {
                pendingResult?.finish()
            }
        }
    }
}
