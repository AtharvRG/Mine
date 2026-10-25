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
    suspend fun chat(chain: List<Target>, prompt: String): Flow<StreamEvent> {
        val target = chain.firstOrNull { limiter.canRequest(it.key, it.limitRpm) }
            ?: throw Exception("queue_message: All targets in cooldown or over limit")

        limiter.recordRequest(target.key)

        return try {
            val rawStream = transport.postStream(target, prompt)
            normalize(rawStream)
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
                } else {
                    val contentTag = "\"content\":\""
                    val startIdx = data.indexOf(contentTag)
                    if (startIdx != -1) {
                        val valStart = startIdx + contentTag.length
                        var valEnd = valStart
                        // Walk forward until we find an UNESCAPED quote
                        while (valEnd < data.length) {
                            if (data[valEnd] == '"' && data[valEnd - 1] != '\\') {
                                break
                            }
                            valEnd++
                        }
                        if (valEnd <= data.length) {
                            val rawContent = data.substring(valStart, valEnd)
                            // Unescape standard JSON formatting
                            val content = rawContent
                                .replace("\\\"", "\"")
                                .replace("\\n", "\n")
                                .replace("\\\\", "\\")
                            emit(StreamEvent.TextDelta(content))
                        }
                    }
                }
            }
        }
    }
}
