// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine Author
// Part of Mine. See LICENSE in the repository root.

package com.anchor.mine.alarms

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.anchor.core.alarms.Reminder
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AlarmSelfTest {

    @Before
    fun setup() {
        AlarmReceiver.lastFiredId = null
    }

    @Test
    fun testAlarmFires_SetsFlag() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scheduler = AndroidAlarmScheduler(context)

        val testId = "test_alarm_1"
        // Schedule for ~1.5 seconds in the future
        val fireTime = System.currentTimeMillis() + 1500L

        scheduler.schedule(Reminder(testId, "Test Reminder", fireTime))

        // Wait for Android's AlarmManager to wake us up (timeout at 10s)
        var elapsed = 0
        while (AlarmReceiver.lastFiredId != testId && elapsed < 10000) {
            Thread.sleep(250)
            elapsed += 250
        }

        assertEquals("Alarm receiver should have updated the static flag", testId, AlarmReceiver.lastFiredId)
    }
}