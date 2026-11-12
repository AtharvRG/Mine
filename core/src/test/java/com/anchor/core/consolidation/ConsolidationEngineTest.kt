// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.consolidation

import com.anchor.core.models.Node
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConsolidationEngineTest {

    @Test
    fun testConsolidateDay_DeterministicSummary() {
        val engine = ConsolidationEngine()
        val completed = listOf(
            Node(id = "c1", type = "task", title = "Morning run", status = "done", createdAt = 0L, updatedAt = 0L),
            Node(id = "c2", type = "task", title = "Submit project proposal", status = "done", createdAt = 0L, updatedAt = 0L)
        )
        val open = listOf(
            Node(id = "o1", type = "task", title = "Review team feedback", status = "active", createdAt = 0L, updatedAt = 0L)
        )

        val digest = engine.consolidateDay("2026-11-12", completed, open, emptyList(), 1700000000L)
        assertEquals("2026-11-12", digest.dayKey)
        assertEquals(2, digest.completedCount)
        assertEquals(1, digest.openCount)
        assertTrue(digest.summaryText.contains("2 tasks completed"))
        assertEquals(2, digest.wins.size)
        assertEquals(1, digest.openLoops.size)
    }

    @Test
    fun testReflectSoulPatterns_StreakAndLearningReflections() {
        val engine = ConsolidationEngine()
        val completed = listOf(
            Node(id = "h1", type = "task", title = "Floss teeth", status = "done", streakCurrent = 7, createdAt = 0L, updatedAt = 0L),
            Node(id = "s1", type = "task", title = "Study Rust chapter 4", status = "done", createdAt = 0L, updatedAt = 0L),
            Node(id = "s2", type = "task", title = "Read Android architecture guide", status = "done", createdAt = 0L, updatedAt = 0L),
            Node(id = "s3", type = "task", title = "Learn Compose animations", status = "done", createdAt = 0L, updatedAt = 0L)
        )

        val reflections = engine.reflectSoulPatterns(completed, emptyList(), 1700000000L)
        assertTrue("Should detect habit streak and learning pattern", reflections.size >= 2)

        val habitCandidate = reflections.firstOrNull { it.category == "habits_routines" }
        assertNotNull(habitCandidate)
        assertTrue(habitCandidate!!.statement.contains("floss teeth"))

        val valuesCandidate = reflections.firstOrNull { it.category == "values" }
        assertNotNull(valuesCandidate)
        assertTrue(valuesCandidate!!.statement.contains("self-learning"))

        val proposedFact = engine.toProposedSoulFact(habitCandidate, 1700000000L)
        assertEquals("proposed", proposedFact.status)
        assertEquals("ai_inferred", proposedFact.source)
    }
}
