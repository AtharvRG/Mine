// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.mine.alarms

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.anchor.core.models.Reminder
import com.anchor.mine.MineApp
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * B5: BootReceiver must only do work for the intended system actions.
 * Any other broadcast (custom action, unrelated protected action) must be a
 * no-op: no reconciliation, no sweep, no notification, no reminder deletion.
 */
@RunWith(AndroidJUnit4::class)
class BootReceiverIgnoreTest {

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
    fun testBootReceiver_IgnoresNonIntendedActions_NoSideEffects(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val storage = (context.applicationContext as MineApp).sharedStorage.await()
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // A stale reminder older than the 60s grace window: the sweeper WOULD
        // fire it if the receiver did work. This is our canary for "work happened".
        val rId = "r_boot_ignore_${System.currentTimeMillis()}"
        val reminder = Reminder(rId, "Canary Appointment", System.currentTimeMillis() - 75000L)
        storage.saveReminder(reminder)
        assertNotNull("Canary reminder must be pending before broadcast", storage.getPendingReminders().find { it.id == rId })

        val receiver = BootReceiver()

        // 1. Custom app action (never in validActions)
        receiver.onReceive(context, Intent("com.anchor.mine.FAKE_ACTION"))
        // 2. Unrelated protected system action an attacker/other app could send to an exported receiver
        receiver.onReceive(context, Intent(Intent.ACTION_POWER_CONNECTED))
        // 3. Null action is impossible via Intent(String) but empty-string action is constructible
        receiver.onReceive(context, Intent(""))

        delay(1500) // Allow any asynchronous work to (wrongly) run

        assertNull("No alarm must fire for non-intended actions", AlarmReceiver.lastFiredId)
        assertNull("No notification must be posted for non-intended actions",
            notificationManager.activeNotifications.find { it.id == rId.hashCode() })
        assertTrue("No missed fire must be recorded for non-intended actions", !storage.hasReminderFire(rId, "missed"))
        assertNotNull("Canary reminder row must survive non-intended broadcasts",
            storage.getPendingReminders().find { it.id == rId })

        // Control: the SAME canary setup must fire when the intended action arrives.
        receiver.onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
        var elapsed = 0
        while (AlarmReceiver.lastFiredId != rId && elapsed < 6000) {
            delay(250)
            elapsed += 250
        }
        assertEquals("Control: intended action must sweep the stale canary", rId, AlarmReceiver.lastFiredId)
        assertNull("Control: canary row must be deleted after intended action",
            storage.getPendingReminders().find { it.id == rId })
    }
}
