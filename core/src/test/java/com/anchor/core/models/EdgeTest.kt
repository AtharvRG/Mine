// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.models

import com.anchor.core.SharedFakeStorage
import com.anchor.core.gateway.Gateway
import com.anchor.core.gateway.ToolCall
import com.anchor.core.resolver.Resolver
import com.anchor.core.time.Clock
import com.anchor.core.time.DateResolver
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EdgeTest {

    private class FakeClock(private var time: Long) : Clock {
        override fun now(): Long = time
    }

    @Test
    fun testEdgeModel_SerializationAndDefaults() {
        val edge = Edge(
            id = "e_12345",
            srcId = "n_task1",
            dstId = "n_task2",
            type = EdgeTypes.FOLLOW_UP,
            createdAt = 1000L,
            updatedAt = 1000L
        )

        assertEquals("follow_up", edge.type)
        assertEquals("node", edge.dstKind)
        assertEquals(1.0, edge.weight, 0.001)
        assertNull(edge.deletedAt)

        val json = Json.encodeToString(edge)
        val decoded = Json.decodeFromString<Edge>(json)
        assertEquals(edge, decoded)
    }

    @Test
    fun testStorage_EdgeCrudAndQuerying() = runBlocking {
        val storage = SharedFakeStorage()
        val edge1 = Edge("e_1", "node_A", "node_B", EdgeTypes.FOLLOW_UP, createdAt = 100L)
        val edge2 = Edge("e_2", "node_A", "node_C", EdgeTypes.FOLLOW_UP, createdAt = 200L)
        val edge3 = Edge("e_3", "node_D", "node_A", EdgeTypes.FOLLOW_UP, createdAt = 300L)

        storage.saveEdge(edge1)
        storage.saveEdge(edge2)
        storage.saveEdge(edge3)

        assertEquals(edge1, storage.getEdge("e_1"))

        val srcEdges = storage.getEdgesForSource("node_A", EdgeTypes.FOLLOW_UP)
        assertEquals(2, srcEdges.size)
        assertTrue(srcEdges.any { it.dstId == "node_B" })
        assertTrue(srcEdges.any { it.dstId == "node_C" })

        val dstEdges = storage.getEdgesForDestination("node_A", EdgeTypes.FOLLOW_UP)
        assertEquals(1, dstEdges.size)
        assertEquals("node_D", dstEdges[0].srcId)

        // Soft delete edge1
        storage.deleteEdge("e_1")
        val srcEdgesAfterDelete = storage.getEdgesForSource("node_A", EdgeTypes.FOLLOW_UP)
        assertEquals(1, srcEdgesAfterDelete.size)
        assertEquals("node_C", srcEdgesAfterDelete[0].dstId)
    }

    @Test
    fun testGateway_CreateTaskWithFollowUp() = runBlocking {
        val storage = SharedFakeStorage()
        val clock = FakeClock(1000L)
        val dateResolver = DateResolver(clock) { true }
        val gateway = Gateway(storage, Resolver(), dateResolver, clock)

        // First, create the root task
        val rootNode = Node("n_root", "task", "Prepare Presentation", "active", createdAt = 500L, updatedAt = 500L)
        val event1 = RawEvent("u_root", "CREATE", "node", "n_root", tsUtc = 500L)
        storage.createNodeAndCommit(rootNode, event1, null)

        // Propose follow-up task
        val call = ToolCall(
            "propose_change",
            mapOf(
                "intent" to JsonPrimitive("CREATE"),
                "title" to JsonPrimitive("Send Slides to Client"),
                "follow_up_to" to JsonPrimitive("n_root")
            )
        )

        val result = gateway.executeTool(call)
        assertTrue(result is ToolResult.Committed)
        val followUpNodeId = (result as ToolResult.Committed).targetId

        // Verify the follow-up edge was committed
        val followUps = storage.getEdgesForSource("n_root", EdgeTypes.FOLLOW_UP)
        assertEquals(1, followUps.size)
        assertEquals(followUpNodeId, followUps[0].dstId)
    }
}
