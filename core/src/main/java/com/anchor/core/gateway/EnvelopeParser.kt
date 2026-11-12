// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.gateway

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

@Serializable
data class ToolEnvelope(
    val reply: String = "",
    val calls: List<ToolCall> = emptyList()
)

@Serializable
data class ToolCall(
    val tool: String,
    val args: Map<String, JsonElement> = emptyMap()
)

object EnvelopeParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(rawOutput: String): ToolEnvelope? {
        val clean = rawOutput.replace("```json", "").replace("```", "").trim()
        val end = clean.lastIndexOf("}")

        if (end != -1) {
            // 1. Try standard envelope parse from the first '{'
            val firstStart = clean.indexOf("{")
            if (firstStart != -1 && firstStart < end) {
                try {
                    val parsed = json.decodeFromString<ToolEnvelope>(clean.substring(firstStart, end + 1))
                    if (parsed.reply.isNotBlank() || parsed.calls.isNotEmpty()) return parsed
                } catch (_: Exception) {}
            }

            // 2. If failover concatenated a fresh JSON envelope after an interrupted one, search backwards for '{'
            if (firstStart != -1) {
                var candidateStart = clean.lastIndexOf("{", end - 1)
                while (candidateStart > firstStart) {
                    try {
                        val parsed = json.decodeFromString<ToolEnvelope>(clean.substring(candidateStart, end + 1))
                        if (parsed.reply.isNotBlank() || parsed.calls.isNotEmpty()) return parsed
                    } catch (_: Exception) {}
                    candidateStart = clean.lastIndexOf("{", candidateStart - 1)
                }
            }
        }

        // 3. Fallback: Lenient extraction for truncated or unescaped model outputs
        val replyKey = Regex(""""reply"\s*:\s*"""").find(clean)
        val extractedReply = if (replyKey != null) {
            val textStart = replyKey.range.last + 1
            val callsIdx = clean.indexOf("\"calls\"", textStart)
            val replySub = if (callsIdx != -1) {
                val lastQuoteBeforeCalls = clean.lastIndexOf("\"", callsIdx - 1)
                if (lastQuoteBeforeCalls > textStart) {
                    clean.substring(textStart, lastQuoteBeforeCalls)
                } else {
                    clean.substring(textStart, callsIdx).trim().removeSuffix(",").removeSuffix("\"")
                }
            } else {
                val lastQuote = clean.lastIndexOf("\"")
                if (lastQuote > textStart) {
                    clean.substring(textStart, lastQuote)
                } else {
                    clean.substring(textStart)
                }
            }
            replySub.replace("\\\"", "\"").replace("\\n", "\n").replace("\\\\", "\\").trim()
        } else null

        val extractedCalls = run {
            val callsIdx = clean.indexOf("\"calls\"")
            if (callsIdx != -1) {
                val bracketStart = clean.indexOf('[', callsIdx)
                if (bracketStart != -1) {
                    var depth = 0
                    var inString = false
                    var escape = false
                    var bracketEnd = -1
                    for (i in bracketStart until clean.length) {
                        val c = clean[i]
                        if (escape) {
                            escape = false
                            continue
                        }
                        if (c == '\\') {
                            escape = true
                            continue
                        }
                        if (c == '"') {
                            inString = !inString
                            continue
                        }
                        if (!inString) {
                            if (c == '[') depth++
                            else if (c == ']') {
                                depth--
                                if (depth == 0) {
                                    bracketEnd = i
                                    break
                                }
                            }
                        }
                    }
                    if (bracketEnd != -1) {
                        try {
                            json.decodeFromString<List<ToolCall>>(clean.substring(bracketStart, bracketEnd + 1))
                        } catch (_: Exception) {
                            emptyList()
                        }
                    } else emptyList()
                } else emptyList()
            } else emptyList()
        }

        if (!extractedReply.isNullOrBlank() || extractedCalls.isNotEmpty()) {
            return ToolEnvelope(reply = extractedReply ?: "", calls = extractedCalls)
        }

        return null
    }

    /**
     * Placeholder noise the model emits instead of omitting an optional argument. "none" is
     * deliberately absent: it is a plausible user-supplied name ("None" for a shopping list item).
     */
    private fun isPlaceholder(value: String): Boolean =
        value.trim().lowercase() in listOf("null", "n/a", "")

    fun resolveArgs(call: ToolCall): Map<String, String> {
        val result = mutableMapOf<String, String>()
        call.args.forEach { (k, element) ->
            if (element is JsonNull) return@forEach

            val strVal = if (element is JsonPrimitive) {
                if (element.isString) element.content else element.contentOrNull
            } else {
                element.toString()
            }

            if (strVal != null && !isPlaceholder(strVal)) {
                result[k] = strVal
            }
        }
        return result
    }

    /**
     * Stable identity of a call for duplicate detection within one envelope. Built from the
     * *unresolved* arguments in a deterministic key order, not from [resolveArgs]: resolution drops
     * placeholders, so `{"title":"n/a"}` and `{"title":null}` would otherwise collide and the
     * second, legitimate call would be rejected as a duplicate.
     */
    fun dedupeKey(call: ToolCall): String {
        val args = call.args.entries
            .filter { it.value !is JsonNull }
            .sortedBy { it.key }
            .joinToString("&") { (k, v) -> "$k=$v" }
        return "${call.tool}|$args"
    }
}
