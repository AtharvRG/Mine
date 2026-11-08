// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.mine.alarms

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.service.notification.StatusBarNotification
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.anchor.core.models.Reminder
import com.anchor.mine.MineApp
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BootReceiverTest {

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
    fun testBootReceiver_SweepsMissedReminder_NotifiesOnceAndDeletesRow(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val storage = (context.applicationContext as MineApp).sharedStorage.await()
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val rId = "r_boot_missed_${System.currentTimeMillis()}"
        val initialFireAt = System.currentTimeMillis() - 75000L // 75 seconds ago (older than 60s grace)
        val reminder = Reminder(rId, "Doctor Appointment", initialFireAt)

        storage.saveReminder(reminder)
        assertNotNull("Reminder must be pending before boot", storage.getPendingReminders().find { it.id == rId })

        // First Boot Completed Broadcast
        val bootReceiver = BootReceiver()
        val bootIntent = Intent(Intent.ACTION_BOOT_COMPLETED)
        bootReceiver.onReceive(context, bootIntent)

        // Wait for BootReceiver -> Sweeper -> AlarmReceiver pipeline to complete
        var elapsed = 0
        while (AlarmReceiver.lastFiredId != rId && elapsed < 6000) {
            delay(250)
            elapsed += 250
        }

        assertEquals("AlarmReceiver must fire for the missed reminder on boot", rId, AlarmReceiver.lastFiredId)

        // 1. Verify notification has "Missed: Doctor Appointment"
        var active: StatusBarNotification? = null
        var notifElapsed = 0
        while (active == null && notifElapsed < 3000) {
            active = notificationManager.activeNotifications.find { it.id == rId.hashCode() }
            if (active == null) {
                delay(100)
                notifElapsed += 100
            }
        }
        assertNotNull("Notification must be posted for missed reminder", active)
        val text = active!!.notification.extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString()
        assertEquals("Missed: Doctor Appointment", text)

        // 2. Verify missed fire recorded once in database
        assertTrue("Reminder fire must be recorded as missed", storage.hasReminderFire(rId, "missed"))

        // 3. Verify reminder row deleted from pending reminders
        assertNull("Reminder row must be deleted after firing", storage.getPendingReminders().find { it.id == rId })

        // 4. Second boot must NOT notify again
        AlarmReceiver.lastFiredId = null
        notificationManager.cancel(rId.hashCode())

        bootReceiver.onReceive(context, bootIntent)
        delay(1500) // Allow any asynchronous sweep to process

        assertNull("Second boot must not fire or notify again", AlarmReceiver.lastFiredId)
        val secondActive = notificationManager.activeNotifications.find { it.id == rId.hashCode() }
        assertNull("No new notification should be posted on second boot", secondActive)
    }

    /**
     * H-02 (R-02): the sweeper ran only at boot and at app open, so a reminder missed while the
     * app stayed closed was never reported until the user happened to open it — the exact moment
     * they no longer needed the notice.
     *
     * The observation channel is the same one H-03 uses, for the same reason: a repeating inexact
     * alarm is invisible to the app (`getNextAlarmClock()` only reports `setAlarmClock`) and
     * `dumpsys alarm` needs the signature-level DUMP permission. So this asserts the two things
     * that are observable — a periodic trigger gets registered with a bounded period, and the
     * `ACTION_SWEEP` broadcast it will deliver actually sweeps and reports.
     */
    @Test
    fun testH02_Sweeper_IsScheduledPeriodically_AndItsBroadcastSweeps(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val storage = (context.applicationContext as MineApp).sharedStorage.await()
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Registration: the app must arm a periodic trigger on start, not only on boot.
        val before = BootReceiver.sweepsArmed
        BootReceiver.schedulePeriodicSweep(context)
        assertEquals(
            "H-02: a periodic sweeper must be armed (before=$before)",
            before + 1,
            BootReceiver.sweepsArmed
        )
        assertTrue(
            "H-02: the sweeper period must be bounded — a day-long period is the defect",
            BootReceiver.SWEEP_INTERVAL_MS in 1..(60L * 60 * 1000)
        )

        // Delivery: the broadcast the trigger delivers must actually run the sweeper.
        val rId = "r_h02_${System.currentTimeMillis()}"
        storage.saveReminder(Reminder(rId, "H02 missed", System.currentTimeMillis() - 75000L))
        notificationManager.cancel(rId.hashCode())
        AlarmReceiver.lastFiredId = null

        BootReceiver().onReceive(context, Intent(BootReceiver.ACTION_SWEEP))

        var elapsed = 0
        while (AlarmReceiver.lastFiredId != rId && elapsed < 6000) {
            delay(250)
            elapsed += 250
        }
        assertEquals("H-02: the periodic sweep broadcast must report the missed reminder", rId, AlarmReceiver.lastFiredId)
        assertTrue("H-02: the missed fire must be recorded", storage.hasReminderFire(rId, "missed"))
        assertNull(
            "H-02: the missed reminder must be deleted from pending",
            storage.getPendingReminders().find { it.id == rId }
        )
    }

    @Test
    fun testBootReceiver_StorageFails_StillArmsBoundedRetry(): Unit = runBlocking {
        // H-03 (R-03): a Keystore failure used to skip reconcile, sweeper AND quote rescheduling
        // for the whole boot, with no retry — every reminder stayed disarmed until the user
        // opened the app. The receiver must still arm a bounded retry on the same path.
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as MineApp
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager

        // An inexact alarm cannot be observed from the app (getNextAlarmClock() ignores set(), and
        // dumpsys needs the signature DUMP permission), so the arming counter is the observation
        // channel. A pre-existing slot is armed here too, to prove the receiver re-arms rather
        // than the test merely seeing its own setup.
        BootReceiver.scheduleStorageRetry(context, 7L * 24 * 3600 * 1000L)
        val before = BootReceiver.retriesArmed

        // Only this test may replace the process-wide deferred; it is restored in finally.
        val original = app.sharedStorage
        // Read back through the real storage: the receiver records the failure by re-opening, not
        // through the injected (already-failed) deferred.
        val realStorage = original.await()
        try {
            val failed = kotlinx.coroutines.CompletableDeferred<com.anchor.core.storage.Storage>()
            failed.completeExceptionally(IllegalStateException("KEYSTORE_LOST"))
            app.sharedStorage = failed

            BootReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))

            // The receiver does its work on Dispatchers.IO; poll the counter.
            var elapsed = 0
            while (BootReceiver.retriesArmed == before && elapsed < 5000) {
                delay(100)
                elapsed += 100
            }
            assertEquals("A storage failure at boot must still arm a bounded retry", before + 1, BootReceiver.retriesArmed)

            // The failure must also be visible to the app, not just retried silently.
            val recorded = realStorage.getReliabilityState()
            assertEquals("Storage failure must be recorded as at_risk", "at_risk", recorded.score)
            assertTrue(
                "Storage failure reason must be in the issues list, was: ${recorded.issuesJson}",
                recorded.issuesJson?.contains("Storage unavailable at boot") == true
            )
        } finally {
            app.sharedStorage = original
            alarmManager.cancel(android.app.PendingIntent.getBroadcast(
                context,
                BootReceiver.REQUEST_CODE_STORAGE_RETRY,
                Intent(context, BootReceiver::class.java).setAction(BootReceiver.ACTION_STORAGE_RETRY),
                android.app.PendingIntent.FLAG_NO_CREATE or android.app.PendingIntent.FLAG_IMMUTABLE
            ))
        }
    }
}
