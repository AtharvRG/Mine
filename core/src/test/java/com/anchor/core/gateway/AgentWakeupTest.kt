// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.gateway

import com.anchor.core.SharedFakeStorage
import com.anchor.core.agent.AgentWakeupEvaluator
import com.anchor.core.alarms.AlarmScheduler
import com.anchor.core.alarms.ScheduleResult
import com.anchor.core.models.AgentWakeup
import com.anchor.core.models.Node
import com.anchor.core.models.Reminder
import com.anchor.core.models.ToolResult
import com.anchor.core.resolver.Resolver
import com.anchor.core.time.DateResolver
import com.anchor.core.time.FakeClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class AgentWakeupTest {

    private lateinit var storage: SharedFakeStorage
    private lateinit var clock: FakeClock
    private lateinit var resolver: Resolver
    private lateinit var dateResolver: DateResolver
    private lateinit var gateway: Gateway
    private var scheduledWakeupId: String? = null
    private var scheduledWakeupTime: Long? = null

    @Before
    fun setUp() {
        clock = FakeClock(1700000000000L)
        storage = SharedFakeStorage(clock)
        runBlocking {
            storage.saveDayRule(com.anchor.core.models.DayRule("r_24h", 0L, 0, 1440, "UTC", 0L))
        }
        resolver = Resolver()
        dateResolver = DateResolver(clock, { false })
        scheduledWakeupId = null
        scheduledWakeupTime = null

        val testScheduler = object : AlarmScheduler {
            override val lastFiredId = MutableStateFlow<String?>(null)
            override fun schedule(reminder: Reminder) = ScheduleResult.Armed
            override fun cancel(reminderId: String) {}
            override fun scheduleAgentWakeup(wakeupId: String, fireAtMillis: Long): ScheduleResult {
                scheduledWakeupId = wakeupId
                scheduledWakeupTime = fireAtMillis
                return ScheduleResult.Armed
            }
            override fun cancelAgentWakeup(wakeupId: String) {}
        }

        gateway = Gateway(storage, resolver, dateResolver, clock, alarmScheduler = testScheduler)
    }

    @Test
    fun testScheduleAgentWakeupTool_SavesToStorageAndArmsScheduler() = runBlocking {
        val result = gateway.executeTool(
            ToolCall(
                "schedule_agent_wakeup",
                mapOf(
                    "fire_at" to JsonPrimitive("in 45 minutes"),
                    "reason" to JsonPrimitive("Check if user finished cooking"),
                    "payload" to JsonPrimitive("""{"node_id":"n_123","message_if_not_done":"Don't forget the oven!"}""")
                )
            )
        )

        assertTrue("Expected committed result, got $result", result is ToolResult.Committed)
        val wakeupId = (result as ToolResult.Committed).targetId
        assertTrue(wakeupId.startsWith("aw_"))

        // Verify row saved in storage
        val saved = storage.getAgentWakeup(wakeupId)
        assertNotNull(saved)
        assertEquals(wakeupId, saved?.id)
        val expectedFireAt = clock.now() + 45 * 60_000L
        assertEquals(expectedFireAt, saved?.fireAt)
        assertEquals("Check if user finished cooking", saved?.reason)
        assertEquals("pending", saved?.state)

        // Verify scheduler armed
        assertEquals(wakeupId, scheduledWakeupId)
        assertEquals(expectedFireAt, scheduledWakeupTime)
    }

    @Test
    fun testAgentWakeupEvaluator_SuppressesNotification_WhenTaskAlreadyDone() = runBlocking {
        val doneNode = Node(
            id = "n_cook",
            title = "Bake bread",
            type = "task",
            status = "done",
            createdAt = clock.now() - 3600_000L,
            updatedAt = clock.now() - 1800_000L
        )
        storage.saveNode(doneNode)

        val wakeup = AgentWakeup(
            id = "aw_test1",
            fireAt = clock.now(),
            reason = "Check bread",
            payloadJson = """{"node_id":"n_cook","message_if_not_done":"Check the oven!"}""",
            createdAt = clock.now()
        )

        val eval = AgentWakeupEvaluator.evaluate(wakeup, storage, clock.now())
        assertFalse("Should NOT notify if task is done", eval.shouldNotify)
        assertNull(eval.notificationText)
        assertEquals("suppressed_task_already_done", eval.actionTaken)
    }

    @Test
    fun testAgentWakeupEvaluator_SuppressesNotification_WhenTaskCancelled() = runBlocking {
        val cancelledNode = Node(
            id = "n_call",
            title = "Call Raj",
            type = "task",
            status = "cancelled",
            createdAt = clock.now() - 3600_000L,
            updatedAt = clock.now() - 1800_000L
        )
        storage.saveNode(cancelledNode)

        val wakeup = AgentWakeup(
            id = "aw_test2",
            fireAt = clock.now(),
            reason = "Follow up with Raj",
            payloadJson = """{"node_id":"n_call","message_if_not_done":"Did Raj text back?"}""",
            createdAt = clock.now()
        )

        val eval = AgentWakeupEvaluator.evaluate(wakeup, storage, clock.now())
        assertFalse("Should NOT notify if task was cancelled", eval.shouldNotify)
        assertNull(eval.notificationText)
        assertEquals("suppressed_task_already_cancelled", eval.actionTaken)
    }

    @Test
    fun testAgentWakeupEvaluator_FiresNotification_WhenTaskStillActive() = runBlocking {
        val activeNode = Node(
            id = "n_cook",
            title = "Bake bread",
            type = "task",
            status = "active",
            createdAt = clock.now() - 3600_000L,
            updatedAt = clock.now() - 1800_000L
        )
        storage.saveNode(activeNode)

        val wakeup = AgentWakeup(
            id = "aw_test3",
            fireAt = clock.now(),
            reason = "Check bread",
            payloadJson = """{"node_id":"n_cook","message_if_not_done":"Check the oven!"}""",
            createdAt = clock.now()
        )

        val eval = AgentWakeupEvaluator.evaluate(wakeup, storage, clock.now())
        assertTrue("Should notify if task is still active", eval.shouldNotify)
        assertEquals("Check the oven!", eval.notificationText)
        assertEquals("notified", eval.actionTaken)
    }

    @Test
    fun testAgentWakeupEvaluator_FiresUnconditionedReason() = runBlocking {
        val wakeup = AgentWakeup(
            id = "aw_test4",
            fireAt = clock.now(),
            reason = "Time for a walk and hydration!",
            payloadJson = null,
            createdAt = clock.now()
        )

        val eval = AgentWakeupEvaluator.evaluate(wakeup, storage, clock.now())
        assertTrue(eval.shouldNotify)
        assertEquals("Time for a walk and hydration!", eval.notificationText)
    }
}
