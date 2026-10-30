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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemPromptTest {
    @Test
    fun testSystemPromptContainsStrictCreateRulesAndTestsCreation() = runBlocking {
        val storage = SharedFakeStorage()
        storage.settings["key"] = "test-key"
        val clock = FakeClock(1000L)

        var interceptedPayload = ""
        var callCount = 0

        val fakeTransport = object : HttpTransport {
            override suspend fun postStream(target: Target, body: String): Flow<String> = flow {
                interceptedPayload = body
                callCount++
                if (callCount == 1) {
                    // Fake LLM obeying the strict prompt
                    emit("""data: {"choices":[{"delta":{"content":"```json\n{\"reply\":\"I'll add it.\",\"calls\":[{\"tool\":\"propose_change\",\"args\":{\"intent\":\"CREATE\",\"title\":\"Dinner with Shyam\",\"due_text\":\"tomorrow at 4pm\"}}]}\n```"}}]}""")
                } else {
                    emit("""data: {"choices":[{"delta":{"content":"Added."}}]}""")
                }
                emit("data: [DONE]")
            }
        }

        val controller = ChatController(
            storage, ProviderOrchestrator(RateLimiter { clock.now() }, fakeTransport), ContextBuilder(storage, clock, DateResolver(clock)),
            Gateway(storage, Resolver(), DateResolver(clock), clock),
            object : AlarmScheduler { override val lastFiredId = MutableStateFlow<String?>(null); override fun schedule(reminder: Reminder) {}; override fun cancel(reminderId: String) {} },
            TestScope(UnconfinedTestDispatcher()), UnconfinedTestDispatcher(), clock
        )

        controller.onAction(UiAction.UpdateDraft("I have a dinner with Shyam at 4 pm tomorrow"))
        controller.onAction(UiAction.SendMessage)

        // 1. Verify Prompt Assertions using parsed JSON to avoid escape conflicts
        val requestJson = kotlinx.serialization.json.Json.parseToJsonElement(interceptedPayload).jsonObject
        val messagesArr = requestJson["messages"]?.jsonArray
        val sysMsg = messagesArr?.firstOrNull()?.jsonObject?.get("content")?.jsonPrimitive?.content ?: ""

        assertTrue(sysMsg.contains("MUST use when user states an appointment, plan, task, commitment"))
        assertTrue(sysMsg.contains("unless you issue a tool call"))
        assertTrue(sysMsg.contains("confirm_change"))
        assertTrue(sysMsg.contains("schedule_reminder"))

        // 2. Verify End-to-End Creation
        assertEquals(1, storage.getActiveNodes().size)
        assertEquals("Dinner with Shyam", storage.getActiveNodes()[0].title)
    }
}
