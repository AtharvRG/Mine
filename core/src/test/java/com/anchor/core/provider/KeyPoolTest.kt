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

class KeyPoolTest {

    private var now = 1_000_000L
    private val limiter = RateLimiter({ now })

    private suspend fun use(key: String, times: Int = 1) = repeat(times) { limiter.recordRequest(key) }

    @Test fun sanitizeTrimsDedupesAndCapsAtTen() {
        val keys = (1..14).map { " k$it " } + listOf("", "k1")
        val clean = KeyPool.sanitize(keys)
        assertEquals(10, clean.size)
        assertEquals("k1", clean.first())
        assertEquals(KeyPool.sanitize(keys), KeyPool.decode(KeyPool.encode(keys)))
    }

    @Test fun leastUsedKeyGoesFirst() = runBlocking {
        use("a", 5); use("b", 1); use("c", 3)
        assertEquals(listOf("b", "c", "a"), KeyPool.order(listOf("a", "b", "c"), limiter, rpmPerKey = 40))
    }

    @Test fun tiesGoToTheLeastRecentlyUsed() = runBlocking {
        use("a"); now += 10; use("b"); now += 10; use("c")
        assertEquals(listOf("a", "b", "c"), KeyPool.order(listOf("c", "b", "a"), limiter, rpmPerKey = 40))
    }

    @Test fun fullWindowAndBenchedKeysAreSkippedUntilTheyRecover() = runBlocking {
        use("a", 40)                       // a's minute is full
        limiter.apply429Cooldown("b", null, null)
        limiter.apply429Cooldown("b", KeyPool.BENCH_SECONDS_ON_429)
        assertEquals(listOf("c"), KeyPool.order(listOf("a", "b", "c"), limiter, rpmPerKey = 40))

        now += 61_000                      // the window slides and the bench ends
        val after = KeyPool.order(listOf("a", "b", "c"), limiter, rpmPerKey = 40)
        assertEquals(setOf("a", "b", "c"), after.toSet())
    }

    @Test fun whenEveryKeyIsOutTheyAllStaySoTheTurnReportsRateLimited() = runBlocking {
        use("a", 40); use("b", 40)
        assertEquals(listOf("a", "b"), KeyPool.order(listOf("a", "b"), limiter, rpmPerKey = 40))
    }

    @Test fun a429MovesTheSameTurnToTheNextKeyAndBenchesTheFirst() = runBlocking {
        val seen = mutableListOf<String>()
        val transport = object : HttpTransport {
            override suspend fun postStream(target: Target, body: String): Flow<String> = flow {
                seen += target.key
                if (target.key == "k1") throw Http429Exception(retryAfterSeconds = null)
                emit("data: {\"choices\":[{\"delta\":{\"content\":\"hi\"}}]}")
                emit("data: [DONE]")
            }
        }
        val orchestrator = ProviderOrchestrator(limiter, transport)
        val targets = KeyPool.targets("https://integrate.api.nvidia.com/v1/chat/completions", listOf("k1", "k2", "k3"), "m", 40, limiter)
        val events = orchestrator.chat(targets, """{"messages":[]}""").toList()

        assertEquals(listOf("k1", "k2"), seen)
        assertTrue("the turn completed on k2", events.any { it is StreamEvent.Finish })
        // k1 sits out a full minute; the next turn starts on k3 (least used), never k1.
        assertTrue(limiter.usage("k1").cooling)
        val next = KeyPool.order(listOf("k1", "k2", "k3"), limiter, 40)
        assertEquals("k3", next.first())
        assertFalse("k1" in next)
        now += 59_000
        assertTrue(limiter.usage("k1").cooling)
        now += 2_000
        assertFalse(limiter.usage("k1").cooling)
    }

    @Test fun maskNeverShowsTheMiddle() {
        assertEquals("nvapi-…wxyz", KeyPool.mask("nvapi-abcdefghijklmnopqrstuvwxyz"))
        assertEquals("••••", KeyPool.mask("short"))
    }

    @Test fun targetToStringRedactsTheKey() {
        val t = Target("https://x", "nvapi-secret", 40)
        assertFalse(t.toString().contains("secret"))
    }
}
