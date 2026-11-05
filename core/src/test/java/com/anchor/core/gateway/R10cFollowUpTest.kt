// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.gateway

import com.anchor.core.SharedFakeStorage
import com.anchor.core.models.*
import com.anchor.core.resolver.Resolver
import com.anchor.core.time.DateResolver
import com.anchor.core.time.FakeClock
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class R10cFollowUpTest {

    private val baseTime = 1_700_000_000_000L
    private val clock = FakeClock(baseTime)
    private val dateResolver = DateResolver(clock)

    @Test
    fun testCreateFollowUp_FailsIfSourceNodeNotFound_CreatesNothing() = runBlocking {
        val storage = SharedFakeStorage()
        val gateway = Gateway(storage, Resolver(), dateResolver, clock)

        val result = gateway.executeTool(
            ToolCall(
                tool = "propose_change",
                args = mapOf(
                    "intent" to JsonPrimitive("CREATE"),
                    "title" to JsonPrimitive("Subtask"),
                    "due_text" to JsonPrimitive("in 1 hour"),
                    "follow_up_to" to JsonPrimitive("n_missing")
                )
            )
        )

        assertTrue("Expected ToolResult.Error when source node does not exist, got: $result", result is ToolResult.Error)
        val error = result as ToolResult.Error
        assertTrue("Error message should mention source item not found: ${error.reason}",
            error.reason.contains("source", ignoreCase = true) || error.reason.contains("not found", ignoreCase = true))

        assertEquals("No node should be created", 0, storage.activeNodes.size)
        assertEquals("No reminder should be created", 0, storage.reminders.size)
        assertEquals("No edge should be created", 0, storage.edges.size)
    }

    @Test
    fun testCreateFollowUp_CreatesNodeReminderEdgeInSingleTransaction() = runBlocking {
        val storage = SharedFakeStorage()
        val gateway = Gateway(storage, Resolver(), dateResolver, clock)

        val parent = Node(
            id = "n_parent",
            type = "task",
            title = "Parent Task",
            status = "active",
            dueAt = baseTime + 3600_000L,
            createdAt = baseTime,
            updatedAt = baseTime
        )
        storage.activeNodes.add(parent)

        val result = gateway.executeTool(
            ToolCall(
                tool = "propose_change",
                args = mapOf(
                    "intent" to JsonPrimitive("CREATE"),
                    "title" to JsonPrimitive("Child Follow-Up Task"),
                    "due_text" to JsonPrimitive("in 2 hours"),
                    "follow_up_to" to JsonPrimitive(parent.id)
                )
            )
        )

        assertTrue("Expected Committed, got: $result", result is ToolResult.Committed)
        val committed = result as ToolResult.Committed

        assertEquals("Parent and child nodes must exist", 2, storage.activeNodes.size)
        val child = storage.activeNodes.find { it.id == committed.targetId }
        assertNotNull("Child node should exist", child)

        assertEquals("Reminder for child should exist", 1, storage.reminders.size)
        assertEquals(child!!.id, storage.reminders[0].nodeId)

        val edges = storage.getEdgesForSource(parent.id, EdgeTypes.FOLLOW_UP)
        assertEquals("Follow-up edge from parent to child must exist", 1, edges.size)
        assertEquals(child.id, edges[0].dstId)
        assertEquals(EdgeTypes.FOLLOW_UP, edges[0].type)
    }

    @Test
    fun testUndoCreate_SoftDeletesFollowUpEdge() = runBlocking {
        val storage = SharedFakeStorage()
        val gateway = Gateway(storage, Resolver(), dateResolver, clock)

        val parent = Node(
            id = "n_parent",
            type = "task",
            title = "Parent Task",
            status = "active",
            dueAt = baseTime + 3600_000L,
            createdAt = baseTime,
            updatedAt = baseTime
        )
        storage.activeNodes.add(parent)

        val createResult = gateway.executeTool(
            ToolCall(
                tool = "propose_change",
                args = mapOf(
                    "intent" to JsonPrimitive("CREATE"),
                    "title" to JsonPrimitive("Child Follow-Up Task"),
                    "due_text" to JsonPrimitive("in 2 hours"),
                    "follow_up_to" to JsonPrimitive(parent.id)
                )
            )
        ) as ToolResult.Committed

        val edgesBefore = storage.getEdgesForSource(parent.id, EdgeTypes.FOLLOW_UP)
        assertEquals(1, edgesBefore.size)
        val edgeId = edgesBefore[0].id

        // Undo the creation
        val undoResult = gateway.executeTool(
            ToolCall(
                tool = "undo",
                args = mapOf("token" to JsonPrimitive(createResult.undoToken))
            )
        )

        assertTrue("Expected undo Committed, got: $undoResult", undoResult is ToolResult.Committed)

        // Node is cancelled
        val child = storage.activeNodes.find { it.id == createResult.targetId }
        assertNotNull(child)
        assertEquals("cancelled", child!!.status)

        // Reminder is deleted
        assertEquals(0, storage.reminders.size)

        // Edge must be soft-deleted: getEdgesForSource excludes soft-deleted edges
        val activeEdges = storage.getEdgesForSource(parent.id, EdgeTypes.FOLLOW_UP)
        assertTrue("Follow-up edge must be soft-deleted upon undo of CREATE", activeEdges.isEmpty())

        // In raw storage, edge has deletedAt set
        val rawEdge = storage.edges.find { it.id == edgeId }
        assertNotNull(rawEdge)
        assertNotNull("Raw edge must have deletedAt timestamp set", rawEdge!!.deletedAt)
    }

    @Test
    fun testAddFollowUp_RejectsMissingSourceOrDestination() = runBlocking {
        val storage = SharedFakeStorage()
        val gateway = Gateway(storage, Resolver(), dateResolver, clock)

        val nodeA = Node("n_a", "task", "Task A", "active", baseTime, createdAt = baseTime, updatedAt = baseTime)
        storage.activeNodes.add(nodeA)

        val res1 = gateway.handleAddFollowUp("n_missing", nodeA.id)
        assertTrue("Should reject missing source", res1 is ToolResult.Error)

        val res2 = gateway.handleAddFollowUp(nodeA.id, "n_missing")
        assertTrue("Should reject missing destination", res2 is ToolResult.Error)

        assertEquals("No edges should be created", 0, storage.edges.size)
    }

    @Test
    fun testAddFollowUp_RejectsSelfLoop() = runBlocking {
        val storage = SharedFakeStorage()
        val gateway = Gateway(storage, Resolver(), dateResolver, clock)

        val nodeA = Node("n_a", "task", "Task A", "active", baseTime, createdAt = baseTime, updatedAt = baseTime)
        storage.activeNodes.add(nodeA)

        val res = gateway.handleAddFollowUp(nodeA.id, nodeA.id)
        assertTrue("Should reject self-loop", res is ToolResult.Error)
        val error = res as ToolResult.Error
        assertTrue(error.reason.contains("itself", ignoreCase = true) || error.reason.contains("self", ignoreCase = true))

        assertEquals("No edges should be created", 0, storage.edges.size)
    }

    @Test
    fun testAddFollowUp_RejectsDuplicateEdge() = runBlocking {
        val storage = SharedFakeStorage()
        val gateway = Gateway(storage, Resolver(), dateResolver, clock)

        val nodeA = Node("n_a", "task", "Task A", "active", baseTime, createdAt = baseTime, updatedAt = baseTime)
        val nodeB = Node("n_b", "task", "Task B", "active", baseTime, createdAt = baseTime, updatedAt = baseTime)
        storage.activeNodes.add(nodeA)
        storage.activeNodes.add(nodeB)

        val res1 = gateway.handleAddFollowUp(nodeA.id, nodeB.id)
        assertTrue("First link should succeed", res1 is ToolResult.Committed)
        assertEquals(1, storage.edges.size)

        val res2 = gateway.handleAddFollowUp(nodeA.id, nodeB.id)
        assertTrue("Duplicate link should be rejected", res2 is ToolResult.Error)
        val error = res2 as ToolResult.Error
        assertTrue(error.reason.contains("already exists", ignoreCase = true) || error.reason.contains("duplicate", ignoreCase = true))

        assertEquals("Edges count should remain 1", 1, storage.edges.size)
    }

    @Test
    fun testAddFollowUp_RejectsCyclicDependency_DepthBounded() = runBlocking {
        val storage = SharedFakeStorage()
        val gateway = Gateway(storage, Resolver(), dateResolver, clock)

        val nodeA = Node("n_a", "task", "Task A", "active", baseTime, createdAt = baseTime, updatedAt = baseTime)
        val nodeB = Node("n_b", "task", "Task B", "active", baseTime, createdAt = baseTime, updatedAt = baseTime)
        val nodeC = Node("n_c", "task", "Task C", "active", baseTime, createdAt = baseTime, updatedAt = baseTime)
        storage.activeNodes.addAll(listOf(nodeA, nodeB, nodeC))

        // A -> B
        val res1 = gateway.handleAddFollowUp(nodeA.id, nodeB.id)
        assertTrue(res1 is ToolResult.Committed)

        // B -> C
        val res2 = gateway.handleAddFollowUp(nodeB.id, nodeC.id)
        assertTrue(res2 is ToolResult.Committed)

        // Attempt C -> A (would create cycle: A -> B -> C -> A)
        val resCycle = gateway.handleAddFollowUp(nodeC.id, nodeA.id)
        assertTrue("Should reject cycle", resCycle is ToolResult.Error)
        val error = resCycle as ToolResult.Error
        assertTrue(error.reason.contains("cyclic", ignoreCase = true) || error.reason.contains("cycle", ignoreCase = true))

        assertEquals("Cycle edge C -> A must not be persisted", 2, storage.edges.size)
    }

    @Test
    fun testRemoveFollowUp_ProducesUndoableReceipt_UndoRestoresEdge() = runBlocking {
        val storage = SharedFakeStorage()
        val gateway = Gateway(storage, Resolver(), dateResolver, clock)

        val nodeA = Node("n_a", "task", "Task A", "active", baseTime, createdAt = baseTime, updatedAt = baseTime)
        val nodeB = Node("n_b", "task", "Task B", "active", baseTime, createdAt = baseTime, updatedAt = baseTime)
        storage.activeNodes.addAll(listOf(nodeA, nodeB))

        val addResult = gateway.handleAddFollowUp(nodeA.id, nodeB.id) as ToolResult.Committed
        val edgeId = addResult.targetId

        val removeResult = gateway.handleRemoveFollowUp(edgeId)
        assertTrue("Remove should succeed", removeResult is ToolResult.Committed)
        val removeCommitted = removeResult as ToolResult.Committed

        // Edge is soft-deleted
        assertTrue("Active edges should be empty", storage.getEdgesForSource(nodeA.id).isEmpty())
        assertNotNull(storage.edges.find { it.id == edgeId }?.deletedAt)

        // Undo the removal
        val undoResult = gateway.executeTool(
            ToolCall(
                tool = "undo",
                args = mapOf("token" to JsonPrimitive(removeCommitted.undoToken))
            )
        )
        assertTrue("Undo remove should succeed", undoResult is ToolResult.Committed)

        // Edge should be restored and active
        val restoredEdges = storage.getEdgesForSource(nodeA.id)
        assertEquals(1, restoredEdges.size)
        assertEquals(edgeId, restoredEdges[0].id)
        assertNull("deletedAt should be cleared upon undo", restoredEdges[0].deletedAt)
    }
}
