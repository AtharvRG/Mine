// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.storage

import com.anchor.core.SharedFakeStorage
import com.anchor.core.models.Edge
import com.anchor.core.models.Node
import com.anchor.core.time.FakeClock
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GraphNeighborhoodAndCalendarTest {

    @Test
    fun testGraphNeighborhood_1Hop_ReturnsImmediateNeighbors() = runBlocking {
        val clock = FakeClock(100_000L)
        val storage = SharedFakeStorage(clock)

        val nA = Node("nA", "task", "Parent Project", "active")
        val nB = Node("nB", "task", "Subtask B", "active")
        val nC = Node("nC", "task", "Subtask C", "active")
        val nD = Node("nD", "task", "Unrelated Task", "active")

        storage.saveNode(nA)
        storage.saveNode(nB)
        storage.saveNode(nC)
        storage.saveNode(nD)

        storage.saveEdge(Edge("e1", "nA", "nB", "node", "contains"))
        storage.saveEdge(Edge("e2", "nA", "nC", "node", "contains"))

        val neighborhood = storage.getGraphNeighborhood("nA", maxHops = 1, cap = 150)
        val ids = neighborhood.map { it.id }.toSet()

        assertTrue(ids.contains("nB"))
        assertTrue(ids.contains("nC"))
        assertFalse("Unconnected node nD must not be in neighborhood", ids.contains("nD"))
    }

    @Test
    fun testGraphNeighborhood_2Hops_RespectsMaxHopsAndCycle() = runBlocking {
        val clock = FakeClock(100_000L)
        val storage = SharedFakeStorage(clock)

        val nA = Node("nA", "task", "Node A", "active")
        val nB = Node("nB", "task", "Node B", "active")
        val nC = Node("nC", "task", "Node C", "active")
        val nD = Node("nD", "task", "Node D (3 hops)", "active")

        storage.saveNode(nA)
        storage.saveNode(nB)
        storage.saveNode(nC)
        storage.saveNode(nD)

        // Chain: A -> B -> C -> D, and cycle B -> A
        storage.saveEdge(Edge("e1", "nA", "nB", "node", "depends_on"))
        storage.saveEdge(Edge("e2", "nB", "nC", "node", "depends_on"))
        storage.saveEdge(Edge("e3", "nC", "nD", "node", "depends_on"))
        storage.saveEdge(Edge("e4", "nB", "nA", "node", "relates_to")) // cycle back to A

        val neighborhood = storage.getGraphNeighborhood("nA", maxHops = 2, cap = 150)
        val ids = neighborhood.map { it.id }.toSet()

        assertTrue(ids.contains("nB")) // 1 hop
        assertTrue(ids.contains("nC")) // 2 hops
        assertFalse("Node D is 3 hops away, must be excluded at maxHops=2", ids.contains("nD"))

        // Cap test: cap = 1 must return at most 1 neighbor
        val capped = storage.getGraphNeighborhood("nA", maxHops = 2, cap = 1)
        assertEquals(1, capped.size)
    }

    @Test
    fun testCalendarBuckets_GroupsNodesByDay() = runBlocking {
        val clock = FakeClock(100_000L)
        val storage = SharedFakeStorage(clock)

        // Day 1: 1700000000000, Day 2: 1700000000000 + 86400000
        val t1 = 1700000000000L
        val t2 = t1 + 86_400_000L

        val n1 = Node("n1", "task", "Task Day 1 A", "active", dueAt = t1)
        val n2 = Node("n2", "task", "Task Day 1 B", "active", dueAt = t1 + 3600_000L)
        val n3 = Node("n3", "task", "Task Day 2", "active", dueAt = t2)

        storage.saveNode(n1)
        storage.saveNode(n2)
        storage.saveNode(n3)

        val buckets = storage.getCalendarBuckets(startMs = t1 - 1000L, endMs = t2 + 1000L)

        assertEquals("Must return 2 distinct day buckets", 2, buckets.keys.size)
        val totalNodes = buckets.values.sumOf { it.size }
        assertEquals(3, totalNodes)
    }

    @Test
    fun testBoardView_GroupsByStatusColumns() = runBlocking {
        val clock = FakeClock(100_000L)
        val storage = SharedFakeStorage(clock)

        val n1 = Node("n1", "task", "Todo task", "active")
        val n2 = Node("n2", "task", "In progress task", "in_progress")
        val n3 = Node("n3", "task", "Paused task", "paused")
        val n4 = Node("n4", "task", "Done task", "done")

        storage.saveNode(n1)
        storage.saveNode(n2)
        storage.saveNode(n3)
        storage.saveNode(n4)

        val board = storage.getBoardViewNodes(limitPerColumn = 10)

        assertEquals(3, board.keys.size)
        assertTrue(board.containsKey("todo"))
        assertTrue(board.containsKey("in_progress"))
        assertTrue(board.containsKey("done"))

        assertEquals(2, board["todo"]!!.size) // active + paused
        assertEquals(1, board["in_progress"]!!.size)
        assertEquals(1, board["done"]!!.size)
    }
}
