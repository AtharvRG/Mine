// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.time

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class DateResolverR4Test {

    @Test
    fun testNightAnd12Ambiguities() {
        // Wednesday Oct 28, 2026 12:00:00 PM
        val cal = Calendar.getInstance().apply {
            set(2026, Calendar.OCTOBER, 28, 12, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val clock = FakeClock(cal.timeInMillis)
        val resolver = DateResolver(clock, is24HourFormat = { false })

        // 1..5 at night has no natural reading -> NeedsClarification (03:00 vs 15:00)
        val res3Night = resolver.resolve("3 at night")
        assertTrue("3 at night must be NeedsClarification, was $res3Night", res3Night is DateResolution.NeedsClarification)

        // tonight at 1..5 -> NeedsClarification
        val resTonight3 = resolver.resolve("tonight at 3")
        assertTrue("tonight at 3 must be NeedsClarification, was $resTonight3", resTonight3 is DateResolution.NeedsClarification)

        // 12 in the evening -> NeedsClarification (00:00 vs 12:00)
        val res12Evening = resolver.resolve("12 in the evening")
        assertTrue("12 in the evening must be NeedsClarification, was $res12Evening", res12Evening is DateResolution.NeedsClarification)

        // 12 in the afternoon -> NeedsClarification
        val res12Afternoon = resolver.resolve("12 in the afternoon")
        assertTrue("12 in the afternoon must be NeedsClarification, was $res12Afternoon", res12Afternoon is DateResolution.NeedsClarification)

        // 6..11 at night / tonight = unambiguous PM
        val res8Night = resolver.resolve("8 at night")
        assertTrue("8 at night must be Exact, was $res8Night", res8Night is DateResolution.Exact)
        val expected8Pm = Calendar.getInstance().apply {
            set(2026, Calendar.OCTOBER, 28, 20, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        assertEquals(expected8Pm, (res8Night as DateResolution.Exact).timeMillis)

        val resTonight8 = resolver.resolve("tonight at 8")
        assertTrue("tonight at 8 must be Exact, was $resTonight8", resTonight8 is DateResolution.Exact)
        assertEquals(expected8Pm, (resTonight8 as DateResolution.Exact).timeMillis)

        // 12 at night = 00:00 next calendar day
        val res12Night = resolver.resolve("12 at night")
        assertTrue("12 at night must be Exact, was $res12Night", res12Night is DateResolution.Exact)
        val expectedMidnight = Calendar.getInstance().apply {
            set(2026, Calendar.OCTOBER, 29, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        assertEquals(expectedMidnight, (res12Night as DateResolution.Exact).timeMillis)
    }

    @Test
    fun testWeekdayNames() {
        // Wednesday Oct 28, 2026 12:00:00 PM
        val cal = Calendar.getInstance().apply {
            set(2026, Calendar.OCTOBER, 28, 12, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val clock = FakeClock(cal.timeInMillis)
        val resolver = DateResolver(clock, is24HourFormat = { false })

        // Coming Friday is Oct 30, 2026
        val expectedFriday9Am = Calendar.getInstance().apply {
            set(2026, Calendar.OCTOBER, 30, 9, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val resOnFriday = resolver.resolve("on friday")
        assertTrue("on friday must be Exact, was $resOnFriday", resOnFriday is DateResolution.Exact)
        assertEquals(expectedFriday9Am, (resOnFriday as DateResolution.Exact).timeMillis)

        val resFriday5pm = resolver.resolve("friday at 5pm")
        assertTrue("friday at 5pm must be Exact, was $resFriday5pm", resFriday5pm is DateResolution.Exact)
        val expectedFriday5Pm = Calendar.getInstance().apply {
            set(2026, Calendar.OCTOBER, 30, 17, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        assertEquals(expectedFriday5Pm, (resFriday5pm as DateResolution.Exact).timeMillis)

        // "friday at 5" -> NeedsClarification for 5am and 5pm on that Friday
        val resFridayAt5 = resolver.resolve("friday at 5")
        assertTrue("friday at 5 must be NeedsClarification, was $resFridayAt5", resFridayAt5 is DateResolution.NeedsClarification)
        val clar = resFridayAt5 as DateResolution.NeedsClarification
        assertEquals(2, clar.candidateMillis.size)
        val expectedFriday5Am = Calendar.getInstance().apply {
            set(2026, Calendar.OCTOBER, 30, 5, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        assertTrue(clar.candidateMillis.contains(expectedFriday5Am))
        assertTrue(clar.candidateMillis.contains(expectedFriday5Pm))

        // "next friday" from Wednesday Oct 28 resolves to the upcoming Friday (Oct 30)
        val resNextFriday = resolver.resolve("next friday")
        assertTrue("next friday must be Exact, was $resNextFriday", resNextFriday is DateResolution.Exact)
        assertEquals(expectedFriday9Am, (resNextFriday as DateResolution.Exact).timeMillis)
    }

    @Test
    fun testChipLabelFormatsIn12hAnd24h() {
        val cal = Calendar.getInstance().apply {
            set(2026, Calendar.OCTOBER, 28, 12, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val clock = FakeClock(cal.timeInMillis)

        // 12-hour format resolver
        val resolver12h = DateResolver(clock, is24HourFormat = { false })
        val res12h = resolver12h.resolve("at 5")
        assertTrue(res12h is DateResolution.NeedsClarification)
        val labels12h = (res12h as DateResolution.NeedsClarification).chipLabels
        assertEquals(listOf("5:00 AM", "5:00 PM"), labels12h)

        // 24-hour format resolver
        val resolver24h = DateResolver(clock, is24HourFormat = { true })
        val res24h = resolver24h.resolve("at 5")
        assertTrue(res24h is DateResolution.NeedsClarification)
        val labels24h = (res24h as DateResolution.NeedsClarification).chipLabels
        assertEquals(listOf("05:00", "17:00"), labels24h)
    }

    @Test
    fun testWeekdayParsing_SameDayEarlierAndLater() {
        // Clock is Friday Oct 30, 2026 at 10:00 AM
        val cal = Calendar.getInstance().apply {
            set(2026, Calendar.OCTOBER, 30, 10, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val clock = FakeClock(cal.timeInMillis)
        val resolver = DateResolver(clock, is24HourFormat = { false })

        // 1. "friday at 5pm" when it's Friday 10am -> resolves to TODAY at 5pm (17:00)
        val resFriday5pm = resolver.resolve("friday at 5pm")
        assertTrue(resFriday5pm is DateResolution.Exact)
        val expectedToday5pm = Calendar.getInstance().apply {
            set(2026, Calendar.OCTOBER, 30, 17, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        assertEquals(expectedToday5pm, (resFriday5pm as DateResolution.Exact).timeMillis)

        // 2. "this friday at 5pm" -> resolves to TODAY at 5pm (17:00)
        val resThisFriday5pm = resolver.resolve("this friday at 5pm")
        assertTrue(resThisFriday5pm is DateResolution.Exact)
        assertEquals(expectedToday5pm, (resThisFriday5pm as DateResolution.Exact).timeMillis)

        // 3. "friday at 8am" -> past time today, rolls forward to NEXT Friday (Nov 6) at 8:00 AM
        val resFriday8am = resolver.resolve("friday at 8am")
        assertTrue(resFriday8am is DateResolution.Exact)
        val expectedNextFriday8am = Calendar.getInstance().apply {
            set(2026, Calendar.NOVEMBER, 6, 8, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        assertEquals(expectedNextFriday8am, (resFriday8am as DateResolution.Exact).timeMillis)

        // 4. "next friday at 5pm" -> explicit "next", rolls to NEXT Friday (Nov 6) at 5:00 PM
        val resNextFriday5pm = resolver.resolve("next friday at 5pm")
        assertTrue(resNextFriday5pm is DateResolution.Exact)
        val expectedNextFriday5pm = Calendar.getInstance().apply {
            set(2026, Calendar.NOVEMBER, 6, 17, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        assertEquals(expectedNextFriday5pm, (resNextFriday5pm as DateResolution.Exact).timeMillis)

        // 5. "this friday" (default 9am) when it's already 10am -> rolls forward to NEXT Friday (Nov 6) at 9:00 AM
        val resThisFridayPast = resolver.resolve("this friday")
        assertTrue(resThisFridayPast is DateResolution.Exact)
        val expectedNextFriday9am = Calendar.getInstance().apply {
            set(2026, Calendar.NOVEMBER, 6, 9, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        assertEquals(expectedNextFriday9am, (resThisFridayPast as DateResolution.Exact).timeMillis)

        // 6. Clock is Friday Oct 30, 2026 at 8:00 AM -> "this friday" resolves to TODAY at 9:00 AM
        val earlyCal = Calendar.getInstance().apply {
            set(2026, Calendar.OCTOBER, 30, 8, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val earlyResolver = DateResolver(FakeClock(earlyCal.timeInMillis), is24HourFormat = { false })
        val resThisFridayFuture = earlyResolver.resolve("this friday")
        assertTrue(resThisFridayFuture is DateResolution.Exact)
        val expectedToday9am = Calendar.getInstance().apply {
            set(2026, Calendar.OCTOBER, 30, 9, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        assertEquals(expectedToday9am, (resThisFridayFuture as DateResolution.Exact).timeMillis)
    }
}
