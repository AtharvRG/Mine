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
    fun testGateway_ScheduleReminder_CreatesAndUndoes() = kotlinx.coroutines.runBlocking {
        val fakeStorage = SharedFakeStorage().apply {
            activeNodes = mutableListOf(Node("n1", "task", "Buy milk", "active"))
        }
        val gateway = Gateway(fakeStorage, Resolver(), DateResolver(clock), clock)

        val scheduleCall = ToolCall("schedule_reminder", mapOf("target_ref" to "n1", "when_text" to "tomorrow at 5pm"))
        val result = gateway.executeTool(scheduleCall)

        assertTrue(result is ToolResult.Committed)
        val commit = result as ToolResult.Committed
        assertEquals("r_n1", commit.targetId)
        
        assertEquals(1, fakeStorage.reminders.size)
        assertEquals("Buy milk", fakeStorage.reminders[0].text)

        val undoCall = ToolCall("undo", mapOf("token" to commit.undoToken))
        val undoResult = gateway.executeTool(undoCall)
        
        assertTrue(undoResult is ToolResult.Committed)
        assertEquals(0, fakeStorage.reminders.size)
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

    @Test
    fun testGateway_SearchMemory_And_SoulPropose() = kotlinx.coroutines.runBlocking {
        val fakeStorage = com.anchor.core.SharedFakeStorage().apply {
            activeNodes = mutableListOf(com.anchor.core.models.Node("n1", "task", "Buy apples", "active"))
            soulFacts = mutableListOf(com.anchor.core.models.SoulFact("s1", "diet", "Hates bananas", 0.9, "chat", "extracted", "active", 100))
        }
        val gateway = Gateway(fakeStorage, com.anchor.core.resolver.Resolver(), com.anchor.core.time.DateResolver(com.anchor.core.time.SystemClock()), com.anchor.core.time.SystemClock())

        // 1. Search Memory
        val searchCall = com.anchor.core.gateway.ToolCall("search_memory", mapOf("query" to "apples"))
        val searchResult = gateway.executeTool(searchCall)
        
        org.junit.Assert.assertTrue(searchResult is com.anchor.core.models.ToolResult.Data)
        val dataResult = searchResult as com.anchor.core.models.ToolResult.Data
        org.junit.Assert.assertTrue(dataResult.text.contains("Buy apples"))

        // 2. Soul Propose
        val proposeCall = com.anchor.core.gateway.ToolCall("soul_propose", mapOf("statement" to "Likes oranges", "category" to "diet"))
        val proposeResult = gateway.executeTool(proposeCall)
        
        org.junit.Assert.assertTrue(proposeResult is com.anchor.core.models.ToolResult.Committed)
        org.junit.Assert.assertEquals(2, fakeStorage.soulFacts.size)
        org.junit.Assert.assertEquals("Likes oranges", fakeStorage.soulFacts[1].statement)
    }
}
