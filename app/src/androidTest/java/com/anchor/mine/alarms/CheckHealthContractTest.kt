// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.mine.alarms

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.anchor.core.alarms.ScheduleResult
import com.anchor.core.models.AlarmTier
import com.anchor.core.models.Reminder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * checkHealth() is a pre-check, not a promise: it must never report healthy for a state where the
 * arm is guaranteed to fail, and it must never report failure for a state where the arm works.
 * The device is the only place these can be checked — the notification manager and the exact-alarm
 * policy are real system services.
 */
@RunWith(AndroidJUnit4::class)
class CheckHealthContractTest {

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun grantNotificationPermission() {
        // The test runner installs the app under test without -g, so POST_NOTIFICATIONS is
        // denied. Arm and notification health are independent domains; without this grant the
        // permission state would masquerade as an arm failure.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
                context.packageName,
                android.Manifest.permission.POST_NOTIFICATIONS
            )
        }
    }

    @Test
    fun testHealthAgreesWithActualArm() {
        val scheduler = AndroidAlarmScheduler(context)
        val health = scheduler.checkHealth()

        val id = "test_health_1"
        val reminder = Reminder(id, "Health probe", System.currentTimeMillis() + 60_000L, tier = AlarmTier.ALARM)
        val armed = scheduler.schedule(reminder)
        scheduler.cancel(id)

        if (health is ScheduleResult.Armed) {
            assertTrue(
                "checkHealth reported healthy but the arm failed with $armed — a false success",
                armed is ScheduleResult.Armed
            )
        } else {
            // A health failure while the arm succeeds is a false alarm that would block the user.
            assertTrue(
                "checkHealth reported ${(health as ScheduleResult.Failed).reason} but the arm succeeded",
                armed is ScheduleResult.Failed
            )
        }
    }

    @Test
    fun testExactAlarmPermissionIsAutoGrantedSoHealthNeedNotCheckIt() {
        // D-102b: USE_EXACT_ALARM is a normal install-time permission, so canScheduleExactAlarms()
        // is always true on a correctly built install. If this ever fails, checkHealth() must start
        // reporting it, because setAlarmClock() will throw SecurityException.
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        assertTrue(
            "USE_EXACT_ALARM is not effective — setAlarmClock() will throw and checkHealth() would lie",
            alarmManager.canScheduleExactAlarms()
        )
    }

    @Test
    fun testNotificationManagerIsNeverNull() {
        // R-52: checkHealth() falls back to Armed when NotificationManager is null, which would
        // report healthy for an alarm that cannot notify. That branch is only safe if unreachable.
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        assertNotNull("NotificationManager is null; checkHealth's Armed fallback is reachable", nm)
    }

    @Test
    fun testChannelsExistAfterAppStart() {
        // checkHealth() treats a missing channel as healthy (it only blocks IMPORTANCE_NONE).
        // A missing channel means notifications would silently not show, so it must exist.
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        assertNotNull("Reminders channel must be created at app start", nm.getNotificationChannel("mine_reminders_v3"))
        assertNotNull("Gentle channel must be created at app start", nm.getNotificationChannel("mine_gentle_reminders_v3"))
    }

    @Test
    fun testHealthFailureReasonIsUserReadable() {
        val scheduler = AndroidAlarmScheduler(context)
        val health = scheduler.checkHealth()
        if (health is ScheduleResult.Failed) {
            assertTrue(
                "A health failure must be explained to the user, not a raw exception: ${health.reason}",
                health.reason.length > 10 && !health.reason.startsWith("Exception")
            )
        } else {
            assertEquals(ScheduleResult.Armed, health)
        }
    }
}
