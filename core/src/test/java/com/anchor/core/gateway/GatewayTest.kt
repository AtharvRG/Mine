// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.gateway

import com.anchor.core.SharedFakeStorage
import com.anchor.core.models.*
import com.anchor.core.resolver.Resolver
import com.anchor.core.time.DateResolver
import com.anchor.core.time.FakeClock
import com.anchor.core.time.SystemClock
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayTest {

    private val clock = SystemClock()
    private val dateResolver = DateResolver(clock)

    @Test
    fun testGateway_ClearWinner_CommitsAndGeneratesUndo() = runBlocking {
        val fakeStorage = SharedFakeStorage().apply {
            activeNodes = mutableListOf(
                Node("n1", "event", "Dinner with Raj", "active", 1000L * 60 * 60 * 24 * 1)
            )
        }
        val gateway = Gateway(fakeStorage, Resolver(), dateResolver, clock)

        // LLM calls: proposeChange(CANCEL, "tomorrow's dinner")
        val toolCall = ToolCall("propose_change", mapOf("intent" to "CANCEL", "hint" to "tomorrow's dinner"))
        val result = gateway.executeTool(toolCall)

        assertTrue(result is ToolResult.Committed)
        val committed = result as ToolResult.Committed

        assertEquals("n1", committed.targetId)
        assertTrue("Must provide an undo token", committed.undoToken.startsWith("u_"))

        // Ensure storage updated
        assertEquals("cancelled", fakeStorage.activeNodes[0].status)
        assertEquals(1, fakeStorage.loggedEvents.size)
        assertEquals(committed.undoToken, fakeStorage.loggedEvents[0].opId)
    }

    @Test
    fun testGateway_Ambiguous_GeneratesProposal() = runBlocking {
        val fakeStorage = SharedFakeStorage().apply {
            activeNodes = mutableListOf(
                Node("n1", "event", "Dinner with Raj", "active", 1000L * 60 * 60 * 24 * 1),
                Node("n2", "event", "Dinner with Sam", "active", 1000L * 60 * 60 * 24 * 5)
            )
        }
        val gateway = Gateway(fakeStorage, Resolver(), dateResolver, clock)

        // LLM calls: proposeChange(CANCEL, "the dinner")
        val toolCall = ToolCall("propose_change", mapOf("intent" to "CANCEL", "hint" to "the dinner"))
        val result = gateway.executeTool(toolCall)

        assertTrue(result is ToolResult.Ambiguous)
        val ambiguous = result as ToolResult.Ambiguous

        assertTrue("Must provide a proposal alias", ambiguous.proposalId.startsWith("p_"))

        // Ensure storage saved the proposal but didn't alter nodes
        assertEquals(1, fakeStorage.savedProposals.size)
        assertEquals(0, fakeStorage.loggedEvents.size)
        assertEquals("active", fakeStorage.activeNodes[0].status)
    }

    @Test
    fun testGateway_Undo_RestoresState() = runBlocking {
        val fakeStorage = SharedFakeStorage().apply {
            activeNodes = mutableListOf(
                Node("n1", "task", "Buy milk", "active")
            )
        }
        val gateway = Gateway(fakeStorage, Resolver(), dateResolver, clock)

        val completeCall = ToolCall("propose_change", mapOf("intent" to "COMPLETE", "hint" to "Buy milk"))
        val completeResult = gateway.executeTool(completeCall) as ToolResult.Committed

        assertEquals("done", fakeStorage.activeNodes[0].status)

        // Undo complete
        val undoCall = ToolCall("undo", mapOf("token" to completeResult.undoToken))
        val undoResult = gateway.executeTool(undoCall)

        assertTrue(undoResult is ToolResult.Committed)
        assertEquals("active", fakeStorage.activeNodes[0].status)
    }

    @Test
    fun testGateway_Create_RejectsBlankTitle() = runBlocking {
        val fakeStorage = SharedFakeStorage()
        val gateway = Gateway(fakeStorage, Resolver(), dateResolver, clock)

        val createCall = ToolCall("propose_change", mapOf("intent" to "CREATE", "hint" to "   "))
        val result = gateway.executeTool(createCall)

        assertTrue(result is ToolResult.Error)
        assertEquals("Title cannot be blank", (result as ToolResult.Error).reason)
    }

    @Test
    fun testGateway_Create_And_UndoCreate() = runBlocking {
        val fakeStorage = SharedFakeStorage()
        val gateway = Gateway(fakeStorage, Resolver(), dateResolver, clock)

        val createCall = ToolCall("propose_change", mapOf("intent" to "CREATE", "hint" to "Buy groceries"))
        val createResult = gateway.executeTool(createCall) as ToolResult.Committed

        assertEquals(1, fakeStorage.activeNodes.size)
        assertEquals("Buy groceries", fakeStorage.activeNodes[0].title)

        val undoCall = ToolCall("undo", mapOf("token" to createResult.undoToken))
        val undoResult = gateway.executeTool(undoCall)

        assertTrue(undoResult is ToolResult.Committed)
        assertEquals("cancelled", fakeStorage.activeNodes[0].status)

        // Duplicate undo check
        val duplicateUndoResult = gateway.executeTool(undoCall)
        assertTrue(duplicateUndoResult is ToolResult.Error)
        assertEquals("Already undone", (duplicateUndoResult as ToolResult.Error).reason)
    }

    @Test
    fun testDateResolver_AmPmEnforcement() {
        val fakeClock = FakeClock(1700000000000L)
        val resolver = DateResolver(fakeClock)

        assertNull("Missing am/pm should return null", resolver.parse("at 5"))
        assertNull("Missing am/pm should return null", resolver.parse("tomorrow at 5"))
        assertNotNull("With am/pm should parse", resolver.parse("at 5pm"))
        assertNotNull("With am/pm should parse", resolver.parse("tomorrow at 5am"))
    }
}
