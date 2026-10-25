// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine Author
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.gateway

import com.anchor.core.SharedFakeStorage
import com.anchor.core.models.*
import com.anchor.core.resolver.Resolver
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayTest {

    @Test
    fun testGateway_ClearWinner_CommitsAndGeneratesUndo() = runBlocking {
        val fakeStorage = SharedFakeStorage().apply {
            activeNodes = mutableListOf(
                Node("n1", "event", "Dinner with Raj", "active", 1000L * 60 * 60 * 24 * 1)
            )
        }
        val gateway = Gateway(fakeStorage, Resolver())

        // LLM calls: proposeChange(CANCEL, "tomorrow's dinner")
        val result = gateway.proposeChange(IntentType.CANCEL, "tomorrow's dinner", 1000L * 60 * 60 * 24 * 1)

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
        val gateway = Gateway(fakeStorage, Resolver())

        // LLM calls: proposeChange(CANCEL, "the dinner")
        val result = gateway.proposeChange(IntentType.CANCEL, "the dinner")

        assertTrue(result is ToolResult.Ambiguous)
        val ambiguous = result as ToolResult.Ambiguous

        assertTrue("Must provide a proposal alias", ambiguous.proposalId.startsWith("p_"))

        // Ensure storage saved the proposal but didn't alter nodes
        assertEquals(1, fakeStorage.savedProposals.size)
        assertEquals(0, fakeStorage.loggedEvents.size)
        assertEquals("active", fakeStorage.activeNodes[0].status)
    }
}
