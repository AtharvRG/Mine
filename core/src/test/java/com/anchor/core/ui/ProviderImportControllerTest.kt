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
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProviderImportControllerTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val controllerScope = CoroutineScope(testDispatcher + SupervisorJob())

    private class NoopScheduler : AlarmScheduler {
        override val lastFiredId = MutableStateFlow<String?>(null)
        override fun schedule(reminder: Reminder): ScheduleResult = ScheduleResult.Armed
        override fun cancel(reminderId: String) {}
    }

    private fun newController(storage: SharedFakeStorage, clock: FakeClock): ChatController {
        val dateResolver = DateResolver(clock)
        val gateway = Gateway(storage, Resolver(), dateResolver, clock)
        val checkinBuilder = CheckinBuilder(storage, clock, dateResolver)
        val contextBuilder = ContextBuilder(storage, clock, dateResolver, checkinBuilder)
        val fakeTransport = object : HttpTransport {
            override suspend fun postStream(target: Target, body: String): Flow<String> = emptyFlow()
        }
        val orchestrator = ProviderOrchestrator(RateLimiter { clock.now() }, fakeTransport)
        return ChatController(
            storage = storage,
            orchestrator = orchestrator,
            contextBuilder = contextBuilder,
            gateway = gateway,
            alarmScheduler = NoopScheduler(),
            coroutineScope = controllerScope,
            defaultDispatcher = testDispatcher,
            clock = clock
        )
    }

    @Test
    fun testImportProviderConfig_WithKey_PersistsAllThreeAndUpdatesState() = runTest(testDispatcher) {
        val clock = FakeClock(1700000000000L)
        val storage = SharedFakeStorage()
        val controller = newController(storage, clock)

        val json = """{"endpoint": "https://api.openai.com/v1", "model": "gpt-4o", "key": "sk-imported"}"""
        controller.onAction(UiAction.ImportProviderConfig(json))

        assertEquals("https://api.openai.com/v1", storage.getSetting("ep"))
        assertEquals("gpt-4o", storage.getSetting("mod"))
        assertEquals("sk-imported", storage.getSetting("key"))
        assertEquals("https://api.openai.com/v1", controller.state.value.providerEndpoint)
        assertEquals("gpt-4o", controller.state.value.providerModel)
        assertEquals("sk-imported", controller.state.value.providerKey)
        assertTrue(controller.state.value.receipts.any { it.message.contains("Imported provider setup") })
    }

    @Test
    fun testImportProviderConfig_WithoutKey_PreservesExistingKeyInStorageAndState() = runTest(testDispatcher) {
        val clock = FakeClock(1700000000000L)
        val storage = SharedFakeStorage()
        storage.settings["ep"] = "https://old.example.com/v1"
        storage.settings["mod"] = "old-model"
        storage.settings["key"] = "sk-existing-secret"
        val controller = newController(storage, clock)

        val json = """{"endpoint": "https://api.groq.com/openai/v1", "model": "llama-3.3-70b"}"""
        controller.onAction(UiAction.ImportProviderConfig(json))

        assertEquals("https://api.groq.com/openai/v1", storage.getSetting("ep"))
        assertEquals("llama-3.3-70b", storage.getSetting("mod"))
        // INVARIANT: existing key MUST be preserved
        assertEquals("sk-existing-secret", storage.getSetting("key"))
        assertEquals("https://api.groq.com/openai/v1", controller.state.value.providerEndpoint)
        assertEquals("llama-3.3-70b", controller.state.value.providerModel)
        assertEquals("sk-existing-secret", controller.state.value.providerKey)
        assertTrue(controller.state.value.receipts.any { it.message.contains("key unchanged") })
    }

    @Test
    fun testImportProviderConfig_UriScheme_PersistsConfig() = runTest(testDispatcher) {
        val clock = FakeClock(1700000000000L)
        val storage = SharedFakeStorage()
        val controller = newController(storage, clock)

        val uri = "mine://provider?endpoint=https%3A%2F%2Fapi.mistral.ai%2Fv1&model=mistral-large&key=mis-xyz"
        controller.onAction(UiAction.ImportProviderConfig(uri))

        assertEquals("https://api.mistral.ai/v1", storage.getSetting("ep"))
        assertEquals("mistral-large", storage.getSetting("mod"))
        assertEquals("mis-xyz", storage.getSetting("key"))
    }

    @Test
    fun testImportProviderConfig_InvalidPayload_EmitsReceiptAndKeepsExistingConfig() = runTest(testDispatcher) {
        val clock = FakeClock(1700000000000L)
        val storage = SharedFakeStorage()
        storage.settings["ep"] = "https://safe.example.com/v1"
        storage.settings["mod"] = "safe-model"
        storage.settings["key"] = "safe-key"
        val controller = newController(storage, clock)

        controller.onAction(UiAction.ImportProviderConfig("not-a-valid-payload"))

        assertEquals("https://safe.example.com/v1", storage.getSetting("ep"))
        assertEquals("safe-model", storage.getSetting("mod"))
        assertEquals("safe-key", storage.getSetting("key"))
        assertTrue(controller.state.value.receipts.any { it.isError && it.message.contains("Could not import") })
    }
}
