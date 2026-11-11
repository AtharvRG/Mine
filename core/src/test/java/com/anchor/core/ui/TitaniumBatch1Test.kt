// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.ui

import com.anchor.core.SharedFakeStorage
import com.anchor.core.alarms.AlarmScheduler
import com.anchor.core.alarms.ScheduleResult
import com.anchor.core.context.ContextBuilder
import com.anchor.core.gateway.Gateway
import com.anchor.core.models.OutboxJob
import com.anchor.core.models.Reminder
import com.anchor.core.models.SoulFact
import com.anchor.core.nudge.CheckinBuilder
import com.anchor.core.provider.HttpTransport
import com.anchor.core.provider.OfflineException
import com.anchor.core.provider.ProviderOrchestrator
import com.anchor.core.provider.RateLimiter
import com.anchor.core.provider.Target
import com.anchor.core.resolver.Resolver
import com.anchor.core.time.DateResolver
import com.anchor.core.time.FakeClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

@OptIn(ExperimentalCoroutinesApi::class)
class TitaniumBatch1Test {

    private class FakeScheduler : AlarmScheduler {
        override val lastFiredId = MutableStateFlow<String?>(null)
        override fun schedule(reminder: Reminder) = ScheduleResult.Armed
        override fun cancel(reminderId: String) {}
    }

    private class ThrowingOfflineTransport : HttpTransport {
        override suspend fun postStream(target: Target, body: String): Flow<String> = flow {
            throw OfflineException("Airplane mode engaged mid-stream")
        }
    }

    private fun createController(
        storage: SharedFakeStorage,
        clock: FakeClock,
        transport: HttpTransport,
        networkCheck: () -> Boolean = { true }
    ): ChatController {
        val orchestrator = ProviderOrchestrator(RateLimiter { clock.now() }, transport)
        val dateResolver = DateResolver(clock)
        val checkinBuilder = CheckinBuilder(storage, clock, dateResolver)
        val contextBuilder = ContextBuilder(storage, clock, dateResolver, checkinBuilder)
        val scope = CoroutineScope(UnconfinedTestDispatcher())
        return ChatController(
            storage = storage,
            orchestrator = orchestrator,
            contextBuilder = contextBuilder,
            gateway = Gateway(storage, Resolver(), dateResolver, clock),
            alarmScheduler = FakeScheduler(),
            coroutineScope = scope,
            defaultDispatcher = UnconfinedTestDispatcher(),
            clock = clock,
            dateResolver = dateResolver,
            networkCheck = networkCheck
        )
    }

    /**
     * B1-03: Background failures (such as TickProactive or DrainQueuedTurns or Setting changes)
     * must NEVER populate globalError, because MainActivity renders globalError as a fatal,
     * non-dismissible full-screen lock.
     */
    @Test
    fun testBackgroundFailure_DoesNotSetGlobalError() = runBlocking {
        val clock = FakeClock(1_000_000L)
        val storage = object : SharedFakeStorage(clock) {
            override suspend fun dropStaleOutbox(now: Long): Int {
                throw IllegalStateException("Simulated transient outbox disk failure")
            }
        }
        storage.settings["key"] = "test-key"
        val controller = createController(storage, clock, ThrowingOfflineTransport())

        // Initial state loaded without issue
        assertNull("Controller init must not set globalError on healthy load", controller.state.value.globalError)

        // Trigger DrainQueuedTurns
        controller.onAction(UiAction.DrainQueuedTurns)

        // Must NOT brick UI via globalError
        assertNull("B1-03: DrainQueuedTurns failure must not set globalError", controller.state.value.globalError)
        assertTrue(
            "B1-03: Transient error receipt should inform the user",
            controller.state.value.receipts.any { it.isError && it.message.contains("Could not send saved messages") }
        )

        // Trigger TickProactive with broken storage
        controller.onAction(UiAction.TickProactive)
        assertNull("B1-03: TickProactive failure must not set globalError", controller.state.value.globalError)
        assertEquals("error", controller.state.value.nudgeGate)
    }

    /**
     * B1-04: Live composer sends (existingUserMsgId == null) that fail with OfflineException
     * mid-stream must be enqueued into the outbox with activeUserMsgId, so the message is not lost.
     */
    @Test
    fun testComposerSend_DropsOfflineMidStream_EnqueuesToOutbox() = runBlocking {
        val clock = FakeClock(1_000_000L)
        val storage = SharedFakeStorage(clock)
        storage.settings["key"] = "test-key"
        val online = AtomicBoolean(true) // Oracle says online initially, but transport throws OfflineException
        val controller = createController(storage, clock, ThrowingOfflineTransport(), networkCheck = { online.get() })

        // Send a message from the live composer (existingUserMsgId is null)
        controller.sendText("Remember to pick up the keys tomorrow")

        // Wait until background turn handling completes
        while (controller.state.value.isChatLoading) {
            kotlinx.coroutines.delay(20)
        }

        // OfflineException occurred during stream
        val outboxJobs = storage.getPendingOutbox(OutboxJob.JOB_CHAT_TURN)
        assertEquals("B1-04: Exactly one chat turn must be queued to outbox on mid-stream offline drop", 1, outboxJobs.size)

        val job = outboxJobs.single()
        assertNotNull("Job idemKey must not be null", job.idemKey)
        assertTrue("Generated msg_id must have user prefix u_", job.idemKey.startsWith("u_"))
        assertTrue("Job payload must contain msg_id per blueprint 41 §4", job.payloadJson.contains(job.idemKey))

        // The user's row was stored in raw_message and matches the outbox job
        val userRow = storage.messages.firstOrNull { it.id == job.idemKey }
        assertNotNull("User raw_message must exist matching the enqueued outbox idem_key", userRow)
        assertEquals("Remember to pick up the keys tomorrow", userRow!!.content)
    }

    /**
     * B1-05: Missing exception boundaries in UI actions:
     * When storage throws an unexpected exception during UI actions (AcceptSoulFact, RejectSoulFact,
     * Undo, ArchiveNode, DeleteNode, LoadDay, etc.), the action must not crash unhandled,
     * but catch the exception, emit a transient error receipt, and keep the app functional.
     */
    @Test
    fun testUiAction_StorageException_EmitsErrorReceiptWithoutCrash() = runBlocking {
        val clock = FakeClock(1_000_000L)
        val storage = object : SharedFakeStorage(clock) {
            override suspend fun updateSoulFactStatusAndCommit(id: String, status: String, event: com.anchor.core.models.RawEvent) {
                throw IllegalStateException("Simulated SQLiteDiskIOException on soul fact commit")
            }
        }
        val fact = SoulFact(
            id = "s_test_fact",
            category = "preference",
            statement = "Prefers dark roast coffee",
            confidence = 0.9,
            source = "chat",
            origin = "inferred",
            status = "proposed",
            createdAt = clock.now()
        )
        storage.saveSoulFact(fact)

        val controller = createController(storage, clock, ThrowingOfflineTransport())

        // Action AcceptSoulFact fails due to storage throw
        controller.onAction(UiAction.AcceptSoulFact("s_test_fact"))

        // Assert no unhandled crash, globalError is untouched, and transient error receipt is emitted
        assertNull("B1-05: Storage failure in AcceptSoulFact must not set globalError", controller.state.value.globalError)
        assertTrue(
            "B1-05: Error receipt must be emitted for failed action",
            controller.state.value.receipts.any { it.isError && it.message.contains("Could not accept fact") }
        )
    }
}
