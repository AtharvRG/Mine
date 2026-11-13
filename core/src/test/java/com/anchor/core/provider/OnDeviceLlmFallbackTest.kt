// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.provider

import com.anchor.core.SharedFakeStorage
import com.anchor.core.alarms.AlarmScheduler
import com.anchor.core.alarms.ScheduleResult
import com.anchor.core.context.ContextBuilder
import com.anchor.core.gateway.Gateway
import com.anchor.core.models.Reminder
import com.anchor.core.nudge.CheckinBuilder
import com.anchor.core.resolver.Resolver
import com.anchor.core.time.DateResolver
import com.anchor.core.time.FakeClock
import com.anchor.core.ui.ChatController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OnDeviceLlmFallbackTest {

    private val fixedClock = 1_700_000_000_000L
    private val testDispatcher = UnconfinedTestDispatcher()
    private val controllerScope = CoroutineScope(testDispatcher + SupervisorJob())

    private class NoopScheduler : AlarmScheduler {
        override val lastFiredId = MutableStateFlow<String?>(null)
        override fun schedule(reminder: Reminder): ScheduleResult = ScheduleResult.Armed
        override fun cancel(reminderId: String) {}
    }

    @Test
    fun testRuleBasedLocalLlmEngine_RemindInMinutes_GeneratesValidProposeChangeCall() = runTest(testDispatcher) {
        val engine = RuleBasedLocalLlmEngine { fixedClock }
        val prompt = """{"model":"test","stream":true,"messages":[{"role":"user","content":"Remind me in 10 minutes to call Sarah"}]}"""

        val events = engine.generateStream(prompt).toList()
        val textDeltas = events.filterIsInstance<StreamEvent.TextDelta>().joinToString("") { it.text }
        val finishEvent = events.filterIsInstance<StreamEvent.Finish>().firstOrNull()

        assertTrue(textDeltas.contains("propose_change"))
        assertTrue(textDeltas.contains("call Sarah"))
        assertTrue(textDeltas.contains("in 10 minutes"))
        assertEquals("stop", finishEvent?.reason)
    }

    @Test
    fun testRuleBasedLocalLlmEngine_AddTask_GeneratesProposeChangeCallWithoutDueAt() = runTest(testDispatcher) {
        val engine = RuleBasedLocalLlmEngine { fixedClock }
        val prompt = """{"model":"test","stream":true,"messages":[{"role":"user","content":"Add task Buy oat milk"}]}"""

        val events = engine.generateStream(prompt).toList()
        val text = events.filterIsInstance<StreamEvent.TextDelta>().joinToString("") { it.text }

        assertTrue(text.contains("propose_change"))
        assertTrue(text.contains("Buy oat milk"))
        assertFalse(text.contains("due_at"))
    }

    @Test
    fun testRuleBasedLocalLlmEngine_SearchMemory_GeneratesSearchMemoryCall() = runTest(testDispatcher) {
        val engine = RuleBasedLocalLlmEngine { fixedClock }
        val prompt = """{"model":"test","stream":true,"messages":[{"role":"user","content":"Search notes for wifi password"}]}"""

        val events = engine.generateStream(prompt).toList()
        val text = events.filterIsInstance<StreamEvent.TextDelta>().joinToString("") { it.text }

        assertTrue(text.contains("search_memory"))
        assertTrue(text.contains("wifi password"))
    }

    @Test
    fun testRuleBasedLocalLlmEngine_SystemFeedback_ProducesSummarizedReply() = runTest(testDispatcher) {
        val engine = RuleBasedLocalLlmEngine { fixedClock }
        val prompt = """{"model":"test","stream":true,"messages":[{"role":"user","content":"System feedback: Found note: Office wifi is BlueSky42"}]}"""

        val events = engine.generateStream(prompt).toList()
        val text = events.filterIsInstance<StreamEvent.TextDelta>().joinToString("") { it.text }

        assertTrue(text.contains("Offline query results:"))
        assertTrue(text.contains("Office wifi is BlueSky42"))
    }

    @Test
    fun testProviderOrchestrator_OfflineException_FallsThroughToLocalTarget() = runTest(testDispatcher) {
        val failingTransport = object : HttpTransport {
            override suspend fun postStream(target: Target, body: String): Flow<String> = flow {
                throw OfflineException("No network connection")
            }
        }
        val orchestrator = ProviderOrchestrator(
            limiter = RateLimiter { fixedClock },
            transport = failingTransport,
            onDeviceEngine = RuleBasedLocalLlmEngine { fixedClock }
        )

        val chain = listOf(
            Target("https://remote.ai/v1", "remote-key", 20),
            Target("local://ondevice", "local", 120)
        )
        val prompt = """{"model":"test","stream":true,"messages":[{"role":"user","content":"Remind me in 5 minutes to stretch"}]}"""

        val events = orchestrator.chat(chain, prompt).toList()
        val text = events.filterIsInstance<StreamEvent.TextDelta>().joinToString("") { it.text }

        assertTrue(text.contains("propose_change"))
        assertTrue(text.contains("stretch"))
    }

    @Test
    fun testProviderOrchestrator_OfflineException_WithoutLocalTarget_RethrowsOfflineException() = runTest(testDispatcher) {
        val failingTransport = object : HttpTransport {
            override suspend fun postStream(target: Target, body: String): Flow<String> = flow {
                throw OfflineException("No network connection")
            }
        }
        val orchestrator = ProviderOrchestrator(
            limiter = RateLimiter { fixedClock },
            transport = failingTransport
        )

        val chain = listOf(
            Target("https://primary.ai/v1", "key1", 20),
            Target("https://fallback.ai/v1", "key2", 20)
        )
        val prompt = "hello"

        try {
            orchestrator.chat(chain, prompt).toList()
            fail("Expected OfflineException")
        } catch (_: OfflineException) {
            // Expected
        }
    }

    @Test
    fun testProviderOrchestrator_Cloud5xx_FallsThroughToLocalTarget() = runTest(testDispatcher) {
        val serverErrorTransport = object : HttpTransport {
            override suspend fun postStream(target: Target, body: String): Flow<String> = flow {
                throw Exception("HTTP 503 Service Unavailable")
            }
        }
        val orchestrator = ProviderOrchestrator(
            limiter = RateLimiter { fixedClock },
            transport = serverErrorTransport,
            onDeviceEngine = RuleBasedLocalLlmEngine { fixedClock }
        )

        val chain = listOf(
            Target("https://remote.ai/v1", "key", 20),
            Target("local://ondevice", "local", 120)
        )
        val prompt = """{"model":"test","stream":true,"messages":[{"role":"user","content":"Schedule doctor visit"}]}"""

        val events = orchestrator.chat(chain, prompt).toList()
        val text = events.filterIsInstance<StreamEvent.TextDelta>().joinToString("") { it.text }

        assertTrue(text.contains("propose_change"))
        assertTrue(text.contains("doctor visit"))
    }

    @Test
    fun testChatController_WithOnDeviceEnabled_OfflineFailoverCreatesReminder() = runTest(testDispatcher) {
        val clock = FakeClock(fixedClock)
        val storage = SharedFakeStorage(clock)
        storage.saveSetting("provider.ondevice.enabled", "1")
        storage.saveSetting("ep", "https://remote.ai/v1/chat/completions")
        storage.saveSetting("key", "test-key")
        storage.saveSetting("mod", "test-model")

        val offlineTransport = object : HttpTransport {
            override suspend fun postStream(target: Target, body: String): Flow<String> = flow {
                throw OfflineException("Device is offline")
            }
        }

        val dateResolver = DateResolver(clock)
        val gateway = Gateway(storage, Resolver(), dateResolver, clock)
        val checkinBuilder = CheckinBuilder(storage, clock, dateResolver)
        val contextBuilder = ContextBuilder(storage, clock, dateResolver, checkinBuilder)
        val orchestrator = ProviderOrchestrator(
            limiter = RateLimiter { clock.now() },
            transport = offlineTransport,
            onDeviceEngine = RuleBasedLocalLlmEngine { clock.now() }
        )

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

        controller.sendText("Remind me in 5 minutes to submit taxes")

        val messages = controller.state.value.chatMessages
        val assistantMsg = messages.lastOrNull { it.role == "assistant" }
        assertTrue(assistantMsg != null)
        val content = assistantMsg?.content ?: ""
        assertTrue(content.contains("offline reminder") || content.contains("submit taxes"))

        // Verify proposal or node was created via gateway tool execution
        val proposals = controller.state.value.openProposals
        val nodes = storage.getActiveNodes(100, null)
        assertTrue(proposals.isNotEmpty() || nodes.any { it.title.contains("submit taxes") })
    }
}
