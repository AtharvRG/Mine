// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.ui

import com.anchor.core.SharedFakeStorage
import com.anchor.core.alarms.AlarmScheduler
import com.anchor.core.alarms.ScheduleResult
import com.anchor.core.context.ContextBuilder
import com.anchor.core.gateway.Gateway
import com.anchor.core.models.Reminder
import com.anchor.core.nudge.CheckinBuilder
import com.anchor.core.provider.Http429Exception
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
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProviderFallbackChainTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val controllerScope = CoroutineScope(testDispatcher + SupervisorJob())

    private class NoopScheduler : AlarmScheduler {
        override val lastFiredId = MutableStateFlow<String?>(null)
        override fun schedule(reminder: Reminder): ScheduleResult = ScheduleResult.Armed
        override fun cancel(reminderId: String) {}
    }

    @Test
    fun testChatController_FallsBackToSecondaryProvider_WhenPrimaryFailsWith429() = runTest(testDispatcher) {
        val clock = FakeClock(1_000_000L)
        val storage = SharedFakeStorage(clock)
        storage.saveSetting("ep", "https://primary.ai/v1/chat/completions")
        storage.saveSetting("key", "primary-key")
        storage.saveSetting("mod", "primary-model")
        storage.saveSetting("provider.fallback.endpoint", "https://fallback.ai/v1/chat/completions")
        storage.saveSetting("provider.fallback.key", "fallback-key")

        val dateResolver = DateResolver(clock)
        val gateway = Gateway(storage, Resolver(), dateResolver, clock)
        val checkinBuilder = CheckinBuilder(storage, clock, dateResolver)
        val contextBuilder = ContextBuilder(storage, clock, dateResolver, checkinBuilder)

        val targetAttempts = mutableListOf<String>()

        val fakeTransport = object : HttpTransport {
            override suspend fun postStream(target: Target, body: String): Flow<String> = flow {
                targetAttempts.add(target.id)
                if (target.id.contains("primary.ai")) {
                    throw Http429Exception(10)
                } else if (target.id.contains("fallback.ai")) {
                    emit("""data: {"choices":[{"delta":{"content":"{\"reply\": \"Hello from fallback!\"}"}}]}""")
                    emit("data: [DONE]")
                }
            }
        }

        val limiter = RateLimiter { clock.now() }
        val orchestrator = ProviderOrchestrator(limiter, fakeTransport)
        val controller = ChatController(
            storage = storage,
            orchestrator = orchestrator,
            contextBuilder = contextBuilder,
            gateway = gateway,
            alarmScheduler = NoopScheduler(),
            coroutineScope = controllerScope,
            clock = clock,
            defaultDispatcher = testDispatcher,
            rateLimiter = limiter
        )

        targetAttempts.clear()
        controller.sendText("Hello")

        // Verify primary target was tried first, then fallback target
        assertEquals(2, targetAttempts.size)
        assertEquals("https://primary.ai/v1/chat/completions", targetAttempts[0])
        assertEquals("https://fallback.ai/v1/chat/completions", targetAttempts[1])

        // Verify the message received is from the fallback provider
        val assistantMessage = controller.state.value.chatMessages.find { it.role == "assistant" }
        assertTrue(assistantMessage != null)
        assertTrue(assistantMessage!!.content?.contains("Hello from fallback!") == true)
    }

    @Test
    fun testChatController_PartialStreamFailover_EmitsClearAndDoesNotCorruptFallbackPayload() = runTest(testDispatcher) {
        val clock = FakeClock(1_000_000L)
        val storage = SharedFakeStorage(clock)
        storage.saveSetting("ep", "https://primary.ai/v1/chat/completions")
        storage.saveSetting("key", "primary-key")
        storage.saveSetting("mod", "primary-model")
        storage.saveSetting("provider.fallback.endpoint", "https://fallback.ai/v1/chat/completions")
        storage.saveSetting("provider.fallback.key", "fallback-key")

        val dateResolver = DateResolver(clock)
        val gateway = Gateway(storage, Resolver(), dateResolver, clock)
        val checkinBuilder = CheckinBuilder(storage, clock, dateResolver)
        val contextBuilder = ContextBuilder(storage, clock, dateResolver, checkinBuilder)

        val fakeTransport = object : HttpTransport {
            override suspend fun postStream(target: Target, body: String): Flow<String> = flow {
                if (target.id.contains("primary.ai")) {
                    // Target 1 emits partial JSON before network failure
                    emit("""data: {"choices":[{"delta":{"content":"{\"reply\": \"Partial "}}]}""")
                    throw java.io.IOException("Socket timeout mid-stream")
                } else if (target.id.contains("fallback.ai")) {
                    emit("""data: {"choices":[{"delta":{"content":"{\"reply\": \"Clean fallback response\"}"}}]}""")
                    emit("data: [DONE]")
                }
            }
        }

        val orchestrator = ProviderOrchestrator(RateLimiter { clock.now() }, fakeTransport)
        val controller = ChatController(
            storage = storage,
            orchestrator = orchestrator,
            contextBuilder = contextBuilder,
            gateway = gateway,
            alarmScheduler = NoopScheduler(),
            coroutineScope = controllerScope,
            clock = clock,
            defaultDispatcher = testDispatcher
        )

        controller.sendText("Hello")

        val assistantMessage = controller.state.value.chatMessages.find { it.role == "assistant" }
        assertTrue("Assistant message must be present", assistantMessage != null)
        // If Clear was not emitted, assistantMessage.content would be raw corrupt JSON:
        // '{"reply": "Partial {"reply": "Clean fallback response"}'
        // With Clear, EnvelopeParser parses cleanly and extracts exact reply:
        assertEquals("Clean fallback response", assistantMessage!!.content)
    }
}
