// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.provider

import kotlinx.coroutines.flow.Flow

data class Target(
    val id: String,
    val key: String,
    val limitRpm: Int,
    val model: String? = null,
    val limiterKey: String = key
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

class HttpStatusException(val code: Int, val bodySnippet: String) : Exception("HTTP $code: $bodySnippet") {
    val isTransient: Boolean get() = code == 408 || code == 425 || code == 429 || code >= 500
}

class ProviderTimeoutException(message: String = "The provider did not respond in time", cause: Throwable? = null) : Exception(message, cause)

class AllProvidersFailedException(cause: Throwable?) : Exception("All providers failed or in cooldown. Last error: ${cause?.message ?: "Unknown"}", cause)

/**
 * Blueprint 41 §2 `no_network`: the request never reached a provider because this device has no
 * usable connection. Distinct from every other transport failure on purpose — a 5xx is the
 * provider's problem and the next target in the chain may still answer, but no target can answer
 * without a network. The orchestrator rethrows this instead of falling through to the next target,
 * and the controller queues the turn instead of reporting an error (audit §6 item 6).
 */
class OfflineException(message: String = "No network connection", cause: Throwable? = null) : Exception(message, cause)
