// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.ui

import com.anchor.core.SharedFakeStorage
import com.anchor.core.alarms.AlarmScheduler
import com.anchor.core.context.ContextBuilder
import com.anchor.core.gateway.Gateway
import com.anchor.core.gateway.ToolCall
import com.anchor.core.models.Node
import com.anchor.core.models.NodeStateSnapshot
import com.anchor.core.models.RawEvent
import com.anchor.core.models.Reminder
import com.anchor.core.models.SoulFact
import com.anchor.core.nudge.CheckinBuilder
import com.anchor.core.provider.HttpTransport
import com.anchor.core.provider.ProviderOrchestrator
import com.anchor.core.provider.RateLimiter
import com.anchor.core.provider.Target
import com.anchor.core.resolver.Resolver
import com.anchor.core.time.DateResolver
import com.anchor.core.time.FakeClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class R7ReceiptsTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

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

    private fun createScheduler() = object : AlarmScheduler {
        override val lastFiredId = MutableStateFlow<String?>(null)
        override fun schedule(reminder: Reminder) = com.anchor.core.alarms.ScheduleResult.Armed
        override fun cancel(reminderId: String) {}
    }

    private fun createController(
        storage: SharedFakeStorage,
        clock: FakeClock,
        dateResolver: DateResolver,
        transport: HttpTransport
    ): ChatController {
        val orchestrator = ProviderOrchestrator(RateLimiter { clock.now() }, transport)
        val checkinBuilder = CheckinBuilder(storage, clock, dateResolver)
        val contextBuilder = ContextBuilder(storage, clock, dateResolver, checkinBuilder)
        val gateway = Gateway(storage, Resolver(), dateResolver, clock)
        return ChatController(
            storage, orchestrator, contextBuilder, gateway,
            createScheduler(), kotlinx.coroutines.CoroutineScope(Dispatchers.Unconfined),
            Dispatchers.Unconfined, clock,
            dateResolver = dateResolver
        )
    }

    @Test
    fun testKillRecreateController_ReceiptsAndValidUndoTokensSurvive() = runBlocking {
        val clock = FakeClock(1700000000000L) // Wednesday Nov 15 2023
        val storage = SharedFakeStorage(clock)
        storage.settings["key"] = "test-key"
        val dateResolver = DateResolver(clock) { true } // 24-hour mode

        val fakeTransport = object : HttpTransport {
            override suspend fun postStream(target: Target, body: String): Flow<String> = flow {
                emit("""data: {"choices":[{"delta":{"content":"```json\n{\"reply\":\"I scheduled it.\",\"calls\":[{\"tool\":\"propose_change\",\"args\":{\"intent\":\"CREATE\",\"title\":\"Dentist Appointment\",\"due_text\":\"tomorrow at 10:00am\"}}]}\n```"}}]}""")
                emit("data: [DONE]")
            }
        }

        val controller1 = createController(storage, clock, dateResolver, fakeTransport)
        controller1.onAction(UiAction.UpdateDraft("Book dentist tomorrow at 10:00"))
        controller1.onAction(UiAction.SendMessage)
        waitUntil { !controller1.state.value.isChatLoading && controller1.state.value.receipts.isNotEmpty() }

        val state1 = controller1.state.value
        assertEquals("Should have 1 receipt in state", 1, state1.receipts.size)
        val receipt1 = state1.receipts.first()
        assertTrue("Receipt message must start with Created: Dentist Appointment", receipt1.message.startsWith("Created: Dentist Appointment"))
        assertNotNull("Undo token must be present", receipt1.undoToken)
        assertNotNull("Cause message ID must be present", receipt1.causeMsgId)
        assertTrue("Cause message ID must link to user message", receipt1.causeMsgId!!.startsWith("u_"))

        // SIMULATE CONTROLLER RECREATION / PROCESS RESTART
        val controller2 = createController(storage, clock, dateResolver, fakeTransport)
        waitUntil { controller2.state.value.receipts.isNotEmpty() }
        val state2 = controller2.state.value

        assertEquals("Receipts must survive controller recreation from raw_event", 1, state2.receipts.size)
        val restoredReceipt = state2.receipts.first()
        assertEquals("Receipt ID must match op_id", receipt1.id, restoredReceipt.id)
        assertEquals("Receipt message must match", receipt1.message, restoredReceipt.message)
        assertEquals("Valid undo token must survive recreation", receipt1.undoToken, restoredReceipt.undoToken)
        assertEquals("Cause message ID must survive recreation", receipt1.causeMsgId, restoredReceipt.causeMsgId)
    }

    @Test
    fun testAfter24Hours_UndoTokenIsAbsent() = runBlocking {
        val clock = FakeClock(1700000000000L)
        val storage = SharedFakeStorage(clock)
        storage.settings["key"] = "test-key"
        val dateResolver = DateResolver(clock) { true }

        val fakeTransport = object : HttpTransport {
            override suspend fun postStream(target: Target, body: String): Flow<String> = flow {
                emit("""data: {"choices":[{"delta":{"content":"```json\n{\"reply\":\"Got it.\",\"calls\":[{\"tool\":\"propose_change\",\"args\":{\"intent\":\"CREATE\",\"title\":\"Gym Session\",\"due_text\":\"tomorrow at 07:00am\"}}]}\n```"}}]}""")
                emit("data: [DONE]")
            }
        }

        val controller = createController(storage, clock, dateResolver, fakeTransport)
        controller.onAction(UiAction.UpdateDraft("Gym tomorrow at 07:00"))
        controller.onAction(UiAction.SendMessage)
        waitUntil { !controller.state.value.isChatLoading && controller.state.value.receipts.isNotEmpty() }

        val receiptInitial = controller.state.value.receipts.first()
        assertNotNull("Undo token must be valid initially", receiptInitial.undoToken)

        // Advance clock by 25 hours (past 24h expiration limit)
        clock.currentTime += 25 * 3600 * 1000L
        controller.onAction(UiAction.RefreshStats)
        waitUntil { controller.state.value.receipts.first().undoToken == null }

        val receiptAfter25h = controller.state.value.receipts.first()
        assertEquals("Receipt message must remain visible after 24h", receiptInitial.message, receiptAfter25h.message)
        assertNull("Undo token must be absent after 24 hours", receiptAfter25h.undoToken)
    }

    @Test
    fun testUndoneReceipt_ShowsNoUndo() = runBlocking {
        val clock = FakeClock(1700000000000L)
        val storage = SharedFakeStorage(clock)
        storage.settings["key"] = "test-key"
        val dateResolver = DateResolver(clock) { true }

        val fakeTransport = object : HttpTransport {
            override suspend fun postStream(target: Target, body: String): Flow<String> = flow {
                emit("""data: {"choices":[{"delta":{"content":"```json\n{\"reply\":\"Added.\",\"calls\":[{\"tool\":\"propose_change\",\"args\":{\"intent\":\"CREATE\",\"title\":\"Pay Electricity Bill\",\"due_text\":\"tomorrow at 09:00am\"}}]}\n```"}}]}""")
                emit("data: [DONE]")
            }
        }

        val controller = createController(storage, clock, dateResolver, fakeTransport)
        controller.onAction(UiAction.UpdateDraft("Pay electricity bill tomorrow at 9am"))
        controller.onAction(UiAction.SendMessage)
        waitUntil { !controller.state.value.isChatLoading && controller.state.value.receipts.isNotEmpty() }

        val receiptBeforeUndo = controller.state.value.receipts.first()
        val token = receiptBeforeUndo.undoToken
        assertNotNull("Undo token must be present before undo", token)

        // Perform undo action
        controller.onAction(UiAction.Undo(token!!))
        waitUntil {
            val r = controller.state.value.receipts.find { it.id == receiptBeforeUndo.id }
            r != null && r.undoToken == null
        }

        val receiptAfterUndo = controller.state.value.receipts.first { it.id == receiptBeforeUndo.id }
        assertNull("Undone receipt must show NO undo token", receiptAfterUndo.undoToken)
        assertEquals("Undone receipt must retain its original deterministic message", receiptBeforeUndo.message, receiptAfterUndo.message)
    }

    @Test
    fun testReceiptOrderAndCauseMsgIdCorrect() = runBlocking {
        val clock = FakeClock(1700000000000L)
        val storage = SharedFakeStorage(clock)
        storage.settings["key"] = "test-key"
        val dateResolver = DateResolver(clock) { true }

        val fakeTransport = object : HttpTransport {
            override suspend fun postStream(target: Target, body: String): Flow<String> = flow {
                emit("""data: {"choices":[{"delta":{"content":"```json\n{\"reply\":\"Created first.\",\"calls\":[{\"tool\":\"propose_change\",\"args\":{\"intent\":\"CREATE\",\"title\":\"Task One\",\"due_text\":\"tomorrow at 08:00am\"}}]}\n```"}}]}""")
                emit("data: [DONE]")
            }
        }

        val controller = createController(storage, clock, dateResolver, fakeTransport)

        // Operation 1: User message creates Task One
        controller.onAction(UiAction.UpdateDraft("Task One tomorrow at 8am"))
        controller.onAction(UiAction.SendMessage)
        waitUntil { !controller.state.value.isChatLoading && controller.state.value.receipts.isNotEmpty() }

        val userMsg1Id = storage.getRecentMessages(10).first { it.role == "user" }.id

        // Advance clock by 10 minutes
        clock.currentTime += 600_000L

        // Operation 2: Chip / direct stale action (has null causeMsgId per spec)
        val node2 = Node("n_stale_2", "task", "Stale Task 2", "active", createdAt = clock.now() - 35L * 86400000L, updatedAt = clock.now() - 35L * 86400000L)
        storage.createNodeAndCommit(node2, RawEvent("u_seed_2", "CREATE", "node", "n_stale_2", tsUtc = node2.createdAt), null)
        controller.onAction(UiAction.KeepStale(node2.id))
        waitUntil { controller.state.value.receipts.size >= 2 }

        val receipts = controller.state.value.receipts
        assertTrue("Must have at least 2 receipts", receipts.size >= 2)

        val r1 = receipts.first { it.message.contains("Task One") }
        val r2 = receipts.first { it.message.contains("Kept: Stale Task 2") }

        assertEquals("First receipt causeMsgId must match user message ID", userMsg1Id, r1.causeMsgId)
        assertNull("Stale action receipt must have null causeMsgId per spec", r2.causeMsgId)

        // Verify chronological order: Task One (earlier) before KeepStale (later)
        val idx1 = receipts.indexOf(r1)
        val idx2 = receipts.indexOf(r2)
        assertTrue("Receipts must be in chronological order", idx1 < idx2)
    }

    @Test
    fun testNoModelTextInReceipts() = runBlocking {
        val clock = FakeClock(1700000000000L)
        val storage = SharedFakeStorage(clock)
        storage.settings["key"] = "test-key"
        val dateResolver = DateResolver(clock) { true }

        val modelFluff = "Sure thing! I got you covered, homie! Done! Absolutely acknowledged!"
        val fakeTransport = object : HttpTransport {
            override suspend fun postStream(target: Target, body: String): Flow<String> = flow {
                emit("""data: {"choices":[{"delta":{"content":"```json\n{\"reply\":\"$modelFluff\",\"calls\":[{\"tool\":\"propose_change\",\"args\":{\"intent\":\"CREATE\",\"title\":\"Clean Kitchen\",\"due_text\":\"tomorrow at 11:00am\"}}]}\n```"}}]}""")
                emit("data: [DONE]")
            }
        }

        val controller = createController(storage, clock, dateResolver, fakeTransport)
        controller.onAction(UiAction.UpdateDraft("Clean kitchen tomorrow at 11am"))
        controller.onAction(UiAction.SendMessage)
        waitUntil { !controller.state.value.isChatLoading && controller.state.value.receipts.isNotEmpty() }

        val receipt = controller.state.value.receipts.first()
        assertFalse("Receipt must NOT contain model reply prose", receipt.message.contains("Sure thing"))
        assertFalse("Receipt must NOT contain model homie", receipt.message.contains("homie"))
        assertFalse("Receipt must NOT contain generic Done!", receipt.message.contains("Done!"))
        assertTrue("Receipt must be deterministic ReceiptFormatter output", receipt.message.startsWith("Created: Clean Kitchen"))
    }

    @Test
    fun testReceiptFormatter_AllUiVisibleOpTypes() {
        val clock = FakeClock(1700000000000L)
        val dateResolver = DateResolver(clock) { true }
        val formatter = ReceiptFormatter(dateResolver)

        // 1. CREATE with dueAt
        val dueTime = 1700042400000L
        val formattedDue = dateResolver.format(dueTime)
        val nCreate = Node("n1", "task", "Doctor", "active", dueAt = dueTime)
        val remCreate = Reminder("r1", "Doctor", dueTime, "n1")
        val evCreate = RawEvent("op1", "CREATE", "node", "n1", afterJson = json.encodeToString(NodeStateSnapshot(nCreate, remCreate)))
        assertEquals("Created: Doctor ($formattedDue)", formatter.format(evCreate))

        // 2. CREATE undated
        val nUndated = Node("n2", "task", "Buy milk", "active")
        val evUndated = RawEvent("op2", "CREATE", "node", "n2", afterJson = json.encodeToString(NodeStateSnapshot(nUndated, null)))
        assertEquals("Created: Buy milk", formatter.format(evUndated))

        // 3. CANCEL
        val evCancel = RawEvent("op3", "CANCEL", "node", "n1", beforeJson = json.encodeToString(NodeStateSnapshot(nCreate, remCreate)))
        assertEquals("Cancelled: Doctor ($formattedDue)", formatter.format(evCancel))

        // 4. COMPLETE
        val evComplete = RawEvent("op4", "COMPLETE", "node", "n1", beforeJson = json.encodeToString(NodeStateSnapshot(nCreate, remCreate)))
        assertEquals("Completed: Doctor ($formattedDue)", formatter.format(evComplete))

        // 5. RESCHEDULE
        val newDue = dueTime + 3600000L
        val formattedNewDue = dateResolver.format(newDue)
        val nResched = nCreate.copy(dueAt = newDue)
        val remResched = remCreate.copy(fireAtMillis = newDue)
        val evResched = RawEvent("op5", "RESCHEDULE", "node", "n1", beforeJson = json.encodeToString(NodeStateSnapshot(nCreate, remCreate)), afterJson = json.encodeToString(NodeStateSnapshot(nResched, remResched)))
        assertEquals("Rescheduled: Doctor to $formattedNewDue", formatter.format(evResched))

        // 6. KEEP_STALE
        val evKeep = RawEvent("op6", "KEEP_STALE", "node", "n2", beforeJson = json.encodeToString(NodeStateSnapshot(nUndated, null)))
        assertEquals("Kept: Buy milk", formatter.format(evKeep))

        // 7. CANCEL_STALE
        val evCancelStale = RawEvent("op7", "CANCEL_STALE", "node", "n2", beforeJson = json.encodeToString(NodeStateSnapshot(nUndated, null)))
        assertEquals("Cancelled: Buy milk", formatter.format(evCancelStale))

        // 8. SCHEDULE reminder
        val evSched = RawEvent("op8", "SCHEDULE", "reminder", "r1", afterJson = json.encodeToString(remCreate))
        assertEquals("Scheduled reminder: Doctor ($formattedDue)", formatter.format(evSched))

        // 9. DELETE reminder
        val evDelRem = RawEvent("op9", "DELETE", "reminder", "r1", beforeJson = json.encodeToString(remCreate))
        assertEquals("Cancelled reminder: Doctor", formatter.format(evDelRem))

        // 10. CONFIRM / ACCEPT soul fact
        val soulFact = SoulFact("sf1", "preference", "Prefers dark chocolate", 0.9, "user", "manual", "active", clock.now())
        val evSoul = RawEvent("op10", "CONFIRM", "soul_fact", "sf1", afterJson = json.encodeToString(soulFact))
        assertEquals("Saved to Soul: Prefers dark chocolate", formatter.format(evSoul))

        // 11. REJECT soul fact
        val evRejectSoul = RawEvent("op11", "REJECT", "soul_fact", "sf1")
        assertEquals("Dismissed Soul fact", formatter.format(evRejectSoul))
    }
}
