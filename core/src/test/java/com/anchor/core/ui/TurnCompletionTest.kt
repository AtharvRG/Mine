// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.ui

import com.anchor.core.SharedFakeStorage
import com.anchor.core.alarms.AlarmScheduler
import com.anchor.core.context.ContextBuilder
import com.anchor.core.gateway.Gateway
import com.anchor.core.models.Node
import com.anchor.core.models.Reminder
import com.anchor.core.provider.HttpTransport
import com.anchor.core.provider.ProviderOrchestrator
import com.anchor.core.provider.RateLimiter
import com.anchor.core.provider.Target
import com.anchor.core.resolver.Resolver
import com.anchor.core.time.DateResolver
import com.anchor.core.time.FakeClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TurnCompletionTest {

    @Test
    fun testAmbiguousCancelThenConfirm_EndsTurn() = runBlocking {
        val storage = SharedFakeStorage()
        storage.settings["key"] = "test-key"
        storage.activeNodes.add(Node("n_tomo", "event", "Dinner with Raj", "active"))
        storage.activeNodes.add(Node("n_thur", "event", "Dinner with Sam", "active"))
        
        val clock = FakeClock(1000L)
        var callCount = 0

        val fakeTransport = object : HttpTransport {
            override suspend fun postStream(target: Target, body: String): Flow<String> = flow {
                callCount++
                if (callCount == 1) {
                    emit("""data: {"choices":[{"delta":{"content":"```json\n{\"reply\":\"I see two.\",\"calls\":[{\"tool\":\"propose_change\",\"args\":{\"intent\":\"CANCEL\",\"hint\":\"the dinner\"}}]}\n```"}}]}""")
                } else if (callCount == 2) {
                    val pId = storage.savedProposals.first().id
                    // TASK 1 TEST: This commit should end the turn, preventing a 3rd call
                    emit("""data: {"choices":[{"delta":{"content":"{\"reply\":\"Canceling Raj.\",\"calls\":[{\"tool\":\"confirm_change\",\"args\":{\"proposal\":\"$pId\",\"choice\":\"n_tomo\"}}]}"}}]}""")
                } else {
                    emit("""data: {"choices":[{"delta":{"content":"{\"reply\":\"FAIL: LLM should not be called again!\",\"calls\":[]}"}}]}""")
                }
                emit("data: [DONE]")
            }
        }

        val dateResolver = DateResolver(clock)
        val checkinBuilder = com.anchor.core.nudge.CheckinBuilder(storage, clock, dateResolver)
        val contextBuilder = ContextBuilder(storage, clock, dateResolver, checkinBuilder)

        val controller = ChatController(
            storage, ProviderOrchestrator(RateLimiter { clock.now() }, fakeTransport), contextBuilder,
            Gateway(storage, Resolver(), dateResolver, clock),
            object : AlarmScheduler { override val lastFiredId = MutableStateFlow<String?>(null); override fun schedule(reminder: Reminder) {}; override fun cancel(reminderId: String) {} },
            TestScope(UnconfinedTestDispatcher()), UnconfinedTestDispatcher(), clock
        )

        controller.onAction(UiAction.UpdateDraft("Cancel the dinner"))
        controller.onAction(UiAction.SendMessage)
        assertEquals(1, callCount) // Ambiguous -> Ask User -> Loop Ends

        controller.onAction(UiAction.UpdateDraft("Raj"))
        controller.onAction(UiAction.SendMessage)
        assertEquals(2, callCount) // User Answers -> Commit -> Loop Ends
        
        val active = storage.getActiveNodes(100)
        assertEquals("Exactly one node cancelled", 1, active.size)
        assertEquals("n_thur", active[0].id)
    }

    @Test
    fun testEmptyCallsHidesJson() = runBlocking {
        val storage = SharedFakeStorage()
        storage.settings["key"] = "test-key"
        val clock = FakeClock(1000L)
        
        val fakeTransport = object : HttpTransport {
            override suspend fun postStream(target: Target, body: String): Flow<String> = flow {
                emit("""data: {"choices":[{"delta":{"content":"{\"reply\":\"Got it\",\"calls\":[]}"}}]}""")
                emit("data: [DONE]")
            }
        }

        val dateResolver = DateResolver(clock)
        val checkinBuilder = com.anchor.core.nudge.CheckinBuilder(storage, clock, dateResolver)
        val contextBuilder = ContextBuilder(storage, clock, dateResolver, checkinBuilder)

        val controller = ChatController(
            storage, ProviderOrchestrator(RateLimiter { clock.now() }, fakeTransport), contextBuilder,
            Gateway(storage, Resolver(), dateResolver, clock),
            object : AlarmScheduler { override val lastFiredId = MutableStateFlow<String?>(null); override fun schedule(reminder: Reminder) {}; override fun cancel(reminderId: String) {} },
            TestScope(UnconfinedTestDispatcher()), UnconfinedTestDispatcher(), clock
        )

        controller.onAction(UiAction.UpdateDraft("Hello"))
        controller.onAction(UiAction.SendMessage)
        
        val lastMsg = storage.messages.last().content!!
        assertEquals("Got it", lastMsg)
        assertFalse("Raw JSON leaked into DB", lastMsg.contains("{\"reply\""))
    }

    @Test
    fun testMixedEnvelopeEndsTurn() = runBlocking {
        val storage = SharedFakeStorage()
        storage.settings["key"] = "test-key"
        storage.activeNodes.add(Node("n1", "task", "Buy milk", "active"))
        val clock = FakeClock(1000L)
        var callCount = 0
        
        val fakeTransport = object : HttpTransport {
            override suspend fun postStream(target: Target, body: String): Flow<String> = flow {
                callCount++
                if (callCount == 1) {
                    // Valid commit + Error (missing intent)
                    emit("""data: {"choices":[{"delta":{"content":"{\"reply\":\"Doing it.\",\"calls\":[{\"tool\":\"propose_change\",\"args\":{\"intent\":\"CANCEL\",\"hint\":\"milk\"}}, {\"tool\":\"propose_change\",\"args\":{}}]}"}}]}""")
                } else {
                    emit("""data: {"choices":[{"delta":{"content":"{\"reply\":\"FAIL\",\"calls\":[]}"}}]}""")
                }
                emit("data: [DONE]")
            }
        }

        val dateResolver = DateResolver(clock)
        val checkinBuilder = com.anchor.core.nudge.CheckinBuilder(storage, clock, dateResolver)
        val contextBuilder = ContextBuilder(storage, clock, dateResolver, checkinBuilder)

        val controller = ChatController(
            storage, ProviderOrchestrator(RateLimiter { clock.now() }, fakeTransport), contextBuilder,
            Gateway(storage, Resolver(), dateResolver, clock),
            object : AlarmScheduler { override val lastFiredId = MutableStateFlow<String?>(null); override fun schedule(reminder: Reminder) {}; override fun cancel(reminderId: String) {} },
            TestScope(UnconfinedTestDispatcher()), UnconfinedTestDispatcher(), clock
        )

        controller.onAction(UiAction.UpdateDraft("Cancel milk and also do an error"))
        controller.onAction(UiAction.SendMessage)
        assertEquals("Mixed envelope with a Commit should end turn", 1, callCount)
    }

    @Test
    fun testLoopLimit_FiresWhenToolKeepsLoopingWithoutCommit() = runBlocking {
        val storage = SharedFakeStorage()
        storage.settings["key"] = "test-key"
        val clock = FakeClock(1000L)
        var callCount = 0

        val fakeTransport = object : HttpTransport {
            override suspend fun postStream(target: Target, body: String): Flow<String> = flow {
                callCount++
                // Continuous non-committing tool calls (search_memory with no results)
                emit("""data: {"choices":[{"delta":{"content":"{\"reply\":\"Searching step $callCount...\",\"calls\":[{\"tool\":\"search_memory\",\"args\":{\"query\":\"query_$callCount\"}}]}"}}]}""")
                emit("data: [DONE]")
            }
        }

        val dateResolver = DateResolver(clock)
        val checkinBuilder = com.anchor.core.nudge.CheckinBuilder(storage, clock, dateResolver)
        val contextBuilder = ContextBuilder(storage, clock, dateResolver, checkinBuilder)

        val controller = ChatController(
            storage, ProviderOrchestrator(RateLimiter { clock.now() }, fakeTransport), contextBuilder,
            Gateway(storage, Resolver(), dateResolver, clock),
            object : AlarmScheduler { override val lastFiredId = MutableStateFlow<String?>(null); override fun schedule(reminder: Reminder) {}; override fun cancel(reminderId: String) {} },
            TestScope(UnconfinedTestDispatcher()), UnconfinedTestDispatcher(), clock
        )

        controller.onAction(UiAction.UpdateDraft("Keep searching"))
        controller.onAction(UiAction.SendMessage)

        assertEquals("Should stop after 4 loops", 4, callCount)
        val messages = storage.messages
        val limitMessage = messages.find { it.content == "Loop limit reached." }
        org.junit.Assert.assertNotNull("Must show loop limit reached", limitMessage)
        // Verify unique IDs so no PK collisions
        val ids = messages.map { it.id }
        assertEquals(ids.distinct().size, ids.size)
    }

    @Test
    fun testLoopLimit_DoesNotFireWhenFourthLoopCommits() = runBlocking {
        val storage = SharedFakeStorage()
        storage.settings["key"] = "test-key"
        val clock = FakeClock(1000L)
        var callCount = 0

        val fakeTransport = object : HttpTransport {
            override suspend fun postStream(target: Target, body: String): Flow<String> = flow {
                callCount++
                if (callCount < 4) {
                    emit("""data: {"choices":[{"delta":{"content":"{\"reply\":\"Searching step $callCount...\",\"calls\":[{\"tool\":\"search_memory\",\"args\":{\"query\":\"query_$callCount\"}}]}"}}]}""")
                } else {
                    // 4th loop commits!
                    emit("""data: {"choices":[{"delta":{"content":"{\"reply\":\"Found and creating.\",\"calls\":[{\"tool\":\"propose_change\",\"args\":{\"intent\":\"CREATE\",\"hint\":\"Finish report\"}}]}"}}]}""")
                }
                emit("data: [DONE]")
            }
        }

        val dateResolver = DateResolver(clock)
        val checkinBuilder = com.anchor.core.nudge.CheckinBuilder(storage, clock, dateResolver)
        val contextBuilder = ContextBuilder(storage, clock, dateResolver, checkinBuilder)

        val controller = ChatController(
            storage, ProviderOrchestrator(RateLimiter { clock.now() }, fakeTransport), contextBuilder,
            Gateway(storage, Resolver(), dateResolver, clock),
            object : AlarmScheduler { override val lastFiredId = MutableStateFlow<String?>(null); override fun schedule(reminder: Reminder) {}; override fun cancel(reminderId: String) {} },
            TestScope(UnconfinedTestDispatcher()), UnconfinedTestDispatcher(), clock
        )

        controller.onAction(UiAction.UpdateDraft("Search then create"))
        controller.onAction(UiAction.SendMessage)

        assertEquals(4, callCount)
        val messages = storage.messages
        val limitMessage = messages.find { it.content == "Loop limit reached." }
        org.junit.Assert.assertNull("Loop limit reached must NOT fire if 4th loop commits", limitMessage)
    }
}
