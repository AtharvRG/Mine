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
