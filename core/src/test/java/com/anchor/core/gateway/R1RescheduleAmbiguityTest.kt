// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.gateway

import com.anchor.core.SharedFakeStorage
import com.anchor.core.alarms.AlarmScheduler
import com.anchor.core.context.ContextBuilder
import com.anchor.core.models.*
import com.anchor.core.provider.HttpTransport
import com.anchor.core.provider.ProviderOrchestrator
import com.anchor.core.provider.RateLimiter
import com.anchor.core.provider.Target
import com.anchor.core.resolver.Resolver
import com.anchor.core.time.DateResolver
import com.anchor.core.time.FakeClock
import com.anchor.core.ui.ChatController
import com.anchor.core.ui.UiAction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class R1RescheduleAmbiguityTest {

    @Test
    fun testRescheduleAmbiguity_TwoDinners_ConfirmAndUndo() = runBlocking {
        // Base time: Wednesday 12:00 UTC (1_700_000_000_000L)
        val baseTime = 1_700_000_000_000L
        val clock = FakeClock(baseTime)
        val dateResolver = DateResolver(clock)
        val storage = SharedFakeStorage()
        val resolver = Resolver()
        val gateway = Gateway(storage, resolver, dateResolver, clock)

        // Two dinners: one tomorrow (Thursday) at 7pm, one next week (Thursday) at 7pm
        val tomorrow7pm = dateResolver.parse("tomorrow at 7pm")!!
        val nextWeek7pm = tomorrow7pm + (7L * 24 * 3600 * 1000L)
        val targetTomorrow8pm = dateResolver.parse("tomorrow at 8pm")!!

        val dinner1 = Node("n1", "event", "Dinner with Alice", "active", tomorrow7pm, createdAt = baseTime, updatedAt = baseTime)
        val dinner2 = Node("n2", "event", "Dinner with Bob", "active", nextWeek7pm, createdAt = baseTime, updatedAt = baseTime)
        storage.activeNodes.add(dinner1)
        storage.activeNodes.add(dinner2)

        val rem1 = Reminder("r_n1", "Dinner with Alice", tomorrow7pm, "n1", AlarmTier.GENTLE)
        val rem2 = Reminder("r_n2", "Dinner with Bob", nextWeek7pm, "n2", AlarmTier.ALARM)
        storage.reminders.add(rem1)
        storage.reminders.add(rem2)

        // 1. "reschedule dinner to tomorrow at 8pm" -> Ambiguous
        val rescheduleCall = ToolCall("propose_change", mapOf(
            "intent" to JsonPrimitive("RESCHEDULE"),
            "hint" to JsonPrimitive("dinner"),
            "due_text" to JsonPrimitive("tomorrow at 8pm")
        ))
        val resResult = gateway.executeTool(rescheduleCall)
        assertTrue("Must be Ambiguous for 'dinner' between two dinners, got $resResult", resResult is ToolResult.Ambiguous)
        val amb = resResult as ToolResult.Ambiguous
        val proposalId = amb.proposalId

        // Proposal must be open and options must contain n1 and n2
        val proposal = storage.getProposal(proposalId)
        assertNotNull(proposal)
        assertEquals(2, proposal!!.options.size)

        // 2. Confirm chip for Alice (n1)
        val confirmCall = ToolCall("confirm_change", mapOf(
            "proposal" to JsonPrimitive(proposalId),
            "choice" to JsonPrimitive("n1")
        ))
        val confirmResult = gateway.executeTool(confirmCall)
        assertTrue("Confirm must commit reschedule, got $confirmResult", confirmResult is ToolResult.Committed)
        val committed = confirmResult as ToolResult.Committed
        assertTrue("Must have an undo token", committed.undoToken.startsWith("u_"))

        // Verify chosen node: dueAt updated to target, status still active, updatedAt updated
        val updatedNode1 = storage.getNode("n1")!!
        assertEquals("Chosen node dueAt must equal target tomorrow 8pm", targetTomorrow8pm, updatedNode1.dueAt)
        assertEquals("Chosen node status must remain active", "active", updatedNode1.status)
        assertNull("Chosen node closedAt must be null", updatedNode1.closedAt)

        // Verify reminder for chosen node: fireAt moved, tier preserved (GENTLE)
        val updatedRem1 = storage.getReminderByNodeId("n1")
        assertNotNull(updatedRem1)
        assertEquals(targetTomorrow8pm, updatedRem1!!.fireAtMillis)
        assertEquals("Reminder tier must be preserved", AlarmTier.GENTLE, updatedRem1.tier)

        // Verify other dinner untouched
        val untouchedNode2 = storage.getNode("n2")!!
        assertEquals(nextWeek7pm, untouchedNode2.dueAt)
        assertEquals("active", untouchedNode2.status)
        val untouchedRem2 = storage.getReminderByNodeId("n2")!!
        assertEquals(nextWeek7pm, untouchedRem2.fireAtMillis)
        assertEquals(AlarmTier.ALARM, untouchedRem2.tier)

        // Verify proposal consumed
        val consumedProposal = storage.getProposal(proposalId)
        assertNotNull(consumedProposal)
        assertEquals("consumed", consumedProposal!!.status)

        // 3. Undo restores old dueAt and alarm
        val undoCall = ToolCall("undo", mapOf("token" to JsonPrimitive(committed.undoToken)))
        val undoResult = gateway.executeTool(undoCall)
        assertTrue("Undo must commit, got $undoResult", undoResult is ToolResult.Committed)

        val restoredNode1 = storage.getNode("n1")!!
        assertEquals("Restored node dueAt must equal original tomorrow 7pm", tomorrow7pm, restoredNode1.dueAt)
        assertEquals("active", restoredNode1.status)

        val restoredRem1 = storage.getReminderByNodeId("n1")!!
        assertEquals(tomorrow7pm, restoredRem1.fireAtMillis)
        assertEquals(AlarmTier.GENTLE, restoredRem1.tier)
    }

    @Test
    fun testResolver_RescheduleNullTime_vs_CancelTargetTime() {
        val baseTime = 1_700_000_000_000L
        val clock = FakeClock(baseTime)
        val dateResolver = DateResolver(clock)
        val resolver = Resolver()

        val tomorrow19 = dateResolver.parse("tomorrow at 7pm")!!
        val thursday = tomorrow19 + (3L * 24 * 3600 * 1000L)
        val tomorrow20 = dateResolver.parse("tomorrow at 8pm")!!

        val candidates = listOf(
            Node("n1", "event", "Dinner with Alice", "active", tomorrow19),
            Node("n2", "event", "Dinner with Bob", "active", thursday)
        )

        // For RESCHEDULE: destination time (tomorrow 8pm) must NOT be passed to resolver.
        // Resolver with null time returns Ambiguous.
        val rescheduleRes = resolver.resolve(IntentType.RESCHEDULE, "dinner", null, candidates)
        assertTrue("RESCHEDULE with null time should be Ambiguous, got $rescheduleRes", rescheduleRes is Resolution.Ambiguous)

        // But on old buggy code where tomorrow20 is passed as targetTimeMillis to resolver:
        // Math.abs(tomorrow19 - tomorrow20) < 24h, so it incorrectly crowns n1 as ClearWinner!
        val buggyRescheduleRes = resolver.resolve(IntentType.RESCHEDULE, "dinner", tomorrow20, candidates)
        assertTrue("Passing destination time to resolver crowned ClearWinner incorrectly", buggyRescheduleRes is Resolution.ClearWinner)

        // For CANCEL: "tomorrow's dinner" with target time tomorrow 19:00 IS an identifier -> ClearWinner tomorrow
        val cancelRes = resolver.resolve(IntentType.CANCEL, "tomorrow's dinner", tomorrow19, candidates)
        assertTrue("CANCEL with target time should be ClearWinner, got $cancelRes", cancelRes is Resolution.ClearWinner)
        assertEquals("n1", (cancelRes as Resolution.ClearWinner).node.id)
    }

    @Test
    fun testRescheduleAmbiguity_TargetInPast_ConsumesProposalAndErrors() = runBlocking {
        val baseTime = 1_700_000_000_000L
        val clock = FakeClock(baseTime)
        val dateResolver = DateResolver(clock)
        val storage = SharedFakeStorage()
        val gateway = Gateway(storage, Resolver(), dateResolver, clock)

        val tomorrow8pm = dateResolver.parse("tomorrow at 8pm")!!
        val node1 = Node("n1", "event", "Dinner with Alice", "active", tomorrow8pm)
        val node2 = Node("n2", "event", "Dinner with Bob", "active", tomorrow8pm + 86400000L)
        storage.activeNodes.add(node1)
        storage.activeNodes.add(node2)

        // Reschedule proposal created
        val res = gateway.executeTool(ToolCall("propose_change", mapOf(
            "intent" to JsonPrimitive("RESCHEDULE"),
            "hint" to JsonPrimitive("dinner"),
            "due_text" to JsonPrimitive("tomorrow at 8pm")
        )))
        assertTrue(res is ToolResult.Ambiguous)
        val pId = (res as ToolResult.Ambiguous).proposalId

        // Now advance clock past tomorrow 8pm (e.g. 2 days later)
        clock.currentTime += 2L * 24 * 3600 * 1000L

        // User taps chip after the destination time has passed
        val confirmRes = gateway.executeTool(ToolCall("confirm_change", mapOf(
            "proposal" to JsonPrimitive(pId),
            "choice" to JsonPrimitive("n1")
        )))

        assertTrue("Confirming expired target time must return Error, got $confirmRes", confirmRes is ToolResult.Error)
        val err = confirmRes as ToolResult.Error
        assertTrue("Error reason must mention passed: ${err.reason}", err.reason.contains("passed", ignoreCase = true))

        // Proposal must be consumed so no dead chips remain
        val p = storage.getProposal(pId)
        assertNotNull(p)
        assertEquals("consumed", p!!.status)
    }

    @Test
    fun testUpdateDirectAndProposal_ReturnsExplicitError() = runBlocking {
        val clock = FakeClock(1_700_000_000_000L)
        val dateResolver = DateResolver(clock)
        val storage = SharedFakeStorage()
        val gateway = Gateway(storage, Resolver(), dateResolver, clock)

        val node = Node("n1", "task", "Old Title", "active", createdAt = 1000L, updatedAt = 1000L)
        storage.activeNodes.add(node)

        // 1. Direct propose_change with UPDATE must return ToolResult.Error
        val updateCall = ToolCall("propose_change", mapOf(
            "intent" to JsonPrimitive("UPDATE"),
            "hint" to JsonPrimitive("Old Title")
        ))
        val updateResult = gateway.executeTool(updateCall)
        assertTrue("UPDATE direct must return ToolResult.Error, got $updateResult", updateResult is ToolResult.Error)
        assertEquals("UPDATE is not supported yet", (updateResult as ToolResult.Error).reason)

        // Node unchanged, no events logged
        assertEquals(1000L, storage.getNode("n1")!!.updatedAt)
        assertEquals(0, storage.loggedEvents.size)

        // 2. Proposal with intent UPDATE confirmed must return Error
        storage.saveProposal(Proposal("p_upd", "ambiguous", IntentType.UPDATE, listOf(ProposalOption("n1", "Old Title"))))
        val confirmUpdate = gateway.executeTool(ToolCall("confirm_change", mapOf(
            "proposal" to JsonPrimitive("p_upd"),
            "choice" to JsonPrimitive("n1")
        )))
        assertTrue("UPDATE via proposal must return ToolResult.Error, got $confirmUpdate", confirmUpdate is ToolResult.Error)
        assertEquals("UPDATE is not supported yet", (confirmUpdate as ToolResult.Error).reason)
        assertEquals("active", storage.getNode("n1")!!.status)
        assertEquals(0, storage.loggedEvents.size)
    }

    @Test
    fun testChatController_RescheduleChipTap_ZeroModelCalls() = runBlocking {
        val baseTime = 1_700_000_000_000L
        val clock = FakeClock(baseTime)
        val dateResolver = DateResolver(clock)
        val storage = SharedFakeStorage()
        storage.settings["key"] = "test-key"

        val tomorrow7pm = dateResolver.parse("tomorrow at 7pm")!!
        val nextWeek7pm = tomorrow7pm + (7L * 24 * 3600 * 1000L)
        val targetTomorrow8pm = dateResolver.parse("tomorrow at 8pm")!!

        storage.activeNodes.add(Node("n1", "event", "Dinner with Alice", "active", tomorrow7pm))
        storage.activeNodes.add(Node("n2", "event", "Dinner with Bob", "active", nextWeek7pm))
        storage.reminders.add(Reminder("r_n1", "Dinner with Alice", tomorrow7pm, "n1", AlarmTier.ALARM))

        var modelCalls = 0
        val fakeTransport = object : HttpTransport {
            override suspend fun postStream(target: Target, body: String): Flow<String> = flow {
                modelCalls++
                // Turn 1: model returns propose_change RESCHEDULE
                emit("""data: {"choices":[{"delta":{"content":"```json\n{\"reply\":\"Which dinner?\",\"calls\":[{\"tool\":\"propose_change\",\"args\":{\"intent\":\"RESCHEDULE\",\"hint\":\"dinner\",\"due_text\":\"tomorrow at 8pm\"}}]}\n```"}}]}""")
                emit("data: [DONE]")
            }
        }

        val checkinBuilder = com.anchor.core.nudge.CheckinBuilder(storage, clock, dateResolver)
        val contextBuilder = ContextBuilder(storage, clock, dateResolver, checkinBuilder)
        val gateway = Gateway(storage, Resolver(), dateResolver, clock)
        val scheduler = object : AlarmScheduler {
            override val lastFiredId = MutableStateFlow<String?>(null)
            override fun schedule(reminder: Reminder) {}
            override fun cancel(reminderId: String) {}
        }

        val controller = ChatController(
            storage, ProviderOrchestrator(RateLimiter { clock.now() }, fakeTransport), contextBuilder,
            gateway, scheduler, TestScope(UnconfinedTestDispatcher()), UnconfinedTestDispatcher(), clock
        )

        // Turn 1: User asks to reschedule dinner
        controller.onAction(UiAction.UpdateDraft("reschedule dinner to tomorrow at 8pm"))
        controller.onAction(UiAction.SendMessage)

        assertEquals("Model should be called exactly once for Turn 1", 1, modelCalls)
        val proposals = storage.getOpenProposals()
        assertEquals(1, proposals.size)
        val proposalId = proposals[0].id

        // Turn 2: User taps chip directly -> UiAction.ConfirmProposal
        controller.onAction(UiAction.ConfirmProposal(proposalId, "n1"))

        // Assert ZERO extra model calls
        assertEquals("Chip tap must execute with ZERO model calls", 1, modelCalls)

        // Node updated to target
        val updatedNode = storage.getNode("n1")!!
        assertEquals(targetTomorrow8pm, updatedNode.dueAt)
        assertEquals("active", updatedNode.status)

        // Reminder updated to target
        val updatedRem = storage.getReminderByNodeId("n1")!!
        assertEquals(targetTomorrow8pm, updatedRem.fireAtMillis)

        // Proposal consumed
        assertEquals("consumed", storage.getProposal(proposalId)!!.status)
    }
}
