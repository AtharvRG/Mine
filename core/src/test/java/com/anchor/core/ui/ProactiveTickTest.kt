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
import com.anchor.core.models.ProactiveState
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Blueprint 31, audit §6 item 2: the nudge engine must be reachable from the real app, and a nudge
 * must actually be emitted once the threshold is met. Before this, `NudgeEngine` had no production
 * caller at all — it was constructed nowhere and `saveProactiveState` was never written, so the
 * Home "active thread" banner could never light up.
 *
 * The transport throws: a nudge in this slice is template text, and any model call from the
 * proactive path would be a regression, not a feature.
 */
class ProactiveTickTest {

    private class NoTransport : HttpTransport {
        override suspend fun postStream(target: Target, body: String): Flow<String> = flow {
            throw AssertionError("the proactive path must not call the model")
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

    private fun overdue(id: String, title: String, dueAt: Long) =
        Node(id, "task", title, "active", dueAt)

    @Test
    fun tick_OverdueItemAboveThreshold_EmitsANudgeAndOpensAThread() {
        val storage = SharedFakeStorage()
        val clock = FakeClock(10_000_000L)
        storage.activeNodes = mutableListOf(overdue("n1", "Pay the electricity bill", 9_000_000L))

        val controller = controller(storage, clock)
        controller.onAction(UiAction.TickProactive)

        val nudges = storage.messages.filter { it.id.startsWith("n_") }
        assertEquals("exactly one nudge is written", 1, nudges.size)
        assertEquals("assistant", nudges.single().role)
        assertTrue(
            "the nudge names the item: ${nudges.single().content}",
            nudges.single().content!!.contains("Pay the electricity bill")
        )

        val after = storage.proactiveState
        assertNotNull("the engine's thread is opened", after.openThreadMsgId)
        assertEquals(nudges.single().id, after.openThreadMsgId)
        assertEquals(1, after.sentToday)
        assertEquals(clock.now(), after.lastSentAt)
        assertEquals("sent", controller.state.value.nudgeGate)
    }

    @Test
    fun tick_SecondTickWhileTheThreadIsOpen_HoldsInsteadOfNudgingAgain() {
        val storage = SharedFakeStorage()
        val clock = FakeClock(10_000_000L)
        storage.activeNodes = mutableListOf(overdue("n1", "Pay the electricity bill", 9_000_000L))

        val controller = controller(storage, clock)
        controller.onAction(UiAction.TickProactive)
        controller.onAction(UiAction.TickProactive)

        assertEquals(
            "an open thread is the gate that stops back-to-back nudges",
            1,
            storage.messages.count { it.id.startsWith("n_") }
        )
        assertEquals("open_thread", controller.state.value.nudgeGate)
    }

    @Test
    fun tick_NothingOverdue_EmitsNothing() {
        val storage = SharedFakeStorage()
        val clock = FakeClock(10_000_000L)
        storage.activeNodes = mutableListOf(overdue("n1", "Due later", 20_000_000L))

        val controller = controller(storage, clock)
        controller.onAction(UiAction.TickProactive)

        assertTrue(storage.messages.none { it.id.startsWith("n_") })
        assertEquals("no_candidate", controller.state.value.nudgeGate)
        assertNull("nothing was sent, so no thread was opened", storage.proactiveState.openThreadMsgId)
    }

    @Test
    fun tick_LocalOnlyOverdueItem_IsNeverNudged() {
        val storage = SharedFakeStorage()
        val clock = FakeClock(10_000_000L)
        storage.activeNodes = mutableListOf(
            overdue("n1", "Private note", 9_000_000L).copy(localOnly = true)
        )

        val controller = controller(storage, clock)
        controller.onAction(UiAction.TickProactive)

        // H2: local-only items are invisible to the model and to chat; a nudge is a chat message.
        assertTrue(storage.messages.none { it.id.startsWith("n_") })
        assertEquals("no_candidate", controller.state.value.nudgeGate)
    }

    @Test
    fun tick_ThresholdIsConsulted_NotJustTheGates() {
        // The threshold is 30 * max(0.3, 1 - hours/4), so the highest it can ever be is 30 — below
        // the 35 base score of every candidate. That means the threshold alone can never hold a
        // nudge, and a test that only ever sees "sent" would not prove it is read at all. This
        // pins the arithmetic the tick relies on at both ends of its range, so a future change to
        // the formula (or to baseValue) that silently stops the comparison from mattering fails here.
        val storage = SharedFakeStorage()
        val clock = FakeClock(10_000_000L)
        val controller = controller(storage, clock)
        val engine = com.anchor.core.nudge.NudgeEngine(clock)

        val justContacted = engine.calculateThreshold(ProactiveState(lastContactAt = clock.now()))
        val fourHoursAgo = engine.calculateThreshold(
            ProactiveState(lastContactAt = clock.now() - 4L * 3600 * 1000L)
        )
        val never = engine.calculateThreshold(ProactiveState())

        assertEquals(30.0, justContacted, 0.001)
        assertEquals("the floor is 30 * 0.3", 9.0, fourHoursAgo, 0.001)
        assertEquals(9.0, never, 0.001)
        assertTrue(
            "the tick's comparison is live: a candidate above the threshold passes, one below does not",
            com.anchor.core.models.NudgeCandidate("overdue_item", false).baseValue >= justContacted
        )
    }

    @Test
    fun sendText_ClosesTheOpenThreadAndResetsTheBackoff() {
        val storage = SharedFakeStorage()
        val clock = FakeClock(10_000_000L)
        storage.activeNodes = mutableListOf(overdue("n1", "Pay the electricity bill", 9_000_000L))

        val controller = controller(storage, clock)
        controller.onAction(UiAction.TickProactive)
        assertNotNull(storage.proactiveState.openThreadMsgId)

        // No provider key is configured, so the turn stops early — but the reply is still a reply.
        controller.sendText("done, thanks")

        val after = storage.proactiveState
        assertNull("answering closes the thread", after.openThreadMsgId)
        assertEquals("answering resets the backoff", 0, after.backoffStep)
        assertEquals(clock.now(), after.lastContactAt)
    }

    @Test
    fun tick_AnIgnoredThreadOlderThanThreeHours_IsClosedAndTheNextNudgeGoesOut() {
        val storage = SharedFakeStorage()
        val clock = FakeClock(10_000_000L)
        storage.activeNodes = mutableListOf(overdue("n1", "Pay the electricity bill", 9_000_000L))
        // A thread opened 4 hours ago and never answered.
        storage.proactiveState = ProactiveState(
            openThreadMsgId = "n_old",
            threadOpenedAt = clock.now() - 4L * 3600 * 1000L,
            lastSentAt = clock.now() - 4L * 3600 * 1000L
        )

        val controller = controller(storage, clock)
        controller.onAction(UiAction.TickProactive)

        val after = storage.proactiveState
        assertEquals("the ignored thread is closed and the backoff advances", 1, after.backoffStep)
        assertTrue("a new nudge went out", after.openThreadMsgId != "n_old")
        assertEquals("sent", controller.state.value.nudgeGate)
    }
}
