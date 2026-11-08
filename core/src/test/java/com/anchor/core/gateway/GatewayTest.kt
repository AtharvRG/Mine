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
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        val toolCall = ToolCall("propose_change", mapOf("intent" to JsonPrimitive("CANCEL"), "hint" to JsonPrimitive("tomorrow's dinner")))
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
        val toolCall = ToolCall("propose_change", mapOf("intent" to JsonPrimitive("CANCEL"), "hint" to JsonPrimitive("the dinner")))
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

        val completeCall = ToolCall("propose_change", mapOf("intent" to JsonPrimitive("COMPLETE"), "hint" to JsonPrimitive("Buy milk")))
        val completeResult = gateway.executeTool(completeCall) as ToolResult.Committed

        assertEquals("done", fakeStorage.activeNodes[0].status)

        // Undo complete
        val undoCall = ToolCall("undo", mapOf("token" to JsonPrimitive(completeResult.undoToken)))
        val undoResult = gateway.executeTool(undoCall)

        assertTrue(undoResult is ToolResult.Committed)
        assertEquals("active", fakeStorage.activeNodes[0].status)
    }

    @Test
    fun testGateway_Create_RejectsBlankTitle() = runBlocking {
        val fakeStorage = SharedFakeStorage()
        val gateway = Gateway(fakeStorage, Resolver(), dateResolver, clock)

        val createCall = ToolCall("propose_change", mapOf("intent" to JsonPrimitive("CREATE"), "hint" to JsonPrimitive("   ")))
        val result = gateway.executeTool(createCall)

        assertTrue(result is ToolResult.Error)
        assertEquals("Missing title: ask the user what it is for.", (result as ToolResult.Error).reason)
    }

    @Test
    fun testGateway_Create_And_UndoCreate() = runBlocking {
        val fakeStorage = SharedFakeStorage()
        val gateway = Gateway(fakeStorage, Resolver(), dateResolver, clock)

        val createCall = ToolCall("propose_change", mapOf("intent" to JsonPrimitive("CREATE"), "hint" to JsonPrimitive("Buy groceries")))
        val createResult = gateway.executeTool(createCall) as ToolResult.Committed

        assertEquals(1, fakeStorage.activeNodes.size)
        assertEquals("Buy groceries", fakeStorage.activeNodes[0].title)

        val undoCall = ToolCall("undo", mapOf("token" to JsonPrimitive(createResult.undoToken)))
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

        val scheduleCall = ToolCall("schedule_reminder", mapOf("target_ref" to JsonPrimitive("n1"), "when_text" to JsonPrimitive("tomorrow at 5pm")))
        val result = gateway.executeTool(scheduleCall)

        assertTrue(result is ToolResult.Committed)
        val commit = result as ToolResult.Committed
        assertTrue(commit.targetId.startsWith("rem_"))
        
        assertEquals(1, fakeStorage.reminders.size)
        assertEquals("Buy milk", fakeStorage.reminders[0].text)

        val undoCall = ToolCall("undo", mapOf("token" to JsonPrimitive(commit.undoToken)))
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
        val searchCall = com.anchor.core.gateway.ToolCall("search_memory", mapOf("query" to JsonPrimitive("apples")))
        val searchResult = gateway.executeTool(searchCall)
        
        org.junit.Assert.assertTrue(searchResult is com.anchor.core.models.ToolResult.Data)
        val dataResult = searchResult as com.anchor.core.models.ToolResult.Data
        org.junit.Assert.assertTrue(dataResult.text.contains("Buy apples"))

        // 2. Soul Propose
        val proposeCall = com.anchor.core.gateway.ToolCall("soul_propose", mapOf("statement" to JsonPrimitive("Likes oranges"), "category" to JsonPrimitive("diet")))
        val proposeResult = gateway.executeTool(proposeCall)
        
        org.junit.Assert.assertTrue(proposeResult is com.anchor.core.models.ToolResult.Committed)
        org.junit.Assert.assertEquals(2, fakeStorage.soulFacts.size)
        org.junit.Assert.assertEquals("Likes oranges", fakeStorage.soulFacts[1].statement)
    }

    // Blueprint 23 §5: `search_history` returns the original messages, newest first, in the
    // `m4 | Tue 14 Oct 21:10 | U: ...` line shape, with limit defaulting to 5 and capped at 10.
    // Blueprint 23 §2.2: the leading token is a per-turn alias, never the real id.
    @Test
    fun testGateway_SearchHistory_ReturnsMessagesNewestFirst_InBlueprintFormat() = runBlocking {
        val storage = SharedFakeStorage()
        val gateway = Gateway(storage, Resolver(), dateResolver, clock)
        val t0 = 1_700_000_000_000L
        storage.insertRawMessageWithDay("m1", "user", "I'll start the report tomorrow evening", "2026-11-01", t0)
        storage.insertRawMessageWithDay("m2", "assistant", "Want a reminder at 18:00?", "2026-11-01", t0 + 1000)
        storage.insertRawMessageWithDay("m3", "user", "nothing about the report", "2026-11-02", t0 + 2000)

        val result = gateway.executeTool(ToolCall("search_history", mapOf("query" to JsonPrimitive("report"))))

        assertTrue(result is ToolResult.Data)
        val lines = (result as ToolResult.Data).text.lines()
        assertEquals(2, lines.size)
        // Newest first: m3 (t0+2000) before m1 (t0).
        assertTrue(lines[0].startsWith("m_"))
        assertTrue(lines[0].endsWith(" | U: nothing about the report"))
        assertTrue(lines[1].startsWith("m_"))
        assertTrue(lines[1].endsWith(" | U: I'll start the report tomorrow evening"))
        // The leading token is an issued alias, not the real id echoed back.
        val alias0 = lines[0].substringBefore(" | ")
        val alias1 = lines[1].substringBefore(" | ")
        assertFalse(alias0 == "m3")
        assertFalse(alias1 == "m1")
    }

    // Blueprint 23 §2.2/§4: an alias the gateway never issued is rejected with a readable
    // reason, not silently treated as a real id.
    @Test
    fun testGateway_ScheduleReminder_UnknownAlias_IsUnknownRef() = runBlocking {
        val storage = SharedFakeStorage().apply {
            activeNodes = mutableListOf(Node("n1", "task", "Buy milk", "active"))
        }
        val gateway = Gateway(storage, Resolver(), dateResolver, clock)

        val result = gateway.executeTool(
            ToolCall("schedule_reminder", mapOf("target_ref" to JsonPrimitive("n_neverissued"), "when_text" to JsonPrimitive("tomorrow at 5pm")))
        )

        assertTrue(result is ToolResult.Error)
        assertTrue((result as ToolResult.Error).reason.startsWith("unknown_ref"))
        assertTrue((result as ToolResult.Error).reason.contains("n_neverissued"))
        assertEquals(0, storage.reminders.size)
    }

    @Test
    fun testGateway_ConfirmChange_UnknownAlias_IsUnknownRef() = runBlocking {
        val storage = SharedFakeStorage()
        val gateway = Gateway(storage, Resolver(), dateResolver, clock)

        val result = gateway.executeTool(
            ToolCall("confirm_change", mapOf("proposal" to JsonPrimitive("p_neverissued"), "choice" to JsonPrimitive("1")))
        )

        assertTrue(result is ToolResult.Error)
        assertTrue((result as ToolResult.Error).reason.startsWith("unknown_ref"))
        assertTrue((result as ToolResult.Error).reason.contains("p_neverissued"))
    }

    @Test
    fun testGateway_SearchHistory_BlankQuery_IsAnError() = runBlocking {
        val gateway = Gateway(SharedFakeStorage(), Resolver(), dateResolver, clock)
        val result = gateway.executeTool(ToolCall("search_history", mapOf("query" to JsonPrimitive("  "))))
        assertTrue(result is ToolResult.Error)
    }

    @Test
    fun testGateway_SearchHistory_LimitDefaultsTo5_AndClampsTo10() = runBlocking {
        val storage = SharedFakeStorage()
        val gateway = Gateway(storage, Resolver(), dateResolver, clock)
        val t0 = 1_700_000_000_000L
        repeat(12) { i ->
            storage.insertRawMessageWithDay("m$i", "user", "report number $i", "2026-11-01", t0 + i)
        }

        val defaulted = gateway.executeTool(ToolCall("search_history", mapOf("query" to JsonPrimitive("report"))))
        assertEquals(5, (defaulted as ToolResult.Data).text.lines().size)

        val capped = gateway.executeTool(ToolCall("search_history", mapOf("query" to JsonPrimitive("report"), "limit" to JsonPrimitive("50"))))
        assertEquals(10, (capped as ToolResult.Data).text.lines().size)

        val explicit = gateway.executeTool(ToolCall("search_history", mapOf("query" to JsonPrimitive("report"), "limit" to JsonPrimitive("3"))))
        assertEquals(3, (explicit as ToolResult.Data).text.lines().size)
    }

    @Test
    fun testGateway_Reschedule_UpdatesDueAt_And_Reminder() = kotlinx.coroutines.runBlocking {
        val storage = com.anchor.core.SharedFakeStorage()
        val clock = com.anchor.core.time.FakeClock(1000L)
        val resolver = com.anchor.core.resolver.Resolver()
        val dateResolver = com.anchor.core.time.DateResolver(clock)
        val gateway = Gateway(storage, resolver, dateResolver, clock)
        
        val node = com.anchor.core.models.Node("n1", "event", "Dinner", "active", 5000L)
        storage.activeNodes.add(node)
        storage.reminders.add(com.anchor.core.models.Reminder("r_n1", "Dinner", 5000L, nodeId = "n1"))

        val rescheduleCall = com.anchor.core.gateway.ToolCall("propose_change", mapOf("intent" to JsonPrimitive("RESCHEDULE"), "hint" to JsonPrimitive("Dinner"), "due_text" to JsonPrimitive("in 20 minutes")))
        
        val result = gateway.executeTool(rescheduleCall)
        org.junit.Assert.assertTrue(result is com.anchor.core.models.ToolResult.Committed)
        
        // Assert Node time changed
        org.junit.Assert.assertEquals(1000L + (20 * 60000L), storage.activeNodes[0].dueAt)
        
        // Assert Reminder was NOT deleted, but updated to new time
        org.junit.Assert.assertEquals(1, storage.reminders.size)
        org.junit.Assert.assertEquals(1000L + (20 * 60000L), storage.reminders[0].fireAtMillis)
    }

    @Test
    fun testGateway_PastTime_ReturnsHelpfulError() = kotlinx.coroutines.runBlocking {
        val storage = com.anchor.core.SharedFakeStorage()
        val cal = java.util.Calendar.getInstance().apply { set(2026, java.util.Calendar.OCTOBER, 25, 12, 0, 0); set(java.util.Calendar.MILLISECOND, 0) }
        val clock = com.anchor.core.time.FakeClock(cal.timeInMillis)
        val resolver = com.anchor.core.resolver.Resolver()
        val dateResolver = com.anchor.core.time.DateResolver(clock)
        val gateway = Gateway(storage, resolver, dateResolver, clock)

        val node = com.anchor.core.models.Node("n1", "event", "Dentist", "active", cal.timeInMillis + 3600_000L)
        storage.activeNodes.add(node)

        // 1. CREATE with past time
        val createCall = com.anchor.core.gateway.ToolCall("propose_change", mapOf("intent" to JsonPrimitive("CREATE"), "hint" to JsonPrimitive("Dentist appointment"), "due_text" to JsonPrimitive("yesterday")))
        val createResult = gateway.executeTool(createCall)
        org.junit.Assert.assertTrue(createResult is com.anchor.core.models.ToolResult.Error)
        org.junit.Assert.assertEquals("That time is in the past; tell me a future time.", (createResult as com.anchor.core.models.ToolResult.Error).reason)

        // 2. RESCHEDULE with past time
        val rescheduleCall = com.anchor.core.gateway.ToolCall("propose_change", mapOf("intent" to JsonPrimitive("RESCHEDULE"), "hint" to JsonPrimitive("Dentist"), "due_text" to JsonPrimitive("last friday")))
        val rescheduleResult = gateway.executeTool(rescheduleCall)
        org.junit.Assert.assertTrue(rescheduleResult is com.anchor.core.models.ToolResult.Error)
        org.junit.Assert.assertEquals("That time has already passed; tell me the new time again", (rescheduleResult as com.anchor.core.models.ToolResult.Error).reason)

        // 3. schedule_reminder with past time
        val remCall = com.anchor.core.gateway.ToolCall("schedule_reminder", mapOf("target_ref" to JsonPrimitive("n1"), "when" to JsonPrimitive("yesterday")))
        val remResult = gateway.executeTool(remCall)
        org.junit.Assert.assertTrue(remResult is com.anchor.core.models.ToolResult.Error)
        org.junit.Assert.assertEquals("That time is in the past; tell me a future time.", (remResult as com.anchor.core.models.ToolResult.Error).reason)
    }
}
