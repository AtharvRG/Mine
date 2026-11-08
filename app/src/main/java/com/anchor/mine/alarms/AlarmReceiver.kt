// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.mine.alarms

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.anchor.core.models.AlarmTier
import com.anchor.core.models.ReminderFire
import com.anchor.mine.MainActivity
import com.anchor.mine.MineApp
import com.anchor.mine.storage.AndroidStorage
import com.anchor.mine.storage.DatabaseProvider
import com.anchor.mine.storage.DbKeyProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.UUID

class AlarmReceiver : BroadcastReceiver() {
    companion object {
        @androidx.annotation.VisibleForTesting
        @Volatile
        var lastFiredId: String? = null
    }

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("EXTRA_ID") ?: return
        val text = intent.getStringExtra("EXTRA_TEXT") ?: "Reminder"
        val tierStr = intent.getStringExtra("EXTRA_TIER") ?: "ALARM"
        val fallbackTier = if (tierStr.equals("GENTLE", ignoreCase = true)) AlarmTier.GENTLE else AlarmTier.ALARM
        val isMissed = intent.getBooleanExtra("EXTRA_MISSED", false)
        // R-54: the fallback path needs the reminder's own fire instant to record a fire the
        // sweeper will dedupe against. When the row is readable it is preferred below.
        val intentScheduledFor = intent.getLongExtra("EXTRA_SCHEDULED_FOR", 0L)

        // Safely acquire Wakelock/Timeout context (fails gracefully in unit tests)
        val pendingResult = try { goAsync() } catch (_: Exception) { null }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // If storage failed to init (e.g. Keystore corruption), await() throws.
                // The catch block below intercepts it instantly. (Fail fast, no hang).
                // R-55: an unchecked cast threw ClassCastException inside the coroutine when the
                // process was started by something other than MineApp, which the catch below then
                // turned into a blind notification for a reminder nobody could resolve.
                val app = context.applicationContext as? MineApp ?: return@launch
                val storage = app.sharedStorage.await()

                // R-26: the pending read is paged, so it cannot be trusted to contain this row.
                // A reminder outside the page would otherwise fire with the intent's stale text.
                // The by-id lookup is the fallback; the page is the fast path for the common case.
                val rem = storage.getPendingReminders().find { it.id == id }
                    ?: storage.getReminderById(id)
                if (rem == null && !isMissed) return@launch

                // B15: Use text from DB row if available, fallback to intent text
                val baseText = rem?.text ?: text
                val safeText = if (isMissed) {
                    if (baseText.startsWith("Missed: ")) baseText else "Missed: $baseText"
                } else baseText
                val tier = rem?.tier ?: fallbackTier

                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                val channelId = NotificationChannels.channelId(tier)
                val priority = NotificationChannels.priority(tier)
                // A process started by the alarm may never have run Application.onCreate.
                NotificationChannels.ensureCreated(context)

                // B15: Removed FLAG_ACTIVITY_CLEAR_TASK to prevent killing active chats
                val openIntent = Intent(context, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
                val pendingOpen = PendingIntent.getActivity(context, id.hashCode(), openIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

                val notification = NotificationCompat.Builder(context, channelId)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle("Mine")
                    .setContentText(safeText)
                    .setPriority(priority)
                    .setCategory(if (tier == AlarmTier.ALARM) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_REMINDER)
                    .setContentIntent(pendingOpen)
                    .setAutoCancel(true)
                    .build()
                manager.notify(id.hashCode(), notification)
                storage.deleteReminder(id) // Mark done

                // D-124: the completion signal follows the durable write, so "fired" means
                // "delivered and row retired", never "attempted". A caller that observes
                // lastFiredId can now rely on the reminder row already being gone.
                lastFiredId = id
                AndroidAlarmScheduler.notifyFired(id)

                // Blueprints 30/42: every delivery records its own latency. Without this only
                // the missed path (ReliabilityManager.runSweeper) produced a reminder_fire row,
                // so a late-but-delivered alarm left no trace to diagnose.
                val firedAt = System.currentTimeMillis()
                val scheduledFor = rem?.fireAtMillis ?: firedAt
                storage.recordReminderFire(
                    ReminderFire(
                        id = "f_${UUID.randomUUID().toString().take(8)}",
                        reminderId = id,
                        firedAt = firedAt,
                        scheduledFor = scheduledFor,
                        latencyMs = (firedAt - scheduledFor).coerceAtLeast(0L).toInt(),
                        outcome = "delivered"
                    )
                )
            } catch (_: Exception) {
                // A4: If storage fails (locked out, crash), do NOT fail silent.
                // Fall back to a blind notification instantly so the user doesn't miss the alarm.
                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                val channelId = NotificationChannels.channelId(fallbackTier)
                val priority = NotificationChannels.priority(fallbackTier)
                NotificationChannels.ensureCreated(context)

                val fallbackText = if (isMissed) "Missed: $text" else text
                val notification = NotificationCompat.Builder(context, channelId)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle("Mine (Fallback)")
                    .setContentText(fallbackText)
                    .setPriority(priority)
                    .setCategory(if (fallbackTier == AlarmTier.ALARM) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_REMINDER)
                    .setAutoCancel(true)
                    .build()

                manager.notify(id.hashCode(), notification)

                // R-54: the fallback used to leave no trace at all, and the row stays pending
                // (deliberately — a flaky storage must not lose the reminder). So the next sweeper
                // saw no "missed" fire for this instance and emitted a SECOND notification for the
                // same fire. Record "errored" against the reminder's own fireAtMillis so the
                // sweeper's dedupe recognises this instance as already handled.
                // Uses a fresh open, not sharedStorage: that deferred is what just failed.
                try {
                    val key = DbKeyProvider(context).getOrGenerateKey()
                    val db = DatabaseProvider.getHelper(context, key, "mine_encrypted.db")
                    AndroidStorage(db).recordReminderFire(
                        ReminderFire(
                            id = "f_${UUID.randomUUID().toString().take(8)}",
                            reminderId = id,
                            firedAt = System.currentTimeMillis(),
                            scheduledFor = if (intentScheduledFor > 0L) intentScheduledFor else System.currentTimeMillis(),
                            latencyMs = 0,
                            outcome = "errored"
                        )
                    )
                } catch (_: Exception) {
                    // Storage is the thing that failed; nothing more can be recorded.
                }
            } finally {
                pendingResult?.finish()
            }
        }
    }
}
