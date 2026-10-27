// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine Author
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.provider

import kotlinx.coroutines.flow.Flow

data class Target(
    val id: String,
    val key: String,
    val limitRpm: Int
)

interface HttpTransport {
    suspend fun postStream(target: Target, body: String): Flow<String>
}

sealed interface StreamEvent {
    data class TextDelta(val text: String) : StreamEvent
    data class ToolCallStart(val index: Int, val name: String) : StreamEvent
    data class Finish(val reason: String) : StreamEvent
    data object Clear : StreamEvent
}

class Http429Exception(val retryAfterSeconds: Int? = null) : Exception("Rate limited")