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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderTroubleTest {
    @Test
    fun testEagerAndLazy429() = runBlocking {
        val limiter = RateLimiter { 1000L }
        val chain = listOf(
            Target("t_primary", "key_A", 20),
            Target("t_fallback", "key_B", 20)
        )

        // 1. Eager Throw (Throws before stream is returned)
        val eagerTransport = object : HttpTransport {
            override suspend fun postStream(target: Target, body: String): Flow<String> {
                if (target.id == "t_primary") throw Http429Exception(null)
                return flow { emit("data: {\"choices\":[{\"delta\":{\"content\":\"eager_success\"}}]}") }
            }
        }
        val eagerResults = ProviderOrchestrator(limiter, eagerTransport).chat(chain, "hi").toList()
        assertTrue(eagerResults.isNotEmpty())
        assertNotNull(limiter.getCooldownExpiry("key_A"))

        // Reset limiter
        val limiter2 = RateLimiter { 1000L }
        
        // 2. Lazy Throw (Throws during flow collection)
        val lazyTransport = object : HttpTransport {
            override suspend fun postStream(target: Target, body: String): Flow<String> = flow {
                if (target.id == "t_primary") {
                    emit("data: {\"choices\":[{\"delta\":{\"content\":\"partial...\"}}]}")
                    throw Http429Exception(1800) // 30 min Retry-After
                }
                emit("data: {\"choices\":[{\"delta\":{\"content\":\"lazy_success\"}}]}")
            }
        }
        val lazyResults = ProviderOrchestrator(limiter2, lazyTransport).chat(chain, "hi").toList()
        
        // E2: The fallback must follow the partial text. H-12 (R-16) narrowed the old
        // "Clear was emitted mid-stream" assertion: Clear wipes the consumer's buffer, so it is
        // only correct when the failed attempt streamed no text. Here it streamed "partial...",
        // so the events must be exactly that text followed by the fallback's text — no Clear.
        assertFalse(
            "A failover after streamed text must not clear it: $lazyResults",
            lazyResults.any { it is StreamEvent.Clear }
        )
        val text = lazyResults.filterIsInstance<StreamEvent.TextDelta>().joinToString("") { it.text }
        assertEquals("partial...lazy_success", text)
        
        val expiry = limiter2.getCooldownExpiry("key_A")
        assertNotNull(expiry)
    }
}
