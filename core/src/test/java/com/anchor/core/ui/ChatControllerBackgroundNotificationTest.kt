// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.ui

import com.anchor.core.SharedFakeStorage
import com.anchor.core.alarms.AlarmScheduler
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatControllerBackgroundNotificationTest {

    @Test
    fun testOnBackgroundReplyCompleted_InvokedOnAssistantTurnCompletion() = runBlocking {
        val storage = SharedFakeStorage()
        storage.settings["key"] = "test-key"
        val clock = FakeClock(1000L)
        val testDispatcher = UnconfinedTestDispatcher()
        val testScope = TestScope(testDispatcher)

        val fakeTransport = object : HttpTransport {
            override suspend fun postStream(target: Target, body: String): Flow<String> = flow {
                emit("""data: {"choices":[{"delta":{"content":"```json\n{\"reply\":\"I can help you with your tasks.\",\"calls\":[]}\n```"}}]}""")
            }
        }

        val rateLimiter = RateLimiter(clock = { clock.now() })
        val orchestrator = ProviderOrchestrator(rateLimiter, fakeTransport)
        val dateResolver = DateResolver(clock, { false })
        val contextBuilder = ContextBuilder(storage, clock, dateResolver, com.anchor.core.nudge.CheckinBuilder(storage, clock, dateResolver))
        val gateway = Gateway(storage, Resolver(), dateResolver, clock)
        val fakeScheduler = object : AlarmScheduler {
            override val lastFiredId = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
            override fun schedule(reminder: Reminder) = com.anchor.core.alarms.ScheduleResult.Armed
            override fun cancel(reminderId: String) {}
        }

        val controller = ChatController(
            storage = storage,
            orchestrator = orchestrator,
            contextBuilder = contextBuilder,
            gateway = gateway,
            alarmScheduler = fakeScheduler,
            coroutineScope = testScope,
            defaultDispatcher = testDispatcher,
            clock = clock,
            dateResolver = dateResolver,
            networkCheck = { true }
        )

        var notifiedReply: String? = null
        controller.onBackgroundReplyCompleted = { reply ->
            notifiedReply = reply
        }

        controller.sendText("Hello", null)

        assertNotNull("Expected background reply callback to be invoked", notifiedReply)
        assertEquals("I can help you with your tasks.", notifiedReply)
    }

    @Test
    fun testOnBackgroundReplyCompleted_InvokedOnOfflineQuickAdd() = runBlocking {
        val storage = SharedFakeStorage()
        storage.settings["key"] = "test-key"
        val clock = FakeClock(1000L)
        val testDispatcher = UnconfinedTestDispatcher()
        val testScope = TestScope(testDispatcher)

        val fakeTransport = object : HttpTransport {
            override suspend fun postStream(target: Target, body: String): Flow<String> = flow {
                emit("""data: {"choices":[{"delta":{"content":""}}]}""")
            }
        }

        val rateLimiter = RateLimiter(clock = { clock.now() })
        val orchestrator = ProviderOrchestrator(rateLimiter, fakeTransport)
        val dateResolver = DateResolver(clock, { false })
        val contextBuilder = ContextBuilder(storage, clock, dateResolver, com.anchor.core.nudge.CheckinBuilder(storage, clock, dateResolver))
        val gateway = Gateway(storage, Resolver(), dateResolver, clock)
        val fakeScheduler = object : AlarmScheduler {
            override val lastFiredId = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
            override fun schedule(reminder: Reminder) = com.anchor.core.alarms.ScheduleResult.Armed
            override fun cancel(reminderId: String) {}
        }

        val controller = ChatController(
            storage = storage,
            orchestrator = orchestrator,
            contextBuilder = contextBuilder,
            gateway = gateway,
            alarmScheduler = fakeScheduler,
            coroutineScope = testScope,
            defaultDispatcher = testDispatcher,
            clock = clock,
            dateResolver = dateResolver,
            networkCheck = { false } // Offline!
        )

        var notifiedReply: String? = null
        controller.onBackgroundReplyCompleted = { reply ->
            notifiedReply = reply
        }

        // Send a quick-add parsable intent offline
        controller.sendText("remind me in 10 minutes to buy milk", null)

        assertNotNull("Expected background reply callback for offline quick add", notifiedReply)
        assertTrue("Expected scheduled reply text, got $notifiedReply", notifiedReply!!.contains("Reminder scheduled: \"buy milk\""))
    }
}
