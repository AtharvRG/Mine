// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.time

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}