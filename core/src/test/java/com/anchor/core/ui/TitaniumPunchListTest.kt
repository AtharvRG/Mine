// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.ui

import com.anchor.core.SharedFakeStorage
import com.anchor.core.alarms.AlarmScheduler
import com.anchor.core.alarms.ScheduleResult
import com.anchor.core.context.ContextBuilder
import com.anchor.core.gateway.Gateway
import com.anchor.core.gateway.ToolCall
import com.anchor.core.models.ToolResult
import com.anchor.core.models.AlarmTier
import com.anchor.core.models.Node
import com.anchor.core.models.OutboxJob
import com.anchor.core.models.Reminder
import com.anchor.core.provider.HttpTransport
import com.anchor.core.provider.ProviderOrchestrator
import com.anchor.core.provider.RateLimiter
import com.anchor.core.provider.RateLimitPersistence
import com.anchor.core.provider.StorageRateLimitPersistence
import com.anchor.core.provider.Target
import com.anchor.core.resolver.Resolver
import com.anchor.core.time.DateResolver
import com.anchor.core.time.FakeClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TitaniumPunchListTest {

    private class FakeScheduler : AlarmScheduler {
        override val lastFiredId = MutableStateFlow<String?>(null)
        override fun schedule(reminder: Reminder) = ScheduleResult.Armed
        override fun cancel(reminderId: String) {}
    }

    private fun createController(
        storage: SharedFakeStorage,
        clock: FakeClock,
        transport: HttpTransport,
        networkCheck: () -> Boolean = { true }
    ): ChatController {
        val orchestrator = ProviderOrchestrator(RateLimiter { clock.now() }, transport)
        val dateResolver = DateResolver(clock)
        val checkinBuilder = com.anchor.core.nudge.CheckinBuilder(storage, clock, dateResolver)
        val contextBuilder = ContextBuilder(storage, clock, dateResolver, checkinBuilder)
        val scope = CoroutineScope(UnconfinedTestDispatcher())
        return ChatController(
            storage = storage,
            orchestrator = orchestrator,
            contextBuilder = contextBuilder,
            gateway = Gateway(storage, Resolver(), dateResolver, clock),
            alarmScheduler = FakeScheduler(),
            coroutineScope = scope,
            defaultDispatcher = UnconfinedTestDispatcher(),
            clock = clock,
            dateResolver = dateResolver,
            networkCheck = networkCheck
        )
    }

    /**
     * TB-1: Primary keys generated for raw_event (u_) and proposals (p_) must use full UUIDs
     * (not .take(6) or .take(8)) to eliminate birthday-paradox collision risks.
     */
    @Test
    fun testTB1_IdsUseFullUUIDEntropy() = runBlocking {
        val clock = FakeClock(1_000_000L)
        val storage = SharedFakeStorage(clock)
        val dateResolver = DateResolver(clock)
        val gateway = Gateway(storage, Resolver(), dateResolver, clock)

        // Propose change CREATE
        val call = ToolCall(
            tool = "propose_change",
            args = mapOf(
                "intent" to kotlinx.serialization.json.JsonPrimitive("CREATE"),
                "title" to kotlinx.serialization.json.JsonPrimitive("Buy groceries"),
                "due_text" to kotlinx.serialization.json.JsonPrimitive("tomorrow at 5pm")
            )
        )
        val result = gateway.executeTool(call, userMessage = "remind me tomorrow at 5pm to buy groceries")
        assertTrue(result is ToolResult.Committed)
        val committed = result as ToolResult.Committed
        assertTrue("Event ID must start with u_ and have full UUID length (>30 chars)", committed.undoToken.startsWith("u_") && committed.undoToken.length >= 36)
        assertTrue("Node ID must start with n_ and have full UUID length (>30 chars)", committed.targetId.startsWith("n_") && committed.targetId.length >= 36)
    }

    /**
     * TB-2: claimOutbox updates next_try_at in place rather than dropping the update via INSERT OR IGNORE.
     */
    @Test
    fun testTB2_ClaimOutboxAdvancesNextTryAt() = runBlocking {
        val clock = FakeClock(1_000_000L)
        val storage = SharedFakeStorage(clock)
        val job = OutboxJob(
            id = "ob_1",
            jobType = OutboxJob.JOB_CHAT_TURN,
            priority = 0,
            payloadJson = """{"msg_id":"u_1"}""",
            idemKey = "u_1",
            nextTryAt = 1_000_000L,
            createdAt = 900_000L,
            updatedAt = 900_000L
        )
        storage.enqueueOutbox(job)

        // Calling claimOutbox must advance nextTryAt
        storage.claimOutbox("ob_1", 1_060_000L, 1_000_000L)

        // Immediately checking due jobs at 1_000_000L returns nothing (it was claimed for 60s)
        val dueImmediate = storage.getDueOutbox(OutboxJob.JOB_CHAT_TURN, 1_000_000L)
        assertTrue("Claimed job must not be due immediately", dueImmediate.isEmpty())

        // Checking due jobs after 60s returns the job again
        val dueLater = storage.getDueOutbox(OutboxJob.JOB_CHAT_TURN, 1_060_001L)
        assertEquals("Job becomes due after claim window expires", 1, dueLater.size)
    }

    /**
     * TB-3: When offline batch is drained and LLM replies with a tool call and suppressed "Done!"
     * (finalAssistantText is blank), markBatchAnswered must still be called so the batch is not re-drained.
     */
    @Test
    fun testTB3_BatchDrain_ToolOnlyReply_MarksBatchAnswered() = runBlocking {
        val clock = FakeClock(1_000_000L)
        val storage = SharedFakeStorage(clock)
        storage.settings["key"] = "test-key"

        // Queue offline message
        storage.insertRawMessage("msg_offline_1", "user", "buy bread tomorrow at 10am")
        storage.enqueueOutbox(
            OutboxJob(
                id = "ob_msg_offline_1",
                jobType = OutboxJob.JOB_CHAT_TURN,
                priority = 0,
                payloadJson = """{"msg_id":"msg_offline_1"}""",
                idemKey = "msg_offline_1",
                nextTryAt = clock.now() - 1000L,
                createdAt = clock.now() - 2000L,
                updatedAt = clock.now() - 2000L
            )
        )

        // Transport returns a tool call with generic "Done!" reply (which gets suppressed by ChatController)
        val fakeTransport = object : HttpTransport {
            override suspend fun postStream(target: Target, body: String): Flow<String> = flow {
                emit("""data: {"choices":[{"delta":{"content":"{\"reply\":\"Done!\",\"calls\":[{\"tool\":\"propose_change\",\"args\":{\"intent\":\"CREATE\",\"title\":\"Buy bread\",\"due_text\":\"tomorrow at 10am\"}}]}"}}]}""")
                emit("data: [DONE]")
            }
        }

        val controller = createController(storage, clock, fakeTransport)

        // Trigger drain
        controller.onAction(UiAction.DrainQueuedTurns)

        while (controller.state.value.isChatLoading) {
            kotlinx.coroutines.delay(10)
        }

        // Job must be marked done
        val pending = storage.getPendingOutbox(OutboxJob.JOB_CHAT_TURN)
        assertTrue("TB-3: Batch job must be marked done even when assistant text is suppressed 'Done!'", pending.isEmpty())
        val allJobs = storage.outbox
        assertEquals("Outbox job must have status DONE", OutboxJob.STATUS_DONE, allJobs.first().status)
    }

    /**
     * TB-5: Exactly ONE system message is emitted per request, combining tool instructions and context.
     */
    @Test
    fun testTB5_ExactlyOneSystemMessageEmitted() = runBlocking {
        val clock = FakeClock(1_000_000L)
        val storage = SharedFakeStorage(clock)
        storage.settings["key"] = "test-key"
        var interceptedPayload = ""

        val fakeTransport = object : HttpTransport {
            override suspend fun postStream(target: Target, body: String): Flow<String> = flow {
                interceptedPayload = body
                emit("""data: {"choices":[{"delta":{"content":"{\"reply\":\"Understood.\",\"calls\":[]}"}}]}""")
                emit("data: [DONE]")
            }
        }

        val controller = createController(storage, clock, fakeTransport)
        controller.sendText("Hello there")

        while (controller.state.value.isChatLoading) {
            kotlinx.coroutines.delay(10)
        }

        val jsonObj = Json.parseToJsonElement(interceptedPayload).jsonObject
        val messages = jsonObj["messages"]!!.jsonArray

        val systemMessages = messages.filter { it.jsonObject["role"]?.jsonPrimitive?.content == "system" }
        assertEquals("TB-5: Exactly ONE system message must be sent per request", 1, systemMessages.size)

        val sysContent = systemMessages.single().jsonObject["content"]!!.jsonPrimitive.content
        assertTrue("Unified system prompt must contain Envelope Mode tool instructions", sysContent.contains("Envelope Mode JSON"))
        assertTrue("Unified system prompt must contain Active Items header", sysContent.contains("Active Items:"))
        assertTrue("Unified system prompt must contain Soul header", sysContent.contains("Soul:"))
    }

    /**
     * Feature Gap 2: 30-day Trash query, RestoreNode, and EraseNode permanent scrub.
     */
    @Test
    fun testFeatureGap2_TrashAndErase() = runBlocking {
        val clock = FakeClock(1_000_000L)
        val storage = SharedFakeStorage(clock)
        val dateResolver = DateResolver(clock)
        val gateway = Gateway(storage, Resolver(), dateResolver, clock)

        // Create an active item
        val node = Node("n_trash_1", "task", "Old Task", "active", dueAt = clock.now() + 10000L, createdAt = clock.now(), updatedAt = clock.now())
        val rem = Reminder("r_trash_1", "Old Task", clock.now() + 10000L, "n_trash_1")
        storage.createNodeAndCommit(node, com.anchor.core.models.RawEvent("u_create", "CREATE", "node", "n_trash_1"), rem)

        // Delete the item (moves to trash)
        val deleteResult = gateway.handleDeleteNode("n_trash_1")
        assertTrue(deleteResult is ToolResult.Committed)

        // Check trash
        val trash = storage.getTrashNodes(clock.now())
        assertEquals("Item must appear in 30-day trash", 1, trash.size)
        assertEquals("n_trash_1", trash.first().id)

        // Restore the item from trash
        val restoreResult = gateway.handleRestoreNode("n_trash_1")
        assertTrue(restoreResult is ToolResult.Committed)
        assertEquals("active", storage.getNode("n_trash_1")?.status)
        assertTrue("Trash must be empty after restore", storage.getTrashNodes(clock.now()).isEmpty())

        // Delete again and permanently erase
        gateway.handleDeleteNode("n_trash_1")
        val eraseResult = gateway.handleEraseNode("n_trash_1")
        assertTrue(eraseResult is ToolResult.Committed)
        assertNull("Node must be completely scrubbed from storage after erase", storage.getNode("n_trash_1"))
        assertTrue("Trash must be empty after erase", storage.getTrashNodes(clock.now()).isEmpty())
    }

    /**
     * Feature Gap 3 (Gate X-07): RateLimiter 429 cooldown state survives process death via storage persistence.
     */
    @Test
    fun testFeatureGap3_RateLimiter429SurvivesProcessDeath() = runBlocking {
        val clock = FakeClock(1_000_000L)
        val storage = SharedFakeStorage(clock)
        val persistence = StorageRateLimitPersistence(storage)

        // RateLimiter 1 applies 429 cooldown
        val rateLimiter1 = RateLimiter({ clock.now() }, persistence)
        assertTrue(rateLimiter1.canRequest("api_key_1", 20))
        rateLimiter1.apply429Cooldown("api_key_1", retryAfterSeconds = 1200) // 20 min cooldown

        assertFalse("Request must be rejected during cooldown", rateLimiter1.canRequest("api_key_1", 20))

        // Simulate process death and cold restart: RateLimiter 2 is instantiated fresh
        val rateLimiter2 = RateLimiter({ clock.now() }, persistence)
        assertFalse("Gate X-07: Fresh RateLimiter instance must honor persisted 429 cooldown", rateLimiter2.canRequest("api_key_1", 20))

        // Advance clock past cooldown
        clock.currentTime += 25 * 60 * 1000L // +25 minutes
        assertTrue("Request must be permitted once 429 cooldown expires", rateLimiter2.canRequest("api_key_1", 20))
    }

    /**
     * Adversarial test: Offline drain must ONLY answer claimed due jobs and must NOT drop
     * future-scheduled or undue jobs from the outbox.
     */
    @Test
    fun testTB3_OfflineBatchDoesNotDropUndueOutboxJobs() = runBlocking {
        val clock = FakeClock(1_000_000L)
        val storage = SharedFakeStorage(clock)
        storage.settings["key"] = "test-key"

        // Job 1 is due now
        storage.insertRawMessage("msg_due_1", "user", "buy milk")
        storage.enqueueOutbox(
            OutboxJob(
                id = "ob_msg_due_1",
                jobType = OutboxJob.JOB_CHAT_TURN,
                priority = 0,
                payloadJson = """{"msg_id":"msg_due_1"}""",
                idemKey = "msg_due_1",
                nextTryAt = clock.now() - 500L,
                createdAt = clock.now() - 1000L,
                updatedAt = clock.now() - 1000L
            )
        )

        // Job 2 is scheduled for retry in future (+10 minutes)
        storage.insertRawMessage("msg_future_2", "user", "call dentist")
        storage.enqueueOutbox(
            OutboxJob(
                id = "ob_msg_future_2",
                jobType = OutboxJob.JOB_CHAT_TURN,
                priority = 0,
                payloadJson = """{"msg_id":"msg_future_2"}""",
                idemKey = "msg_future_2",
                nextTryAt = clock.now() + 600_000L,
                createdAt = clock.now() - 500L,
                updatedAt = clock.now() - 500L
            )
        )

        val fakeTransport = object : HttpTransport {
            override suspend fun postStream(target: Target, body: String): Flow<String> = flow {
                emit("""data: {"choices":[{"delta":{"content":"{\"reply\":\"Understood, bought milk.\",\"calls\":[]}"}}]}""")
                emit("data: [DONE]")
            }
        }

        val controller = createController(storage, clock, fakeTransport)
        controller.onAction(UiAction.DrainQueuedTurns)

        while (controller.state.value.isChatLoading) {
            kotlinx.coroutines.delay(10)
        }

        // Job 1 must be DONE
        val job1 = storage.outbox.first { it.id == "ob_msg_due_1" }
        assertEquals("Due job must be marked done", OutboxJob.STATUS_DONE, job1.status)

        // Job 2 must STILL be pending and NOT marked done!
        val job2 = storage.outbox.first { it.id == "ob_msg_future_2" }
        assertEquals("Undue future job must remain pending", OutboxJob.STATUS_PENDING, job2.status)
        assertEquals(clock.now() + 600_000L, job2.nextTryAt)
    }

    /**
     * Adversarial test: Synthetic batch instruction must NEVER be inserted into raw_message.
     */
    @Test
    fun testTB3_OfflineBatchInstructionNeverInsertedIntoRawMessage() = runBlocking {
        val clock = FakeClock(1_000_000L)
        val storage = SharedFakeStorage(clock)
        storage.settings["key"] = "test-key"

        storage.insertRawMessage("msg_user_real", "user", "remember to hydrate")
        storage.enqueueOutbox(
            OutboxJob(
                id = "ob_msg_user_real",
                jobType = OutboxJob.JOB_CHAT_TURN,
                priority = 0,
                payloadJson = """{"msg_id":"msg_user_real"}""",
                idemKey = "msg_user_real",
                nextTryAt = clock.now() - 500L,
                createdAt = clock.now() - 1000L,
                updatedAt = clock.now() - 1000L
            )
        )

        val fakeTransport = object : HttpTransport {
            override suspend fun postStream(target: Target, body: String): Flow<String> = flow {
                emit("""data: {"choices":[{"delta":{"content":"{\"reply\":\"Hydration is key.\",\"calls\":[]}"}}]}""")
                emit("data: [DONE]")
            }
        }

        val controller = createController(storage, clock, fakeTransport)
        controller.onAction(UiAction.DrainQueuedTurns)

        while (controller.state.value.isChatLoading) {
            kotlinx.coroutines.delay(10)
        }

        // Verify raw_message has the user's real message and the assistant's reply, but NO synthetic prompt instruction
        val userMessages = storage.messages.filter { it.role == "user" }
        assertEquals("Only the original user message should exist as a user row", 1, userMessages.size)
        assertEquals("remember to hydrate", userMessages.single().content)
        assertFalse(
            "Synthetic offline instruction must NEVER be stored as a raw user message",
            storage.messages.any { it.content?.contains("The user was offline") == true }
        )
    }

    /**
     * Blueprint 12 & Gate E-12: Soul auto-accept mode, suppression of rejected facts, and erase fact.
     */
    @Test
    fun testSoulAutoAccept_Suppression_AndErase() = runBlocking {
        val clock = FakeClock(1_000_000L)
        val storage = SharedFakeStorage(clock)
        val dateResolver = DateResolver(clock)
        val gateway = Gateway(storage, Resolver(), dateResolver, clock)

        // 1. Propose fact in default "ask" mode -> proposed
        storage.settings["soul.mode"] = "ask"
        val call1 = ToolCall(
            tool = "soul_propose",
            args = mapOf(
                "statement" to kotlinx.serialization.json.JsonPrimitive("Prefers dark chocolate"),
                "category" to kotlinx.serialization.json.JsonPrimitive("diet"),
                "confidence" to kotlinx.serialization.json.JsonPrimitive("0.85")
            )
        )
        val res1 = gateway.executeTool(call1)
        assertTrue(res1 is ToolResult.Committed)
        val proposedFacts = storage.getProposedSoulFacts()
        assertEquals(1, proposedFacts.size)
        assertEquals("proposed", proposedFacts.first().status)

        // User rejects this fact
        val factId = proposedFacts.first().id
        storage.updateSoulFactStatus(factId, "rejected")

        // 2. Chat AI tries to propose the EXACT SAME fact again tomorrow -> must be suppressed!
        val callRepeat = ToolCall(
            tool = "soul_propose",
            args = mapOf(
                "statement" to kotlinx.serialization.json.JsonPrimitive("Prefers dark chocolate"),
                "category" to kotlinx.serialization.json.JsonPrimitive("diet")
            )
        )
        val resRepeat = gateway.executeTool(callRepeat)
        assertTrue("Repeated proposal of rejected fact must be suppressed", resRepeat is ToolResult.Error)
        assertEquals("Fact has been rejected by user and is suppressed", (resRepeat as ToolResult.Error).reason)

        // 3. In "auto" mode, facts with confidence >= 0.75 are auto-accepted into active status
        storage.settings["soul.mode"] = "auto"
        val callAuto = ToolCall(
            tool = "soul_propose",
            args = mapOf(
                "statement" to kotlinx.serialization.json.JsonPrimitive("Enjoys green tea"),
                "category" to kotlinx.serialization.json.JsonPrimitive("diet"),
                "confidence" to kotlinx.serialization.json.JsonPrimitive("0.80")
            )
        )
        val resAuto = gateway.executeTool(callAuto)
        assertTrue(resAuto is ToolResult.Committed)
        val activeFacts = storage.getActiveSoulFacts()
        assertTrue("Auto mode with conf >= 0.75 must auto-accept to active", activeFacts.any { it.statement == "Enjoys green tea" && it.status == "active" })

        // 4. Sensitive facts are NEVER auto-accepted even in auto mode
        val callSensitive = ToolCall(
            tool = "soul_propose",
            args = mapOf(
                "statement" to kotlinx.serialization.json.JsonPrimitive("Has severe asthma"),
                "category" to kotlinx.serialization.json.JsonPrimitive("medical"),
                "confidence" to kotlinx.serialization.json.JsonPrimitive("0.99")
            )
        )
        val resSensitive = gateway.executeTool(callSensitive)
        assertTrue(resSensitive is ToolResult.Committed)
        val sensitiveFact = storage.getProposedSoulFacts().first { it.statement == "Has severe asthma" }
        assertEquals("Sensitive facts must always be proposed, never auto-accepted", "proposed", sensitiveFact.status)

        // 5. Permanent Erase fact scrubs the fact and its event log
        val autoFact = activeFacts.first { it.statement == "Enjoys green tea" }
        val eraseRes = gateway.handleEraseSoulFact(autoFact.id)
        assertTrue(eraseRes is ToolResult.Committed)
        assertFalse("Erased fact must be scrubbed from active facts", storage.getActiveSoulFacts().any { it.id == autoFact.id })
    }
}
