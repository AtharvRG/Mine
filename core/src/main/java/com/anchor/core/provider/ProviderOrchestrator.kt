// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine Author
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.provider

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

interface HttpTransport {
    suspend fun postStream(target: Target, body: String): Flow<String>
}

class ProviderOrchestrator(
    private val limiter: RateLimiter,
    private val transport: HttpTransport
) {
    /**
     * Attempts the request against the chain of targets.
     * Falls back automatically on 429s or limits.
     */
    suspend fun chat(chain: List<Target>, prompt: String): Flow<StreamEvent> {
        val target = chain.firstOrNull { limiter.canRequest(it.key, it.limitRpm) }
            ?: throw Exception("queue_message: All targets in cooldown or over limit")

        limiter.recordRequest(target.key)

        return try {
            val rawStream = transport.postStream(target, prompt)
            normalize(rawStream)
        } catch (e: Http429Exception) {
            limiter.apply429Cooldown(target.key, e.retryAfterSeconds)

            // Recurse to the fallback chain, skipping the bad key
            val remainingChain = chain.dropWhile { it.key == target.key }
            if (remainingChain.isNotEmpty()) {
                chat(remainingChain, prompt)
            } else {
                throw Exception("queue_message: Fallbacks exhausted")
            }
        }
    }

    /**
     * Very basic SSE normalizer for testing without external JSON libs.
     * Extracts OpenAI-compatible text deltas.
     */
    private fun normalize(rawStream: Flow<String>): Flow<StreamEvent> = flow {
        rawStream.collect { line ->
            if (line.startsWith("data: ")) {
                val data = line.removePrefix("data: ").trim()
                if (data == "[DONE]") {
                    emit(StreamEvent.Finish("stop"))
                } else if (data.contains("\"content\":\"")) {
                    // Quick regex-free extraction for the test
                    val content = data.substringAfter("\"content\":\"").substringBefore("\"")
                    emit(StreamEvent.TextDelta(content))
                }
            }
        }
    }
}