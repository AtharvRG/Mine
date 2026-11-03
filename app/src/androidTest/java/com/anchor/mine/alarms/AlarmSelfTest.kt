// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.mine.alarms

import android.app.AlarmManager
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.anchor.core.models.AlarmTier
import com.anchor.core.models.Reminder
import com.anchor.mine.MineApp
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AlarmSelfTest {

    @Before
    fun setup() {
        AlarmReceiver.lastFiredId = null
    }

    @After
    fun teardown() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val storage = (context.applicationContext as MineApp).sharedStorage.await()
        storage.deleteReminder("test_alarm_1")
        storage.deleteReminder("test_gentle_1")
        val scheduler = AndroidAlarmScheduler(context)
        scheduler.cancel("test_alarm_1")
        scheduler.cancel("test_gentle_1")
    }

    @Test
    fun testAlarmFires_SetsFlag() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as MineApp
        val storage = app.sharedStorage.await()
        val scheduler = AndroidAlarmScheduler(context)

        val testId = "test_alarm_1"
        // Schedule for ~1.5 seconds in the future
        val fireTime = System.currentTimeMillis() + 1500L
        val reminder = Reminder(testId, "Test Reminder", fireTime, tier = AlarmTier.ALARM)

        storage.saveReminder(reminder)
        val scheduleResult = scheduler.schedule(reminder)
        assertTrue("Schedule must succeed as Armed unconditionally, but was $scheduleResult", scheduleResult is com.anchor.core.alarms.ScheduleResult.Armed)

        // Wait for Android's AlarmManager to wake us up (timeout at 10s)
        var elapsed = 0
        while (AlarmReceiver.lastFiredId != testId && elapsed < 10000) {
            kotlinx.coroutines.delay(250)
            elapsed += 250
        }

        assertEquals("Alarm receiver should have updated the static flag", testId, AlarmReceiver.lastFiredId)
    }

    @Test
    fun testAlarmClock_NextAlarmClockVerification() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val scheduler = AndroidAlarmScheduler(context)

        // Clear any previous registration
        scheduler.cancel("test_alarm_1")
        scheduler.cancel("test_gentle_1")

        val testId = "test_alarm_1"
        val fireTime = System.currentTimeMillis() + 15000L
        val alarmReminder = Reminder(testId, "Alarm Tier Test", fireTime, tier = AlarmTier.ALARM)

        val result = scheduler.schedule(alarmReminder)
        assertTrue("ALARM tier schedule must succeed as Armed unconditionally per D-102, but was $result", result is com.anchor.core.alarms.ScheduleResult.Armed)

        // Verify alarmManager.nextAlarmClock
        val nextClock = alarmManager.nextAlarmClock
        assertNotNull("nextAlarmClock must not be null for ALARM tier", nextClock)
        assertEquals("triggerTime must match fireTime", fireTime, nextClock!!.triggerTime)

        val creatorPackage = nextClock.showIntent?.creatorPackage
        assertEquals("creatorPackage must match app package", context.packageName, creatorPackage)

        // Cancel the alarm reminder
        scheduler.cancel(testId)

        // Now test GENTLE tier
        val gentleId = "test_gentle_1"
        val gentleFireTime = System.currentTimeMillis() + 120000L
        val gentleReminder = Reminder(gentleId, "Gentle Tier Test", gentleFireTime, tier = AlarmTier.GENTLE)

        val gentleResult = scheduler.schedule(gentleReminder)
        assertTrue("GENTLE tier schedule must succeed as Armed, but was $gentleResult", gentleResult is com.anchor.core.alarms.ScheduleResult.Armed)

        val nextClockAfterGentle = alarmManager.nextAlarmClock
        // The gentle reminder should NOT appear as nextAlarmClock
        if (nextClockAfterGentle != null) {
            assertNotEquals("GENTLE tier reminder must NOT appear as nextAlarmClock triggerTime", gentleFireTime, nextClockAfterGentle.triggerTime)
        }
    }
}