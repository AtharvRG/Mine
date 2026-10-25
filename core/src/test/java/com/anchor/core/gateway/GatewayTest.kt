// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine Author
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.gateway

import com.anchor.core.models.*
import com.anchor.core.resolver.Resolver
import com.anchor.core.storage.Storage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayTest {

    // Simple fake storage for the unit test
    class FakeStorage(var nodes: List<Node>) : Storage {
        override val ioDispatcher: CoroutineDispatcher = Dispatchers.Unconfined

        val loggedEvents = mutableListOf<RawEvent>()
        val savedProposals = mutableListOf<Proposal>()

        override suspend fun insertRawMessage(id: String, content: String) {}
        override suspend fun getRawMessage(id: String): String? = null

        override suspend fun getActiveNodes(): List<Node> = nodes
        override suspend fun updateNodeStatus(nodeId: String, newStatus: String) {
            nodes = nodes.map { if (it.id == nodeId) it.copy(status = newStatus) else it }
        }
        override suspend fun logEvent(event: RawEvent) { loggedEvents.add(event) }
        override suspend fun saveProposal(proposal: Proposal) { savedProposals.add(proposal) }
    }

    @Test
    fun testGateway_ClearWinner_CommitsAndGeneratesUndo() = runBlocking {
        val fakeStorage = FakeStorage(listOf(
            Node("n1", "event", "Dinner with Raj", "active", 1000L * 60 * 60 * 24 * 1)
        ))
        val gateway = Gateway(fakeStorage, Resolver())

        // LLM calls: proposeChange(CANCEL, "tomorrow's dinner")
        val result = gateway.proposeChange(IntentType.CANCEL, "tomorrow's dinner", 1000L * 60 * 60 * 24 * 1)

        assertTrue(result is ToolResult.Committed)
        val committed = result as ToolResult.Committed

        assertEquals("n1", committed.targetId)
        assertTrue("Must provide an undo token", committed.undoToken.startsWith("u_"))

        // Ensure storage updated
        assertEquals("cancelled", fakeStorage.nodes[0].status)
        assertEquals(1, fakeStorage.loggedEvents.size)
        assertEquals(committed.undoToken, fakeStorage.loggedEvents[0].opId)
    }

    @Test
    fun testGateway_Ambiguous_GeneratesProposal() = runBlocking {
        val fakeStorage = FakeStorage(listOf(
            Node("n1", "event", "Dinner with Raj", "active", 1000L * 60 * 60 * 24 * 1),
            Node("n2", "event", "Dinner with Sam", "active", 1000L * 60 * 60 * 24 * 5)
        ))
        val gateway = Gateway(fakeStorage, Resolver())

        // LLM calls: proposeChange(CANCEL, "the dinner")
        val result = gateway.proposeChange(IntentType.CANCEL, "the dinner")

        assertTrue(result is ToolResult.Ambiguous)
        val ambiguous = result as ToolResult.Ambiguous

        assertTrue("Must provide a proposal alias", ambiguous.proposalId.startsWith("p_"))

        // Ensure storage saved the proposal but didn't alter nodes
        assertEquals(1, fakeStorage.savedProposals.size)
        assertEquals(0, fakeStorage.loggedEvents.size)
        assertEquals("active", fakeStorage.nodes[0].status)
    }
}