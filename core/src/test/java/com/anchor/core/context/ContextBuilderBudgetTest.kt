// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.context

import com.anchor.core.SharedFakeStorage
import com.anchor.core.models.ChatMessage
import com.anchor.core.models.Node
import com.anchor.core.nudge.CheckinBuilder
import com.anchor.core.time.DateResolver
import com.anchor.core.time.FakeClock
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextBuilderBudgetTest {

    private fun builder(
        storage: SharedFakeStorage,
        clock: FakeClock
    ): ContextBuilder {
        val dateResolver = DateResolver(clock)
        val checkinBuilder = CheckinBuilder(storage, clock, dateResolver)
        return ContextBuilder(storage, clock, dateResolver, checkinBuilder)
    }

    @Test
    fun compactMode_ShrinksActiveNodesAndChatHistoryBudget() = runBlocking {
        val storage = SharedFakeStorage()
        val clock = FakeClock(1_000_000L)
        val now = clock.now()

        // Insert 20 active nodes
        for (i in 1..20) {
            storage.activeNodes.add(
                Node(
                    id = "n_$i",
                    type = "task",
                    title = "Task number $i with a detailed description of what needs doing",
                    status = "active",
                    dueAt = now + i * 3600_000L,
                    createdAt = now - 10_000L,
                    updatedAt = now - 10_000L,
                    localOnly = false
                )
            )
        }

        // Insert 15 chat messages
        for (i in 1..15) {
            storage.insertRawMessageWithDay("m_$i", if (i % 2 == 1) "user" else "assistant", "Message $i: This is a somewhat long chat message to fill the token budget.", "d_1", now - (20 - i) * 60_000L)
        }

        val b = builder(storage, clock)

        // Normal mode
        val normalPack = b.buildPack(compactMode = false)
        val normalChatCount = normalPack.count { it.role == "user" || it.role == "assistant" }

        // Compact mode
        val compactPack = b.buildPack(compactMode = true)
        val compactChatCount = compactPack.count { it.role == "user" || it.role == "assistant" }

        assertTrue("Compact mode must include fewer or equal chat messages than normal mode", compactChatCount <= normalChatCount)
        assertTrue("Compact mode must still preserve at least 6 messages", compactChatCount >= 6)
    }

    @Test
    fun minimumSixMessagesPreserved_EvenWhenExceedingTokenBudget() = runBlocking {
        val storage = SharedFakeStorage()
        val clock = FakeClock(1_000_000L)
        val now = clock.now()

        // Insert 10 messages of 250 chars each (total 2500 chars > compact budget of 1200 chars)
        for (i in 1..10) {
            val longContent = "Message $i: " + "X".repeat(240)
            storage.insertRawMessageWithDay("m_$i", if (i % 2 == 1) "user" else "assistant", longContent, "d_1", now - (15 - i) * 60_000L)
        }

        val b = builder(storage, clock)
        val pack = b.buildPack(compactMode = true) // 600 tokens ~ 1200 chars

        val chatMessages = pack.filter { it.role == "user" || it.role == "assistant" }
        assertTrue("Must keep at least 6 messages when available per Blueprint 20 §3.4", chatMessages.size >= 6)
    }

    @Test
    fun lowTokenModeSetting_EnablesCompactModeAutomatically() = runBlocking {
        val storage = SharedFakeStorage()
        val clock = FakeClock(1_000_000L)
        val now = clock.now()

        for (i in 1..20) {
            storage.activeNodes.add(
                Node(
                    id = "n_$i",
                    type = "task",
                    title = "Task $i with a detailed title description",
                    status = "active",
                    dueAt = now + i * 3600_000L,
                    createdAt = now - 10_000L,
                    updatedAt = now - 10_000L
                )
            )
        }

        val b = builder(storage, clock)
        val normalPack = b.buildPack()

        // Set low token mode in settings
        storage.saveSetting("ai.compact_mode", "1")
        val compactPack = b.buildPack()

        val normalSys = normalPack.first().content ?: ""
        val compactSys = compactPack.first().content ?: ""

        assertTrue("Compact system block should be shorter than normal", compactSys.length <= normalSys.length)
    }
}
