// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.ui

import com.anchor.core.SharedFakeStorage
import com.anchor.core.alarms.AlarmScheduler
import com.anchor.core.context.ContextBuilder
import com.anchor.core.gateway.Gateway
import com.anchor.core.gateway.ToolCall
import com.anchor.core.models.*
import com.anchor.core.nudge.CheckinBuilder
import com.anchor.core.provider.HttpTransport
import com.anchor.core.provider.ProviderOrchestrator
import com.anchor.core.provider.RateLimiter
import com.anchor.core.provider.StreamEvent
import com.anchor.core.provider.Target
import com.anchor.core.resolver.Resolver
import com.anchor.core.storage.Storage
import com.anchor.core.time.DateResolver
import com.anchor.core.time.FakeClock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class R12ConcurrencyLifecycleTest {

    private fun buildController(
        storage: SharedFakeStorage,
        clock: FakeClock,
        transport: HttpTransport,
        scope: CoroutineScope,
        dispatcher: CoroutineDispatcher = Dispatchers.Unconfined
    ): ChatController {
        val orchestrator = ProviderOrchestrator(RateLimiter { clock.now() }, transport)
        val dateResolver = DateResolver(clock)
        val checkinBuilder = CheckinBuilder(storage, clock, dateResolver)
        val contextBuilder = ContextBuilder(storage, clock, dateResolver, checkinBuilder)
        val gateway = Gateway(storage, Resolver(), dateResolver, clock)
        val scheduler = object : AlarmScheduler {
            override val lastFiredId = MutableStateFlow<String?>(null)
            override fun schedule(reminder: Reminder): com.anchor.core.alarms.ScheduleResult =
                com.anchor.core.alarms.ScheduleResult.Armed
            override fun cancel(reminderId: String) {}
        }
        return ChatController(
            storage = storage,
            orchestrator = orchestrator,
            contextBuilder = contextBuilder,
            gateway = gateway,
            alarmScheduler = scheduler,
            coroutineScope = scope,
            defaultDispatcher = dispatcher,
            clock = clock
        )
    }

    // A-01 Window (a): Stop immediately after SendMessage (before placeholder is created)
    // Must NOT mutate or re-insert the previous turn's assistant message with [Interrupted]
    @Test
    fun testStopImmediatelyAfterSendMessage_DoesNotTouchPreviousAssistantMessage() = runBlocking {
        val clock = FakeClock(1000L)
        val insertGate = CompletableDeferred<Unit>()
        val proceedGate = CompletableDeferred<Unit>()

        val storage = object : SharedFakeStorage(clock) {
            override suspend fun insertRawMessage(id: String, role: String, content: String) {
                if (role == "user" && content == "Turn 2 message") {
                    insertGate.complete(Unit)
                    proceedGate.await()
                }
                super.insertRawMessage(id, role, content)
            }
        }.apply {
            settings["key"] = "test-key"
            // Previous turn's messages already persisted
            messages.add(ChatMessage("u_prev", "user", "Turn 1 question", "2026-11-04", 500L))
            messages.add(ChatMessage("a_prev", "assistant", "Turn 1 answer", "2026-11-04", 600L))
        }

        val transport = object : HttpTransport {
            override suspend fun postStream(target: Target, body: String): Flow<String> = flow {
                emit("""data: {"choices":[{"delta":{"content":"Turn 2 reply"}}]}""")
            }
        }

        val testScope = CoroutineScope(Dispatchers.Default + Job())
        try {
            val controller = buildController(storage, clock, transport, testScope, Dispatchers.Default)

            // Initialize UI with previous messages
            controller.onAction(UiAction.RefreshStats)
            var initDone = false
            val startWait = System.currentTimeMillis()
            while (!initDone && System.currentTimeMillis() - startWait < 2000L) {
                if (controller.state.value.chatMessages.any { it.id == "a_prev" }) initDone = true
                yield()
            }
            assertTrue("Previous turn message must be loaded into UI state", initDone)

            controller.onAction(UiAction.UpdateDraft("Turn 2 message"))
            controller.onAction(UiAction.SendMessage)

            // Wait until user message insertion has started
            insertGate.await()

            // Stop is clicked while user message insertion is pending / before placeholder is created
            controller.onAction(UiAction.Stop)
            proceedGate.complete(Unit)

            // Wait until loading finishes
            var finished = false
            val deadline = System.currentTimeMillis() + 2000L
            while (!finished && System.currentTimeMillis() - deadline < 0) {
                if (!controller.state.value.isChatLoading) finished = true
                yield()
            }
            assertTrue("Chat loading should be reset to false within deadline", finished)

            // Previous turn's assistant message MUST NOT be corrupted with [Interrupted]
            val prevAssistant = storage.messages.find { it.id == "a_prev" }
            assertNotNull("Previous assistant message must exist", prevAssistant)
            assertEquals("Turn 1 answer", prevAssistant!!.content)
            assertFalse("Previous assistant message must NOT be marked [Interrupted]", prevAssistant.content!!.contains("[Interrupted]"))

            // Assert that raw_message contains NO duplicate or re-inserted messages
            val aPrevCount = storage.messages.count { it.id == "a_prev" }
            assertEquals("Previous assistant message must exist exactly once in storage", 1, aPrevCount)
        } finally {
            testScope.cancel()
        }
    }

    // A-01 Window (b): Stop right after insertRawMessage of botId
    // Must NOT insert the bot message a second time or create duplicate row
    @Test
    fun testStopRightAfterInsertRawMessage_DoesNotInsertDuplicateMessage() = runBlocking {
        val clock = FakeClock(1000L)
        val botInsertGate = CompletableDeferred<Unit>()
        val proceedGate = CompletableDeferred<Unit>()

        val storage = object : SharedFakeStorage(clock) {
            override suspend fun insertRawMessage(id: String, role: String, content: String) {
                super.insertRawMessage(id, role, content)
                if (role == "assistant" && !content.contains("[Interrupted]")) {
                    botInsertGate.complete(Unit)
                    proceedGate.await()
                }
            }
        }.apply {
            settings["key"] = "test-key"
        }

        val transport = object : HttpTransport {
            override suspend fun postStream(target: Target, body: String): Flow<String> = flow {
                emit("""data: {"choices":[{"delta":{"content":"{\"reply\":\"Real reply text\"}"}}]}""")
            }
        }

        val testScope = CoroutineScope(Dispatchers.Default + Job())
        try {
            val controller = buildController(storage, clock, transport, testScope, Dispatchers.Default)

            var initDone = false
            val startWait = System.currentTimeMillis()
            while (!initDone && System.currentTimeMillis() - startWait < 2000L) {
                if (controller.state.value.providerKey.isNotBlank()) initDone = true
                yield()
            }
            assertTrue("Provider key must be loaded", initDone)

            controller.onAction(UiAction.UpdateDraft("Hello"))
            controller.onAction(UiAction.SendMessage)

            // Wait until assistant message is inserted in storage
            botInsertGate.await()

            // Stop right as insertRawMessage completed
            controller.onAction(UiAction.Stop)
            proceedGate.complete(Unit)

            var finished = false
            val deadline = System.currentTimeMillis() + 2000L
            while (!finished && System.currentTimeMillis() - deadline < 0) {
                if (!controller.state.value.isChatLoading) finished = true
                yield()
            }
            assertTrue("Chat loading should be reset to false within deadline", finished)

            // Assistant messages in storage must not have duplicate IDs
            val assistantMsgs = storage.messages.filter { it.role == "assistant" }
            assertEquals("Must have exactly one assistant message stored", 1, assistantMsgs.size)
            assertFalse("Must NOT contain [Interrupted] because reply was already successfully committed", assistantMsgs.first().content!!.contains("[Interrupted]"))
        } finally {
            testScope.cancel()
        }
    }

    // A-01: Storage throwing during cancellation teardown must not crash parent scope or suppress CancellationException
    @Test
    fun testStorageThrowingDuringCancellation_DoesNotCrashScopeOrSuppressCancellation() = runBlocking {
        val clock = FakeClock(1000L)
        val streamStartedGate = CompletableDeferred<Unit>()
        val proceedGate = CompletableDeferred<Unit>()

        val storage = object : SharedFakeStorage(clock) {
            override suspend fun insertRawMessage(id: String, role: String, content: String) {
                if (role == "assistant" && content.contains("[Interrupted]")) {
                    throw RuntimeException("Simulated disk failure during cancellation teardown")
                }
                super.insertRawMessage(id, role, content)
            }
        }.apply {
            settings["key"] = "test-key"
        }

        val transport = object : HttpTransport {
            override suspend fun postStream(target: Target, body: String): Flow<String> = flow {
                emit("""data: {"choices":[{"delta":{"content":"Partial response text"}}]}""")
                streamStartedGate.complete(Unit)
                proceedGate.await()
            }
        }

        var uncaughtException: Throwable? = null
        val handler = CoroutineExceptionHandler { _, exception ->
            uncaughtException = exception
        }
        val testScope = CoroutineScope(Dispatchers.Default + Job() + handler)
        try {
            val controller = buildController(storage, clock, transport, testScope, Dispatchers.Default)

            var initDone = false
            val startWait = System.currentTimeMillis()
            while (!initDone && System.currentTimeMillis() - startWait < 2000L) {
                if (controller.state.value.providerKey.isNotBlank()) initDone = true
                yield()
            }
            assertTrue("Provider key must be loaded", initDone)

            controller.onAction(UiAction.UpdateDraft("Hello world"))
            controller.onAction(UiAction.SendMessage)

            // Wait until streaming has begun
            streamStartedGate.await()

            // Wait until placeholder message contains "Partial response text"
            var partialVisible = false
            val partialDeadline = System.currentTimeMillis() + 2000L
            while (!partialVisible && System.currentTimeMillis() - partialDeadline < 0) {
                if (controller.state.value.chatMessages.any { it.content?.contains("Partial response text") == true }) {
                    partialVisible = true
                }
                yield()
            }
            assertTrue("Partial message must be visible in UI state", partialVisible)

            // Stop stream while streaming — triggers cancellation teardown
            controller.onAction(UiAction.Stop)
            proceedGate.complete(Unit)

            // Wait for teardown to finish (isChatLoading should become false)
            var finished = false
            val deadline = System.currentTimeMillis() + 2000L
            while (!finished && System.currentTimeMillis() - deadline < 0) {
                if (!controller.state.value.isChatLoading) finished = true
                yield()
            }
            assertTrue("Chat loading should be reset to false in finally", finished)

            // Verify no uncaught exception was delivered to CoroutineExceptionHandler
            assertNull("Teardown storage exception must not escape or crash CoroutineScope: $uncaughtException", uncaughtException)
            assertTrue("Test scope must still be active", testScope.isActive)
        } finally {
            testScope.cancel()
        }
    }

    // A-04: Two concurrent ConfirmProposal on one time-chip CREATE proposal (untitled alarm)
    // Exactly 1 node, 1 reminder, 1 success receipt. Second caller gets "Proposal already resolved"
    @Test
    fun testConcurrentConfirmProposal_TimeChipCreate_ExactlyOneNodeAndReminder() = runBlocking {
        val clock = FakeClock(1000L)
        val pId = "p_time_1"
        var getProposalCount = 0
        val bothReadGate = CompletableDeferred<Unit>()

        val storage = object : SharedFakeStorage(clock) {
            override suspend fun getProposal(id: String): Proposal? {
                val p = super.getProposal(id)
                if (p?.id == pId && p.status == "open") {
                    val count = synchronized(this) { ++getProposalCount }
                    if (count >= 2) {
                        bothReadGate.complete(Unit)
                    } else {
                        withTimeout(5000L) { bothReadGate.await() }
                    }
                }
                return p
            }
        }
        val dateResolver = DateResolver(clock)
        val gateway = Gateway(storage, Resolver(), dateResolver, clock)

        // Setup a time-chip proposal for an untitled alarm
        val fireAt = clock.now() + 3600_000L
        val payload = ProposalPayload(
            intent = IntentType.CREATE,
            untitled = true,
            label = "alarm",
            tier = AlarmTier.ALARM
        )
        val proposal = Proposal(
            id = pId,
            kind = "time",
            intent = IntentType.CREATE,
            options = listOf(ProposalOption(fireAt.toString(), "11:00 AM")),
            status = "open",
            payload = Json.encodeToString(payload)
        )
        storage.saveProposal(proposal)

        // Two concurrent confirmations on the same proposal
        val deferred1 = async(Dispatchers.Default) {
            gateway.executeTool(
                ToolCall("confirm_change", mapOf("proposal" to JsonPrimitive(pId), "choice" to JsonPrimitive(fireAt.toString())))
            )
        }
        val deferred2 = async(Dispatchers.Default) {
            gateway.executeTool(
                ToolCall("confirm_change", mapOf("proposal" to JsonPrimitive(pId), "choice" to JsonPrimitive(fireAt.toString())))
            )
        }

        val res1 = withTimeout(5000L) { deferred1.await() }
        val res2 = withTimeout(5000L) { deferred2.await() }

        val results = listOf(res1, res2)
        val committedCount = results.count { it is ToolResult.Committed }
        val errorCount = results.count { it is ToolResult.Error && it.reason == "Proposal already resolved" }

        assertEquals("Exactly one confirmation must commit", 1, committedCount)
        assertEquals("The concurrent second call must receive 'Proposal already resolved'", 1, errorCount)

        // Storage assertions: exactly 1 node and 1 reminder
        assertEquals("Exactly 1 node must be created", 1, storage.activeNodes.size)
        assertEquals("Exactly 1 reminder must be created", 1, storage.reminders.size)
        assertEquals("Proposal must be consumed", "consumed", storage.getProposal(pId)?.status)
    }

    // A-04: Two concurrent ConfirmProposal on a CANCEL chip
    // Exactly 1 commit, exactly 1 CANCEL event, second gets "Proposal already resolved"
    @Test
    fun testConcurrentConfirmProposal_CancelChip_ExactlyOneCommitAndEvent() = runBlocking {
        val clock = FakeClock(1000L)
        val pId = "p_cancel_1"
        var getProposalCount = 0
        val bothReadGate = CompletableDeferred<Unit>()

        val storage = object : SharedFakeStorage(clock) {
            override suspend fun getProposal(id: String): Proposal? {
                val p = super.getProposal(id)
                if (p?.id == pId && p.status == "open") {
                    val count = synchronized(this) { ++getProposalCount }
                    if (count >= 2) {
                        bothReadGate.complete(Unit)
                    } else {
                        withTimeout(5000L) { bothReadGate.await() }
                    }
                }
                return p
            }
        }
        val dateResolver = DateResolver(clock)
        val gateway = Gateway(storage, Resolver(), dateResolver, clock)

        val node = Node("n_cancel_1", "task", "Buy Groceries", "active", clock.now() + 3600_000L)
        storage.activeNodes.add(node)

        val proposal = Proposal(
            id = pId,
            kind = "node",
            intent = IntentType.CANCEL,
            options = listOf(ProposalOption("n_cancel_1", "Buy Groceries")),
            status = "open"
        )
        storage.saveProposal(proposal)

        val deferred1 = async(Dispatchers.Default) {
            gateway.executeTool(
                ToolCall("confirm_change", mapOf("proposal" to JsonPrimitive(pId), "choice" to JsonPrimitive("n_cancel_1")))
            )
        }
        val deferred2 = async(Dispatchers.Default) {
            gateway.executeTool(
                ToolCall("confirm_change", mapOf("proposal" to JsonPrimitive(pId), "choice" to JsonPrimitive("n_cancel_1")))
            )
        }

        val res1 = withTimeout(5000L) { deferred1.await() }
        val res2 = withTimeout(5000L) { deferred2.await() }

        val results = listOf(res1, res2)
        val committedCount = results.count { it is ToolResult.Committed }
        val errorCount = results.count { it is ToolResult.Error && it.reason == "Proposal already resolved" }

        assertEquals("Exactly one confirmation must commit", 1, committedCount)
        assertEquals("Concurrent second call must receive 'Proposal already resolved'", 1, errorCount)

        val cancelEvents = storage.loggedEvents.filter { it.opType == "CANCEL" && it.targetId == "n_cancel_1" }
        assertEquals("Exactly 1 CANCEL event must be logged", 1, cancelEvents.size)
        assertEquals("Node status must be cancelled", "cancelled", storage.getNode("n_cancel_1")?.status)
        assertEquals("Proposal must be consumed", "consumed", storage.getProposal(pId)?.status)
    }

    // A-09: Paused node via chip: committed
    @Test
    fun testPausedNodeViaChip_Committed() = runBlocking {
        val clock = FakeClock(1000L)
        val storage = SharedFakeStorage(clock)
        val dateResolver = DateResolver(clock)
        val gateway = Gateway(storage, Resolver(), dateResolver, clock)

        val node = Node("n_paused_1", "task", "Read Book", "paused", clock.now() + 3600_000L)
        storage.activeNodes.add(node)

        val pId = "p_paused_1"
        val proposal = Proposal(
            id = pId,
            kind = "node",
            intent = IntentType.COMPLETE,
            options = listOf(ProposalOption("n_paused_1", "Read Book")),
            status = "open"
        )
        storage.saveProposal(proposal)

        val result = gateway.executeTool(
            ToolCall("confirm_change", mapOf("proposal" to JsonPrimitive(pId), "choice" to JsonPrimitive("n_paused_1")))
        )

        assertTrue("Paused node confirmation must succeed: $result", result is ToolResult.Committed)
        assertEquals("Node status must be updated to done", "done", storage.getNode("n_paused_1")?.status)
        assertEquals("Proposal must be consumed", "consumed", storage.getProposal(pId)?.status)
    }

    // A-09: Failed confirm leaves proposal consumed on terminal failure ("Node not found" / "no longer active")
    // Non-terminal "Invalid choice" does NOT consume
    @Test
    fun testFailedConfirm_TerminalLeavesProposalConsumed_InvalidChoicePreserves() = runBlocking {
        val clock = FakeClock(1000L)
        val storage = SharedFakeStorage(clock)
        val dateResolver = DateResolver(clock)
        val gateway = Gateway(storage, Resolver(), dateResolver, clock)

        // Case 1: Terminal failure "Node not found"
        val p1 = Proposal("p_term_1", "node", IntentType.CANCEL, listOf(ProposalOption("n_missing", "Missing Item")), "open")
        storage.saveProposal(p1)

        val res1 = gateway.executeTool(
            ToolCall("confirm_change", mapOf("proposal" to JsonPrimitive("p_term_1"), "choice" to JsonPrimitive("n_missing")))
        )
        assertTrue("Must return error: $res1", res1 is ToolResult.Error)
        assertEquals("Node not found", (res1 as ToolResult.Error).reason)
        assertEquals("Proposal must be consumed on terminal 'Node not found' failure", "consumed", storage.getProposal("p_term_1")?.status)

        // Case 2: Terminal failure "Node is no longer active"
        val archivedNode = Node("n_archived", "task", "Old Task", "archived")
        storage.activeNodes.add(archivedNode)
        val p2 = Proposal("p_term_2", "node", IntentType.CANCEL, listOf(ProposalOption("n_archived", "Old Task")), "open")
        storage.saveProposal(p2)

        val res2 = gateway.executeTool(
            ToolCall("confirm_change", mapOf("proposal" to JsonPrimitive("p_term_2"), "choice" to JsonPrimitive("n_archived")))
        )
        assertTrue("Must return error: $res2", res2 is ToolResult.Error)
        assertEquals("Node is no longer active", (res2 as ToolResult.Error).reason)
        assertEquals("Proposal must be consumed on terminal 'Node is no longer active' failure", "consumed", storage.getProposal("p_term_2")?.status)

        // Case 3: Non-terminal failure "Invalid choice" (user picked option not in proposal)
        val p3 = Proposal("p_nonterm", "node", IntentType.CANCEL, listOf(ProposalOption("n_valid", "Valid Task")), "open")
        storage.saveProposal(p3)

        val res3 = gateway.executeTool(
            ToolCall("confirm_change", mapOf("proposal" to JsonPrimitive("p_nonterm"), "choice" to JsonPrimitive("n_bogus")))
        )
        assertTrue("Must return error: $res3", res3 is ToolResult.Error)
        assertEquals("Invalid choice", (res3 as ToolResult.Error).reason)
        assertEquals("Proposal must REMAIN open on non-terminal 'Invalid choice'", "open", storage.getProposal("p_nonterm")?.status)
    }

    // B3: Proposal rolls back to "open" if storage mutation throws an exception
    @Test
    fun testConfirmProposal_RollbackOnStorageFailure() = runBlocking {
        val clock = FakeClock(1000L)
        val storage = object : SharedFakeStorage(clock) {
            override suspend fun updateNodeAndCommit(
                node: Node,
                event: RawEvent,
                deleteReminderId: String?,
                upsertReminder: Reminder?,
                consumeProposalId: String?,
                softDeleteEdgeIds: List<String>
            ) {
                throw RuntimeException("Simulated disk error during commit")
            }
        }
        val dateResolver = DateResolver(clock)
        val gateway = Gateway(storage, Resolver(), dateResolver, clock)

        val node = Node("n_fail_1", "task", "Clean Room", "active")
        storage.activeNodes.add(node)
        val proposal = Proposal("p_fail_1", "node", IntentType.CANCEL, listOf(ProposalOption("n_fail_1", "Clean Room")), "open")
        storage.saveProposal(proposal)

        var thrown = false
        try {
            gateway.executeTool(ToolCall("confirm_change", mapOf("proposal" to JsonPrimitive("p_fail_1"), "choice" to JsonPrimitive("n_fail_1"))))
        } catch (e: RuntimeException) {
            thrown = true
            assertEquals("Simulated disk error during commit", e.message)
        }

        assertTrue("Exception must be rethrown", thrown)
        assertEquals("Proposal must be rolled back to 'open' when commit fails", "open", storage.getProposal("p_fail_1")?.status)
    }

    // P1-B 17: Proposal transitions to "needs_confirm" on near-match duplicate during time chip creation
    @Test
    fun testConfirmProposal_TransitionsToNeedsConfirmOnNearMatchDuplicate() = runBlocking {
        val clock = FakeClock(1000L)
        val storage = SharedFakeStorage(clock)
        val dateResolver = DateResolver(clock)
        val gateway = Gateway(storage, Resolver(), dateResolver, clock)

        // Existing active item
        val existing = Node("n_exist_1", "task", "Gym Workout", "active")
        storage.activeNodes.add(existing)

        val fireAt = clock.now() + 3600_000L
        val payload = ProposalPayload(
            intent = IntentType.CREATE,
            title = "Gym Workout",
            untitled = false
        )
        val proposal = Proposal(
            id = "p_dup_1",
            kind = "time",
            intent = IntentType.CREATE,
            options = listOf(ProposalOption(fireAt.toString(), "11:00 AM")),
            status = "open",
            payload = Json.encodeToString(payload)
        )
        storage.saveProposal(proposal)

        val result = gateway.executeTool(
            ToolCall("confirm_change", mapOf("proposal" to JsonPrimitive("p_dup_1"), "choice" to JsonPrimitive(fireAt.toString())))
        )

        assertTrue("Must return NeedsConfirm: $result", result is ToolResult.NeedsConfirm)
        val needsConfirm = result as ToolResult.NeedsConfirm
        assertTrue("Message mentions duplicate title", needsConfirm.message.contains("Gym Workout"))
        assertEquals("Old time proposal is consumed", "consumed", storage.getProposal("p_dup_1")?.status)
        assertNotNull("New needs_confirm proposal is open", storage.getProposal(needsConfirm.proposalId))
        assertEquals("open", storage.getProposal(needsConfirm.proposalId)?.status)
    }

    // R12c Item 1: Proposal rollback must NOT extend 24h expiry
    @Test
    fun testRollbackDoesNotExtend24hExpiry_ExpiryMustNotExtendAfterRolledBackConfirm() = runBlocking {
        val t0 = 1_000_000L
        val clock = FakeClock(t0)
        var shouldFailCommit = false
        val storage = object : SharedFakeStorage(clock) {
            override suspend fun createNodeAndCommit(
                node: Node,
                event: RawEvent,
                reminder: Reminder?,
                edge: Edge?,
                edgeEvent: RawEvent?,
                consumeProposalId: String?
            ) {
                if (shouldFailCommit && consumeProposalId == "p_rollback_expiry_1") {
                    throw RuntimeException("Simulated disk error during commit")
                }
                super.createNodeAndCommit(node, event, reminder, edge, edgeEvent, consumeProposalId)
            }
        }
        val dateResolver = DateResolver(clock)
        val gateway = Gateway(storage, Resolver(), dateResolver, clock)

        val fireAt = t0 + (25 * 3600 * 1000L)
        val payload = ProposalPayload(
            intent = IntentType.CREATE,
            title = "Gym Workout",
            untitled = false
        )
        val proposal = Proposal(
            id = "p_rollback_expiry_1",
            kind = "time",
            intent = IntentType.CREATE,
            options = listOf(ProposalOption(fireAt.toString(), "11:00 AM")),
            status = "open",
            payload = Json.encodeToString(payload)
        )
        // Saved at T0
        storage.saveProposal(proposal)
        assertNotNull("Proposal exists right after creation", storage.getProposal("p_rollback_expiry_1"))

        // Advance clock to 23 hours and 50 minutes after T0 (10 minutes before 24h expiry)
        val tRollback = t0 + (23 * 3600 * 1000L) + (50 * 60 * 1000L)
        clock.currentTime = tRollback

        // Attempt confirm with simulated commit failure; catches and rolls back proposal
        shouldFailCommit = true
        var caught = false
        try {
            gateway.executeTool(
                ToolCall("confirm_change", mapOf("proposal" to JsonPrimitive("p_rollback_expiry_1"), "choice" to JsonPrimitive(fireAt.toString())))
            )
        } catch (e: RuntimeException) {
            caught = true
            assertEquals("Simulated disk error during commit", e.message)
        }
        assertTrue("Simulated exception must be thrown", caught)
        assertEquals("Proposal must be rolled back to 'open'", "open", storage.getProposal("p_rollback_expiry_1")?.status)

        // Advance clock to 24 hours and 10 minutes after T0 (past original 24h expiry)
        val tExpired = t0 + (24 * 3600 * 1000L) + (10 * 60 * 1000L)
        clock.currentTime = tExpired

        // The proposal's 24-hour expiry MUST NOT have been extended by the rollback!
        assertNull("Proposal must be expired after 24h from original creation", storage.getProposal("p_rollback_expiry_1"))
        assertTrue("getOpenProposals must not return expired proposal", storage.getOpenProposals().none { it.id == "p_rollback_expiry_1" })
    }

    // B3: Loser UX in ChatController: concurrent or second ConfirmProposal does NOT show an error banner
    @Test
    fun testUiConfirmProposal_LoserDoesNotShowErrorBanner() = runBlocking {
        val clock = FakeClock(1000L)
        val storage = SharedFakeStorage(clock)
        val transport = object : HttpTransport {
            override suspend fun postStream(target: Target, body: String): Flow<String> = flow {}
        }
        val testScope = CoroutineScope(Dispatchers.Default + Job())
        try {
            val controller = buildController(storage, clock, transport, testScope, Dispatchers.Default)

            val node = Node("n_ux_1", "task", "Pay Bills", "active")
            storage.activeNodes.add(node)
            val proposal = Proposal("p_ux_1", "node", IntentType.COMPLETE, listOf(ProposalOption("n_ux_1", "Pay Bills")), "open")
            storage.saveProposal(proposal)

            // Winner tap
            controller.onAction(UiAction.ConfirmProposal("p_ux_1", "n_ux_1"))

            // Wait until winner commits
            var winnerCommitted = false
            val deadline1 = System.currentTimeMillis() + 2000L
            while (!winnerCommitted && System.currentTimeMillis() < deadline1) {
                if (controller.state.value.receipts.any { it.message.contains("Completed") }) {
                    winnerCommitted = true
                } else {
                    delay(20)
                }
            }
            assertTrue("Winner receipt must be present", winnerCommitted)

            // Loser tap (second click / duplicate click)
            controller.onAction(UiAction.ConfirmProposal("p_ux_1", "n_ux_1"))

            // Allow any events to process
            delay(50)

            val receipts = controller.state.value.receipts
            val errorReceipts = receipts.filter { it.isError }
            assertEquals("Loser tap must NOT produce an error banner in receipts: $receipts", 0, errorReceipts.size)
            assertEquals("Only 1 commit receipt must exist", 1, receipts.count { it.message.contains("Completed") })
        } finally {
            testScope.cancel()
        }
    }

    // R12d Item 3: Expiry parity between SharedFakeStorage and SQLite (missing created_at must return null)
    @Test
    fun testFakeStorage_ProposalExpiryParity_MissingCreatedAtReturnsNull() = runBlocking {
        val clock = FakeClock(1000L)
        val storage = SharedFakeStorage(clock)
        val proposal = Proposal("p_missing_created", "time", IntentType.CREATE, listOf(ProposalOption("1", "Opt 1")), status = "open")
        storage.saveProposal(proposal)
        storage.proposalCreatedAt.remove("p_missing_created")

        // In real SQLite, if created_at is missing/null, query WHERE created_at > threshold returns 0 rows (null).
        // SharedFakeStorage must match SQLite semantics and return null instead of falling back to clock.now().
        val fetched = storage.getProposal("p_missing_created")
        assertNull("getProposal must return null when created_at is missing (matching SQLite)", fetched)
    }

    // R12d Item 3: Crash-window: effect transaction throws => proposal remains open and no effect row exists
    @Test
    fun testCrashWindow_EffectTransactionThrows_ProposalRemainsOpenAndNoEffectRow() = runBlocking {
        val clock = FakeClock(1000L)
        val storage = SharedFakeStorage(clock)
        val dateResolver = DateResolver(clock)
        val gateway = Gateway(storage, Resolver(), dateResolver, clock)

        val fireAt = clock.now() + 3600_000L
        val payload = ProposalPayload(intent = IntentType.CREATE, title = "Crash Test Task", untitled = true)
        val proposal = Proposal("p_crash_1", "time", IntentType.CREATE, listOf(ProposalOption(fireAt.toString(), "11:00 AM")), status = "open", payload = Json.encodeToString(payload))
        storage.saveProposal(proposal)

        // Make effect throw to simulate a crash/disk error inside the effect transaction
        var callCount = 0
        val crashingStorage = object : Storage by storage {
            override suspend fun createNodeAndCommit(
                node: Node,
                event: RawEvent,
                reminder: Reminder?,
                edge: Edge?,
                edgeEvent: RawEvent?,
                consumeProposalId: String?
            ) {
                callCount++
                // Option (b) proof: at the moment createNodeAndCommit is entered, the proposal must still be "open".
                // In Option (a), Gateway had already marked it "consumed" before calling createNodeAndCommit.
                assertEquals("Proposal must still be 'open' upon entering effect transaction in Option (b)", "open", storage.getProposal("p_crash_1")?.status)
                throw java.sql.SQLException("Simulated SQLite disk I/O failure inside effect transaction")
            }
        }
        val crashGateway = Gateway(crashingStorage, Resolver(), dateResolver, clock)

        try {
            crashGateway.executeTool(ToolCall("confirm_change", mapOf("proposal" to JsonPrimitive("p_crash_1"), "choice" to JsonPrimitive(fireAt.toString()))))
        } catch (e: Exception) {
            // expected failure
        }

        assertEquals("Effect must have been called", 1, callCount)
        assertEquals("Proposal must remain 'open' when effect transaction fails", "open", storage.getProposal("p_crash_1")?.status)
        assertTrue("No node must be created in storage when effect fails", storage.activeNodes.isEmpty())
    }

    // R12d Item 3: In-transaction CAS single winner: second confirm with consumed proposal aborts effect transaction
    @Test
    fun testInTransactionCas_ConsumedProposalAbortsEffectTransaction() = runBlocking {
        val clock = FakeClock(1000L)
        val storage = SharedFakeStorage(clock)

        val proposal = Proposal("p_consumed_cas", "time", IntentType.CREATE, listOf(ProposalOption("1", "Opt 1")), status = "consumed")
        storage.saveProposal(proposal)
        storage.proposalCreatedAt["p_consumed_cas"] = clock.now()

        val node = Node("n_should_not_exist", "task", "Ghost Task", "active")
        val event = RawEvent("op_ghost", "CREATE", "node", "n_should_not_exist")

        var aborted = false
        try {
            storage.createNodeAndCommit(node, event, reminder = null, consumeProposalId = "p_consumed_cas")
        } catch (e: Exception) {
            aborted = true
        }

        assertTrue("createNodeAndCommit with consumed proposal must abort/throw", aborted)
        assertNull("Node must NOT be committed when in-transaction CAS fails", storage.getNode("n_should_not_exist"))
        assertTrue("Logged events must NOT contain aborted event", storage.loggedEvents.none { it.opId == "op_ghost" })
    }

    // Audit #6 Bug 4: Stop action cancels the active streaming turn, not the waiting turn
    @Test
    fun testStopAction_CancelsActiveStreamingTurn_NotQueuedTurn() = runBlocking {
        val clock = FakeClock(1000L)
        val storage = SharedFakeStorage(clock).apply {
            settings["key"] = "test-key"
        }
        val turn1Started = CompletableDeferred<Unit>()
        val turn1Cancelled = CompletableDeferred<Unit>()

        val transport = object : HttpTransport {
            override suspend fun postStream(target: Target, body: String): Flow<String> = flow {
                turn1Started.complete(Unit)
                try {
                    delay(5000L) // Simulate long streaming
                    emit("""data: {"choices":[{"delta":{"content":"Turn reply"}}]}""")
                } catch (e: CancellationException) {
                    turn1Cancelled.complete(Unit)
                    throw e
                }
            }
        }

        val testScope = CoroutineScope(Dispatchers.Default + Job())
        val controller = buildController(storage, clock, transport, testScope, Dispatchers.Default)

        // Start Turn 1
        controller.sendText("First message")
        turn1Started.await()

        // Enqueue Turn 2 while Turn 1 is holding sendMutex
        controller.sendText("Second message")

        // User taps Stop
        controller.onAction(UiAction.Stop)

        // Turn 1 must have received the cancellation signal
        withTimeout(2000L) {
            turn1Cancelled.await()
        }
        assertTrue("Active turn 1 must be cancelled by Stop action", turn1Cancelled.isCompleted)
        testScope.cancel()
    }

    // Audit #6 Bug 3: Archive, Delete, Restore, and UpdateNodeStatus do not emit duplicate receipts
    @Test
    fun testNodeActionReceipts_AreNotDuplicatedInUiState() = runBlocking {
        val clock = FakeClock(1000L)
        val storage = SharedFakeStorage(clock)
        val node = Node("n_test_dup", "task", "Receipt Test Task", "active")
        storage.saveNode(node)

        val transport = object : HttpTransport {
            override suspend fun postStream(target: Target, body: String): Flow<String> = flow {
                emit("""data: {"choices":[{"delta":{"content":"done"}}]}""")
            }
        }
        val testScope = CoroutineScope(Dispatchers.Default + Job())
        val controller = buildController(storage, clock, transport, testScope, Dispatchers.Default)

        // Archive the node
        controller.onAction(UiAction.ArchiveNode("n_test_dup"))

        // Wait for UI refresh
        var archiveReceipts = emptyList<ReceiptState>()
        for (i in 1..20) {
            delay(50L)
            archiveReceipts = controller.state.value.receipts.filter { it.message.contains("Archived", ignoreCase = true) }
            if (archiveReceipts.isNotEmpty()) break
        }
        assertEquals("There must be exactly one archive receipt, not duplicated", 1, archiveReceipts.size)

        testScope.cancel()
    }

    // Audit #6 Bug 2: refreshUI reloads ambient report, items, and settings
    @Test
    fun testRefreshUi_ReloadsAmbientState() = runBlocking {
        val clock = FakeClock(1000L)
        val storage = SharedFakeStorage(clock)
        val transport = object : HttpTransport {
            override suspend fun postStream(target: Target, body: String): Flow<String> = flow {
                emit("""data: {"choices":[{"delta":{"content":"done"}}]}""")
            }
        }
        val testScope = CoroutineScope(Dispatchers.Default + Job())
        val controller = buildController(storage, clock, transport, testScope, Dispatchers.Default)

        assertEquals(false, controller.state.value.ambientEnabled)
        assertNull(controller.state.value.latestAmbientReport)

        // External process/service adds report and enables ambient
        storage.saveSetting("ambient.enabled", "1")
        val report = com.anchor.core.ambient.AmbientReport("rep_test_1", "2026-11-12", 1000L)
        storage.saveAmbientReport(report)
        storage.saveAmbientReportItem(
            com.anchor.core.ambient.AmbientReportItem("item_1", "rep_test_1", "win_1", "proposal", "Summary 1", "n_1")
        )

        // Trigger refresh
        controller.onAction(UiAction.RefreshStats)
        delay(150L)

        assertEquals(true, controller.state.value.ambientEnabled)
        assertNotNull(controller.state.value.latestAmbientReport)
        assertEquals("rep_test_1", controller.state.value.latestAmbientReport?.id)
        assertEquals(1, controller.state.value.ambientReportItems.size)

        testScope.cancel()
    }
}
