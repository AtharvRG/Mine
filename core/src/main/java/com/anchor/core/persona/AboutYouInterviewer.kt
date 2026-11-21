// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.persona

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * The little interview chat in About you: an isolated conversation (no web, no voice, no tools,
 * its own history) whose only job is getting to know the user. The model asks one question at a
 * time and may *propose* facts it learned; the app stores those through the gateway's
 * `soul_propose`, so the usual review (or auto-accept) rules apply. The model never writes Soul.
 *
 * When answers go short or a topic has run its course, it moves on to a new topic itself; the
 * user can also ask it to "cook" a question when they don't know what to talk about.
 */
object AboutYouInterviewer {

    data class Turn(val role: String, val text: String)
    data class FactProposal(val statement: String, val category: String, val confidence: Double)
    data class Reply(val text: String, val facts: List<FactProposal>)

    val TOPICS = listOf(
        "work or studies", "a normal day", "the people who matter to them", "what they do for fun",
        "goals for this year", "what stresses them out", "food, sleep and exercise habits",
        "how they like to be helped or reminded", "places they love", "where they grew up",
        "music, books and shows", "a perfect weekend", "something they're proud of", "what they're learning",
    )

    private val LOCAL_QUESTIONS = mapOf(
        "work or studies" to "What do you spend most of your weekdays on — work, studies, something else?",
        "a normal day" to "Walk me through a normal day for you. When does it really start?",
        "the people who matter to them" to "Who are the people you talk to most? Anyone I should know by name?",
        "what they do for fun" to "When you have a free evening, what do you actually end up doing?",
        "goals for this year" to "What's one thing you'd love to have done by the end of this year?",
        "what stresses them out" to "What tends to stress you out the most lately?",
        "food, sleep and exercise habits" to "How are your sleep and food routines — anything you're trying to change?",
        "how they like to be helped or reminded" to "When you're putting something off, what actually gets you moving: a nudge, a deadline, or being left alone?",
        "places they love" to "Is there a place — a café, a park, a city — where you feel most like yourself?",
        "where they grew up" to "Where did you grow up, and what do you miss about it?",
        "music, books and shows" to "What have you been listening to or watching on repeat?",
        "a perfect weekend" to "Describe a perfect weekend for you.",
        "something they're proud of" to "What's something you did recently that you're quietly proud of?",
        "what they're learning" to "Are you learning anything new right now?",
    )

    val CATEGORIES = setOf("identity", "routine", "health", "reply_style", "dislikes", "goals", "preferences", "people", "other")

    /** The first question, asked locally with no model call. */
    fun opening(name: String?, knownFacts: Int): String {
        val hi = name?.trim()?.takeIf { it.isNotEmpty() }?.let { "Hey $it! " } ?: "Hey! "
        return if (knownFacts < 3) "${hi}I'd like to get to know you a little. What do you do with most of your days?"
        else "${hi}I know a few things about you already — let's fill in the gaps. What's been on your mind this week?"
    }

    /** Short answers twice in a row, or six exchanges without a topic change: time to move on. */
    fun isStale(history: List<Turn>): Boolean {
        val user = history.filter { it.role == "user" }
        if (user.size >= 2 && user.takeLast(2).all { it.text.trim().split(Regex("\\s+")).size <= 4 }) return true
        return user.size >= 6 && user.size % 6 == 0
    }

    /** A topic not yet covered in this conversation, chosen by how many user turns there have been. */
    fun nextTopic(history: List<Turn>, avoid: Set<String> = emptySet()): String {
        val asked = history.filter { it.role == "assistant" }.joinToString(" ").lowercase()
        val fresh = TOPICS.filter { t -> t !in avoid && t.split(' ').none { w -> w.length > 4 && asked.contains(w) } }
        val pool = fresh.ifEmpty { TOPICS }
        return pool[history.count { it.role == "user" } % pool.size]
    }

    /** "Cook a question" without a model (offline, or no provider): a ready question on a new topic. */
    fun localQuestion(history: List<Turn>): String = LOCAL_QUESTIONS.getValue(nextTopic(history))

