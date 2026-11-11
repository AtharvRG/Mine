// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.mine.alarms

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.anchor.core.models.AlarmTier
import com.anchor.core.models.Reminder
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DirectBootSafetyNetTest {

    private lateinit var store: DirectBootAlarmStore

    @Before
    fun setup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        store = DirectBootAlarmStore(context)
        store.clear()
        AlarmReceiver.lastFiredId = null
    }

    @After
    fun tearDown() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scheduler = AndroidAlarmScheduler(context)
        scheduler.cancel("r_db_1")
        scheduler.cancel("r_db_2")
        scheduler.cancel("r_sync_1")
        scheduler.cancel("r_sync_2")
        scheduler.cancel("r_del")
        scheduler.cancel("r_boot_locked")
        store.clear()
    }

    @Test
    fun testDirectBootStore_SavesAndRetrievesUpcomingAlarms_WithoutTouchingCredentialStorage() {
        val now = System.currentTimeMillis()
        val alarm1 = "r_db_1"
        val alarm2 = "r_db_2"

        store.saveAlarm(alarm1, now + 10_000, AlarmTier.ALARM, "Reminder")
        store.saveAlarm(alarm2, now + 50_000, AlarmTier.GENTLE, "Gentle Reminder")

        val upcoming = store.getUpcomingAlarms(now)
        assertEquals(2, upcoming.size)
        assertEquals(alarm1, upcoming[0].id)
        assertEquals(AlarmTier.ALARM, upcoming[0].tier)
        assertEquals("Reminder", upcoming[0].label)

        assertEquals(alarm2, upcoming[1].id)
        assertEquals(AlarmTier.GENTLE, upcoming[1].tier)
        assertEquals("Gentle Reminder", upcoming[1].label)
    }

    @Test
    fun testDirectBootStore_SyncFromReminders_MaintainsParityAndEnforcesGenericPrivacyLabel() {
        val now = System.currentTimeMillis()
        val reminders = listOf(
            Reminder("r_sync_1", "Secret private user doctor appointment", now + 20_000, tier = AlarmTier.ALARM),
            Reminder("r_sync_2", "Confidential financial review", now + 40_000, tier = AlarmTier.GENTLE)
        )

        store.syncFromReminders(reminders)

        val upcoming = store.getUpcomingAlarms(now)
        assertEquals(2, upcoming.size)

        // Invariant 1 (Privacy): Zero private text in Device-Protected Storage
        for (item in upcoming) {
            assertEquals("Reminder", item.label)
            assertTrue("Must not contain private text", !item.label.contains("Secret") && !item.label.contains("Confidential"))
        }

        val byId = store.getAlarmById("r_sync_1")
        assertNotNull(byId)
        assertEquals("Reminder", byId?.label)
    }

    @Test
    fun testDirectBootStore_RemoveAlarm_RemovesEntry() {
        val now = System.currentTimeMillis()
        store.saveAlarm("r_del", now + 30_000, AlarmTier.ALARM, "Reminder")
        assertNotNull(store.getAlarmById("r_del"))

        store.removeAlarm("r_del")
        assertNull(store.getAlarmById("r_del"))
    }

    @Test
    fun testDirectBootStore_RecordAndRetrieveFiredAlarms() {
        val now = System.currentTimeMillis()
        store.recordDirectBootFire("r_fired_1", now, now - 1000)
        store.recordDirectBootFire("r_fired_2", now + 2000, now + 1500)

        val fired = store.getFiredAlarms()
        assertEquals(2, fired.size)
        assertEquals("r_fired_1", fired[0].id)
        assertEquals(now, fired[0].firedAt)
        assertEquals(now - 1000, fired[0].scheduledFor)

        assertEquals("r_fired_2", fired[1].id)
        assertEquals(now + 2000, fired[1].firedAt)
        assertEquals(now + 1500, fired[1].scheduledFor)

        store.clearFiredAlarms()
        assertTrue(store.getFiredAlarms().isEmpty())
    }

    @Test
    fun testBootReceiver_HandlesLockedBootCompleted_WithoutCrashing() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val now = System.currentTimeMillis()
        store.saveAlarm("r_boot_locked", now + 15_000, AlarmTier.ALARM, "Reminder")

        val receiver = BootReceiver()
        val intent = Intent(Intent.ACTION_LOCKED_BOOT_COMPLETED)
        receiver.onReceive(context, intent)

        // Give the async IO coroutine time to complete
        delay(500)

        // Safety check: store still has the alarm armed
        assertNotNull(store.getAlarmById("r_boot_locked"))
    }
}
