// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.ui

import com.anchor.core.SharedFakeStorage
import com.anchor.core.models.ChatMessage
import com.anchor.core.models.Node
import com.anchor.core.models.RawEvent
import com.anchor.core.time.FakeClock
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class P1D20HomeAndTimelineTest {

    private val now = 1770000000000L
    private val dayMs = 24L * 3600 * 1000L

    private fun node(
        id: String,
        title: String,
        status: String = "active",
        dueAt: Long? = null,
        priority: Int = 0,
        severity: Int = 0,
        pinned: Boolean = false,
        updatedAt: Long = now
    ) = Node(
        id = id, type = "task", title = title, status = status,
        dueAt = dueAt, createdAt = now, updatedAt = updatedAt,
        priority = priority, severity = severity, pinned = pinned
    )

    private fun storage(clock: FakeClock) = SharedFakeStorage(clock)

    // ---------- Home priority list ranking (code-side, D-121) ----------

    @Test
    fun testHomePriorityList_OverdueFirst_ThenInProgress_ThenDueSoonest() = runBlocking {
        val clock = FakeClock(now)
        val st = storage(clock)
        val overdue = node("n_over", "Overdue task", dueAt = now - 3600_000L)
        val dueSoon = node("n_soon", "Due soon task", dueAt = now + 3600_000L)
        val inProgress = node("n_prog", "In progress task", status = "in_progress", dueAt = now + 10 * 3600_000L)
        st.activeNodes.addAll(listOf(dueSoon, inProgress, overdue))

        val list = st.getHomePriorityList(now)

        assertEquals(listOf("n_over", "n_prog", "n_soon"), list.map { it.id })
    }

    @Test
    fun testHomePriorityList_PinnedAndSeverityRankAbovePlainActive() = runBlocking {
        val clock = FakeClock(now)
        val st = storage(clock)
        val plain = node("n_plain", "Plain task", dueAt = now + 5 * 3600_000L)
        val pinned = node("n_pin", "Pinned task", dueAt = now + 6 * 3600_000L, pinned = true)
        val severe = node("n_sev", "Severe task", dueAt = now + 7 * 3600_000L, severity = 3)
        st.activeNodes.addAll(listOf(plain, pinned, severe))

        val list = st.getHomePriorityList(now)

        // All three are due in the future and not in_progress: pinned and severity rank above plain.
        assertEquals(listOf("n_pin", "n_sev", "n_plain"), list.map { it.id })
    }

    @Test
    fun testHomePriorityList_ExcludesClosedStaleAndLocalOnly_CapsAtLimit() = runBlocking {
        val clock = FakeClock(now)
        val st = storage(clock)
        st.activeNodes.add(node("n_done", "Done", status = "done", dueAt = now + 3600_000L))
        st.activeNodes.add(node("n_local", "Local only", dueAt = now + 3600_000L).copy(localOnly = true))
        st.activeNodes.add(node("n_stale", "Stale", updatedAt = now - 40L * dayMs))
        for (i in 1..7) {
            st.activeNodes.add(node("n_$i", "Task $i", dueAt = now + i * 3600_000L))
        }

        val list = st.getHomePriorityList(now, limit = 5)

        assertEquals(5, list.size)
        assertTrue("Done node excluded", list.none { it.id == "n_done" })
        // Blueprint 16 & 21 §3.5 Rule 8 / Audit Bug 3: Local-only nodes are visible in Home UI
        assertTrue("Local-only node included in Home UI", list.any { it.id == "n_local" })
        assertTrue("Stale node excluded", list.none { it.id == "n_stale" })
    }

    // ---------- Recently closed with undo validity ----------

    @Test
    fun testGetRecentlyClosed_Within24h_UndoValidUntilUndone() = runBlocking {
        val clock = FakeClock(now)
        val st = storage(clock)
        val closed = node("n_closed", "Finished task", status = "done", dueAt = null)
        st.activeNodes.add(closed)
        st.updateNodeAndCommit(
            closed.copy(closedAt = now - 3600_000L, updatedAt = now - 3600_000L),
            RawEvent("e_close", "COMPLETE", "node", closed.id, tsUtc = now - 3600_000L),
            deleteReminderId = null, upsertReminder = null, consumeProposalId = null
        )

        val recent = st.getRecentlyClosed(now)
        assertEquals(listOf(closed.id), recent.map { it.id })

        // Undo-validity rule mirrors loadReceipts: <24h old and not already undone.
        val event = st.getLatestEventForTarget("node", closed.id)!!
        assertTrue((now - event.tsUtc) < dayMs && !st.hasUndo(event.opId))

        // After an undo event, hasUndo flips and validity must drop.
        st.loggedEvents.add(RawEvent("e_undo", "UNDO", "node", closed.id, undoOf = event.opId, tsUtc = now))
        assertTrue(st.hasUndo(event.opId))
    }

    @Test
    fun testGetRecentlyClosed_OlderThan24h_Excluded() = runBlocking {
        val clock = FakeClock(now)
        val st = storage(clock)
        val old = node("n_old", "Old finished", status = "done")
        st.activeNodes.add(old.copy(closedAt = now - 2 * dayMs))
        val fresh = node("n_fresh", "Fresh finished", status = "cancelled")
        st.activeNodes.add(fresh.copy(closedAt = now - 3600_000L))

        val recent = st.getRecentlyClosed(now)

        assertEquals(listOf("n_fresh"), recent.map { it.id })
    }

    // ---------- UPDATE receipt visibility (P1-B 16 regression) ----------

    @Test
    fun testGetRecentEvents_IncludesUpdate() = runBlocking {
        val clock = FakeClock(now)
        val st = storage(clock)
        st.loggedEvents.add(RawEvent("e_1", "CREATE", "node", "n_a", tsUtc = now))
        st.loggedEvents.add(RawEvent("e_2", "UPDATE", "node", "n_a", tsUtc = now + 1))

        val events = st.getRecentEvents(30)

        assertTrue("UPDATE must be UI-visible", events.any { it.opType == "UPDATE" })
    }

    @Test
    fun testReceiptFormatter_UpdateBranch_UsesAfterSnapshotTitleAndDue() {
        val clock = FakeClock(now)
        val dateResolver = com.anchor.core.time.DateResolver(clock) { true }
        val formatter = ReceiptFormatter(dateResolver)
        val snapshotJson = """{"node":{"id":"n_1","type":"task","title":"Renamed task","status":"active","dueAt":$now,"createdAt":$now,"updatedAt":$now},"reminder":null}"""
        val event = RawEvent("e_upd", "UPDATE", "node", "n_1", afterJson = snapshotJson, tsUtc = now)

        val text = formatter.format(event)

        assertTrue("Expected 'Updated: Renamed task (...)', got '$text'", text.startsWith("Updated: Renamed task ("))
    }

    // ---------- Chat timeline composition ----------

    @Test
    fun testTimelineBuilder_MessagesDayHeadersReceiptsAndProposals() {
        val messages = listOf(
            ChatMessage("m_1", "user", "hello", "2026-11-05", 1000L),
            ChatMessage("m_2", "assistant", "hi", "2026-11-05", 2000L),
            ChatMessage("m_3", "system", "boot", "2026-11-05", 2500L),
            ChatMessage("m_4", "user", "new day", "2026-11-06", 3000L)
        )
        val receipts = listOf(
            ReceiptState("r_1", "Created: X", "e_1", false, causeMsgId = "m_2", timestamp = 2100L),
            ReceiptState("r_2", "Updated: Y", "e_2", false, causeMsgId = null, timestamp = 2200L)
        )
        val proposal = com.anchor.core.models.Proposal(
            id = "p_1", kind = "ambiguous",
            intent = com.anchor.core.models.IntentType.COMPLETE,
            options = listOf(com.anchor.core.models.ProposalOption("n_1", "Task one"))
        )

        val items = ChatTimelineBuilder.build(messages, receipts, listOf(proposal))

        val types = items.map { it::class.simpleName }
        // Day header + user bubble + assistant bubble + attached receipt + system line + new day header + user bubble + orphan receipt + proposal row
        assertEquals(
            listOf(
                "DayHeader", "Message", "Message", "ReceiptItem", "SystemEvent",
                "DayHeader", "Message", "ReceiptItem", "ProposalRow"
            ),
            types
        )
        val receiptItem = items.filterIsInstance<ChatItem.ReceiptItem>().first()
        assertEquals("r_1", receiptItem.receipt.id)
        val sys = items.filterIsInstance<ChatItem.SystemEvent>().first()
        assertEquals("boot", sys.text)
    }

    @Test
    fun testTimelineBuilder_EmptyInputs() {
        val items = ChatTimelineBuilder.build(emptyList(), emptyList(), emptyList())
        assertTrue(items.isEmpty())
    }

    // ---------- rankHomeNodes comparator edge cases ----------

    @Test
    fun testRankHomeNodes_UndatedNodesFallToBottom() {
        val undated = node("n_undated", "No due date")
        val dated = node("n_dated", "With due date", dueAt = now + 3600_000L)
        val ranked = com.anchor.core.storage.rankHomeNodes(listOf(undated, dated), now)
        assertEquals(listOf("n_dated", "n_undated"), ranked.map { it.id })
    }

    @Test
    fun testRankHomeNodes_PriorityBreaksSeverityTies() {
        val lowPrio = node("n_low", "Low prio", dueAt = now + 3600_000L, priority = 1)
        val highPrio = node("n_high", "High prio", dueAt = now + 3600_000L, priority = 3)
        val ranked = com.anchor.core.storage.rankHomeNodes(listOf(lowPrio, highPrio), now)
        assertEquals(listOf("n_high", "n_low"), ranked.map { it.id })
    }
}
