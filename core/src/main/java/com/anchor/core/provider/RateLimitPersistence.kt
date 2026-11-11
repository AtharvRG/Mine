// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.provider

import com.anchor.core.storage.Storage

interface RateLimitPersistence {
    suspend fun getCooldown(key: String): Long?
    suspend fun saveCooldown(key: String, expiry: Long)
}

class StorageRateLimitPersistence(
    private val storage: Storage
) : RateLimitPersistence {

    override suspend fun getCooldown(key: String): Long? {
        val hash = key.hashCode().toString()
        return storage.getSetting("rate_cooldown_$hash")?.toLongOrNull()
    }

    override suspend fun saveCooldown(key: String, expiry: Long) {
        val hash = key.hashCode().toString()
        storage.saveSetting("rate_cooldown_$hash", expiry.toString())
    }
}
