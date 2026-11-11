// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.context

import com.anchor.core.SharedFakeStorage
import com.anchor.core.models.ChatMessage
import com.anchor.core.models.IntentType
import com.anchor.core.models.Node
import com.anchor.core.models.Proposal
import com.anchor.core.models.ProposalOption
import com.anchor.core.models.SoulFact
import com.anchor.core.time.DateResolver
import com.anchor.core.time.FakeClock
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextBuilderTest {

    @Test
    fun testPendingClarifications_IncludeOptionTitlesAndDueTimes() = runBlocking {
        // B1: The model must see each option's title (and due time when present),
        // not just the option id, so it can match the user's words to a choice.
        val storage = SharedFakeStorage().apply {
            runBlocking {
                saveProposal(
                    Proposal(
                        id = "p1",
                        kind = "node",
                        intent = IntentType.RESCHEDULE,
                        options = listOf(
                            ProposalOption(id = "opt_a", title = "Gym workout", dueTime = "Tomorrow 6:00 PM"),
                            ProposalOption(id = "opt_b", title = "Gym workout", dueTime = "Friday 7:30 AM")
                        )
                    )
                )
            }
        }
        val clock = FakeClock(1000L)
        val dateResolver = DateResolver(clock)
        val checkinBuilder = com.anchor.core.nudge.CheckinBuilder(storage, clock, dateResolver)
        val builder = ContextBuilder(storage, clock, dateResolver, checkinBuilder)

        val pack = builder.buildPack()
        val sysMsg = pack.first { it.role == "system" }

        val pendingSection = sysMsg.content!!.substringAfter("Pending Clarifications:\n")
        assertTrue("Proposal id must be present", pendingSection.contains("p1"))
        assertTrue("Option A title must be present", pendingSection.contains("Gym workout"))
        assertTrue("Option A due time must be present", pendingSection.contains("Tomorrow 6:00 PM"))
        assertTrue("Option B due time must be present", pendingSection.contains("Friday 7:30 AM"))
        assertFalse("Option ids alone are not enough", pendingSection.contains("Options: opt_a, opt_b"))
    }

    @Test
    fun testPromptCacheOrdering_StaticBeforeDynamic() = runBlocking {
        val storage = SharedFakeStorage().apply {
            soulFacts = mutableListOf(SoulFact("sf1", "pref", "Likes green tea", 0.9, "user", "manual", "active", 1000L))
            activeNodes = mutableListOf(Node("n1", "task", "Submit report", "active"))
        }
        val clock = FakeClock(1000L)
        val dateResolver = DateResolver(clock)
        val checkinBuilder = com.anchor.core.nudge.CheckinBuilder(storage, clock, dateResolver)
        val builder = ContextBuilder(storage, clock, dateResolver, checkinBuilder)

        val pack = builder.buildPack()
        val sysContent = pack.first { it.role == "system" }.content!!

        val soulIndex = sysContent.indexOf("\n\nSoul:\n")
        val activeIndex = sysContent.indexOf("\n\nActive Items:\n")
        val nowIndex = sysContent.indexOf("\n\nNow: ")

        assertTrue("Soul must be present", soulIndex >= 0)
        assertTrue("Active Items must be present", activeIndex >= 0)
        assertTrue("Now: must be present", nowIndex >= 0)

        // Blueprint 20 §3.2 (Prompt Cache optimization):
        // Static components (Soul, Active Items) must precede volatile components (Now, Check-ins)
        assertTrue("Soul facts must precede dynamic Now timestamp", soulIndex < nowIndex)
        assertTrue("Active Items must precede dynamic Now timestamp", activeIndex < nowIndex)
    }

    @Test
    fun testContextBuilder_TruncatesChatToRespectBudget() = runBlocking {
        // Create 20 large messages, each about 100 tokens (350 chars)
        // Total history = 2000 tokens (Exceeds the 1500 token chat budget)
        val bigText = "A".repeat(350)
        val history = (1..20).map { i ->
            ChatMessage("m$i", "user", "$i $bigText")
        }
        val newUserMsg = ChatMessage("m21", "user", "What did I say?")

        val nodes = listOf(Node("n1", "task", "Buy milk", "active"))
        val storage = SharedFakeStorage().apply {
            messages = (history + newUserMsg).toMutableList()
            activeNodes = nodes.toMutableList()
        }

        val clock = FakeClock(1000L)
        val dateResolver = DateResolver(clock)
        val checkinBuilder = com.anchor.core.nudge.CheckinBuilder(storage, clock, dateResolver)
        val builder = ContextBuilder(storage, clock, dateResolver, checkinBuilder)
        val pack = builder.buildPack()

        // 1 for system, N for history
        val historyIncluded = pack.filter { it.id.startsWith("m") }

        // It shouldn't include all 21 messages.
        // 1500 tokens / ~100 tokens per message = roughly 14-15 messages allowed.
        assertTrue("Should truncate history to fit budget", historyIncluded.size < 21)
        assertTrue("Should still include some history", historyIncluded.isNotEmpty())

        // Ensure chronological order (last item in historyIncluded should be m21)
        assertEquals("m21", historyIncluded.last().id)

        // Ensure System prompt has the nodes
        val sysMsg = pack.first { it.role == "system" }
        assertTrue(sysMsg.content?.contains("Buy milk") == true)

        // Ensure last message is the new user message
        assertEquals("What did I say?", pack.last().content)
    }

    @Test
    fun testD104_ContextBuilder_CapsAtBudgetAndAppendsCount_WithLargeNodeSet() = runBlocking {
        val now = 1_700_000_000_000L
        val clock = FakeClock(now)
        val dateResolver = DateResolver(clock)

        // Generate 500 nodes:
        // 5 overdue nodes
        // 5 in_progress nodes
        // 10 due within 48h
        // 5 pinned nodes
        // 10 high severity nodes
        // 450 regular undated nodes
        // 15 stale nodes (created 35 days ago, updated 35 days ago)
        val nodes = mutableListOf<Node>()

        // 15 stale nodes (> 30 days inactive)
        val thirtyFiveDaysAgo = now - 35L * 24 * 3600 * 1000L
        for (i in 1..15) {
            nodes.add(Node(
                id = "stale_$i",
                type = "task",
                title = "Stale task $i",
                status = "active",
                dueAt = null,
                createdAt = thirtyFiveDaysAgo,
                updatedAt = thirtyFiveDaysAgo
            ))
        }

        // 5 overdue nodes
        for (i in 1..5) {
            nodes.add(Node(
                id = "overdue_$i",
                type = "task",
                title = "Overdue task $i",
                status = "active",
                dueAt = now - (i * 3600 * 1000L), // hours ago
                createdAt = now - 10000L,
                updatedAt = now - 10000L
            ))
        }

        // 5 in_progress nodes (not overdue)
        for (i in 1..5) {
            nodes.add(Node(
                id = "in_prog_$i",
                type = "task",
                title = "In progress task $i",
                status = "in_progress",
                dueAt = now + 72 * 3600 * 1000L,
                createdAt = now - 20000L,
                updatedAt = now - 20000L
            ))
        }

        // 10 due within 48h
        for (i in 1..10) {
            nodes.add(Node(
                id = "due_soon_$i",
                type = "task",
                title = "Due soon task $i",
                status = "active",
                dueAt = now + (i * 3600 * 1000L),
                createdAt = now - 30000L,
                updatedAt = now - 30000L
            ))
        }

        // 5 pinned nodes
        for (i in 1..5) {
            nodes.add(Node(
                id = "pinned_$i",
                type = "task",
                title = "Pinned task $i",
                status = "active",
                dueAt = null,
                createdAt = now - 40000L,
                updatedAt = now - 40000L,
                pinned = true
            ))
        }

        // 10 high severity nodes
        for (i in 1..10) {
            nodes.add(Node(
                id = "sev_$i",
                type = "task",
                title = "High severity task $i",
                status = "active",
                dueAt = null,
                createdAt = now - 50000L,
                updatedAt = now - 50000L,
                severity = 3
            ))
        }

        // 450 regular undated nodes
        for (i in 1..450) {
            nodes.add(Node(
                id = "regular_$i",
                type = "task",
                title = "Regular task $i",
                status = "active",
                dueAt = null,
                createdAt = now - (60000L + i),
                updatedAt = now - (60000L + i)
            ))
        }

        assertEquals(495 + 5, nodes.size) // 500 nodes total

        val storage = SharedFakeStorage().apply {
            activeNodes = nodes.toMutableList()
        }

        val checkinBuilder = com.anchor.core.nudge.CheckinBuilder(storage, clock, dateResolver)
        val builder = ContextBuilder(storage, clock, dateResolver, checkinBuilder)
        val pack = builder.buildPack()

        val sysMsg = pack.first { it.role == "system" }.content ?: ""

        // 1. Stale nodes must NOT be included in the active context
        for (i in 1..15) {
            assertTrue("Stale node stale_$i must leave active context", !sysMsg.contains("stale_$i"))
        }

        // 2. Overdue items must ALWAYS be included
        for (i in 1..5) {
            assertTrue("Overdue item overdue_$i must be included", sysMsg.contains("overdue_$i"))
        }

        // 3. Count line must be present and exact
        // Total non-stale open = 500 - 15 = 485.
        // Capped at 25 items.
        // 485 - 25 = 460 more open items not shown.
        // All 5 overdue items are shown, so 0 overdue are not shown.
        assertTrue("Count line must be present", sysMsg.contains("460 more open items not shown (0 overdue)"))

        // 4. Budget cap: at most 25 items shown in Active Items
        val activeSection = sysMsg.substringAfter("Active Items:\n").substringBefore("\n\nPending Clarifications:")
        val itemLines = activeSection.lines().filter { it.contains(" | ") }
        assertEquals(25, itemLines.size)

        // 5. Deterministic ordering: overdue first, then in_progress, then due_soon
        val firstOverdueIdx = activeSection.indexOf("overdue_1")
        val firstInProgIdx = activeSection.indexOf("in_prog_1")
        val firstDueSoonIdx = activeSection.indexOf("due_soon_1")
        assertTrue("Overdue comes before in_progress", firstOverdueIdx < firstInProgIdx)
        assertTrue("In_progress comes before due_soon", firstInProgIdx < firstDueSoonIdx)
    }

    @Test
    fun testD104_StaleNodes_MoveToInbox() = runBlocking {
        val now = 1_700_000_000_000L
        val clock = FakeClock(now)
        val thirtyOneDaysAgo = now - 31L * 24 * 3600 * 1000L

        val staleNode = Node("stale_1", "task", "Old task", "active", null, thirtyOneDaysAgo, thirtyOneDaysAgo)
        val activeNode = Node("act_1", "task", "Fresh task", "active", null, now, now)

        val storage = SharedFakeStorage().apply {
            activeNodes = mutableListOf(staleNode, activeNode)
        }

        val staleNodes = storage.getStaleNodes(now)
        assertEquals(1, staleNodes.size)
        assertEquals("stale_1", staleNodes[0].id)
    }

    @Test
    fun testD104_PaginationCursor_HasNoGapsOrDuplicates() = runBlocking {
        val now = 1_700_000_000_000L
        val nodes = mutableListOf<Node>()

        // 5,000 nodes total with diverse due dates and IDs
        for (i in 1..5000) {
            val dueAt = if (i % 3 == 0) null else now + (i * 60_000L)
            nodes.add(Node(
                id = String.format("node_%05d", i),
                type = "task",
                title = "Task $i",
                status = "active",
                dueAt = dueAt,
                createdAt = now - i,
                updatedAt = now - i
            ))
        }

        val storage = SharedFakeStorage().apply {
            activeNodes = nodes.toMutableList()
        }

        // Paginate using cursor with page size 150
        val pageSize = 150
        val visitedIds = mutableListOf<String>()
        var cursor: String? = null
        var pages = 0

        while (true) {
            val page = storage.getActiveNodes(limit = pageSize, cursor = cursor)
            if (page.isEmpty()) break
            pages++
            page.forEach { visitedIds.add(it.id) }
            val last = page.last()
            cursor = "${last.dueAt ?: "null"}:${last.id}"
        }

        assertEquals("Must visit exactly 5000 items", 5000, visitedIds.size)
        assertEquals("Zero duplicates allowed", 5000, visitedIds.toSet().size)
    }

    @Test
    fun testH3_NewestUserMessage_Survives_WhenItselfLargerThanBudget() = runBlocking {
        // H3: the newest message is the user's live turn. The 3000-char budget must never
        // silently drop it - if it alone exceeds the budget, it is still the message the model
        // has to answer.
        val clock = FakeClock(1000L)
        val dateResolver = DateResolver(clock)
        val oversized = "A".repeat(4000)
        val storage = SharedFakeStorage().apply {
            messages = mutableListOf(
                ChatMessage("m1", "user", "older message"),
                ChatMessage("m2", "assistant", "older reply"),
                ChatMessage("m3", "user", oversized)
            )
        }
        val checkinBuilder = com.anchor.core.nudge.CheckinBuilder(storage, clock, dateResolver)
        val builder = ContextBuilder(storage, clock, dateResolver, checkinBuilder)

        val pack = builder.buildPack()

        assertEquals("Newest user message must be the last element", "m3", pack.last().id)
        assertEquals(oversized, pack.last().content)
    }

    @Test
    fun testH3_LargeMidHistoryMessage_DoesNotStarveOlderMessages() = runBlocking {
        // H3: one message that alone exceeds the REMAINING budget must be skipped, not used to
        // terminate the walk - otherwise every older message is discarded even though it fits.
        val clock = FakeClock(1000L)
        val dateResolver = DateResolver(clock)
        val storage = SharedFakeStorage().apply {
            messages = mutableListOf(
                ChatMessage("m1", "user", "first small message"),
                ChatMessage("m2", "assistant", "second small message"),
                ChatMessage("m3", "user", "B".repeat(3500)),
                ChatMessage("m4", "user", "newest message")
            )
        }
        val checkinBuilder = com.anchor.core.nudge.CheckinBuilder(storage, clock, dateResolver)
        val builder = ContextBuilder(storage, clock, dateResolver, checkinBuilder)

        val pack = builder.buildPack()
        val historyIds = pack.filter { it.id.startsWith("m") }.map { it.id }

        assertEquals("Newest message must survive", "m4", historyIds.last())
        assertTrue("Older messages that fit must not be starved by one oversized message",
            historyIds.containsAll(listOf("m1", "m2")))
        assertFalse("The oversized message itself must not be included", historyIds.contains("m3"))
    }

    @Test
    fun testContextBuilder_200SoulFacts_RespectsBudget() = runBlocking {
        val now = 1_700_000_000_000L
        val clock = FakeClock(now)
        val dateResolver = DateResolver(clock)
        val storage = SharedFakeStorage()

        // Insert 200 soul facts
        for (i in 1..200) {
            storage.saveSoulFact(SoulFact("sf_$i", "Preferences", "User prefers hot drink variety $i with extra sugar", 1.0, "chat", "inferred", "accepted", now))
        }

        val checkinBuilder = com.anchor.core.nudge.CheckinBuilder(storage, clock, dateResolver)
        val builder = ContextBuilder(storage, clock, dateResolver, checkinBuilder)
        val pack = builder.buildPack()

        val sysMsg = pack.first { it.role == "system" }.content ?: ""
        val soulSection = sysMsg.substringAfter("Soul:\n").substringBefore("\n\nActive Items:")

        assertTrue("Soul section must not be empty", soulSection.isNotBlank())
        assertTrue("Soul section must not exceed 1250 chars budget", soulSection.length <= 1250)
        // Ensure not all 200 facts leaked in uncapped
        assertTrue("Must cap soul facts", !soulSection.contains("variety 200"))
    }
}
