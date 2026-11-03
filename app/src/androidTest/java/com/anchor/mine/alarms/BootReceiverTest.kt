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
}
