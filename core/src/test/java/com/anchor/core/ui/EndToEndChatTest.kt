// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.ui

import com.anchor.core.SharedFakeStorage
import com.anchor.core.alarms.AlarmScheduler
import com.anchor.core.context.ContextBuilder
import com.anchor.core.gateway.Gateway
import com.anchor.core.models.Node
import com.anchor.core.models.Reminder
import com.anchor.core.provider.HttpTransport
import com.anchor.core.provider.ProviderOrchestrator
import com.anchor.core.provider.RateLimiter
import com.anchor.core.provider.Target
import com.anchor.core.resolver.Resolver
import com.anchor.core.time.DateResolver
import com.anchor.core.time.FakeClock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Test

class EndToEndChatTest {
    @Test
    fun testWhichDinnerLoop() = runBlocking {
        val storage = SharedFakeStorage()
        storage.settings["key"] = "test-key"
        storage.activeNodes.add(Node("n_tomo", "event", "Dinner with Raj", "active"))
        storage.activeNodes.add(Node("n_thur", "event", "Dinner with Sam", "active"))
        
        val clock = FakeClock(1000L)
        
        var turnCount = 0
        val fakeTransport = object : HttpTransport {
            override suspend fun postStream(target: Target, body: String): Flow<String> = flow {
                turnCount++
                if (turnCount == 1) {
                    emit("""data: {"choices":[{"delta":{"content":"```json\n{\"reply\":\"I see two.\",\"calls\":[{\"tool\":\"propose_change\",\"args\":{\"intent\":\"CANCEL\",\"hint\":\"the dinner\"}}]}\n```"}}]}""")
                } else if (turnCount == 2) {
                    val pId = storage.savedProposals.first().id
                    emit("""data: {"choices":[{"delta":{"content":"{\"reply\":\"Canceling Raj.\",\"calls\":[{\"tool\":\"confirm_change\",\"args\":{\"proposal\":\"$pId\",\"choice\":\"n_tomo\"}}]}"}}]}""")
                }
                emit("data: [DONE]")
            }
        }

        val orchestrator = ProviderOrchestrator(RateLimiter { clock.now() }, fakeTransport)
        val scheduler = object : AlarmScheduler {
            override val lastFiredId = MutableStateFlow<String?>(null)
            override fun schedule(reminder: Reminder) {}
            override fun cancel(reminderId: String) {}
        }
        
        val controller = ChatController(
            storage, orchestrator, ContextBuilder(storage, clock, DateResolver(clock)), 
            Gateway(storage, Resolver(), DateResolver(clock), clock), 
            scheduler, TestScope(UnconfinedTestDispatcher()), UnconfinedTestDispatcher(), clock
        )

        // User: "Cancel the dinner"
        controller.onAction(UiAction.UpdateDraft("Cancel the dinner"))
        controller.onAction(UiAction.SendMessage)
        assertEquals(1, turnCount) // Ambiguous ends turn immediately so UI can display choice selection
        
        // Verify node wasn't deleted, and a proposal was made
        assertEquals(2, storage.getActiveNodes().size)
        assertEquals(1, storage.savedProposals.size)

        // User: "Raj"
        controller.onAction(UiAction.UpdateDraft("Raj"))
        controller.onAction(UiAction.SendMessage)
        assertEquals(2, turnCount) // Confirm change executed and turn completed
        
        // Verify only Raj was cancelled
        val active = storage.getActiveNodes()
        assertEquals(1, active.size)
        assertEquals("n_thur", active[0].id)
    }
}
