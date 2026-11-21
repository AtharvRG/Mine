// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.time

import java.util.Locale

/**
 * Rewrites the many ways people (and models) write a date or a time into the small set of
 * canonical forms [DateResolver] understands. Pure text in, text out: it never decides what a
 * time *means*, so every "never guess" rule of the resolver still applies to its output.
 *
 * Examples (input → output):
 *  - "24th Nov at 3.40pm"      → "24th nov at 3:40pm"
 *  - "on 24 November, 3:40 PM" → "24 november 3:40pm"
 *  - "3.40 p.m."               → "3:40pm"
 *  - "quarter past 4"          → "4:15"
 *  - "1540 hrs"                → "15:40"
 *  - "tmrw @ noon"             → "tomorrow at 12pm"
 *  - "in five minutes"         → "in 5 minutes"
 *
 * Every rule is anchored on digits or on a closed word list, so ordinary prose around a time
 * ("I am", "at home") is left alone.
 */
object TimeTextNormalizer {

    private const val MONTH_ALT =
        "jan|january|feb|february|mar|march|apr|april|may|jun|june|jul|july|aug|august|sep|sept|september|oct|october|nov|november|dec|december"
    private const val WEEKDAY_ALT =
        "monday|mon|tuesday|tues|tue|wednesday|wed|thursday|thurs|thur|thu|friday|fri|saturday|sat|sunday|sun"

    private val NUMBER_WORDS = mapOf(
        "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7,
        "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12, "thirteen" to 13,
        "fourteen" to 14, "fifteen" to 15, "sixteen" to 16, "seventeen" to 17, "eighteen" to 18,
        "nineteen" to 19, "twenty" to 20, "twenty five" to 25, "twenty-five" to 25, "thirty" to 30,
        "forty" to 40, "forty five" to 45, "forty-five" to 45, "fifty" to 50, "sixty" to 60, "ninety" to 90,
    )

    private val TYPO_WORDS = listOf(
        Regex("""\b(?:tmrw|tmrow|tmr|tmw|tomm?or+ow|tomorow|tommorow|tomorro|2morrow|2moro|2mrw)\b""") to "tomorrow",
        Regex("""\b(?:2nite|tonite|tonyt)\b""") to "tonight",
        Regex("""\b2day\b""") to "today",
        Regex("""\b(?:overmorrow|day after tmrw|day after tomorow)\b""") to "day after tomorrow",
        Regex("""\bsept\b""") to "sep",
    )

