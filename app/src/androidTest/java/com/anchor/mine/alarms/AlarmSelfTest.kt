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
        // The real delivery path posts a notification; without this grant the notify() call is
        // silently dropped and the failure surfaces only as "did not fire", hiding the real cause.
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
                context.packageName,
                android.Manifest.permission.POST_NOTIFICATIONS
            )
        }
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
        // 5 s, not 1.5 s: the ALARM tier is the one that goes through setAlarmClock, and a
        // 1.5 s window is inside the noise of a loaded device. The poll deadline is separate
        // and longer, so a late-but-real delivery is still observed rather than reported as
        // "never fired".
        val fireTime = System.currentTimeMillis() + 5000L
        val reminder = Reminder(testId, "Test Reminder", fireTime, tier = AlarmTier.ALARM)

        storage.saveReminder(reminder)
        val scheduleResult = scheduler.schedule(reminder)
        assertTrue("Schedule must succeed as Armed unconditionally, but was $scheduleResult", scheduleResult is com.anchor.core.alarms.ScheduleResult.Armed)

        // Wait for Android's AlarmManager to wake us up (timeout at 20s = fire time + 15s slack)
        var elapsed = 0
        while (AlarmReceiver.lastFiredId != testId && elapsed < 20000) {
            kotlinx.coroutines.delay(250)
            elapsed += 250
        }

        if (AlarmReceiver.lastFiredId != testId) {
            // A bare assertEquals prints only "expected:<x> but was:<y>". When this fires we need
            // to know *why*: still armed at the deadline (never delivered), already gone
            // (delivered but the receiver bailed), or a different id fired.
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val nextClock = alarmManager.nextAlarmClock?.triggerTime
            val rowStillPending = storage.getPendingReminders().any { it.id == testId }
            org.junit.Assert.fail(
                "ALARM tier did not fire within ${elapsed}ms. " +
                    "lastFiredId=${AlarmReceiver.lastFiredId} " +
                    "nextAlarmClock=$nextClock fireTime=$fireTime " +
                    "stillNextAlarmClock=${nextClock == fireTime} " +
                    "reminderRowStillPending=$rowStillPending " +
                    "schedulerFiredFlow=${AndroidAlarmScheduler.firedFlow.value}"
            )
        }
    }

    @Test
    fun testGentleReminderFires_SetsFlag() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as MineApp
        val storage = app.sharedStorage.await()
        val scheduler = AndroidAlarmScheduler(context)

        val testId = "test_gentle_1"
        // Same 5 s window as the ALARM-tier test: one timing constant for both tiers, so a
        // failure in one is comparable with the other.
        val fireTime = System.currentTimeMillis() + 5000L
        val reminder = Reminder(testId, "Gentle Test Reminder", fireTime, tier = AlarmTier.GENTLE)

        storage.saveReminder(reminder)
        val scheduleResult = scheduler.schedule(reminder)
        assertTrue("Schedule must succeed as Armed unconditionally, but was $scheduleResult", scheduleResult is com.anchor.core.alarms.ScheduleResult.Armed)

        // Wait for Android's AlarmManager to wake us up (timeout at 20s = fire time + 15s slack)
        var elapsed = 0
        while (AlarmReceiver.lastFiredId != testId && elapsed < 20000) {
            kotlinx.coroutines.delay(250)
            elapsed += 250
        }

        assertEquals("Gentle alarm receiver should have updated the static flag", testId, AlarmReceiver.lastFiredId)
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

        // D-105: @After cancels the gentle alarm, so the status bar can only be inspected while
        // this test is still running. Hold the armed alarm open long enough for a host-side
        // screencap. Opt-in only, so a normal suite run does not pay the wait.
        val holdMs = InstrumentationRegistry.getArguments()
            .getString("d105GentleHoldMs", "0")!!.toLong()
        if (holdMs > 0) {
            val until = System.currentTimeMillis() + holdMs
            while (System.currentTimeMillis() < until) Thread.sleep(250)
        }
    }
}