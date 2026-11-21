// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.ui

import com.anchor.core.provider.AllProvidersFailedException
import com.anchor.core.provider.Http429Exception
import com.anchor.core.provider.HttpStatusException
import com.anchor.core.provider.OfflineException
import com.anchor.core.provider.ProviderTimeoutException

/**
 * Everything the chat path needs so a turn never ends in silence or a stack-trace string.
 *
 * Mine is used as much for "hey, rough day" as for "move dinner to 8". Whatever happens on the
 * wire, the user must get *something* sensible back: a real answer, a short local reply, or a
 * plain-language explanation with a way forward. Nothing here touches storage; it is pure and
 * pinned by `ChatResilienceTest`.
 */
object ChatResilience {

    /** How a failure is explained to the user. */
    data class Friendly(
        val message: String,
        /** Worth retrying as-is (network blips, 5xx, timeouts, rate limits). */
        val retryable: Boolean,
        /** Fixing it needs the user to change provider settings (bad key, unknown model). */
        val needsSettings: Boolean = false,
    )

    fun describe(e: Throwable): Friendly {
        val root = unwrap(e)
        return when (root) {
            is OfflineException -> Friendly("You're offline. I saved your message and will answer as soon as you're back.", retryable = true)
            is Http429Exception -> Friendly(
                if (root.retryAfterSeconds != null && root.retryAfterSeconds > 0)
                    "Your AI provider is rate-limiting requests. Trying again in ${formatSeconds(root.retryAfterSeconds)}."
                else "Your AI provider is rate-limiting requests. I'll retry shortly.",
                retryable = true,
            )
            is ProviderTimeoutException -> Friendly("Your AI provider took too long to answer. Tap retry, or try again in a moment.", retryable = true)
            is HttpStatusException -> when {
                root.code == 401 || root.code == 403 -> Friendly("Your API key was rejected. Check it in Settings → AI provider.", retryable = false, needsSettings = true)
                root.code == 404 -> Friendly("Your provider doesn't recognise that model or address. Check Settings → AI provider.", retryable = false, needsSettings = true)
                root.code == 402 -> Friendly("Your AI provider account is out of credit.", retryable = false, needsSettings = true)
                isContextOverflow(root) -> Friendly("That conversation got too long for the model. I trimmed it — please send again.", retryable = true)
                root.code == 400 || root.code == 422 -> Friendly("Your provider refused the request (${root.code}). Try a different model in Settings if this keeps happening.", retryable = false, needsSettings = true)
                root.code == 408 || root.code == 425 -> Friendly("Your AI provider timed out. Tap retry.", retryable = true)
                root.code >= 500 -> Friendly("Your AI provider is having trouble right now (${root.code}). I'll keep your message and retry.", retryable = true)
                else -> Friendly("Your AI provider returned an error (${root.code}).", retryable = root.isTransient)
            }
            is java.net.UnknownHostException -> Friendly("Can't reach your AI provider. Check your connection or the address in Settings.", retryable = true)
            is java.io.IOException -> Friendly("The connection dropped mid-reply. Tap retry.", retryable = true)
            else -> {
                val m = root.message.orEmpty()
                if (m.contains("Empty response", ignoreCase = true)) Friendly("The model sent back an empty reply. Tap retry.", retryable = true)
                else Friendly("Something went wrong while answering. Tap retry.", retryable = true)
            }
        }
    }

    /** The provider error that actually happened, beneath the orchestrator's wrapper. */
    fun unwrap(e: Throwable): Throwable {
        var cur: Throwable = e
        repeat(4) {
            val next = (cur as? AllProvidersFailedException)?.cause ?: return cur
            cur = next
        }
        return cur
    }

    private val OVERFLOW_HINTS = listOf(
        "context length", "context_length", "maximum context", "too many tokens", "token limit",
        "tokens exceed", "reduce the length", "too long", "max_tokens", "prompt is too long", "input is too long",
    )

    /** A 400/413 that means "the prompt is too big", which a shorter history fixes. */
    fun isContextOverflow(e: Throwable): Boolean {
        val root = unwrap(e) as? HttpStatusException ?: return false
        if (root.code == 413) return true
        if (root.code != 400 && root.code != 422) return false
        val body = root.bodySnippet.lowercase()
        return OVERFLOW_HINTS.any { it in body }
    }

    private fun formatSeconds(s: Int): String = when {
        s < 60 -> "$s s"
        s < 3600 -> "${(s + 59) / 60} min"
        else -> "${(s + 3599) / 3600} h"
    }

    /**
     * Caps one message for the model. Huge pastes keep their head and tail (where the question
     * usually is) with a marker in between, so a 200 KB paste cannot make the request fail.
     */
    fun capForModel(text: String, maxChars: Int = 24_000): String {
        if (text.length <= maxChars) return text
        val head = (maxChars * 0.7).toInt()
        val tail = maxChars - head - 64
        val omitted = text.length - head - tail
        return text.substring(0, head) + "\n\n[… $omitted characters omitted …]\n\n" + text.substring(text.length - tail)
    }

    // ---------------------------------------------------------------------------------------
    // Local small talk
    // ---------------------------------------------------------------------------------------

    enum class SmallTalkKind { GREETING, MORNING, NIGHT, THANKS, HOW_ARE_YOU, BYE, ACK, LAUGH, WHO_ARE_YOU }

