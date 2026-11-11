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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class WhatsNewCardTest {

    @Test
    fun testShouldShowWhatsNew_WhenNullOrOlderVersion() {
        assertTrue(WhatsNewCatalog.shouldShowWhatsNew(null))
        assertTrue(WhatsNewCatalog.shouldShowWhatsNew("1.0.0"))
        assertFalse(WhatsNewCatalog.shouldShowWhatsNew("1.1.0"))
    }

    @Test
    fun testBuildWhatsNewCard_ContainsAllV11Features() {
        val card = WhatsNewCatalog.buildWhatsNewCard()
        assertTrue(card.contains("v1.1"))
        assertTrue(card.contains("Offline Quick Add"))
        assertTrue(card.contains("Habits & Streaks"))
        assertTrue(card.contains("Hybrid Vector Search"))
        assertTrue(card.contains("Direct-Boot"))
        assertTrue(card.contains("Readable Markdown Export"))
    }

    @Test
    fun testControllerInit_ShowsWhatsNewCard_WhenUpgradingToV11() = runTest {
        val clock = FakeClock(1000L)
        val storage = SharedFakeStorage(clock).apply {
            settings["onboarding.done"] = "true"
            settings["key"] = "test-key"
            // Previous version notification was 1.0.0
            settings[WhatsNewCatalog.SETTING_VERSION_NOTIFIED] = "1.0.0"
        }
        val dateResolver = DateResolver(clock = clock, is24HourFormat = { false }, timeZone = TimeZone.getTimeZone("UTC"))
        val checkinBuilder = CheckinBuilder(storage, clock, dateResolver)
        val contextBuilder = ContextBuilder(storage, clock, dateResolver, checkinBuilder)
        val dummyTransport = object : HttpTransport {
            override suspend fun postStream(target: Target, body: String): Flow<String> = flow {}
        }
        val orchestrator = ProviderOrchestrator(RateLimiter { clock.now() }, dummyTransport)
        val gateway = Gateway(storage, Resolver(), dateResolver, clock)
        val fakeScheduler = object : AlarmScheduler {
            override val lastFiredId = MutableStateFlow<String?>(null)
            override fun schedule(reminder: Reminder) = ScheduleResult.Armed
            override fun cancel(reminderId: String) {}
        }
        val dispatcher = UnconfinedTestDispatcher()

        val controller = ChatController(
            storage = storage,
            orchestrator = orchestrator,
            contextBuilder = contextBuilder,
            gateway = gateway,
            alarmScheduler = fakeScheduler,
            coroutineScope = CoroutineScope(dispatcher),
            defaultDispatcher = dispatcher,
            clock = clock,
            dateResolver = dateResolver
        )

        // Must insert the What's New message
        val messages = storage.getRecentMessages(20)
        val whatsNewMsg = messages.find { it.content?.contains("Welcome to Mine v1.1") == true }
        assertNotNull("Controller must display What's New card on upgrade to v1.1", whatsNewMsg)

        // Version notified setting must be updated to 1.1.0
        assertEquals("1.1.0", storage.getSetting(WhatsNewCatalog.SETTING_VERSION_NOTIFIED))
    }
}
