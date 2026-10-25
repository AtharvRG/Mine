// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine Author
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.provider

class RateLimiter(private val clock: () -> Long) {

    // In a real app, this state would be persisted to storage
    private val keyTimestamps = mutableMapOf<String, MutableList<Long>>()
    private val keyCooldowns = mutableMapOf<String, Long>()

    fun canRequest(key: String, limit: Int): Boolean {
        val now = clock()

        // 1. Check Cooldown
        if (keyCooldowns.getOrDefault(key, 0L) > now) {
            return false
        }

        // 2. Sliding Window (Last 60 seconds)
        val stamps = keyTimestamps.getOrPut(key) { mutableListOf() }
        stamps.removeAll { now - it > 60_000 }

        // Blueprint 40 (3.5): Hard ceiling of 50 RPM per model/key
        val hardCeiling = minOf(limit, 50)

        return stamps.size < hardCeiling
    }

    fun recordRequest(key: String) {
        keyTimestamps.getOrPut(key) { mutableListOf() }.add(clock())
    }

    fun apply429Cooldown(key: String, retryAfterSeconds: Int? = null) {
        // Blueprint 40 (3.6): Max of (15 mins, Retry-After)
        val requestedMin = (retryAfterSeconds ?: 0) / 60
        val cooldownMin = maxOf(15, requestedMin)
        keyCooldowns[key] = clock() + (cooldownMin * 60 * 1000L)
    }

    fun getCooldownExpiry(key: String): Long? {
        val expiry = keyCooldowns[key]
        return if (expiry != null && expiry > clock()) expiry else null
    }
}