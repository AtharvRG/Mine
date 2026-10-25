// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.provider

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RateLimiterTest {
    @Test
    fun testLimiterMath() {
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
}
