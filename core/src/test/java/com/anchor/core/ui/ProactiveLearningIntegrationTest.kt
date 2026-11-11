// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.ui

import com.anchor.core.SharedFakeStorage
import com.anchor.core.alarms.AlarmScheduler
import com.anchor.core.alarms.ScheduleResult
import com.anchor.core.context.ContextBuilder
import com.anchor.core.gateway.Gateway
import com.anchor.core.learning.LearningEngine
import com.anchor.core.models.LearnedPref
import com.anchor.core.models.ProactiveState
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ProactiveLearningIntegrationTest {

    private class NoTransport : HttpTransport {
        override suspend fun postStream(target: Target, body: String): Flow<String> = flow {
            emit("{\"choices\":[{\"delta\":{\"content\":\"OK\"}}]}")
        }
    }

    private lateinit var storage: SharedFakeStorage
    private lateinit var clock: FakeClock
    private lateinit var controller: ChatController

    @Before
    fun setUp() {
        storage = SharedFakeStorage()
        clock = FakeClock(1700000000000L)
        val dateResolver = DateResolver(clock)
        val contextBuilder = ContextBuilder(storage, clock, dateResolver, CheckinBuilder(storage, clock, dateResolver))
        val gateway = Gateway(storage, Resolver(), dateResolver, clock)
        val scheduler = object : AlarmScheduler {
            override val lastFiredId = MutableStateFlow<String?>(null)
            override fun schedule(reminder: Reminder) = ScheduleResult.Armed
            override fun cancel(reminderId: String) {}
        }
        controller = ChatController(
            storage = storage,
            orchestrator = ProviderOrchestrator(RateLimiter { clock.now() }, NoTransport()),
            contextBuilder = contextBuilder,
            gateway = gateway,
            alarmScheduler = scheduler,
            coroutineScope = CoroutineScope(Dispatchers.Unconfined),
            defaultDispatcher = Dispatchers.Unconfined,
            clock = clock
        )
    }

    @Test
    fun testProactiveReply_RecordsNudgeReplySignal() = runBlocking {
        // Setup an open thread
        storage.saveProactiveState(
            ProactiveState(
                openThreadMsgId = "n_123",
                threadOpenedAt = clock.now() - 1000L,
                backoffStep = 0
            )
        )

        // User sends text
        controller.sendText("I am working on it now")

        val signals = storage.getSignals(kind = LearningEngine.SIGNAL_NUDGE_REPLY)
        assertEquals(1, signals.size)
        assertEquals("overdue", signals[0].subject)
        assertEquals(clock.now(), signals[0].tsUtc)
    }

    @Test
    fun testProactiveIgnore_RecordsNudgeIgnoredSignal() = runBlocking {
        val openedAt = clock.now() - 4 * 3600 * 1000L // 4 hours ago (> 3h window)
        storage.saveProactiveState(
            ProactiveState(
                openThreadMsgId = "n_123",
                threadOpenedAt = openedAt,
                backoffStep = 0
            )
        )

        // Tick proactive
        controller.onAction(UiAction.TickProactive)

        val signals = storage.getSignals(kind = LearningEngine.SIGNAL_NUDGE_IGNORED)
        assertEquals(1, signals.size)
        assertEquals("overdue", signals[0].subject)
    }

    @Test
    fun testWhyNudge_PopulatesWhyTextInUiState() = runBlocking {
        controller.onAction(UiAction.WhyNudge())

        val why = controller.state.value.whyNudgeText
        assertNotNull(why)
        assertTrue(why!!.contains("I asked because: an item is overdue"))
    }

    @Test
    fun testLockAndResetLearning_RefreshesUiState() = runBlocking {
        val pref = LearnedPref(
            key = LearningEngine.KEY_NUDGE_REPLY_RATE,
            scope = "trigger=overdue",
            valueJson = "{}",
            samples = 10,
            confidence = 0.8,
            locked = false,
            updatedAt = clock.now()
        )
        storage.saveLearnedPref(pref)

        // Lock preference
        controller.onAction(UiAction.LockLearnedPref(pref.key, pref.scope, locked = true))
        val lockedPref = storage.getLearnedPref(pref.key, pref.scope)
        assertNotNull(lockedPref)
        assertTrue(lockedPref!!.locked)

        // Reset learning preserving locked
        controller.onAction(UiAction.ResetLearning(preserveLocked = true))
        assertNotNull(storage.getLearnedPref(pref.key, pref.scope))
        assertTrue(controller.state.value.learnedPrefs.isNotEmpty())

        // Reset learning completely
        controller.onAction(UiAction.ResetLearning(preserveLocked = false))
        assertNull(storage.getLearnedPref(pref.key, pref.scope))
        assertTrue(controller.state.value.learnedPrefs.isEmpty())
    }
}
