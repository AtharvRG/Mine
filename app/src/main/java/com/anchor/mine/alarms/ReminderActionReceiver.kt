// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.mine.alarms

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.anchor.core.models.AlarmTier
import com.anchor.core.models.NodeStateSnapshot
import com.anchor.core.models.RawEvent
import com.anchor.core.models.Reminder
import com.anchor.mine.MineApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

class ReminderActionReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_DONE = "com.anchor.mine.alarms.ACTION_DONE"
        const val ACTION_SNOOZE = "com.anchor.mine.alarms.ACTION_SNOOZE"
        const val ACTION_SILENCE = "com.anchor.mine.alarms.ACTION_SILENCE"

        const val EXTRA_REMINDER_ID = "EXTRA_REMINDER_ID"
        const val EXTRA_NODE_ID = "EXTRA_NODE_ID"
        const val EXTRA_NOTIFICATION_ID = "EXTRA_NOTIFICATION_ID"
        const val EXTRA_TEXT = "EXTRA_TEXT"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)
        if (notificationId != -1) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.cancel(notificationId)
        }

        val action = intent.action ?: return
        if (action == ACTION_SILENCE) {
            return
        }

        val pendingResult = try { goAsync() } catch (_: Exception) { null }
        val app = context.applicationContext as? MineApp
        val scope = app?.applicationScope ?: CoroutineScope(Dispatchers.IO)

        scope.launch {
            try {
                val storage = app?.sharedStorage?.await() ?: return@launch
                val now = System.currentTimeMillis()

                when (action) {
                    ACTION_DONE -> {
                        val nodeId = intent.getStringExtra(EXTRA_NODE_ID)
                        val reminderId = intent.getStringExtra(EXTRA_REMINDER_ID)
                        if (nodeId != null) {
                            val node = storage.getNode(nodeId)
                            if (node != null && node.status != "done") {
                                val beforeSnapshot = NodeStateSnapshot(node, null)
                                val updatedNode = node.copy(status = "done", closedAt = now, updatedAt = now)
                                val afterSnapshot = NodeStateSnapshot(updatedNode, null)
                                val event = RawEvent(
                                    opId = "op_${UUID.randomUUID()}",
                                    opType = "UPDATE",
                                    targetType = "node",
                                    targetId = node.id,
                                    beforeJson = Json.encodeToString(beforeSnapshot),
                                    afterJson = Json.encodeToString(afterSnapshot),
                                    tsUtc = now
                                )
                                storage.updateNodeAndCommit(updatedNode, event, deleteReminderId = reminderId)
                            } else if (reminderId != null) {
                                storage.deleteReminder(reminderId)
                            }
                        } else if (reminderId != null) {
                            storage.deleteReminder(reminderId)
                        }

                        if (reminderId != null) {
                            try {
                                DirectBootAlarmStore(context).removeAlarm(reminderId)
                            } catch (_: Exception) {}
                        }
                    }
                    ACTION_SNOOZE -> {
                        val reminderId = intent.getStringExtra(EXTRA_REMINDER_ID) ?: return@launch
                        val text = intent.getStringExtra(EXTRA_TEXT) ?: "Reminder"
                        val snoozeFireAt = now + 10 * 60_000L // 10 minutes
                        val snoozedReminder = Reminder(
                            id = reminderId,
                            text = text,
                            fireAtMillis = snoozeFireAt,
                            tier = AlarmTier.ALARM
                        )
                        storage.saveReminder(snoozedReminder)
                        try {
                            DirectBootAlarmStore(context).saveAlarm(
                                id = reminderId,
                                fireAtMillis = snoozeFireAt,
                                tier = snoozedReminder.tier,
                                label = text
                            )
                        } catch (_: Exception) {}
                        AndroidAlarmScheduler(context).schedule(snoozedReminder)
                    }
                }
            } catch (t: Throwable) {
                android.util.Log.e("ReminderActionReceiver", "Failed handling reminder action $action", t)
            } finally {
                pendingResult?.finish()
            }
        }
    }
}
