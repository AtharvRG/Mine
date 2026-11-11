// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.ui

import com.anchor.core.SharedFakeStorage
import com.anchor.core.alarms.AlarmScheduler
import com.anchor.core.alarms.ScheduleResult
import com.anchor.core.context.ContextBuilder
import com.anchor.core.gateway.Gateway
import com.anchor.core.models.Node
import com.anchor.core.models.Reminder
import com.anchor.core.models.SoulFact
import com.anchor.core.nudge.CheckinBuilder
import com.anchor.core.provider.HttpTransport
import com.anchor.core.provider.ProviderOrchestrator
import com.anchor.core.provider.RateLimiter
import com.anchor.core.provider.Target
import com.anchor.core.resolver.Resolver
import com.anchor.core.time.DateResolver
import com.anchor.core.time.FakeClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Row 20, blueprint 51 §3.1 rule 1: the viewer shows what the tables hold. This pins the load
 * itself — the Compose test pins what the screen draws from it.
 *
 * The two things that can silently regress: a load that never marks itself done (the tab would
 * re-read on every state change) and a load that shows rows the rest of the system treats as
 * closed or local-only.
 */
class MemoryViewerLoadTest {

    private class NoTransport : HttpTransport {
        override suspend fun postStream(target: Target, body: String): Flow<String> = flow {
            throw AssertionError("the memory viewer must not call the model")
        }
    }

    private fun controller(storage: SharedFakeStorage, clock: FakeClock): ChatController {
        val dateResolver = DateResolver(clock)
        return ChatController(
            storage,
            ProviderOrchestrator(RateLimiter { clock.now() }, NoTransport()),
            ContextBuilder(storage, clock, dateResolver, CheckinBuilder(storage, clock, dateResolver)),
            Gateway(storage, Resolver(), dateResolver, clock),
            object : AlarmScheduler {
                override val lastFiredId = MutableStateFlow<String?>(null)
                override fun schedule(reminder: Reminder) = ScheduleResult.Armed
                override fun cancel(reminderId: String) {}
            },
            CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined, clock
        )
    }

    @Test
    fun loadMemory_ReadsOpenItemsAndActiveFacts_WithoutCallingTheModel() {
        val storage = SharedFakeStorage()
        val clock = FakeClock(1000L)
        storage.activeNodes = mutableListOf(
            Node("n1", "task", "Pay the electricity bill", "active", 5000L),
            Node("n2", "task", "Book the dentist", "in_progress", 6000L),
            Node("n3", "task", "Cancelled thing", "cancelled", 7000L)
        )
        storage.soulFacts = mutableListOf(
            SoulFact("s1", "Preferences", "Prefers tea over coffee", 0.9, "chat", "inferred", "active", 100L),
            SoulFact("s2", "Preferences", "Rejected guess", 0.4, "chat", "inferred", "rejected", 100L)
        )

        val controller = controller(storage, clock)
        assertFalse("nothing is loaded before the tab is opened", controller.state.value.memoryLoaded)

        controller.onAction(UiAction.LoadMemory)

        val state = controller.state.value
        assertTrue("the load marks itself done so it does not re-fire", state.memoryLoaded)
        assertEquals(
            "open items only, in the order the table returns them",
            listOf("n1", "n2"),
            state.memoryNodes.map { it.id }
        )
        assertEquals(listOf("s1"), state.memorySoulFacts.map { it.id })
    }

    @Test
    fun loadMemory_EmptyTables_StillMarksLoaded() {
        val storage = SharedFakeStorage()
        val controller = controller(storage, FakeClock(1000L))

        controller.onAction(UiAction.LoadMemory)

        val state = controller.state.value
        assertTrue("an empty viewer is loaded, not perpetually loading", state.memoryLoaded)
        assertTrue(state.memoryNodes.isEmpty())
        assertTrue(state.memorySoulFacts.isEmpty())
    }

    @Test
    fun loadMemory_IncludesLocalOnlySoulFacts_WithLockMarkerForViewer() {
        val storage = SharedFakeStorage()
        val clock = FakeClock(1000L)
        storage.soulFacts = mutableListOf(
            SoulFact("s1", "Preferences", "Prefers tea", 0.9, "chat", "inferred", "active", 100L, localOnly = false),
            SoulFact("s2", "health", "Migraines", 0.9, "onboarding", "approved", "active", 100L, localOnly = true, sensitive = true)
        )

        val controller = controller(storage, clock)
        controller.onAction(UiAction.LoadMemory)

        val state = controller.state.value
        assertEquals("both public and local-only facts are loaded for the viewer", listOf("s1", "s2"), state.memorySoulFacts.map { it.id })
        val healthFact = state.memorySoulFacts.single { it.id == "s2" }
        assertTrue("localOnly is preserved so the UI draws the lock", healthFact.localOnly)
    }

    @Test
    fun archiveNode_ArchivesItem_RefreshesMemory_AndCanBeUndone() = runBlocking {
        val storage = SharedFakeStorage()
        val clock = FakeClock(1000L)
        val n1 = Node("n1", "task", "Pay electricity bill", "active", 5000L)
        storage.activeNodes.add(n1)

        val controller = controller(storage, clock)
        controller.onAction(UiAction.LoadMemory)
        assertEquals(listOf("n1"), controller.state.value.memoryNodes.map { it.id })

        controller.onAction(UiAction.ArchiveNode("n1"))

        assertEquals("Archived item must disappear from active memoryNodes", emptyList<String>(), controller.state.value.memoryNodes.map { it.id })
        assertEquals("archived", storage.getNode("n1")?.status)

        val receipt = controller.state.value.receipts.firstOrNull { it.message.contains("Archived") }
        assertTrue("Must emit receipt for Archive", receipt != null)
        assertTrue("Receipt must carry undo token", receipt?.undoToken != null)

        // Undo the archive
        controller.onAction(UiAction.Undo(receipt!!.undoToken!!))
        assertEquals("active", storage.getNode("n1")?.status)
    }

    @Test
    fun deleteNode_CancelsItem_RefreshesMemory_AndCanBeUndone() = runBlocking {
        val storage = SharedFakeStorage()
        val clock = FakeClock(1000L)
        val n1 = Node("n1", "task", "Pay electricity bill", "active", 5000L)
        storage.activeNodes.add(n1)

        val controller = controller(storage, clock)
        controller.onAction(UiAction.LoadMemory)
        assertEquals(listOf("n1"), controller.state.value.memoryNodes.map { it.id })

        controller.onAction(UiAction.DeleteNode("n1"))

        assertEquals("Deleted item must disappear from active memoryNodes", emptyList<String>(), controller.state.value.memoryNodes.map { it.id })
        assertEquals("cancelled", storage.getNode("n1")?.status)

        val receipt = controller.state.value.receipts.firstOrNull { it.message.contains("Deleted") }
        assertTrue("Must emit receipt for Delete", receipt != null)
        assertTrue("Receipt must carry undo token", receipt?.undoToken != null)

        // Undo the delete
        controller.onAction(UiAction.Undo(receipt!!.undoToken!!))
        assertEquals("active", storage.getNode("n1")?.status)
    }
}
