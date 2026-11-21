// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.mine.alarms

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.anchor.core.alarms.ScheduleResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AgentWakeupDeviceTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun testIntentConstruction_ForAgentWakeup() {
        val wakeupId = "aw_test123"
        val fireAt = System.currentTimeMillis() + 60_000L
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = AlarmReceiver.ACTION_AGENT_WAKEUP
            putExtra("EXTRA_WAKEUP_ID", wakeupId)
            putExtra("EXTRA_SCHEDULED_FOR", fireAt)
        }

        assertEquals(AlarmReceiver.ACTION_AGENT_WAKEUP, intent.action)
        assertEquals(wakeupId, intent.getStringExtra("EXTRA_WAKEUP_ID"))
        assertEquals(fireAt, intent.getLongExtra("EXTRA_SCHEDULED_FOR", 0L))
    }

    @Test
    fun testAndroidAlarmScheduler_ScheduleAndCancelAgentWakeup() {
        val scheduler = AndroidAlarmScheduler(context)
        val wakeupId = "aw_dev_${System.currentTimeMillis()}"
        val fireAt = System.currentTimeMillis() + 300_000L

        val result = scheduler.scheduleAgentWakeup(wakeupId, fireAt)
        assertTrue("Expected Armed or Failed due to alarm policy, got $result", result is ScheduleResult.Armed || result is ScheduleResult.Failed)

        scheduler.cancelAgentWakeup(wakeupId)
    }
}
