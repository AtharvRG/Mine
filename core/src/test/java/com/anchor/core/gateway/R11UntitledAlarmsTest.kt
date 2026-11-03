// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.gateway

import com.anchor.core.SharedFakeStorage
import com.anchor.core.models.IntentType
import com.anchor.core.models.Node
import com.anchor.core.models.ToolResult
import com.anchor.core.resolver.Resolver
import com.anchor.core.time.DateResolver
import com.anchor.core.time.FakeClock
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class R11UntitledAlarmsTest {

    @Test
    fun testUntitledDuration_CreatesMMSSAlarm() = runBlocking {
        val cal = Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, 25, 12, 0, 0); set(Calendar.MILLISECOND, 0) }
        val now = cal.timeInMillis
        val clock = FakeClock(now)
        val dateResolver = DateResolver(clock)
        val storage = SharedFakeStorage()
        val gateway = Gateway(storage, Resolver(), dateResolver, clock)

        val toolCall = ToolCall(
            "propose_change",
            mapOf(
                "intent" to JsonPrimitive("CREATE"),
                "untitled" to JsonPrimitive("true"),
                "label" to JsonPrimitive("alarm"),
                "due_text" to JsonPrimitive("in 2 min 32 seconds")
            )
        )
        val result = gateway.executeTool(toolCall)

        assertTrue("Expected committed result", result is ToolResult.Committed)
        val committed = result as ToolResult.Committed
        val node = storage.getNode(committed.targetId)!!

        assertEquals("02:32 Alarm", node.title)
        assertEquals(now + 152_000L, node.dueAt)

        val rem = storage.getReminderByNodeId(node.id)!!
        assertEquals("02:32 Alarm", rem.text)
        assertEquals(now + 152_000L, rem.fireAtMillis)
    }

    @Test
    fun testUntitledLongDuration_CreatesHMMSSAlarm() = runBlocking {
        val cal = Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, 25, 12, 0, 0); set(Calendar.MILLISECOND, 0) }
        val now = cal.timeInMillis
        val clock = FakeClock(now)
        val dateResolver = DateResolver(clock)
        val storage = SharedFakeStorage()
        val gateway = Gateway(storage, Resolver(), dateResolver, clock)

        val toolCall = ToolCall(
            "propose_change",
            mapOf(
                "intent" to JsonPrimitive("CREATE"),
                "untitled" to JsonPrimitive("true"),
                "label" to JsonPrimitive("alarm"),
                "due_text" to JsonPrimitive("1 hour and 5 minutes")
            )
        )
        val result = gateway.executeTool(toolCall)

        assertTrue(result is ToolResult.Committed)
        val committed = result as ToolResult.Committed
        val node = storage.getNode(committed.targetId)!!
        assertEquals("1:05:00 Alarm", node.title)
        assertEquals(now + 3_900_000L, node.dueAt)
    }

    @Test
    fun testUntitledAbsoluteTime_CreatesTimeOfDayAlarm() = runBlocking {
        val cal = Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, 25, 12, 0, 0); set(Calendar.MILLISECOND, 0) }
        val now = cal.timeInMillis
        val clock = FakeClock(now)
        val dateResolver = DateResolver(clock, is24HourFormat = { true })
        val storage = SharedFakeStorage()
        val gateway = Gateway(storage, Resolver(), dateResolver, clock)

        val toolCall = ToolCall(
            "propose_change",
            mapOf(
                "intent" to JsonPrimitive("CREATE"),
                "untitled" to JsonPrimitive("true"),
                "label" to JsonPrimitive("alarm"),
                "due_text" to JsonPrimitive("at 15:30")
            )
        )
        val result = gateway.executeTool(toolCall)

        assertTrue(result is ToolResult.Committed)
        val committed = result as ToolResult.Committed
        val node = storage.getNode(committed.targetId)!!
        assertEquals("15:30 Alarm", node.title)
    }

    @Test
    fun testUntitledLabels_WhitelistAndDefault() = runBlocking {
        val cal = Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, 25, 12, 0, 0); set(Calendar.MILLISECOND, 0) }
        val now = cal.timeInMillis
        val clock = FakeClock(now)
        val dateResolver = DateResolver(clock)
        val storage = SharedFakeStorage()
        val gateway = Gateway(storage, Resolver(), dateResolver, clock)

        // Whitelisted: reminder
        val resReminder = gateway.executeTool(ToolCall("propose_change", mapOf(
            "intent" to JsonPrimitive("CREATE"),
            "untitled" to JsonPrimitive("true"),
            "label" to JsonPrimitive("reminder"),
            "due_text" to JsonPrimitive("in 2 min 32 seconds")
        ))) as ToolResult.Committed
        assertEquals("02:32 Reminder", storage.getNode(resReminder.targetId)!!.title)

        // Whitelisted: notification
        val resNotification = gateway.executeTool(ToolCall("propose_change", mapOf(
            "intent" to JsonPrimitive("CREATE"),
            "untitled" to JsonPrimitive("true"),
            "label" to JsonPrimitive("notification"),
            "due_text" to JsonPrimitive("in 2 min 32 seconds")
        ))) as ToolResult.Committed
        assertEquals("02:32 Notification", storage.getNode(resNotification.targetId)!!.title)

        // Non-whitelisted: fallback to Alarm
        val resFallback = gateway.executeTool(ToolCall("propose_change", mapOf(
            "intent" to JsonPrimitive("CREATE"),
            "untitled" to JsonPrimitive("true"),
            "label" to JsonPrimitive("timer"),
            "due_text" to JsonPrimitive("in 2 min 32 seconds")
        ))) as ToolResult.Committed
        assertEquals("02:32 Alarm", storage.getNode(resFallback.targetId)!!.title)
    }

    @Test
    fun testUntitledDuplicate_BypassesDuplicateCheck() = runBlocking {
        val cal = Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, 25, 12, 0, 0); set(Calendar.MILLISECOND, 0) }
        val now = cal.timeInMillis
        val clock = FakeClock(now)
        val dateResolver = DateResolver(clock)
        val storage = SharedFakeStorage()
        val gateway = Gateway(storage, Resolver(), dateResolver, clock)

        val call1 = ToolCall("propose_change", mapOf(
            "intent" to JsonPrimitive("CREATE"),
            "untitled" to JsonPrimitive("true"),
            "label" to JsonPrimitive("alarm"),
            "due_text" to JsonPrimitive("in 60 seconds")
        ))
        val res1 = gateway.executeTool(call1)
        assertTrue(res1 is ToolResult.Committed)
        assertEquals("01:00 Alarm", storage.getNode((res1 as ToolResult.Committed).targetId)!!.title)

        // Second untitled alarm with same title 01:00 Alarm MUST succeed (no duplicate reject)
        val call2 = ToolCall("propose_change", mapOf(
            "intent" to JsonPrimitive("CREATE"),
            "untitled" to JsonPrimitive("true"),
            "label" to JsonPrimitive("alarm"),
            "due_text" to JsonPrimitive("in 60 seconds")
        ))
        val res2 = gateway.executeTool(call2)
        assertTrue(res2 is ToolResult.Committed)
        assertEquals("01:00 Alarm", storage.getNode((res2 as ToolResult.Committed).targetId)!!.title)
        assertEquals(2, storage.activeNodes.size)

        // Non-untitled duplicate MUST still be rejected
        val titledCall1 = ToolCall("propose_change", mapOf("intent" to JsonPrimitive("CREATE"), "hint" to JsonPrimitive("Buy milk")))
        assertTrue(gateway.executeTool(titledCall1) is ToolResult.Committed)
        val titledCall2 = ToolCall("propose_change", mapOf("intent" to JsonPrimitive("CREATE"), "hint" to JsonPrimitive("Buy milk")))
        val dupResult = gateway.executeTool(titledCall2)
        assertTrue(dupResult is ToolResult.Error)
        assertEquals("An active item named 'Buy milk' already exists.", (dupResult as ToolResult.Error).reason)
    }

    @Test
    fun testBlankTitleWithoutUntitled_ReturnsMissingTitleError() = runBlocking {
        val clock = FakeClock(1000L)
        val gateway = Gateway(SharedFakeStorage(), Resolver(), DateResolver(clock), clock)

        val call = ToolCall("propose_change", mapOf("intent" to JsonPrimitive("CREATE"), "hint" to JsonPrimitive("   ")))
        val result = gateway.executeTool(call)
        assertTrue(result is ToolResult.Error)
        assertEquals("Missing title: ask the user what it is for.", (result as ToolResult.Error).reason)
    }
}
