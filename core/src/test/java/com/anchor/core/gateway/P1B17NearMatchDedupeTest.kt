// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.gateway

import com.anchor.core.SharedFakeStorage
import com.anchor.core.models.AlarmTier
import com.anchor.core.models.IntentType
import com.anchor.core.models.Node
import com.anchor.core.models.RawEvent
import com.anchor.core.models.Reminder
import com.anchor.core.models.ToolResult
import com.anchor.core.resolver.Resolver
import com.anchor.core.time.DateResolver
import com.anchor.core.time.FakeClock
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Calendar

class P1B17NearMatchDedupeTest {

    private lateinit var clock: FakeClock
    private lateinit var storage: SharedFakeStorage
    private lateinit var resolver: Resolver
    private lateinit var dateResolver: DateResolver
    private lateinit var gateway: Gateway

    private val baseTime = 1_760_000_000_000L // arbitrary fixed timestamp

    @Before
    fun setUp() {
        clock = FakeClock(baseTime)
        storage = SharedFakeStorage(clock)
        resolver = Resolver()
        dateResolver = DateResolver(clock, is24HourFormat = { false })
        gateway = Gateway(storage, resolver, dateResolver, clock)
    }

    @Test
    fun testBothUndated_ReturnsNeedsConfirm() = runBlocking {
        // Create initial undated item
        val create1 = ToolCall(
            "propose_change",
            mapOf("intent" to JsonPrimitive("CREATE"), "title" to JsonPrimitive("Buy groceries"))
        )
        val res1 = gateway.executeTool(create1)
        assertTrue("First create must succeed: $res1", res1 is ToolResult.Committed)

        // Attempt to create second undated item with same title
        val create2 = ToolCall(
            "propose_change",
            mapOf("intent" to JsonPrimitive("CREATE"), "title" to JsonPrimitive("Buy groceries"))
        )
        val res2 = gateway.executeTool(create2)
        assertTrue("Second create must return NeedsConfirm: $res2", res2 is ToolResult.NeedsConfirm)
        val needsConfirm = res2 as ToolResult.NeedsConfirm
        assertEquals(listOf("Update existing", "Add anyway", "Cancel"), needsConfirm.options)
        assertTrue("Message mentions similar item exists: ${needsConfirm.message}",
            needsConfirm.message.contains("Similar item already exists", ignoreCase = true) &&
            needsConfirm.message.contains("Buy groceries")
        )

        val proposal = storage.getProposal(needsConfirm.proposalId)
        assertNotNull("Proposal must be persisted", proposal)
        assertEquals("needs_confirm", proposal!!.kind)
        assertEquals(IntentType.CREATE, proposal.intent)
        assertEquals("open", proposal.status)
    }

    @Test
    fun testBothDated_Within2Hours_ReturnsNeedsConfirm() = runBlocking {
        val due1 = baseTime + 3600_000L // 1 hour from now
        val due2 = baseTime + (3600_000L + 1800_000L) // 1.5 hours from now (30 min difference <= 2h)

        val node1 = Node("n_dentist", "task", "Dentist appointment", "active", dueAt = due1, createdAt = baseTime, updatedAt = baseTime)
        storage.createNodeAndCommit(node1, RawEvent("u_init", "CREATE", "node", "n_dentist"), null)

        val call = ToolCall(
            "propose_change",
            mapOf(
                "intent" to JsonPrimitive("CREATE"),
                "title" to JsonPrimitive("Dentist appointment"),
                "due_text" to JsonPrimitive("in 90 minutes")
            )
        )
        val res = gateway.executeTool(call)
        assertTrue("Near-time match must return NeedsConfirm: $res", res is ToolResult.NeedsConfirm)
        val needsConfirm = res as ToolResult.NeedsConfirm
        assertEquals(listOf("Update existing", "Add anyway", "Cancel"), needsConfirm.options)
        assertTrue(needsConfirm.message.contains("Dentist appointment"))
    }

    @Test
    fun testBothDated_MoreThan2HoursApart_CreatesNewNodeImmediately() = runBlocking {
        val due1 = baseTime + 3600_000L // 1 hour from now
        val node1 = Node("n_gym", "task", "Gym workout", "active", dueAt = due1, createdAt = baseTime, updatedAt = baseTime)
        storage.createNodeAndCommit(node1, RawEvent("u_init", "CREATE", "node", "n_gym"), null)

        // Create gym workout 5 hours from now (4 hours apart > 2h window)
        val call = ToolCall(
            "propose_change",
            mapOf(
                "intent" to JsonPrimitive("CREATE"),
                "title" to JsonPrimitive("Gym workout"),
                "due_text" to JsonPrimitive("in 5 hours")
            )
        )
        val res = gateway.executeTool(call)
        assertTrue("Distinct times must commit without confirmation: $res", res is ToolResult.Committed)
        val committed = res as ToolResult.Committed
        assertEquals(2, storage.activeNodes.size)
        assertTrue("New node has separate id", committed.targetId != "n_gym")
    }

