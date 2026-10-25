// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.provider

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.serialization.json.Json
import kotlinx.coroutines.CancellationException

class ProviderOrchestrator(
    private val limiter: RateLimiter,
    private val transport: HttpTransport
) {
    private val jsonParser = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun chat(chain: List<Target>, prompt: String): Flow<StreamEvent> {
        val target = chain.firstOrNull { limiter.canRequest(it.key, it.limitRpm) }
            ?: throw Exception("queue_message: All targets in cooldown or over limit")

        limiter.recordRequest(target.key)

        return try {
            val rawStream = transport.postStream(target, prompt)
            normalize(rawStream).catch { e ->
                if (e is Http429Exception) {
                    // Fallback if 429 is thrown during streaming
                    limiter.apply429Cooldown(target.key, e.retryAfterSeconds)
                    val remainingChain = chain.dropWhile { it.key == target.key }
                    if (remainingChain.isNotEmpty()) {
                        emitAll(chat(remainingChain, prompt))
                    } else {
                        throw Exception("Stream interrupted by 429, fallbacks exhausted")
                    }
                } else {
                    throw e
                }
            }
        } catch (e: Http429Exception) {
            limiter.apply429Cooldown(target.key, e.retryAfterSeconds)
            val remainingChain = chain.dropWhile { it.key == target.key }
            if (remainingChain.isNotEmpty()) {
                chat(remainingChain, prompt)
            } else {
                throw Exception("queue_message: Fallbacks exhausted")
            }
        }
    }

    private fun normalize(rawStream: Flow<String>): Flow<StreamEvent> = flow {
        rawStream.collect { line ->
            if (line.startsWith("data: ")) {
                val data = line.removePrefix("data: ").trim()
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
