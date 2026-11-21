// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.gateway

import com.anchor.core.SharedFakeStorage
import com.anchor.core.models.IntentType
import com.anchor.core.models.Node
import com.anchor.core.models.ToolResult
import com.anchor.core.resolver.Resolver
import com.anchor.core.time.DateResolver
import com.anchor.core.time.FakeClock
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class StrikethroughDeadlineTest {

    private lateinit var clock: FakeClock
    private lateinit var storage: SharedFakeStorage
    private lateinit var dateResolver: DateResolver
    private lateinit var gateway: Gateway
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        clock = FakeClock(1700000000000L) // Anchor
        storage = SharedFakeStorage(clock)
        dateResolver = DateResolver(clock, { false })
        gateway = Gateway(storage, Resolver(), dateResolver, clock)
    }

    @Test
    fun testReschedule_TypoCorrectionWithin10MinutesOfCreation_NoSlip() = runBlocking {
        val creationTime = clock.now()
        val originalDue = creationTime + 5 * 3600_000L // 5 hours later

        // Create task
        val task = Node(
            id = "n_typo_task",
            type = "task",
            title = "Doctor appointment",
            status = "active",
            dueAt = originalDue,
            createdAt = creationTime,
            updatedAt = creationTime,
            originalDueAt = originalDue,
            slipCount = 0
        )
        storage.saveNode(task)

        // 3 minutes after creation, user changes due time to 8 hours later
        clock.currentTime = creationTime + 3 * 60_000L
        val correctedDue = creationTime + 8 * 3600_000L

        val result = gateway.executeTool(
            ToolCall(
                tool = "propose_change",
                args = mapOf(
                    "intent" to JsonPrimitive(IntentType.UPDATE.name),
                    "target_ref" to JsonPrimitive("n_typo_task"),
                    "due_at" to JsonPrimitive(correctedDue.toString())
                )
            ),
            executedCalls = mutableSetOf()
        )

        assertTrue(result is ToolResult.Committed)
        val updated = storage.getNode("n_typo_task")
        assertNotNull(updated)
        assertEquals("Typo correction within 10m must have slipCount = 0", 0, updated!!.slipCount)
        assertEquals("DueAt updated to corrected time", correctedDue, updated.dueAt)
        assertEquals("OriginalDueAt updated to corrected time so no strikethrough", correctedDue, updated.originalDueAt)
    }

    @Test
    fun testReschedule_EarlierDeadline_NoSlip() = runBlocking {
        val creationTime = clock.now() - 24 * 3600_000L // 1 day ago
        val originalDue = clock.now() + 10 * 3600_000L // 10 hours from now

        val task = Node(
            id = "n_early_task",
            type = "task",
            title = "File taxes",
            status = "active",
            dueAt = originalDue,
            createdAt = creationTime,
            updatedAt = creationTime,
            originalDueAt = originalDue,
            slipCount = 0
        )
        storage.saveNode(task)

        // Reschedule to 4 hours from now (moving deadline earlier!)
        val earlierDue = clock.now() + 4 * 3600_000L
        val result = gateway.executeTool(
            ToolCall(
                tool = "propose_change",
                args = mapOf(
                    "intent" to JsonPrimitive(IntentType.UPDATE.name),
                    "target_ref" to JsonPrimitive("n_early_task"),
                    "due_at" to JsonPrimitive(earlierDue.toString())
                )
            ),
            executedCalls = mutableSetOf()
        )

        assertTrue(result is ToolResult.Committed)
        val updated = storage.getNode("n_early_task")
        assertNotNull(updated)
        assertEquals("Moving deadline earlier must NOT slip", 0, updated!!.slipCount)
        assertEquals(earlierDue, updated.dueAt)
        assertEquals(originalDue, updated.originalDueAt)
    }

    @Test
    fun testReschedule_GraceAdjustmentWithin10MinutesForward_NoSlip() = runBlocking {
        val creationTime = clock.now() - 24 * 3600_000L // 1 day ago
        val currentDue = clock.now() + 2 * 3600_000L

        val task = Node(
            id = "n_grace_task",
            type = "task",
            title = "Quick standup",
            status = "active",
            dueAt = currentDue,
            createdAt = creationTime,
            updatedAt = creationTime,
            originalDueAt = currentDue,
            slipCount = 0
        )
        storage.saveNode(task)

        // Move deadline forward by 8 minutes (grace window)
        val adjustedDue = currentDue + 8 * 60_000L
        val result = gateway.executeTool(
            ToolCall(
                tool = "propose_change",
                args = mapOf(
                    "intent" to JsonPrimitive(IntentType.UPDATE.name),
                    "target_ref" to JsonPrimitive("n_grace_task"),
                    "due_at" to JsonPrimitive(adjustedDue.toString())
                )
            ),
            executedCalls = mutableSetOf()
        )

        assertTrue(result is ToolResult.Committed)
        val updated = storage.getNode("n_grace_task")
        assertNotNull(updated)
        assertEquals("Moving deadline forward by <= 10m is grace: slipCount = 0", 0, updated!!.slipCount)
        assertEquals(adjustedDue, updated.dueAt)
    }

    @Test
    fun testReschedule_PastGraceForward_IncrementsSlipCountAndRecordsHistory() = runBlocking {
        val creationTime = clock.now() - 24 * 3600_000L // 1 day ago
        val currentDue = clock.now() + 2 * 3600_000L

        val task = Node(
            id = "n_slip_task",
            type = "task",
            title = "Final presentation",
            status = "active",
            dueAt = currentDue,
            createdAt = creationTime,
            updatedAt = creationTime,
            originalDueAt = currentDue,
            slipCount = 0
        )
        storage.saveNode(task)

        // Move deadline forward by 2 days (48 hours)
        val slippedDue = currentDue + 48 * 3600_000L
        val result = gateway.executeTool(
            ToolCall(
                tool = "propose_change",
                args = mapOf(
                    "intent" to JsonPrimitive(IntentType.UPDATE.name),
                    "target_ref" to JsonPrimitive("n_slip_task"),
                    "due_at" to JsonPrimitive(slippedDue.toString())
                )
            ),
            executedCalls = mutableSetOf()
        )

        assertTrue(result is ToolResult.Committed)
        val updated = storage.getNode("n_slip_task")
        assertNotNull(updated)
        assertEquals("Slipping deadline forward past grace must increment slipCount", 1, updated!!.slipCount)
        assertEquals(slippedDue, updated.dueAt)
        assertEquals(currentDue, updated.originalDueAt)
        assertNotNull(updated.dueHistoryJson)
        val history = json.decodeFromString<List<Long>>(updated.dueHistoryJson!!)
        assertEquals(listOf(currentDue, slippedDue), history)
    }

    @Test
    fun testUndo_RestoresOriginalDueAtAndSlipCountAndHistory() = runBlocking {
        val now = clock.now()
        val originalDue = now + 3600_000L
        val task = Node(
            id = "n_undo_task",
            type = "task",
            title = "Car wash",
            status = "active",
            dueAt = originalDue,
            createdAt = now - 3600_000L,
            updatedAt = now - 3600_000L,
            originalDueAt = originalDue,
            slipCount = 0
        )
        storage.saveNode(task)

        // Reschedule past grace -> slipCount = 1
        val slippedDue = originalDue + 24 * 3600_000L
        val updateRes = gateway.executeTool(
            ToolCall(
                tool = "propose_change",
                args = mapOf(
                    "intent" to JsonPrimitive(IntentType.UPDATE.name),
                    "target_ref" to JsonPrimitive("n_undo_task"),
                    "due_at" to JsonPrimitive(slippedDue.toString())
                )
            ),
            executedCalls = mutableSetOf()
        ) as ToolResult.Committed

        val slipped = storage.getNode("n_undo_task")!!
        assertEquals(1, slipped.slipCount)
        assertEquals(slippedDue, slipped.dueAt)

        // Undo the update
        val undoRes = gateway.executeTool(
            ToolCall(
                tool = "undo",
                args = mapOf("token" to JsonPrimitive(updateRes.undoToken!!))
            ),
            executedCalls = mutableSetOf()
        )

        assertTrue(undoRes is ToolResult.Committed)
        val restored = storage.getNode("n_undo_task")!!
        assertEquals(0, restored.slipCount)
        assertEquals(originalDue, restored.dueAt)
        assertEquals(originalDue, restored.originalDueAt)
    }
}
