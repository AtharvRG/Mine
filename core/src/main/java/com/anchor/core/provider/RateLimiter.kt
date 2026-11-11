// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.provider

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class RateLimiter(
    private val clock: () -> Long,
    private val persistence: RateLimitPersistence? = null,
    private val monotonicClock: () -> Long = clock
) {
    constructor(clock: () -> Long) : this(
        clock = clock,
        persistence = null,
        monotonicClock = clock
    )

    constructor(clock: () -> Long, persistence: RateLimitPersistence?) : this(
        clock = clock,
        persistence = persistence,
        monotonicClock = clock
    )

    companion object {
        const val MAX_COOLDOWN_MS = 3600_000L // 1 hour max reasonable cooldown
    }

    private val keyTimestamps = mutableMapOf<String, MutableList<Long>>()
    private val keyCooldowns = mutableMapOf<String, Long>()
    private val mutex = Mutex()

    suspend fun canRequest(key: String, limit: Int): Boolean = mutex.withLock {
        val now = clock()
        val inMemory = keyCooldowns[key]
        val cooldown = if (inMemory != null) inMemory else {
            val persisted = persistence?.getCooldown(key) ?: 0L
            keyCooldowns[key] = persisted
            persisted
        }
        if (cooldown > now + MAX_COOLDOWN_MS) {
            keyCooldowns[key] = 0L
            try { persistence?.saveCooldown(key, 0L) } catch (_: Exception) {}
        } else if (cooldown > now) {
            return false
        }

        val nowMono = monotonicClock()
        val stamps = keyTimestamps.getOrPut(key) { mutableListOf() }
        stamps.removeAll { nowMono - it > 60_000 || nowMono < it }
        return stamps.size < minOf(limit, 50)
    }

    suspend fun recordRequest(key: String) = mutex.withLock {
        keyTimestamps.getOrPut(key) { mutableListOf() }.add(monotonicClock())
    }

    suspend fun apply429Cooldown(key: String, retryAfterSeconds: Int? = null) = mutex.withLock {
        val requestedMin = (retryAfterSeconds ?: 0) / 60
        val cooldownMin = maxOf(15, minOf(requestedMin, 60)) // at least 15 min, at most 60 min
        val expiry = clock() + (cooldownMin * 60 * 1000L)
        keyCooldowns[key] = expiry
        try {
            persistence?.saveCooldown(key, expiry)
        } catch (_: Exception) {}
    }

    suspend fun getCooldownExpiry(key: String): Long? = mutex.withLock {
        val now = clock()
        val expiry = keyCooldowns[key] ?: (persistence?.getCooldown(key)?.also { keyCooldowns[key] = it } ?: 0L.also { keyCooldowns[key] = 0L })
        return if (expiry > now && expiry <= now + MAX_COOLDOWN_MS) expiry else null
    }
}