    private val PATTERNS: List<Pair<SmallTalkKind, Regex>> = listOf(
        SmallTalkKind.MORNING to Regex("(?i)^(?:good\\s*morning|gm|morning|suprabhat)\\b.*"),
        SmallTalkKind.NIGHT to Regex("(?i)^(?:good\\s*night|gn|night|nighty|shubh\\s*ratri|going to (?:bed|sleep)|sleeping now)\\b.*"),
        SmallTalkKind.HOW_ARE_YOU to Regex("(?i)^(?:hi|hey|hello|yo)?[,!\\s]*(?:how\\s*(?:are|r)\\s*(?:you|u|ya)|how'?s it going|what'?s up|wassup|sup|kaise\\s*ho|kya\\s*haal|kaisa\\s*hai|how have you been)\\b.*"),
        SmallTalkKind.THANKS to Regex("(?i)^(?:thanks?|thank\\s*you|thx|ty|tysm|shukriya|dhanyavaad|dhanyavad|thanks a lot|much appreciated)\\b.*"),
        SmallTalkKind.BYE to Regex("(?i)^(?:bye|goodbye|see\\s*(?:you|ya)|cya|ttyl|talk\\s*later|later|alvida|chalo bye)\\b.*"),
        SmallTalkKind.WHO_ARE_YOU to Regex("(?i)^(?:who\\s*are\\s*you|what\\s*are\\s*you|what\\s*can\\s*you\\s*do|tum\\s*kaun\\s*ho)\\b.*"),
        SmallTalkKind.GREETING to Regex("(?i)^(?:hi+|hey+|hello+|hii+|heya|hola|namaste|namaskar|yo|sup|good\\s*(?:afternoon|evening))\\b.*"),
        SmallTalkKind.LAUGH to Regex("(?i)^(?:lol+|lmao+|haha+|hehe+|rofl|😂|🤣)+.*"),
        SmallTalkKind.ACK to Regex("(?i)^(?:ok(?:ay)?|k+|cool|nice|great|alright|got it|sure|fine|theek\\s*hai|accha|achha|hmm+)\\b.*"),
    )

    /**
     * Recognises a short social message. Long or task-like messages are never small talk, so
     * "hey remind me to call mom at 6" still goes to the model or the offline parser.
     */
    fun classifySmallTalk(text: String): SmallTalkKind? {
        val t = text.trim().trimEnd('!', '.', '?', ' ', '~').trim()
        if (t.isEmpty() || t.length > 48) return null
        if (t.split(Regex("\\s+")).size > 7) return null
        if (TASKY.containsMatchIn(t)) return null
        return PATTERNS.firstOrNull { it.second.matches(t) }?.first
    }

    private val TASKY = Regex("(?i)\\b(?:remind|alarm|reminder|schedule|cancel|move|reschedule|add|task|todo|at \\d|tomorrow|tonight|in \\d+)\\b")

    /**
     * A warm, short reply for [kind] that does not pretend to be the model. [connected] says
     * whether an AI provider is configured; when it is not, the reply says what still works.
     */
    fun smallTalkReply(kind: SmallTalkKind, userName: String?, connected: Boolean, hourOfDay: Int): String {
        val name = userName?.trim()?.takeIf { it.isNotEmpty() && it.length <= 24 }
        val hi = if (name != null) "Hey $name" else "Hey"
        val base = when (kind) {
            SmallTalkKind.GREETING -> "$hi! I'm here."
            SmallTalkKind.MORNING -> if (hourOfDay in 4..11) "Good morning${name?.let { ", $it" } ?: ""}! Hope you slept well." else "Morning${name?.let { ", $it" } ?: ""}! Well, afternoon by my clock, but I'll take it."
            SmallTalkKind.NIGHT -> "Good night${name?.let { ", $it" } ?: ""}. Sleep well. Your reminders are still set."
            SmallTalkKind.THANKS -> "Anytime."
            SmallTalkKind.HOW_ARE_YOU -> "I'm good, thanks for asking. How are you doing?"
            SmallTalkKind.BYE -> "Talk soon${name?.let { ", $it" } ?: ""}."
            SmallTalkKind.ACK -> "👍"
            SmallTalkKind.LAUGH -> "😄"
            SmallTalkKind.WHO_ARE_YOU -> "I'm Mine, your personal assistant. I keep track of your tasks, reminders, notes and what matters to you, and I'm happy to just talk too."
        }
        if (connected) return base
        val needsHint = kind in setOf(SmallTalkKind.GREETING, SmallTalkKind.HOW_ARE_YOU, SmallTalkKind.WHO_ARE_YOU, SmallTalkKind.MORNING)
        return if (needsHint) "$base I can't chat properly until an AI provider is connected in Settings, but reminders and quick tasks like \"gym at 7\" work right now." else base
    }

    // ---------------------------------------------------------------------------------------
    // Reply fallbacks
    // ---------------------------------------------------------------------------------------

    /** Shown when the model kept calling tools and never wrote an answer. */
    const val LOOP_LIMIT_REPLY = "I dug around but couldn't put together a clear answer. Could you tell me a bit more about what you need?"

    /** Shown when the model returned nothing twice in a row. */
    const val EMPTY_REPLY = "Sorry, I lost my train of thought there. Could you say that again?"

    /** System feedback that asks the model to answer after an empty reply. */
    const val EMPTY_REPLY_FEEDBACK = "System feedback: your last reply was empty. Answer the user's latest message now, in natural language, inside \"reply\". Use no tools unless the user asked for an action."

    /** Progress-only lines a model writes before a tool call; never a final answer. */
    private val PROGRESS_ONLY = Regex("(?i)^(?:let me|i'?ll|i will|one moment|hold on|checking|searching|looking)\\b.{0,60}(?:\\.\\.\\.|…)?$")

    fun isProgressOnly(reply: String): Boolean {
        val t = reply.trim()
        return t.isEmpty() || (t.length <= 80 && PROGRESS_ONLY.matches(t))
    }
}
