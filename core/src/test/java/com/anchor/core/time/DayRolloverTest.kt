// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.time

import com.anchor.core.SharedFakeStorage
import com.anchor.core.gateway.Gateway
import com.anchor.core.models.DayRule
import com.anchor.core.nudge.CheckinBuilder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class DayRolloverTest {

    private class SettableClock(var time: Long) : Clock {
        override fun now(): Long = time
    }

    @Test
    fun testLateNightMessage_BelongsToPreviousCalendarDate() {
        val rule = DayRule(
            id = "rule1",
            effectiveFrom = 0L,
            startMinute = 240, // 04:00 AM
            endMinute = 1320,  // 22:00 PM
            tzId = "UTC",
            createdAt = 0L
        )

        // 2026-11-04 02:30:00 UTC (before 04:00 rollover)
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            set(2026, Calendar.NOVEMBER, 4, 2, 30, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val ts = cal.timeInMillis

        val dayKey = DayRollover.computeDayKey(ts, rule)
        assertEquals("2026-11-03", dayKey)
    }

    @Test
    fun testPostRolloverMessage_BelongsToCurrentCalendarDate() {
        val rule = DayRule(
            id = "rule1",
            effectiveFrom = 0L,
            startMinute = 240, // 04:00 AM
            endMinute = 1320,
            tzId = "UTC",
            createdAt = 0L
        )

        // 2026-11-04 04:01:00 UTC (after 04:00 rollover)
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            set(2026, Calendar.NOVEMBER, 4, 4, 1, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val ts = cal.timeInMillis

        val dayKey = DayRollover.computeDayKey(ts, rule)
        assertEquals("2026-11-04", dayKey)
    }

    @Test
    fun testScheduleNextRule_TakesEffectAtNextRollover_NeverMidDay() = runBlocking {
        val storage = SharedFakeStorage()

        val initialRule = DayRule(
            id = "rule_init",
            effectiveFrom = 0L,
            startMinute = 240, // 04:00
            endMinute = 1320,
            tzId = "UTC",
            createdAt = 0L
        )
        storage.saveDayRule(initialRule)

        // It is currently 2026-11-04 14:00:00 UTC (mid-day)
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            set(2026, Calendar.NOVEMBER, 4, 14, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val now = cal.timeInMillis

        // User changes day start to 06:00 (360 min)
        val newRule = DayRollover.scheduleNextRule(
            currentRule = initialRule,
            newStartMinute = 360,
            newEndMinute = 1320,
            newTzId = "UTC",
            nowUtc = now
        )
        storage.saveDayRule(newRule)

        // Next rollover should be 2026-11-05 04:00:00 UTC (under current rule's 04:00)
        val expectedRolloverCal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            set(2026, Calendar.NOVEMBER, 5, 4, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        assertEquals(expectedRolloverCal.timeInMillis, newRule.effectiveFrom)

        // At 14:00 today -> still initialRule (240 min)
        assertEquals(240, storage.getEffectiveDayRule(now).startMinute)

        // At 23:59:59 today -> still initialRule
        val endOfToday = cal.apply { set(2026, Calendar.NOVEMBER, 4, 23, 59, 59) }.timeInMillis
        assertEquals(240, storage.getEffectiveDayRule(endOfToday).startMinute)

        // At 03:59:59 tomorrow -> still initialRule
        val beforeRolloverTomorrow = cal.apply { set(2026, Calendar.NOVEMBER, 5, 3, 59, 59) }.timeInMillis
        assertEquals(240, storage.getEffectiveDayRule(beforeRolloverTomorrow).startMinute)

        // At 04:00:00 tomorrow (next rollover boundary) -> newRule (360 min) takes effect!
        val atRolloverTomorrow = cal.apply { set(2026, Calendar.NOVEMBER, 5, 4, 0, 0) }.timeInMillis
        assertEquals(360, storage.getEffectiveDayRule(atRolloverTomorrow).startMinute)
    }

    @Test
    fun testGatewayAndCheckinBuilder_UseEffectiveDayRule_NotDayStartMinuteSetting() = runBlocking {
        val storage = SharedFakeStorage()
        val clock = SettableClock(1700000000000L)
        val dateResolver = DateResolver(clock)
        val resolver = com.anchor.core.resolver.Resolver()
        val gateway = Gateway(storage, resolver, dateResolver, clock)
        val checkinBuilder = CheckinBuilder(storage, clock, dateResolver)

        // Set day_rule with start_minute = 480 (8 AM)
        val customRule = DayRule(
            id = "rule_custom",
            effectiveFrom = 0L,
            startMinute = 480, // 8:00 AM
            endMinute = 1320,
            tzId = TimeZone.getDefault().id,
            createdAt = 0L
        )
        storage.saveDayRule(customRule)

        // Intentionally set a conflicting/stale day_start_minute setting
        storage.saveSetting("day_start_minute", "120") // 2:00 AM - should be ignored!

        // When Gateway resolves the day rule for proposing a change or reminder
        val effectiveRule = storage.getEffectiveDayRule(clock.now())
        assertEquals("Single source of truth must be day_rule", 480, effectiveRule.startMinute)

        // Checkin facts should use the effective rule's logical day start
        val logicalStartFromRule = dateResolver.getLogicalDayStart(clock.now(), effectiveRule.startMinute)
        val facts = checkinBuilder.buildMorningFacts()
        assertTrue("Facts generated without crashing", facts.isNotEmpty())
    }

    @Test
    fun testDefaultFallback_WhenNoRulePresent() = runBlocking {
        val storage = SharedFakeStorage()
        val rule = storage.getEffectiveDayRule(System.currentTimeMillis())
        assertEquals(240, rule.startMinute)
        assertEquals(1320, rule.endMinute)
        assertEquals(TimeZone.getDefault().id, rule.tzId)
    }

    @Test
    fun testTimezoneChange_ProducesStrictlyFutureRolloverBoundary() {
        val initialRule = DayRule(
            id = "rule_utc",
            effectiveFrom = 0L,
            startMinute = 240, // 04:00 AM
            endMinute = 1320,
            tzId = "UTC",
            createdAt = 0L
        )

        // 2026-11-04 20:00:00 UTC
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            set(2026, Calendar.NOVEMBER, 4, 20, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val nowUtc = cal.timeInMillis

        // Change timezone to Asia/Tokyo (+9h) -> 20:00 UTC is 05:00 next day in Tokyo
        val nextTokyoRollover = DayRollover.getNextRollover(
            nowUtc = nowUtc,
            currentRule = initialRule,
            targetTzId = "Asia/Tokyo",
            targetStartMinute = 240
        )
        assertTrue(
            "Rollover in target timezone must be strictly in the future",
            nextTokyoRollover > nowUtc
        )

        // Change timezone to Pacific/Honolulu (-10h) -> 20:00 UTC is 10:00 same day in Honolulu
        val nextHonoluluRollover = DayRollover.getNextRollover(
            nowUtc = nowUtc,
            currentRule = initialRule,
            targetTzId = "Pacific/Honolulu",
            targetStartMinute = 240
        )
        assertTrue(
            "Rollover in target timezone must be strictly in the future",
            nextHonoluluRollover > nowUtc
        )
    }

    @Test
    fun testSafeTimeZone_InvalidIdFallsBackToDefaultNotGmt() {
        // Compute day key with invalid timezone id
        val ruleWithBogusTz = DayRule(
            id = "bogus",
            effectiveFrom = 0L,
            startMinute = 240,
            endMinute = 1320,
            tzId = "Invalid/Bogus_TimeZone_XYZ",
            createdAt = 0L
        )
        val now = 1730700000000L // arbitrary timestamp
        val dayKey = DayRollover.computeDayKey(now, ruleWithBogusTz)

        val expectedDayKey = DayRollover.computeDayKey(
            now,
            ruleWithBogusTz.copy(tzId = TimeZone.getDefault().id)
        )
        assertEquals("Invalid tzId should resolve to default timezone", expectedDayKey, dayKey)
    }
}
