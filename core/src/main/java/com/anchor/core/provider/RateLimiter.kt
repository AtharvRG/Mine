// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.provider

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class RateLimiter(private val clock: () -> Long) {
    private val keyTimestamps = mutableMapOf<String, MutableList<Long>>()
    private val keyCooldowns = mutableMapOf<String, Long>()
    private val mutex = Mutex()

    suspend fun canRequest(key: String, limit: Int): Boolean = mutex.withLock {
        val now = clock()
        if (keyCooldowns.getOrDefault(key, 0L) > now) return false
        val stamps = keyTimestamps.getOrPut(key) { mutableListOf() }
        stamps.removeAll { now - it > 60_000 }
        return stamps.size < minOf(limit, 50)
    }

    suspend fun recordRequest(key: String) = mutex.withLock {
        keyTimestamps.getOrPut(key) { mutableListOf() }.add(clock())
    }

    suspend fun apply429Cooldown(key: String, retryAfterSeconds: Int? = null) = mutex.withLock {
        val requestedMin = (retryAfterSeconds ?: 0) / 60
        val cooldownMin = maxOf(15, requestedMin)
        keyCooldowns[key] = clock() + (cooldownMin * 60 * 1000L)
    }

    suspend fun getCooldownExpiry(key: String): Long? = mutex.withLock {
        val expiry = keyCooldowns[key]
        return if (expiry != null && expiry > clock()) expiry else null
    }
}
