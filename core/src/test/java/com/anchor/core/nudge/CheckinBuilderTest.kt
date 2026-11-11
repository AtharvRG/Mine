// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.nudge

import com.anchor.core.SharedFakeStorage
import com.anchor.core.models.Node
import com.anchor.core.time.DateResolver
import com.anchor.core.time.FakeClock
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CheckinBuilderTest {

    @Test
    fun testBuildMorningFacts_IncludesDueTodayAndOverdue() = runBlocking {
        val clock = FakeClock(1700000000000L) // Fixed timestamp
        val dateResolver = DateResolver(clock) { true }
        val storage = SharedFakeStorage(clock)

        val todayNode = Node(
            id = "n_today",
            type = "task",
            title = "Morning Standup",
            status = "active",
            dueAt = clock.now() + 10_000L,
            createdAt = clock.now(),
            updatedAt = clock.now()
        )
        val builder = CheckinBuilder(storage, clock, dateResolver)
        storage.activeNodes.add(todayNode)
        val facts = builder.buildMorningFacts()

        assertTrue(facts.contains("Today's Facts:"))
        assertTrue(facts.contains("- Due Today: 1"))
        assertTrue(facts.contains("Morning Standup"))
    }

    @Test
    fun testBuildEveningFacts_IncludesCompletedToday_StillOpen_AndTomorrowPreview() = runBlocking {
        val clock = FakeClock(1700000000000L)
        val dateResolver = DateResolver(clock) { true }
        val storage = SharedFakeStorage(clock)

        val doneNode = Node(
            id = "n_done",
            type = "task",
            title = "Ship release notes",
            status = "done",
            closedAt = clock.now() - 1000L,
            createdAt = clock.now() - 3600_000L,
            updatedAt = clock.now() - 1000L
        )
        val openNode = Node(
            id = "n_open",
            type = "task",
            title = "Review PR",
            status = "active",
            createdAt = clock.now(),
            updatedAt = clock.now()
        )
        val tomorrowNode = Node(
            id = "n_tomorrow",
            type = "task",
            title = "Team retro",
            status = "active",
            dueAt = clock.now() + 24 * 3600_000L,
            createdAt = clock.now(),
            updatedAt = clock.now()
        )
        storage.activeNodes.add(doneNode)
        storage.activeNodes.add(openNode)
        storage.activeNodes.add(tomorrowNode)

        val builder = CheckinBuilder(storage, clock, dateResolver)
        val facts = builder.buildEveningFacts()

        assertTrue(facts.contains("Evening Facts:"))
        assertTrue(facts.contains("- Completed Today: 1"))
        assertTrue(facts.contains("Ship release notes"))
        assertTrue(facts.contains("- Still Open: 2"))
        assertTrue(facts.contains("Review PR"))
        assertTrue(facts.contains("Tomorrow Preview:"))
        assertTrue(facts.contains("Team retro"))
    }

    @Test
    fun testBuildEveningFacts_ExcludesLocalOnlyItems() = runBlocking {
        val clock = FakeClock(1700000000000L)
        val dateResolver = DateResolver(clock) { true }
        val storage = SharedFakeStorage(clock)

        val localDone = Node(
            id = "n_local_done",
            type = "task",
            title = "Private Health Log",
            status = "done",
            localOnly = true,
            closedAt = clock.now() - 1000L,
            createdAt = clock.now(),
            updatedAt = clock.now()
        )
        val localTomorrow = Node(
            id = "n_local_tomorrow",
            type = "task",
            title = "Secret doctor appointment",
            status = "active",
            localOnly = true,
            dueAt = clock.now() + 24 * 3600_000L,
            createdAt = clock.now(),
            updatedAt = clock.now()
        )
        storage.activeNodes.add(localDone)
        storage.activeNodes.add(localTomorrow)

        val builder = CheckinBuilder(storage, clock, dateResolver)
        val facts = builder.buildEveningFacts()

        assertFalse(facts.contains("Private Health Log"))
        assertFalse(facts.contains("Secret doctor appointment"))
    }

    @Test
    fun testBuildWeeklyFacts_IncludesCompletedAndBacklog() = runBlocking {
        val clock = FakeClock(1700000000000L)
        val dateResolver = DateResolver(clock) { true }
        val storage = SharedFakeStorage(clock)

        val doneNode = Node(
            id = "n_done_week",
            type = "task",
            title = "Launch v1",
            status = "done",
            closedAt = clock.now() - 3600_000L,
            createdAt = clock.now() - 7200_000L,
            updatedAt = clock.now() - 3600_000L
        )
        storage.activeNodes.add(doneNode)

        val builder = CheckinBuilder(storage, clock, dateResolver)
        val facts = builder.buildWeeklyFacts()

        assertTrue(facts.contains("Weekly Review Facts:"))
        assertTrue(facts.contains("- Completed Recent: 1"))
        assertTrue(facts.contains("Launch v1"))
    }

    private val utcRule = com.anchor.core.models.DayRule(
        id = "r_utc",
        effectiveFrom = 0L,
        startMinute = 240, // 04:00 AM
        endMinute = 1320,  // 22:00 PM (evening threshold = 1230 = 20:30)
        tzId = "UTC",
        createdAt = 0L
    )

    @Test
    fun testBuildCurrentFacts_Morning_ReturnsMorningFacts() = runBlocking {
        // Tuesday Nov 10, 2026 09:00:00 UTC
        val clock = FakeClock(1794301200000L)
        val storage = SharedFakeStorage(clock)
        storage.saveDayRule(utcRule)
        val dateResolver = DateResolver(clock) { true }
        val builder = CheckinBuilder(storage, clock, dateResolver)

        val facts = builder.buildCurrentFacts()
        assertTrue("Morning facts must have Today's Facts header", facts.contains("Today's Facts:"))
        assertFalse("Morning facts must not have Evening Facts", facts.contains("Evening Facts:"))
    }

    @Test
    fun testBuildCurrentFacts_Evening_ReturnsEveningFacts() = runBlocking {
        // Tuesday Nov 10, 2026 20:45:00 UTC
        val clock = FakeClock(1794343500000L)
        val storage = SharedFakeStorage(clock)
        storage.saveDayRule(utcRule)
        val dateResolver = DateResolver(clock) { true }
        val builder = CheckinBuilder(storage, clock, dateResolver)

        val facts = builder.buildCurrentFacts()
        assertTrue("Evening facts must have Evening Facts header", facts.contains("Evening Facts:"))
        assertFalse("Evening facts must not have Today's Facts", facts.contains("Today's Facts:"))
    }

    @Test
    fun testBuildCurrentFacts_SundayEvening_ReturnsWeeklyAndEveningFacts() = runBlocking {
        // Sunday Nov 15, 2026 20:45:00 UTC
        val clock = FakeClock(1794775500000L)
        val storage = SharedFakeStorage(clock)
        storage.saveDayRule(utcRule)
        val dateResolver = DateResolver(clock) { true }
        val builder = CheckinBuilder(storage, clock, dateResolver)

        val facts = builder.buildCurrentFacts()
        assertTrue("Sunday evening must have Weekly Review Facts", facts.contains("Weekly Review Facts:"))
        assertTrue("Sunday evening must also have Evening Facts", facts.contains("Evening Facts:"))
    }

    @Test
    fun testBuildTemplateNarration_ProducesPersonaNarrationOffline() {
        val clock = FakeClock(1794301200000L)
        val storage = SharedFakeStorage(clock)
        val dateResolver = DateResolver(clock) { true }
        val builder = CheckinBuilder(storage, clock, dateResolver)

        val morningFacts = "Today's Facts:\n- Due Today: 2\nAgenda:\n  * Buy milk\n  * Team sync"
        val morningNarration = builder.buildTemplateNarration(morningFacts)
        assertTrue(morningNarration.contains("Good morning"))
        assertTrue(morningNarration.contains("Buy milk"))

        val eveningFacts = "Evening Facts:\n- Completed Today: 1\n  * Buy milk\n- Still Open: 1"
        val eveningNarration = builder.buildTemplateNarration(eveningFacts)
        assertTrue(eveningNarration.contains("evening"))
        assertTrue(eveningNarration.contains("How did the day feel?"))
    }
}
