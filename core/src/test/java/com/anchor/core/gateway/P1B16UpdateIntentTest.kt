// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.gateway

import com.anchor.core.SharedFakeStorage
import com.anchor.core.models.AlarmTier
import com.anchor.core.models.IntentType
import com.anchor.core.models.Node
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

class P1B16UpdateIntentTest {

    private val now = 1770000000000L
    private val clock = FakeClock(now)
    private val dateResolver = DateResolver(clock) { true }

    private fun gateway(storage: SharedFakeStorage) = Gateway(storage, Resolver(), dateResolver, clock)

    private var seedCounter = 0

    private suspend fun seedNode(
        storage: SharedFakeStorage,
        title: String,
        dueInHours: Long? = null,
        priority: Int = 0,
        severity: Int = 0,
        pinned: Boolean = false,
        tier: AlarmTier = AlarmTier.ALARM
    ): Node {
        val due = dueInHours?.let { now + it * 3600_000L }
        val node = Node(
            id = "n_${title.replace(Regex("\\W+"), "").lowercase().take(6)}${seedCounter++}",
            type = "task",
            title = title,
            status = "active",
            dueAt = due,
            createdAt = now,
            updatedAt = now,
            priority = priority,
            severity = severity,
            pinned = pinned
        )
        val reminder = due?.let { Reminder("r_${node.id}", title, it, node.id, tier = tier) }
        storage.createNodeAndCommit(
            node,
            com.anchor.core.models.RawEvent("e_${node.id}", "CREATE", "node", node.id, tsUtc = now),
            reminder
        )
        return node
    }

    private fun updateCall(vararg pairs: Pair<String, String>) = ToolCall(
        tool = "propose_change",
        args = mapOf(
            "intent" to JsonPrimitive("UPDATE"),
            *pairs.map { (k, v) -> k to JsonPrimitive(v) }.toTypedArray()
        )
    )

    @Test
    fun testRename_ClearWinner_CommitsAndSyncsReminderText() = runBlocking {
        val storage = SharedFakeStorage(clock)
        val node = seedNode(storage, "Buy milk", dueInHours = 5)
        val gw = gateway(storage)

        val result = gw.executeTool(updateCall("hint" to "buy milk", "new_title" to "Buy oat milk"))

        assertTrue("Expected Committed, got $result", result is ToolResult.Committed)
        val committed = result as ToolResult.Committed
        assertEquals(node.id, committed.targetId)

        val updated = storage.getNode(node.id)!!
        assertEquals("Buy oat milk", updated.title)
        assertEquals(now + 5 * 3600_000L, updated.dueAt)

        val rem = storage.getReminderByNodeId(node.id)
        assertNotNull(rem)
        assertEquals("Buy oat milk", rem!!.text)
        assertEquals(now + 5 * 3600_000L, rem.fireAtMillis)
    }

    @Test
    fun testUpdateDueTime_CommitsAndMovesReminder() = runBlocking {
        val storage = SharedFakeStorage(clock)
        val node = seedNode(storage, "Call doctor", dueInHours = 2)
        val gw = gateway(storage)

        val result = gw.executeTool(updateCall("hint" to "call doctor", "due_text" to "in 4 hours"))

        assertTrue("Expected Committed, got $result", result is ToolResult.Committed)

        val updated = storage.getNode(node.id)!!
        assertEquals(now + 4 * 3600_000L, updated.dueAt)
        val rem = storage.getReminderByNodeId(node.id)!!
        assertEquals(now + 4 * 3600_000L, rem.fireAtMillis)
    }

    @Test
    fun testUpdateFields_PrioritySeverityPinnedTier() = runBlocking {
        val storage = SharedFakeStorage(clock)
        val node = seedNode(storage, "Pay rent", dueInHours = 24, tier = AlarmTier.ALARM)
        val gw = gateway(storage)

        val result = gw.executeTool(
            updateCall(
                "hint" to "pay rent",
                "priority" to "2",
                "severity" to "3",
                "pinned" to "true",
                "tier" to "gentle"
            )
        )

        assertTrue("Expected Committed, got $result", result is ToolResult.Committed)
        val updated = storage.getNode(node.id)!!
        assertEquals(2, updated.priority)
        assertEquals(3, updated.severity)
        assertTrue(updated.pinned)
        assertEquals(AlarmTier.GENTLE, storage.getReminderByNodeId(node.id)!!.tier)
    }

