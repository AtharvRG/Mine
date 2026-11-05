// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.ui

import com.anchor.core.SharedFakeStorage
import com.anchor.core.alarms.AlarmScheduler
import com.anchor.core.context.ContextBuilder
import com.anchor.core.gateway.Gateway
import com.anchor.core.models.Node
import com.anchor.core.models.RawEvent
import com.anchor.core.models.Reminder
import com.anchor.core.nudge.CheckinBuilder
import com.anchor.core.provider.HttpTransport
import com.anchor.core.provider.ProviderOrchestrator
import com.anchor.core.provider.RateLimiter
import com.anchor.core.provider.Target
import com.anchor.core.resolver.Resolver
import com.anchor.core.time.DateResolver
import com.anchor.core.time.FakeClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class R4StaleItemsTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val controllerScope = CoroutineScope(testDispatcher + SupervisorJob())
    private lateinit var clock: FakeClock
    private lateinit var storage: SharedFakeStorage
    private lateinit var gateway: Gateway
    private lateinit var controller: ChatController

    @Before
    fun setUp() {
        clock = FakeClock(1700000000000L) // Reference timestamp
        storage = SharedFakeStorage()
        val resolver = Resolver()
        val dateResolver = DateResolver(clock)
        gateway = Gateway(storage, resolver, dateResolver, clock)

        val checkinBuilder = CheckinBuilder(storage, clock, dateResolver)
        val contextBuilder = ContextBuilder(storage, clock, dateResolver, checkinBuilder)
        val fakeTransport = object : HttpTransport {
            override suspend fun postStream(target: Target, body: String): Flow<String> = emptyFlow()
        }
        val orchestrator = ProviderOrchestrator(RateLimiter { clock.now() }, fakeTransport)
        val dummyScheduler = object : AlarmScheduler {
            override val lastFiredId = MutableStateFlow<String?>(null)
            override fun schedule(reminder: Reminder) = com.anchor.core.alarms.ScheduleResult.Armed
            override fun cancel(reminderId: String) {}
        }

        controller = ChatController(
            storage = storage,
            orchestrator = orchestrator,
            contextBuilder = contextBuilder,
            gateway = gateway,
            alarmScheduler = dummyScheduler,
            coroutineScope = controllerScope,
            defaultDispatcher = testDispatcher,
            clock = clock
        )
    }

    @Test
    fun testStaleItems_SurfacedInUiStateAndInboxCount() = runTest(testDispatcher) {
        val now = clock.now()
        val thirtyOneDaysAgo = now - (31L * 24 * 3600 * 1000L)

        // Seed 5 stale nodes (idle 31 days)
        for (i in 1..5) {
            val node = Node("n_stale_$i", "task", "Stale Task $i", "active", createdAt = thirtyOneDaysAgo, updatedAt = thirtyOneDaysAgo)
            storage.createNodeAndCommit(node, RawEvent("u_s_$i", "CREATE", "node", "n_stale_$i"), null)
        }

        // Seed 1 active non-stale node
        val freshNode = Node("n_fresh", "task", "Fresh Task", "active", createdAt = now - 1000L, updatedAt = now - 1000L)
        storage.createNodeAndCommit(freshNode, RawEvent("u_fresh", "CREATE", "node", "n_fresh"), null)

        controller.onAction(UiAction.RefreshStats)

        val state = controller.state.value
        assertEquals("staleItems must contain exactly the 5 idle items", 5, state.staleItems.size)
        assertEquals("inboxCount must include staleItems", 5, state.inboxCount)
    }

    @Test
    fun testKeepStale_TouchesUpdatedAt_LeavesStaleList_AndEnablesUndo() = runTest(testDispatcher) {
        val now = clock.now()
        val thirtyOneDaysAgo = now - (31L * 24 * 3600 * 1000L)
        val node = Node("n_keep", "task", "Keep Me", "active", createdAt = thirtyOneDaysAgo, updatedAt = thirtyOneDaysAgo)
        storage.createNodeAndCommit(node, RawEvent("u_k", "CREATE", "node", "n_keep"), null)

        controller.onAction(UiAction.RefreshStats)
        assertEquals(1, controller.state.value.staleItems.size)

        // Keep stale item
        controller.onAction(UiAction.KeepStale("n_keep"))

        val stateAfterKeep = controller.state.value
        assertEquals("staleItems must be empty after KeepStale", 0, stateAfterKeep.staleItems.size)
        assertEquals(0, stateAfterKeep.inboxCount)

        val updatedNode = storage.getNode("n_keep")
        assertNotNull(updatedNode)
        assertEquals(now, updatedNode!!.updatedAt)

        // Verify receipt with undo token
        val receipt = stateAfterKeep.receipts.lastOrNull()
        assertNotNull(receipt)
        assertTrue(receipt!!.message.contains("Kept: Keep Me"))
        val undoToken = receipt.undoToken
        assertNotNull(undoToken)

        // Undo KeepStale restores old updatedAt -> returns to stale
        controller.onAction(UiAction.Undo(undoToken!!))

        val stateAfterUndo = controller.state.value
        assertEquals("staleItems must re-surface item after undo", 1, stateAfterUndo.staleItems.size)
        assertEquals(thirtyOneDaysAgo, storage.getNode("n_keep")?.updatedAt)
    }

    @Test
    fun testCancelStale_CancelsNode_LeavesStaleList_AndEnablesUndo() = runTest(testDispatcher) {
        val now = clock.now()
        val thirtyOneDaysAgo = now - (31L * 24 * 3600 * 1000L)
        val node = Node("n_cancel", "task", "Cancel Me", "active", createdAt = thirtyOneDaysAgo, updatedAt = thirtyOneDaysAgo)
        val reminder = Reminder("r_cancel", "Cancel Me", now + 10000L, "n_cancel")
        storage.createNodeAndCommit(node, RawEvent("u_c", "CREATE", "node", "n_cancel"), reminder)

        controller.onAction(UiAction.RefreshStats)
        assertEquals(1, controller.state.value.staleItems.size)

        // Cancel stale item
        controller.onAction(UiAction.CancelStale("n_cancel"))

        val stateAfterCancel = controller.state.value
        assertEquals(0, stateAfterCancel.staleItems.size)
        assertEquals("cancelled", storage.getNode("n_cancel")?.status)
        assertEquals(0, storage.getPendingReminders().size)

        val receipt = stateAfterCancel.receipts.lastOrNull()
        assertNotNull(receipt)
        assertTrue(receipt!!.message.contains("Cancelled: Cancel Me"))
        val undoToken = receipt.undoToken
        assertNotNull(undoToken)

        // Undo CancelStale restores active status and reminder
        controller.onAction(UiAction.Undo(undoToken!!))

        val stateAfterUndo = controller.state.value
        assertEquals(1, stateAfterUndo.staleItems.size)
        val restoredNode = storage.getNode("n_cancel")
        assertEquals("active", restoredNode?.status)
        assertEquals(1, storage.getPendingReminders().size)
    }

    @Test
    fun testCancelStale_WithLegacyReminderId_CleansUpLegacyReminder() = runTest(testDispatcher) {
        val now = clock.now()
        val thirtyOneDaysAgo = now - (31L * 24 * 3600 * 1000L)
        val node = Node("n_legacy", "task", "Legacy Task", "active", createdAt = thirtyOneDaysAgo, updatedAt = thirtyOneDaysAgo)
        // Legacy reminder has id "r_n_legacy" but nodeId is empty or unset
        val legacyReminder = Reminder("r_n_legacy", "Legacy Task", now + 10000L, "")
        storage.createNodeAndCommit(node, RawEvent("u_legacy", "CREATE", "node", "n_legacy"), legacyReminder)

        controller.onAction(UiAction.RefreshStats)
        assertEquals(1, controller.state.value.staleItems.size)
        assertEquals(1, storage.getPendingReminders().size)

        // Cancel stale item
        controller.onAction(UiAction.CancelStale("n_legacy"))

        assertEquals(0, controller.state.value.staleItems.size)
        assertEquals("cancelled", storage.getNode("n_legacy")?.status)
        assertEquals(0, storage.getPendingReminders().size)
    }
}
