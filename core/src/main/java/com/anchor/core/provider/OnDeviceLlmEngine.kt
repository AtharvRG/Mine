// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.provider

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Interface for on-device local LLM execution (v4 / P-010).
 * Invoked as the final fallback chain entry when cloud providers fail or when offline.
 */
interface OnDeviceLlmEngine {
    suspend fun generateStream(prompt: String): Flow<StreamEvent>
}

/**
 * Pure Kotlin rule-based local intent and execution engine.
 * Serves as a zero-dependency on-device fallback when heavy model weights are missing or offline.
 */
class RuleBasedLocalLlmEngine(
    private val clock: () -> Long = { System.currentTimeMillis() }
) : OnDeviceLlmEngine {

    private val jsonParser = Json { ignoreUnknownKeys = true; isLenient = true }

    override suspend fun generateStream(prompt: String): Flow<StreamEvent> = flow {
        val userTurn = extractUserUtterance(prompt)
        val systemFeedback = extractSystemFeedback(prompt)

        val jsonResponse = when {
            systemFeedback != null -> handleSystemFeedback(systemFeedback)
            userTurn.isBlank() -> buildSimpleReply("I am ready to assist you offline.")
            else -> evaluateIntent(userTurn)
        }

        // Emit response incrementally to match streaming provider contract
        val chunkSize = 32
        var i = 0
        while (i < jsonResponse.length) {
            val end = (i + chunkSize).coerceAtMost(jsonResponse.length)
            emit(StreamEvent.TextDelta(jsonResponse.substring(i, end)))
            i = end
        }
        emit(StreamEvent.Finish("stop"))
    }

    private fun extractUserUtterance(prompt: String): String {
        return try {
            val root = jsonParser.parseToJsonElement(prompt).jsonObject
            val msgs = root["messages"]?.jsonArray
            val lastUserMsg = msgs?.reversed()?.firstOrNull { elem ->
                elem.jsonObject["role"]?.jsonPrimitive?.content == "user"
            }
            val content = lastUserMsg?.jsonObject["content"]?.jsonPrimitive?.content ?: ""
            // Strip out System feedback if present in multi-step loops
            if (content.contains("System feedback:")) {
                ""
            } else {
                content.trim()
            }
        } catch (_: Exception) {
            prompt.trim()
        }
    }

    private fun extractSystemFeedback(prompt: String): String? {
        return try {
            val root = jsonParser.parseToJsonElement(prompt).jsonObject
            val msgs = root["messages"]?.jsonArray
            val lastUserMsg = msgs?.reversed()?.firstOrNull { elem ->
                elem.jsonObject["role"]?.jsonPrimitive?.content == "user"
            }
            val content = lastUserMsg?.jsonObject["content"]?.jsonPrimitive?.content ?: ""
            if (content.contains("System feedback:")) {
                content.substringAfter("System feedback:").trim()
            } else {
                null
            }
        } catch (_: Exception) {
            if (prompt.contains("System feedback:")) prompt.substringAfter("System feedback:").trim() else null
        }
    }

    private fun evaluateIntent(utterance: String): String {
        val lower = utterance.lowercase().trim()

        // 1. Remind me in X minutes/seconds/hours to Y
        val remindInPattern = Regex("""remind me in (\d+)\s*(min|minute|sec|second|hour|h|m|s)s?\s*(?:to|for)?\s*(.+)""", RegexOption.IGNORE_CASE)
        val matchIn = remindInPattern.find(utterance)
        if (matchIn != null) {
            val amount = matchIn.groupValues[1].toLongOrNull() ?: 1L
            val unit = matchIn.groupValues[2].lowercase()
            val title = matchIn.groupValues[3].trim().removePrefix("to ").trim()
            val fullUnit = normalizeUnit(amount, unit)
            val dueText = "in $amount $fullUnit"
            return buildProposeChangeJson(title, dueText, "I've scheduled an offline reminder for: \"$title\".")
        }

        // 2. Remind me to Y in X minutes/seconds/hours
        val remindToPattern = Regex("""remind me to (.+?)\s+in\s+(\d+)\s*(min|minute|sec|second|hour|h|m|s)s?""", RegexOption.IGNORE_CASE)
        val matchTo = remindToPattern.find(utterance)
        if (matchTo != null) {
            val title = matchTo.groupValues[1].trim()
            val amount = matchTo.groupValues[2].toLongOrNull() ?: 1L
            val unit = matchTo.groupValues[3].lowercase()
            val fullUnit = normalizeUnit(amount, unit)
            val dueText = "in $amount $fullUnit"
            return buildProposeChangeJson(title, dueText, "I've scheduled an offline reminder for: \"$title\".")
        }

        // 3. Add task / buy / schedule generic
        val taskPattern = Regex("""^(?:add task|todo|remember to|buy|schedule)\s+(.+)""", RegexOption.IGNORE_CASE)
        val taskMatch = taskPattern.find(utterance)
        if (taskMatch != null) {
            val title = taskMatch.groupValues[1].trim()
            return buildProposeChangeJson(title, null, "I've created an offline task for: \"$title\".")
        }

        // 4. Search memory / query
        val searchPattern = Regex("""^(?:what is|what's|find|search for|search|where is|tell me about)\s+(.+)""", RegexOption.IGNORE_CASE)
        val searchMatch = searchPattern.find(utterance)
        if (searchMatch != null) {
            val query = searchMatch.groupValues[1].trim().removeSuffix("?").trim()
            return buildSearchMemoryJson(query, "Searching offline memory for \"$query\"...")
        }

        // 5. Daily agenda / schedule check
        if (lower.contains("schedule") || lower.contains("agenda") || lower.contains("what do i have")) {
            return buildSearchMemoryJson("today", "Checking your offline schedule...")
        }

        // 6. Conversational offline response
        return buildSimpleReply("I am currently in on-device offline mode. You can ask me to set reminders, create tasks, or search your local notes and memory.")
    }

    private fun normalizeUnit(amount: Long, unit: String): String {
        return when {
            unit.startsWith("s") -> if (amount == 1L) "second" else "seconds"
            unit.startsWith("h") -> if (amount == 1L) "hour" else "hours"
            else -> if (amount == 1L) "minute" else "minutes"
        }
    }

    private fun handleSystemFeedback(feedback: String): String {
        val clean = feedback.replace("\"", "\\\"").replace("\n", " ").trim()
        return """{"reply": "Offline query results: $clean", "calls": []}"""
    }

    private fun buildProposeChangeJson(title: String, dueText: String?, reply: String): String {
        val safeTitle = title.replace("\"", "\\\"")
        val dueTextField = if (!dueText.isNullOrBlank()) """, "due_text": "$dueText"""" else ""
        return """{"reply": "$reply", "calls": [{"tool": "propose_change", "args": {"intent": "CREATE", "title": "$safeTitle"$dueTextField}}]}"""
    }

    private fun buildSearchMemoryJson(query: String, reply: String): String {
        val safeQuery = query.replace("\"", "\\\"")
        return """{"reply": "$reply", "calls": [{"tool": "search_memory", "args": {"query": "$safeQuery"}}]}"""
    }

    private fun buildSimpleReply(reply: String): String {
        val safe = reply.replace("\"", "\\\"")
        return """{"reply": "$safe", "calls": []}"""
    }
}
