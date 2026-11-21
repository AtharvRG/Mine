// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.gateway

import com.anchor.core.SharedFakeStorage
import com.anchor.core.context.ContextBuilder
import com.anchor.core.models.Node
import com.anchor.core.models.ToolResult
import com.anchor.core.models.TopicNote
import com.anchor.core.nudge.CheckinBuilder
import com.anchor.core.resolver.Resolver
import com.anchor.core.time.DateResolver
import com.anchor.core.time.FakeClock
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TopicNotesTest {

    private lateinit var clock: FakeClock
    private lateinit var storage: SharedFakeStorage
    private lateinit var dateResolver: DateResolver
    private lateinit var gateway: Gateway
    private lateinit var aliasRegistry: AliasRegistry

    @Before
    fun setUp() {
        clock = FakeClock(1700000000000L)
        storage = SharedFakeStorage(clock)
        dateResolver = DateResolver(clock, { false })
        gateway = Gateway(storage, Resolver(), dateResolver, clock)
        aliasRegistry = gateway.aliasRegistry
    }

    @Test
    fun testUpsertTopicNote_Max3PinnedNotesEnforced() = runBlocking {
        // Pin 3 notes
        for (i in 1..3) {
            val res = gateway.executeTool(
                ToolCall(
                    tool = "upsert_topic_note",
                    args = mapOf(
                        "topic" to JsonPrimitive("Note $i"),
                        "bullets" to JsonPrimitive("- Bullet $i"),
                        "pinned" to JsonPrimitive(true)
                    )
                ),
                executedCalls = mutableSetOf()
            )
            assertTrue(res is ToolResult.Committed)
            val note = storage.getTopicNoteByTopic("Note $i")
            assertNotNull(note)
            assertTrue("Note $i should be pinned", note!!.pinned)
        }

        // Try to pin a 4th note
        val res4 = gateway.executeTool(
            ToolCall(
                tool = "upsert_topic_note",
                args = mapOf(
                    "topic" to JsonPrimitive("Note 4"),
                    "bullets" to JsonPrimitive("- Bullet 4"),
                    "pinned" to JsonPrimitive(true)
                )
            ),
            executedCalls = mutableSetOf()
        )
        assertTrue(res4 is ToolResult.Committed)
        assertTrue((res4 as ToolResult.Committed).message.contains("max 3 pinned notes reached"))

        val note4 = storage.getTopicNoteByTopic("Note 4")
        assertNotNull(note4)
        assertFalse("4th note must NOT be pinned", note4!!.pinned)
    }

    @Test
    fun testLinkNoteReference_SupportsReadOnlyAndReadWriteModes() = runBlocking {
        // Create a living note
        storage.saveTopicNote(
            TopicNote(
                id = "tn_packing",
                topic = "Conference Packing",
                content = "- Passport\n- Laptop charger",
                bulletsJson = "[\"Passport\",\"Laptop charger\"]",
                pinned = true,
                lastEditedAt = clock.now(),
                createdAt = clock.now()
            )
        )

        // Create 2 tasks
        val task1 = Node(id = "n_flight", type = "task", title = "Board flight", status = "active", createdAt = clock.now())
        val task2 = Node(id = "n_hotel", type = "task", title = "Hotel checkin", status = "active", createdAt = clock.now())
        storage.saveNode(task1)
        storage.saveNode(task2)

        // Register alias note_1 for tn_packing
        aliasRegistry.aliasFor("note", "tn_packing")
        aliasRegistry.aliasFor("n", "n_flight")
        aliasRegistry.aliasFor("n", "n_hotel")

        // Link flight to packing note as READ_ONLY using aliases
        val resRo = gateway.executeTool(
            ToolCall(
                tool = "link_note_reference",
                args = mapOf(
                    "note_id" to JsonPrimitive("note_1"),
                    "node_id" to JsonPrimitive("n_1"),
                    "mode" to JsonPrimitive("read_only")
                )
            ),
            executedCalls = mutableSetOf()
        )
        assertTrue(resRo is ToolResult.Committed)
        assertTrue((resRo as ToolResult.Committed).message.contains("read_only"))

        val refs1 = storage.getReferencesForNode("n_flight")
        assertEquals(1, refs1.size)
        assertEquals("read_only", refs1[0].mode)

        // Link hotel to packing note as READ_WRITE
        val resRw = gateway.executeTool(
            ToolCall(
                tool = "link_note_reference",
                args = mapOf(
                    "note_id" to JsonPrimitive("tn_packing"),
                    "node_id" to JsonPrimitive("n_hotel"),
                    "mode" to JsonPrimitive("read_write")
                )
            ),
            executedCalls = mutableSetOf()
        )
        assertTrue(resRw is ToolResult.Committed)
        assertTrue((resRw as ToolResult.Committed).message.contains("read_write"))

        val refs2 = storage.getReferencesForNode("n_hotel")
        assertEquals(1, refs2.size)
        assertEquals("read_write", refs2[0].mode)

        // Check ContextBuilder formats [READ_ONLY] and instruction
        val checkinBuilder = CheckinBuilder(storage, clock, dateResolver)
        val contextBuilder = ContextBuilder(storage, clock, dateResolver, checkinBuilder)
        contextBuilder.aliasRegistry = aliasRegistry

        val pack = contextBuilder.buildPack()
        val sysPrompt = pack.first { it.role == "system" }.content!!

        assertTrue(sysPrompt.contains("[READ_ONLY]"))
        assertTrue(sysPrompt.contains("READ_ONLY: do not modify its bullets or title"))
    }
}
