// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.gateway

import com.anchor.core.SharedFakeStorage
import com.anchor.core.context.ContextBuilder
import com.anchor.core.models.ChatMessage
import com.anchor.core.models.Node
import com.anchor.core.models.ToolResult
import com.anchor.core.nudge.CheckinBuilder
import com.anchor.core.resolver.Resolver
import com.anchor.core.time.DateResolver
import com.anchor.core.time.FakeClock
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * H2: nodes marked local_only must never surface their titles to the model.
 *
 * The leak path is the proposal channel, not the Active Items block: the Gateway builds its
 * resolver candidate pool from getActiveNodes, which does not filter local_only, and the
 * Ambiguous branch writes node.title into ProposalOption.title. ContextBuilder then emits those
 * titles under "Pending Clarifications", so a private title reaches the prompt.
 */
class H2LocalOnlyLeakTest {

    private val now = 1770000000000L
    private val clock = FakeClock(now)
    private val dateResolver = DateResolver(clock) { true }
    private val privateTitle = "Therapy appointment with Dr Rao"

    // Two private items sharing a word: "appointment" alone is ambiguous between them, which is
    // exactly the branch that writes node.title into a ProposalOption.
    private fun storageWithPrivateNode(): SharedFakeStorage = SharedFakeStorage(clock).apply {
        activeNodes = mutableListOf(
            Node("n_priv", "task", privateTitle, "active", dueAt = now + 3600_000L,
                createdAt = now, updatedAt = now, localOnly = true),
            Node("n_priv2", "task", "Dentist appointment", "active", dueAt = now + 7200_000L,
                createdAt = now, updatedAt = now, localOnly = true)
        )
    }

    private fun packText(storage: SharedFakeStorage): String = runBlocking {
        val builder = ContextBuilder(storage, clock, dateResolver, CheckinBuilder(storage, clock, dateResolver))
        builder.buildPack().joinToString("\n") { it.content ?: "" }
    }

    @Test
    fun testH2_LocalOnlyTitle_NeverEntersContext_ViaAmbiguousProposal() = runBlocking {
        val storage = storageWithPrivateNode()
        val gateway = Gateway(storage, Resolver(), dateResolver, clock)

        // "appointment" matches the private node's title but not exactly, so the resolver would
        // produce an Ambiguous resolution over it if it were a candidate at all.
        val call = ToolCall(
            tool = "propose_change",
            args = mapOf(
                "intent" to JsonPrimitive("CANCEL"),
                "hint" to JsonPrimitive("appointment")
            )
        )
        gateway.executeTool(call)

        val context = packText(storage)
        assertFalse("local_only title must never reach the model prompt", context.contains(privateTitle))
        assertFalse("local_only title fragments must not leak either", context.contains("Therapy"))
    }

    @Test
    fun testH2_LocalOnlyNode_IsNotMatchedByResolver() = runBlocking {
        val storage = storageWithPrivateNode()
        val gateway = Gateway(storage, Resolver(), dateResolver, clock)

        val call = ToolCall(
            tool = "propose_change",
            args = mapOf(
                "intent" to JsonPrimitive("CANCEL"),
                "hint" to JsonPrimitive("appointment")
            )
        )
        val result = gateway.executeTool(call)

        // A local-only item is invisible to chat: no chip, no title echo, nothing to confirm.
        assertTrue("Private item must not be offered as a choice, got $result",
            result is ToolResult.NotFound)
        assertTrue("Private node must remain untouched", storage.getNode("n_priv")?.status == "active")
        assertTrue("No proposal may be created for a private item", storage.getOpenProposals().isEmpty())
    }

    @Test
    fun testH2_NonLocalOnlyNode_StillResolvable() = runBlocking {
        // Guard: the filter must exclude only local_only items, not everything.
        val storage = SharedFakeStorage(clock).apply {
            activeNodes = mutableListOf(
                Node("n_pub", "task", "Buy milk", "active", dueAt = now + 3600_000L,
                    createdAt = now, updatedAt = now)
            )
        }
        val gateway = Gateway(storage, Resolver(), dateResolver, clock)

        val call = ToolCall(
            tool = "propose_change",
            args = mapOf(
                "intent" to JsonPrimitive("CANCEL"),
                "hint" to JsonPrimitive("Buy milk")
            )
        )
        val result = gateway.executeTool(call)

        assertTrue("Ordinary node must still be cancellable, got $result", result is ToolResult.Committed)
        assertTrue("Ordinary node must be cancelled", storage.getNode("n_pub")?.status == "cancelled")
    }
}
