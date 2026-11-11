// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.ui

import com.anchor.core.SharedFakeStorage
import com.anchor.core.alarms.AlarmScheduler
import com.anchor.core.alarms.ScheduleResult
import com.anchor.core.context.ContextBuilder
import com.anchor.core.gateway.Gateway
import com.anchor.core.models.OutboxJob
import com.anchor.core.models.Reminder
import com.anchor.core.nudge.CheckinBuilder
import com.anchor.core.provider.HttpTransport
import com.anchor.core.provider.ProviderOrchestrator
import com.anchor.core.provider.Target
import com.anchor.core.resolver.Resolver
import com.anchor.core.time.DateResolver
import com.anchor.core.time.FakeClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

@OptIn(ExperimentalCoroutinesApi::class)
class OfflineQuickAddIntegrationTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val clock = FakeClock(1792929600000L) // 2026-10-25 12:00 UTC
    private val tz = TimeZone.getTimeZone("UTC")
    private val dateResolver = DateResolver(clock = clock, is24HourFormat = { false }, timeZone = tz)
    private val storage = SharedFakeStorage(clock).apply {
        settings["key"] = "test-key"
    }

    private val scheduledAlarms = mutableListOf<Reminder>()
    private val fakeScheduler = object : AlarmScheduler {
        override val lastFiredId = MutableStateFlow<String?>(null)
        override fun schedule(reminder: Reminder): ScheduleResult {
            scheduledAlarms.add(reminder)
            return ScheduleResult.Armed
        }
        override fun cancel(reminderId: String) {}
    }

    private val dummyTransport = object : HttpTransport {
        override suspend fun postStream(target: Target, body: String): Flow<String> = flow {
            emit("""data: {"choices":[{"delta":{"content":"{\"reply\":\"Got it.\",\"calls\":[]}"}}]}""")
            emit("data: [DONE]")
        }
    }

    private fun createController(networkOnline: Boolean): ChatController {
        val checkinBuilder = CheckinBuilder(storage, clock, dateResolver)
        val contextBuilder = ContextBuilder(storage, clock, dateResolver, checkinBuilder)
        val orchestrator = ProviderOrchestrator(com.anchor.core.provider.RateLimiter { clock.now() }, dummyTransport)
        val gateway = Gateway(storage, Resolver(), dateResolver, clock)
        val scope = CoroutineScope(testDispatcher)

        return ChatController(
            storage = storage,
            orchestrator = orchestrator,
            contextBuilder = contextBuilder,
            gateway = gateway,
            alarmScheduler = fakeScheduler,
            coroutineScope = scope,
            defaultDispatcher = testDispatcher,
            clock = clock,
            dateResolver = dateResolver,
            networkCheck = { networkOnline }
        )
    }

    @Test
    fun testOfflineQuickAdd_RemindMeAt_CreatesNodeAndSchedulesAlarmImmediately() = runTest(testDispatcher) {
        val controller = createController(networkOnline = false)

        controller.sendText("remind me in 30 mins to take medicine")

        // 1. Node must be created in storage
        val nodes = storage.getActiveNodes(10)
        val created = nodes.find { it.title == "take medicine" }
        assertNotNull("Task node must be created in storage while offline", created)
        assertEquals(clock.now() + 30 * 60 * 1000L, created!!.dueAt)

        // 2. Alarm must be scheduled with AlarmScheduler
        val alarm = scheduledAlarms.find { it.nodeId == created.id }
        assertNotNull("Alarm must be scheduled while offline", alarm)
        assertEquals(created.dueAt, alarm!!.fireAtMillis)

        // 3. Outbox must NOT have a chat_turn job (it was handled on-device)
        val pendingOutbox = storage.getPendingOutbox(OutboxJob.JOB_CHAT_TURN)
        assertTrue("No outbox job should be enqueued for handled quick-add", pendingOutbox.isEmpty())

        // 4. Assistant message must confirm
        val recentMessages = storage.getRecentMessages(10)
        val assistantReply = recentMessages.find { it.role == "assistant" }
        assertNotNull("Assistant reply must confirm the offline quick-add", assistantReply)
        assertTrue(assistantReply!!.content!!.contains("take medicine"))
    }

    @Test
    fun testOfflineQuickAdd_TodoPrefix_CreatesUndatedTaskImmediately() = runTest(testDispatcher) {
        val controller = createController(networkOnline = false)

        controller.sendText("todo: buy milk and bread")

        val nodes = storage.getActiveNodes(10)
        val created = nodes.find { it.title == "buy milk and bread" }
        assertNotNull("Task node must be created in storage while offline", created)
        assertEquals(null, created!!.dueAt)

        val pendingOutbox = storage.getPendingOutbox(OutboxJob.JOB_CHAT_TURN)
        assertTrue("No outbox job should be enqueued for handled quick-add", pendingOutbox.isEmpty())
    }

    @Test
    fun testOffline_NonQuickAddUtterance_IsQueuedToOutboxAsUsual() = runTest(testDispatcher) {
        val controller = createController(networkOnline = false)

        controller.sendText("What is the meaning of life?")

        // Non-matching utterance should be queued in outbox
        val pendingOutbox = storage.getPendingOutbox(OutboxJob.JOB_CHAT_TURN)
        assertEquals(1, pendingOutbox.size)
        assertEquals(OutboxJob.JOB_CHAT_TURN, pendingOutbox.first().jobType)
    }
}
