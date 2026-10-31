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
}
