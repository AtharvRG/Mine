// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.gateway

import com.anchor.core.SharedFakeStorage
import com.anchor.core.models.Node
import com.anchor.core.models.RawEvent
import com.anchor.core.models.Reminder
import com.anchor.core.models.ToolResult
import com.anchor.core.resolver.Resolver
import com.anchor.core.time.DateResolver
import com.anchor.core.time.FakeClock
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Calendar

class ConfirmNegationTest {

    private lateinit var clock: FakeClock
    private lateinit var storage: SharedFakeStorage
    private lateinit var gateway: Gateway

    @Before
    fun setUp() {
        val cal = Calendar.getInstance().apply {
            set(2026, Calendar.OCTOBER, 28, 12, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        clock = FakeClock(cal.timeInMillis)
        storage = SharedFakeStorage()
        gateway = Gateway(storage, Resolver(), DateResolver(clock, is24HourFormat = { false }), clock)
    }

    private data class Chip(val proposalId: String, val choiceId: String, val title: String)

    /** Opens a CREATE time-chip proposal and returns the proposal, the 5 PM option, and the title. */
    private suspend fun openDinnerChip(title: String = "Dinner with Alice"): Chip {
        val call = ToolCall(
            "propose_change",
            mapOf(
                "intent" to JsonPrimitive("CREATE"),
                "title" to JsonPrimitive(title),
                "due_text" to JsonPrimitive("at 5")
            )
        )
        val result = gateway.executeTool(call)
        assertTrue("Expected NeedsTime, but was $result", result is ToolResult.NeedsTime)
        val proposalId = (result as ToolResult.NeedsTime).proposalId
        val fivePm = Calendar.getInstance().apply {
            set(2026, Calendar.OCTOBER, 28, 17, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val fivePmOption = storage.getProposal(proposalId)!!.options.find { it.id == fivePm.toString() }
        assertNotNull("5 PM option must exist", fivePmOption)
        return Chip(proposalId, fivePmOption!!.id, title)
    }

    @Test
    fun testNegatedConfirmation_DoesNotCommit() = runBlocking {
        // H-13 (R-15): token-level confirmation matched on *any* shared token, so "no, don't
        // set the 5 pm one" matched the chip's "5:00 PM" option through "pm" and the reminder was
        // created — the exact opposite of what the user said. A negation must never confirm,
        // whatever the overlap.
        val (proposalId, choiceId) = openDinnerChip()
        val confirm = ToolCall(
            "confirm_change",
            mapOf("proposal" to JsonPrimitive(proposalId), "choice" to JsonPrimitive(choiceId))
        )

        val result = gateway.executeTool(confirm, userMessage = "no, don't set the 5 pm one")

        assertTrue("A negated reply must not commit, but was $result", result is ToolResult.Error)
        assertEquals("open", storage.getProposal(proposalId)?.status)
        assertEquals("No reminder may be created", 0, storage.getPendingReminders().size)
        assertTrue("No node may be created", storage.getActiveNodes(limit = 100, cursor = null).isEmpty())
    }

    @Test
    fun testAffirmativeConfirmation_StillCommits() = runBlocking {
        // Pins that the negation guard does not swallow the normal path: the reply names the
        // chosen time and carries no negation, so it must still commit. (A time chip's option
        // title *is* the time label, so the reply must name the time, not the item.)
        val (proposalId, choiceId) = openDinnerChip()
        val confirm = ToolCall(
            "confirm_change",
            mapOf("proposal" to JsonPrimitive(proposalId), "choice" to JsonPrimitive(choiceId))
        )

        val result = gateway.executeTool(confirm, userMessage = "5 pm")

        assertTrue("An affirmative reply must commit, but was $result", result is ToolResult.Committed)
        assertEquals(1, storage.getPendingReminders().size)
    }

    @Test
    fun testNegationInTitle_DoesNotBlockConfirmation() = runBlocking {
        // The exemption in isNegated() exists for exactly one case: the reply *is* the option's
        // own title, and that title contains a negation word ("Not now review"). The user is
        // naming the item, not rejecting it. So the reply here must be the title itself — a reply
        // like "update existing" carries no negation token and would pass with or without the
        // exemption, which is what made an earlier version of this test prove nothing.
        //
        // The chip is opened through the dedupe path (a second CREATE of the same title), because
        // a CANCEL whose hint equals the node title is an exact match and auto-commits — no
        // proposal is ever created.
        val node = Node("n_title", "task", "Not now review", "active", dueAt = null)
        storage.createNodeAndCommit(node, RawEvent("u_t", "CREATE", "node", node.id), null)
        val call = ToolCall(
            "propose_change",
            mapOf("intent" to JsonPrimitive("CREATE"), "title" to JsonPrimitive("Not now review"))
        )
        val needsConfirm = gateway.executeTool(call)
        assertTrue("Expected NeedsConfirm, but was $needsConfirm", needsConfirm is ToolResult.NeedsConfirm)
        val chip = Chip((needsConfirm as ToolResult.NeedsConfirm).proposalId, node.id, node.title)
        val confirm = ToolCall(
            "confirm_change",
            mapOf("proposal" to JsonPrimitive(chip.proposalId), "choice" to JsonPrimitive(chip.choiceId))
        )

        val result = gateway.executeTool(confirm, userMessage = "Not now review")

        assertTrue("A reply that is the item's own title must commit even when the title holds 'not', but was $result", result is ToolResult.Committed)
        assertEquals(1, storage.getActiveNodes(limit = 100, cursor = null).size)
    }

    @Test
    fun testNegationInTitle_RejectedWhenReplyAddsNegation() = runBlocking {
        // The title exemption must be exact: "not now review" names the item, but
        // "no, not now review" rejects it. Without this, the exemption would be a hole wide
        // enough to swallow any reply that merely quotes a negatively-named item.
        val node = Node("n_title", "task", "Not now review", "active", dueAt = null)
        storage.createNodeAndCommit(node, RawEvent("u_t", "CREATE", "node", node.id), null)
        val call = ToolCall(
            "propose_change",
            mapOf("intent" to JsonPrimitive("CREATE"), "title" to JsonPrimitive("Not now review"))
        )
        val needsConfirm = gateway.executeTool(call)
        assertTrue("Expected NeedsConfirm, but was $needsConfirm", needsConfirm is ToolResult.NeedsConfirm)
        val chip = Chip((needsConfirm as ToolResult.NeedsConfirm).proposalId, node.id, node.title)
        val confirm = ToolCall(
            "confirm_change",
            mapOf("proposal" to JsonPrimitive(chip.proposalId), "choice" to JsonPrimitive(chip.choiceId))
        )

        val result = gateway.executeTool(confirm, userMessage = "no, not now review")

        assertTrue("A reply that quotes the title but adds a negation must not commit, but was $result", result is ToolResult.Error)
        assertEquals("open", storage.getProposal(chip.proposalId)?.status)
    }

    @Test
    fun testNegationOfOtherOption_DoesNotConfirmThisOne() = runBlocking {
        // "not the 5 pm one" names the option but rejects it. The model asking to confirm the
        // 5 PM option anyway must be refused.
        val (proposalId, choiceId) = openDinnerChip()
        val confirm = ToolCall(
            "confirm_change",
            mapOf("proposal" to JsonPrimitive(proposalId), "choice" to JsonPrimitive(choiceId))
        )

        val result = gateway.executeTool(confirm, userMessage = "not the 5 pm one")

        assertTrue("A rejected option must not commit, but was $result", result is ToolResult.Error)
        assertEquals(0, storage.getPendingReminders().size)
    }
}
