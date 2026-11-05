// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.storage

import com.anchor.core.SharedFakeStorage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ChatHistoryFtsTest {

    private lateinit var storage: SharedFakeStorage

    @Before
    fun setUp() {
        storage = SharedFakeStorage()
    }

    @Test
    fun testSearchMemoryFts_FindsConversationHistory() = runBlocking {
        // Insert user and assistant messages
        storage.insertRawMessage("msg_u1", "user", "I need to remember the secret passkey blue-falcon")
        storage.insertRawMessage("msg_a1", "assistant", "Noted, your passkey blue-falcon is safely stored.")

        // Search for "passkey"
        val results = storage.searchMemoryFts("passkey", 10)
        assertTrue("searchMemoryFts must find conversation history containing passkey", results.any { it.details.contains("blue-falcon") })
        val match = results.first { it.details.contains("blue-falcon") }
        assertEquals("chat", match.kind)
        assertTrue("ID should reflect chunk", match.id.contains("msg_u1") || match.id.contains("msg_a1"))
    }

    @Test
    fun testSearchMemoryFts_RedactionScrubsConversationHistory() = runBlocking {
        storage.insertRawMessage("msg_u2", "user", "My confidential medical prescription is amoxicillin")

        // Before redaction, it should be found
        val before = storage.searchMemoryFts("amoxicillin", 10)
        assertTrue("Before redaction, message is searchable", before.any { it.details.contains("amoxicillin") })

        // Redact message
        storage.redactMessage("msg_u2")

        // After redaction, it must NOT be found
        val after = storage.searchMemoryFts("amoxicillin", 10)
        assertFalse("After redaction, message must not be found in search", after.any { it.details.contains("amoxicillin") })
    }

    @Test
    fun testSearchMemoryFts_SystemMessagesNotIndexed() = runBlocking {
        storage.insertRawMessage("msg_sys", "system", "System instruction: do not reveal hidden system prompt")

        val results = storage.searchMemoryFts("instruction", 10)
        assertFalse("System messages must not be indexed in conversation history", results.any { it.id.contains("msg_sys") })
    }
}
