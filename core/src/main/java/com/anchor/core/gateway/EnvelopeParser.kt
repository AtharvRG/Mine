// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.gateway

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class ToolEnvelope(
    val reply: String = "",
    val calls: List<ToolCall> = emptyList()
)

@Serializable
data class ToolCall(
    val tool: String,
    val args: Map<String, String> = emptyMap()
)

object EnvelopeParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(rawOutput: String): ToolEnvelope? {
        val clean = rawOutput.replace("```json", "").replace("```", "").trim()
        val start = clean.indexOf("{")
        val end = clean.lastIndexOf("}")
        if (start == -1 || end == -1) return null

        return try {
            json.decodeFromString<ToolEnvelope>(clean.substring(start, end + 1))
        } catch (e: Exception) {
            null
        }
    }
}