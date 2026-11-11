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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLockStateTest {

    private val dummyScheduler = object : AlarmScheduler {
        override val lastFiredId = MutableStateFlow<String?>(null)
        override fun schedule(reminder: Reminder) = ScheduleResult.Armed
        override fun cancel(reminderId: String) {}
    }

    private val dummyTransport = object : HttpTransport {
        override suspend fun postStream(target: Target, body: String): Flow<String> = emptyFlow()
    }

    private fun createController(storage: SharedFakeStorage, clock: FakeClock): ChatController {
        val dateResolver = DateResolver(clock)
        val checkinBuilder = CheckinBuilder(storage, clock, dateResolver)
        val contextBuilder = ContextBuilder(storage, clock, dateResolver, checkinBuilder)
        val orchestrator = ProviderOrchestrator(RateLimiter { clock.now() }, dummyTransport)
        val gateway = Gateway(storage, Resolver(), dateResolver, clock)

        return ChatController(
            storage,
            orchestrator,
            contextBuilder,
            gateway,
            dummyScheduler,
            TestScope(UnconfinedTestDispatcher()),
            UnconfinedTestDispatcher(),
            clock
        )
    }

    @Test
    fun testAppLock_DisabledByDefault() = runBlocking {
        val clock = FakeClock(1000L)
        val storage = SharedFakeStorage()
        val controller = createController(storage, clock)

        assertFalse("App lock must be disabled by default", controller.state.value.appLockEnabled)
        assertFalse("App must not be locked by default", controller.state.value.isAppLocked)
        assertEquals(60, controller.state.value.relockTimeoutSec)
        assertFalse(controller.state.value.blockScreenshots)
    }

    @Test
    fun testAppLock_WhenEnabled_ColdStartIsLocked() = runBlocking {
        val clock = FakeClock(1000L)
        val storage = SharedFakeStorage().apply {
            settings["privacy.app_lock"] = "1"
            settings["privacy.relock_timeout_sec"] = "300"
            settings["privacy.block_screenshots"] = "1"
        }
        val controller = createController(storage, clock)

        assertTrue("App lock should be enabled", controller.state.value.appLockEnabled)
        assertTrue("App must start in locked state on cold start", controller.state.value.isAppLocked)
        assertEquals(300, controller.state.value.relockTimeoutSec)
        assertTrue(controller.state.value.blockScreenshots)
    }

    @Test
    fun testUnlockAndLockApp_TransitionsCorrectly() = runBlocking {
        val clock = FakeClock(1000L)
        val storage = SharedFakeStorage().apply {
            settings["privacy.app_lock"] = "1"
        }
        val controller = createController(storage, clock)
        assertTrue(controller.state.value.isAppLocked)

        // 1. User authenticates successfully -> UnlockApp
        controller.onAction(UiAction.UnlockApp)
        assertFalse("isAppLocked must be false after UnlockApp", controller.state.value.isAppLocked)

        // 2. Timeout expires / user switches app -> LockApp
        controller.onAction(UiAction.LockApp)
        assertTrue("isAppLocked must be true after LockApp", controller.state.value.isAppLocked)
    }

    @Test
    fun testLockApp_IgnoredWhenAppLockDisabled() = runBlocking {
        val clock = FakeClock(1000L)
        val storage = SharedFakeStorage()
        val controller = createController(storage, clock)
        assertFalse(controller.state.value.appLockEnabled)

        // Dispatch LockApp when feature is off
        controller.onAction(UiAction.LockApp)
        assertFalse("isAppLocked must remain false when appLockEnabled is false", controller.state.value.isAppLocked)
    }

    @Test
    fun testSetAppLock_UpdatesSettingAndState() = runBlocking {
        val clock = FakeClock(1000L)
        val storage = SharedFakeStorage()
        val controller = createController(storage, clock)

        // Turn on
        controller.onAction(UiAction.SetAppLock(true))
        assertEquals("1", storage.getSetting("privacy.app_lock"))
        assertTrue(controller.state.value.appLockEnabled)

        // Turn off
        controller.onAction(UiAction.SetAppLock(false))
        assertEquals("0", storage.getSetting("privacy.app_lock"))
        assertFalse(controller.state.value.appLockEnabled)
    }

    @Test
    fun testSetRelockTimeout_UpdatesSettingAndState() = runBlocking {
        val clock = FakeClock(1000L)
        val storage = SharedFakeStorage()
        val controller = createController(storage, clock)

        controller.onAction(UiAction.SetRelockTimeout(1800))
        assertEquals("1800", storage.getSetting("privacy.relock_timeout_sec"))
        assertEquals(1800, controller.state.value.relockTimeoutSec)
    }

    @Test
    fun testSetBlockScreenshots_UpdatesSettingAndState() = runBlocking {
        val clock = FakeClock(1000L)
        val storage = SharedFakeStorage()
        val controller = createController(storage, clock)

        controller.onAction(UiAction.SetBlockScreenshots(true))
        assertEquals("1", storage.getSetting("privacy.block_screenshots"))
        assertTrue(controller.state.value.blockScreenshots)
    }

    @Test
    fun testSetCompactMode_UpdatesSettingAndState() = runBlocking {
        val clock = FakeClock(1000L)
        val storage = SharedFakeStorage()
        val controller = createController(storage, clock)

        controller.onAction(UiAction.SetCompactMode(true))
        assertEquals("1", storage.getSetting("ai.compact_mode"))
        assertTrue(controller.state.value.compactMode)

        controller.onAction(UiAction.SetCompactMode(false))
        assertEquals("0", storage.getSetting("ai.compact_mode"))
        assertFalse(controller.state.value.compactMode)
    }
}