    @Test
    fun testUpdateNoChanges_ReturnsError() = runBlocking {
        val storage = SharedFakeStorage(clock)
        seedNode(storage, "Buy milk", dueInHours = 5)
        val gw = gateway(storage)

        val result = gw.executeTool(updateCall("hint" to "buy milk"))

        assertTrue("Expected Error, got $result", result is ToolResult.Error)
    }

    @Test
    fun testUpdateMissingHint_ReturnsError() = runBlocking {
        val storage = SharedFakeStorage(clock)
        val gw = gateway(storage)

        val result = gw.executeTool(updateCall("new_title" to "Whatever"))

        assertTrue("Expected Error, got $result", result is ToolResult.Error)
    }

    @Test
    fun testUpdateUnknownTarget_ReturnsNotFound() = runBlocking {
        val storage = SharedFakeStorage(clock)
        val gw = gateway(storage)

        val result = gw.executeTool(updateCall("hint" to "nonexistent thing", "new_title" to "X"))

        assertTrue("Expected NotFound, got $result", result is ToolResult.NotFound)
    }

    @Test
    fun testUpdateAmbiguousTarget_ReturnsAmbiguousProposalThenConfirmAppliesUpdate() = runBlocking {
        val storage = SharedFakeStorage(clock)
        val a = seedNode(storage, "Dinner with Alice", dueInHours = 6)
        val b = seedNode(storage, "Dinner with Bob", dueInHours = 8)
        val gw = gateway(storage)

        val result = gw.executeTool(updateCall("hint" to "dinner", "new_title" to "Dinner with Alice and Carol"))

        assertTrue("Expected Ambiguous, got $result", result is ToolResult.Ambiguous)
        val ambiguous = result as ToolResult.Ambiguous

        val confirm = gw.executeTool(
            ToolCall(
                tool = "confirm_change",
                args = mapOf(
                    "proposal" to JsonPrimitive(ambiguous.proposalId),
                    "choice" to JsonPrimitive(b.id)
                )
            ),
            userMessage = "bob"
        )

        assertTrue("Expected Committed after confirm, got $confirm", confirm is ToolResult.Committed)
        val updated = storage.getNode(b.id)!!
        assertEquals("Dinner with Alice and Carol", updated.title)
        // Other node untouched
        assertEquals("Dinner with Alice", storage.getNode(a.id)!!.title)
    }

    @Test
    fun testUpdateAmbiguousTime_ReturnsNeedsTimeChipThenConfirmAppliesDue() = runBlocking {
        val storage = SharedFakeStorage(clock)
        val node = seedNode(storage, "Team sync", dueInHours = 3)
        val gw = gateway(storage)

        val result = gw.executeTool(updateCall("hint" to "team sync", "due_text" to "at 5"))

        assertTrue("Expected NeedsTime, got $result", result is ToolResult.NeedsTime)
        val needsTime = result as ToolResult.NeedsTime

        val proposal = storage.getOpenProposals().first { it.id == needsTime.proposalId }
        val choice = proposal.options.first().id
        assertTrue("Chip choice should be a millis value", choice.toLongOrNull() != null)

        val confirm = gw.executeTool(
            ToolCall(
                tool = "confirm_change",
                args = mapOf(
                    "proposal" to JsonPrimitive(needsTime.proposalId),
                    "choice" to JsonPrimitive(choice)
                )
            )
        )

        assertTrue("Expected Committed after time chip, got $confirm", confirm is ToolResult.Committed)
        val updated = storage.getNode(node.id)!!
        assertEquals(choice.toLong(), updated.dueAt)
        assertEquals(choice.toLong(), storage.getReminderByNodeId(node.id)!!.fireAtMillis)
    }

