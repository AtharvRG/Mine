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
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StatusBarAlarmIconTest {

    @Before
    @After
    fun cleanUpAll() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scheduler = AndroidAlarmScheduler(context)
        scheduler.cancel("d105_alarm_test")
        scheduler.cancel("d105_gentle_test")
    }

    @Test
    fun scheduleAlarmTierAndHold() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scheduler = AndroidAlarmScheduler(context)
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        val testId = "d105_alarm_test"
        // 10 minutes in future
        val fireTime = System.currentTimeMillis() + 600_000L
        val reminder = Reminder(testId, "D105 Alarm Tier Reminder", fireTime, tier = AlarmTier.ALARM)

        val result = scheduler.schedule(reminder)
        assertTrue(result is com.anchor.core.alarms.ScheduleResult.Armed)

        val nextClock = alarmManager.nextAlarmClock
        assertNotNull("nextAlarmClock must not be null for ALARM tier", nextClock)
        assertEquals(fireTime, nextClock!!.triggerTime)

        delay(200L)
    }

    @Test
    fun scheduleGentleTierAndHold() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scheduler = AndroidAlarmScheduler(context)
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        // Ensure alarm tier is cancelled first
        scheduler.cancel("d105_alarm_test")

        val testId = "d105_gentle_test"
        // 10 minutes in future
        val fireTime = System.currentTimeMillis() + 600_000L
        val reminder = Reminder(testId, "D105 Gentle Tier Reminder", fireTime, tier = AlarmTier.GENTLE)

        val result = scheduler.schedule(reminder)
        assertTrue(result is com.anchor.core.alarms.ScheduleResult.Armed)

        // NextAlarmClock should NOT be set to this reminder's fireTime
        val nextClock = alarmManager.nextAlarmClock
        assertTrue(
            "GENTLE tier reminder must NOT appear as nextAlarmClock triggerTime",
            nextClock == null || nextClock.triggerTime != fireTime
        )

        delay(200L)
    }
}
