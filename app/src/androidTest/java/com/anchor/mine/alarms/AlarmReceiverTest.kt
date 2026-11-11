// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.mine.alarms

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.anchor.core.models.Node
import com.anchor.core.models.Reminder
import com.anchor.mine.MineApp
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AlarmReceiverTest {
    @Before
    fun setup() {
        AlarmReceiver.lastFiredId = null
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
                context.packageName,
                android.Manifest.permission.POST_NOTIFICATIONS
            )
        }
    }

    @Test
    fun testAlarmReceiver_ValidReminder_FiresAndDeletesRow() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val storage = (context.applicationContext as MineApp).sharedStorage.await()
        
        val rId = "r_valid_test"
        val scheduledFor = System.currentTimeMillis() - 1000
        storage.saveReminder(Reminder(rId, "Test", scheduledFor))
        
        val receiver = AlarmReceiver()
        val intent = Intent().apply { putExtra("EXTRA_ID", rId) }
        receiver.onReceive(context, intent)
        
        var elapsed = 0
        while (AlarmReceiver.lastFiredId != rId && elapsed < 5000) {
            kotlinx.coroutines.delay(250)
            elapsed += 250
        }
        
        assertEquals(rId, AlarmReceiver.lastFiredId)
        assertNull("Row must be deleted after firing", storage.getPendingReminders().find { it.id == rId })

        // D-124: a delivered reminder leaves a latency record, mirroring the missed path.
        val fire = storage.getReminderFire(rId, "delivered")
        assertNotNull("Delivered reminder must record a reminder_fire row", fire)
        assertEquals("scheduledFor must be the reminder's own fire time", scheduledFor, fire!!.scheduledFor)
        assertTrue("latency must be at least the 1 s the reminder was already overdue", fire.latencyMs >= 1000)
        assertEquals("firedAt - scheduledFor must equal latencyMs", fire.latencyMs, (fire.firedAt - fire.scheduledFor).toInt())
    }

    @Test
    fun testAlarmReceiver_CancelledReminder_DoesNotFire() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        
        val rId = "r_cancelled_test"
        // Intentionally NOT saving to DB to simulate a cancelled/deleted reminder
        
        val receiver = AlarmReceiver()
        val intent = Intent().apply { putExtra("EXTRA_ID", rId) }
        receiver.onReceive(context, intent)
        
        kotlinx.coroutines.delay(1000) // Wait to ensure it doesn't fire

        assertNull("Must not fire if reminder row is missing", AlarmReceiver.lastFiredId)
    }

    @Test
    fun testAlarmReceiver_MissedReminder_PrefixesMissedText() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val storage = (context.applicationContext as MineApp).sharedStorage.await()

        val rId = "r_missed_${System.currentTimeMillis()}"
        storage.saveReminder(Reminder(rId, "Doctor Appointment", System.currentTimeMillis() - 70000L))

        val receiver = AlarmReceiver()
        val intent = Intent().apply {
            putExtra("EXTRA_ID", rId)
            putExtra("EXTRA_MISSED", true)
        }
        receiver.onReceive(context, intent)

        var elapsed = 0
        while (AlarmReceiver.lastFiredId != rId && elapsed < 5000) {
            kotlinx.coroutines.delay(250)
            elapsed += 250
        }

        assertEquals(rId, AlarmReceiver.lastFiredId)

        val notificationManager = context.getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        val channel = notificationManager.getNotificationChannel("mine_reminders_v3")
        org.junit.Assert.assertNotNull("Channel must exist", channel)
        org.junit.Assert.assertFalse("Notification channel must NOT vibrate", channel!!.shouldVibrate())

        var active: android.service.notification.StatusBarNotification? = null
        var notifElapsed = 0
        while (active == null && notifElapsed < 3000) {
            active = notificationManager.activeNotifications.find { it.id == rId.hashCode() }
            if (active == null) {
                kotlinx.coroutines.delay(100)
                notifElapsed += 100
            }
        }

        org.junit.Assert.assertNotNull("Notification must be posted", active)
        val text = active!!.notification.extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString()
        assertEquals("Missed: Doctor Appointment", text)

        notificationManager.cancel(rId.hashCode())
    }

    /**
     * R-54: when storage fails, the catch-all fallback still posts a notification. Before the fix
     * it recorded nothing, so the next sweeper saw an unreported instance and posted a SECOND
     * notification for one fire. The fallback must record `outcome = "errored"` against the
     * reminder's own fire instant (carried on the intent as EXTRA_SCHEDULED_FOR), and the
     * reconciler must then treat that instance as already reported.
     */
    @Test
    fun testAlarmReceiver_StorageFails_RecordsErroredFireAndSweeperDedupes() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as MineApp
        val originalDeferred = app.sharedStorage
        val realStorage = originalDeferred.await()

        // The reminder's own fire instant, far enough in the past to be "overdue" (> 60 s grace)
        // so the reconciler would report it if it did not see the errored fire.
        val rId = "r_errored_${System.currentTimeMillis()}"
        val scheduledFor = System.currentTimeMillis() - 120_000L
        realStorage.saveReminder(Reminder(rId, "Errored Path", scheduledFor))

        // Inject the failure the fallback is meant to survive: every await() now throws.
        val failed = kotlinx.coroutines.CompletableDeferred<com.anchor.core.storage.Storage>()
        failed.completeExceptionally(IllegalStateException("R-54 injected storage failure"))
        app.sharedStorage = failed
        try {
            val receiver = AlarmReceiver()
            val intent = Intent().apply {
                putExtra("EXTRA_ID", rId)
                putExtra("EXTRA_TEXT", "Errored Path")
                putExtra("EXTRA_SCHEDULED_FOR", scheduledFor)
            }
            receiver.onReceive(context, intent)

            // The fallback runs on Dispatchers.IO; poll for its durable write.
            var fire: com.anchor.core.models.ReminderFire? = null
            var elapsed = 0
            while (fire == null && elapsed < 5000) {
                kotlinx.coroutines.delay(250)
                elapsed += 250
                fire = realStorage.getReminderFire(rId, "errored")
            }
            assertNotNull("R-54: fallback must record an 'errored' fire", fire)
            assertEquals(
                "R-54: scheduledFor must be the reminder's own fire instant, not 'now'",
                scheduledFor,
                fire!!.scheduledFor
            )

            // The row must survive: a flaky storage must not lose the reminder.
            assertNotNull(
                "The reminder row must stay pending after a fallback",
                realStorage.getPendingReminders().find { it.id == rId }
            )

            // And the reconciler must not report this instance overdue a second time.
            val reconciler = com.anchor.core.alarms.AlarmReconciler(
                realStorage,
                AndroidAlarmScheduler(context),
                com.anchor.core.time.SystemClock()
            )
            val results = reconciler.reconcile(force = false)
            val overdueForThis = results.filterIsInstance<com.anchor.core.alarms.ScheduleResult.Overdue>()
                .filter { it.reminderId == rId }
            assertTrue(
                "R-54: an errored fire must dedupe the overdue report, got $overdueForThis",
                overdueForThis.isEmpty()
            )
        } finally {
            app.sharedStorage = originalDeferred
            realStorage.deleteReminder(rId)
        }
    }

    @Test
    fun testAlarmReceiver_LinkedNodeDone_SuppressesAndRecordsSuppressedFire() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val storage = (context.applicationContext as MineApp).sharedStorage.await()

        val nodeId = "n_done_${System.currentTimeMillis()}"
        val eDone = com.anchor.core.models.RawEvent("e_done_${System.currentTimeMillis()}", "CREATE", "node", nodeId)
        storage.createNodeAndCommit(Node(id = nodeId, type = "task", title = "Completed Task", status = "done"), eDone, null)

        val rId = "r_linked_done_${System.currentTimeMillis()}"
        val scheduledFor = System.currentTimeMillis() - 1000
        storage.saveReminder(Reminder(rId, "Do Task", scheduledFor, nodeId = nodeId))

        val receiver = AlarmReceiver()
        val intent = Intent().apply { putExtra("EXTRA_ID", rId) }
        receiver.onReceive(context, intent)

        var elapsed = 0
        while (AlarmReceiver.lastFiredId != rId && elapsed < 5000) {
            kotlinx.coroutines.delay(250)
            elapsed += 250
        }

        assertEquals(rId, AlarmReceiver.lastFiredId)
        assertNull("Row must be deleted when suppressed", storage.getPendingReminders().find { it.id == rId })

        val fire = storage.getReminderFire(rId, "suppressed")
        assertNotNull("Suppressed reminder must record a 'suppressed' reminder_fire row", fire)

        // Ensure notification was NOT posted
        val notificationManager = context.getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        val active = notificationManager.activeNotifications.find { it.id == rId.hashCode() }
        assertNull("Notification must not be posted for suppressed reminder", active)
    }

    @Test
    fun testAlarmReceiver_LinkedNodeInProgress_TransformsStartToCheck() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val storage = (context.applicationContext as MineApp).sharedStorage.await()

        val nodeId = "n_prog_${System.currentTimeMillis()}"
        val eProg = com.anchor.core.models.RawEvent("e_prog_${System.currentTimeMillis()}", "CREATE", "node", nodeId)
        storage.createNodeAndCommit(Node(id = nodeId, type = "task", title = "In Progress Task", status = "in_progress"), eProg, null)

        val rId = "r_linked_prog_${System.currentTimeMillis()}"
        val scheduledFor = System.currentTimeMillis() - 1000
        storage.saveReminder(Reminder(rId, "start writing report", scheduledFor, nodeId = nodeId))

        val receiver = AlarmReceiver()
        val intent = Intent().apply { putExtra("EXTRA_ID", rId) }
        receiver.onReceive(context, intent)

        var elapsed = 0
        while (AlarmReceiver.lastFiredId != rId && elapsed < 5000) {
            kotlinx.coroutines.delay(250)
            elapsed += 250
        }

        assertEquals(rId, AlarmReceiver.lastFiredId)

        val notificationManager = context.getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        var active: android.service.notification.StatusBarNotification? = null
        var notifElapsed = 0
        while (active == null && notifElapsed < 3000) {
            active = notificationManager.activeNotifications.find { it.id == rId.hashCode() }
            if (active == null) {
                kotlinx.coroutines.delay(100)
                notifElapsed += 100
            }
        }

        assertNotNull("Notification must be posted", active)
        val text = active!!.notification.extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString()
        assertEquals("Check: writing report", text)

        notificationManager.cancel(rId.hashCode())
    }
}