    fun systemPrompt(name: String?, knownFacts: List<String>): String = buildString {
        appendLine("You are Mine, getting to know ${name?.takeIf { it.isNotBlank() } ?: "the user"} in a relaxed one-to-one chat.")
        appendLine("Your only goal here is to learn about them: ask ONE short, warm, specific question at a time, react briefly to what they said first (one sentence at most), and follow interesting threads.")
        appendLine("Never ask about things already known (below). Never give advice, lists, or long replies. No web, no tools, no reminders in this chat.")
        appendLine("Do not ask for passwords, ID numbers, bank details, or exact addresses. Health, money and relationships only if they bring it up.")
        appendLine("When you learn a lasting fact about them, add it to \"facts\" as a short third-person statement (\"Works night shifts at a hospital.\").")
        appendLine("Only facts they actually stated. Categories: ${CATEGORIES.joinToString()}.")
        appendLine("Reply ONLY with JSON: {\"reply\":\"…\",\"facts\":[{\"statement\":\"…\",\"category\":\"…\",\"confidence\":0.9}]}")
        if (knownFacts.isNotEmpty()) {
            appendLine()
            appendLine("Already known (do not ask again):")
            knownFacts.take(40).forEach { appendLine("- ${it.replace("\n", " ").take(160)}") }
        }
    }

    /** Chat-completions body for one interviewer turn. [cookTopic] asks for a fresh question on that topic. */
    fun requestBody(name: String?, knownFacts: List<String>, history: List<Turn>, cookTopic: String?): String = buildJsonObject {
        putJsonArray("messages") {
            addJsonObject { put("role", "system"); put("content", systemPrompt(name, knownFacts)) }
            history.takeLast(20).forEach { t -> addJsonObject { put("role", if (t.role == "user") "user" else "assistant"); put("content", t.text) } }
            if (cookTopic != null) {
                addJsonObject {
                    put("role", "system")
                    put("content", "The user wants a new topic. Ask one fresh, easy question about $cookTopic. No facts unless stated.")
                }
            }
        }
        put("stream", true)
        put("temperature", 0.8)
    }.toString()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Reads the model's envelope. Plain text (a model that ignored the format) becomes the reply with no facts. */
    fun parse(raw: String): Reply {
        val t = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val start = t.indexOf('{')
        val end = t.lastIndexOf('}')
        val obj = if (start >= 0 && end > start) runCatching { json.parseToJsonElement(t.substring(start, end + 1)).jsonObject }.getOrNull() else null
        if (obj == null) return Reply(t.take(600), emptyList())
        val reply = (obj["reply"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        val facts = (obj["facts"] as? JsonArray).orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val st = (o["statement"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.length in 3..300 } ?: return@mapNotNull null
            val cat = (o["category"] as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase()?.takeIf { it in CATEGORIES } ?: "other"
            val conf = (o["confidence"] as? JsonPrimitive)?.doubleOrNull?.coerceIn(0.0, 1.0) ?: 0.7
            FactProposal(st, cat, conf)
        }.take(5)
        return Reply(reply.ifBlank { "Tell me more?" }.take(600), facts)
    }

    /** Persisted history (last 30 turns) so leaving the screen doesn't lose the conversation. */
    fun encodeHistory(history: List<Turn>): String = buildJsonObject {
        putJsonArray("t") { history.takeLast(30).forEach { add(buildJsonObject { put("r", it.role); put("x", it.text.take(1000)) }) } }
    }.toString()

    fun decodeHistory(s: String?): List<Turn> {
        if (s.isNullOrBlank()) return emptyList()
        val arr = runCatching { json.parseToJsonElement(s).jsonObject["t"] as? JsonArray }.getOrNull() ?: return emptyList()
        return arr.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val r = (o["r"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val x = (o["x"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            Turn(r, x)
        }
    }

    const val KEY_HISTORY = "profile.interview"
}
