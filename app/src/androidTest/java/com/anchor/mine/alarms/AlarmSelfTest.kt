// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.mine.alarms

import android.app.AlarmManager
import android.content.Context
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.anchor.core.models.Reminder
import com.anchor.mine.MineApp
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AlarmSelfTest {

    @Before
    fun setup() {
        GlobalAlarmState.lastFired.value = null
        AlarmReceiver.lastFiredId = null
    }

    @After
    fun teardown() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        AndroidAlarmScheduler(context).cancel("test_alarm_1")
    }

    @Test
    fun testAlarmFires_SetsFlag() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        // Skip test on Android 14+ if the CI environment denies the permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            return@runBlocking
        }

        val app = context.applicationContext as MineApp
        val storage = app.sharedStorage.await()
        val scheduler = AndroidAlarmScheduler(context)

        val testId = "test_alarm_1"
        // Schedule for ~1.5 seconds in the future
        val fireTime = System.currentTimeMillis() + 1500L
        val reminder = Reminder(testId, "Test Reminder", fireTime)

        storage.saveReminder(reminder)
        scheduler.schedule(reminder)

        // Wait for Android's AlarmManager to wake us up (timeout at 10s)
        var elapsed = 0
        while (scheduler.lastFiredId.value != testId && elapsed < 10000) {
            kotlinx.coroutines.delay(250)
            elapsed += 250
        }

        assertEquals("Alarm receiver should have updated the static flag", testId, scheduler.lastFiredId.value)
    }
}