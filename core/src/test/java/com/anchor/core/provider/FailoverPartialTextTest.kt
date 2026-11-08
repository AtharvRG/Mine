// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.provider

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FailoverPartialTextTest {

    private val chain = listOf(
        Target("t_primary", "key_A", 20),
        Target("t_fallback", "key_B", 20)
    )

    @Test
    fun testFailoverAfterPartialText_DoesNotEmitClear() = runBlocking {
        // H-12 (R-16): a provider that streamed half an answer and then failed emitted Clear,
        // and every Clear consumer (ChatController, QuoteManager) wipes its buffer — the user
        // watched an answer they had already read disappear. A failed attempt is not a
        // correction of the previous one; the fallback's text must be appended, not restart
        // the bubble.
        val transport = object : HttpTransport {
            override suspend fun postStream(target: Target, body: String): Flow<String> = flow {
                if (target.id == "t_primary") {
                    emit("""data: {"choices":[{"delta":{"content":"Partial answer"}}]}""")
                    throw Http429Exception(null)
                }
                emit("""data: {"choices":[{"delta":{"content":" and the rest"}}]}""")
            }
        }
        val events = ProviderOrchestrator(RateLimiter { 1000L }, transport).chat(chain, "hi").toList()

        assertFalse(
            "Text was already streamed; a Clear here erases it: $events",
            events.any { it is StreamEvent.Clear }
        )
        val text = events.filterIsInstance<StreamEvent.TextDelta>().joinToString("") { it.text }
        assertEquals("Partial answer and the rest", text)
    }

    @Test
    fun testFailoverBeforeAnyText_StillEmitsClear() = runBlocking {
        // The Clear is what erases a stale partial from an earlier *turn* or a placeholder
        // bubble. When the failing attempt streamed nothing there is nothing to keep, so the
        // Clear must survive — this test pins that we narrowed the condition, not removed it.
        val transport = object : HttpTransport {
            override suspend fun postStream(target: Target, body: String): Flow<String> = flow {
                if (target.id == "t_primary") throw Http429Exception(null)
                emit("""data: {"choices":[{"delta":{"content":"fallback only"}}]}""")
            }
        }
        val events = ProviderOrchestrator(RateLimiter { 1000L }, transport).chat(chain, "hi").toList()

        assertTrue("Nothing was streamed, so Clear is still correct: $events", events.any { it is StreamEvent.Clear })
        val text = events.filterIsInstance<StreamEvent.TextDelta>().joinToString("") { it.text }
        assertEquals("fallback only", text)
    }
}
