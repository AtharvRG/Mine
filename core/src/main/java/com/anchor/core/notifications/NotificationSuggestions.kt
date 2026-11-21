// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.notifications

/**
 * Spots the few notifications that carry something the user will want to act on — "call me at
 * 6", "standup moved to 11", "rent is due on the 5th" — and turns them into a suggestion.
 *
 * Suggestions are never committed silently. They land in the ambient review list, exactly like
 * a commitment overheard in conversation, and become tasks only when the user accepts them.
 * Rules are deliberately conservative: a missed suggestion costs nothing, a wrong one is noise.
 */
object NotificationSuggestions {

    private const val MONTHS = "jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|jun(?:e)?|jul(?:y)?|aug(?:ust)?|sep(?:t(?:ember)?)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?"
    private const val DAYS = "mon(?:day)?|tue(?:s(?:day)?)?|wed(?:nesday)?|thu(?:rs(?:day)?)?|fri(?:day)?|sat(?:urday)?|sun(?:day)?"

    /** One time-ish token. Several adjacent tokens ("tomorrow at 5pm") are merged by [timeSpan]. */
    private val TIME_TOKEN = Regex(
        "(?i)\\b(?:" +
            "today|tonight|tomorrow|tmrw|tmr|day after tomorrow|" +
            "(?:this|next)\\s+(?:morning|afternoon|evening|week|month|$DAYS)|" +
            "(?:$DAYS)\\b|" +
            "\\d{1,2}(?::\\d{2})?\\s*(?:am|pm|a\\.m\\.|p\\.m\\.)|" +
            "\\d{1,2}:\\d{2}|noon|midnight|" +
            "(?:at|by|around|before|after|till|until)\\s+\\d{1,2}(?::\\d{2})?\\s*(?:am|pm|a\\.m\\.|p\\.m\\.)|" +
            "(?:at|by|around|before|after|till|until)\\s+\\d{1,2}(?::\\d{2})?(?!\\s*(?:%|rs|inr|usd|\\$|₹|k\\b|mins?|minutes?|hours?|hrs?|days?))(?![\\d:])|" +
            "in\\s+\\d{1,3}\\s*(?:mins?|minutes?|hours?|hrs?)|" +
            "\\d{1,2}(?:st|nd|rd|th)?\\s+(?:$MONTHS)\\b|" +
            "(?:$MONTHS)\\s+\\d{1,2}(?:st|nd|rd|th)?\\b|" +
            "the\\s+\\d{1,2}(?:st|nd|rd|th)\\b" +
            ")"
    )

    private val CALL_ME = Regex("(?i)\\b(?:call|ring|phone)\\s+me(?:\\s+back)?\\b")

    private val REQUEST = Regex(
        "(?i)\\b(?:can you|could you|can u|pls|plz|please|don'?t forget to|remember to|remind me to|make sure (?:you |to )|need you to|you (?:have|need) to)\\s+" +
            "([^.!?\\n]{3,80})"
    )

    private val EVENT = Regex(
        "(?i)\\b(meeting|call|class|lecture|lab|interview|appointment|dinner|lunch|breakfast|standup|stand-up|sync|session|exam|test|party|match|game|movie|flight|train|bus|pickup|pick-up|delivery|demo|review)\\b" +
            "[^.!?\\n]{0,40}?\\b(?:is\\s+)?(?:moved|rescheduled|shifted|pushed|postponed|preponed|changed|starts?|starting|scheduled|confirmed|booked|at|on|tomorrow|today|tonight)\\b"
    )

    private val DUE = Regex(
        "(?i)\\b(?:due|deadline|last date|pay(?:ment)?|renew(?:al)?|expires?|submit(?:ssion)?)\\b[^.!?\\n]{0,30}?\\b(?:on|by|before|till|until|is)\\b"
    )

    /** Leading filler words that make a bad task title. */
    private val TITLE_FILLER = Regex("(?i)^(?:also|just|quickly|pls|plz|please|maybe|kindly)\\s+")
    private val TRAILING_FILLER = Regex("(?i)[\\s,]*(?:pls|plz|please|thanks|thank you|thx|asap|ok|okay|na|yaar|bro|\\?)+\\s*$")

