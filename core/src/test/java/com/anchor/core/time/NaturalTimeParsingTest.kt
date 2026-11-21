// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.time

import com.anchor.core.capture.RuleBasedQuickAdd
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * Time phrases as people (and models) actually write them. The clock is pinned to
 * Sat 21 Nov 2026, 20:57 IST, the moment "24th nov at 3.40pm" was reported broken.
 */
class NaturalTimeParsingTest {

    private val ist: TimeZone = TimeZone.getTimeZone("Asia/Kolkata")
    private val now = at(2026, Calendar.NOVEMBER, 21, 20, 57)
    private val resolver = DateResolver(FakeClock(now), { false }, ist)

    private fun at(y: Int, m: Int, d: Int, h: Int, min: Int, tz: TimeZone = ist): Long =
        Calendar.getInstance(tz).apply { clear(); set(y, m, d, h, min, 0) }.timeInMillis

    private fun exact(text: String): Long {
        val r = resolver.resolve(text)
        assertTrue("\"$text\" should resolve exactly, was $r", r is DateResolution.Exact)
        return (r as DateResolution.Exact).timeMillis
    }

    // ---- the reported failures ----------------------------------------------------------

    @Test fun dottedTimeAfterOrdinalDate() {
        val want = at(2026, Calendar.NOVEMBER, 24, 15, 40)
        assertEquals(want, exact("24th nov at 3.40pm"))
        assertEquals(want, exact("24th Nov at 3.40 PM"))
        assertEquals(want, exact("on 24th nov at 3.40pm"))
        assertEquals(want, exact("24 November, 3:40 p.m."))
        assertEquals(want, exact("November 24th, 3:40 PM"))
        assertEquals(want, exact("nov 24 3:40pm"))
        assertEquals(want, exact("the 24th of november at 3:40pm"))
    }

    @Test fun bareClockTimeRollsToNextOccurrence() {
        // 15:40 already passed today at 20:57, so the next 15:40 is tomorrow.
        assertEquals(at(2026, Calendar.NOVEMBER, 22, 15, 40), exact("3:40pm"))
        assertEquals(at(2026, Calendar.NOVEMBER, 22, 15, 40), exact("3.40pm"))
        assertEquals(at(2026, Calendar.NOVEMBER, 22, 15, 40), exact("1540 hrs"))
    }

    @Test fun timeBeforeDateIsReordered() {
        assertEquals(at(2026, Calendar.NOVEMBER, 22, 15, 40), exact("at 3:40pm tomorrow"))
        assertEquals(at(2026, Calendar.NOVEMBER, 24, 15, 40), exact("3:40pm on 24th nov"))
        assertEquals(at(2026, Calendar.NOVEMBER, 22, 12, 0), exact("noon tomorrow"))
    }

    // ---- new forms ----------------------------------------------------------------------

    @Test fun relativeDays() {
        assertEquals(at(2026, Calendar.NOVEMBER, 23, 9, 0), exact("day after tomorrow 9am"))
        assertEquals(at(2026, Calendar.NOVEMBER, 23, 9, 0), exact("day after tomorrow"))
        assertEquals(at(2026, Calendar.NOVEMBER, 24, 17, 0), exact("in 3 days at 5pm"))
        assertEquals(at(2026, Calendar.NOVEMBER, 28, 17, 0), exact("next week at 5pm"))
        assertEquals(at(2026, Calendar.NOVEMBER, 22, 0, 0), exact("midnight"))
        assertEquals(at(2026, Calendar.NOVEMBER, 22, 9, 0), exact("tmrw"))
    }

    @Test fun ordinalDayWithoutMonth() {
        assertEquals(at(2026, Calendar.NOVEMBER, 24, 15, 40), exact("the 24th at 15:40"))
        // The 5th of this month has passed: the next one is in December.
        assertEquals(at(2026, Calendar.DECEMBER, 5, 9, 0), exact("the 5th"))
    }

    @Test fun numericDates() {
        assertEquals(at(2026, Calendar.NOVEMBER, 24, 15, 40), exact("24/11 at 3:40pm"))
        assertEquals(at(2026, Calendar.NOVEMBER, 24, 15, 40), exact("24-11-2026 15:40"))
        // 5/12 is 5 December or 12 May: both are offered, nothing is picked.
        val r = resolver.resolve("5/12")
        assertTrue("5/12 must ask, was $r", r is DateResolution.NeedsClarification)
        val c = (r as DateResolution.NeedsClarification).candidateMillis
        assertEquals(listOf(at(2026, Calendar.DECEMBER, 5, 9, 0), at(2027, Calendar.MAY, 12, 9, 0)), c)
    }

