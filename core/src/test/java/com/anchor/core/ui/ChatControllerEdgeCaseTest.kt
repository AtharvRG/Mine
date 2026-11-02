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
            override fun schedule(reminder: Reminder) { scheduledIds.add(reminder.id) }
            override fun cancel(reminderId: String) {}
        }

        // Fresh controller startup (simulating app start after force stop)
        val controller = ChatController(
            storage, ProviderOrchestrator(RateLimiter { clock.now() }, object : HttpTransport { override suspend fun postStream(t: Target, b: String): Flow<String> = flow{} }),
            com.anchor.core.context.ContextBuilder(storage, clock, DateResolver(clock), com.anchor.core.nudge.CheckinBuilder(storage, clock, DateResolver(clock))),
            Gateway(storage, Resolver(), DateResolver(clock), clock),
            scheduler,
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
            kotlinx.coroutines.Dispatchers.Unconfined,
            clock
        )
        kotlinx.coroutines.delay(100)

        // Pending reminder MUST be re-armed despite being present in registry hint
        assertTrue("Pending reminder must be re-armed after app start/force-stop recovery", scheduledIds.contains("r_pending"))
    }

    @Test
    fun testProviderUrlSave_DoesNotStripPath_AndPostsToExactUrl() = runBlocking {
        val storage = SharedFakeStorage().apply { settings["key"] = "test-key" }
        var postedTargetUrl: String? = null
        val transport = object : HttpTransport {
            override suspend fun postStream(t: Target, b: String): Flow<String> = flow {
                postedTargetUrl = t.id
                emit("""data: {"choices":[{"delta":{"content":"ok"}}]}""")
            }
        }
        val controller = buildController(storage, FakeClock(1000L), transport)

        controller.onAction(UiAction.SaveProvider("https://api.openai.com/v1/chat/completions", "key", "mod"))
        kotlinx.coroutines.delay(100)

        assertEquals("https://api.openai.com/v1/chat/completions", controller.state.value.providerEndpoint)

        controller.onAction(UiAction.UpdateDraft("Test message"))
        controller.onAction(UiAction.SendMessage)
        kotlinx.coroutines.delay(200)

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
        gateway.executeTool(undoCall)
        
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
            override fun schedule(reminder: Reminder) {}
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
            storage, ProviderOrchestrator(RateLimiter { clock.now() }, object : HttpTransport { override suspend fun postStream(t: Target, b: String): Flow<String> = flow{} }),
            com.anchor.core.context.ContextBuilder(storage, clock, DateResolver(clock), com.anchor.core.nudge.CheckinBuilder(storage, clock, DateResolver(clock))),
            Gateway(storage, Resolver(), DateResolver(clock), clock),
            scheduler,
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
            kotlinx.coroutines.Dispatchers.Unconfined,
            clock
        )

        controller.onAction(UiAction.ConfirmProposal("prop_cancel", "n_cancel"))
        kotlinx.coroutines.delay(100)

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
            override suspend fun postStream(t: Target, b: String): Flow<String> = flow {
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
        kotlinx.coroutines.delay(100)

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
            override suspend fun postStream(t: Target, b: String): Flow<String> = flow {
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
        kotlinx.coroutines.delay(100)

        org.junit.Assert.assertFalse("LLM should not be called on chip undo", llmCalled)
        val receipts = controller.state.value.receipts
        org.junit.Assert.assertTrue("Should create receipt for undo", receipts.isNotEmpty())
        org.junit.Assert.assertFalse("Receipt must not be an error", receipts.last().isError)
        org.junit.Assert.assertTrue("Receipt message should mention undone action", receipts.last().message.contains("Removed: Buy milk"))
    }

    @Test
    fun testModelGenericDone_SuppressesDoneProse_AndKeepsReceipt() = runBlocking {
        val storage = SharedFakeStorage().apply { settings["key"] = "test-key" }
        val clock = FakeClock(1000L)
        val transport = object : HttpTransport {
            override suspend fun postStream(t: Target, b: String): Flow<String> = flow {
                emit("""data: {"choices":[{"delta":{"content":"{\"reply\":\"Done!\",\"calls\":[{\"tool\":\"propose_change\",\"args\":{\"intent\":\"CREATE\",\"hint\":\"Buy groceries\"}}]}"}}]}""")
                emit("data: [DONE]")
            }
        }
        val controller = buildController(storage, clock, transport)

        controller.onAction(UiAction.UpdateDraft("Buy groceries"))
        controller.onAction(UiAction.SendMessage)
        kotlinx.coroutines.delay(200)

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
            override suspend fun postStream(t: Target, b: String): Flow<String> = flow {
                emit("""data: {"choices":[{"delta":{"content":"{\"reply\":\"Handling your request.\",\"calls\":[{\"tool\":\"propose_change\",\"args\":{\"intent\":\"CREATE\",\"hint\":\"Valid task\"}},{\"tool\":\"propose_change\",\"args\":{}}]}"}}]}""")
                emit("data: [DONE]")
            }
        }
        val controller = buildController(storage, clock, transport)

        controller.onAction(UiAction.UpdateDraft("Create valid and invalid"))
        controller.onAction(UiAction.SendMessage)
        kotlinx.coroutines.delay(200)

        val receipts = controller.state.value.receipts
        assertEquals("Must produce 2 receipts for mixed envelope", 2, receipts.size)
        org.junit.Assert.assertTrue("Must have 1 commit receipt", receipts.any { !it.isError && it.message.contains("Valid task") })
        org.junit.Assert.assertTrue("Must have 1 error receipt", receipts.any { it.isError && it.message.contains("Missing intent") })

        val lastMsg = storage.messages.last()
        assertEquals("assistant", lastMsg.role)
        org.junit.Assert.assertTrue(lastMsg.content!!.contains("Handling your request."))
        org.junit.Assert.assertTrue(lastMsg.content!!.contains("Missing intent"))
    }

    @Test
    fun testSoulFacts_AcceptAndRejectLifecycle_AndContextInjection() = runBlocking {
        val storage = SharedFakeStorage().apply { settings["key"] = "test-key" }
        val clock = FakeClock(1000L)
        val dateResolver = DateResolver(clock)
        val checkinBuilder = com.anchor.core.nudge.CheckinBuilder(storage, clock, dateResolver)
        val contextBuilder = ContextBuilder(storage, clock, dateResolver, checkinBuilder)
        val controller = buildController(storage, clock, object : HttpTransport { override suspend fun postStream(t: Target, b: String): Flow<String> = flow{} })

        val fact1 = com.anchor.core.models.SoulFact("sf1", "food", "Likes chai", 0.9, "user", "stated", "proposed", clock.now())
        val fact2 = com.anchor.core.models.SoulFact("sf2", "sport", "Plays tennis", 0.8, "user", "stated", "proposed", clock.now())
        storage.createSoulFactAndCommit(fact1, com.anchor.core.models.RawEvent("e_sf1", "CREATE", "soul_fact", "sf1"))
        storage.createSoulFactAndCommit(fact2, com.anchor.core.models.RawEvent("e_sf2", "CREATE", "soul_fact", "sf2"))

        controller.onAction(UiAction.AcceptSoulFact("sf1"))
        kotlinx.coroutines.delay(50)
        assertEquals("active", storage.soulFacts.find { it.id == "sf1" }?.status)

        controller.onAction(UiAction.RejectSoulFact("sf2"))
        kotlinx.coroutines.delay(50)
        assertEquals("rejected", storage.soulFacts.find { it.id == "sf2" }?.status)

        val pack = contextBuilder.buildPack()
        val sysPrompt = pack.find { it.role == "system" }?.content ?: ""
        org.junit.Assert.assertTrue("Context must include active soul fact", sysPrompt.contains("Likes chai"))
        org.junit.Assert.assertFalse("Context must not include rejected soul fact", sysPrompt.contains("Plays tennis"))
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