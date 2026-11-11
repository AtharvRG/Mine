// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.capture

import com.anchor.core.models.AlarmTier
import com.anchor.core.time.DateResolver
import com.anchor.core.time.FakeClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class RuleBasedQuickAddTest {

    private val tz = TimeZone.getTimeZone("UTC")
    // Base time: 2026-10-25 12:00:00 UTC = 1792929600000L
    private val baseNow = 1792929600000L
    private val clock = FakeClock(baseNow)
    private val dateResolver = DateResolver(clock = clock, is24HourFormat = { false }, timeZone = tz)
    private val quickAdd = RuleBasedQuickAdd(dateResolver)

    @Test
    fun testRemindMeAtTimeToTask() {
        val result = quickAdd.parse("remind me at 6pm to call mom")
        assertNotNull("Should parse 'remind me at 6pm to call mom'", result)
        assertEquals("call mom", result!!.title)
        assertEquals("at 6pm", result.dueText)
        assertEquals(AlarmTier.ALARM, result.tier)
        assertTrue("dueAt must be after now", result.dueAt != null && result.dueAt!! > baseNow)
    }

    @Test
    fun testRemindMeInDurationToTask() {
        val result = quickAdd.parse("remind me in 30 mins to take medicine")
        assertNotNull("Should parse 'remind me in 30 mins to take medicine'", result)
        assertEquals("take medicine", result!!.title)
        assertEquals("in 30 mins", result.dueText)
        assertEquals(baseNow + 30 * 60 * 1000L, result.dueAt)
    }

    @Test
    fun testRemindMeToTaskAtTime() {
        val result = quickAdd.parse("remind me to check oven in 10 minutes")
        assertNotNull("Should parse 'remind me to check oven in 10 minutes'", result)
        assertEquals("check oven", result!!.title)
        assertEquals("in 10 minutes", result.dueText)
        assertEquals(baseNow + 10 * 60 * 1000L, result.dueAt)
    }

    @Test
    fun testGentleReminderTier() {
        val result = quickAdd.parse("gentle reminder at 5pm to drink water")
        assertNotNull("Should parse gentle reminder", result)
        assertEquals("drink water", result!!.title)
        assertEquals(AlarmTier.GENTLE, result.tier)
    }

    @Test
    fun testSetAlarmForTaskAtTime() {
        val result = quickAdd.parse("set alarm at 8am to wake up")
        assertNotNull("Should parse set alarm pattern", result)
        assertEquals("wake up", result!!.title)
        assertEquals("at 8am", result.dueText)
    }

    @Test
    fun testTodoPrefixWithoutTime() {
        val result = quickAdd.parse("todo: buy groceries and milk")
        assertNotNull("Should parse 'todo:' prefix", result)
        assertEquals("buy groceries and milk", result!!.title)
        assertNull("Undated todo should have null dueAt", result.dueAt)
    }

    @Test
    fun testTaskPrefixWithTime() {
        val result = quickAdd.parse("task: review pr tomorrow at 10am")
        assertNotNull("Should parse 'task:' with time", result)
        assertEquals("review pr", result!!.title)
        assertEquals("tomorrow at 10am", result.dueText)
        assertTrue("dueAt must be > baseNow", result.dueAt != null && result.dueAt!! > baseNow)
    }

    @Test
    fun testNonQuickAddUtterances_ReturnNull() {
        assertNull(quickAdd.parse("how are you doing today?"))
        assertNull(quickAdd.parse("what did I say about the Goa trip?"))
        assertNull(quickAdd.parse("yesterday was fun"))
        assertNull(quickAdd.parse("thanks!"))
    }
}