    @Test
    fun testExistingUndated_NewDated_ReturnsNeedsConfirm() = runBlocking {
        val node1 = Node("n_tax", "task", "File taxes", "active", dueAt = null, createdAt = baseTime, updatedAt = baseTime)
        storage.createNodeAndCommit(node1, RawEvent("u_init", "CREATE", "node", "n_tax"), null)

        val call = ToolCall(
            "propose_change",
            mapOf(
                "intent" to JsonPrimitive("CREATE"),
                "title" to JsonPrimitive("File taxes"),
                "due_text" to JsonPrimitive("in 2 hours")
            )
        )
        val res = gateway.executeTool(call)
        assertTrue("Existing undated + new dated must return NeedsConfirm: $res", res is ToolResult.NeedsConfirm)
        val needsConfirm = res as ToolResult.NeedsConfirm
        assertEquals(listOf("Update existing", "Add anyway", "Cancel"), needsConfirm.options)
    }

    @Test
    fun testExistingDated_NewUndated_ReturnsNeedsConfirm() = runBlocking {
        val node1 = Node("n_tax2", "task", "Pay rent", "active", dueAt = baseTime + 3600_000L, createdAt = baseTime, updatedAt = baseTime)
        storage.createNodeAndCommit(node1, RawEvent("u_init", "CREATE", "node", "n_tax2"), null)

        val call = ToolCall(
            "propose_change",
            mapOf(
                "intent" to JsonPrimitive("CREATE"),
                "title" to JsonPrimitive("Pay rent")
            )
        )
        val res = gateway.executeTool(call)
        assertTrue("Existing dated + new undated must return NeedsConfirm: $res", res is ToolResult.NeedsConfirm)
        val needsConfirm = res as ToolResult.NeedsConfirm
        assertEquals(listOf("Update existing", "Add anyway", "Cancel"), needsConfirm.options)
    }

    @Test
    fun testConfirmUpdateExisting_UpdatesNodeAndReminder() = runBlocking {
        val node1 = Node("n_call_dad", "task", "Call dad", "active", dueAt = null, createdAt = baseTime, updatedAt = baseTime)
        storage.createNodeAndCommit(node1, RawEvent("u_init", "CREATE", "node", "n_call_dad"), null)

        val targetDue = baseTime + 7200_000L
        val call = ToolCall(
            "propose_change",
            mapOf(
                "intent" to JsonPrimitive("CREATE"),
                "title" to JsonPrimitive("Call dad"),
                "due_text" to JsonPrimitive("in 2 hours")
            )
        )
        val res = gateway.executeTool(call) as ToolResult.NeedsConfirm

        // Confirm [Update existing] using duplicate node ID as choice
        val confirmCall = ToolCall(
            "confirm_change",
            mapOf("proposal" to JsonPrimitive(res.proposalId), "choice" to JsonPrimitive("n_call_dad"))
        )
        val confirmRes = gateway.executeTool(confirmCall)
        assertTrue("Confirm update must commit: $confirmRes", confirmRes is ToolResult.Committed)
        val committed = confirmRes as ToolResult.Committed
        assertEquals("n_call_dad", committed.targetId)

        // Verify node updated
        val updatedNode = storage.getNode("n_call_dad")!!
        assertNotNull(updatedNode.dueAt)
        assertEquals(1, storage.activeNodes.size)

        // Verify reminder created/scheduled
        val reminder = storage.getReminderByNodeId("n_call_dad")
        assertNotNull(reminder)
        assertEquals(updatedNode.dueAt, reminder!!.fireAtMillis)

        // Verify proposal consumed
        assertEquals("consumed", storage.getProposal(res.proposalId)?.status)
    }

    @Test
    fun testConfirmAddNew_CreatesDistinctNode() = runBlocking {
        val node1 = Node("n_walk_1", "task", "Walk dog", "active", dueAt = null, createdAt = baseTime, updatedAt = baseTime)
        storage.createNodeAndCommit(node1, RawEvent("u_init", "CREATE", "node", "n_walk_1"), null)

        val call = ToolCall(
            "propose_change",
            mapOf("intent" to JsonPrimitive("CREATE"), "title" to JsonPrimitive("Walk dog"))
        )
        val res = gateway.executeTool(call) as ToolResult.NeedsConfirm

        // Confirm [Add anyway]
        val confirmCall = ToolCall(
            "confirm_change",
            mapOf("proposal" to JsonPrimitive(res.proposalId), "choice" to JsonPrimitive("add_new"))
        )
        val confirmRes = gateway.executeTool(confirmCall)
        assertTrue("Confirm add_new must commit: $confirmRes", confirmRes is ToolResult.Committed)
        val committed = confirmRes as ToolResult.Committed
        assertTrue("New node must have distinct id", committed.targetId != "n_walk_1")
        assertEquals(2, storage.activeNodes.size)
        assertEquals("consumed", storage.getProposal(res.proposalId)?.status)
    }

