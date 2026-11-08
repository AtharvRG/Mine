// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.ui

import com.anchor.core.SharedFakeStorage
import com.anchor.core.alarms.AlarmScheduler
import com.anchor.core.alarms.ScheduleResult
import com.anchor.core.capture.QuickCapture
import com.anchor.core.context.ContextBuilder
import com.anchor.core.gateway.Gateway
import com.anchor.core.models.Reminder
import com.anchor.core.provider.HttpTransport
import com.anchor.core.provider.ProviderOrchestrator
import com.anchor.core.provider.RateLimiter
import com.anchor.core.provider.Target
import com.anchor.core.resolver.Resolver
import com.anchor.core.time.DateResolver
import com.anchor.core.time.FakeClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * E-17, the restart half: two captures stored while the app was dead must each be sent as their
 * own turn, in the order they were written.
 *
 * The failure this pins: a turn's assistant reply answers *every* capture before it, so sending
 * only the oldest capture would mark the second one answered without ever showing it to the
 * model. A send dropped because a turn was already streaming has the same effect.
 *
 * A live turn is held in flight on purpose. Under an unconfined dispatcher a send otherwise runs
 * to completion before the next one is called, so drop-if-busy and queueing would look identical
 * and the drop would pass. The park is inside the fake transport's [HttpTransport.postStream],
 * which is the one suspension point both SendMessage paths reach — the composer turn here has a
 * hydrated key and takes the synchronous branch, so a gate on the key read would never fire.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class QuickCaptureDrainTest {

    @Test
    fun twoPendingCaptures_AreEachSentAsTheirOwnTurn_InWriteOrder() = runBlocking {
        val storage = SharedFakeStorage()
        storage.settings["key"] = "test-key"
        val clock = FakeClock(1000L)
        val prompts = mutableListOf<String>()

        val liveTurnParked = CompletableDeferred<Unit>()
        val releaseLiveTurn = CompletableDeferred<Unit>()

        val fakeTransport = object : HttpTransport {
            override suspend fun postStream(target: Target, body: String): Flow<String> = flow {
                // The live turn is the LAST message in the payload; capture one's text is still
                // in the history of capture two's turn, so assert on the tail of the body.
                prompts.add(body.takeLast(400))
                if (body.contains("what's on my plate")) {
                    // Park the live turn: it is in flight (isChatLoading true, stream job alive)
                    // until the drain has been issued below.
                    liveTurnParked.complete(Unit)
                    releaseLiveTurn.await()
                }
                emit("""data: {"choices":[{"delta":{"content":"{\"reply\":\"Noted.\",\"calls\":[]}"}}]}""")
                emit("data: [DONE]")
            }
        }

        val dateResolver = DateResolver(clock)
        val controller = ChatController(
            storage,
            ProviderOrchestrator(RateLimiter { clock.now() }, fakeTransport),
            ContextBuilder(storage, clock, dateResolver, com.anchor.core.nudge.CheckinBuilder(storage, clock, dateResolver)),
            Gateway(storage, Resolver(), dateResolver, clock),
            object : AlarmScheduler {
                override val lastFiredId = MutableStateFlow<String?>(null)
                override fun schedule(reminder: Reminder) = ScheduleResult.Armed
                override fun cancel(reminderId: String) {}
            },
            // Controller work runs on its own unconfined dispatcher, NOT the test's. Sharing the
            // test dispatcher deadlocks: a drain send parks on job.join() and the only thread
            // that could resume the live turn is the one it is blocking.
            CoroutineScope(UnconfinedTestDispatcher()), UnconfinedTestDispatcher(), clock
        )

        val first = QuickCapture.store(storage, "capture one")!!
        val second = QuickCapture.store(storage, "capture two")!!

        // Cold-start shape: the user typed before the controller hydrated, so the composer turn
        // takes the synchronous branch and parks inside the transport.
        controller.onAction(UiAction.UpdateDraft("what's on my plate"))
        controller.onAction(UiAction.SendMessage)
        liveTurnParked.await()
        assertTrue("live turn is in flight", controller.state.value.isChatLoading)

        // Exactly what MainViewModel does at startup, while that turn is still in flight.
        for (id in QuickCapture.pending(storage)) {
            controller.sendText(storage.getRawMessage(id)!!, id)
        }
        // Neither capture may have been sent yet: the live turn still owns the wire.
        assertEquals("drain waits for the live turn", 1, prompts.size)

        releaseLiveTurn.complete(Unit)

        assertEquals("each capture is its own turn", 3, prompts.size)
        assertTrue("live turn first: $prompts", prompts[0].contains("what's on my plate"))
        assertTrue("first capture is its own turn: $prompts", prompts[1].contains("capture one"))
        assertTrue("second capture is its own turn: $prompts", prompts[2].contains("capture two"))
        assertTrue(QuickCapture.pending(storage).isEmpty())
        // The stored rows are the user turns; neither was duplicated.
        assertEquals(1, storage.messages.count { it.id == first })
        assertEquals(1, storage.messages.count { it.id == second })
    }
}
