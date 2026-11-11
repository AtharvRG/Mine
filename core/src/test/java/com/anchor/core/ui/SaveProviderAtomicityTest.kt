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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * SaveProvider writes three settings (endpoint, key, model). They describe ONE provider config;
 * a crash or failure part-way must not leave a mixed config (new endpoint + old key), which would
 * make every request fail with an error the user cannot interpret.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SaveProviderAtomicityTest {

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
    fun testSaveProvider_Success_WritesAllThreeSettings() = runTest(testDispatcher) {
        val clock = FakeClock(1700000000000L)
        val storage = SharedFakeStorage()
        val controller = newController(storage, clock)

        controller.onAction(UiAction.SaveProvider("https://api.example.com/v1", "sk-new", "model-b"))

        assertEquals("https://api.example.com/v1", storage.getSetting("ep"))
        assertEquals("sk-new", storage.getSetting("key"))
        assertEquals("model-b", storage.getSetting("mod"))
        assertEquals("model-b", controller.state.value.providerModel)
    }

    @Test
    fun testSaveProvider_FailureMidWrite_LeavesPreviousConfigIntact() = runTest(testDispatcher) {
        val clock = FakeClock(1700000000000L)
        val storage = SharedFakeStorage()
        storage.settings["ep"] = "https://old.example.com/v1"
        storage.settings["key"] = "sk-old"
        storage.settings["mod"] = "model-a"
        val controller = newController(storage, clock)

        // Fail on the second of the three writes (endpoint written, key not).
        storage.failSettingsWriteAfter = 1
        controller.onAction(UiAction.SaveProvider("https://new.example.com/v1", "sk-new", "model-b"))

        assertEquals("Endpoint must not be half-applied", "https://old.example.com/v1", storage.getSetting("ep"))
        assertEquals("sk-old", storage.getSetting("key"))
        assertEquals("model-a", storage.getSetting("mod"))
        assertEquals(
            "A failed save must publish the config that is actually persisted, not the rejected one",
            "https://old.example.com/v1",
            controller.state.value.providerEndpoint
        )
        assertTrue(
            "A failed save must tell the user via receipt, not fail silently",
            controller.state.value.receipts.any { it.isError && it.message.contains("Could not save provider settings") }
        )
        org.junit.Assert.assertNull(
            "B1-03: A failed save must not set globalError and brick the UI",
            controller.state.value.globalError
        )
    }
}
