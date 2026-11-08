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

        val dateResolver = DateResolver(clock)
        val checkinBuilder = com.anchor.core.nudge.CheckinBuilder(storage, clock, dateResolver)
        val contextBuilder = ContextBuilder(storage, clock, dateResolver, checkinBuilder)

        val controller = ChatController(
            storage, ProviderOrchestrator(RateLimiter { clock.now() }, fakeTransport), contextBuilder,
            Gateway(storage, Resolver(), dateResolver, clock),
            object : AlarmScheduler { override val lastFiredId = MutableStateFlow<String?>(null); override fun schedule(reminder: Reminder) = com.anchor.core.alarms.ScheduleResult.Armed; override fun cancel(reminderId: String) {} },
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
        // Removed schedule_reminder assertion, as it was stripped from the prompt to prevent double-alarms

        // 2. Verify End-to-End Creation
        assertEquals(1, storage.getActiveNodes(100).size)
        assertEquals("Dinner with Shyam", storage.getActiveNodes(100)[0].title)
    }

    @Test
    fun testR11_SystemPromptContainsTemporalAuthorityAndUntitledAlarmRules() = runBlocking {
        val storage = SharedFakeStorage()
        storage.settings["key"] = "test-key"
        val clock = FakeClock(1000L)
        var interceptedPayload = ""

        val fakeTransport = object : HttpTransport {
            override suspend fun postStream(target: Target, body: String): Flow<String> = flow {
                interceptedPayload = body
                emit("""data: {"choices":[{"delta":{"content":"```json\n{\"reply\":\"For what?\",\"calls\":[]}\n```"}}]}""")
                emit("data: [DONE]")
            }
        }

        val dateResolver = DateResolver(clock)
        val checkinBuilder = com.anchor.core.nudge.CheckinBuilder(storage, clock, dateResolver)
        val contextBuilder = ContextBuilder(storage, clock, dateResolver, checkinBuilder)

        val controller = ChatController(
            storage, ProviderOrchestrator(RateLimiter { clock.now() }, fakeTransport), contextBuilder,
            Gateway(storage, Resolver(), dateResolver, clock),
            object : AlarmScheduler { override val lastFiredId = MutableStateFlow<String?>(null); override fun schedule(reminder: Reminder) = com.anchor.core.alarms.ScheduleResult.Armed; override fun cancel(reminderId: String) {} },
            TestScope(UnconfinedTestDispatcher()), UnconfinedTestDispatcher(), clock
        )

        controller.onAction(UiAction.UpdateDraft("remind me in 2 mins"))
        controller.onAction(UiAction.SendMessage)

        val requestJson = kotlinx.serialization.json.Json.parseToJsonElement(interceptedPayload).jsonObject
        val messagesArr = requestJson["messages"]?.jsonArray
        val sysMsg = messagesArr?.firstOrNull()?.jsonObject?.get("content")?.jsonPrimitive?.content ?: ""

        // Fix 2: Prompt rule forbidding model from computing clock dates
        assertTrue("Prompt must instruct to copy user time words", sysMsg.contains("copy the user's own words for the time exactly"))
        assertTrue("Prompt must forbid computing clock time", sysMsg.contains("Never compute a clock time or date yourself"))
        assertTrue("Prompt must forbid reusing Now format", sysMsg.contains("never reuse the \"Now:\" format"))

        // Fix 3: Prompt rule for asking reason and untitled=true fallback
        assertTrue("Prompt must instruct to ask For what?", sysMsg.contains("For what?"))
        assertTrue("Prompt must describe untitled=true", sysMsg.contains("untitled=true"))
        assertTrue("Prompt must specify label whitelist", sysMsg.contains("alarm, reminder or notification"))

        // R6: Gentle tier documentation and rules
        assertTrue("Prompt must document tier argument", sysMsg.contains("tier: optional \"alarm\"|\"gentle\""))
        assertTrue("Prompt must specify gentle tier rule", sysMsg.contains("Use tier=\"gentle\" only when the user says low priority, quiet, or no alarm"))
    }

    @Test
    fun testO1_SetAlarmForSubject_IsNotTreatedAsMissingSubject() = runBlocking {
        // O-1: on the R10g release build, "set alarm for drink water in 3 minutes" produced the
        // prose "Setting an alarm to drink water." and no reminder. The MISSING_SUBJECT rule listed
        // "set an alarm for 7am" as an example of a time-only request, so a model reading it can
        // treat the verb "set an alarm" as the whole request and ask "For what?" — dropping the
        // subject and the time that were both present.
        val storage = SharedFakeStorage()
        storage.settings["key"] = "test-key"
        val clock = FakeClock(1000L)
        var interceptedPayload = ""

        val fakeTransport = object : HttpTransport {
            override suspend fun postStream(target: Target, body: String): Flow<String> = flow {
                interceptedPayload = body
                emit("""data: {"choices":[{"delta":{"content":"{\"reply\":\"Setting an alarm to drink water.\",\"calls\":[{\"tool\":\"propose_change\",\"args\":{\"intent\":\"CREATE\",\"title\":\"Drink water\",\"due_text\":\"in 3 minutes\"}}]}"}}]}""")
                emit("data: [DONE]")
            }
        }

        val dateResolver = DateResolver(clock)
        val checkinBuilder = com.anchor.core.nudge.CheckinBuilder(storage, clock, dateResolver)
        val contextBuilder = ContextBuilder(storage, clock, dateResolver, checkinBuilder)

        val controller = ChatController(
            storage, ProviderOrchestrator(RateLimiter { clock.now() }, fakeTransport), contextBuilder,
            Gateway(storage, Resolver(), dateResolver, clock),
            object : AlarmScheduler { override val lastFiredId = MutableStateFlow<String?>(null); override fun schedule(reminder: Reminder) = com.anchor.core.alarms.ScheduleResult.Armed; override fun cancel(reminderId: String) {} },
            TestScope(UnconfinedTestDispatcher()), UnconfinedTestDispatcher(), clock
        )

        controller.onAction(UiAction.UpdateDraft("set alarm for drink water in 3 minutes"))
        controller.onAction(UiAction.SendMessage)

        val requestJson = kotlinx.serialization.json.Json.parseToJsonElement(interceptedPayload).jsonObject
        val sysMsg = requestJson["messages"]?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("content")?.jsonPrimitive?.content ?: ""

        assertTrue(
            "A time is not a subject: 'set alarm for X in N minutes' must create X with that time",
            sysMsg.contains("A time is not a subject")
        )
        assertTrue(
            "The prompt must not offer a time-only 'set an alarm for 7am' as a MISSING_SUBJECT example",
            !Regex("MISSING ONLY when[^\\n]*set an alarm for 7am").containsMatchIn(sysMsg)
        )

        // The reminder is armed with the subject and the time the user gave.
        assertEquals(1, storage.getActiveNodes(100).size)
        assertEquals("Drink water", storage.getActiveNodes(100)[0].title)
        assertEquals(1, storage.reminders.size)
        assertEquals(1000L + 3 * 60_000L, storage.reminders[0].fireAtMillis)
    }

    @Test
    fun testO1_ProseOnlyReplyThatClaimsAChange_IsReportedAsNotDone() = runBlocking {
        // O-1's second half: even with a corrected prompt a model can answer in prose. When the
        // reply claims a reminder was set but no tool call was issued, nothing was stored — the
        // user must be told that, not shown a confirmation for a reminder that does not exist.
        val storage = SharedFakeStorage()
        storage.settings["key"] = "test-key"
        val clock = FakeClock(1000L)

        val fakeTransport = object : HttpTransport {
            override suspend fun postStream(target: Target, body: String): Flow<String> = flow {
                emit("""data: {"choices":[{"delta":{"content":"Setting an alarm to drink water."}}]}""")
                emit("data: [DONE]")
            }
        }

        val dateResolver = DateResolver(clock)
        val checkinBuilder = com.anchor.core.nudge.CheckinBuilder(storage, clock, dateResolver)
        val contextBuilder = ContextBuilder(storage, clock, dateResolver, checkinBuilder)

        val controller = ChatController(
            storage, ProviderOrchestrator(RateLimiter { clock.now() }, fakeTransport), contextBuilder,
            Gateway(storage, Resolver(), dateResolver, clock),
            object : AlarmScheduler { override val lastFiredId = MutableStateFlow<String?>(null); override fun schedule(reminder: Reminder) = com.anchor.core.alarms.ScheduleResult.Armed; override fun cancel(reminderId: String) {} },
            TestScope(UnconfinedTestDispatcher()), UnconfinedTestDispatcher(), clock
        )

        controller.onAction(UiAction.UpdateDraft("set alarm for drink water in 3 minutes"))
        controller.onAction(UiAction.SendMessage)

        assertEquals("Nothing may be stored when the model issued no tool call", 0, storage.reminders.size)
        val errorReceipts = controller.state.value.receipts.filter { it.isError }
        assertTrue(
            "A reply that claims a reminder but issued no tool call must produce an error receipt, " +
                "not a silent success. Receipts: ${controller.state.value.receipts}",
            errorReceipts.isNotEmpty()
        )
        val assistantText = controller.state.value.chatMessages.last { it.role == "assistant" }.content ?: ""
        assertTrue(
            "The assistant message must not read as a confirmation. Was: $assistantText",
            !assistantText.contains("Setting an alarm", ignoreCase = true)
        )
    }
}