    @Test fun clockPhrasesAndSpelledNumbers() {
        assertEquals(now + 5 * 60_000L, exact("in five minutes"))
        assertEquals(now + 90 * 60_000L, exact("in an hour and a half"))
        assertEquals(at(2026, Calendar.NOVEMBER, 21, 21, 30), exact("half past 9 tonight"))
        // "quarter past 4 tomorrow" has no am/pm: both 04:15 and 16:15 are offered.
        val r = resolver.resolve("quarter past 4 tomorrow")
        assertTrue("must ask am/pm, was $r", r is DateResolution.NeedsClarification)
        assertEquals(
            listOf(at(2026, Calendar.NOVEMBER, 22, 4, 15), at(2026, Calendar.NOVEMBER, 22, 16, 15)),
            (r as DateResolution.NeedsClarification).candidateMillis,
        )
    }

    @Test fun zonesAndIso() {
        val ny = TimeZone.getTimeZone("America/New_York")
        assertEquals(at(2026, Calendar.NOVEMBER, 22, 9, 0, ny), exact("tomorrow 9am est"))
        assertEquals(at(2026, Calendar.NOVEMBER, 24, 15, 40), exact("2026-11-24T15:40:00+05:30"))
        assertEquals(at(2026, Calendar.NOVEMBER, 24, 10, 10, TimeZone.getTimeZone("UTC")), exact("2026-11-24T10:10Z"))
    }

    @Test fun weekdayMustAgreeWithDate() {
        // 23 Nov 2026 is a Monday.
        assertEquals(at(2026, Calendar.NOVEMBER, 23, 17, 0), exact("mon 23 nov 5pm"))
        assertEquals(DateResolution.Failed, resolver.resolve("tue 23 nov 5pm"))
    }

    // ---- never guess ----------------------------------------------------------------------

    @Test fun neverGuess() {
        assertEquals(DateResolution.Failed, resolver.resolve("31 nov"))
        assertEquals(DateResolution.Failed, resolver.resolve("not tomorrow but friday"))
        assertEquals(DateResolution.Failed, resolver.resolve("sometime next week"))
        assertEquals(DateResolution.Failed, resolver.resolve("3 days before the 24th"))
        assertEquals(DateResolution.Failed, resolver.resolve("in a couple of hours"))
        assertTrue(resolver.resolve("tomorrow at 9:30") is DateResolution.NeedsClarification)
        assertTrue(resolver.resolve("3.40") is DateResolution.NeedsClarification)
        // "in 2.30 hours" is not 2:30.
        assertTrue(resolver.resolve("in 2.30 hours") !is DateResolution.NeedsClarification)
    }

    // ---- extraction from sentences ---------------------------------------------------------

    @Test fun extractorFindsTimesAnywhere() {
        val a = TimeExpressionExtractor.extract("Call Mom on 24th Nov at 3.40pm")!!
        assertEquals("Call Mom", a.title)
        assertEquals(at(2026, Calendar.NOVEMBER, 24, 15, 40), exact(a.dueText))

        val b = TimeExpressionExtractor.extract("3:40pm Dentist tomorrow")!!
        assertEquals("Dentist", b.title)
        assertEquals(at(2026, Calendar.NOVEMBER, 22, 15, 40), exact(b.dueText))

        val c = TimeExpressionExtractor.extract("remind me to Pay rent at 3:40pm")!!
        assertEquals("Pay rent", c.title)

        val d = TimeExpressionExtractor.extract("remind me in five minutes to stretch")!!
        assertEquals("stretch", d.title)
        assertEquals(now + 5 * 60_000L, exact(d.dueText))

        val e = TimeExpressionExtractor.extract("meeting with 3 people at 5pm")!!
        assertEquals("meeting with 3 people", e.title)

        assertNull(TimeExpressionExtractor.extract("I sat in the sun and wed my friend"))
        assertNull(TimeExpressionExtractor.extract("buy 12 eggs"))
    }

    @Test fun hindiDigitsKeepOffsets() {
        val ex = TimeExpressionExtractor.extract("दवा ३:४०pm कल")
        assertNotNull(ex)
        assertTrue(ex!!.title.startsWith("दवा"))
    }

    // ---- quick add ----------------------------------------------------------------------------

    @Test fun quickAddCommandsAndLooseSheet() {
        val qa = RuleBasedQuickAdd(resolver)

        val r1 = qa.parse("remind me to check in on dad at 5pm")
        assertNotNull("command with the time after a preposition", r1)
        assertEquals("check in on dad", r1!!.title)

        val r2 = qa.parse("remind me to call mom on 24th nov at 3.40pm")!!
        assertEquals("call mom", r2.title)
        assertEquals(at(2026, Calendar.NOVEMBER, 24, 15, 40), r2.dueAt)

        // A chat question with a time in it is NOT a task.
        assertNull(qa.parse("I have a meeting at 5pm, how should I prepare?"))

        // The quick-add sheet takes the time from anywhere.
        val r3 = qa.parseLoose("3:40pm Dentist tomorrow")!!
        assertEquals("Dentist", r3.title)
        assertEquals(at(2026, Calendar.NOVEMBER, 22, 15, 40), r3.dueAt)

        // Ambiguous time keeps its text so the gateway asks instead of dropping it.
        val r4 = qa.parseLoose("call the plumber at 6")!!
        assertEquals("call the plumber", r4.title)
        assertNull(r4.dueAt)
        assertEquals("at 6", r4.dueText)
    }
}
