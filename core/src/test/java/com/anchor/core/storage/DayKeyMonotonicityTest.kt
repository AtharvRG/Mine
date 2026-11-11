// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.storage

import com.anchor.core.SharedFakeStorage
import com.anchor.core.time.FakeClock
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class DayKeyMonotonicityTest {

    private val utcRule = com.anchor.core.models.DayRule(
        id = "r_utc",
        effectiveFrom = 0L,
        startMinute = 240, // 04:00 AM
        endMinute = 1320,  // 22:00 PM
        tzId = "UTC",
        createdAt = 0L
    )

    @Test
    fun testSharedFakeStorage_DayKeyMonotonicity_ClockRegressDoesNotRegressDayKey() = runBlocking {
        // Nov 10, 2026 12:00 UTC (well after 04:00 rollover)
        val clock = FakeClock(1794312000000L)
        val storage = SharedFakeStorage(clock)
        storage.saveDayRule(utcRule)

        storage.insertRawMessage("m1", "user", "Hello today")
        val m1 = storage.messages.first { it.id == "m1" }
        assertEquals("2026-11-10", m1.dayKey)

        // Clock jumps backward 2 days to Nov 8, 2026
        clock.currentTime = 1794139200000L
        storage.insertRawMessage("m2", "user", "Message after clock skew backward")
        val m2 = storage.messages.first { it.id == "m2" }

        // Blueprint 11 & 50 §3.13: day_key is monotonic (never earlier than previous message's day_key)
        assertEquals("day_key must not regress on clock skew backward", "2026-11-10", m2.dayKey)
    }

    @Test
    fun testSharedFakeStorage_DayKey_CopiedFromCauseMessageAcrossRollover() = runBlocking {
        // Nov 10, 2026 23:59:50 UTC (before rollover, dayKey = 2026-11-10)
        val clock = FakeClock(1794355190000L)
        val storage = SharedFakeStorage(clock)
        storage.saveDayRule(utcRule)

        storage.insertRawMessage("u1", "user", "What is quantum computing?")
        val u1 = storage.messages.first { it.id == "u1" }
        assertEquals("2026-11-10", u1.dayKey)

        // Advance clock past midnight / rollover to Nov 11, 2026 04:05:00 UTC (dayKey would be 2026-11-11)
        clock.currentTime = 1794370000000L

        // Assistant reply finishes streaming and inserts with causeMsgId = u1
        storage.insertRawMessage("a1", "assistant", "Quantum computing is...", causeMsgId = "u1", dayKey = null)
        val a1 = storage.messages.first { it.id == "a1" }

        // Blueprint 50 §3.13: Day rollover while reply is streaming -> day_key copied from cause message
        assertEquals("day_key of reply must match cause message day_key", "2026-11-10", a1.dayKey)
    }

    @Test
    fun testSharedFakeStorage_DayKey_ExplicitDayKeyHonored() = runBlocking {
        val clock = FakeClock(1794312000000L)
        val storage = SharedFakeStorage(clock)
        storage.saveDayRule(utcRule)

        storage.insertRawMessage("m_custom", "system", "System note", causeMsgId = null, dayKey = "2026-11-15")
        val mCustom = storage.messages.first { it.id == "m_custom" }
        assertEquals("2026-11-15", mCustom.dayKey)
    }
}
