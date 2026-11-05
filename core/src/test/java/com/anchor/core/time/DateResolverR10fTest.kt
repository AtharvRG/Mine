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
import java.util.TimeZone

class DateResolverR10fTest {

    private val testTz = TimeZone.getTimeZone("UTC")

    private fun calendarAt(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
        tz: TimeZone = testTz
    ): Long {
        val cal = Calendar.getInstance(tz)
        cal.set(year, month - 1, day, hour, minute, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    @Test
    fun testWeekdayAbbreviations() {
        // Wednesday Oct 28, 2026 12:00:00 PM UTC
        val now = calendarAt(2026, 10, 28, 12, 0)
        val clock = FakeClock(now)
        val resolver = DateResolver(clock, is24HourFormat = { false }, timeZone = testTz)

        // "fri at 5pm" -> Friday Oct 30, 2026 17:00 UTC
        val resFri = resolver.resolve("fri at 5pm")
        assertTrue("fri at 5pm must resolve to Exact, was $resFri", resFri is DateResolution.Exact)
        assertEquals(calendarAt(2026, 10, 30, 17, 0), (resFri as DateResolution.Exact).timeMillis)

        // "on fri at 5pm" -> Friday Oct 30, 2026 17:00 UTC
        val resOnFri = resolver.resolve("on fri at 5pm")
        assertTrue("on fri at 5pm must resolve to Exact", resOnFri is DateResolution.Exact)
        assertEquals(calendarAt(2026, 10, 30, 17, 0), (resOnFri as DateResolution.Exact).timeMillis)

        // "next mon at 10am" -> Monday Nov 2, 2026 10:00 UTC
        val resNextMon = resolver.resolve("next mon at 10am")
        assertTrue("next mon at 10am must resolve to Exact", resNextMon is DateResolution.Exact)
        assertEquals(calendarAt(2026, 11, 2, 10, 0), (resNextMon as DateResolution.Exact).timeMillis)

        // "wed 3pm" (today Wednesday at 3pm, after 12pm) -> Wednesday Oct 28, 2026 15:00 UTC
        val resWed3pm = resolver.resolve("wed 3pm")
        assertTrue("wed 3pm must resolve to Exact", resWed3pm is DateResolution.Exact)
        assertEquals(calendarAt(2026, 10, 28, 15, 0), (resWed3pm as DateResolution.Exact).timeMillis)

        // "this thu at 4pm" -> Thursday Oct 29, 2026 16:00 UTC
        val resThu = resolver.resolve("this thu at 4pm")
        assertTrue("this thu at 4pm must resolve to Exact", resThu is DateResolution.Exact)
        assertEquals(calendarAt(2026, 10, 29, 16, 0), (resThu as DateResolution.Exact).timeMillis)

        // "sat at 11am" -> Saturday Oct 31, 2026 11:00 UTC
        val resSat = resolver.resolve("sat at 11am")
        assertTrue("sat at 11am must resolve to Exact", resSat is DateResolution.Exact)
        assertEquals(calendarAt(2026, 10, 31, 11, 0), (resSat as DateResolution.Exact).timeMillis)

        // "sun at 8pm" -> Sunday Nov 1, 2026 20:00 UTC
        val resSun = resolver.resolve("sun at 8pm")
        assertTrue("sun at 8pm must resolve to Exact", resSun is DateResolution.Exact)
        assertEquals(calendarAt(2026, 11, 1, 20, 0), (resSun as DateResolution.Exact).timeMillis)
    }

    @Test
    fun testCompoundPrefixes() {
        // Wednesday Oct 28, 2026 12:00:00 PM UTC
        val now = calendarAt(2026, 10, 28, 12, 0)
        val clock = FakeClock(now)
        val resolver = DateResolver(clock, is24HourFormat = { false }, timeZone = testTz)

        // "on next friday at 5pm" -> Friday Oct 30, 2026 17:00 UTC
        val resOnNextFri = resolver.resolve("on next friday at 5pm")
        assertTrue("on next friday at 5pm must resolve to Exact", resOnNextFri is DateResolution.Exact)
        assertEquals(calendarAt(2026, 10, 30, 17, 0), (resOnNextFri as DateResolution.Exact).timeMillis)

        // "coming friday" -> Friday Oct 30, 2026 09:00 UTC
        val resComingFri = resolver.resolve("coming friday")
        assertTrue("coming friday must resolve to Exact", resComingFri is DateResolution.Exact)
        assertEquals(calendarAt(2026, 10, 30, 9, 0), (resComingFri as DateResolution.Exact).timeMillis)

        // "this coming monday at 9am" -> Monday Nov 2, 2026 09:00 UTC
        val resThisComingMon = resolver.resolve("this coming monday at 9am")
        assertTrue("this coming monday at 9am must resolve to Exact", resThisComingMon is DateResolution.Exact)
        assertEquals(calendarAt(2026, 11, 2, 9, 0), (resThisComingMon as DateResolution.Exact).timeMillis)
    }

    @Test
    fun testWeekdayAndRelativeDayParts() {
        // Wednesday Oct 28, 2026 12:00:00 PM UTC
        val now = calendarAt(2026, 10, 28, 12, 0)
        val clock = FakeClock(now)
        val resolver = DateResolver(clock, is24HourFormat = { false }, timeZone = testTz)

        // Weekday + day parts
        // "friday morning" -> Friday Oct 30, 2026 09:00 UTC
        val resFriMorn = resolver.resolve("friday morning")
        assertTrue("friday morning must resolve to Exact", resFriMorn is DateResolution.Exact)
        assertEquals(calendarAt(2026, 10, 30, 9, 0), (resFriMorn as DateResolution.Exact).timeMillis)

        // "friday afternoon" -> Friday Oct 30, 2026 14:00 UTC
        val resFriAft = resolver.resolve("friday afternoon")
        assertTrue("friday afternoon must resolve to Exact", resFriAft is DateResolution.Exact)
        assertEquals(calendarAt(2026, 10, 30, 14, 0), (resFriAft as DateResolution.Exact).timeMillis)

        // "friday evening" -> Friday Oct 30, 2026 19:00 UTC
        val resFriEve = resolver.resolve("friday evening")
        assertTrue("friday evening must resolve to Exact", resFriEve is DateResolution.Exact)
        assertEquals(calendarAt(2026, 10, 30, 19, 0), (resFriEve as DateResolution.Exact).timeMillis)

        // "friday night" -> Friday Oct 30, 2026 21:00 UTC
        val resFriNight = resolver.resolve("friday night")
        assertTrue("friday night must resolve to Exact", resFriNight is DateResolution.Exact)
        assertEquals(calendarAt(2026, 10, 30, 21, 0), (resFriNight as DateResolution.Exact).timeMillis)

        // Tomorrow + day parts
        // "tomorrow morning" -> Thursday Oct 29, 2026 09:00 UTC
        val resTomMorn = resolver.resolve("tomorrow morning")
        assertTrue("tomorrow morning must resolve to Exact", resTomMorn is DateResolution.Exact)
        assertEquals(calendarAt(2026, 10, 29, 9, 0), (resTomMorn as DateResolution.Exact).timeMillis)

        // "tomorrow afternoon" -> Thursday Oct 29, 2026 14:00 UTC
        val resTomAft = resolver.resolve("tomorrow afternoon")
        assertTrue("tomorrow afternoon must resolve to Exact", resTomAft is DateResolution.Exact)
        assertEquals(calendarAt(2026, 10, 29, 14, 0), (resTomAft as DateResolution.Exact).timeMillis)

        // "tomorrow evening" -> Thursday Oct 29, 2026 19:00 UTC
        val resTomEve = resolver.resolve("tomorrow evening")
        assertTrue("tomorrow evening must resolve to Exact", resTomEve is DateResolution.Exact)
        assertEquals(calendarAt(2026, 10, 29, 19, 0), (resTomEve as DateResolution.Exact).timeMillis)

        // "tomorrow night" -> Thursday Oct 29, 2026 21:00 UTC
        val resTomNight = resolver.resolve("tomorrow night")
        assertTrue("tomorrow night must resolve to Exact", resTomNight is DateResolution.Exact)
        assertEquals(calendarAt(2026, 10, 29, 21, 0), (resTomNight as DateResolution.Exact).timeMillis)
    }

    @Test
    fun testDurationVocabularyGaps() {
        val now = calendarAt(2026, 10, 28, 12, 0)
        val clock = FakeClock(now)
        val resolver = DateResolver(clock, is24HourFormat = { false }, timeZone = testTz)

        // "half an hour" without "in" -> 1_800_000L
        assertEquals(1_800_000L, resolver.parseDuration("half an hour"))
        assertEquals(now + 1_800_000L, resolver.parse("half an hour"))

        // "in a half hour" -> 1_800_000L
        assertEquals(1_800_000L, resolver.parseDuration("in a half hour"))
        assertEquals(now + 1_800_000L, resolver.parse("in a half hour"))

        // "an hour" without "in" -> 3_600_000L
        assertEquals(3_600_000L, resolver.parseDuration("an hour"))
        assertEquals(now + 3_600_000L, resolver.parse("an hour"))

        // "a minute" without "in" -> 60_000L
        assertEquals(60_000L, resolver.parseDuration("a minute"))
        assertEquals(now + 60_000L, resolver.parse("a minute"))

        // "in a day" -> 86_400_000L
        assertEquals(86_400_000L, resolver.parseDuration("in a day"))
        assertEquals(now + 86_400_000L, resolver.parse("in a day"))
    }

    @Test
    fun testTwoAmCases_TonightNeverYesterday() {
        // Wednesday Oct 28, 2026 at 02:00:00 AM UTC (late night / post-midnight, before 04:00 rollover)
        val twoAm = calendarAt(2026, 10, 28, 2, 0)
        val clock = FakeClock(twoAm)
        val resolver = DateResolver(clock, is24HourFormat = { false }, timeZone = testTz)

        // 1. "tonight" must resolve to Wednesday Oct 28 at 21:00 UTC (NEVER yesterday Tuesday night!)
        val resTonight = resolver.resolve("tonight", rolloverMinuteOfDay = 240)
        assertTrue("tonight at 2am must resolve to Exact", resTonight is DateResolution.Exact)
        val tonightExpected = calendarAt(2026, 10, 28, 21, 0)
        assertEquals(tonightExpected, (resTonight as DateResolution.Exact).timeMillis)
        assertTrue("tonight must be in the future relative to 2am", (resTonight as DateResolution.Exact).timeMillis > twoAm)

        // 2. "tonight at 8pm" -> Wednesday Oct 28 at 20:00 UTC
        val resTonight8pm = resolver.resolve("tonight at 8pm", rolloverMinuteOfDay = 240)
        assertTrue("tonight at 8pm must resolve to Exact", resTonight8pm is DateResolution.Exact)
        assertEquals(calendarAt(2026, 10, 28, 20, 0), (resTonight8pm as DateResolution.Exact).timeMillis)

        // 3. "today at 7pm" -> Wednesday Oct 28 at 19:00 UTC
        val resToday7pm = resolver.resolve("today at 7pm", rolloverMinuteOfDay = 240)
        assertTrue("today at 7pm must resolve to Exact", resToday7pm is DateResolution.Exact)
        assertEquals(calendarAt(2026, 10, 28, 19, 0), (resToday7pm as DateResolution.Exact).timeMillis)

        // 4. "today at 1am" -> 1 hour ago -> PastTime
        val resToday1am = resolver.resolve("today at 1am", rolloverMinuteOfDay = 240)
        assertEquals("today at 1am when clock is 2am must be PastTime", DateResolution.PastTime, resToday1am)

        // 5. "yesterday" -> PastTime
        val resYesterday = resolver.resolve("yesterday", rolloverMinuteOfDay = 240)
        assertEquals("yesterday must be PastTime", DateResolution.PastTime, resYesterday)

        // 6. "tomorrow at 4pm" -> Thursday Oct 29 at 16:00 UTC
        val resTomorrow4pm = resolver.resolve("tomorrow at 4pm", rolloverMinuteOfDay = 240)
        assertTrue("tomorrow at 4pm must resolve to Exact", resTomorrow4pm is DateResolution.Exact)
        assertEquals(calendarAt(2026, 10, 29, 16, 0), (resTomorrow4pm as DateResolution.Exact).timeMillis)
    }

    @Test
    fun testAmbiguousBareHour_NoAmPmAsks() {
        // Wednesday Oct 28, 2026 12:00:00 PM UTC
        val now = calendarAt(2026, 10, 28, 12, 0)
        val clock = FakeClock(now)
        val resolver = DateResolver(clock, is24HourFormat = { false }, timeZone = testTz)

        // "friday at 5" -> NeedsClarification (5am vs 5pm)
        val resFriday5 = resolver.resolve("friday at 5")
        assertTrue("friday at 5 must be NeedsClarification, was $resFriday5", resFriday5 is DateResolution.NeedsClarification)
        val clarFri = resFriday5 as DateResolution.NeedsClarification
        assertEquals(2, clarFri.candidateMillis.size)
        assertTrue(clarFri.candidateMillis.contains(calendarAt(2026, 10, 30, 5, 0)))
        assertTrue(clarFri.candidateMillis.contains(calendarAt(2026, 10, 30, 17, 0)))

        // "tomorrow at 9:30" -> NeedsClarification
        val resTom930 = resolver.resolve("tomorrow at 9:30")
        assertTrue("tomorrow at 9:30 must be NeedsClarification, was $resTom930", resTom930 is DateResolution.NeedsClarification)
        assertNull("parse() must return null for ambiguous times without am/pm", resolver.parse("tomorrow at 9:30"))
    }

    @Test
    fun testTimezoneSupport() {
        // 12:00 PM UTC
        val nowUtc = calendarAt(2026, 10, 28, 12, 0, tz = TimeZone.getTimeZone("UTC"))
        val clock = FakeClock(nowUtc)

        // Timezone: America/New_York (EDT, UTC-4). At 12:00 UTC, it is 08:00 EDT in New York.
        val nyTz = TimeZone.getTimeZone("America/New_York")
        val resolverNy = DateResolver(clock, is24HourFormat = { false }, timeZone = nyTz)

        // "at 5pm" in New York resolves to 17:00 EDT (which is 21:00 UTC)
        val res5pm = resolverNy.resolve("at 5pm")
        assertTrue(res5pm is DateResolution.Exact)
        val expectedNy5pmInUtc = calendarAt(2026, 10, 28, 21, 0, tz = TimeZone.getTimeZone("UTC"))
        assertEquals(expectedNy5pmInUtc, (res5pm as DateResolution.Exact).timeMillis)
    }
}
