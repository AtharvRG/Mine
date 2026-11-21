// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.ui

import com.anchor.core.SharedFakeStorage
import com.anchor.core.alarms.AlarmScheduler
import com.anchor.core.context.ContextBuilder
import com.anchor.core.gateway.Gateway
import com.anchor.core.models.ChatMessage
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class WorkerAiExtractionTest {

    private lateinit var storage: SharedFakeStorage
    private lateinit var clock: FakeClock
    private lateinit var controller: ChatController
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        clock = FakeClock(1700000000000L)
        storage = SharedFakeStorage(clock)
        val testDispatcher = UnconfinedTestDispatcher()
        val testScope = TestScope(testDispatcher)

        val fakeTransport = object : HttpTransport {
            override suspend fun postStream(target: Target, body: String): Flow<String> = flow {
                emit("""data: {"choices":[{"delta":{"content":"{\"reply\":\"ok\",\"calls\":[]}"}}]}""")
            }
        }

        val rateLimiter = RateLimiter(clock = { clock.now() })
        val orchestrator = ProviderOrchestrator(rateLimiter, fakeTransport)
        val dateResolver = DateResolver(clock, { false })
        val contextBuilder = ContextBuilder(storage, clock, dateResolver, CheckinBuilder(storage, clock, dateResolver))
        val gateway = Gateway(storage, Resolver(), dateResolver, clock)
        val fakeScheduler = object : AlarmScheduler {
            override val lastFiredId = MutableStateFlow<String?>(null)
            override fun schedule(reminder: Reminder) = com.anchor.core.alarms.ScheduleResult.Armed
            override fun cancel(reminderId: String) {}
        }

        controller = ChatController(
            storage = storage,
            orchestrator = orchestrator,
            contextBuilder = contextBuilder,
            gateway = gateway,
            alarmScheduler = fakeScheduler,
            coroutineScope = testScope,
            defaultDispatcher = testDispatcher,
            clock = clock,
            dateResolver = dateResolver,
            networkCheck = { true }
        )
    }

    @Test
    fun testWorkerAiExtraction_ExtractsLivingNotesAndLinksToMatchingTask() = runBlocking {
        // Active node matching conference
        val confTask = Node(
            id = "n_conf_123",
            title = "Attend Python Conference",
            type = "task",
            status = "active",
            createdAt = clock.now(),
            updatedAt = clock.now()
        )
        storage.saveNode(confTask)

        // Seed a user chat turn containing bullet points
        val userTurn = ChatMessage(
            id = "m_user_1",
            role = "user",
            content = "Conference packing:\n- Laptop charger\n- Slide clicker\n- Name badge",
            dayKey = "2026-11-14",
            tsUtc = clock.now()
        )
        storage.messages.add(userTurn)

        // Run worker AI extraction pipeline
        controller.runWorkerAiExtraction()

        // Verify note was extracted
        val note = storage.getTopicNoteByTopic("Conference packing")
        assertNotNull("Topic note should have been created by Worker AI", note)
        val bullets = json.decodeFromString<List<String>>(note!!.bulletsJson)
        assertEquals(listOf("Laptop charger", "Slide clicker", "Name badge"), bullets)

        // Verify living note was automatically linked to the matching active task
        val refs = storage.getReferencesForNode("n_conf_123")
        assertEquals(1, refs.size)
        assertEquals(note.id, refs[0].noteId)
    }

    @Test
    fun testWorkerAiExtraction_NaturalProse_CallsLlmAndUpsertsTopicNote() = runBlocking {
        var workerAiChatCalled = false
        val workerAiTransport = object : HttpTransport {
            override suspend fun postStream(target: Target, body: String): Flow<String> = flow {
                if (body.contains("Worker AI")) {
                    workerAiChatCalled = true
                    emit("""data: {"choices":[{"delta":{"content":"{\"reply\":\"\",\"calls\":[{\"tool\":\"upsert_topic_note\",\"args\":{\"topic\":\"Python Presentation Ideas\",\"bullets\":[\"Introduce asyncio patterns\",\"Benchmark JIT compiler\",\"Live coding demo\"]}}]}"}}]}""")
                } else {
                    emit("""data: {"choices":[{"delta":{"content":"{\"reply\":\"ok\",\"calls\":[]}"}}]}""")
                }
            }
        }

        val rateLimiter = RateLimiter(clock = { clock.now() })
        val orchestrator = ProviderOrchestrator(rateLimiter, workerAiTransport)
        val dateResolver = DateResolver(clock, { false })
        val contextBuilder = ContextBuilder(storage, clock, dateResolver, CheckinBuilder(storage, clock, dateResolver))
        val gateway = Gateway(storage, Resolver(), dateResolver, clock)
        val fakeScheduler = object : AlarmScheduler {
            override val lastFiredId = MutableStateFlow<String?>(null)
            override fun schedule(reminder: Reminder) = com.anchor.core.alarms.ScheduleResult.Armed
            override fun cancel(reminderId: String) {}
        }

        val testDispatcher = UnconfinedTestDispatcher()
        val testScope = TestScope(testDispatcher)

        // Seed provider key BEFORE creating ChatController
        storage.saveSetting("key", "test-provider-key")

        val workerController = ChatController(
            storage = storage,
            orchestrator = orchestrator,
            contextBuilder = contextBuilder,
            gateway = gateway,
            alarmScheduler = fakeScheduler,
            coroutineScope = testScope,
            defaultDispatcher = testDispatcher,
            clock = clock,
            dateResolver = dateResolver,
            networkCheck = { true }
        )

        // Seed natural prose message (>= 25 characters, NO bullets!)
        val proseTurn = ChatMessage(
            id = "m_user_2",
            role = "user",
            content = "For my upcoming Python conference talk, I am planning to introduce asyncio patterns, benchmark the new JIT compiler, and finish with a live coding demo.",
            dayKey = "2026-11-14",
            tsUtc = clock.now()
        )
        storage.messages.add(proseTurn)

        // Run worker AI extraction pipeline
        workerController.runWorkerAiExtraction()

        // Assert orchestrator was called by Worker AI
        assertTrue("Worker AI must call orchestrator.chat for natural prose", workerAiChatCalled)

        // Verify TopicNote was created via Gateway
        val note = storage.getTopicNoteByTopic("Python Presentation Ideas")
        assertNotNull("Topic note should have been created by Worker AI LLM", note)
        val bullets = json.decodeFromString<List<String>>(note!!.bulletsJson)
        assertEquals(listOf("Introduce asyncio patterns", "Benchmark JIT compiler", "Live coding demo"), bullets)
    }
}
