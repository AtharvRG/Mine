// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine Author
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.context

import com.anchor.core.models.ChatMessage
import com.anchor.core.models.Node
import com.anchor.core.models.Proposal
import com.anchor.core.models.RawEvent
import com.anchor.core.storage.Storage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextBuilderTest {

    class FakeStorage(val messages: List<ChatMessage>, val nodes: List<Node>) : Storage {
        override val ioDispatcher: CoroutineDispatcher = Dispatchers.Unconfined
        override suspend fun insertRawMessage(id: String, content: String) {}
        override suspend fun getRawMessage(id: String): String? = null

        override suspend fun getRecentMessages(limit: Int): List<ChatMessage> = messages.takeLast(limit)

        override suspend fun getActiveNodes(): List<Node> = nodes
        override suspend fun updateNodeStatus(nodeId: String, newStatus: String) {}
        override suspend fun logEvent(event: RawEvent) {}
        override suspend fun saveProposal(proposal: Proposal) {}
    }

    @Test
    fun testContextBuilder_TruncatesChatToRespectBudget() = runBlocking {
        // Create 20 large messages, each about 100 tokens (350 chars)
        // Total history = 2000 tokens (Exceeds the 1500 token chat budget)
        val bigText = "A".repeat(350)
        val history = (1..20).map { i ->
            ChatMessage("m$i", "user", "$i $bigText")
        }

        val nodes = listOf(Node("n1", "task", "Buy milk", "active"))
        val storage = FakeStorage(history, nodes)

        val builder = ContextBuilder(storage)
        val pack = builder.buildPack("What did I say?")

        // 1 for system, N for history, 1 for new message
        val historyIncluded = pack.filter { it.id.startsWith("m") }

        // It shouldn't include all 20 messages.
        // 1500 tokens / ~100 tokens per message = roughly 14-15 messages allowed.
        assertTrue("Should truncate history to fit budget", historyIncluded.size < 20)
        assertTrue("Should still include some history", historyIncluded.isNotEmpty())

        // Ensure chronological order (last item in historyIncluded should be m20)
        assertEquals("m20", historyIncluded.last().id)

        // Ensure System prompt has the nodes
        val sysMsg = pack.first { it.role == "system" }
        assertTrue(sysMsg.content.contains("Buy milk"))

        // Ensure last message is the new user message
        assertEquals("What did I say?", pack.last().content)
    }
}