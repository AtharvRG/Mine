// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.mine.alarms

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.anchor.core.models.Reminder
import com.anchor.mine.MineApp
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        storage.saveReminder(Reminder(rId, "Test", System.currentTimeMillis() - 1000))
        
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
}
