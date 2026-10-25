// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine Author
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.storage

import kotlinx.coroutines.CoroutineDispatcher

/**
 * The single storage interface exposed to the core module.
 * Implementations must run disk operations on [ioDispatcher].
 */
interface Storage {
    val ioDispatcher: CoroutineDispatcher

    // Stub for Step 1. We will expand this massively in Step 2 for the Gateway.
    suspend fun insertRawMessage(id: String, content: String)
    suspend fun getRawMessage(id: String): String?
}