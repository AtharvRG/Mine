// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.retrieval

import com.anchor.core.models.ChatMessage
import com.anchor.core.models.Node
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DigestGeneratorTest {

    private val generator = DigestGenerator()

    @Test
    fun testGenerateDayDigest_WithMessagesAndNodes() {
        val messages = listOf(
            ChatMessage("m1", "user", "Remember to call dentist today"),
            ChatMessage("m2", "assistant", "Reminder set for call dentist at 2pm"),
            ChatMessage("m3", "user", "Done with dentist appointment")
        )
        val nodes = listOf(
            Node("n1", "task", "Call dentist", "done", dueAt = 1000L, closedAt = 2000L),
            Node("n2", "task", "Submit expense report", "active", dueAt = 3000L)
        )

        val digest = generator.generateDayDigest("2026-10-25", messages, nodes)

        assertEquals("2026-10-25", digest.dayKey)
        assertTrue(digest.completedTasks.contains("Call dentist"))
        assertTrue(digest.openLoops.contains("Submit expense report"))
        assertEquals(listOf("m1", "m2", "m3"), digest.msgIds)
        assertTrue(digest.summaryText.contains("Day Digest: 2026-10-25"))
        assertTrue(digest.summaryText.contains("Completed: Call dentist"))
        assertTrue(digest.summaryText.contains("Open loop: Submit expense report"))
    }

    @Test
    fun testGenerateDayDigest_EmptyInputs_ProducesGracefulDigest() {
        val digest = generator.generateDayDigest("2026-10-26", emptyList(), emptyList())
        assertEquals("2026-10-26", digest.dayKey)
        assertTrue(digest.completedTasks.isEmpty())
        assertTrue(digest.openLoops.isEmpty())
        assertTrue(digest.summaryText.contains("No messages or task updates"))
    }

    @Test
    fun testGenerateWeekDigest_CombinesSevenDays() {
        val day1 = DayDigest("2026-10-20", "Day 1", listOf("Task A"), listOf("Task B"), listOf("m1"), "Day 1 text")
        val day2 = DayDigest("2026-10-21", "Day 2", listOf("Task B"), listOf("Task C"), listOf("m2"), "Day 2 text")

        val weekDigest = generator.generateWeekDigest("2026-W43", listOf(day1, day2))

        assertEquals("2026-W43", weekDigest.weekKey)
        assertEquals(2, weekDigest.totalCompleted)
        assertTrue(weekDigest.ongoingLoops.contains("Task C"))
        assertTrue(weekDigest.summaryText.contains("Week Digest: 2026-W43"))
    }

    @Test
    fun testGenerateMonthDigest_CombinesWeeks() {
        val week1 = WeekDigest("2026-W40", "Week 1", 5, listOf("Loop 1"), "Summary 1")
        val week2 = WeekDigest("2026-W41", "Week 2", 7, listOf("Loop 2"), "Summary 2")

        val monthDigest = generator.generateMonthDigest("2026-10", listOf(week1, week2))

        assertEquals("2026-10", monthDigest.monthKey)
        assertEquals(12, monthDigest.totalCompleted)
        assertTrue(monthDigest.summaryText.contains("Month Digest: 2026-10"))
    }
}
