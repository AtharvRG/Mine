// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.provider

/**
 * Several API keys for one provider, used together so their per-key limits add up
 * ("gatling gun"): with NVIDIA NIM's free tier at 40 requests a minute per key, ten keys
 * give up to 400.
 *
 * How a request picks its key:
 *  1. Keys benched after a 429 are skipped until their bench ends.
 *  2. Each key keeps a 60-second sliding window of its own requests ([RateLimiter]); a key whose
 *     window is full is skipped, so no key is pushed past its limit.
 *  3. Of the keys left, the one with the fewest requests in the window goes first; ties go to
 *     the key used least recently. Load spreads evenly instead of draining key 1 first.
 *
 * The keys become an ordered chain of [Target]s, so the orchestrator's existing failover does
 * the rest: on a 429 it benches that key and moves straight to the next one in the same turn.
 */
object KeyPool {
    const val MAX_KEYS = 10

    /** A key that hits 429 with no Retry-After sits out a full window; the next key takes over. */
    const val BENCH_SECONDS_ON_429 = 60

    /** Cleans what the user pasted: trims, drops blanks and duplicates, caps at [MAX_KEYS]. */
    fun sanitize(keys: List<String>): List<String> =
        keys.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(MAX_KEYS)

    /** Stored as one setting, one key per line (keys never contain line breaks). */
    fun encode(keys: List<String>): String = sanitize(keys).joinToString("\n")
    fun decode(stored: String?): List<String> = sanitize(stored.orEmpty().lines())

    /**
     * Orders [keys] for the next request. Keys that can't take a request right now are dropped,
     * unless every key is unavailable — then they all stay, in the same order, so the
     * orchestrator still reports one clear "rate limited" instead of an empty chain.
     */
    suspend fun order(keys: List<String>, limiter: RateLimiter, rpmPerKey: Int, limiterPrefix: String = ""): List<String> {
        val clean = sanitize(keys)
        if (clean.size <= 1) return clean
        val usage = clean.associateWith { limiter.usage(limiterPrefix + it) }
        val available = clean.filter { k -> usage.getValue(k).let { !it.cooling && it.inWindow < minOf(rpmPerKey, RateLimiter.HARD_RPM_CAP) } }
        val pool = available.ifEmpty { clean }
        return pool.sortedWith(
            compareBy<String>({ usage.getValue(it).inWindow }, { usage.getValue(it).lastUsedAt }, { clean.indexOf(it) })
        )
    }

    /**
     * The chain of targets for one provider reached with [keys]. Each key is its own limiter
     * bucket ([limiterPrefix] separates background work from chat when wanted).
     */
    suspend fun targets(
        endpoint: String,
        keys: List<String>,
        model: String?,
        rpmPerKey: Int,
        limiter: RateLimiter,
        limiterPrefix: String = "",
    ): List<Target> = order(keys, limiter, rpmPerKey, limiterPrefix).map { k ->
        Target(
            id = endpoint,
            key = k,
            limitRpm = rpmPerKey,
            model = model?.ifBlank { null },
            limiterKey = limiterPrefix + k,
            benchSecondsOn429 = if (keys.size > 1) BENCH_SECONDS_ON_429 else null,
        )
    }

    /** "nvapi-…a1b2" for showing a key without revealing it. */
    fun mask(key: String): String {
        val k = key.trim()
        if (k.length <= 8) return "••••"
        return k.take(6) + "…" + k.takeLast(4)
    }
}
