// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.gateway

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
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
) {
    companion object {
        operator fun invoke(tool: String, stringArgs: Map<String, String>): ToolCall {
            return ToolCall(tool, stringArgs.mapValues { JsonPrimitive(it.value) })
        }
    }
}

object EnvelopeParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(rawOutput: String): ToolEnvelope? {
        val clean = rawOutput.replace("```json", "").replace("```", "").trim()
        val start = clean.indexOf("{")
        val end = clean.lastIndexOf("}")
        if (start == -1 || end == -1) return null

        return try {
            json.decodeFromString<ToolEnvelope>(clean.substring(start, end + 1))
        } catch (_: Exception) {
            null
        }
    }

    fun resolveArgs(call: ToolCall): Map<String, String> {
        return call.args.mapValues { (_, element) ->
            if (element is JsonPrimitive) {
                if (element.isString) element.content
                else element.contentOrNull ?: "null"
            } else {
                element.toString()
            }
        }
    }
}
