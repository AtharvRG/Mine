// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.mine.alarms

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
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
        const val ACTION_AGENT_WAKEUP = "com.anchor.mine.ACTION_AGENT_WAKEUP"

        @androidx.annotation.VisibleForTesting
        @Volatile
        var lastFiredId: String? = null

        fun shouldSuppressForNodeStatus(status: String?): Boolean {
            return status in setOf("done", "cancelled", "paused", "archived")
        }

        fun transformTextForInProgress(baseText: String): String {
            val startRegex = Regex("""^(?i)start[:\s]+(.*)$""")
            return if (startRegex.containsMatchIn(baseText)) {
                startRegex.replace(baseText, "Check: $1")
            } else if (!baseText.startsWith("Check:", ignoreCase = true)) {
                "Check: $baseText"
            } else {
                baseText
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_AGENT_WAKEUP || intent.hasExtra("EXTRA_WAKEUP_ID")) {
            handleAgentWakeup(context, intent)
            return
        }
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

        val isDirectBoot = !androidx.core.os.UserManagerCompat.isUserUnlocked(context)

        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (isDirectBoot) {
                    // Direct-Boot Alarm Safety Net (v1.1 Item 2):
                    // Phone is still locked before first PIN entry. Post generic notification from DE store.
                    val deStore = DirectBootAlarmStore(context)
                    val deAlarm = deStore.getAlarmById(id)
                    val notifyTier = deAlarm?.tier ?: fallbackTier
                    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    val channelId = NotificationChannels.channelId(notifyTier)
                    val priority = NotificationChannels.priority(notifyTier)
                    NotificationChannels.ensureCreated(context)

                    val notificationText = if (isMissed) "Missed: Reminder" else (deAlarm?.label ?: "Reminder")
                    val directBootBuilder = NotificationCompat.Builder(context, channelId)
                        .setSmallIcon(com.anchor.mine.R.drawable.ic_notification)
                        .setContentTitle("Mine")
                        .setContentText(notificationText)
                        .setPriority(priority)
                        .setCategory(if (notifyTier == AlarmTier.ALARM) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_REMINDER)
                        .setAutoCancel(true)

                    if (notifyTier == AlarmTier.ALARM) {
                        val silenceIntent = Intent(context, ReminderActionReceiver::class.java).apply {
                            action = ReminderActionReceiver.ACTION_SILENCE
                            putExtra(ReminderActionReceiver.EXTRA_NOTIFICATION_ID, id.hashCode())
                        }
                        val silencePending = PendingIntent.getBroadcast(
                            context,
                            id.hashCode() + 4,
                            silenceIntent,
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                        )
                        directBootBuilder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Silence", silencePending)
                    }

                    val notification = directBootBuilder.build()
                    if (notifyTier == AlarmTier.ALARM && !isMissed) {
                        notification.flags = notification.flags or android.app.Notification.FLAG_INSISTENT
                    }

                    manager.notify(id.hashCode(), notification)
                    deStore.removeAlarm(id)
                    deStore.recordDirectBootFire(
                        id = id,
                        firedAt = System.currentTimeMillis(),
                        scheduledFor = deAlarm?.fireAtMillis ?: System.currentTimeMillis()
                    )
                    lastFiredId = id
                    AndroidAlarmScheduler.notifyFired(id)
                    return@launch
                }

                // If storage failed to init (e.g. Keystore corruption), await() throws.
                // The catch block below intercepts it instantly. (Fail fast, no hang).
                // R-55: an unchecked cast threw ClassCastException inside the coroutine when the
                // process was started by something other than MineApp, which the catch below then
                // turned into a blind notification for a reminder nobody could resolve.
                val app = context.applicationContext as? MineApp ?: return@launch
                val storage = app.sharedStorage.await()

                // Fast-path: indexed primary key lookup on reminders table
                val rem = storage.getReminderById(id)
                    ?: storage.getPendingReminders().find { it.id == id }
                if (rem == null && !isMissed) return@launch

                // Blueprint 30 §3.7: Fire-time guards & transformations
                val linkedNodeId = rem?.nodeId
                val linkedNode = if (linkedNodeId != null) storage.getNode(linkedNodeId) else null
                if (linkedNode != null) {
                    // Suppress when done, cancelled, paused, or archived
                    if (shouldSuppressForNodeStatus(linkedNode.status)) {
                        storage.deleteReminder(id)
                        try {
                            DirectBootAlarmStore(context).removeAlarm(id)
                        } catch (_: Exception) {}
                        lastFiredId = id
                        AndroidAlarmScheduler.notifyFired(id)
                        storage.recordReminderFire(
                            ReminderFire(
                                id = "f_${UUID.randomUUID()}",
                                reminderId = id,
                                firedAt = System.currentTimeMillis(),
                                scheduledFor = rem?.fireAtMillis ?: System.currentTimeMillis(),
                                latencyMs = 0,
                                outcome = "suppressed"
                            )
                        )
                        return@launch
                    }
                }

                // B15: Use text from DB row if available, fallback to intent text
                var baseText = rem?.text ?: text

                // Blueprint 30 §3.7: Transform 'start' -> 'check' when linked node is in_progress
                if (linkedNode?.status == "in_progress") {
                    baseText = transformTextForInProgress(baseText)
                }
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

                val hideContent = storage.getSetting("privacy.hide_notification_content") == "1"
                val customSoundUri = storage.getSetting("reminder.alarm_sound_uri")?.takeIf { it.isNotBlank() }
                val builder = NotificationCompat.Builder(context, channelId)
                    .setSmallIcon(com.anchor.mine.R.drawable.ic_notification)
                    .setContentTitle("Mine")
                    .setContentText(safeText)
                    .setPriority(priority)
                    .setCategory(if (tier == AlarmTier.ALARM) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_REMINDER)
                    .setContentIntent(pendingOpen)
                    .setAutoCancel(true)

                if (tier == AlarmTier.ALARM) {
                    val fullScreenIntent = Intent(context, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        putExtra("EXTRA_REMINDER_ID", id)
                    }
                    val fullScreenPending = PendingIntent.getActivity(
                        context,
                        id.hashCode() + 1,
                        fullScreenIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    builder.setFullScreenIntent(fullScreenPending, true)

                    val doneIntent = Intent(context, ReminderActionReceiver::class.java).apply {
                        action = ReminderActionReceiver.ACTION_DONE
                        putExtra(ReminderActionReceiver.EXTRA_REMINDER_ID, id)
                        putExtra(ReminderActionReceiver.EXTRA_NODE_ID, linkedNodeId)
                        putExtra(ReminderActionReceiver.EXTRA_NOTIFICATION_ID, id.hashCode())
                    }
                    val donePending = PendingIntent.getBroadcast(
                        context,
                        id.hashCode() + 2,
                        doneIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    builder.addAction(android.R.drawable.checkbox_on_background, "Done", donePending)

                    val snoozeIntent = Intent(context, ReminderActionReceiver::class.java).apply {
                        action = ReminderActionReceiver.ACTION_SNOOZE
                        putExtra(ReminderActionReceiver.EXTRA_REMINDER_ID, id)
                        putExtra(ReminderActionReceiver.EXTRA_NOTIFICATION_ID, id.hashCode())
                        putExtra(ReminderActionReceiver.EXTRA_TEXT, safeText)
                    }
                    val snoozePending = PendingIntent.getBroadcast(
                        context,
                        id.hashCode() + 3,
                        snoozeIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    builder.addAction(android.R.drawable.ic_lock_idle_alarm, "Snooze 10m", snoozePending)

                    val silenceIntent = Intent(context, ReminderActionReceiver::class.java).apply {
                        action = ReminderActionReceiver.ACTION_SILENCE
                        putExtra(ReminderActionReceiver.EXTRA_NOTIFICATION_ID, id.hashCode())
                    }
                    val silencePending = PendingIntent.getBroadcast(
                        context,
                        id.hashCode() + 4,
                        silenceIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Silence", silencePending)
                } else {
                    builder.addAction(com.anchor.mine.capture.CaptureNotifications.replyAction(context, id.hashCode()))
                }

                if (customSoundUri != null) {
                    try {
                        builder.setSound(Uri.parse(customSoundUri))
                    } catch (e: Throwable) {
                        android.util.Log.w("AlarmReceiver", "Failed to parse custom alarm sound URI: $customSoundUri", e)
                    }
                }

                if (hideContent) {
                    val publicNotification = NotificationCompat.Builder(context, channelId)
                        .setSmallIcon(com.anchor.mine.R.drawable.ic_notification)
                        .setContentTitle("Mine")
                        .setContentText("Reminder")
                        .setPriority(priority)
                        .build()
                    builder.setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                    builder.setPublicVersion(publicNotification)
                }

                val notification = builder.build()
                if (tier == AlarmTier.ALARM && !isMissed) {
                    notification.flags = notification.flags or android.app.Notification.FLAG_INSISTENT
                }

                // P1-15 (Patch Q): Advance recurrence or retire single reminder BEFORE checking notification state
                val firedAt = System.currentTimeMillis()
                val scheduledFor = rem?.fireAtMillis ?: firedAt
                val notifsEnabled = androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()

                if (!notifsEnabled) {
                    // P1-4: Notifications blocked: keep the reminder row so it is NOT lost silently!
                    storage.recordReminderFire(
                        ReminderFire(
                            id = "f_${UUID.randomUUID()}",
                            reminderId = id,
                            firedAt = firedAt,
                            scheduledFor = scheduledFor,
                            latencyMs = (firedAt - scheduledFor).coerceAtLeast(0L).toInt(),
                            outcome = "errored"
                        )
                    )
                    lastFiredId = id
                    AndroidAlarmScheduler.notifyFired(id)
                    try {
                        storage.insertRawMessage("m_${UUID.randomUUID()}", "assistant", "Reminder could not alert you (notifications are disabled in Android Settings): $safeText")
                    } catch (_: Throwable) {}
                    return@launch
                }

                // P1-4: User-visible effect FIRST: post notification before deleting or mutating rows!
                manager.notify(id.hashCode(), notification)

                // D-124: durable write precedes completion signal
                lastFiredId = id
                AndroidAlarmScheduler.notifyFired(id)

                storage.recordReminderFire(
                    ReminderFire(
                        id = "f_${UUID.randomUUID()}",
                        reminderId = id,
                        firedAt = firedAt,
                        scheduledFor = scheduledFor,
                        latencyMs = (firedAt - scheduledFor).coerceAtLeast(0L).toInt(),
                        outcome = "delivered"
                    )
                )

                // A quiet line in today's chat too, so the conversation shows what fired. Never
                // instead of the notification (already posted above), and never fatal.
                try {
                    com.anchor.mine.notifications.ProactiveMessages.post(
                        context, storage, if (isMissed) safeText else "Reminder: $safeText",
                        source = "reminder", kind = com.anchor.core.ui.MessageAttrs.KIND_REMINDER, notify = false,
                    )
                } catch (_: Throwable) {}

                // Cleanup/advance recurrence LAST (after notification is posted)
                val rrule = rem?.rrule
                if (rem != null && !rrule.isNullOrBlank()) {
                    val now = System.currentTimeMillis()
                    val nextFireAt = com.anchor.core.recurrence.RecurrenceEngine.nextOccurrence(rrule, rem.fireAtMillis, now)
                    val nextRem = rem.copy(
                        fireAtMillis = nextFireAt,
                        iteration = rem.iteration + 1
                    )
                    storage.saveReminder(nextRem)
                    try {
                        DirectBootAlarmStore(context).saveAlarm(
                            id = nextRem.id,
                            fireAtMillis = nextRem.fireAtMillis,
                            tier = nextRem.tier,
                            label = "Reminder"
                        )
                    } catch (_: Throwable) {}
                    AndroidAlarmScheduler(context).schedule(nextRem)
                    val expectedApi = if (nextRem.tier == AlarmTier.GENTLE) "rtc_wakeup" else "alarm_clock"
                    try {
                        storage.saveRegisteredAlarm(nextRem.id, nextRem.id.hashCode(), nextRem.fireAtMillis, expectedApi)
                    } catch (_: Throwable) {}

                    if (linkedNode != null) {
                        try {
                            val updatedNode = linkedNode.copy(
                                dueAt = nextFireAt,
                                updatedAt = now
                            )
                            val event = com.anchor.core.models.RawEvent(
                                opId = "e_${UUID.randomUUID()}",
                                opType = "UPDATE_NODE",
                                targetType = "node",
                                targetId = updatedNode.id,
                                tsUtc = now
                            )
                            storage.updateNodeAndCommit(
                                node = updatedNode,
                                event = event,
                                deleteReminderId = null,
                                upsertReminder = nextRem
                            )
                        } catch (e: Throwable) {
                            android.util.Log.w("AlarmReceiver", "Failed to update node dueAt for recurring reminder", e)
                        }
                    }
                } else {
                    storage.deleteReminder(id) // Mark done only after notification delivered
                    try {
                        DirectBootAlarmStore(context).removeAlarm(id)
                    } catch (_: Throwable) {}
                    try {
                        storage.deleteRegisteredAlarm(id)
                    } catch (_: Throwable) {}
                }
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                // P0-4: If storage fails (locked out, crash, StackOverflowError, etc), do NOT fail silent.
                // Fall back to a blind notification instantly so the user doesn't miss the alarm.
                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                val channelId = NotificationChannels.channelId(fallbackTier)
                val priority = NotificationChannels.priority(fallbackTier)
                NotificationChannels.ensureCreated(context)

                val fallbackText = if (isMissed) "Missed: $text" else text
                val fallbackBuilder = NotificationCompat.Builder(context, channelId)
                    .setSmallIcon(com.anchor.mine.R.drawable.ic_notification)
                    .setContentTitle("Mine (Fallback)")
                    .setContentText(fallbackText)
                    .setPriority(priority)
                    .setCategory(if (fallbackTier == AlarmTier.ALARM) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_REMINDER)
                    .setAutoCancel(true)

                if (fallbackTier == AlarmTier.ALARM) {
                    val silenceIntent = Intent(context, ReminderActionReceiver::class.java).apply {
                        action = ReminderActionReceiver.ACTION_SILENCE
                        putExtra(ReminderActionReceiver.EXTRA_NOTIFICATION_ID, id.hashCode())
                    }
                    val silencePending = PendingIntent.getBroadcast(
                        context,
                        id.hashCode() + 4,
                        silenceIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    fallbackBuilder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Silence", silencePending)
                } else {
                    fallbackBuilder.addAction(com.anchor.mine.capture.CaptureNotifications.replyAction(context, id.hashCode()))
                }

                val notification = fallbackBuilder.build()
                if (fallbackTier == AlarmTier.ALARM && !isMissed) {
                    notification.flags = notification.flags or android.app.Notification.FLAG_INSISTENT
                }

                // POST NOTIFICATION FIRST! Before touching storage!
                manager.notify(id.hashCode(), notification)
                lastFiredId = id
                AndroidAlarmScheduler.notifyFired(id)

                // R-54: the fallback used to leave no trace at all, and the row stays pending.
                // Record "errored" against the reminder's fireAtMillis so sweeper dedupes.
                try {
                    val key = DbKeyProvider(context).getOrGenerateKey()
                    val db = DatabaseProvider.getHelper(context, key, "mine_encrypted.db")
                    try {
                        AndroidStorage(db).recordReminderFire(
                            ReminderFire(
                                id = "f_${UUID.randomUUID()}",
                                reminderId = id,
                                firedAt = System.currentTimeMillis(),
                                scheduledFor = if (intentScheduledFor > 0L) intentScheduledFor else System.currentTimeMillis(),
                                latencyMs = 0,
                                outcome = "errored"
                            )
                        )
                    } finally {
                        db.close()
                    }
                } catch (_: Throwable) {
                    // Storage is the thing that failed; nothing more can be recorded.
                }
            } finally {
                pendingResult?.finish()
            }
        }
    }

    private fun handleAgentWakeup(context: Context, intent: Intent) {
        val wakeupId = intent.getStringExtra("EXTRA_WAKEUP_ID")
        val pendingResult = try { goAsync() } catch (_: Exception) { null }
        val app = context.applicationContext as? MineApp
        val scope = app?.applicationScope ?: CoroutineScope(Dispatchers.IO)

        scope.launch {
            try {
                val storage = if (app != null) {
                    app.ensureStorageInitialized().await()
                } else {
                    val key = DbKeyProvider(context).getOrGenerateKey()
                    val db = DatabaseProvider.getHelper(context, key, "mine_encrypted.db")
                    AndroidStorage(db)
                }

                val now = System.currentTimeMillis()
                // P1-6: Unstick wakeups stuck in 'running' older than 10 minutes
                storage.unstickRunningWakeups(now - 600_000L)

                val wakeups = if (wakeupId != null) {
                    val single = storage.getAgentWakeup(wakeupId)
                    if (single != null && single.state == "pending") listOf(single) else emptyList()
                } else {
                    storage.getPendingAgentWakeups(now)
                }

                for (w in wakeups) {
                    storage.markAgentWakeupState(w.id, "running")
                    val eval = com.anchor.core.agent.AgentWakeupEvaluator.evaluate(w, storage, now, app?.contextEngine)
                    val snoozeTime = eval.snoozeUntilMs
                    if (snoozeTime != null) {
                        storage.saveAgentWakeup(w.copy(fireAt = snoozeTime, state = "pending"))
                        AndroidAlarmScheduler(context).scheduleAgentWakeup(w.id, snoozeTime)
                        continue
                    }
                    val text = eval.notificationText
                    if (eval.shouldNotify && !text.isNullOrBlank()) {
                        // A real chat message (tagged proactive), not just a notification.
                        val msgId = com.anchor.mine.notifications.ProactiveMessages.post(context, storage, text, source = "wakeup", notify = false)
                        val pState = storage.getProactiveState()
                        val dayRule = storage.getEffectiveDayRule(now)
                        val dayKey = com.anchor.core.time.DayRollover.computeDayKey(now, dayRule)
                        val nudgeEngine = com.anchor.core.nudge.NudgeEngine(object : com.anchor.core.time.Clock {
                            override fun now() = now
                        })
                        storage.saveProactiveState(nudgeEngine.onNudgeSent(pState, msgId, now, dayKey))
                        com.anchor.mine.notifications.ChatReplyNotifier.notify(context, text)
                        storage.markAgentWakeupState(w.id, "fired")
                    } else {
                        val finalState = if (eval.actionTaken.startsWith("suppressed_")) "cancelled" else "fired"
                        storage.markAgentWakeupState(w.id, finalState)
                    }
                }
                ProactiveAnchorScheduler.scheduleNextAnchors(context, storage)
            } catch (t: Throwable) {
                android.util.Log.e("AlarmReceiver", "Agent wakeup execution failed: ${t.message}", t)
            } finally {
                pendingResult?.finish()
            }
        }
    }
}