    @Test
    fun testUpdatePastTime_ReturnsError() = runBlocking {
        val storage = SharedFakeStorage(clock)
        seedNode(storage, "Old task", dueInHours = 1)
        val gw = gateway(storage)

        val result = gw.executeTool(updateCall("hint" to "old task", "due_text" to "yesterday at 9am"))

        assertTrue("Expected Error for past time, got $result", result is ToolResult.Error)
    }

    @Test
    fun testUpdateUndo_RestoresNodeAndReminder() = runBlocking {
        val storage = SharedFakeStorage(clock)
        val node = seedNode(storage, "Submit report", dueInHours = 10, priority = 1, severity = 1)
        val gw = gateway(storage)

        val result = gw.executeTool(
            updateCall(
                "hint" to "submit report",
                "new_title" to "Submit final report",
                "due_text" to "in 20 hours",
                "priority" to "3",
                "pinned" to "true"
            )
        )
        assertTrue(result is ToolResult.Committed)
        val undoToken = (result as ToolResult.Committed).undoToken

        // Sanity: change applied
        assertEquals("Submit final report", storage.getNode(node.id)!!.title)

        val undo = gw.executeTool(ToolCall(tool = "undo", args = mapOf("token" to JsonPrimitive(undoToken))))
        assertTrue("Expected Committed undo, got $undo", undo is ToolResult.Committed)

        val restored = storage.getNode(node.id)!!
        assertEquals("Submit report", restored.title)
        assertEquals(now + 10 * 3600_000L, restored.dueAt)
        assertEquals(1, restored.priority)
        assertEquals(1, restored.severity)
        assertEquals(false, restored.pinned)
        val rem = storage.getReminderByNodeId(node.id)!!
        assertEquals("Submit report", rem.text)
        assertEquals(now + 10 * 3600_000L, rem.fireAtMillis)
    }

    @Test
    fun testUpdateTierOnly_ChangesReminderTierKeepsEverythingElse() = runBlocking {
        val storage = SharedFakeStorage(clock)
        val node = seedNode(storage, "Evening walk", dueInHours = 7, tier = AlarmTier.ALARM)
        val gw = gateway(storage)

        val result = gw.executeTool(updateCall("hint" to "evening walk", "tier" to "gentle"))

        assertTrue("Expected Committed, got $result", result is ToolResult.Committed)
        val updated = storage.getNode(node.id)!!
        assertEquals("Evening walk", updated.title)
        assertEquals(now + 7 * 3600_000L, updated.dueAt)
        assertEquals(AlarmTier.GENTLE, storage.getReminderByNodeId(node.id)!!.tier)
    }

    @Test
    fun testUpdateUndatedNode_AddsDueAndCreatesReminder() = runBlocking {
        val storage = SharedFakeStorage(clock)
        val node = seedNode(storage, "Read book", dueInHours = null)
        val gw = gateway(storage)

        val result = gw.executeTool(updateCall("hint" to "read book", "due_text" to "in 6 hours"))

        assertTrue("Expected Committed, got $result", result is ToolResult.Committed)
        val updated = storage.getNode(node.id)!!
        assertEquals(now + 6 * 3600_000L, updated.dueAt)
        val rem = storage.getReminderByNodeId(node.id)
        assertNotNull("Reminder should be created for newly dated node", rem)
        assertEquals(now + 6 * 3600_000L, rem!!.fireAtMillis)
    }

    @Test
    fun testUpdateClosedNode_ReturnsNotFound() = runBlocking {
        val storage = SharedFakeStorage(clock)
        val node = seedNode(storage, "Done task", dueInHours = 1)
        storage.updateNodeAndCommit(
            node.copy(status = "done", closedAt = now),
            com.anchor.core.models.RawEvent("e_close", "COMPLETE", "node", node.id, tsUtc = now),
            deleteReminderId = "r_${node.id}",
            upsertReminder = null,
            consumeProposalId = null
        )
        val gw = gateway(storage)

        // Closed nodes are invisible to the active-node resolver, same as CANCEL/COMPLETE.
        val result = gw.executeTool(updateCall("hint" to "done task", "new_title" to "Whatever"))

        assertTrue("Expected NotFound for closed node, got $result", result is ToolResult.NotFound)
    }