    fun normalize(input: String): String {
        var t = normalizeDigits(input).lowercase(Locale.ROOT)

        // Typography: curly quotes, dashes, non-breaking spaces.
        t = t.replace('’', '\'').replace('‘', '\'')
            .replace('–', '-').replace('—', '-')
            .replace(' ', ' ').replace(' ', ' ')

        // A full ISO instant is left whole; the resolver reads it with its offset.
        if (ISO_INSTANT.matches(t.trim())) return t.trim()

        // "@ 5" → "at 5"
        t = t.replace(Regex("""\s*@\s*"""), " at ")

        for ((re, word) in TYPO_WORDS) t = re.replace(t, word)

        // "a.m." / "p.m." / "a. m." after a number → "am" / "pm" (never touches the word "am").
        t = Regex("""(\d)\s*([ap])\s?\.\s?m\b\.?""").replace(t) { "${it.groupValues[1]}${it.groupValues[2]}m" }
        t = Regex("""(\d)\s+([ap])m\b""").replace(t) { "${it.groupValues[1]}${it.groupValues[2]}m" }

        // "5 o'clock" → "5"
        t = Regex("""(\d{1,2})\s*o\s?'?\s?clock\b""").replace(t) { it.groupValues[1] }

        // Noon and midnight. "12 noon" first so it is not read as "12 12pm".
        t = Regex("""\b12\s*(?:noon|midday)\b""").replace(t, "12pm")
        t = Regex("""\b(?:noon|midday)\b""").replace(t, "12pm")
        t = Regex("""\b12\s*midnight\b""").replace(t, "12am")
        t = Regex("""\bmidnight\b""").replace(t, "12am")

        // Spelled-out numbers next to a unit or "past/to" ("in five minutes", "ten past 4").
        t = replaceNumberWords(t)

        // "an hour and a half", "one and a half hours", "1.5 hours" → minutes.
        t = Regex("""\b(?:an|1)\s+hours?\s+and\s+a\s+half\b|\b(?:one|1)\s+and\s+a\s+half\s+hours?\b|\b1\.5\s*(?:hours?|hrs?)\b""").replace(t, "90 minutes")
        t = Regex("""\b(\d{1,2})\s+and\s+a\s+half\s+hours?\b""").replace(t) { "${it.groupValues[1].toInt() * 60 + 30} minutes" }
        t = Regex("""\b(\d{1,2})\.5\s*(?:hours?|hrs?)\b""").replace(t) { "${it.groupValues[1].toInt() * 60 + 30} minutes" }

        // Clock phrases: "quarter past 4" → "4:15", "half past 7" → "7:30", "quarter to 5" → "4:45".
        t = Regex("""\b(?:a\s+)?quarter\s+past\s+(\d{1,2})\b""").replace(t) { clock(it.groupValues[1].toInt(), 15) }
        t = Regex("""\bhalf\s+past\s+(\d{1,2})\b""").replace(t) { clock(it.groupValues[1].toInt(), 30) }
        t = Regex("""\b(?:a\s+)?quarter\s+to\s+(\d{1,2})\b""").replace(t) { clockBefore(it.groupValues[1].toInt(), 15) }
        t = Regex("""\b(\d{1,2})\s+(?:minutes?|mins?)\s+past\s+(\d{1,2})\b""").replace(t) {
            val m = it.groupValues[1].toInt()
            if (m in 1..59) clock(it.groupValues[2].toInt(), m) else it.value
        }
        t = Regex("""\b(\d{1,2})\s+(?:minutes?|mins?)\s+to\s+(\d{1,2})\b""").replace(t) {
            val m = it.groupValues[1].toInt()
            if (m in 1..59) clockBefore(it.groupValues[2].toInt(), m) else it.value
        }
        // "ten past 4" with a round number but no "minutes": only the conventional values, and
        // never after "from"/"between", where "5 to 7" is a range, not a time.
        t = Regex("""(^|[^\w])(?<!from )(?<!between )(5|10|20|25)\s+past\s+(\d{1,2})\b""").replace(t) {
            it.groupValues[1] + clock(it.groupValues[3].toInt(), it.groupValues[2].toInt())
        }

        // "3 40 pm" → "3:40pm"
        t = Regex("""\b(\d{1,2})\s(\d{2})\s*(am|pm)\b""").replace(t) { m ->
            if (validClock(m.groupValues[1], m.groupValues[2])) "${m.groupValues[1]}:${m.groupValues[2]}${m.groupValues[3]}" else m.value
        }

        // Dotted times. "3.40pm", "15.40h", "at 3.40", "by 9.15". Not "in 2.30 hours" (a duration)
        // and not "24.11.2026" (a date).
        t = Regex("""(?<![\d.])(\d{1,2})[.](\d{2})(?![.\d])(\s*(?:am|pm|hrs?|hours|h)\b)""").replace(t) { m ->
            val before = t.substring(0, m.range.first)
            val unit = m.groupValues[3].trim()
            if (!validClock(m.groupValues[1], m.groupValues[2])) m.value
            else if ((unit.startsWith("hour") || unit.startsWith("hr")) && Regex("""\b(?:in|after|within|for)\s*$""").containsMatchIn(before)) m.value
            else "${m.groupValues[1]}:${m.groupValues[2]}${m.groupValues[3]}"
        }
        t = Regex("""\b(at|by|around|till|until|before|after|from|between|and)\s+(\d{1,2})[.](\d{2})(?![.\d])""").replace(t) { m ->
            if (validClock(m.groupValues[2], m.groupValues[3])) "${m.groupValues[1]} ${m.groupValues[2]}:${m.groupValues[3]}" else m.value
        }
        Regex("""^(?:at\s+)?(\d{1,2})[.](\d{2})$""").matchEntire(t.trim())?.let { m ->
            if (validClock(m.groupValues[1], m.groupValues[2])) t = "${m.groupValues[1]}:${m.groupValues[2]}"
        }

        // "15:40 hrs", "1540 hrs", "1540h", "15:40h" → "15:40"
        t = Regex("""\b(\d{1,2}):(\d{2})\s*(?:hrs?|hours|h)\b""").replace(t) { m ->
            if (validClock(m.groupValues[1], m.groupValues[2])) "${m.groupValues[1]}:${m.groupValues[2]}" else m.value
        }
        t = Regex("""\b(\d{2})(\d{2})\s*(?:hrs?|hours|h)\b""").replace(t) { m ->
            if (validClock(m.groupValues[1], m.groupValues[2])) "${m.groupValues[1]}:${m.groupValues[2]}" else m.value
        }

        // Approximations and deadlines read as the time itself: "around 5pm", "by 6", "5pm sharp".
        t = Regex("""\b(?:around|about|approx\.?|approximately|roughly|by|before|latest by|no later than)\s+(?=\d)""").replace(t, "at ")
        t = Regex("""(\d(?:am|pm)?)\s+sharp\b""").replace(t) { it.groupValues[1] }
        t = Regex("""\bat\s+at\b""").replace(t, "at")

        // Weekend shorthand.
        t = Regex("""\bnext\s+weekend\b""").replace(t, "next saturday")
        t = Regex("""\b(?:this\s+|the\s+|on\s+the\s+)?weekend\b""").replace(t, "saturday")

        // "24-nov", "nov-24", commas and "of" between date pieces.
        t = Regex("""\b(\d{1,2})(st|nd|rd|th)?-($MONTH_ALT)\b""").replace(t) { "${it.groupValues[1]}${it.groupValues[2]} ${it.groupValues[3]}" }
        t = Regex("""\b($MONTH_ALT)-(\d{1,2})\b""").replace(t) { "${it.groupValues[1]} ${it.groupValues[2]}" }
        t = t.replace(',', ' ')

        // "the 24th of november" → "24th november"
        t = Regex("""\bthe\s+(\d{1,2}(?:st|nd|rd|th))\s+(?:of\s+)?($MONTH_ALT)\b""").replace(t) { "${it.groupValues[1]} ${it.groupValues[2]}" }
        t = Regex("""\b($MONTH_ALT)\s+the\s+(\d{1,2}(?:st|nd|rd|th)?)\b""").replace(t) { "${it.groupValues[1]} ${it.groupValues[2]}" }

        // Collapse whitespace, then trim a leading "on " before a date, weekday or "the 24th".
        t = t.replace(Regex("""\s+"""), " ").trim()
        t = Regex("""^on\s+(?=\d|the\s+\d|($MONTH_ALT)\b)""").replace(t, "")
        t = Regex("""^(?:for|due|due on|due by|deadline)\s+(?=\d|tomorrow|today|tonight|next|this|($MONTH_ALT|$WEEKDAY_ALT)\b)""").replace(t, "")

        // Trailing punctuation the model or user added.
        t = t.trimEnd('.', '!', '?', ';', ':').trim()
        return t
    }

