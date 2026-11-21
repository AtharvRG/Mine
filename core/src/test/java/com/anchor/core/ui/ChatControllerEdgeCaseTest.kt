// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.ui

import com.anchor.core.SharedFakeStorage
import com.anchor.core.alarms.AlarmScheduler
import com.anchor.core.context.ContextBuilder
import com.anchor.core.gateway.Gateway
import com.anchor.core.models.Reminder
import com.anchor.core.models.OutboxJob
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
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.coroutines.cancellation.CancellationException

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ChatControllerEdgeCaseTest {

    private suspend fun waitUntil(timeoutMs: Long = 1000L, condition: suspend () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) {
                org.junit.Assert.fail("Timed out waiting for condition after ${timeoutMs}ms")
            }
            kotlinx.coroutines.yield()
            kotlinx.coroutines.delay(5)
        }
    }

    @Test
    fun testStopCancelsStreamAndPersistsInterrupted() = runBlocking {
        val storage = SharedFakeStorage().apply { settings["key"] = "test-key" }
        val clock = FakeClock(1000L)
        val halfwayEmitted = kotlinx.coroutines.CompletableDeferred<Unit>()

        val transport = object : HttpTransport {
            override suspend fun postStream(target: Target, body: String): Flow<String> = flow {
                emit("""data: {"choices":[{"delta":{"content":"Halfway "}}]}""")
                halfwayEmitted.complete(Unit)
                while (true) {
                    kotlinx.coroutines.delay(10)
                }
            }
        }

        val controller = buildController(storage, clock, transport)
        controller.onAction(UiAction.UpdateDraft("Tell me a story"))
        controller.onAction(UiAction.SendMessage)

        halfwayEmitted.await()
        controller.onAction(UiAction.Stop)

        waitUntil { !controller.state.value.isChatLoading && storage.messages.any { it.role == "assistant" && it.content?.contains("[Interrupted]") == true } }

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
        // The raw exception text is never shown; the user gets plain language and a retry path.
        assertEquals("Something went wrong while answering. Tap retry.", controller.state.value.connectionError)
        // "Hello" is small talk: it still gets a human reply while the provider is down.
        assertTrue(storage.messages.any { it.role == "assistant" && it.content?.startsWith("Hey") == true })
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
            override fun schedule(reminder: Reminder): com.anchor.core.alarms.ScheduleResult {
                scheduledIds.add(reminder.id)
                return com.anchor.core.alarms.ScheduleResult.Armed
            }
            override fun cancel(reminderId: String) { canceledIds.add(reminderId) }
        }

        val controller = ChatController(
            storage, ProviderOrchestrator(RateLimiter { clock.now() }, object : HttpTransport { override suspend fun postStream(target: Target, body: String): Flow<String> = flow{} }),
            com.anchor.core.context.ContextBuilder(storage, clock, DateResolver(clock), com.anchor.core.nudge.CheckinBuilder(storage, clock, DateResolver(clock))),
            Gateway(storage, Resolver(), DateResolver(clock), clock), scheduler, kotlinx.coroutines.CoroutineScope(UnconfinedTestDispatcher()), UnconfinedTestDispatcher(), clock
        )

        // Controller init completed initial reconcile. Clear tracking to isolate RefreshStats effect.
        scheduledIds.clear()
        canceledIds.clear()

        // 3. Post-init drift:
        // - Time changed in DB to 9000 (Simulating a RESCHEDULE while registry has 5000)
        storage.reminders.clear()
        storage.reminders.add(Reminder("r1", "Task", 9000L))
        // - Orphan in OS registry (alarm exists in registry but no reminder in DB)
        storage.saveRegisteredAlarm("r2_orphan", 2, 8000L)

        // Trigger reconcile via RefreshStats
        controller.onAction(UiAction.RefreshStats)
        waitUntil { canceledIds.contains("r2_orphan") && scheduledIds.contains("r1") }

        assertTrue("Orphan alarm must be cancelled", canceledIds.contains("r2_orphan"))
        assertTrue("Rescheduled alarm must be re-scheduled because time changed", scheduledIds.contains("r1"))
    }

    @Test
    fun testReconcileAlarms_RecoversAfterForceStop_ReArmsPendingAlarms() = runBlocking {
        val storage = SharedFakeStorage().apply { settings["key"] = "test-key" }
        val clock = FakeClock(1000L)

        // Simulate state before force stop: DB has pending reminder, registry already has it recorded.
        // On Android, a force-stop clears OS alarms, leaving registry as an out-of-date hint.
        storage.reminders.add(Reminder("r_pending", "Task to keep", 6000L))
        storage.saveRegisteredAlarm("r_pending", 1, 6000L)

        val scheduledIds = mutableListOf<String>()
        val scheduler = object : AlarmScheduler {
            override val lastFiredId = MutableStateFlow<String?>(null)
            override fun schedule(reminder: Reminder): com.anchor.core.alarms.ScheduleResult {
                scheduledIds.add(reminder.id)
                return com.anchor.core.alarms.ScheduleResult.Armed
            }
            override fun cancel(reminderId: String) {}
        }

        // Fresh controller startup (simulating app start after force stop)
        val controller = ChatController(
            storage, ProviderOrchestrator(RateLimiter { clock.now() }, object : HttpTransport { override suspend fun postStream(target: Target, body: String): Flow<String> = flow{} }),
            com.anchor.core.context.ContextBuilder(storage, clock, DateResolver(clock), com.anchor.core.nudge.CheckinBuilder(storage, clock, DateResolver(clock))),
            Gateway(storage, Resolver(), DateResolver(clock), clock),
            scheduler,
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
            kotlinx.coroutines.Dispatchers.Unconfined,
            clock
        )
        waitUntil { scheduledIds.contains("r_pending") }

        // Pending reminder MUST be re-armed despite being present in registry hint
        assertTrue("Pending reminder must be re-armed after app start/force-stop recovery", scheduledIds.contains("r_pending"))
    }

    @Test
    fun testProviderUrlSave_DoesNotStripPath_AndPostsToExactUrl() = runBlocking {
        val storage = SharedFakeStorage().apply { settings["key"] = "test-key" }
        var postedTargetUrl: String? = null
        val transport = object : HttpTransport {
            override suspend fun postStream(target: Target, body: String): Flow<String> = flow {
                postedTargetUrl = target.id
                emit("""data: {"choices":[{"delta":{"content":"ok"}}]}""")
            }
        }
        val controller = buildController(storage, FakeClock(1000L), transport)

        controller.onAction(UiAction.SaveProvider("https://api.openai.com/v1/chat/completions", "key", "mod"))
        waitUntil { controller.state.value.providerEndpoint == "https://api.openai.com/v1/chat/completions" }

        assertEquals("https://api.openai.com/v1/chat/completions", controller.state.value.providerEndpoint)

        controller.onAction(UiAction.UpdateDraft("Test message"))
        controller.onAction(UiAction.SendMessage)
        waitUntil { postedTargetUrl != null }

        assertEquals("https://api.openai.com/v1/chat/completions", postedTargetUrl)
    }

    @Test
    fun testUndoAfterReschedule_NodeWithNoReminder() = runBlocking {
        val storage = SharedFakeStorage().apply { settings["key"] = "test-key" }
        val clock = FakeClock(1000L)
        val resolver = Resolver()
        val gateway = Gateway(storage, resolver, DateResolver(clock), clock)

        val node = com.anchor.core.models.Node("n1", "note", "Just a note", "active", 5000L)
        storage.activeNodes.add(node)
        // Explicitly NOT adding a reminder for this node

        val rescheduleCall = com.anchor.core.gateway.ToolCall("propose_change", mapOf("intent" to kotlinx.serialization.json.JsonPrimitive("RESCHEDULE"), "hint" to kotlinx.serialization.json.JsonPrimitive("note"), "due_text" to kotlinx.serialization.json.JsonPrimitive("in 20 minutes")))
        val result = gateway.executeTool(rescheduleCall) as com.anchor.core.models.ToolResult.Committed

        // Assert Reminder WAS created during reschedule
        assertEquals(1, storage.reminders.size)

        val undoCall = com.anchor.core.gateway.ToolCall("undo", mapOf("token" to kotlinx.serialization.json.JsonPrimitive(result.undoToken)))
        val undoResult = gateway.executeTool(undoCall)
        assertTrue("Undo tool call must be committed", undoResult is com.anchor.core.models.ToolResult.Committed)

        // Assert Reminder WAS destroyed because the old state had no reminder
        assertEquals(0, storage.reminders.size)
    }

    @Test
    fun testChipCancel_RemovesAlarmAndCancelsScheduler() = runBlocking {
        val storage = SharedFakeStorage().apply { settings["key"] = "test-key" }
        val clock = FakeClock(1000L)
        val cancelledAlarms = mutableListOf<String>()
        val scheduler = object : AlarmScheduler {
            override val lastFiredId = MutableStateFlow<String?>(null)
            override fun schedule(reminder: Reminder) = com.anchor.core.alarms.ScheduleResult.Armed
            override fun cancel(reminderId: String) { cancelledAlarms.add(reminderId) }
        }

        val node = com.anchor.core.models.Node("n_cancel", "task", "Cancel Me", "active", 5000L)
        val reminder = Reminder("rem_cancel_1", "Cancel Me", 5000L, nodeId = "n_cancel")
        storage.createNodeAndCommit(node, com.anchor.core.models.RawEvent("e1", "CREATE", "node", "n_cancel"), reminder)
        storage.saveRegisteredAlarm("rem_cancel_1", 1, 5000L)

        val proposal = com.anchor.core.models.Proposal(
            id = "prop_cancel",
            kind = "resolution",
            intent = com.anchor.core.models.IntentType.CANCEL,
            options = listOf(com.anchor.core.models.ProposalOption("n_cancel", "Cancel Me")),
            status = "open"
        )
        storage.saveProposal(proposal)

        val controller = ChatController(
            storage, ProviderOrchestrator(RateLimiter { clock.now() }, object : HttpTransport { override suspend fun postStream(target: Target, body: String): Flow<String> = flow{} }),
            com.anchor.core.context.ContextBuilder(storage, clock, DateResolver(clock), com.anchor.core.nudge.CheckinBuilder(storage, clock, DateResolver(clock))),
            Gateway(storage, Resolver(), DateResolver(clock), clock),
            scheduler,
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
            kotlinx.coroutines.Dispatchers.Unconfined,
            clock
        )

        controller.onAction(UiAction.ConfirmProposal("prop_cancel", "n_cancel"))
        waitUntil { storage.getNode("n_cancel")?.status == "cancelled" }

        // Verify Node is cancelled
        val updatedNode = storage.getNode("n_cancel")
        assertEquals("cancelled", updatedNode?.status)

        // Verify Reminder is deleted from DB
        val rem = storage.getReminderByNodeId("n_cancel")
        org.junit.Assert.assertNull(rem)

        // Verify Scheduler received cancellation for the reminder
        assertTrue(cancelledAlarms.contains("rem_cancel_1"))

        // Verify Registered alarm is deleted
        val reg = storage.getRegisteredAlarms()
        assertTrue(reg.none { it.first == "rem_cancel_1" })
    }

    @Test
    fun testCreateWithTime_AndScheduleReminder_DoesNotDoubleArm_AndUndoRestores() = runBlocking {
        val storage = SharedFakeStorage().apply { settings["key"] = "test-key" }
        val clock = FakeClock(1000L)
        val resolver = Resolver()
        val gateway = Gateway(storage, resolver, DateResolver(clock), clock)

        // 1. CREATE with time (auto-creates reminder)
        val createCall = com.anchor.core.gateway.ToolCall(
            "propose_change",
            mapOf(
                "intent" to kotlinx.serialization.json.JsonPrimitive("CREATE"),
                "hint" to kotlinx.serialization.json.JsonPrimitive("Dentist appointment"),
                "due_text" to kotlinx.serialization.json.JsonPrimitive("in 2 hours")
            )
        )
        val createResult = gateway.executeTool(createCall) as com.anchor.core.models.ToolResult.Committed
        val nodeId = createResult.targetId

        assertEquals(1, storage.reminders.size)
        val initialReminder = storage.reminders.first()
        assertEquals(nodeId, initialReminder.nodeId)
        val initialFireAt = initialReminder.fireAtMillis

        // 2. schedule_reminder on the same node (e.g. LLM calls schedule_reminder after create)
        val scheduleCall = com.anchor.core.gateway.ToolCall(
            "schedule_reminder",
            mapOf(
                "target_ref" to kotlinx.serialization.json.JsonPrimitive(nodeId),
                "when_text" to kotlinx.serialization.json.JsonPrimitive("in 5 hours")
            )
        )
        val schedResult = gateway.executeTool(scheduleCall) as com.anchor.core.models.ToolResult.Committed

        // Must NOT double-arm (must still have exactly 1 reminder for this node)
        assertEquals(1, storage.reminders.size)
        assertEquals(nodeId, storage.reminders.first().nodeId)
        val updatedFireAt = storage.reminders.first().fireAtMillis
        assertTrue("Fire at should be updated", updatedFireAt > initialFireAt)

        // 3. Undo schedule_reminder restores initial reminder
        val undoSchedCall = com.anchor.core.gateway.ToolCall(
            "undo",
            mapOf("token" to kotlinx.serialization.json.JsonPrimitive(schedResult.undoToken))
        )
        val undoSchedResult = gateway.executeTool(undoSchedCall) as com.anchor.core.models.ToolResult.Committed
        assertEquals(1, storage.reminders.size)
        assertEquals(initialFireAt, storage.reminders.first().fireAtMillis)

        // 4. Undo CREATE cancels node and deletes reminder
        val undoCreateCall = com.anchor.core.gateway.ToolCall(
            "undo",
            mapOf("token" to kotlinx.serialization.json.JsonPrimitive(createResult.undoToken))
        )
        val undoCreateResult = gateway.executeTool(undoCreateCall) as com.anchor.core.models.ToolResult.Committed
        assertEquals("cancelled", storage.getNode(nodeId)?.status)
        assertEquals(0, storage.reminders.size)
    }

    @Test
    fun testGetUrgentNodes_OrdersNullsLast_AndExcludesDone() = runBlocking {
        val storage = SharedFakeStorage()
        val now = 1000L

        val nodeNoDue = com.anchor.core.models.Node("n_nodue", "task", "No due date", "active", null, now, now)
        val nodeLater = com.anchor.core.models.Node("n_later", "task", "Later", "active", now + 100000L, now, now)
        val nodeSoonest = com.anchor.core.models.Node("n_soon", "task", "Soonest", "active", now + 50000L, now, now)
        val nodeDone = com.anchor.core.models.Node("n_done", "task", "Completed", "done", now + 10000L, now, now)

        storage.activeNodes.add(nodeNoDue)
        storage.activeNodes.add(nodeLater)
        storage.activeNodes.add(nodeSoonest)
        storage.activeNodes.add(nodeDone)

        val urgent = storage.getUrgentNodes()
        assertEquals(3, urgent.size)
        assertEquals("n_soon", urgent[0].id)
        assertEquals("n_later", urgent[1].id)
        assertEquals("n_nodue", urgent[2].id)
    }

    @Test
    fun testConfirmProposal_CreatesReceipt_WithoutCallingLLM() = runBlocking {
        val storage = SharedFakeStorage().apply { settings["key"] = "test-key" }
        val clock = FakeClock(1000L)
        var llmCalled = false
        val transport = object : HttpTransport {
            override suspend fun postStream(target: Target, body: String): Flow<String> = flow {
                llmCalled = true
                emit("""data: [DONE]""")
            }
        }
        val controller = buildController(storage, clock, transport)

        val node1 = com.anchor.core.models.Node("n1", "task", "Clean garage", "active", 5000L)
        storage.activeNodes.add(node1)
        val proposal = com.anchor.core.models.Proposal(
            id = "p_ambig",
            kind = "ambiguous",
            intent = com.anchor.core.models.IntentType.COMPLETE,
            options = listOf(com.anchor.core.models.ProposalOption("n1", "Clean garage")),
            status = "open"
        )
        storage.saveProposal(proposal)

        controller.onAction(UiAction.ConfirmProposal("p_ambig", "n1"))
        waitUntil { storage.getNode("n1")?.status == "done" }

        org.junit.Assert.assertFalse("LLM should not be called on chip confirm", llmCalled)
        assertEquals("Node should be completed", "done", storage.getNode("n1")?.status)
        val receipts = controller.state.value.receipts
        org.junit.Assert.assertTrue("Should create receipt for confirmed proposal", receipts.isNotEmpty())
        org.junit.Assert.assertFalse("Receipt must not be an error", receipts.last().isError)
        org.junit.Assert.assertNotNull("Receipt must have undo token", receipts.last().undoToken)
    }

    @Test
    fun testUndoAction_CreatesReceipt_WithoutCallingLLM() = runBlocking {
        val storage = SharedFakeStorage().apply { settings["key"] = "test-key" }
        val clock = FakeClock(1000L)
        var llmCalled = false
        val transport = object : HttpTransport {
            override suspend fun postStream(target: Target, body: String): Flow<String> = flow {
                llmCalled = true
                emit("""data: [DONE]""")
            }
        }
        val controller = buildController(storage, clock, transport)

        val gateway = Gateway(storage, Resolver(), DateResolver(clock), clock)
        val createCall = com.anchor.core.gateway.ToolCall(
            "propose_change",
            mapOf("intent" to kotlinx.serialization.json.JsonPrimitive("CREATE"), "hint" to kotlinx.serialization.json.JsonPrimitive("Buy milk"))
        )
        val res = gateway.executeTool(createCall) as com.anchor.core.models.ToolResult.Committed

        controller.onAction(UiAction.Undo(res.undoToken))
        waitUntil { controller.state.value.receipts.isNotEmpty() }

        org.junit.Assert.assertFalse("LLM should not be called on chip undo", llmCalled)
        val receipts = controller.state.value.receipts
        org.junit.Assert.assertTrue("Should create receipt for undo", receipts.isNotEmpty())
        org.junit.Assert.assertFalse("Receipt must not be an error", receipts.last().isError)
        org.junit.Assert.assertTrue("Receipt message should mention undone action", receipts.last().message.contains("Removed: Buy milk"))
    }

    @Test
    fun testUndoAction_NodeWithSeverityAndPinned_RestoresProperly() = runBlocking {
        val storage = SharedFakeStorage().apply { settings["key"] = "test-key" }
        val clock = FakeClock(1000L)
        val transport = object : HttpTransport {
            override suspend fun postStream(target: Target, body: String): Flow<String> = flow { emit("""data: [DONE]""") }
        }
        val controller = buildController(storage, clock, transport)

        val gateway = Gateway(storage, Resolver(), DateResolver(clock), clock)
        val createCall = com.anchor.core.gateway.ToolCall(
            "propose_change",
            mapOf(
                "intent" to kotlinx.serialization.json.JsonPrimitive("CREATE"),
                "hint" to kotlinx.serialization.json.JsonPrimitive("Fix prod outage"),
                "severity" to kotlinx.serialization.json.JsonPrimitive("3"),
                "priority" to kotlinx.serialization.json.JsonPrimitive("2"),
                "pinned" to kotlinx.serialization.json.JsonPrimitive("true")
            )
        )
        val res = gateway.executeTool(createCall) as com.anchor.core.models.ToolResult.Committed
        val createdNode = storage.getNode(res.targetId)
        org.junit.Assert.assertNotNull(createdNode)
        assertEquals(3, createdNode!!.severity)
        assertEquals(2, createdNode.priority)
        assertEquals(true, createdNode.pinned)

        // Undo CREATE
        controller.onAction(UiAction.Undo(res.undoToken))
        waitUntil { storage.getNode(res.targetId)?.status == "cancelled" }

        val receipts = controller.state.value.receipts
        org.junit.Assert.assertTrue("Should create receipt for undo", receipts.isNotEmpty())
        org.junit.Assert.assertFalse("Receipt must NOT be error ('state has changed')", receipts.last().isError)
        org.junit.Assert.assertTrue(receipts.last().message.contains("Removed: Fix prod outage"))
        assertEquals("cancelled", storage.getNode(res.targetId)?.status)
    }

    @Test
    fun testModelGenericDone_SuppressesDoneProse_AndKeepsReceipt() = runBlocking {
        val storage = SharedFakeStorage().apply { settings["key"] = "test-key" }
        val clock = FakeClock(1000L)
        val transport = object : HttpTransport {
            override suspend fun postStream(target: Target, body: String): Flow<String> = flow {
                emit("""data: {"choices":[{"delta":{"content":"{\"reply\":\"Done!\",\"calls\":[{\"tool\":\"propose_change\",\"args\":{\"intent\":\"CREATE\",\"hint\":\"Buy groceries\"}}]}"}}]}""")
                emit("data: [DONE]")
            }
        }
        val controller = buildController(storage, clock, transport)

        controller.onAction(UiAction.UpdateDraft("Buy groceries"))
        controller.onAction(UiAction.SendMessage)
        waitUntil { controller.state.value.receipts.isNotEmpty() }

        val receipts = controller.state.value.receipts
        assertEquals(1, receipts.size)
        org.junit.Assert.assertTrue(receipts[0].message.contains("Buy groceries"))

        val msgs = controller.state.value.chatMessages
        org.junit.Assert.assertTrue("Should not store redundant 'Done!' message", msgs.none { it.role == "assistant" && it.content?.trim() == "Done!" })
        org.junit.Assert.assertTrue("Storage raw_message must not store 'Done!'", storage.messages.none { it.role == "assistant" && it.content?.trim() == "Done!" })
    }

    @Test
    fun testAmbiguityProposalOptions_CarryTitlesAndDueTimes() = runBlocking {
        val storage = SharedFakeStorage()
        val clock = FakeClock(1000L)
        val dateResolver = DateResolver(clock)
        val gateway = Gateway(storage, Resolver(), dateResolver, clock)

        val tomorrow4pm = 1000L + 86400000L
        storage.activeNodes.add(com.anchor.core.models.Node("n_dinner1", "event", "Dinner with Maya", "active", tomorrow4pm))
        storage.activeNodes.add(com.anchor.core.models.Node("n_dinner2", "event", "Dinner with Liam", "active", null))

        val call = com.anchor.core.gateway.ToolCall(
            "propose_change",
            mapOf("intent" to kotlinx.serialization.json.JsonPrimitive("CANCEL"), "hint" to kotlinx.serialization.json.JsonPrimitive("dinner"))
        )
        val result = gateway.executeTool(call) as com.anchor.core.models.ToolResult.Ambiguous

        val prop = storage.getProposal(result.proposalId)
        org.junit.Assert.assertNotNull(prop)
        assertEquals(2, prop!!.options.size)

        val optionWithTime = prop.options.find { it.id == "n_dinner1" }
        org.junit.Assert.assertNotNull(optionWithTime)
        org.junit.Assert.assertNotNull("Option must carry formatted dueTime", optionWithTime!!.dueTime)
        org.junit.Assert.assertTrue("Option title must include formatted time", optionWithTime.title.contains(optionWithTime.dueTime!!))

        val optionNoTime = prop.options.find { it.id == "n_dinner2" }
        org.junit.Assert.assertNotNull(optionNoTime)
        org.junit.Assert.assertNull("Option without dueAt has null dueTime", optionNoTime!!.dueTime)
        assertEquals("Dinner with Liam", optionNoTime.title)
    }

    @Test
    fun testMixedCommitAndError_SurfacesBothReceiptsAndErrorText() = runBlocking {
        val storage = SharedFakeStorage().apply { settings["key"] = "test-key" }
        val clock = FakeClock(1000L)
        val transport = object : HttpTransport {
            override suspend fun postStream(target: Target, body: String): Flow<String> = flow {
                emit("""data: {"choices":[{"delta":{"content":"{\"reply\":\"Handling your request.\",\"calls\":[{\"tool\":\"propose_change\",\"args\":{\"intent\":\"CREATE\",\"hint\":\"Valid task\"}},{\"tool\":\"propose_change\",\"args\":{}}]}"}}]}""")
                emit("data: [DONE]")
            }
        }
        val controller = buildController(storage, clock, transport)

        controller.onAction(UiAction.UpdateDraft("Create valid and invalid"))
        controller.onAction(UiAction.SendMessage)
        waitUntil { controller.state.value.receipts.size == 2 && !controller.state.value.isChatLoading }

        val receipts = controller.state.value.receipts
        assertEquals("Must produce 2 receipts for mixed envelope", 2, receipts.size)
        org.junit.Assert.assertTrue("Must have 1 commit receipt", receipts.any { !it.isError && it.message.contains("Valid task") })
        org.junit.Assert.assertTrue("Must have 1 error receipt", receipts.any { it.isError && it.message.contains("Missing intent") })

        val lastMsg = storage.messages.last()
        assertEquals("assistant", lastMsg.role)
        org.junit.Assert.assertTrue(lastMsg.content?.contains("Handling your request.") == true)
        org.junit.Assert.assertTrue(lastMsg.content?.contains("Missing intent") == true)
    }

    @Test
    fun testSoulFacts_AcceptAndRejectLifecycle_AndContextInjection() = runBlocking {
        val storage = SharedFakeStorage().apply { settings["key"] = "test-key" }
        val clock = FakeClock(1000L)
        val dateResolver = DateResolver(clock)
        val checkinBuilder = com.anchor.core.nudge.CheckinBuilder(storage, clock, dateResolver)
        val contextBuilder = ContextBuilder(storage, clock, dateResolver, checkinBuilder)
        val controller = buildController(storage, clock, object : HttpTransport { override suspend fun postStream(target: Target, body: String): Flow<String> = flow{} })

        val fact1 = com.anchor.core.models.SoulFact("sf1", "food", "Likes chai", 0.9, "user", "stated", "proposed", clock.now())
        val fact2 = com.anchor.core.models.SoulFact("sf2", "sport", "Plays tennis", 0.8, "user", "stated", "proposed", clock.now())
        storage.createSoulFactAndCommit(fact1, com.anchor.core.models.RawEvent("e_sf1", "CREATE", "soul_fact", "sf1"))
        storage.createSoulFactAndCommit(fact2, com.anchor.core.models.RawEvent("e_sf2", "CREATE", "soul_fact", "sf2"))

        controller.onAction(UiAction.AcceptSoulFact("sf1"))
        waitUntil { storage.soulFacts.find { it.id == "sf1" }?.status == "active" }
        assertEquals("active", storage.soulFacts.find { it.id == "sf1" }?.status)

        controller.onAction(UiAction.RejectSoulFact("sf2"))
        waitUntil { storage.soulFacts.find { it.id == "sf2" }?.status == "rejected" }
        assertEquals("rejected", storage.soulFacts.find { it.id == "sf2" }?.status)

        val pack = contextBuilder.buildPack()
        val sysPrompt = pack.find { it.role == "system" }?.content ?: ""
        org.junit.Assert.assertTrue("Context must include active soul fact", sysPrompt.contains("Likes chai"))
        org.junit.Assert.assertFalse("Context must not include rejected soul fact", sysPrompt.contains("Plays tennis"))
    }

    @Test
    fun testGatewayAndControllerUntitledFlowIntegration() = runBlocking {
        val storage = SharedFakeStorage().apply { settings["key"] = "test-key" }
        val clock = FakeClock(1000L)
        val interceptedPayloads = mutableListOf<String>()

        val transport = object : HttpTransport {
            override suspend fun postStream(target: Target, body: String): Flow<String> = flow {
                interceptedPayloads.add(body)
                val reply = when {
                    body.contains("\"content\":\"remaind me in 15 secs for checking laptop\"") ->
                        """ "{\"reply\":\"Got it.\",\"calls\":[{\"tool\":\"propose_change\",\"args\":{\"intent\":\"CREATE\",\"title\":\"checking laptop\",\"due_text\":\"in 15 secs\"}}]}" """
                    body.contains("\"content\":\"nothing specific just do it\"") ->
                        """ "{\"reply\":\"Got it.\",\"calls\":[{\"tool\":\"propose_change\",\"args\":{\"intent\":\"CREATE\",\"untitled\":\"true\",\"label\":\"notification\",\"due_text\":\"in 10 secs\"}}]}" """
                    body.contains("\"content\":\"notify me in 10 secs\"") ->
                        """ "{\"reply\":\"For what?\",\"calls\":[]}" """
                    else ->
                        """ "{\"reply\":\"Unexpected message\",\"calls\":[]}" """
                }
                emit("""data: {"choices":[{"delta":{"content":$reply}}]}""")
                emit("data: [DONE]")
            }
        }
        val controller = buildController(storage, clock, transport)

        // Turn 1: "notify me in 10 secs" -> LLM asks "For what?"
        controller.onAction(UiAction.UpdateDraft("notify me in 10 secs"))
        controller.onAction(UiAction.SendMessage)
        waitUntil { !controller.state.value.isChatLoading && storage.messages.any { it.role == "assistant" && it.content?.contains("For what?") == true } }

        // Turn 2: "nothing specific just do it" -> LLM calls propose_change untitled=true
        controller.onAction(UiAction.UpdateDraft("nothing specific just do it"))
        controller.onAction(UiAction.SendMessage)
        waitUntil { !controller.state.value.isChatLoading && storage.getActiveNodes(100).any { it.title.contains("Notification") } }

        // Turn 3: "remaind me in 15 secs for checking laptop" -> With subject present, LLM must propose_change CREATE directly
        controller.onAction(UiAction.UpdateDraft("remaind me in 15 secs for checking laptop"))
        controller.onAction(UiAction.SendMessage)
        waitUntil { !controller.state.value.isChatLoading && storage.getActiveNodes(100).size == 2 }

        val nodes = storage.getActiveNodes(100)
        assertEquals("Nodes found: ${nodes.map { it.title }}", 2, nodes.size)
        assertTrue(nodes.any { it.title == "checking laptop" })
        assertTrue(nodes.any { it.title.contains("Notification") })
    }

    @Test
    fun testReconcileAlarms_SchedulerFailure_ExposesSchedulingErrorInUiState() = runBlocking {
        val storage = SharedFakeStorage().apply { settings["key"] = "test-key" }
        val clock = FakeClock(1000L)
        val reminder = Reminder("r_fail", "Fail Me", 5000L)
        storage.saveReminder(reminder)

        val failingScheduler = object : AlarmScheduler {
            override val lastFiredId = MutableStateFlow<String?>(null)
            override fun schedule(reminder: Reminder): com.anchor.core.alarms.ScheduleResult {
                return com.anchor.core.alarms.ScheduleResult.Failed("OS security restriction")
            }
            override fun cancel(reminderId: String) {}
        }

        val controller = ChatController(
            storage,
            ProviderOrchestrator(RateLimiter { clock.now() }, object : HttpTransport { override suspend fun postStream(target: Target, body: String): Flow<String> = flow{} }),
            com.anchor.core.context.ContextBuilder(storage, clock, DateResolver(clock), com.anchor.core.nudge.CheckinBuilder(storage, clock, DateResolver(clock))),
            Gateway(storage, Resolver(), DateResolver(clock), clock),
            failingScheduler,
            kotlinx.coroutines.CoroutineScope(UnconfinedTestDispatcher()),
            UnconfinedTestDispatcher(),
            clock
        )

        controller.onAction(UiAction.RefreshStats)
        waitUntil { controller.state.value.schedulingError != null }

        assertEquals("OS security restriction", controller.state.value.schedulingError)
        assertEquals(1, controller.state.value.schedulingFailedCount)
    }

    @Test
    fun testReconcileAlarms_MultipleFailures_SurfacesFirstReasonAndCount_AndClearsOnCleanPass() = runBlocking {
        val storage = SharedFakeStorage().apply { settings["key"] = "test-key" }
        val clock = FakeClock(1000L)
        val r1 = Reminder("r1", "Task 1", 5000L)
        val r2 = Reminder("r2", "Task 2", 6000L)
        storage.saveReminder(r1)
        storage.saveReminder(r2)

        var failScheduler = true
        val configurableScheduler = object : AlarmScheduler {
            override val lastFiredId = MutableStateFlow<String?>(null)
            override fun schedule(reminder: Reminder): com.anchor.core.alarms.ScheduleResult {
                return if (failScheduler) {
                    com.anchor.core.alarms.ScheduleResult.Failed("Failure for ${reminder.id}")
                } else {
                    com.anchor.core.alarms.ScheduleResult.Armed
                }
            }
            override fun cancel(reminderId: String) {}
        }

        val controller = ChatController(
            storage,
            ProviderOrchestrator(RateLimiter { clock.now() }, object : HttpTransport { override suspend fun postStream(target: Target, body: String): Flow<String> = flow{} }),
            com.anchor.core.context.ContextBuilder(storage, clock, DateResolver(clock), com.anchor.core.nudge.CheckinBuilder(storage, clock, DateResolver(clock))),
            Gateway(storage, Resolver(), DateResolver(clock), clock),
            configurableScheduler,
            kotlinx.coroutines.CoroutineScope(UnconfinedTestDispatcher()),
            UnconfinedTestDispatcher(),
            clock
        )

        controller.onAction(UiAction.RefreshStats)
        waitUntil { controller.state.value.schedulingFailedCount == 2 }

        assertEquals("Failure for r1", controller.state.value.schedulingError)
        assertEquals(2, controller.state.value.schedulingFailedCount)

        // Now fix the scheduler (clean pass)
        failScheduler = false
        controller.onAction(UiAction.RefreshStats)
        waitUntil { controller.state.value.schedulingFailedCount == 0 }

        assertEquals(null, controller.state.value.schedulingError)
        assertEquals(0, controller.state.value.schedulingFailedCount)
    }

    @Test
    fun testReconcileAlarms_OverdueReminder_IsSurfacedToTheUser() = runBlocking {
        // H-01 (R-01): the reconciler now reports a reminder that came due while the device was off.
        // A report nobody reads is still a silent miss, so the controller must surface it.
        val storage = SharedFakeStorage().apply { settings["key"] = "test-key" }
        val clock = FakeClock(1_000_000L)
        storage.saveReminder(Reminder("r_overdue", "Call the dentist", 400_000L)) // 10 minutes overdue

        val scheduler = object : AlarmScheduler {
            override val lastFiredId = MutableStateFlow<String?>(null)
            override fun schedule(reminder: Reminder) = com.anchor.core.alarms.ScheduleResult.Armed
            override fun cancel(reminderId: String) {}
        }

        val controller = ChatController(
            storage,
            ProviderOrchestrator(RateLimiter { clock.now() }, object : HttpTransport { override suspend fun postStream(target: Target, body: String): Flow<String> = flow{} }),
            com.anchor.core.context.ContextBuilder(storage, clock, DateResolver(clock), com.anchor.core.nudge.CheckinBuilder(storage, clock, DateResolver(clock))),
            Gateway(storage, Resolver(), DateResolver(clock), clock),
            scheduler,
            kotlinx.coroutines.CoroutineScope(UnconfinedTestDispatcher()),
            UnconfinedTestDispatcher(),
            clock
        )

        controller.onAction(UiAction.RefreshStats)
        waitUntil { controller.state.value.missedReminders.isNotEmpty() }

        val missed = controller.state.value.missedReminders.single()
        assertEquals("r_overdue", missed.reminderId)
        assertEquals("Call the dentist", missed.text)
        assertEquals(400_000L, missed.scheduledFor)
        assertEquals("A reported miss is not a scheduling failure", null, controller.state.value.schedulingError)
        assertEquals(0, controller.state.value.schedulingFailedCount)
    }

    @Test
    fun testExtractReplyFromJsonStream_ReturnsNullUntilRealReplyExists() {
        assertEquals(null, ChatController.extractReplyFromJsonStream(""))
        assertEquals(null, ChatController.extractReplyFromJsonStream("   "))
        assertEquals(null, ChatController.extractReplyFromJsonStream("{"))
        assertEquals(null, ChatController.extractReplyFromJsonStream("""{"thought":"pondering..."}"""))
        assertEquals(null, ChatController.extractReplyFromJsonStream("""{"calls":[{"tool":"propose_change"}]}"""))
        assertEquals(null, ChatController.extractReplyFromJsonStream("""{"reply":"""))
        assertEquals(null, ChatController.extractReplyFromJsonStream("""{"reply":""""))
        assertEquals("Hello", ChatController.extractReplyFromJsonStream("""{"reply":"Hello"""))
        assertEquals("Hello\nWorld", ChatController.extractReplyFromJsonStream("""{"reply":"Hello\nWorld"}"""))
        assertEquals("Quote: \"Mine\"", ChatController.extractReplyFromJsonStream("""{"reply":"Quote: \"Mine\""}"""))
        assertEquals("Non-JSON raw text", ChatController.extractReplyFromJsonStream("Non-JSON raw text"))
    }

    @Test
    fun testStopDuringThinking_DoesNotPersistInterruptedToRawMessage() = runBlocking {
        val storage = SharedFakeStorage().apply { settings["key"] = "test-key" }
        val clock = FakeClock(1000L)
        val thinkingEmitted = kotlinx.coroutines.CompletableDeferred<Unit>()

        val transport = object : HttpTransport {
            override suspend fun postStream(target: Target, body: String): Flow<String> = flow {
                emit("""data: {"choices":[{"delta":{"content":"{\"thought\":\"evaluating input...\",\"calls\":[]"}}]}""")
                thinkingEmitted.complete(Unit)
                while (true) {
                    kotlinx.coroutines.delay(10)
                }
            }
        }

        val controller = buildController(storage, clock, transport)
        controller.onAction(UiAction.UpdateDraft("Hello"))
        controller.onAction(UiAction.SendMessage)

        thinkingEmitted.await()
        controller.onAction(UiAction.Stop)

        waitUntil { !controller.state.value.isChatLoading }

        assertTrue("Must NOT persist any interrupted message if no real reply text existed",
            storage.messages.none { it.content?.contains("[Interrupted]") == true })
        assertTrue("Must NOT persist any assistant message to raw_message",
            storage.messages.none { it.role == "assistant" })
        assertTrue("UI chatMessages must NOT have Thinking...",
            controller.state.value.chatMessages.none { it.content?.contains("Thinking") == true })
    }

    @Test
    fun testStopDuringRealReply_PersistsInterruptedWithRealText() = runBlocking {
        val storage = SharedFakeStorage().apply { settings["key"] = "test-key" }
        val clock = FakeClock(1000L)
        val replyEmitted = kotlinx.coroutines.CompletableDeferred<Unit>()

        val transport = object : HttpTransport {
            override suspend fun postStream(target: Target, body: String): Flow<String> = flow {
                emit("""data: {"choices":[{"delta":{"content":"{\"reply\":\"The capital is Paris and"}}]}""")
                replyEmitted.complete(Unit)
                while (true) {
                    kotlinx.coroutines.delay(10)
                }
            }
        }

        val controller = buildController(storage, clock, transport)
        controller.onAction(UiAction.UpdateDraft("What is the capital?"))
        controller.onAction(UiAction.SendMessage)

        replyEmitted.await()
        controller.onAction(UiAction.Stop)

        waitUntil { !controller.state.value.isChatLoading && storage.messages.any { it.role == "assistant" && it.content?.contains("[Interrupted]") == true } }

        val lastMsg = storage.messages.last()
        assertEquals("assistant", lastMsg.role)
        assertTrue("Must append [Interrupted] to partial real reply text",
            lastMsg.content!!.contains("The capital is Paris and [Interrupted]"))
    }

    @Test
    fun testConfirmProposal_ConcurrentOrDuplicateClicks_OnlyOneCommitsAndLoserNoErrorBanner() = runBlocking {
        val storage = SharedFakeStorage().apply { settings["key"] = "test-key" }
        val clock = FakeClock(1000L)
        val transport = object : HttpTransport {
            override suspend fun postStream(target: Target, body: String): Flow<String> = flow { emit("""data: [DONE]""") }
        }
        val controller = buildController(storage, clock, transport)

        val node1 = com.anchor.core.models.Node("n1", "task", "Clean garage", "active", 5000L)
        storage.activeNodes.add(node1)
        val proposal = com.anchor.core.models.Proposal(
            id = "p_ambig",
            kind = "ambiguous",
            intent = com.anchor.core.models.IntentType.COMPLETE,
            options = listOf(com.anchor.core.models.ProposalOption("n1", "Clean garage")),
            status = "open"
        )
        storage.saveProposal(proposal)

        // Winner tap
        controller.onAction(UiAction.ConfirmProposal("p_ambig", "n1"))
        // Immediate duplicate/loser tap
        controller.onAction(UiAction.ConfirmProposal("p_ambig", "n1"))

        waitUntil { storage.getNode("n1")?.status == "done" }

        val receipts = controller.state.value.receipts
        assertEquals("Exactly 1 commit receipt should be produced", 1, receipts.count { !it.isError })
        assertEquals("No error receipts should be shown for loser/duplicate confirm", 0, receipts.count { it.isError })
        assertEquals("Proposal must be consumed", "consumed", storage.getProposal("p_ambig")?.status)
    }

    @Test
    fun testClaimsAction_WithParsableRuleBasedIntent_SilentlyCoversUpAndSucceeds() = runBlocking {
        val storage = SharedFakeStorage().apply { settings["key"] = "test-key" }
        val clock = FakeClock(1700000000000L) // Some fixed time
        val transport = object : HttpTransport {
            override suspend fun postStream(target: Target, body: String): Flow<String> = flow {
                // LLM outputs raw prose claiming action without envelope on both turns (initial + auto-retry)
                emit("""data: {"choices":[{"delta":{"content":"I have set a reminder to check oven."}}]}""")
            }
        }
        val controller = buildController(storage, clock, transport)
        controller.onAction(UiAction.UpdateDraft("Remind me to check oven in 10 minutes"))
        controller.onAction(UiAction.SendMessage)

        waitUntil { !controller.state.value.isChatLoading && storage.activeNodes.isNotEmpty() }

        val activeNodes = storage.getActiveNodes(10)
        assertEquals(1, activeNodes.size)
        assertTrue(activeNodes[0].title.contains("check oven", ignoreCase = true))

        val receipts = controller.state.value.receipts
        assertEquals("Should have 1 successful receipt", 1, receipts.count { !it.isError })
        assertEquals("Should have 0 error receipts", 0, receipts.count { it.isError })
        assertTrue("Receipt message must indicate creation", receipts.first().message.contains("Created: check oven"))
    }

    @Test
    fun testImportProviderConfig_RequiresConfirmation_AndClearsKeyOnDifferentHost() = runBlocking {
        val storage = SharedFakeStorage().apply {
            settings["ep"] = "https://legit.example.com/v1"
            settings["key"] = "secret-user-key"
            settings["mod"] = "model-a"
        }
        val clock = FakeClock(1700000000000L)
        val transport = object : HttpTransport {
            override suspend fun postStream(target: Target, body: String): Flow<String> = emptyFlow()
        }
        val controller = buildController(storage, clock, transport)

        // 1. Deep link arrives from attacker with NO key and different host
        val deepLink = "mine://provider?endpoint=https://attacker.example.com/v1&model=evil-model"
        controller.onAction(UiAction.ImportProviderConfig(deepLink))

        // Assert pendingProviderImport is set, but storage/state NOT changed yet
        val pending = controller.state.value.pendingProviderImport
        assertNotNull(pending)
        assertEquals("https://attacker.example.com/v1", pending?.endpoint)
        assertEquals("https://legit.example.com/v1", controller.state.value.providerEndpoint)
        assertEquals("secret-user-key", controller.state.value.providerKey)
        assertEquals("https://legit.example.com/v1", storage.getSetting("ep"))
        assertEquals("secret-user-key", storage.getSetting("key"))

        // 2. Reject clears pending import
        controller.onAction(UiAction.RejectProviderImport)
        assertNull(controller.state.value.pendingProviderImport)
        assertEquals("secret-user-key", storage.getSetting("key"))

        // 3. Import again, then Confirm
        controller.onAction(UiAction.ImportProviderConfig(deepLink))
        controller.onAction(UiAction.ConfirmProviderImport)

        // Assert new endpoint and model applied, BUT existing key was CLEARED because host changed without key!
        assertNull(controller.state.value.pendingProviderImport)
        assertEquals("https://attacker.example.com/v1", controller.state.value.providerEndpoint)
        assertEquals("evil-model", controller.state.value.providerModel)
        assertEquals("", controller.state.value.providerKey)
        assertEquals("", storage.getSetting("key"))
    }

    @Test
    fun testMidTurnFailure_AfterCommit_DoesNotReEnqueueToOutbox_AndShowsSavedNotice() = runBlocking {
        val storage = SharedFakeStorage().apply { settings["key"] = "test-key" }
        val clock = FakeClock(1700000000000L)
        var callCount = 0

        val transport = object : HttpTransport {
            override suspend fun postStream(target: Target, body: String): Flow<String> = flow {
                callCount++
                if (callCount == 1) {
                    // Turn 1: Valid tool call creating a node + search requiring a second loop
                    emit("""data: {"choices":[{"delta":{"content":"```json\n{\"calls\":[{\"tool\":\"propose_change\",\"args\":{\"intent\":\"CREATE\",\"hint\":\"Write tests\"}},{\"tool\":\"search_history\",\"args\":{\"query\":\"Write tests\"}}]}\n```"}}]}""")
                    emit("data: [DONE]")
                } else {
                    // Turn 2: Transient network timeout failure
                    throw com.anchor.core.provider.ProviderTimeoutException("Gateway timeout")
                }
            }
        }

        val controller = buildController(storage, clock, transport)
        controller.onAction(UiAction.UpdateDraft("Create task Write tests"))
        controller.onAction(UiAction.SendMessage)

        waitUntil { !controller.state.value.isChatLoading && storage.messages.any { it.role == "assistant" } }

        // 1. Assert task was actually created and committed
        val activeNodes = storage.getActiveNodes(10)
        assertEquals(1, activeNodes.size)
        assertEquals("Write tests", activeNodes[0].title)

        // 2. Assert turn was NOT re-enqueued into outbox (avoiding duplicate creation!)
        val outboxJobs = storage.getPendingOutbox(OutboxJob.JOB_CHAT_TURN)
        assertTrue("Committed turn must never be re-enqueued into outbox", outboxJobs.isEmpty())

        // 3. Assert assistant reply informs user of partial commit
        val lastMsg = storage.messages.last { it.role == "assistant" }
        val content = lastMsg.content ?: ""
        assertTrue("Assistant message must state changes were saved. Was: $content", content.contains("your changes are saved") && content.contains("couldn't finish replying"))
    }

    private fun buildController(storage: com.anchor.core.storage.Storage, clock: Clock, transport: HttpTransport): ChatController {
        return ChatController(
            storage, ProviderOrchestrator(RateLimiter { clock.now() }, transport),
            com.anchor.core.context.ContextBuilder(storage, clock, DateResolver(clock), com.anchor.core.nudge.CheckinBuilder(storage, clock, DateResolver(clock))),
            Gateway(storage, Resolver(), DateResolver(clock), clock),
            object : AlarmScheduler { override val lastFiredId = MutableStateFlow<String?>(null); override fun schedule(reminder: Reminder) = com.anchor.core.alarms.ScheduleResult.Armed; override fun cancel(reminderId: String) {} },
            kotlinx.coroutines.CoroutineScope(UnconfinedTestDispatcher()), UnconfinedTestDispatcher(), clock
        )
    }
}
