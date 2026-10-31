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
        val start = clean.indexOf("{")
        val end = clean.lastIndexOf("}")
        if (start == -1 || end == -1) return null

        return try {
            val parsed = json.decodeFromString<ToolEnvelope>(clean.substring(start, end + 1))
            // B8: Reject empty JSON objects like {"foo":1}
            if (parsed.reply.isBlank() && parsed.calls.isEmpty()) null else parsed
        } catch (_: Exception) {
            null
        }
    }

    fun resolveArgs(call: ToolCall): Map<String, String> {
        val result = mutableMapOf<String, String>()
        call.args.forEach { (k, element) ->
            if (element is JsonNull) return@forEach
            
            val strVal = if (element is JsonPrimitive) {
                if (element.isString) element.content else element.contentOrNull
            } else {
                element.toString()
            }
            
            if (strVal != null) {
                val normalized = strVal.trim().lowercase()
                if (normalized !in listOf("null", "none", "n/a", "")) {
                    result[k] = strVal
                }
            }
        }
        return result
    }
}
