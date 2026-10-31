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
    private val transport: HttpTransport
) {
    private val jsonParser = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun chat(chain: List<Target>, prompt: String): Flow<StreamEvent> = flow {
        var lastError: Exception? = null
        
        for (target in chain) {
            if (!limiter.canRequest(target.key, target.limitRpm)) continue
            
            try {
                limiter.recordRequest(target.key)
                var emittedAnything = false
                
                // B13: Catch 5xx, Timeouts, and empty 200s, then fallback to next target!
                normalize(transport.postStream(target, prompt)).collect { event ->
                    emit(event)
                    emittedAnything = true
                }
                
                if (!emittedAnything) throw Exception("Empty response from provider")
                
                return@flow // Success! Turn completes.
            } catch (e: Http429Exception) {
                limiter.apply429Cooldown(target.key, e.retryAfterSeconds)
                lastError = e
                emit(StreamEvent.Clear)
                continue // Try next fallback target
            } catch (e: Exception) {
                if (e is CancellationException) throw e // Never swallow coroutine cancellation
                lastError = e
                emit(StreamEvent.Clear)
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
