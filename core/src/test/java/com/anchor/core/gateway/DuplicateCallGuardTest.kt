// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.gateway

import com.anchor.core.SharedFakeStorage
import com.anchor.core.models.ToolResult
import com.anchor.core.resolver.Resolver
import com.anchor.core.time.DateResolver
import com.anchor.core.time.FakeClock
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Calendar

/**
 * The duplicate guard exists to stop a model repeating one call within an envelope. It must never
 * fire on two calls that differ in what the user asked for: a dropped second call is a silently
 * lost reminder.
 */
class DuplicateCallGuardTest {

    private lateinit var clock: FakeClock
    private lateinit var storage: SharedFakeStorage
    private lateinit var gateway: Gateway

    @Before
    fun setUp() {
        val now = Calendar.getInstance().apply {
            set(2026, Calendar.OCTOBER, 25, 12, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        clock = FakeClock(now)
        storage = SharedFakeStorage(clock)
        gateway = Gateway(storage, Resolver(), DateResolver(clock), clock)
    }

    private fun create(title: kotlinx.serialization.json.JsonElement, whenText: String) = ToolCall(
        "propose_change",
        mapOf(
            "intent" to JsonPrimitive("CREATE"),
            "title" to title,
            "due_text" to JsonPrimitive(whenText)
        )
    )

    @Test
    fun testIdenticalCalls_SecondIsRejectedAsDuplicate() = runBlocking {
        val executed = mutableSetOf<String>()
        val first = gateway.executeTool(create(JsonPrimitive("Buy milk"), "tomorrow at 9am"), executed)
        val second = gateway.executeTool(create(JsonPrimitive("Buy milk"), "tomorrow at 9am"), executed)

        assertTrue("First call must commit: $first", first is ToolResult.Committed)
        assertTrue("Repeating the same call must be rejected: $second", second is ToolResult.Error)
        // R-25: the reminder is due "tomorrow" relative to this test's FakeClock, so the query has
        // to be asked against that clock — the wall clock is years past it and would return nothing.
        assertEquals(1, storage.getActiveReminders(clock.now()).size)
        assertEquals((first as ToolResult.Committed).targetId, storage.getActiveReminders(clock.now()).single().nodeId)
    }

    @Test
    fun testNullTitleVsNAPlaceholder_AreNotTreatedAsDuplicates() = runBlocking {
        val executed = mutableSetOf<String>()
        // Same intent and time; the only difference is a placeholder the model invented for an
        // optional argument it did not have. Resolution drops both, so a key built from the
        // resolved args would make the second call look like a repeat of the first.
        fun createWithNote(note: kotlinx.serialization.json.JsonElement) = ToolCall(
            "propose_change",
            mapOf(
                "intent" to JsonPrimitive("CREATE"),
                "title" to JsonPrimitive("Buy milk"),
                "due_text" to JsonPrimitive("tomorrow at 9am"),
                "note" to note
            )
        )
        val first = gateway.executeTool(createWithNote(JsonNull), executed)
        val second = gateway.executeTool(createWithNote(JsonPrimitive("n/a")), executed)

        assertTrue("First call must commit: $first", first is ToolResult.Committed)
        assertTrue(
            "A placeholder must not make a different call look like a duplicate: $second",
            second !is ToolResult.Error || (second as ToolResult.Error).reason != "Duplicate tool call ignored"
        )
    }

    @Test
    fun testItemLiterallyNamedNone_IsNotDroppedAsPlaceholder() = runBlocking {
        val executed = mutableSetOf<String>()
        val result = gateway.executeTool(create(JsonPrimitive("None"), "tomorrow at 9am"), executed)

        assertTrue("A user-named item called 'None' must reach the gateway: $result", result is ToolResult.Committed)
        val node = storage.getNode((result as ToolResult.Committed).targetId)!!
        assertEquals("None", node.title)
    }

    @Test
    fun testArgumentOrderDoesNotAffectIdentity() = runBlocking {
        val executed = mutableSetOf<String>()
        val a = ToolCall(
            "propose_change",
            mapOf("intent" to JsonPrimitive("CREATE"), "title" to JsonPrimitive("Buy milk"), "due_text" to JsonPrimitive("tomorrow at 9am"))
        )
        val b = ToolCall(
            "propose_change",
            mapOf("due_text" to JsonPrimitive("tomorrow at 9am"), "title" to JsonPrimitive("Buy milk"), "intent" to JsonPrimitive("CREATE"))
        )

        val first = gateway.executeTool(a, executed)
        val second = gateway.executeTool(b, executed)

        assertTrue("First call must commit: $first", first is ToolResult.Committed)
        assertTrue("Same call in a different key order is still a duplicate: $second", second is ToolResult.Error)
    }
}
