// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.gateway

import com.anchor.core.SharedFakeStorage
import com.anchor.core.models.AlarmTier
import com.anchor.core.models.IntentType
import com.anchor.core.models.Node
import com.anchor.core.models.Reminder
import com.anchor.core.models.ToolResult
import com.anchor.core.resolver.Resolver
import com.anchor.core.time.DateResolver
import com.anchor.core.time.FakeClock
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Calendar

class R4TimeChipsTest {

    private lateinit var clock: FakeClock
    private lateinit var storage: SharedFakeStorage
    private lateinit var resolver: Resolver
    private lateinit var dateResolver: DateResolver
    private lateinit var gateway: Gateway

    @Before
    fun setUp() {
        val cal = Calendar.getInstance().apply {
            set(2026, Calendar.OCTOBER, 28, 12, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        clock = FakeClock(cal.timeInMillis)
        storage = SharedFakeStorage()
        resolver = Resolver()
        dateResolver = DateResolver(clock, is24HourFormat = { false })
        gateway = Gateway(storage, resolver, dateResolver, clock)
    }

    @Test
    fun testCreateWithAmbiguousTime_ReturnsNeedsTime_AndChipConfirmationCreatesNodeAndReminder() = runBlocking {
        // "remind me at 5 to call mom"
        val call = ToolCall(
            "propose_change",
            mapOf(
                "intent" to JsonPrimitive("CREATE"),
                "title" to JsonPrimitive("Call mom"),
                "due_text" to JsonPrimitive("at 5")
            )
        )

        val result = gateway.executeTool(call)
        assertTrue("Expected NeedsTime result, but was $result", result is ToolResult.NeedsTime)
        val needsTime = result as ToolResult.NeedsTime
        assertEquals(2, needsTime.chipLabels.size)

        // Proposal kind must be "time"
        val proposal = storage.getProposal(needsTime.proposalId)
        assertNotNull("Proposal must be persisted", proposal)
        assertEquals("time", proposal!!.kind)
        assertEquals(IntentType.CREATE, proposal.intent)
        assertEquals(2, proposal.options.size)

        // Find candidate for 5 PM (17:00 on Oct 28)
        val cal5pm = Calendar.getInstance().apply {
            set(2026, Calendar.OCTOBER, 28, 17, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val target5pmMillis = cal5pm.timeInMillis
        val option5pm = proposal.options.find { it.id == target5pmMillis.toString() }
        assertNotNull("5 PM option must exist", option5pm)

        // Confirm chip selection (zero model calls)
        val confirmCall = ToolCall(
            "confirm_change",
            mapOf(
                "proposal" to JsonPrimitive(proposal.id),
                "choice" to JsonPrimitive(target5pmMillis.toString())
            )
        )
        val confirmResult = gateway.executeTool(confirmCall)
        assertTrue("Expected Committed, but was $confirmResult", confirmResult is ToolResult.Committed)
        val committed = confirmResult as ToolResult.Committed
        assertTrue(committed.message.contains("Created: Call mom"))
        assertNotNull(committed.undoToken)

        // Verify Node and Reminder in storage
        val node = storage.getNode(committed.targetId)
        assertNotNull("Node must be saved", node)
        assertEquals("Call mom", node!!.title)
        assertEquals(target5pmMillis, node.dueAt)
        assertEquals("active", node.status)

        val reminders = storage.getPendingReminders()
        assertEquals("Exactly one reminder should be created", 1, reminders.size)
        assertEquals(node.id, reminders[0].nodeId)
        assertEquals(target5pmMillis, reminders[0].fireAtMillis)

        // Proposal must be consumed
        assertEquals("consumed", storage.getProposal(proposal.id)?.status)
    }

    @Test
    fun testRescheduleAmbiguousTimeAndAmbiguousTarget_TimeChipsFirstThenTargetChips() = runBlocking {
        // Create two active nodes matching "dinner"
        val now = clock.now()
        val node1 = Node("n_1", "task", "Dinner with Alice", "active", dueAt = now + 3600000L, createdAt = now, updatedAt = now)
        val node2 = Node("n_2", "task", "Dinner with Bob", "active", dueAt = now + 7200000L, createdAt = now, updatedAt = now)
        storage.createNodeAndCommit(node1, com.anchor.core.models.RawEvent("u_1", "CREATE", "node", "n_1"), Reminder("r_1", "Dinner with Alice", node1.dueAt!!, "n_1"))
        storage.createNodeAndCommit(node2, com.anchor.core.models.RawEvent("u_2", "CREATE", "node", "n_2"), Reminder("r_2", "Dinner with Bob", node2.dueAt!!, "n_2"))

        // User asks to reschedule dinner to "at 5" (ambiguous time + ambiguous target)
        val call = ToolCall(
            "propose_change",
            mapOf(
                "intent" to JsonPrimitive("RESCHEDULE"),
                "hint" to JsonPrimitive("dinner"),
                "due_text" to JsonPrimitive("at 5")
            )
        )

        // Time chips MUST come first
        val result1 = gateway.executeTool(call)
        assertTrue("Expected NeedsTime for ambiguous time, but was $result1", result1 is ToolResult.NeedsTime)
        val timeProposalId = (result1 as ToolResult.NeedsTime).proposalId

        val cal5pm = Calendar.getInstance().apply {
            set(2026, Calendar.OCTOBER, 28, 17, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val target5pmMillis = cal5pm.timeInMillis

        // Confirm 5 PM chip
        val confirmTimeCall = ToolCall(
            "confirm_change",
            mapOf(
                "proposal" to JsonPrimitive(timeProposalId),
                "choice" to JsonPrimitive(target5pmMillis.toString())
            )
        )
        val result2 = gateway.executeTool(confirmTimeCall)
        // Now target is ambiguous -> Target chips presented
        assertTrue("Expected Ambiguous target resolution, but was $result2", result2 is ToolResult.Ambiguous)
        val targetProposalId = (result2 as ToolResult.Ambiguous).proposalId

        // Confirm target node "n_1"
        val confirmTargetCall = ToolCall(
            "confirm_change",
            mapOf(
                "proposal" to JsonPrimitive(targetProposalId),
                "choice" to JsonPrimitive("n_1")
            )
        )
        val result3 = gateway.executeTool(confirmTargetCall)
        assertTrue("Expected Committed reschedule, but was $result3", result3 is ToolResult.Committed)
        assertEquals("n_1", (result3 as ToolResult.Committed).targetId)

        // Verify n_1 rescheduled to 5pm and n_2 unchanged
        val updatedNode1 = storage.getNode("n_1")
        assertEquals(target5pmMillis, updatedNode1?.dueAt)
        val rem1 = storage.getReminderByNodeId("n_1")
        assertEquals(target5pmMillis, rem1?.fireAtMillis)

        val updatedNode2 = storage.getNode("n_2")
        assertEquals(now + 7200000L, updatedNode2?.dueAt)
    }

    @Test
    fun testCancelWithAmbiguousTime_IgnoresTimeAndResolvesByText() = runBlocking {
        val now = clock.now()
        val node = Node("n_doc", "task", "Doctor Appointment", "active", dueAt = now + 3600000L, createdAt = now, updatedAt = now)
        storage.createNodeAndCommit(node, com.anchor.core.models.RawEvent("u_doc", "CREATE", "node", "n_doc"), Reminder("r_doc", "Doctor Appointment", node.dueAt!!, "n_doc"))

        // User says cancel doctor appointment at 5 (due_text "at 5" is ambiguous)
        val call = ToolCall(
            "propose_change",
            mapOf(
                "intent" to JsonPrimitive("CANCEL"),
                "hint" to JsonPrimitive("Doctor Appointment"),
                "due_text" to JsonPrimitive("at 5")
            )
        )

        // Must NOT return NeedsTime; time must be ignored as identifier and resolved directly
        val result = gateway.executeTool(call)
        assertTrue("Expected Committed, but was $result", result is ToolResult.Committed)
        assertEquals("n_doc", (result as ToolResult.Committed).targetId)
        val cancelled = storage.getNode("n_doc")
        assertEquals("cancelled", cancelled?.status)
    }

    @Test
    fun testCreateWithAmbiguousTime_MissingTitle_ReturnsMissingTitleErrorAndNoProposal() = runBlocking {
        // "remind me at 5" with no title or hint
        val call = ToolCall(
            "propose_change",
            mapOf(
                "intent" to JsonPrimitive("CREATE"),
                "due_text" to JsonPrimitive("at 5")
            )
        )

        val result = gateway.executeTool(call)
        assertTrue("Expected Error result, but was $result", result is ToolResult.Error)
        val error = result as ToolResult.Error
        assertEquals("Missing title: ask the user what it is for.", error.reason)
        assertTrue("No proposal should be saved", storage.getOpenProposals().isEmpty())
    }

    @Test
    fun testCreateWithAmbiguousTime_DuplicateTitle_TransitionsToNeedsConfirmOnTimeChipConfirm() = runBlocking {
        val call = ToolCall(
            "propose_change",
            mapOf(
                "intent" to JsonPrimitive("CREATE"),
                "title" to JsonPrimitive("Call mom"),
                "due_text" to JsonPrimitive("at 5")
            )
        )

        val result = gateway.executeTool(call)
        assertTrue("Expected NeedsTime result for ambiguous time, but was $result", result is ToolResult.NeedsTime)
        val needsTime = result as ToolResult.NeedsTime
        val proposal = storage.getProposal(needsTime.proposalId)
        assertNotNull("Time proposal must be saved", proposal)

        val chosenOption = proposal!!.options.first().id
        val chosenMillis = chosenOption.toLong()

        // Existing active item scheduled 10 seconds after the chosen candidate time
        val existing = Node("n_call", "task", "Call mom", "active", dueAt = chosenMillis + 10_000L, createdAt = clock.now(), updatedAt = clock.now())
        storage.createNodeAndCommit(existing, com.anchor.core.models.RawEvent("u_pre", "CREATE", "node", "n_call"), null)

        // Confirming the time chip triggers near-match dedupe -> NeedsConfirm
        val confirmCall = ToolCall(
            "confirm_change",
            mapOf(
                "proposal" to JsonPrimitive(proposal.id),
                "choice" to JsonPrimitive(chosenOption)
            )
        )
        val confirmResult = gateway.executeTool(confirmCall)
        assertTrue("Expected NeedsConfirm on duplicate title, but got: $confirmResult", confirmResult is ToolResult.NeedsConfirm)
        val needsConfirm = confirmResult as ToolResult.NeedsConfirm
        assertEquals(listOf("Update existing", "Add anyway", "Cancel"), needsConfirm.options)
        assertTrue(needsConfirm.message.contains("Call mom"))
    }

    @Test
    fun testCreateWithAmbiguousTime_UntitledAlarm_PreservesPayloadAndAutoTitlesOnConfirm() = runBlocking {
        // "alarm at 5" with untitled = true, label = "alarm", due_text = "at 5"
        val call = ToolCall(
            "propose_change",
            mapOf(
                "intent" to JsonPrimitive("CREATE"),
                "untitled" to JsonPrimitive("true"),
                "label" to JsonPrimitive("alarm"),
                "due_text" to JsonPrimitive("at 5")
            )
        )

        val result = gateway.executeTool(call)
        assertTrue("Expected NeedsTime result, but was $result", result is ToolResult.NeedsTime)
        val needsTime = result as ToolResult.NeedsTime

        val proposal = storage.getProposal(needsTime.proposalId)
        assertNotNull(proposal)
        val payload = kotlinx.serialization.json.Json.decodeFromString<com.anchor.core.models.ProposalPayload>(proposal!!.payload!!)
        assertTrue("Proposal payload must have untitled=true", payload.untitled)
        assertEquals("Alarm", payload.label)

        val cal5pm = Calendar.getInstance().apply {
            set(2026, Calendar.OCTOBER, 28, 17, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val target5pmMillis = cal5pm.timeInMillis

        val confirmCall = ToolCall(
            "confirm_change",
            mapOf(
                "proposal" to JsonPrimitive(proposal.id),
                "choice" to JsonPrimitive(target5pmMillis.toString())
            )
        )
        val confirmResult = gateway.executeTool(confirmCall)
        assertTrue("Expected Committed, but was $confirmResult", confirmResult is ToolResult.Committed)
        val committed = confirmResult as ToolResult.Committed

        val node = storage.getNode(committed.targetId)!!
        val expectedTitle = "${dateResolver.formatTimeOfDay(target5pmMillis)} Alarm"
        assertEquals(expectedTitle, node.title)
        assertEquals(target5pmMillis, node.dueAt)
    }

    @Test
    fun testConfirmChange_RejectsAmChoiceWhenUserSpecifiedPm() = runBlocking {
        val call = ToolCall(
            "propose_change",
            mapOf(
                "intent" to JsonPrimitive("CREATE"),
                "title" to JsonPrimitive("Call doctor"),
                "due_text" to JsonPrimitive("at 5")
            )
        )
        val result = gateway.executeTool(call)
        assertTrue("Expected NeedsTime, was $result", result is ToolResult.NeedsTime)
        val proposal = storage.getProposal((result as ToolResult.NeedsTime).proposalId)!!

        val cal5am = Calendar.getInstance().apply {
            set(2026, Calendar.OCTOBER, 29, 5, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val cal5pm = Calendar.getInstance().apply {
            set(2026, Calendar.OCTOBER, 28, 17, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }

        // User typed "5:00 pm", but model tried to pick 5:00 am
        val badCall = ToolCall(
            "confirm_change",
            mapOf(
                "proposal" to JsonPrimitive(proposal.id),
                "choice" to JsonPrimitive(cal5am.timeInMillis.toString())
            )
        )
        val badResult = gateway.executeTool(badCall, userMessage = "5:00 pm")
        assertTrue("Expected Error for AM choice when user specified PM, was $badResult", badResult is ToolResult.Error)
        assertTrue((badResult as ToolResult.Error).reason.contains("does not match user's response"))

        // Correct 5:00 pm choice matches
        val goodCall = ToolCall(
            "confirm_change",
            mapOf(
                "proposal" to JsonPrimitive(proposal.id),
                "choice" to JsonPrimitive(cal5pm.timeInMillis.toString())
            )
        )
        val goodResult = gateway.executeTool(goodCall, userMessage = "5:00 pm")
        assertTrue("Expected Committed for matching PM choice, was $goodResult", goodResult is ToolResult.Committed)
    }
}
