// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.mine.alarms

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import com.anchor.core.models.AlarmTier

/**
 * The two channels every reminder notification is posted to. They must exist before the first
 * alarm fires: posting to a channel that was never created throws, and checkHealth() reports a
 * missing channel as a failure. Creation is idempotent — re-creating an existing channel only
 * updates the name/description, never the user's importance choice.
 */
object NotificationChannels {
    const val ALARM = "mine_reminders_v3"
    const val GENTLE = "mine_gentle_reminders_v3"
    const val CALL_NUDGE = "mine_call_nudge"
    const val AMBIENT = "mine_ambient_listening"
    const val AMBIENT_HEARTBEAT = "mine_ambient_heartbeat"

    fun channelId(tier: AlarmTier): String = if (tier == AlarmTier.GENTLE) GENTLE else ALARM

    fun ensureCreated(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        manager.createNotificationChannel(channel(GENTLE, "Gentle Reminders", NotificationManager.IMPORTANCE_DEFAULT))
        manager.createNotificationChannel(channel(ALARM, "Reminders", NotificationManager.IMPORTANCE_HIGH))
        manager.createNotificationChannel(channel(CALL_NUDGE, "Post-call Nudges", NotificationManager.IMPORTANCE_DEFAULT))
        manager.createNotificationChannel(channel(AMBIENT, "Ambient Listening Status", NotificationManager.IMPORTANCE_LOW))
        manager.createNotificationChannel(channel(AMBIENT_HEARTBEAT, "Ambient Listening Alerts", NotificationManager.IMPORTANCE_DEFAULT))
    }

    private fun channel(id: String, name: String, importance: Int) =
        NotificationChannel(id, name, importance).apply {
            enableVibration(false)
            vibrationPattern = null
        }

    fun priority(tier: AlarmTier): Int =
        if (tier == AlarmTier.GENTLE) NotificationCompat.PRIORITY_DEFAULT else NotificationCompat.PRIORITY_HIGH
}
