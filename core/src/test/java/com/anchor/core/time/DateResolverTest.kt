// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.time

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class DateResolverTest {
    @Test
    fun testDateResolver() {
        val cal = Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, 25, 12, 0, 0); set(Calendar.MILLISECOND, 0) }
        val fakeClock = FakeClock(cal.timeInMillis)
        val resolver = DateResolver(fakeClock)

        assertEquals(cal.timeInMillis + 120_000L, resolver.parse("in 2 minutes"))
        assertEquals(cal.timeInMillis + 3_600_000L, resolver.parse("in an hour"))
        assertEquals(cal.timeInMillis + 1_800_000L, resolver.parse("in half an hour"))

        val fivePm = Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, 25, 17, 0, 0); set(Calendar.MILLISECOND, 0) }
        assertEquals(fivePm.timeInMillis, resolver.parse("at 5pm"))

        val fiveThirtyPm = Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, 25, 17, 30, 0); set(Calendar.MILLISECOND, 0) }
        assertEquals(fiveThirtyPm.timeInMillis, resolver.parse("at 5:30pm"))

        val sevenPm = Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, 25, 19, 0, 0); set(Calendar.MILLISECOND, 0) }
        assertEquals(sevenPm.timeInMillis, resolver.parse("today at 7pm"))

        val nextDay4pm = Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, 26, 16, 0, 0); set(Calendar.MILLISECOND, 0) }
        assertEquals(nextDay4pm.timeInMillis, resolver.parse("tomorrow 4pm"))

        val nextDay930am = Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, 26, 9, 30, 0); set(Calendar.MILLISECOND, 0) }
        assertNull(resolver.parse("tomorrow at 9:30")) // Fails if no am/pm (Never guess)
        assertEquals(nextDay930am.timeInMillis, resolver.parse("tomorrow at 9:30am"))

        val tonight = Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, 25, 21, 0, 0); set(Calendar.MILLISECOND, 0) }
        assertEquals(tonight.timeInMillis, resolver.parse("tonight"))

        val nextMonday = Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, 26, 9, 0, 0); set(Calendar.MILLISECOND, 0) }
        assertEquals(nextMonday.timeInMillis, resolver.parse("next monday"))

        // "today" with a past time
        assertNull(resolver.parse("today at 9am")) // It's currently 12pm, should not shift to tomorrow, must ask user
    }

    @Test
    fun testD103_Accepted24HourAndConversationalForms() {
        val cal = Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, 25, 12, 0, 0); set(Calendar.MILLISECOND, 0) }
        val fakeClock = FakeClock(cal.timeInMillis)
        val resolver = DateResolver(fakeClock)

        val threeThirtyPm = Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, 25, 15, 30, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
        val threePm = Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, 25, 15, 0, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
        val ninePm = Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, 25, 21, 0, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
        val threeAm = Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, 26, 3, 0, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis

        // 24-hour variants per D-103
        assertEquals(threeThirtyPm, resolver.parse("15:30"))
        assertEquals(threeThirtyPm, resolver.parse("1530"))
        assertEquals(threePm, resolver.parse("15h"))
        assertEquals(threeThirtyPm, resolver.parse("15h30"))
        assertEquals(threePm, resolver.parse("at 15"))

        // Conversational 12-hour per D-103
        assertEquals(threePm, resolver.parse("3pm"))
        assertEquals(threeThirtyPm, resolver.parse("3:30 pm"))
        assertEquals(threePm, resolver.parse("3 in the evening"))
        assertEquals(threePm, resolver.parse("3 in the afternoon"))
        assertEquals(threeAm, resolver.parse("3 in the morning"))
        assertEquals(ninePm, resolver.parse("tonight at 9"))

        // Devanagari Hindi digits per D-103 ("१५:३०" -> 15:30)
        assertEquals(threeThirtyPm, resolver.parse("१५:३०"))
        assertEquals(threePm, resolver.parse("at ३pm"))
    }

    @Test
    fun testD103_BareHourAmbiguity_ReturnsNeedsClarificationWithTwoChips() {
        val cal = Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, 25, 12, 0, 0); set(Calendar.MILLISECOND, 0) }
        val fakeClock = FakeClock(cal.timeInMillis)
        val resolver = DateResolver(fakeClock, is24HourFormat = { true })

        val morningFiveThirty = Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, 26, 5, 30, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
        val eveningFiveThirty = Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, 25, 17, 30, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis

        val result530 = resolver.resolve("5:30")
        assertTrue("Expected NeedsClarification for 5:30", result530 is DateResolution.NeedsClarification)
        val clarification530 = result530 as DateResolution.NeedsClarification
        assertEquals(2, clarification530.candidateMillis.size)
        assertTrue(clarification530.candidateMillis.contains(eveningFiveThirty))
        assertTrue(clarification530.candidateMillis.contains(morningFiveThirty))
        assertEquals(listOf("05:30", "17:30"), clarification530.chipLabels)

        // "at 5" is also ambiguous bare hour
        val morningFive = Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, 26, 5, 0, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
        val eveningFive = Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, 25, 17, 0, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
        val resultAt5 = resolver.resolve("at 5")
        assertTrue("Expected NeedsClarification for at 5", resultAt5 is DateResolution.NeedsClarification)
        val clarificationAt5 = resultAt5 as DateResolution.NeedsClarification
        assertEquals(listOf("05:00", "17:00"), clarificationAt5.chipLabels)
        assertTrue(clarificationAt5.candidateMillis.contains(morningFive))
        assertTrue(clarificationAt5.candidateMillis.contains(eveningFive))

        // "tomorrow at 5" explicit test (D-103)
        val tomorrowMorningFive = Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, 26, 5, 0, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
        val tomorrowEveningFive = Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, 26, 17, 0, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
        val resultTomorrowAt5 = resolver.resolve("tomorrow at 5")
        assertTrue(resultTomorrowAt5 is DateResolution.NeedsClarification)
        val clarificationTomorrowAt5 = resultTomorrowAt5 as DateResolution.NeedsClarification
        assertEquals(listOf("05:00", "17:00"), clarificationTomorrowAt5.chipLabels)
        assertTrue(clarificationTomorrowAt5.candidateMillis.contains(tomorrowMorningFive))
        assertTrue(clarificationTomorrowAt5.candidateMillis.contains(tomorrowEveningFive))

        // Ambiguous "12:30" (midnight vs noon)
        val result1230 = resolver.resolve("12:30")
        assertTrue(result1230 is DateResolution.NeedsClarification)
        val clarification1230 = result1230 as DateResolution.NeedsClarification
        assertEquals(listOf("00:30", "12:30"), clarification1230.chipLabels)

        // Ambiguous Hindi digits "५:३०" and "at ५"
        val resultHindi = resolver.resolve("५:३०")
        assertTrue(resultHindi is DateResolution.NeedsClarification)
        assertEquals(listOf("05:30", "17:30"), (resultHindi as DateResolution.NeedsClarification).chipLabels)

        // Bare hour via parse() must return null (safe fallback)
        assertNull(resolver.parse("5:30"))
        assertNull(resolver.parse("at 5"))
        assertNull(resolver.parse("tomorrow at 5"))
    }

    @Test
    fun testD103_UnambiguousMidnightAndZeroHour() {
        val cal = Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, 25, 12, 0, 0); set(Calendar.MILLISECOND, 0) }
        val fakeClock = FakeClock(cal.timeInMillis)
        val resolver = DateResolver(fakeClock)

        val nextDay0030 = Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, 26, 0, 30, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
        val nextDay0000 = Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, 26, 0, 0, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis

        // 00:xx and at 00 are unambiguous
        assertEquals(nextDay0030, resolver.parse("00:30"))
        assertEquals(nextDay0030, resolver.parse("0030"))
        assertEquals(nextDay0000, resolver.parse("at 00"))

        // "tonight at 12" -> midnight tonight
        assertEquals(nextDay0000, resolver.parse("tonight at 12"))
        assertEquals(nextDay0000, resolver.parse("12 at night"))
    }

    @Test
    fun testTonightAt1am_At11pm_ResolvesToUpcomingMorning1am() {
        val cal11pm = Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, 26, 23, 0, 0); set(Calendar.MILLISECOND, 0) }
        val resolver = DateResolver(FakeClock(cal11pm.timeInMillis))
        val res = resolver.resolve("tonight at 1am")
        assertTrue("tonight at 1am should resolve to Exact, was $res", res is DateResolution.Exact)
        val expected1am = Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, 27, 1, 0, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
        assertEquals(expected1am, (res as DateResolution.Exact).timeMillis)
    }

    @Test
    fun testD103_FourAmRollover_LogicalDayBoundary() {
        // Base time: 2:00 AM on Oct 26 (within 4am logical night window of Oct 25)
        val cal2am = Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, 26, 2, 0, 0); set(Calendar.MILLISECOND, 0) }
        val fakeClock = FakeClock(cal2am.timeInMillis)
        val resolver = DateResolver(fakeClock)

        // getLogicalDayStart at 4 AM rollover
        val logicalDayStart = resolver.getLogicalDayStart(cal2am.timeInMillis, 240)
        val expectedStart = Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, 25, 4, 0, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
        assertEquals(expectedStart, logicalDayStart)

        // At 2am, "today at 7pm" refers to 7pm on the upcoming calendar day (Oct 26)
        val today7pm = Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, 26, 19, 0, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
        assertEquals(today7pm, resolver.parse("today at 7pm"))

        // At 2am, "tonight" and "tonight at 9" refer to 9pm on Oct 26
        val tonight9pm = Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, 26, 21, 0, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
        assertEquals(tonight9pm, resolver.parse("tonight"))
        assertEquals(tonight9pm, resolver.parse("tonight at 9"))
    }

    @Test
    fun testD103_DateFormattingRespects24HourSetting() {
        val cal = Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, 27, 17, 30, 0); set(Calendar.MILLISECOND, 0) }
        val fakeClock = FakeClock(cal.timeInMillis)

        var is24h = true
        val resolver24 = DateResolver(fakeClock, is24HourFormat = { is24h })
        assertEquals("Tue 27 Oct, 17:30", resolver24.format(cal.timeInMillis))

        is24h = false
        val resolver12 = DateResolver(fakeClock, is24HourFormat = { is24h })
        assertEquals("Tue 27 Oct, 5:30 PM", resolver12.format(cal.timeInMillis))
    }

    @Test
    fun testD103_NegativeAndMalformedInputs() {
        val cal = Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, 25, 12, 0, 0); set(Calendar.MILLISECOND, 0) }
        val fakeClock = FakeClock(cal.timeInMillis)
        val resolver = DateResolver(fakeClock)

        // Null, empty, blank
        assertEquals(DateResolution.Failed, resolver.resolve(null))
        assertEquals(DateResolution.Failed, resolver.resolve(""))
        assertEquals(DateResolution.Failed, resolver.resolve("   "))
        assertNull(resolver.parse(null))
        assertNull(resolver.parse(""))
        assertNull(resolver.parse("   "))

        // Malformed hours & minutes
        assertEquals(DateResolution.Failed, resolver.resolve("25:00"))
        assertEquals(DateResolution.Failed, resolver.resolve("14:65"))
        assertEquals(DateResolution.Failed, resolver.resolve("at 25"))
        assertEquals(DateResolution.Failed, resolver.resolve("invalid text"))
        assertNull(resolver.parse("25:00"))
        assertNull(resolver.parse("14:65"))
    }

    @Test
    fun testR11_CompoundDurationAndUnitParsing() {
        val cal = Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, 25, 12, 0, 0); set(Calendar.MILLISECOND, 0) }
        val now = cal.timeInMillis
        val fakeClock = FakeClock(now)
        val resolver = DateResolver(fakeClock)

        // Seconds and compound expressions
        assertEquals(now + 10_000L, resolver.parse("in 10 seconds"))
        assertEquals(now + 10_000L, resolver.parse("in 10 secs"))
        assertEquals(now + 152_000L, resolver.parse("in 2 min 32 seconds"))
        assertEquals(now + 152_000L, resolver.parse("in 2 mins, 32 secs"))
        assertEquals(now + 152_000L, resolver.parse("in 2 minutes and 32 seconds"))

        // Prefixes and conjunctions
        assertEquals(now + 120_000L, resolver.parse("after 2 mins"))
        assertEquals(now + 120_000L, resolver.parse("within 2 mins"))
        assertEquals(now + 120_000L, resolver.parse("2 mins"))
        assertEquals(now + 5_400_000L, resolver.parse("1 hour and 30 minutes"))

        // Single letter prefix rules: with prefix it parses, without prefix it returns null
        assertEquals(now + 5_400_000L, resolver.parse("in 1h 30m"))
        assertNull(resolver.parse("1h 30m"))

        // D-103 preservation: "15h" without prefix must remain 3pm (15:00), NOT a duration
        val threePm = Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, 25, 15, 0, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
        assertEquals(threePm, resolver.parse("15h"))

        // Zero duration must return null
        assertNull(resolver.parse("in 0 seconds"))
        assertNull(resolver.parse("0 mins"))

        // Literals: "in a minute", "in an hour", "in half an hour"
        assertEquals(now + 60_000L, resolver.parse("in a minute"))
        assertEquals(now + 60_000L, resolver.parse("in a min"))
        assertEquals(now + 3_600_000L, resolver.parse("in an hour"))
        assertEquals(now + 1_800_000L, resolver.parse("in half an hour"))

        // Overflow & bounds protection: amounts > 100_000 or total > 366 days return null
        assertNull(resolver.parse("in 99999999999999 days"))
        assertNull(resolver.parse("in 100001 seconds"))
        assertNull(resolver.parse("in 367 days"))
        assertEquals(now + 365L * 86_400_000L, resolver.parse("in 365 days"))
    }

    @Test
    fun testPastTimeAndMultiDayChipLabels() {
        // Base time: Sunday, Oct 25, 2026, 12:00:00 PM
        val cal = Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, 25, 12, 0, 0); set(Calendar.MILLISECOND, 0) }
        val now = cal.timeInMillis
        val fakeClock = FakeClock(now)
        val resolver = DateResolver(fakeClock)

        // Past keywords and times must resolve to DateResolution.PastTime
        assertEquals(DateResolution.PastTime, resolver.resolve("yesterday"))
        assertEquals(DateResolution.PastTime, resolver.resolve("last friday"))
        assertEquals(DateResolution.PastTime, resolver.resolve("past monday"))
        assertEquals(DateResolution.PastTime, resolver.resolve("today at 9am"))

        // Multi-day chip labels test
        val sameDay5pm = Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, 25, 17, 0, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
        val tomorrow5pm = Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, 26, 17, 0, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
        val friday5pm = Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, 30, 17, 0, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
        val nextMonth5pm = Calendar.getInstance().apply { set(2026, Calendar.NOVEMBER, 15, 17, 0, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis

        assertEquals("5:00 PM", resolver.formatChipLabel(sameDay5pm, now))
        assertEquals("Tomorrow 5:00 PM", resolver.formatChipLabel(tomorrow5pm, now))
        assertEquals("Fri 5:00 PM", resolver.formatChipLabel(friday5pm, now))
        assertEquals("Sun 15 Nov, 5:00 PM", resolver.formatChipLabel(nextMonth5pm, now))
    }

    @Test
    fun testDateResolver_DynamicTimezone_EvaluatesWithoutStaleCache() {
        val originalTz = java.util.TimeZone.getDefault()
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"))
            val fakeClock = FakeClock(1700000000000L)
            val resolver = DateResolver(fakeClock, { true })
            assertEquals("UTC", resolver.timeZone.id)

            // Dynamically change JVM default timezone
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Tokyo"))
            assertEquals("Asia/Tokyo", resolver.timeZone.id)
        } finally {
            java.util.TimeZone.setDefault(originalTz)
        }
    }
}
