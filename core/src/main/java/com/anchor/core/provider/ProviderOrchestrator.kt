// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.provider

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.coroutines.CancellationException

class ProviderOrchestrator(
    private val limiter: RateLimiter,
    private val transport: HttpTransport,
    private val onDeviceEngine: OnDeviceLlmEngine = RuleBasedLocalLlmEngine()
) {
    private val jsonParser = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun chat(chain: List<Target>, prompt: String): Flow<StreamEvent> = flow {
        var lastError: Exception? = null
        // Declared outside the try: the catch clauses decide whether to emit Clear based on it.
        var emittedText = false

        for ((index, target) in chain.withIndex()) {
            if (!limiter.canRequest(target.key, target.limitRpm)) continue

            try {
                limiter.recordRequest(target.key)
                var emittedAnything = false
                emittedText = false

                if (target.id.startsWith("local://")) {
                    onDeviceEngine.generateStream(prompt).collect { event ->
                        emit(event)
                        emittedAnything = true
                        if (event is StreamEvent.TextDelta) emittedText = true
                    }
                } else {
                    // B13: Catch 5xx, Timeouts, and empty 200s, then fallback to next target!
                    normalize(transport.postStream(target, prompt)).collect { event ->
                        emit(event)
                        emittedAnything = true
                        if (event is StreamEvent.TextDelta) emittedText = true
                    }
                }

                if (!emittedAnything) throw Exception("Empty response from provider")

                return@flow // Success! Turn completes.
            } catch (e: Http429Exception) {
                limiter.apply429Cooldown(target.key, e.retryAfterSeconds)
                lastError = e
                // H-12 (R-16): Clear wipes the consumer's buffer. Only emit it when this attempt
                // streamed no text — otherwise the user watches an answer they already read
                // vanish and restart. A failed attempt is not a correction of the previous one.
                if (!emittedText) emit(StreamEvent.Clear)
                continue // Try next fallback target
            } catch (e: OfflineException) {
                // Blueprint 41 §2 no_network: check if an on-device local target is remaining in the chain
                val hasLocalRemaining = chain.drop(index + 1).any { it.id.startsWith("local://") }
                if (hasLocalRemaining) {
                    lastError = e
                    if (!emittedText) emit(StreamEvent.Clear)
                    continue // Fall through to on-device LLM target!
                }
                // No local target available: rethrow so the controller queues on offline signal
                throw e
            } catch (e: Exception) {
                if (e is CancellationException) throw e // Never swallow coroutine cancellation
                lastError = e
                if (!emittedText) emit(StreamEvent.Clear)
                continue // 5xx, timeout, or parsing error -> Try next fallback target!
            }
        }
        
        throw Exception("All providers failed or in cooldown. Last error: ${lastError?.message ?: "Unknown"}")
    }

    private fun normalize(rawStream: Flow<String>): Flow<StreamEvent> = flow {
        rawStream.collect { line ->
            // B13: Accepts with or without space
            if (line.startsWith("data:")) {
                val data = line.substring(5).trim()
                if (data == "[DONE]") {
                    emit(StreamEvent.Finish("stop"))
                } else if (data.isNotEmpty()) {
                    try {
                        val chunk = jsonParser.decodeFromString<ChatChunk>(data)
                        val choice = chunk.choices?.firstOrNull()
                        if (choice?.finish_reason != null) emit(StreamEvent.Finish(choice.finish_reason))
                        if (choice?.delta?.content != null) emit(StreamEvent.TextDelta(choice.delta.content))
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        // Ignore malformed chunks
                    }
                }
            }
        }
    }
}
