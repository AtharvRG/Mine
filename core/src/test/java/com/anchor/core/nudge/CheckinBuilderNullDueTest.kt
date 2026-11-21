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

class CheckinBuilderNullDueTest {

    @Test
    fun testDueTodayRowWithNullDueAt_DoesNotThrow() = runBlocking {
        // H-10 (R-14): the agenda line used `it.dueAt!!`. Both storage implementations filter
        // `due_at IS NOT NULL`, so no such row exists today — but the builder is the only thing
        // between a null and a crash of every context build, and a future query change (or a
        // migration that leaves a row undated) turns that into a NPE on the whole chat path.
        // A row without a due date must degrade to a title, not throw.
        val clock = FakeClock(1700000000000L)
        val dated = Node(
            id = "n_dated",
            type = "task",
            title = "Pay rent",
            status = "active",
            dueAt = clock.now() + 3600_000L,
            createdAt = clock.now(),
            updatedAt = clock.now()
        )
        val undated = Node(
            id = "n_undated",
            type = "task",
            title = "Call the dentist",
            status = "active",
            dueAt = null,
            createdAt = clock.now(),
            updatedAt = clock.now()
        )
        val storage = object : SharedFakeStorage(clock) {
            override suspend fun getNodesDueBetween(startMs: Long, endMs: Long, limit: Int, includeLocalOnly: Boolean): List<Node> =
                listOf(undated, dated)
        }

        val builder = CheckinBuilder(storage, clock, DateResolver(clock) { true })
        val facts = builder.buildMorningFacts()

        assertTrue("The dated row must still appear: $facts", facts.contains("Pay rent"))
        assertTrue("The undated row must still be listed by title: $facts", facts.contains("Call the dentist"))
        assertFalse("A null dueAt must never surface as 'null': $facts", facts.contains("null"))
    }
}
