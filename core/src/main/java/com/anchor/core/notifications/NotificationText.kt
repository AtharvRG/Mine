// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.notifications

import com.anchor.core.ambient.NumberScrubber

/**
 * Turns raw notification strings into something safe and pleasant to hear.
 *
 * Text-to-speech reads exactly what it is given, so everything that sounds bad or leaks data is
 * removed here: URLs, emoji, message counters, verification codes and long digit runs. Scripts
 * other than Latin (Devanagari, Arabic, CJK, …) are kept untouched.
 */
object NotificationText {

    private val URL = Regex("""(?i)\b(?:https?://|www\.)\S+""")
    private val WHITESPACE = Regex("""[ \t ]+""")
    private val NEWLINES = Regex("""\s*[\r\n]+\s*""")
    private val COUNTER = Regex("""\s*[(\[]\s*\d+\s+(?:new\s+)?(?:messages?|notifications?)\s*[)\]]\s*$""", RegexOption.IGNORE_CASE)
    private val REPEATED_PUNCT = Regex("""([!?.])\1{1,}""")

    /** Keyword + 4-8 digits, or a bare "is 482913"-style code next to a code keyword. */
    private val CODE_KEYWORDS = Regex(
        """(?i)\b(?:otp|one[\s-]?time\s+(?:pass(?:word|code)?|code)|verification|verify|security\s+code|login\s+code|auth(?:entication)?\s+code|passcode|2fa|pin)\b"""
    )
    /** 4-8 digits standing alone: not part of a longer number, an amount ("1,240") or a decimal. */
    private val CODE_DIGITS = Regex("""(?<![\d.,])\d{4,8}(?!\d)(?![.,]\d)""")

    /**
     * True for messages whose point is a one-time code. Those are announced ("a verification code
     * from your bank") but the code itself is never spoken, shown in the feed or stored.
     */
    fun isVerificationCode(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        return CODE_KEYWORDS.containsMatchIn(text) && CODE_DIGITS.containsMatchIn(text)
    }

    /** Removes pictographs, flags, skin tones, variation selectors and zero-width joiners. */
    fun stripEmoji(input: String): String {
        if (input.isEmpty()) return input
        val sb = StringBuilder(input.length)
        var i = 0
        while (i < input.length) {
            val cp = input.codePointAt(i)
            i += Character.charCount(cp)
            if (!isEmojiCodePoint(cp)) sb.appendCodePoint(cp)
        }
        return sb.toString()
    }

    private fun isEmojiCodePoint(cp: Int): Boolean =
        cp in 0x1F000..0x1FAFF ||      // pictographs, emoticons, transport, symbols & pictographs ext
            cp in 0x2600..0x27BF ||    // misc symbols, dingbats
            cp in 0x2B00..0x2BFF ||    // arrows & stars used as emoji
            cp in 0x1F1E6..0x1F1FF ||  // regional indicators (flags)
            cp in 0xFE00..0xFE0F ||    // variation selectors
            cp == 0x200D ||            // zero-width joiner
            cp == 0x20E3 ||            // combining keycap
            cp in 0xE0020..0xE007F     // tag characters (subdivision flags)

    /** A display name fit to be spoken: no emoji, no "(3 messages)", at most 40 characters. */
    fun cleanName(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val s = stripEmoji(raw).replace(COUNTER, "").replace(WHITESPACE, " ").trim().trim(':', '-', '·', '•').trim()
        if (s.isEmpty()) return null
        return if (s.length <= 40) s else s.take(39).trimEnd() + "…"
    }

    /**
     * Body text fit to be spoken. Digits that could be codes, cards or phone numbers become
     * "a number"; links become "a link"; line breaks become sentence breaks.
     */
    fun cleanBody(raw: String?, maxChars: Int = 220): String {
        if (raw.isNullOrBlank()) return ""
        var s = raw
        s = URL.replace(s, "a link")
        s = stripEmoji(s)
        s = NumberScrubber.scrub(s).replace("[number]", "a number")
        s = NEWLINES.replace(s) { ". " }
        s = s.replace(Regex("""\.\s*\.\s"""), ". ")
        s = REPEATED_PUNCT.replace(s) { it.groupValues[1] }
        s = WHITESPACE.replace(s, " ").trim().trimStart('.', ' ')
        return truncate(s, maxChars)
    }

    /** Cuts at the last sentence end, else the last word, before [maxChars]. */
    fun truncate(s: String, maxChars: Int): String {
        if (s.length <= maxChars) return s
        val window = s.substring(0, maxChars)
        val sentenceEnd = window.lastIndexOfAny(charArrayOf('.', '!', '?', '।'))
        if (sentenceEnd >= maxChars / 2) return window.substring(0, sentenceEnd + 1).trim()
        val space = window.lastIndexOf(' ')
        val cut = if (space >= maxChars / 2) window.substring(0, space) else window
        return cut.trimEnd(',', ';', ':', ' ') + "…"
    }

    /** Ends a phrase with a full stop unless it already ends with punctuation. */
    fun sentence(s: String): String {
        val t = s.trim()
        if (t.isEmpty()) return t
        return if (t.last() in ".!?…।") t else "$t."
    }
}
