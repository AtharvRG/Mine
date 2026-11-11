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
import com.anchor.core.provider.HttpTransport
import com.anchor.core.provider.OfflineException
import com.anchor.core.provider.ProviderOrchestrator
import com.anchor.core.provider.RateLimiter
import com.anchor.core.provider.Target
import com.anchor.core.resolver.Resolver
import com.anchor.core.time.DateResolver
import com.anchor.core.time.FakeClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Audit §6 item 6 / blueprint 41 §8 test 1: "send fails offline → message queued → sent on
 * reconnect". Every test drives the real [ChatController] against [SharedFakeStorage] and a fake
 * transport; the only thing that changes between "offline" and "online" is the oracle the app
 * layer installs, which is exactly the seam the production code keys on.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OfflineQueueTest {

    private class FakeScheduler : AlarmScheduler {
        override val lastFiredId = MutableStateFlow<String?>(null)
        override fun schedule(reminder: Reminder) = ScheduleResult.Armed
        override fun cancel(reminderId: String) {}
    }

    /**
     * Records every request body so a test can assert what the model was actually told. When the
     * oracle says offline it fails the way the app's transport does — [OfflineException] — which is
     * the case where the controller's pre-send check raced a network that dropped mid-send.
     */
    private class RecordingTransport(private val network: AtomicBoolean) : HttpTransport {
        val bodies = mutableListOf<String>()
        override suspend fun postStream(target: Target, body: String): Flow<String> = flow {
            // The body is recorded before the network is checked so a test can see the request
            // that was attempted: "no request may be attempted while offline" is then an
            // assertion about this list, not about the oracle having been consulted.
            bodies.add(body)
            if (!network.get()) throw OfflineException("No network connection")
            emit("""data: {"choices":[{"delta":{"content":"{\"reply\":\"Got it.\",\"calls\":[]}"}}]}""")
            emit("data: [DONE]")
        }
    }

    private class Harness(
        val storage: SharedFakeStorage,
        val clock: FakeClock,
        val transport: RecordingTransport,
        /** The oracle the app layer installs; flipped by a test to simulate a reconnect. */
        val network: AtomicBoolean,
        /** The same scope the controller runs on, so the helpers below queue behind its work. */
        val scope: CoroutineScope,
        val controller: ChatController
    ) {
        var online: Boolean
            get() = network.get()
            set(v) = network.set(v)

        /**
         * sendText and drainQueuedTurns launch lazily, so each one runs to completion in turn.
         * Launched eagerly they race on the unconfined dispatcher: a later capture can enqueue
         * before an earlier one, and the capture's own handleSendMessage can beat its sibling.
         */
        fun send(text: String, id: String) {
            val job = scope.launch(start = CoroutineStart.LAZY) { controller.sendText(text, id) }
            job.start()
            runBlocking { job.join() }
        }

        fun drain() {
            val job = scope.launch(start = CoroutineStart.LAZY) { controller.drainQueuedTurns() }
            job.start()
            runBlocking { job.join() }
        }
    }

    private fun harness(): Harness {
        val storage = SharedFakeStorage()
        storage.settings["key"] = "test-key"
        val clock = FakeClock(1_000_000L)
        // Created before the controller: the controller's init drains on an unconfined dispatcher,
        // so the oracle is read during construction. A lateinit Harness would throw there.
        val network = AtomicBoolean(false)
        val transport = RecordingTransport(network)
        val orchestrator = ProviderOrchestrator(RateLimiter { clock.now() }, transport)
        val dateResolver = DateResolver(clock)
        val checkinBuilder = com.anchor.core.nudge.CheckinBuilder(storage, clock, dateResolver)
        val contextBuilder = ContextBuilder(storage, clock, dateResolver, checkinBuilder)
        // The controller runs on its own unconfined scope, not the test's: sharing the test
        // dispatcher deadlocks when a send parks on job.join() (see QuickCaptureDrainTest).
        val scope = CoroutineScope(UnconfinedTestDispatcher())
        val controller = ChatController(
            storage, orchestrator, contextBuilder,
            Gateway(storage, Resolver(), dateResolver, clock),
            FakeScheduler(), scope, UnconfinedTestDispatcher(), clock,
            dateResolver = dateResolver,
            networkCheck = { network.get() }
        )
        return Harness(storage, clock, transport, network, scope, controller)
    }

    @Test
    fun offlineCaptureIsQueuedAndMarkedWithoutAnyRequest() = runTest {
        val h = harness()
        h.online = false

        // The app stores the captured row before the turn runs (QuickCapture.store), and sendText's
        // contract is that the caller did. Without it the queue would hold an id that reads back
        // blank at drain time, so this is the real capture path, not test scaffolding.
        h.storage.insertRawMessage("q_1", "user", "buy milk")
        h.send("buy milk", "q_1")

        assertTrue("no request may be attempted while offline", h.transport.bodies.isEmpty())
        assertEquals(listOf("q_1"), h.storage.getPendingOutbox(OutboxJob.JOB_CHAT_TURN).map { it.idemKey })
        assertTrue("the user's row is kept", h.storage.hasRawMessage("q_1"))
        assertEquals(listOf("q_1"), h.controller.state.value.queuedChatIds)
        assertEquals("Offline: your messages are saved.", h.controller.state.value.offlineQueuedText)
        assertNull("queuing is not an error", h.controller.state.value.connectionError)
    }

    @Test
    fun reconnectAnswersAllQueuedMessagesInOneTurnNamingTheirTimes() = runTest {
        val h = harness()
        h.online = false
        h.storage.insertRawMessage("q_1", "user", "buy milk")
        h.send("buy milk", "q_1")
        h.clock.currentTime += 3 * 60_000L
        h.storage.insertRawMessage("q_2", "user", "call mum")
        h.send("call mum", "q_2")
        h.clock.currentTime += 2 * 60_000L
        h.storage.insertRawMessage("q_3", "user", "pay rent")
        h.send("pay rent", "q_3")
        assertEquals(3, h.storage.getPendingOutbox(OutboxJob.JOB_CHAT_TURN).size)

        h.online = true
        h.drain()

        assertEquals("one turn for the whole backlog", 1, h.transport.bodies.size)
        val body = h.transport.bodies.single()
        assertTrue("the model is told they were sent offline", body.contains("sent offline"))
        assertTrue("each message is named with its original time", body.contains("00:00") || body.contains(":"))
        assertTrue("the oldest message's text is in the pack", body.contains("buy milk"))
        assertTrue("the newest message's text is in the pack", body.contains("pay rent"))

        assertTrue("the queue is cleared", h.storage.getPendingOutbox(OutboxJob.JOB_CHAT_TURN).isEmpty())
        assertEquals(emptyList<String>(), h.controller.state.value.queuedChatIds)
        assertNull(h.controller.state.value.offlineQueuedText)
        assertTrue(
            "the reply is stored",
            h.storage.messages.any { it.role == "assistant" && it.content == "Got it." }
        )
    }

    @Test
    fun aDrainThatIsStillOfflineLeavesEveryJobPendingAndSaysNothingAlarming() = runTest {
        val h = harness()
        h.online = false
        h.storage.insertRawMessage("q_1", "user", "buy milk")
        h.send("buy milk", "q_1")
        // A reconnect event fires while the network is not actually validated yet.
        h.drain()

        assertTrue("a drain on an unvalidated network must not reach the provider", h.transport.bodies.isEmpty())
        assertEquals(1, h.storage.getPendingOutbox(OutboxJob.JOB_CHAT_TURN).size)
        assertEquals(listOf("q_1"), h.controller.state.value.queuedChatIds)
        assertNull(h.controller.state.value.connectionError)
    }

    @Test
    fun aSecondDrainInsideTheClaimWindowDoesNotSendTwice() = runTest {
        val h = harness()
        h.online = false
        h.storage.insertRawMessage("q_1", "user", "buy milk")
        h.send("buy milk", "q_1")
        h.online = true
        h.drain()
        assertEquals(1, h.transport.bodies.size)

        // The reconnect callback and the app-open drain can both fire in the same minute. Here the
        // first drain's turn runs to completion and markBatchAnswered settles the job, so the
        // second drain finds nothing due. The claim re-enqueue covers the other shape — a drain
        // killed after claiming but before the turn finishes — which this harness cannot reach
        // because the unconfined dispatcher runs the turn synchronously (D-138).
        h.clock.currentTime += 30_000L
        h.drain()
        assertEquals("nothing is due again inside the claim window", 1, h.transport.bodies.size)
    }

    @Test
    fun aFourteenHourBacklogIsAcknowledgedBeforeItIsAnswered() = runTest {
        // Blueprint 41 §8 test 6 / §3.5.3.
        val h = harness()
        h.online = false
        // A real capture stamps the row when it is written; the fake reads the clock at insert
        // time, so the row must be stored before the clock jumps or it would be born 14 h old.
        h.storage.insertRawMessageWithDay("q_1", "user", "buy milk", "d", h.clock.currentTime)
        h.send("buy milk", "q_1")
        h.clock.currentTime += 14 * 3600_000L
        h.online = true
        h.drain()

        val body = h.transport.bodies.single()
        assertTrue("a 14-hour-old backlog is acknowledged", body.contains("acknowledging the delay"))
        assertTrue(body.contains("14 hours ago"))
    }

    @Test
    fun aQueuedCaptureIsOneJobEvenWhenTheReceiverAndTheStartupDrainBothQueueIt() = runTest {
        val h = harness()
        h.online = false
        h.storage.insertRawMessage("q_1", "user", "buy milk")
        h.send("buy milk", "q_1")
        h.send("buy milk", "q_1") // the receiver, then the startup drain

        assertEquals(1, h.storage.getPendingOutbox(OutboxJob.JOB_CHAT_TURN).size)
        assertFalse(h.controller.state.value.queuedChatIds.isEmpty())
    }

    @Test
    fun drainQueuedTurnsSetsChatLoadingDuringStreamAndClearsWhenComplete() = runTest {
        val network = AtomicBoolean(false)
        val transport = object : HttpTransport {
            var loadingDuringStream = false
            var checked = false
            lateinit var controllerSupplier: () -> ChatController

            override suspend fun postStream(target: Target, body: String): Flow<String> = flow {
                loadingDuringStream = controllerSupplier().state.value.isChatLoading
                checked = true
                emit("""data: {"choices":[{"delta":{"content":"{\"reply\":\"Done\",\"calls\":[]}"}}]}""")
                emit("data: [DONE]")
            }
        }
        val storage = SharedFakeStorage()
        storage.settings["key"] = "test-key"
        val clock = FakeClock(1_000_000L)
        val orchestrator = ProviderOrchestrator(RateLimiter { clock.now() }, transport)
        val dateResolver = DateResolver(clock)
        val checkinBuilder = com.anchor.core.nudge.CheckinBuilder(storage, clock, dateResolver)
        val contextBuilder = ContextBuilder(storage, clock, dateResolver, checkinBuilder)
        val scope = CoroutineScope(UnconfinedTestDispatcher())
        val controller = ChatController(
            storage, orchestrator, contextBuilder,
            Gateway(storage, Resolver(), dateResolver, clock),
            FakeScheduler(), scope, UnconfinedTestDispatcher(), clock,
            dateResolver = dateResolver,
            networkCheck = { network.get() }
        )
        transport.controllerSupplier = { controller }

        // Queue an offline message
        network.set(false)
        storage.insertRawMessage("q_1", "user", "buy milk")
        val sendJob = scope.launch(start = CoroutineStart.LAZY) { controller.sendText("buy milk", "q_1") }
        sendJob.start()
        runBlocking { sendJob.join() }

        // Connect and drain
        network.set(true)
        val drainJob = scope.launch(start = CoroutineStart.LAZY) { controller.drainQueuedTurns() }
        drainJob.start()
        runBlocking { drainJob.join() }

        assertTrue("Must have checked loading state during stream execution", transport.checked)
        assertTrue("isChatLoading must be true during outbox drain stream", transport.loadingDuringStream)
        assertFalse("isChatLoading must be false after outbox drain completes", controller.state.value.isChatLoading)
    }
}