    @Test
    fun testUpdate_ResolverStripsUpdateVerb() = runBlocking {
        val storage = SharedFakeStorage(clock)
        val node = seedNode(storage, "Water plants", dueInHours = 4)
        val gw = gateway(storage)

        // "update water plants" — the leading verb must not break the exact match
        val result = gw.executeTool(updateCall("hint" to "update water plants", "new_title" to "Water the plants"))

        assertTrue("Expected Committed, got $result", result is ToolResult.Committed)
        assertEquals("Water the plants", storage.getNode(node.id)!!.title)
    }

    @Test
    fun testUpdateTierOnly_UndatedNode_ReturnsErrorNoMutation() = runBlocking {
        val storage = SharedFakeStorage(clock)
        val node = seedNode(storage, "Read book", dueInHours = null)
        val eventsBefore = storage.loggedEvents.size
        val gw = gateway(storage)

        val result = gw.executeTool(updateCall("hint" to "read book", "tier" to "gentle"))

        // Tier lives on the reminder; an undated node has none, so this must not report Committed.
        assertTrue("Expected Error for tier-only UPDATE on undated node, got $result", result is ToolResult.Error)
        val err = result as ToolResult.Error
        assertTrue(
            "Error should explain the missing due date: ${err.reason}",
            err.reason.contains("no due date", ignoreCase = true)
        )
        assertEquals("Node must be untouched", 0, storage.getNode(node.id)!!.priority)
        assertEquals("Node title must be untouched", "Read book", storage.getNode(node.id)!!.title)
        assertEquals("No reminder may be created", null, storage.getReminderByNodeId(node.id))
        assertEquals("No event may be logged", eventsBefore, storage.loggedEvents.size)
    }

    @Test
    fun testUpdateTierOnly_UndatedNode_ViaAmbiguousConfirm_ConsumesProposalAndErrors() = runBlocking {
        val storage = SharedFakeStorage(clock)
        val a = seedNode(storage, "Read book one", dueInHours = null)
        val b = seedNode(storage, "Read book two", dueInHours = null)
        val gw = gateway(storage)

        val ambiguous = gw.executeTool(updateCall("hint" to "read book", "tier" to "gentle"))
        assertTrue("Expected Ambiguous between the two books, got $ambiguous", ambiguous is ToolResult.Ambiguous)
        val proposalId = (ambiguous as ToolResult.Ambiguous).proposalId

        val confirm = gw.executeTool(
            ToolCall(
                tool = "confirm_change",
                args = mapOf(
                    "proposal" to JsonPrimitive(proposalId),
                    "choice" to JsonPrimitive(b.id)
                )
            ),
            userMessage = "two"
        )

        assertTrue("Expected Error after confirm, got $confirm", confirm is ToolResult.Error)
        assertTrue((confirm as ToolResult.Error).reason.contains("no due date", ignoreCase = true))
        // The failed confirm must consume the proposal so no dead chip remains.
        assertEquals("consumed", storage.getProposal(proposalId)!!.status)
        assertEquals("Read book two", storage.getNode(b.id)!!.title)
        assertEquals("Read book one", storage.getNode(a.id)!!.title)
    }

    @Test
    fun testUpdateBlankNewDueText_FallsBackToDueText() = runBlocking {
        val storage = SharedFakeStorage(clock)
        val node = seedNode(storage, "Call doctor", dueInHours = 2)
        val gw = gateway(storage)

        // A blank new_due_text must not silently drop a populated due_text.
        val result = gw.executeTool(updateCall("hint" to "call doctor", "new_due_text" to "   ", "due_text" to "in 4 hours"))

        assertTrue("Expected Committed, got $result", result is ToolResult.Committed)
        assertEquals(now + 4 * 3600_000L, storage.getNode(node.id)!!.dueAt)
        assertEquals(now + 4 * 3600_000L, storage.getReminderByNodeId(node.id)!!.fireAtMillis)
    }
}