    // ---------------------------------------------------------------------------------------

    private val ISO_INSTANT = Regex("""\d{4}-\d{2}-\d{2}t\d{2}:\d{2}(?::\d{2}(?:\.\d+)?)?(?:z|[+-]\d{2}:?\d{2})?""")

    private fun replaceNumberWords(text: String): String {
        var t = text
        // Longest phrases first so "twenty five" wins over "twenty".
        for ((word, n) in NUMBER_WORDS.entries.sortedByDescending { it.key.length }) {
            val w = Regex.escape(word)
            t = Regex("""\b$w(?=\s+(?:seconds?|secs?|minutes?|mins?|hours?|hrs?|days?|weeks?|past|to\s+\d|o'?clock|am\b|pm\b))""").replace(t, n.toString())
            t = Regex("""(?<=\b(?:at|past|quarter to|half past)\s)$w\b""").replace(t, n.toString())
        }
        // "a couple of hours" and "a few minutes" are deliberately left alone: they are guesses.
        return t
    }

    private fun validClock(h: String, m: String): Boolean {
        val hh = h.toIntOrNull() ?: return false
        val mm = m.toIntOrNull() ?: return false
        return hh in 0..23 && mm in 0..59
    }

    private fun clock(hour: Int, minute: Int): String =
        if (hour in 0..23 && minute in 0..59) "$hour:${"%02d".format(minute)}" else "$hour"

    /** "quarter to 5" → "4:45"; "to 1" wraps to "12:45" on the same 12-hour face. */
    private fun clockBefore(hour: Int, minutes: Int): String {
        if (hour !in 0..23 || minutes !in 1..59) return "$hour"
        val h = when {
            hour == 0 -> 23
            hour == 1 -> 12
            hour == 13 -> 12
            else -> hour - 1
        }
        return "$h:${"%02d".format(60 - minutes)}"
    }

    /**
     * Any Unicode decimal digit ("१५", "١٥") becomes its ASCII digit. Only single UTF-16 units are
     * rewritten, so the result has exactly the same length as the input and character offsets
     * found in it point at the same characters in the original (the extractor relies on this).
     */
    fun normalizeDigits(input: String): String {
        val sb = StringBuilder(input.length)
        for (c in input) {
            val d = if (Character.isSurrogate(c)) -1 else Character.digit(c, 10)
            sb.append(if (d in 0..9) ('0' + d) else c)
        }
        return sb.toString()
    }
}
