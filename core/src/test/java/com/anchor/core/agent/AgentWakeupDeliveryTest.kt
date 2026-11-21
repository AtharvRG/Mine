// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.agent

import com.anchor.core.SharedFakeStorage
import com.anchor.core.alarms.AlarmReconciler
import com.anchor.core.alarms.AlarmScheduler
import com.anchor.core.alarms.ScheduleResult
import com.anchor.core.models.AgentWakeup
import com.anchor.core.models.ChatMessage
import com.anchor.core.models.DayRule
import com.anchor.core.models.Node
import com.anchor.core.models.Reminder
import com.anchor.core.sensing.ContextEngine
import com.anchor.core.sensing.MotionAxis
import com.anchor.core.time.FakeClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AgentWakeupDeliveryTest {

    private lateinit var clock: FakeClock
    private lateinit var storage: SharedFakeStorage

    @Before
    fun setUp() {
        clock = FakeClock(1700000000000L) // Normal daytime anchor
        storage = SharedFakeStorage(clock)
    }

    @Test
    fun testWakeupEvaluation_DuringQuietHours_ReturnsSnooze30m() = runBlocking {
        // Set DayRule: day starts at 08:00 (480 mins) and ends at 22:00 (1320 mins) UTC
        storage.saveDayRule(
            DayRule(
                id = "r_default",
                effectiveFrom = 0L,
                startMinute = 480,
                endMinute = 1320,
                tzId = "UTC",
                createdAt = 0L
            )
        )

        // Pick an epoch timestamp that falls at 03:00 AM UTC (180 mins)
        // 1700006400000L is 2023-11-15 00:00:00 UTC
        val quietHourTime = 1700006400000L + (3 * 3600 * 1000L) // 03:00 UTC

        val wakeup = AgentWakeup(
            id = "w_quiet_1",
            fireAt = quietHourTime,
            reason = "Time to check on tomorrow's deck",
            payloadJson = null,
            state = "pending",
            createdAt = quietHourTime - 3600_000L
        )

        val result = AgentWakeupEvaluator.evaluate(wakeup, storage, quietHourTime)
        assertFalse(result.shouldNotify)
        assertEquals("snooze_30m", result.actionTaken)
        assertEquals(quietHourTime + 30 * 60_000L, result.snoozeUntilMs)
    }

    @Test
    fun testWakeupEvaluation_DuringVehicleHold_ReturnsSnooze30m() = runBlocking {
        // Daytime: 12:00 PM UTC (720 mins)
        val dayTime = 1700006400000L + (12 * 3600 * 1000L)
        clock.currentTime = dayTime
        val contextEngine = ContextEngine(clock)
        contextEngine.onMotionSignal(motion = MotionAxis.VEHICLE, confidence = 1.0f, tsUtc = dayTime)
        clock.currentTime = dayTime + 45_000L
        contextEngine.checkTimeouts()
        assertTrue(contextEngine.isVehicleHoldActive())

        val wakeup = AgentWakeup(
            id = "w_veh_1",
            fireAt = dayTime,
            reason = "Call dentist",
            payloadJson = null,
            state = "pending",
            createdAt = dayTime - 1800_000L
        )

        val result = AgentWakeupEvaluator.evaluate(wakeup, storage, dayTime, contextEngine)
        assertFalse(result.shouldNotify)
        assertEquals("snooze_30m", result.actionTaken)
        assertEquals(dayTime + 30 * 60_000L, result.snoozeUntilMs)
    }

    @Test
    fun testWakeupEvaluation_TaskAlreadyDone_Suppressed() = runBlocking {
        val now = 1700006400000L + (14 * 3600 * 1000L)
        val task = Node(id = "n_task_done", type = "task", title = "Submit report", status = "done", createdAt = now - 5000)
        storage.saveNode(task)

        val wakeup = AgentWakeup(
            id = "w_done_1",
            fireAt = now,
            reason = "Submit report reminder",
            payloadJson = """{"node_id":"n_task_done","cancel_if_done":true}""",
            state = "pending",
            createdAt = now - 3600_000L
        )

        val result = AgentWakeupEvaluator.evaluate(wakeup, storage, now)
        assertFalse(result.shouldNotify)
        assertEquals("suppressed_task_already_done", result.actionTaken)
    }

    @Test
    fun testWakeupEvaluation_WalkReturn_WhenHome_Notifies() = runBlocking {
        val now = 1700006400000L + (14 * 3600 * 1000L)
        clock.currentTime = now
        val contextEngine = ContextEngine(clock)
        val homePlace = com.anchor.core.sensing.Place("p_home", null, "home", "Home", now)
        val homeSignal = com.anchor.core.sensing.PlaceSignal("p_home", wifiJson = """{"ssid":"MyHomeWifi"}""")
        contextEngine.onWifiSignal("MyHomeWifi", emptyList(), listOf(homePlace to homeSignal), now)
        clock.currentTime = now + 125_000L
        contextEngine.checkTimeouts(now + 125_000L)

        val wakeup = AgentWakeup(
            id = "w_walk_1",
            fireAt = now,
            reason = "Walk return check-in",
            payloadJson = """{"type":"WALK_RETURN_CHECKIN","started_at":${now - 35 * 60_000L}}""",
            state = "pending",
            createdAt = now - 35 * 60_000L
        )

        val result = AgentWakeupEvaluator.evaluate(wakeup, storage, now, contextEngine)
        assertTrue(result.shouldNotify)
        assertEquals("notified", result.actionTaken)
        assertTrue(result.notificationText?.contains("Welcome back") == true)
    }

    @Test
    fun testWakeupEvaluation_WalkReturn_WhenNotHome_Snoozes15m() = runBlocking {
        val now = 1700006400000L + (14 * 3600 * 1000L)
        clock.currentTime = now
        val contextEngine = ContextEngine(clock)

        val wakeup = AgentWakeup(
            id = "w_walk_2",
            fireAt = now,
            reason = "Walk return check-in",
            payloadJson = """{"type":"WALK_RETURN_CHECKIN","started_at":${now - 35 * 60_000L}}""",
            state = "pending",
            createdAt = now - 35 * 60_000L
        )

        val result = AgentWakeupEvaluator.evaluate(wakeup, storage, now, contextEngine)
        assertFalse(result.shouldNotify)
        assertEquals("snooze_15m", result.actionTaken)
        assertEquals(now + 15 * 60_000L, result.snoozeUntilMs)
    }

    @Test
    fun testAlarmReconciler_ReconcilesPendingAgentWakeups() = runBlocking {
        val now = 1700006400000L + (10 * 3600 * 1000L)
        clock.currentTime = now

        val scheduledWakeups = mutableListOf<Pair<String, Long>>()
        val scheduler = object : AlarmScheduler {
            override val lastFiredId = MutableStateFlow<String?>(null)
            override fun schedule(reminder: Reminder) = ScheduleResult.Armed
            override fun cancel(reminderId: String) {}
            override fun scheduleAgentWakeup(wakeupId: String, fireAtMillis: Long): ScheduleResult {
                scheduledWakeups.add(wakeupId to fireAtMillis)
                return ScheduleResult.Armed
            }
        }

        // Add 2 pending wakeups (one future, one recently due within grace, one way overdue past grace)
        storage.saveAgentWakeup(AgentWakeup("w_future", now + 3600_000L, "Future checkin", null, "pending", now))
        storage.saveAgentWakeup(AgentWakeup("w_grace", now - 30_000L, "Grace checkin", null, "pending", now))
        storage.saveAgentWakeup(AgentWakeup("w_stale", now - 120_000L, "Stale checkin", null, "pending", now))

        val reconciler = AlarmReconciler(storage, scheduler, clock)
        val results = reconciler.reconcile(force = false)

        // w_future and w_grace must be re-armed
        assertEquals(2, scheduledWakeups.size)
        assertTrue(scheduledWakeups.any { it.first == "w_future" && it.second == now + 3600_000L })
        assertTrue(scheduledWakeups.any { it.first == "w_grace" && it.second == now - 30_000L })
        assertFalse(scheduledWakeups.any { it.first == "w_stale" })
    }

    @Test
    fun testChatController_TalkLater_CapturesOriginTopicInWakeup() = runBlocking {
        val now = 1700006400000L + (11 * 3600 * 1000L)
        clock.currentTime = now

        var scheduledWakeupId: String? = null
        var scheduledFireAt: Long? = null
        val scheduler = object : AlarmScheduler {
            override val lastFiredId = MutableStateFlow<String?>(null)
            override fun schedule(reminder: Reminder) = ScheduleResult.Armed
            override fun cancel(reminderId: String) {}
            override fun scheduleAgentWakeup(wakeupId: String, fireAtMillis: Long): ScheduleResult {
                scheduledWakeupId = wakeupId
                scheduledFireAt = fireAtMillis
                return ScheduleResult.Armed
            }
        }

        val rateLimiter = com.anchor.core.provider.RateLimiter(clock = { clock.now() })
        val fakeTransport = object : com.anchor.core.provider.HttpTransport {
            override suspend fun postStream(target: com.anchor.core.provider.Target, body: String) = kotlinx.coroutines.flow.emptyFlow<String>()
        }
        val orchestrator = com.anchor.core.provider.ProviderOrchestrator(rateLimiter, fakeTransport)
        val dateResolver = com.anchor.core.time.DateResolver(clock, { false })
        val checkinBuilder = com.anchor.core.nudge.CheckinBuilder(storage, clock, dateResolver)
        val contextBuilder = com.anchor.core.context.ContextBuilder(storage, clock, dateResolver, checkinBuilder)
        val gateway = com.anchor.core.gateway.Gateway(storage, com.anchor.core.resolver.Resolver(), dateResolver, clock)

        val testDispatcher = kotlinx.coroutines.test.UnconfinedTestDispatcher()
        val testScope = kotlinx.coroutines.test.TestScope(testDispatcher)

        storage.saveSetting("key", "test-key")

        val controller = com.anchor.core.ui.ChatController(
            storage = storage,
            orchestrator = orchestrator,
            contextBuilder = contextBuilder,
            gateway = gateway,
            alarmScheduler = scheduler,
            coroutineScope = testScope,
            defaultDispatcher = testDispatcher,
            clock = clock,
            dateResolver = dateResolver,
            networkCheck = { true }
        )

        // Seed an earlier conversation topic in history
        storage.insertRawMessage("m_prev_1", "assistant", "Do you want to finalize the quarterly budget review slides today?")

        // User says "busy right now"
        controller.onAction(com.anchor.core.ui.UiAction.UpdateDraft("busy right now"))
        controller.onAction(com.anchor.core.ui.UiAction.SendMessage)

        assertNotNull("AgentWakeup should have been scheduled", scheduledWakeupId)
        assertEquals(now + 2 * 3600_000L, scheduledFireAt)

        val wakeup = storage.getAgentWakeup(scheduledWakeupId!!)
        assertNotNull(wakeup)
        assertTrue(wakeup!!.reason.contains("quarterly budget review slides"))
        assertTrue(wakeup.payloadJson!!.contains("DEFERRED_CHECKIN"))
        assertTrue(wakeup.payloadJson!!.contains("quarterly budget review slides"))
    }

    @Test
    fun testWakeupEvaluation_DayAnchor_EvaluatesThroughNudgeEngine() = runBlocking {
        // Daytime: 10:00 AM UTC
        val daytime = 1700006400000L + (10 * 3600 * 1000L)
        storage.saveDayRule(
            DayRule(
                id = "r_default",
                effectiveFrom = 0L,
                startMinute = 480, // 08:00
                endMinute = 1320,  // 22:00
                tzId = "UTC",
                createdAt = 0L
            )
        )

        val wakeup = AgentWakeup(
            id = "w_anchor_1",
            fireAt = daytime,
            reason = "DAY_ANCHOR_MIDDAY",
            payloadJson = """{"type":"DAY_ANCHOR","phase":"midday"}""",
            state = "pending",
            createdAt = daytime - 3600_000L
        )

        val result = AgentWakeupEvaluator.evaluate(wakeup, storage, daytime)
        assertTrue("Day anchor should notify during active daytime window", result.shouldNotify)
        assertEquals("notified_day_anchor", result.actionTaken)
        assertTrue(result.notificationText!!.contains("Midday check-in"))
    }
}
