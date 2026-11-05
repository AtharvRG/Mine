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
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class R6GentleTierTest {

    private val now = 1770000000000L
    private val clock = FakeClock(now)
    private val dateResolver = DateResolver(clock) { true }

    @Test
    fun testCreateGentle_StoresGentleReminder() = runBlocking {
        val storage = SharedFakeStorage(clock)
        val gateway = Gateway(storage, Resolver(), dateResolver, clock)

        val call = ToolCall(
            tool = "propose_change",
            args = mapOf(
                "intent" to JsonPrimitive("CREATE"),
                "title" to JsonPrimitive("Gentle Meditation"),
                "due_text" to JsonPrimitive("in 2 hours"),
                "tier" to JsonPrimitive("gentle")
            )
        )

        val result = gateway.executeTool(call)
        assertTrue("Expected Committed result, got $result", result is ToolResult.Committed)

        val reminders = storage.getPendingReminders()
        assertEquals(1, reminders.size)
        assertEquals("Gentle Meditation", reminders[0].text)
        assertEquals(AlarmTier.GENTLE, reminders[0].tier)
    }

    @Test
    fun testCreateUnknownTier_DefaultsToAlarmWithoutCrash() = runBlocking {
        val storage = SharedFakeStorage(clock)
        val gateway = Gateway(storage, Resolver(), dateResolver, clock)

        val call = ToolCall(
            tool = "propose_change",
            args = mapOf(
                "intent" to JsonPrimitive("CREATE"),
                "title" to JsonPrimitive("Unknown Tier Task"),
                "due_text" to JsonPrimitive("in 1 hour"),
                "tier" to JsonPrimitive("super_whisper")
            )
        )

        val result = gateway.executeTool(call)
        assertTrue("Expected Committed result, got $result", result is ToolResult.Committed)

        val reminders = storage.getPendingReminders()
        assertEquals(1, reminders.size)
        assertEquals(AlarmTier.ALARM, reminders[0].tier)
    }

    @Test
    fun testReschedule_Direct_PreservesGentleTier() = runBlocking {
        val storage = SharedFakeStorage(clock)
        val gateway = Gateway(storage, Resolver(), dateResolver, clock)

        // Pre-create node with gentle reminder
        val node = Node("n_gentle", "task", "Gentle Reading", "active", dueAt = now + 3600_000L)
        val reminder = Reminder("r_gentle", "Gentle Reading", now + 3600_000L, nodeId = "n_gentle", tier = AlarmTier.GENTLE)
        storage.createNodeAndCommit(node, RawEvent("e1", "CREATE", "node", "n_gentle", tsUtc = now), reminder)

        val call = ToolCall(
            tool = "propose_change",
            args = mapOf(
                "intent" to JsonPrimitive("RESCHEDULE"),
                "hint" to JsonPrimitive("Gentle Reading"),
                "due_text" to JsonPrimitive("in 5 hours")
            )
        )

        val result = gateway.executeTool(call)
        assertTrue("Expected Committed result, got $result", result is ToolResult.Committed)

        val rem = storage.getReminderByNodeId("n_gentle")
        assertNotNull(rem)
        assertEquals(AlarmTier.GENTLE, rem!!.tier)
        assertEquals(now + 5 * 3600_000L, rem.fireAtMillis)
    }

    @Test
    fun testReschedule_ConfirmChip_PreservesGentleTier() = runBlocking {
        val storage = SharedFakeStorage(clock)
        val gateway = Gateway(storage, Resolver(), dateResolver, clock)

        // Two nodes matching "stretch"
        val node1 = Node("n_g1", "task", "Morning stretch", "active", dueAt = now + 3600_000L)
        val rem1 = Reminder("r_g1", "Morning stretch", now + 3600_000L, nodeId = "n_g1", tier = AlarmTier.GENTLE)
        storage.createNodeAndCommit(node1, RawEvent("e1", "CREATE", "node", "n_g1", tsUtc = now), rem1)

        val node2 = Node("n_g2", "task", "Evening stretch", "active", dueAt = now + 7200_000L)
        val rem2 = Reminder("r_g2", "Evening stretch", now + 7200_000L, nodeId = "n_g2", tier = AlarmTier.ALARM)
        storage.createNodeAndCommit(node2, RawEvent("e2", "CREATE", "node", "n_g2", tsUtc = now), rem2)

        // Reschedule "stretch" -> Ambiguous
        val call = ToolCall(
            tool = "propose_change",
            args = mapOf(
                "intent" to JsonPrimitive("RESCHEDULE"),
                "hint" to JsonPrimitive("stretch"),
                "due_text" to JsonPrimitive("in 6 hours")
            )
        )

        val result = gateway.executeTool(call)
        assertTrue("Expected Ambiguous result, got $result", result is ToolResult.Ambiguous)
        val proposalId = (result as ToolResult.Ambiguous).proposalId

        // Confirm choice for n_g1 (the gentle one)
        val confirmCall = ToolCall(
            tool = "confirm_change",
            args = mapOf(
                "proposal" to JsonPrimitive(proposalId),
                "choice" to JsonPrimitive("n_g1")
            )
        )

        val confirmResult = gateway.executeTool(confirmCall)
        assertTrue("Expected Committed confirm, got $confirmResult", confirmResult is ToolResult.Committed)

        val remAfter = storage.getReminderByNodeId("n_g1")
        assertNotNull(remAfter)
        assertEquals(AlarmTier.GENTLE, remAfter!!.tier)
        assertEquals(now + 6 * 3600_000L, remAfter.fireAtMillis)
    }

    @Test
    fun testTimeChip_CreateGentle_CarriesTierAndStoresGentle() = runBlocking {
        val storage = SharedFakeStorage(clock)
        val gateway = Gateway(storage, Resolver(), dateResolver, clock)

        // "at 5" is ambiguous (AM vs PM) -> NeedsTime
        val call = ToolCall(
            tool = "propose_change",
            args = mapOf(
                "intent" to JsonPrimitive("CREATE"),
                "title" to JsonPrimitive("Quiet Yoga"),
                "due_text" to JsonPrimitive("at 5"),
                "tier" to JsonPrimitive("gentle")
            )
        )

        val result = gateway.executeTool(call)
        assertTrue("Expected NeedsTime result, got $result", result is ToolResult.NeedsTime)
        val proposalId = (result as ToolResult.NeedsTime).proposalId

        val proposal = storage.getProposal(proposalId)
        assertNotNull(proposal)
        val chosenOption = proposal!!.options.first().id // epoch millis as string

        val confirmCall = ToolCall(
            tool = "confirm_change",
            args = mapOf(
                "proposal" to JsonPrimitive(proposalId),
                "choice" to JsonPrimitive(chosenOption)
            )
        )

        val confirmResult = gateway.executeTool(confirmCall)
        assertTrue("Expected Committed confirm, got $confirmResult", confirmResult is ToolResult.Committed)

        val pending = storage.getPendingReminders()
        assertEquals(1, pending.size)
        assertEquals("Quiet Yoga", pending[0].text)
        assertEquals(AlarmTier.GENTLE, pending[0].tier)
    }

    @Test
    fun testUndo_RestoresGentleTier() = runBlocking {
        val storage = SharedFakeStorage(clock)
        val gateway = Gateway(storage, Resolver(), dateResolver, clock)

        // Pre-create node with gentle reminder
        val node = Node("n_undo_g", "task", "Gentle Tea", "active", dueAt = now + 1800_000L)
        val reminder = Reminder("r_undo_g", "Gentle Tea", now + 1800_000L, nodeId = "n_undo_g", tier = AlarmTier.GENTLE)
        storage.createNodeAndCommit(node, RawEvent("e_pre", "CREATE", "node", "n_undo_g", tsUtc = now), reminder)

        // Reschedule it
        val call = ToolCall(
            tool = "propose_change",
            args = mapOf(
                "intent" to JsonPrimitive("RESCHEDULE"),
                "hint" to JsonPrimitive("Gentle Tea"),
                "due_text" to JsonPrimitive("in 3 hours")
            )
        )

        val result = gateway.executeTool(call) as ToolResult.Committed
        val undoToken = result.undoToken
        assertNotNull(undoToken)

        // Undo the reschedule
        val undoCall = ToolCall(
            tool = "undo",
            args = mapOf("token" to JsonPrimitive(undoToken!!))
        )
        val undoResult = gateway.executeTool(undoCall)
        assertTrue("Expected Committed undo, got $undoResult", undoResult is ToolResult.Committed)

        val restoredRem = storage.getReminderByNodeId("n_undo_g")
        assertNotNull(restoredRem)
        assertEquals(AlarmTier.GENTLE, restoredRem!!.tier)
        assertEquals(now + 1800_000L, restoredRem.fireAtMillis)
    }
}
