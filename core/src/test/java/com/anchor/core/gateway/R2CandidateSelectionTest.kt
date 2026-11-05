// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.gateway

import com.anchor.core.SharedFakeStorage
import com.anchor.core.context.ContextBuilder
import com.anchor.core.models.IntentType
import com.anchor.core.models.Node
import com.anchor.core.models.ToolResult
import com.anchor.core.nudge.CheckinBuilder
import com.anchor.core.resolver.Resolver
import com.anchor.core.storage.TooManyActiveNodesException
import com.anchor.core.storage.getAllActiveNodesPaged
import com.anchor.core.time.DateResolver
import com.anchor.core.time.FakeClock
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class R2CandidateSelectionTest {

    @Test
    fun testR2_1_ResolveChange_With300ActiveNodes_FindsItem250ByDueDate(): Unit = runBlocking {
        val now = 1_700_000_000_000L
        val clock = FakeClock(now)
        val dateResolver = DateResolver(clock)
        val storage = SharedFakeStorage()

        // Insert 300 active nodes ordered by dueAt ASC
        for (i in 1..300) {
            val title = if (i == 250) "Dinner with Alice" else "Task number $i"
            storage.activeNodes.add(
                Node(
                    id = "node_$i",
                    type = "task",
                    title = title,
                    status = "active",
                    dueAt = now + (i * 60_000L), // spaced by minutes
                    createdAt = now,
                    updatedAt = now
                )
            )
        }

        val gateway = Gateway(storage, Resolver(), dateResolver, clock)

        // Cancel "dinner" which is item #250
        val result = gateway.executeTool(
            ToolCall(
                tool = "propose_change",
                args = mapOf(
                    "intent" to JsonPrimitive("CANCEL"),
                    "hint" to JsonPrimitive("dinner")
                )
            )
        )

        // With the 100-item default, node_250 was never loaded and returned NotFound.
        // With R2 paged candidate resolver, it must find node_250 and propose/commit cancellation.
        assertTrue("Must not return NotFound for node #250: $result", result !is ToolResult.NotFound)
        assertTrue("Must commit change for node_250, but got: $result", result is ToolResult.Committed)
        val committed = result as ToolResult.Committed
        assertEquals("node_250", committed.targetId)
    }

    @Test
    fun testR2_2_CreateDuplicateTitle_FoundWhen500NodesExist(): Unit = runBlocking {
        val now = 1_700_000_000_000L
        val clock = FakeClock(now)
        val dateResolver = DateResolver(clock)
        val storage = SharedFakeStorage()

        // Insert 500 active nodes. Node 400 has title "Walk the dog"
        for (i in 1..500) {
            val title = if (i == 400) "Walk the dog" else "Background item $i"
            storage.activeNodes.add(
                Node(
                    id = "n_$i",
                    type = "task",
                    title = title,
                    status = "active",
                    dueAt = now + (i * 10_000L),
                    createdAt = now,
                    updatedAt = now
                )
            )
        }

        val gateway = Gateway(storage, Resolver(), dateResolver, clock)

        // Attempt to create duplicate with whitespace / case variations
        val result = gateway.executeTool(
            ToolCall(
                tool = "propose_change",
                args = mapOf(
                    "intent" to JsonPrimitive("CREATE"),
                    "title" to JsonPrimitive("  walk the DOG  ")
                )
            )
        )

        assertTrue("Must return ToolResult.NeedsConfirm for duplicate title under P1-B 17, got: $result", result is ToolResult.NeedsConfirm)
        val needsConfirm = result as ToolResult.NeedsConfirm
        assertTrue("Message must state similar item already exists: ${needsConfirm.message}",
            needsConfirm.message.contains("already exists", ignoreCase = true) && needsConfirm.message.contains("Walk the dog"))
        assertEquals(listOf("Update existing", "Add anyway", "Cancel"), needsConfirm.options)
    }

    @Test
    fun testR2_Resolver_HardCapExceeded_ReturnsClearError(): Unit = runBlocking {
        val now = 1_700_000_000_000L
        val clock = FakeClock(now)
        val dateResolver = DateResolver(clock)
        val storage = SharedFakeStorage()

        // Insert 5001 active nodes
        for (i in 1..5001) {
            storage.activeNodes.add(
                Node(
                    id = "n_$i",
                    type = "task",
                    title = "Item $i",
                    status = "active",
                    dueAt = now + i,
                    createdAt = now,
                    updatedAt = now
                )
            )
        }

        // Direct helper test
        var exceptionCaught = false
        try {
            storage.getAllActiveNodesPaged(pageSize = 200, hardCap = 5000)
        } catch (e: TooManyActiveNodesException) {
            exceptionCaught = true
            assertEquals(5000, e.cap)
        }
        assertTrue("getAllActiveNodesPaged must throw TooManyActiveNodesException when exceeding 5000", exceptionCaught)

        // Gateway resolver test: must return clear error telling user to be more specific
        val gateway = Gateway(storage, Resolver(), dateResolver, clock)
        val result = gateway.executeTool(
            ToolCall(
                tool = "propose_change",
                args = mapOf(
                    "intent" to JsonPrimitive("CANCEL"),
                    "hint" to JsonPrimitive("Item 2000")
                )
            )
        )

        assertTrue("Must return ToolResult.Error when cap is exceeded, got: $result", result is ToolResult.Error)
        val error = result as ToolResult.Error
        assertTrue("Error message must tell user to be more specific: ${error.reason}",
            error.reason.contains("too many", ignoreCase = true) && error.reason.contains("specific", ignoreCase = true))
    }

    @Test
    fun testR2_3_ContextBuilder_150FutureNodesAndPinnedAndSeverity3_BothAppearInPack(): Unit = runBlocking {
        val now = 1_700_000_000_000L
        val clock = FakeClock(now)
        val dateResolver = DateResolver(clock)
        val storage = SharedFakeStorage()

        // 150 future dated nodes (> 48h from now, so beyond Tier 3 horizon)
        for (i in 1..150) {
            storage.activeNodes.add(
                Node(
                    id = "future_$i",
                    type = "task",
                    title = "Future task $i",
                    status = "active",
                    dueAt = now + (72L * 3600 * 1000L) + (i * 1000L),
                    createdAt = now - 1000L,
                    updatedAt = now - 1000L
                )
            )
        }

        // 1 undated pinned node
        storage.activeNodes.add(
            Node(
                id = "pinned_node_1",
                type = "task",
                title = "Important pinned rule",
                status = "active",
                dueAt = null,
                pinned = true,
                createdAt = now - 5000L,
                updatedAt = now - 5000L
            )
        )

        // 1 undated severity=3 node
        storage.activeNodes.add(
            Node(
                id = "severity_node_1",
                type = "task",
                title = "Urgent high severity matter",
                status = "active",
                dueAt = null,
                severity = 3,
                createdAt = now - 6000L,
                updatedAt = now - 6000L
            )
        )

        val checkinBuilder = CheckinBuilder(storage, clock, dateResolver)
        val contextBuilder = ContextBuilder(storage, clock, dateResolver, checkinBuilder)

        val pack = contextBuilder.buildPack()
        val sysMsg = pack.first { it.role == "system" }.content ?: ""

        assertTrue("Pinned node must appear in context pack despite 150 future nodes",
            sysMsg.contains("Important pinned rule"))
        assertTrue("Severity=3 node must appear in context pack despite 150 future nodes",
            sysMsg.contains("Urgent high severity matter"))
    }

    @Test
    fun testR2_4_ContextBuilder_100StaleOverdueAnd30Fresh_PackShowsFreshAndCorrectCount(): Unit = runBlocking {
        val now = 1_700_000_000_000L
        val clock = FakeClock(now)
        val dateResolver = DateResolver(clock)
        val storage = SharedFakeStorage()

        val staleCutoff = 30L * 24 * 3600 * 1000L
        val thirtyFiveDaysAgo = now - 35L * 24 * 3600 * 1000L

        // 100 stale overdue nodes
        for (i in 1..100) {
            storage.activeNodes.add(
                Node(
                    id = "stale_overdue_$i",
                    type = "task",
                    title = "Ancient overdue $i",
                    status = "active",
                    dueAt = thirtyFiveDaysAgo - (i * 3600 * 1000L),
                    createdAt = thirtyFiveDaysAgo,
                    updatedAt = thirtyFiveDaysAgo
                )
            )
        }

        // 30 fresh nodes
        for (i in 1..30) {
            storage.activeNodes.add(
                Node(
                    id = "fresh_$i",
                    type = "task",
                    title = "Fresh task $i",
                    status = "active",
                    dueAt = now + (i * 60_000L),
                    createdAt = now - 1000L,
                    updatedAt = now - 1000L
                )
            )
        }

        val checkinBuilder = CheckinBuilder(storage, clock, dateResolver)
        val contextBuilder = ContextBuilder(storage, clock, dateResolver, checkinBuilder)

        val pack = contextBuilder.buildPack()
        val sysMsg = pack.first { it.role == "system" }.content ?: ""

        // None of the stale overdue tasks should appear
        assertFalse("Stale overdue task must not appear in context", sysMsg.contains("Ancient overdue"))

        // Fresh tasks should appear in the context pack
        assertTrue("Fresh tasks must appear in context pack", sysMsg.contains("Fresh task 1"))

        // Count summary: 30 non-stale open items, budget cap 25 items -> 5 more open items not shown
        assertTrue("Count line must only reflect non-stale items (5 more open items not shown, not 105)",
            sysMsg.contains("5 more open items not shown"))
    }

    @Test
    fun testR2_5_CheckinBuilder_200Items_CountsEqualRealCounts(): Unit = runBlocking {
        val now = 1_700_000_000_000L
        val clock = FakeClock(now)
        val dateResolver = DateResolver(clock)
        val storage = SharedFakeStorage()

        val rolloverMin = 240 // 4:00 AM
        val logicalTodayStart = dateResolver.getLogicalDayStart(now, rolloverMin)
        val logicalTodayEnd = logicalTodayStart + 86400000L

        // Populate 200 items:
        // 50 overdue (< logicalTodayStart)
        for (i in 1..50) {
            storage.activeNodes.add(
                Node(
                    id = "overdue_$i",
                    type = "task",
                    title = "Overdue $i",
                    status = "active",
                    dueAt = logicalTodayStart - (i * 60_000L),
                    createdAt = now - 10000L,
                    updatedAt = now - 10000L
                )
            )
        }

        // 70 due today (in logicalTodayStart until logicalTodayEnd)
        for (i in 1..70) {
            storage.activeNodes.add(
                Node(
                    id = "today_$i",
                    type = "task",
                    title = "Due today $i",
                    status = "active",
                    dueAt = logicalTodayStart + 3600_000L + (i * 60_000L),
                    createdAt = now - 10000L,
                    updatedAt = now - 10000L
                )
            )
        }

        // 80 due in the future (>= logicalTodayEnd)
        for (i in 1..80) {
            storage.activeNodes.add(
                Node(
                    id = "future_$i",
                    type = "task",
                    title = "Future $i",
                    status = "active",
                    dueAt = logicalTodayEnd + (i * 60_000L),
                    createdAt = now - 10000L,
                    updatedAt = now - 10000L
                )
            )
        }

        val checkinBuilder = CheckinBuilder(storage, clock, dateResolver)
        val facts = checkinBuilder.buildMorningFacts()

        // Must show real counts: Due Today: 70, Overdue: 50
        assertTrue("Must report exact Due Today count of 70, got:\n$facts", facts.contains("- Due Today: 70"))
        assertTrue("Must report exact Overdue count of 50, got:\n$facts", facts.contains("- Overdue: 50"))
    }

    @Test
    fun testR2_6_StorageContract_SharedFakeStorage(): Unit = runBlocking {
        val now = 1_700_000_000_000L
        val staleCutoff = 30L * 24 * 3600 * 1000L
        val storage = SharedFakeStorage()

        // Populate standard contract dataset
        storage.activeNodes.add(Node(id = "n_walk", type = "task", title = "Walk the dog", status = "active", dueAt = now + 10_000L, createdAt = now - 1000L, updatedAt = now - 1000L))
        storage.activeNodes.add(Node(id = "n_groceries", type = "task", title = "Buy groceries", status = "active", dueAt = now + 7200_000L, createdAt = now - 1000L, updatedAt = now - 1000L))
        storage.activeNodes.add(Node(id = "n_overdue_fresh", type = "task", title = "Pay electric bill", status = "active", dueAt = now - 3600_000L, createdAt = now - 1000L, updatedAt = now - 1000L))
        storage.activeNodes.add(Node(id = "n_overdue_stale", type = "task", title = "Old parking ticket", status = "active", dueAt = now - (40L * 86400000L), createdAt = now - (40L * 86400000L), updatedAt = now - (35L * 86400000L)))
        storage.activeNodes.add(Node(id = "n_pinned", type = "task", title = "Keep kitchen clean", status = "active", dueAt = null, pinned = true, createdAt = now - 1000L, updatedAt = now - 1000L))
        storage.activeNodes.add(Node(id = "n_urgent", type = "task", title = "Fix water leak", status = "active", dueAt = null, severity = 3, createdAt = now - 1000L, updatedAt = now - 1000L))
        storage.activeNodes.add(Node(id = "n_done", type = "task", title = "Walk the dog", status = "done", dueAt = now - 10_000L, createdAt = now - 2000L, updatedAt = now - 1000L))
        storage.activeNodes.add(Node(id = "n_deleted", type = "task", title = "Walk the dog", status = "deleted", dueAt = now - 10_000L, createdAt = now - 2000L, updatedAt = now - 1000L))

        // 1. findActiveNodeByTitle
        val foundWalk = storage.findActiveNodeByTitle("  walk the DOG  ")
        assertNotNull(foundWalk)
        assertEquals("n_walk", foundWalk?.id)

        val foundGroceries = storage.findActiveNodeByTitle("buy groceries")
        assertNotNull(foundGroceries)
        assertEquals("n_groceries", foundGroceries?.id)

        val foundMissing = storage.findActiveNodeByTitle("does not exist")
        assertEquals(null, foundMissing)

        // 2. getNodesDueBetween
        val dueSoon = storage.getNodesDueBetween(now, now + 86400000L, limit = 10)
        assertEquals(listOf("n_walk", "n_groceries"), dueSoon.map { it.id })

        // 3. getOverdueNodes
        val overdue = storage.getOverdueNodes(now, limit = 10)
        assertEquals(listOf("n_overdue_stale", "n_overdue_fresh"), overdue.map { it.id })

        // 4. getRankedCandidates: must exclude stale overdue, done, deleted; include fresh overdue, due soon, pinned, high severity
        val ranked = storage.getRankedCandidates(now, staleCutoff, perTierLimit = 10)
        val rankedIds = ranked.map { it.id }.toSet()
        assertTrue("Must include fresh overdue", rankedIds.contains("n_overdue_fresh"))
        assertFalse("Must NOT include stale overdue", rankedIds.contains("n_overdue_stale"))
        assertTrue("Must include due soon", rankedIds.contains("n_walk") && rankedIds.contains("n_groceries"))
        assertTrue("Must include pinned", rankedIds.contains("n_pinned"))
        assertTrue("Must include high severity", rankedIds.contains("n_urgent"))
        assertFalse("Must NOT include done or deleted", rankedIds.contains("n_done") || rankedIds.contains("n_deleted"))
    }
}