    /** The merged span of adjacent time tokens, e.g. "tomorrow at 5pm". */
    fun timeSpan(text: String): String? {
        val matches = TIME_TOKEN.findAll(text).toList()
        if (matches.isEmpty()) return null
        val start = matches[0].range.first
        var end = matches[0].range.last
        for (m in matches.drop(1)) {
            val gap = text.substring(end + 1, m.range.first)
            val connector = gap.isBlank() || gap.trim().lowercase() in setOf("at", "by", "on", ",", "around")
            if (gap.length <= 8 && connector) {
                end = m.range.last
            } else break
        }
        return text.substring(start, end + 1).trim()
    }

    private fun cap(s: String): String = s.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }

    private fun cleanTitle(raw: String): String {
        var t = raw.trim()
        t = TITLE_FILLER.replace(t, "")
        t = TRAILING_FILLER.replace(t, "")
        t = NotificationText.stripEmoji(t).replace(Regex("\\s+"), " ").trim().trim(',', '.', '-', ':')
        if (t.length > 80) t = t.take(79).trimEnd() + "…"
        return cap(t)
    }

    /** Converts second-person requests into a first-person task ("send me the notes" → "Send Riya the notes"). */
    private fun personalise(phrase: String, from: String?): String {
        if (from == null) return phrase
        return phrase
            .replace(Regex("(?i)\\bme\\b"), from)
            .replace(Regex("(?i)\\bmy\\b"), "$from's")
    }

    /**
     * The suggestion for [n], or null. Never fires on verification codes, promotions, ongoing or
     * system notifications; never includes digits that could be codes (the summary is scrubbed).
     */
    fun extract(n: IncomingNotification): NotificationSuggestion? {
        val raw = n.body ?: return null
        if (raw.length < 6) return null
        if (NotificationText.isVerificationCode(raw) || NotificationText.isVerificationCode(n.title)) return null
        if (n.category in IncomingNotification.NEVER_READ_CATEGORIES) return null
        if (n.category in IncomingNotification.LOW_VALUE_CATEGORIES) return null
        if (n.isOngoing || n.isGroupSummary) return null

        val parts = NotificationSpeech.parts(n)
        val from = if (n.isMessaging || n.category == IncomingNotification.CATEGORY_EMAIL) parts.from else null
        val source = listOfNotNull(parts.app, from).joinToString(" · ")
        val time = timeSpan(raw)

        // 1. "Call me back at 6" → "Call Riya"
        if (CALL_ME.containsMatchIn(raw) && from != null) {
            val title = "Call $from"
            return NotificationSuggestion(title, time, summary(source, title, time))
        }

        // 2. Explicit requests: "can you send the slides tonight?"
        REQUEST.find(raw)?.let { m ->
            val phrase = m.groupValues[1]
            val withoutTime = if (time != null) phrase.replace(time, "").replace(Regex("\\s+"), " ") else phrase
            val title = cleanTitle(personalise(withoutTime.replace(Regex("(?i)\\b(?:at|by|on|around)\\s*$"), ""), from))
            if (title.length >= 3 && title.split(' ').size <= 14) {
                return NotificationSuggestion(title, time, summary(source, title, time))
            }
        }

        // 3. Events with a time: "Standup moved to 11:30", "Dinner tomorrow at 8?"
        if (time != null) {
            EVENT.find(raw)?.let { m ->
                val event = cap(m.groupValues[1].lowercase())
                val title = if (from != null && !n.isGroupConversation) "$event with $from" else event
                return NotificationSuggestion(title, time, summary(source, title, time))
            }
            // 4. Deadlines: "Electricity bill is due on 25 Nov"
            if (DUE.containsMatchIn(raw)) {
                val subject = NotificationText.cleanName(n.title)?.takeUnless { it.equals(parts.app, true) } ?: parts.app
                val title = cleanTitle("Deadline: $subject")
                return NotificationSuggestion(title, time, summary(source, title, time))
            }
        }
        return null
    }

    private fun summary(source: String, title: String, time: String?): String {
        val t = if (time != null) "$title — $time" else title
        return NotificationText.cleanBody("From $source: $t", maxChars = 140)
    }
}
