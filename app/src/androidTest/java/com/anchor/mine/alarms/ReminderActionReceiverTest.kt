// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.mine.alarms

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.anchor.core.models.Node
import com.anchor.core.models.NodeStateSnapshot
import com.anchor.core.models.RawEvent
import com.anchor.core.models.Reminder
import com.anchor.mine.MineApp
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ReminderActionReceiverTest {

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setup() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
                context.packageName,
                android.Manifest.permission.POST_NOTIFICATIONS
            )
        }
    }

    @Test
    fun testActionDone_MarksNodeDoneAndRemovesReminder() = runBlocking {
        val app = context.applicationContext as MineApp
        val storage = app.sharedStorage.await()
        val now = System.currentTimeMillis()

        val nodeId = "n_test_done_${UUID.randomUUID()}"
        val reminderId = "r_test_done_${UUID.randomUUID()}"

        val node = Node(
            id = nodeId,
            type = "task",
            title = "Task to complete",
            status = "active",
            createdAt = now
        )
        val event = RawEvent(
            opId = "op_${UUID.randomUUID()}",
            opType = "CREATE",
            targetType = "node",
            targetId = nodeId,
            beforeJson = null,
            afterJson = Json.encodeToString(NodeStateSnapshot(node, null)),
            tsUtc = now
        )
        val reminder = Reminder(id = reminderId, text = "Task to complete", fireAtMillis = now + 10000L, nodeId = nodeId)
        storage.createNodeAndCommit(node, event, reminder = reminder)
        DirectBootAlarmStore(context).saveAlarm(reminderId, now + 10000L, reminder.tier, reminder.text)

        val receiver = ReminderActionReceiver()
        val intent = Intent().apply {
            action = ReminderActionReceiver.ACTION_DONE
            putExtra(ReminderActionReceiver.EXTRA_NODE_ID, nodeId)
            putExtra(ReminderActionReceiver.EXTRA_REMINDER_ID, reminderId)
            putExtra(ReminderActionReceiver.EXTRA_NOTIFICATION_ID, 12345)
        }

        receiver.onReceive(context, intent)

        var elapsed = 0
        while (storage.getNode(nodeId)?.status != "done" && elapsed < 3000) {
            delay(100)
            elapsed += 100
        }

        val updatedNode = storage.getNode(nodeId)
        assertNotNull(updatedNode)
        assertEquals("done", updatedNode?.status)
        assertNull(storage.getReminderById(reminderId))
        assertNull(DirectBootAlarmStore(context).getAlarmById(reminderId))
    }

    @Test
    fun testActionSnooze_ReschedulesReminderPlus10Minutes() = runBlocking {
        val app = context.applicationContext as MineApp
        val storage = app.sharedStorage.await()
        val now = System.currentTimeMillis()

        val reminderId = "r_test_snooze_${UUID.randomUUID()}"
        val initialReminder = Reminder(id = reminderId, text = "Snooze me", fireAtMillis = now - 5000L)
        storage.saveReminder(initialReminder)

        val receiver = ReminderActionReceiver()
        val intent = Intent().apply {
            action = ReminderActionReceiver.ACTION_SNOOZE
            putExtra(ReminderActionReceiver.EXTRA_REMINDER_ID, reminderId)
            putExtra(ReminderActionReceiver.EXTRA_TEXT, "Snooze me")
            putExtra(ReminderActionReceiver.EXTRA_NOTIFICATION_ID, 54321)
        }

        receiver.onReceive(context, intent)

        var elapsed = 0
        var snoozed: Reminder? = null
        while (elapsed < 3000) {
            val r = storage.getReminderById(reminderId)
            if (r != null && r.fireAtMillis > now + 500_000L) {
                snoozed = r
                break
            }
            delay(100)
            elapsed += 100
        }

        assertNotNull("Reminder must be rescheduled to future (+10m)", snoozed)
        assertTrue(snoozed!!.fireAtMillis >= now + 9 * 60_000L)
        val deAlarm = DirectBootAlarmStore(context).getAlarmById(reminderId)
        assertNotNull(deAlarm)
        assertTrue(deAlarm!!.fireAtMillis >= now + 9 * 60_000L)
    }

    @Test
    fun testActionSilence_CancelsNotificationWithoutAffectingDatabase() = runBlocking {
        val app = context.applicationContext as MineApp
        val storage = app.sharedStorage.await()
        val now = System.currentTimeMillis()

        val reminderId = "r_test_silence_${UUID.randomUUID()}"
        val initialReminder = Reminder(id = reminderId, text = "Silence me", fireAtMillis = now + 10000L)
        storage.saveReminder(initialReminder)

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notifId = 998877

        val receiver = ReminderActionReceiver()
        val intent = Intent().apply {
            action = ReminderActionReceiver.ACTION_SILENCE
            putExtra(ReminderActionReceiver.EXTRA_NOTIFICATION_ID, notifId)
        }

        receiver.onReceive(context, intent)
        delay(300)

        // Reminder row remains untouched
        assertNotNull(storage.getReminderById(reminderId))
    }
}
