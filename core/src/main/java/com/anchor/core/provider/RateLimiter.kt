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
        /** No single key is ever pushed past this many requests a minute, whatever it is configured to. */
        const val HARD_RPM_CAP = 50
    }

    /** One key's state for [KeyPool]: requests in the last minute, last use, and whether it is benched. */
    data class KeyUsage(val inWindow: Int, val lastUsedAt: Long, val cooling: Boolean)

    suspend fun usage(key: String): KeyUsage = mutex.withLock {
        val now = clock()
        val cooldown = keyCooldowns[key] ?: (persistence?.getCooldown(key) ?: 0L).also { keyCooldowns[key] = it }
        val nowMono = monotonicClock()
        val stamps = keyTimestamps[key]
        stamps?.removeAll { nowMono - it > 60_000 || nowMono < it }
        KeyUsage(
            inWindow = stamps?.size ?: 0,
            lastUsedAt = stamps?.lastOrNull() ?: Long.MIN_VALUE,
            cooling = cooldown > now && cooldown <= now + MAX_COOLDOWN_MS,
        )
    }

    private val keyTimestamps = mutableMapOf<String, MutableList<Long>>()
    private val keyCooldowns = mutableMapOf<String, Long>()
    private val strikes = mutableMapOf<String, Int>()
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
        return stamps.size < minOf(limit, HARD_RPM_CAP)
    }

    suspend fun recordRequest(key: String) = mutex.withLock {
        keyTimestamps.getOrPut(key) { mutableListOf() }.add(monotonicClock())
    }

    suspend fun apply429Cooldown(key: String, retryAfterSeconds: Int? = null, defaultCooldownMin: Int? = null) = mutex.withLock {
        val n = (strikes[key] ?: 0) + 1
        strikes[key] = n
        val backoffMs = if (retryAfterSeconds != null && retryAfterSeconds > 0) {
            retryAfterSeconds * 1000L
        } else if (defaultCooldownMin != null) {
            defaultCooldownMin * 60_000L
        } else {
            2_000L * (1L shl (n - 1).coerceAtMost(9))
        }
        val maxCap = maxOf(15 * 60_000L, if (defaultCooldownMin != null) defaultCooldownMin * 60_000L else 15 * 60_000L).coerceAtMost(MAX_COOLDOWN_MS)
        val expiry = clock() + backoffMs.coerceIn(1_000L, maxCap)
        keyCooldowns[key] = expiry
        try {
            persistence?.saveCooldown(key, expiry)
        } catch (_: Exception) {}
    }

    suspend fun recordSuccess(key: String) = mutex.withLock {
        strikes.remove(key)
    }

    suspend fun clearCooldown(key: String) = mutex.withLock {
        keyCooldowns[key] = 0L
        strikes.remove(key)
        try {
            persistence?.saveCooldown(key, 0L)
        } catch (_: Exception) {}
    }

    suspend fun getCooldownExpiry(key: String): Long? = mutex.withLock {
        val now = clock()
        val expiry = keyCooldowns[key] ?: (persistence?.getCooldown(key)?.also { keyCooldowns[key] = it } ?: 0L.also { keyCooldowns[key] = 0L })
        return if (expiry > now && expiry <= now + MAX_COOLDOWN_MS) expiry else null
    }
}
