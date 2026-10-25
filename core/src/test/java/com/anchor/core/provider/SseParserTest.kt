// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.provider

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SseParserTest {
    @Test
    fun testSseSerializationParsing() = runBlocking {
        val limiter = RateLimiter { 1000L }
        val target = Target("url", "key", 20)
        
        val transport = object : HttpTransport {
            override suspend fun postStream(target: Target, body: String): Flow<String> = flow {
                emit("data: {\"choices\":[{\"delta\":{\"content\":\"Hello \"}}]}")
                emit("data: {\"choices\":[{\"delta\":{\"content\":\"\\u00A9 \"}}]}") // Unicode
                emit("data: {\"choices\":[{\"delta\":{\"content\":\"\\nworld\"}}]}") // Escaped newline
                emit("data: {\"choices\":[{\"delta\":{}}]}") // Empty delta (should be ignored)
                emit("data: [DONE]")
            }
        }
        
        val orchestrator = ProviderOrchestrator(limiter, transport)
        val results = orchestrator.chat(listOf(target), "test").toList()

        val text = results.filterIsInstance<StreamEvent.TextDelta>().joinToString("") { it.text }
        assertEquals("Hello © \nworld", text)
        assertTrue("Should contain Finish event", results.last() is StreamEvent.Finish)
    }
}
