// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.recurrence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class RecurrenceEngineTest {

    private val tz = TimeZone.getTimeZone("UTC")

    @Test
    fun testDailyRecurrence_AdvancesBy24Hours() {
        val cal = Calendar.getInstance(tz).apply {
            set(2026, Calendar.NOVEMBER, 11, 10, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val base = cal.timeInMillis
        val now = base + 1000L // 1 second later

        val next = RecurrenceEngine.nextOccurrence("FREQ=DAILY", base, now, tz)
        val expected = base + (24 * 3600 * 1000L)
        assertEquals(expected, next)
    }

    @Test
    fun testDailyRecurrence_CustomInterval() {
        val cal = Calendar.getInstance(tz).apply {
            set(2026, Calendar.NOVEMBER, 11, 10, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val base = cal.timeInMillis
        val now = base + 1000L

        val next = RecurrenceEngine.nextOccurrence("FREQ=DAILY;INTERVAL=3", base, now, tz)
        val expected = base + (3 * 24 * 3600 * 1000L)
        assertEquals(expected, next)
    }

    @Test
    fun testWeeklyRecurrence_AdvancesBy7Days() {
        val cal = Calendar.getInstance(tz).apply {
            set(2026, Calendar.NOVEMBER, 11, 10, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val base = cal.timeInMillis
        val now = base + 1000L

        val next = RecurrenceEngine.nextOccurrence("FREQ=WEEKLY", base, now, tz)
        val expected = base + (7 * 24 * 3600 * 1000L)
        assertEquals(expected, next)
    }

    @Test
    fun testWeekdaysRecurrence_SkipsWeekends() {
        // Friday Nov 13, 2026
        val cal = Calendar.getInstance(tz).apply {
            set(2026, Calendar.NOVEMBER, 13, 9, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val friday = cal.timeInMillis
        val now = friday + 1000L

        // Next occurrence on weekdays should be Monday Nov 16 (3 days later)
        val next = RecurrenceEngine.nextOccurrence("BYDAY=MO,TU,WE,TH,FR", friday, now, tz)
        val nextCal = Calendar.getInstance(tz).apply { timeInMillis = next }
        assertEquals(Calendar.MONDAY, nextCal.get(Calendar.DAY_OF_WEEK))
        assertEquals(16, nextCal.get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun testFarPastTimestamp_FastForwardsPastNow() {
        val cal = Calendar.getInstance(tz).apply {
            set(2025, Calendar.JANUARY, 1, 9, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val past = cal.timeInMillis

        val nowCal = Calendar.getInstance(tz).apply {
            set(2026, Calendar.NOVEMBER, 11, 15, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val now = nowCal.timeInMillis

        val next = RecurrenceEngine.nextOccurrence("FREQ=DAILY", past, now, tz)
        assertTrue("Next occurrence must be strictly in the future (> now)", next > now)

        val nextCal = Calendar.getInstance(tz).apply { timeInMillis = next }
        assertEquals(9, nextCal.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, nextCal.get(Calendar.MINUTE))
    }

    @Test
    fun testHabitCompletion_FirstCompletionStartsStreak() {
        val now = 1_700_000_000_000L
        val res = RecurrenceEngine.evaluateHabitCompletion(
            rrule = "FREQ=DAILY",
            lastCompletedAt = null,
            currentStreak = 0,
            bestStreak = 0,
            now = now
        )
        assertEquals(1, res.currentStreak)
        assertEquals(1, res.bestStreak)
        assertTrue(res.isContinued)
    }

    @Test
    fun testHabitCompletion_ConsecutiveDaysIncrementsStreak() {
        val day1 = 1_700_000_000_000L
        val day2 = day1 + (24 * 3600 * 1000L) // 24h later

        val res = RecurrenceEngine.evaluateHabitCompletion(
            rrule = "FREQ=DAILY",
            lastCompletedAt = day1,
            currentStreak = 3,
            bestStreak = 5,
            now = day2
        )
        assertEquals(4, res.currentStreak)
        assertEquals(5, res.bestStreak)
        assertTrue(res.isContinued)
    }

    @Test
    fun testHabitCompletion_ExceedsBestStreakUpdatesBest() {
        val day1 = 1_700_000_000_000L
        val day2 = day1 + (22 * 3600 * 1000L) // 22h later

        val res = RecurrenceEngine.evaluateHabitCompletion(
            rrule = "FREQ=DAILY",
            lastCompletedAt = day1,
            currentStreak = 5,
            bestStreak = 5,
            now = day2
        )
        assertEquals(6, res.currentStreak)
        assertEquals(6, res.bestStreak)
        assertTrue(res.isContinued)
    }

    @Test
    fun testHabitCompletion_IdempotentWithin6Hours() {
        val t1 = 1_700_000_000_000L
        val t2 = t1 + (2 * 3600 * 1000L) // 2 hours later

        val res = RecurrenceEngine.evaluateHabitCompletion(
            rrule = "FREQ=DAILY",
            lastCompletedAt = t1,
            currentStreak = 4,
            bestStreak = 4,
            now = t2
        )
        // Idempotent: streak stays at 4
        assertEquals(4, res.currentStreak)
        assertEquals(4, res.bestStreak)
        assertTrue(res.isContinued)
    }

    @Test
    fun testHabitCompletion_BrokenStreakPastGraceWindowResetsToOne() {
        val day1 = 1_700_000_000_000L
        val day3 = day1 + (48 * 3600 * 1000L) // 48h later (> 36h grace window)

        val res = RecurrenceEngine.evaluateHabitCompletion(
            rrule = "FREQ=DAILY",
            lastCompletedAt = day1,
            currentStreak = 7,
            bestStreak = 10,
            now = day3
        )
        assertEquals(1, res.currentStreak)
        assertEquals(10, res.bestStreak) // best streak preserved
        assertFalse(res.isContinued)
    }
}
