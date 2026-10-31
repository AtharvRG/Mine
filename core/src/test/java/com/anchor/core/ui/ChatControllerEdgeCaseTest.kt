// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.ui

import com.anchor.core.SharedFakeStorage
import com.anchor.core.alarms.AlarmScheduler
import com.anchor.core.context.ContextBuilder
import com.anchor.core.gateway.Gateway
import com.anchor.core.models.Reminder
import com.anchor.core.provider.HttpTransport
import com.anchor.core.provider.ProviderOrchestrator
import com.anchor.core.provider.RateLimiter
import com.anchor.core.provider.StreamEvent
import com.anchor.core.provider.Target
import com.anchor.core.resolver.Resolver
import com.anchor.core.time.Clock
import com.anchor.core.time.DateResolver
import com.anchor.core.time.FakeClock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.cancellation.CancellationException

class ChatControllerEdgeCaseTest {

    @Test
    fun testStopCancelsStreamAndPersistsInterrupted() = runBlocking {
        val storage = SharedFakeStorage().apply { settings["key"] = "test-key" }
        val clock = FakeClock(1000L)
        
        val transport = object : HttpTransport {
            override suspend fun postStream(target: Target, body: String): Flow<String> = flow {
                emit("""data: {"choices":[{"delta":{"content":"Halfway "}}]}""")
                kotlinx.coroutines.delay(100) // Yield to allow Stop action
                throw CancellationException("Stream cancelled by user")
            }
        }
        
        val controller = buildController(storage, clock, transport)
        controller.onAction(UiAction.UpdateDraft("Tell me a story"))
        controller.onAction(UiAction.SendMessage)
        
        controller.onAction(UiAction.Stop)
        
        // Wait for coroutines to settle
        kotlinx.coroutines.delay(200)
        
        val lastMsg = storage.messages.last()
        assertEquals("assistant", lastMsg.role)
        assertTrue("Must append [Interrupted] to partial text", lastMsg.content!!.contains("Halfway  [Interrupted]"))
        assertEquals(false, controller.state.value.isChatLoading)
    }

    @Test
    fun testProviderErrorRemovesLoadingAndShowsErrorBubble() = runBlocking {
        val storage = SharedFakeStorage().apply { settings["key"] = "test-key" }
        val clock = FakeClock(1000L)
        
        val transport = object : HttpTransport {
            override suspend fun postStream(target: Target, body: String): Flow<String> = flow {
                throw Exception("Provider error: HTTP 500")
            }
        }
        
        val controller = buildController(storage, clock, transport)
        controller.onAction(UiAction.UpdateDraft("Hello"))
        controller.onAction(UiAction.SendMessage)
        
        assertEquals(false, controller.state.value.isChatLoading)
        assertEquals("Error: All providers failed or in cooldown. Last error: Provider error: HTTP 500", controller.state.value.connectionError)
        // Ensure "Thinking..." bubble is removed
        assertTrue(controller.state.value.chatMessages.none { it.content == "Thinking..." })
    }

    @Test
    fun testReconcileAlarms_UpdatesTime_And_CancelsOrphans() = runBlocking {
        val storage = SharedFakeStorage().apply { settings["key"] = "test-key" }
        val clock = FakeClock(1000L)
        
        // 1. Initial State: DB has reminder at 5000, OS has it registered at 5000
        storage.reminders.add(Reminder("r1", "Task", 5000L))
        storage.saveRegisteredAlarm("r1", 1, 5000L)
        
        // 2. Orphan State: OS has an alarm that doesn't exist in DB
        storage.saveRegisteredAlarm("r2_orphan", 2, 8000L)
        
        val canceledIds = mutableListOf<String>()
        val scheduledIds = mutableListOf<String>()
        
        val scheduler = object : AlarmScheduler {
            override val lastFiredId = MutableStateFlow<String?>(null)
            override fun schedule(reminder: Reminder) { scheduledIds.add(reminder.id) }
            override fun cancel(reminderId: String) { canceledIds.add(reminderId) }
        }
        
        val controller = ChatController(
            storage, ProviderOrchestrator(RateLimiter { clock.now() }, object : HttpTransport { override suspend fun postStream(t: Target, b: String): Flow<String> = flow{} }), 
            com.anchor.core.context.ContextBuilder(storage, clock, DateResolver(clock), com.anchor.core.nudge.CheckinBuilder(storage, clock, DateResolver(clock))),
            Gateway(storage, Resolver(), DateResolver(clock), clock), scheduler, kotlinx.coroutines.CoroutineScope(UnconfinedTestDispatcher()), UnconfinedTestDispatcher(), clock
        )
        
        // 3. Change time in DB to 9000 (Simulating a RESCHEDULE)
        storage.reminders.clear()
        storage.reminders.add(Reminder("r1", "Task", 9000L))
        
        // Trigger reconcile via RefreshStats
        controller.onAction(UiAction.RefreshStats)
        kotlinx.coroutines.delay(100)
        
        assertTrue("Orphan alarm must be cancelled", canceledIds.contains("r2_orphan"))
        assertTrue("Rescheduled alarm must be re-scheduled because time changed", scheduledIds.contains("r1"))
    }

    private fun buildController(storage: com.anchor.core.storage.Storage, clock: Clock, transport: HttpTransport): ChatController {
        return ChatController(
            storage, ProviderOrchestrator(RateLimiter { clock.now() }, transport), 
            com.anchor.core.context.ContextBuilder(storage, clock, DateResolver(clock), com.anchor.core.nudge.CheckinBuilder(storage, clock, DateResolver(clock))),
            Gateway(storage, Resolver(), DateResolver(clock), clock),
            object : AlarmScheduler { override val lastFiredId = MutableStateFlow<String?>(null); override fun schedule(reminder: Reminder) {}; override fun cancel(reminderId: String) {} },
            kotlinx.coroutines.CoroutineScope(UnconfinedTestDispatcher()), UnconfinedTestDispatcher(), clock
        )
    }
}