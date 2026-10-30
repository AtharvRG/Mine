// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.context

import com.anchor.core.SharedFakeStorage
import com.anchor.core.models.ChatMessage
import com.anchor.core.models.Node
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextBuilderTest {

    @Test
    fun testContextBuilder_TruncatesChatToRespectBudget() = runBlocking {
        // Create 20 large messages, each about 100 tokens (350 chars)
        // Total history = 2000 tokens (Exceeds the 1500 token chat budget)
        val bigText = "A".repeat(350)
        val history = (1..20).map { i ->
            ChatMessage("m$i", "user", "$i $bigText")
        }
        val newUserMsg = ChatMessage("m21", "user", "What did I say?")

        val nodes = listOf(Node("n1", "task", "Buy milk", "active"))
        val storage = SharedFakeStorage().apply {
            messages = (history + newUserMsg).toMutableList()
            activeNodes = nodes.toMutableList()
        }

        val builder = ContextBuilder(storage)
        val pack = builder.buildPack()

        // 1 for system, N for history
        val historyIncluded = pack.filter { it.id.startsWith("m") }

        // It shouldn't include all 21 messages.
        // 1500 tokens / ~100 tokens per message = roughly 14-15 messages allowed.
        assertTrue("Should truncate history to fit budget", historyIncluded.size < 21)
        assertTrue("Should still include some history", historyIncluded.isNotEmpty())

        // Ensure chronological order (last item in historyIncluded should be m21)
        assertEquals("m21", historyIncluded.last().id)

        // Ensure System prompt has the nodes
        val sysMsg = pack.first { it.role == "system" }
        assertTrue(sysMsg.content?.contains("Buy milk") == true)

        // Ensure last message is the new user message
        assertEquals("What did I say?", pack.last().content)
    }
}
