// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.retrieval

import com.anchor.core.SharedFakeStorage
import com.anchor.core.models.ChatMessage
import com.anchor.core.models.Node
import com.anchor.core.models.SoulFact
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchFtsParityTest {

    @Test
    fun testSearchMemoryFts_MultiTokenAndQuery_MatchesOutOfOrder() = runBlocking {
        val storage = SharedFakeStorage().apply {
            activeNodes.add(Node("n1", "task", "Buy fresh almond milk", "active"))
            soulFacts.add(SoulFact("s1", "diet", "Prefers drinking oat milk over dairy", 0.9, "chat", "approved", "active", 100L))
            messages.add(ChatMessage("m1", "user", "Remember to grab almond milk from store", tsUtc = 1000L))
        }

        // Search with out-of-order and interleaved tokens
        val hits = storage.searchMemoryFts("milk almond", limit = 10)
        assertTrue("Node must match multi-token AND: $hits", hits.any { it.id == "n1" })
        assertTrue("Chat chunk must match multi-token AND: $hits", hits.any { it.id == "chunk_m1" })

        val soulHits = storage.searchMemoryFts("dairy oat", limit = 10)
        assertTrue("Soul fact must match multi-token AND: $soulHits", soulHits.any { it.id == "s1" })
    }

    @Test
    fun testSearchHistory_MultiTokenAndQuery_MatchesOutOfOrder() = runBlocking {
        val storage = SharedFakeStorage().apply {
            messages.add(ChatMessage("m10", "user", "Need to file quarterly taxes before Friday", tsUtc = 2000L))
            messages.add(ChatMessage("m11", "assistant", "I will remind you about taxes on Thursday", tsUtc = 2001L))
        }

        val hits = storage.searchHistory("taxes quarterly", limit = 10)
        assertEquals("Exactly one message matches both tokens", 1, hits.size)
        assertEquals("m10", hits[0].id)
    }

    @Test
    fun testSearchMemoryFts_RespectsStatusFilter() = runBlocking {
        val storage = SharedFakeStorage().apply {
            activeNodes.add(Node("n_active", "task", "Quarterly taxes review", "active"))
            activeNodes.add(Node("n_progress", "task", "Quarterly budget review", "in_progress"))
            activeNodes.add(Node("n_done", "task", "Quarterly report review", "done"))
            activeNodes.add(Node("n_archived", "task", "Quarterly audit review", "archived"))
            activeNodes.add(Node("n_cancelled", "task", "Quarterly tax cancellation", "cancelled"))
        }

        val hits = storage.searchMemoryFts("quarterly review", limit = 10)
        val hitIds = hits.map { it.id }
        assertTrue("Active node must match", hitIds.contains("n_active"))
        assertTrue("In-progress node must match", hitIds.contains("n_progress"))
        assertFalse("Done node must be excluded", hitIds.contains("n_done"))
        assertFalse("Archived node must be excluded", hitIds.contains("n_archived"))
        assertFalse("Cancelled node must be excluded", hitIds.contains("n_cancelled"))
    }
}
