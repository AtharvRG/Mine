// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.provider

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RateLimiterTest {
    @Test
    fun testLimiterMath() = runBlocking {
        var clock = 1000L
        val limiter = RateLimiter { clock }

        // Test 50 RPM ceiling (Even if limit is 100, blocks at 50)
        for (i in 1..50) {
            assertTrue(limiter.canRequest("key1", 100))
            limiter.recordRequest("key1")
        }
        assertFalse("Should hit hard ceiling of 50", limiter.canRequest("key1", 100))

        // Move clock forward 61 seconds (Window clears)
        clock += 61_000L
        assertTrue("Window should clear", limiter.canRequest("key1", 100))

        // Test Default Cooldown (15 min)
        limiter.apply429Cooldown("key2", null)
        assertFalse(limiter.canRequest("key2", 20))
        clock += (14 * 60 * 1000L) // 14 mins
        assertFalse(limiter.canRequest("key2", 20))
        clock += (2 * 60 * 1000L)  // 16 mins
        assertTrue("Should recover after 15m", limiter.canRequest("key2", 20))
    }

    @Test
    fun testLimiter_ClockJumpBackwards_DoesNotBlockRequestsBeyondSlidingWindow() = runBlocking {
        var wallClock = 1_000_000L
        var monoClock = 50_000L

        val limiter = RateLimiter(
            clock = { wallClock },
            persistence = null,
            monotonicClock = { monoClock }
        )

        // Exhaust quota (50 requests)
        for (i in 1..50) {
            assertTrue(limiter.canRequest("keyA", 50))
            limiter.recordRequest("keyA")
        }
        assertFalse(limiter.canRequest("keyA", 50))

        // Wall clock jumps BACKWARDS by 10 minutes, but real elapsed time advances 61 seconds
        wallClock -= 600_000L
        monoClock += 61_000L

        // Monotonic sliding window must evict old stamps and allow requests
        assertTrue(
            "Limiter must allow requests when monotonic window passes even if wall clock jumps backwards",
            limiter.canRequest("keyA", 50)
        )
    }

    @Test
    fun testLimiter_SanitizesUnreasonableFutureCooldown() = runBlocking {
        var wallClock = 1_000_000L
        val storage = com.anchor.core.SharedFakeStorage()
        val persistence = StorageRateLimitPersistence(storage)

        // Simulate corrupted or clock-skewed timestamp 1 year in the future
        val runawayFuture = wallClock + 365L * 24 * 3600 * 1000L
        storage.saveSetting("rate_cooldown_${ "key_skew".hashCode() }", runawayFuture.toString())

        val limiter = RateLimiter(
            clock = { wallClock },
            persistence = persistence
        )

        assertTrue(
            "Limiter must allow request when persisted cooldown is absurdly far in the future",
            limiter.canRequest("key_skew", 20)
        )
        org.junit.Assert.assertNull(limiter.getCooldownExpiry("key_skew"))
    }
}
