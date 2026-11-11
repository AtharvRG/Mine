// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.storage

import com.anchor.core.SharedFakeStorage
import com.anchor.core.models.Node
import com.anchor.core.time.FakeClock
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StaleNodesClockTest {

    @Test
    fun testGetStaleNodes_RequiresNowAndDefaultsTo30Days() = runBlocking {
        val baseTime = 1_700_000_000_000L
        val clock = FakeClock(baseTime)
        val storage = SharedFakeStorage(clock)

        val thirtyOneDaysAgo = baseTime - (31L * 24 * 3600 * 1000L)
        val twentyNineDaysAgo = baseTime - (29L * 24 * 3600 * 1000L)

        val staleNode = Node(
            id = "n_stale",
            type = "task",
            title = "Stale Task",
            status = "active",
            createdAt = thirtyOneDaysAgo,
            updatedAt = thirtyOneDaysAgo
        )
        val freshNode = Node(
            id = "n_fresh",
            type = "task",
            title = "Fresh Task",
            status = "active",
            createdAt = twentyNineDaysAgo,
            updatedAt = twentyNineDaysAgo
        )
        storage.activeNodes.addAll(listOf(staleNode, freshNode))

        // Invoking getStaleNodes with only required `now` parameter (default cutoff: 30 days)
        val staleItems = storage.getStaleNodes(now = clock.now())
        assertEquals(1, staleItems.size)
        assertEquals("n_stale", staleItems[0].id)

        // Custom cutoff: 20 days -> both nodes are older than 20 days
        val customCutoff = 20L * 24 * 3600 * 1000L
        val customStale = storage.getStaleNodes(now = clock.now(), cutoffMs = customCutoff)
        assertEquals(2, customStale.size)
        // Parity with AndroidStorage ORDER BY created_at DESC: fresher node comes first
        assertEquals(listOf("n_fresh", "n_stale"), customStale.map { it.id })

        // Advancing clock forward advances stale detection deterministically
        clock.currentTime += 5L * 24 * 3600 * 1000L // 5 days later
        // Now freshNode is 34 days old, so with default 30-day cutoff both should be stale
        val laterStale = storage.getStaleNodes(now = clock.now())
        assertEquals(2, laterStale.size)
        assertEquals(listOf("n_fresh", "n_stale"), laterStale.map { it.id })
    }

    @Test
    fun testGetStaleNodes_ExcludesLocalOnly() = runBlocking {
        val baseTime = 1_700_000_000_000L
        val clock = FakeClock(baseTime)
        val storage = SharedFakeStorage(clock)

        val oldTime = baseTime - (40L * 24 * 3600 * 1000L)
        storage.activeNodes.add(Node("public_stale", "task", "Public", "active", createdAt = oldTime, updatedAt = oldTime, localOnly = false))
        storage.activeNodes.add(Node("private_stale", "task", "Private", "active", createdAt = oldTime, updatedAt = oldTime, localOnly = true))

        val stale = storage.getStaleNodes(clock.now())
        assertEquals(1, stale.size)
        assertEquals("public_stale", stale[0].id)
    }

    @Test
    fun testGetUrgentNodes_ExcludesLocalOnly() = runBlocking {
        val storage = SharedFakeStorage()
        storage.activeNodes.add(Node("u_pub", "task", "Urgent Public", "active", dueAt = 1000L, localOnly = false))
        storage.activeNodes.add(Node("u_priv", "task", "Urgent Private", "active", dueAt = 500L, localOnly = true))

        val urgent = storage.getUrgentNodes()
        assertEquals(1, urgent.size)
        assertEquals("u_pub", urgent[0].id)
    }

    @Test
    fun testGetDebugStats_ReturnsCollectionCounts() = runBlocking {
        val storage = SharedFakeStorage()
        storage.messages.add(com.anchor.core.models.ChatMessage("m1", "user", "hi"))
        storage.activeNodes.add(Node("n1", "task", "title", "active"))
        val stats = storage.getDebugStats()
        assertEquals("1", stats["Rows in raw_message"])
        assertEquals("1", stats["Rows in node"])
        assertEquals("0", stats["Rows in reminder"])
    }

    @Test
    fun testGetStaleNodes_PositionalCallRequiresNowFirst() = runBlocking {
        val baseTime = 1_700_000_000_000L
        val clock = FakeClock(baseTime)
        val storage = SharedFakeStorage(clock)

        val thirtyOneDaysAgo = baseTime - (31L * 24 * 3600 * 1000L)
        val staleNode = Node("n1", "task", "Old", "active", createdAt = thirtyOneDaysAgo, updatedAt = thirtyOneDaysAgo)
        storage.activeNodes.add(staleNode)

        // Positional call with only now
        val stale1 = storage.getStaleNodes(clock.now())
        assertEquals(1, stale1.size)

        // Positional call with now and cutoffMs
        val stale2 = storage.getStaleNodes(clock.now(), 40L * 24 * 3600 * 1000L)
        assertEquals(0, stale2.size)
    }
}
