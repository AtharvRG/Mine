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
        limiter.apply429Cooldown("key2", null, defaultCooldownMin = 15)
        assertFalse(limiter.canRequest("key2", 20))
        clock += (14 * 60 * 1000L) // 14 mins
        assertFalse(limiter.canRequest("key2", 20))
        clock += (2 * 60 * 1000L)  // 16 mins
        assertTrue("Should recover after 15m", limiter.canRequest("key2", 20))
    }

    @Test
    fun testLimiter_RetryAfterSecondsAndExponentialBackoff() = runBlocking {
        var clock = 1000L
        val limiter = RateLimiter { clock }

        // Test explicit retryAfterSeconds = 3
        limiter.apply429Cooldown("keyExplicit", retryAfterSeconds = 3)
        assertFalse(limiter.canRequest("keyExplicit", 20))
        clock += 2_000L // 2s passed
        assertFalse(limiter.canRequest("keyExplicit", 20))
        clock += 2_000L // 4s total passed
        assertTrue("Should recover after 3s", limiter.canRequest("keyExplicit", 20))

        // Test exponential backoff: strike 1 = 2s
        limiter.apply429Cooldown("keyExp", retryAfterSeconds = null)
        assertFalse(limiter.canRequest("keyExp", 20))
        clock += 1_000L
        assertFalse(limiter.canRequest("keyExp", 20))
        clock += 2_000L
        assertTrue("Should recover after 2s for strike 1", limiter.canRequest("keyExp", 20))

        // Strike 2 = 4s
        limiter.apply429Cooldown("keyExp", retryAfterSeconds = null)
        clock += 3_000L
        assertFalse("Should still be in cooldown at 3s for strike 2", limiter.canRequest("keyExp", 20))
        clock += 2_000L
        assertTrue("Should recover after 4s for strike 2", limiter.canRequest("keyExp", 20))

        // Clear cooldown
        limiter.apply429Cooldown("keyExp", retryAfterSeconds = null)
        assertFalse(limiter.canRequest("keyExp", 20))
        limiter.clearCooldown("keyExp")
        assertTrue("Should be immediately clear after clearCooldown", limiter.canRequest("keyExp", 20))

        // Strike count reset on recordSuccess
        limiter.apply429Cooldown("keyExp", retryAfterSeconds = null) // strike 1 (was cleared)
        limiter.recordSuccess("keyExp")
        limiter.clearCooldown("keyExp")
        limiter.apply429Cooldown("keyExp", retryAfterSeconds = null) // should start back at strike 1 (2s)
        clock += 3_000L
        assertTrue("Should be strike 1 (2s) after recordSuccess reset", limiter.canRequest("keyExp", 20))
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