    @Test
    fun testConfirmCancel_ConsumesProposalWithoutChanges() = runBlocking {
        val node1 = Node("n_mail_1", "task", "Check mail", "active", dueAt = null, createdAt = baseTime, updatedAt = baseTime)
        storage.createNodeAndCommit(node1, RawEvent("u_init", "CREATE", "node", "n_mail_1"), null)

        val call = ToolCall(
            "propose_change",
            mapOf("intent" to JsonPrimitive("CREATE"), "title" to JsonPrimitive("Check mail"))
        )
        val res = gateway.executeTool(call) as ToolResult.NeedsConfirm

        // Confirm [Cancel]
        val confirmCall = ToolCall(
            "confirm_change",
            mapOf("proposal" to JsonPrimitive(res.proposalId), "choice" to JsonPrimitive("cancel"))
        )
        val confirmRes = gateway.executeTool(confirmCall)
        assertTrue("Cancel must return committed cancel: $confirmRes", confirmRes is ToolResult.Committed)
        // H-05 (R-09): a cancel changes nothing, so there is no event to undo. An empty token made
        // the UI render an Undo button (it tests `!= null`) whose only possible outcome was
        // "Undo token not found".
        assertEquals("A cancel has nothing to undo", "", (confirmRes as ToolResult.Committed).undoToken)
        assertEquals(1, storage.activeNodes.size)
        assertEquals("consumed", storage.getProposal(res.proposalId)?.status)
    }

    @Test
    fun testUntitledAlarms_BypassDedupeCheck() = runBlocking {
        val call1 = ToolCall("propose_change", mapOf(
            "intent" to JsonPrimitive("CREATE"),
            "untitled" to JsonPrimitive("true"),
            "label" to JsonPrimitive("alarm"),
            "due_text" to JsonPrimitive("in 60 seconds")
        ))
        val res1 = gateway.executeTool(call1)
        assertTrue(res1 is ToolResult.Committed)

        // Second untitled alarm with same duration/label must commit directly without NeedsConfirm
        val call2 = ToolCall("propose_change", mapOf(
            "intent" to JsonPrimitive("CREATE"),
            "untitled" to JsonPrimitive("true"),
            "label" to JsonPrimitive("alarm"),
            "due_text" to JsonPrimitive("in 60 seconds")
        ))
        val res2 = gateway.executeTool(call2)
        assertTrue("Untitled alarm must bypass dedupe check: $res2", res2 is ToolResult.Committed)
        assertEquals(2, storage.activeNodes.size)
    }

    @Test
    fun testConcurrentConfirmNeedsConfirm_SingleWinnerCas() = runBlocking {
        val node1 = Node("n_rep_1", "task", "Review report", "active", dueAt = null, createdAt = baseTime, updatedAt = baseTime)
        storage.createNodeAndCommit(node1, RawEvent("u_init", "CREATE", "node", "n_rep_1"), null)

        val call = ToolCall(
            "propose_change",
            mapOf("intent" to JsonPrimitive("CREATE"), "title" to JsonPrimitive("Review report"))
        )
        val res = gateway.executeTool(call) as ToolResult.NeedsConfirm

        val confirmCall = ToolCall(
            "confirm_change",
            mapOf("proposal" to JsonPrimitive(res.proposalId), "choice" to JsonPrimitive("add_new"))
        )

        // Simulate two rapid concurrent clicks on [Add anyway]
        val def1 = async { gateway.executeTool(confirmCall) }
        val def2 = async { gateway.executeTool(confirmCall) }
        val results = listOf(def1.await(), def2.await())

        val committedCount = results.count { it is ToolResult.Committed }
        val resolvedErrorCount = results.count { it is ToolResult.Error && it.reason.contains("already resolved") }

        assertEquals("Exactly one caller must commit: $results", 1, committedCount)
        assertEquals("Losing caller must receive Proposal already resolved: $results", 1, resolvedErrorCount)
        assertEquals("Exactly 2 active nodes after one addition", 2, storage.activeNodes.size)
        assertEquals("consumed", storage.getProposal(res.proposalId)?.status)
    }
}
