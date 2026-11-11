// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.persona

import com.anchor.core.SharedFakeStorage
import com.anchor.core.alarms.AlarmScheduler
import com.anchor.core.alarms.ScheduleResult
import com.anchor.core.context.ContextBuilder
import com.anchor.core.gateway.Gateway
import com.anchor.core.models.Node
import com.anchor.core.models.Reminder
import com.anchor.core.nudge.CheckinBuilder
import com.anchor.core.provider.HttpTransport
import com.anchor.core.provider.ProviderOrchestrator
import com.anchor.core.provider.RateLimiter
import com.anchor.core.provider.Target
import com.anchor.core.resolver.Resolver
import com.anchor.core.time.DateResolver
import com.anchor.core.time.FakeClock
import com.anchor.core.ui.ChatController
import com.anchor.core.ui.UiAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonaIntegrationTest {

    private class FakeScheduler : AlarmScheduler {
        override val lastFiredId = MutableStateFlow<String?>(null)
        override fun schedule(reminder: Reminder) = ScheduleResult.Armed
        override fun cancel(reminderId: String) {}
    }

    private class FakeTransport : HttpTransport {
        override suspend fun postStream(target: Target, body: String): Flow<String> = flow {
            emit("""data: {"choices":[{"delta":{"content":"{\"reply\":\"Understood.\",\"calls\":[]}"}}]}""")
            emit("data: [DONE]")
        }
    }

    private fun controller(
        storage: SharedFakeStorage,
        clock: FakeClock,
        scope: CoroutineScope
    ): ChatController {
        val dateResolver = DateResolver(clock)
        val checkinBuilder = CheckinBuilder(storage, clock, dateResolver)
        val contextBuilder = ContextBuilder(storage, clock, dateResolver, checkinBuilder)
        val orchestrator = ProviderOrchestrator(RateLimiter { clock.now() }, FakeTransport())
        val gateway = Gateway(storage, Resolver(), dateResolver, clock)
        return ChatController(
            storage,
            orchestrator,
            contextBuilder,
            gateway,
            FakeScheduler(),
            scope,
            Dispatchers.Unconfined,
            clock,
            dateResolver = dateResolver
        )
    }

    @Test
    fun defaultPersonaIsCompanion_CanSwitchToCofounder() = runBlocking {
        val storage = SharedFakeStorage()
        val clock = FakeClock(100_000L)
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val c = controller(storage, clock, scope)

        assertEquals("companion", c.state.value.activePersonaId)
        assertEquals("Companion", c.state.value.activePersonaName)

        c.onAction(UiAction.SetPersona("cofounder"))
        assertEquals("cofounder", c.state.value.activePersonaId)
        assertEquals("Cofounder", c.state.value.activePersonaName)
        assertEquals("cofounder", storage.getSetting("persona.active"))

        // New controller restores persisted persona
        val c2 = controller(storage, clock, scope)
        assertEquals("cofounder", c2.state.value.activePersonaId)
        assertEquals("Cofounder", c2.state.value.activePersonaName)
    }

    @Test
    fun proactiveNudgeUsesActivePersonaTone() = runBlocking {
        val storage = SharedFakeStorage()
        val clock = FakeClock(100_000L)
        val scope = CoroutineScope(Dispatchers.Unconfined)

        val overdueNode = Node(
            id = "n_overdue",
            type = "task",
            title = "File quarterly taxes",
            status = "active",
            dueAt = 90_000L,
            createdAt = 10_000L,
            updatedAt = 10_000L,
            localOnly = false
        )
        storage.activeNodes.add(overdueNode)

        val c = controller(storage, clock, scope)
        c.onAction(UiAction.SetPersona("minimal"))
        c.onAction(UiAction.TickProactive)

        val nudges = storage.messages.filter { it.id.startsWith("n_") }
        assertEquals(1, nudges.size)
        val text = nudges.single().content!!
        assertTrue("Minimal persona nudge should be concise and contain title", text.contains("File quarterly taxes"))
        // Minimal template is e.g. "Overdue: File quarterly taxes."
        assertTrue("Minimal phrasing expected", text.startsWith("Overdue:") || text.startsWith("Pending:") || text.contains("(overdue)"))
    }

    @Test
    fun bereavementInRecentChat_TriggersToneSafetyOverrideInContextPack() = runBlocking {
        // Blueprint 24 §8 test 2: Cofounder persona given bereavement message drops sarcasm
        val storage = SharedFakeStorage()
        val clock = FakeClock(100_000L)
        val scope = CoroutineScope(Dispatchers.Unconfined)
        storage.saveSetting("persona.active", "cofounder")

        storage.insertRawMessage("m1", "user", "My grandfather passed away today and I am in mourning.")

        val dateResolver = DateResolver(clock)
        val checkinBuilder = CheckinBuilder(storage, clock, dateResolver)
        val contextBuilder = ContextBuilder(storage, clock, dateResolver, checkinBuilder)

        val pack = contextBuilder.buildPack()
        val sysMsg = pack.first().content ?: ""

        assertTrue("Tone safety must be triggered", sysMsg.contains("TONE SAFETY OVERRIDE"))
        assertTrue("Must enforce calm and supportive tone", sysMsg.contains("Calm, gentle, supportive"))
        org.junit.Assert.assertFalse("Sarcastic persona instructions must be stripped", sysMsg.contains("sarcastic"))
    }

    @Test
    fun customPersona_CanBeStoredAndActivated() = runBlocking {
        val storage = SharedFakeStorage()
        val clock = FakeClock(100_000L)
        val scope = CoroutineScope(Dispatchers.Unconfined)

        val customId = "custom_librarian"
        val customPersonaJson = """
            {"id":"$customId","name":"Librarian","kind":"custom","prompt":"PERSONA: Librarian\nVoice: Quiet, scholarly, patient.","tone":"WARM","humor":0,"strictness":1,"replyLength":"SHORT","emoji":false}
        """.trimIndent()
        storage.saveSetting("persona.custom.$customId", customPersonaJson)
        storage.saveSetting("persona.active", customId)

        val c = controller(storage, clock, scope)
        assertEquals(customId, c.state.value.activePersonaId)
        assertEquals("Librarian", c.state.value.activePersonaName)

        val dateResolver = DateResolver(clock)
        val checkinBuilder = CheckinBuilder(storage, clock, dateResolver)
        val contextBuilder = ContextBuilder(storage, clock, dateResolver, checkinBuilder)
        val pack = contextBuilder.buildPack()
        val sysMsg = pack.first().content ?: ""

        assertTrue("Context pack must contain custom persona prompt", sysMsg.contains("PERSONA: Librarian"))
        assertTrue(sysMsg.contains("scholarly, patient"))
    }
}
