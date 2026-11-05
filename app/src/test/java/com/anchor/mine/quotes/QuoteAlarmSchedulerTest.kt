// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.mine.quotes

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class QuoteAlarmSchedulerTest {

    private val tz = TimeZone.getTimeZone("UTC")

    private fun calendarAt(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long {
        return Calendar.getInstance(tz).apply {
            set(year, month - 1, day, hour, minute, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    @Test
    fun testNextPrepareTime_earlyMorning() {
        // At 01:00, next prepare time is 04:00 today
        val now = calendarAt(2026, 11, 4, 1, 0)
        val next = QuoteAlarmScheduler.getNextPrepareTimeMillis(now, tz)
        val expected = calendarAt(2026, 11, 4, 4, 0)
        assertEquals(expected, next)
    }

    @Test
    fun testNextPrepareTime_midMorning() {
        // At 04:30, next prepare time is 11:00 today
        val now = calendarAt(2026, 11, 4, 4, 30)
        val next = QuoteAlarmScheduler.getNextPrepareTimeMillis(now, tz)
        val expected = calendarAt(2026, 11, 4, 11, 0)
        assertEquals(expected, next)
    }

    @Test
    fun testNextPrepareTime_afternoon() {
        // At 12:00, next prepare time is 16:00 today
        val now = calendarAt(2026, 11, 4, 12, 0)
        val next = QuoteAlarmScheduler.getNextPrepareTimeMillis(now, tz)
        val expected = calendarAt(2026, 11, 4, 16, 0)
        assertEquals(expected, next)
    }

    @Test
    fun testNextPrepareTime_evening() {
        // At 17:00, next prepare time is 20:00 today
        val now = calendarAt(2026, 11, 4, 17, 0)
        val next = QuoteAlarmScheduler.getNextPrepareTimeMillis(now, tz)
        val expected = calendarAt(2026, 11, 4, 20, 0)
        assertEquals(expected, next)
    }

    @Test
    fun testNextPrepareTime_nightRolloverToTomorrow() {
        // At 21:00, next prepare time is 04:00 tomorrow
        val now = calendarAt(2026, 11, 4, 21, 0)
        val next = QuoteAlarmScheduler.getNextPrepareTimeMillis(now, tz)
        val expected = calendarAt(2026, 11, 5, 4, 0)
        assertEquals(expected, next)
    }
}
