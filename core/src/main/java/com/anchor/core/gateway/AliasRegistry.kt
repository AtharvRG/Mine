// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.gateway

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Thread-safe bidirectional mapping between internal database UUIDs and
 * short, sequential per-turn aliases (e.g. n_1, s_1, m_1, p_1).
 *
 * Invariant: Raw database UUIDs are NEVER leaked to LLM prompts, tool results,
 * or network requests. The model only ever observes and returns aliases.
 */
class AliasRegistry(
    private val maxCapacity: Int = 500
) {
    private val aliasToId = ConcurrentHashMap<String, String>()
    private val idToAlias = ConcurrentHashMap<String, String>()
    private val counters = ConcurrentHashMap<String, AtomicInteger>()

    fun aliasFor(prefix: String, targetId: String): String {
        idToAlias[targetId]?.let { return it }

        if (aliasToId.size >= maxCapacity) {
            clear()
        }

        val counter = counters.computeIfAbsent(prefix) { AtomicInteger(0) }.incrementAndGet()
        val alias = "${prefix}_$counter"
        aliasToId[alias] = targetId
        idToAlias[targetId] = alias
        return alias
    }

    fun resolve(alias: String): String? {
        return aliasToId[alias]
    }

    fun resolveRef(ref: String, expectedPrefix: String): String {
        // Direct lookup by alias
        aliasToId[ref]?.let { return it }

        // If it starts with prefix_ but not in map
        if (ref.startsWith("${expectedPrefix}_")) {
            return aliasToId[ref] ?: ref
        }

        return ref
    }

    fun clear() {
        aliasToId.clear()
        idToAlias.clear()
        counters.clear()
    }
}
