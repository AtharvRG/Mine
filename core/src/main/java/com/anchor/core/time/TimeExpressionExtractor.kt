// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.time

/**
 * Finds the date and time phrases inside a whole sentence, wherever they sit:
 *
 *   "call mom on 24th Nov at 3.40pm"   → date "on 24th Nov", time "at 3.40pm"
 *   "3:40pm dentist tomorrow"          → date "tomorrow",    time "3:40pm"
 *   "remind me in five minutes to stretch" → duration "in five minutes"
 *
 * It only *finds* the phrases. [dueText] is the canonical "<date> at <time>" string the
 * [DateResolver] reads; [title] is the sentence with those phrases (and the connecting
 * words around them) removed, with the user's own capitalisation kept.
 *
 * Matching runs on a digit-normalized copy of the input that has exactly the same length, so
 * every span maps straight back onto the original text.
 */
object TimeExpressionExtractor {

    data class Span(val start: Int, val end: Int, val text: String, val kind: Kind)
    enum class Kind { DATE, TIME, DURATION }

    data class Extraction(
        /** Canonical text for [DateResolver.resolve], e.g. "24th nov at 3:40pm". */
        val dueText: String,
        /** The input with the time phrases removed, e.g. "call mom". Empty when nothing is left. */
        val title: String,
        val spans: List<Span>,
    )

    private const val MONTH =
        "jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|june?|july?|aug(?:ust)?|sep(?:t(?:ember)?)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?"
    private const val WEEKDAY_FULL = "monday|tuesday|wednesday|thursday|friday|saturday|sunday"
    /** Short forms collide with words ("sat", "sun", "wed"), so they only count with a prefix or a date after them. */
    private const val WEEKDAY_ABBR = "mon|tues?|wed|thur?s?|fri|sat|sun"
    private const val NUMWORD =
        "one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|fifteen|twenty(?:[ -]five)?|thirty|forty(?:[ -]five)?|fifty|sixty|ninety"
    private const val AMPM = """[ap]\.?\s?m\b\.?"""
    private const val TOMORROW = "tomorrow|tmrw|tmrow|tmr|tmw|tomorow|tommorow|tommorrow|2moro|2morrow"
    /** Day parts that can follow a clock time ("9:30 in the evening"). "tonight" is a date, not a day part. */
    private const val DAYPART = """(?:in\s+the\s+)?(?:morning|afternoon|evening)|at\s+night"""

    private val OPTS = setOf(RegexOption.IGNORE_CASE)

    private val DURATION_RE = Regex(
        """\b(?:in|after|within)\s+(?:""" +
            """(?:an?\s+)?(?:hour|hr)\s+and\s+a\s+half""" +
            """|half\s+an?\s+hour|a\s+half\s+hour""" +
            """|(?:(?:\d{1,5}(?:\.5)?|an?|$NUMWORD)\s*(?:seconds?|secs?|minutes?|mins?|hours?|hrs?|days?|weeks?|wks?|[smhdw])\b(?:\s*(?:and|,)\s*|\s+)?)+""" +
            """)""" +
            """|\b(?:\d{1,4}|an?|$NUMWORD)\s+(?:minutes?|mins?|hours?|hrs?|days?|weeks?)\s+from\s+now\b""",
        OPTS,
    )

    private val DATE_RE = Regex(
        listOf(
            """\b(?:the\s+)?day\s+after\s+(?:$TOMORROW)\b|\bovermorrow\b""",
            """\b(?:on\s+)?(?:(?:next|this(?:\s+coming)?|coming)\s+)?(?:$WEEKDAY_FULL)\b(?:\s*,?\s*(?:the\s+)?\d{1,2}(?:st|nd|rd|th)?(?:\s+(?:of\s+)?(?:$MONTH)\b)?)?""",
            """\b(?:on|next|this|coming)\s+(?:$WEEKDAY_ABBR)\b\.?(?:\s*,?\s*(?:the\s+)?\d{1,2}(?:st|nd|rd|th)?(?:\s+(?:of\s+)?(?:$MONTH)\b)?)?""",
            """\b(?:$WEEKDAY_ABBR)\b\.?\s*,?\s*(?:the\s+)?\d{1,2}(?:st|nd|rd|th)?\s+(?:of\s+)?(?:$MONTH)\b""",
            """\b(?:on\s+)?(?:the\s+)?\d{1,2}(?:st|nd|rd|th)?(?:\s+of)?[\s-]+(?:$MONTH)\b\.?(?:,?\s+\d{4}\b)?""",
            """\b(?:on\s+)?(?:$MONTH)\.?[\s-]+(?:the\s+)?\d{1,2}(?:st|nd|rd|th)?\b(?:,?\s+\d{4}\b)?""",
            """\b\d{4}-\d{1,2}-\d{1,2}\b""",
            """\b(?:on\s+)?\d{1,2}/\d{1,2}(?:/\d{2,4})?\b|\b(?:on\s+)?\d{1,2}-\d{1,2}-\d{2,4}\b|\b(?:on\s+)?\d{1,2}\.\d{1,2}\.\d{2,4}\b""",
            """\b(?:on\s+)?the\s+\d{1,2}(?:st|nd|rd|th)\b""",
            """\bin\s+(?:\d{1,3}|$NUMWORD)\s+days?\b|\b\d{1,3}\s+days?\s+from\s+(?:now|today)\b""",
            """\b(?:next|this|coming)\s+(?:week|month)\b""",
            """\b(?:(?:this|next)\s+)?weekend\b""",
            """\b(?:$TOMORROW|today|tonight|tonite|2nite)\b(?:\s+(?:$DAYPART))?""",
        ).joinToString("|"),
        OPTS,
    )

    private val TIME_RE = Regex(
        listOf(
            // 3pm, 3:40pm, 3.40 p.m., at 9 am — with an optional day part or "sharp".
            """(?:\b(?:at|by|around|about)\s+)?\b\d{1,2}(?:[:.]\d{2})?\s*$AMPM(?:\s+sharp\b)?""",
            // quarter past four, half past 7, 20 minutes to 5, ten past 4
            """(?:\b(?:at|by|around)\s+)?\b(?:a\s+)?quarter\s+(?:past|to)\s+(?:\d{1,2}|$NUMWORD)\b(?:\s*$AMPM)?""",
            """(?:\b(?:at|by|around)\s+)?\bhalf\s+past\s+(?:\d{1,2}|$NUMWORD)\b(?:\s*$AMPM)?""",
            """(?:\b(?:at|by|around)\s+)?\b(?:\d{1,2}|$NUMWORD)\s+(?:minutes?\s+|mins?\s+)?(?:past|to)\s+(?:\d{1,2}|$NUMWORD)\b(?:\s*$AMPM)?""",
            // noon, 12 noon, midnight
            """(?:\b(?:at|by|around)\s+)?\b(?:12\s*)?(?:noon|midday|midnight)\b""",
            // 5 o'clock
            """(?:\b(?:at|by|around)\s+)?\b\d{1,2}\s*o\s?'?\s?clock\b(?:\s*$AMPM)?""",
            // 15:40 hrs, 1540 hrs, 15.40h
            """(?:\b(?:at|by|around)\s+)?\b\d{1,2}[:.]\d{2}\s*(?:hrs?|hours|h)\b|(?:\b(?:at|by|around)\s+)?\b\d{4}\s*(?:hrs?|hours|h)\b""",
            // 15:40, at 3:40 in the evening, 9:30 tonight-style day parts
            """(?:\b(?:at|by|around|about)\s+)?\b\d{1,2}:\d{2}\b(?!\s*(?:minutes?|mins?|hours?|hrs?))(?:\s+(?:$DAYPART))?""",
            // at 15h, at 15h30 (D-103 forms need "at" inside a sentence)
            """\bat\s+\d{1,2}h(?:\d{2})?\b""",
            // 3 in the evening, at 6 in the morning, 3 at night
            """(?:\b(?:at|by|around)\s+)?\b\d{1,2}(?:[.]\d{2})?\s+(?:in\s+the\s+)?(?:morning|afternoon|evening|night)\b|\b\d{1,2}\s+at\s+night\b""",
            // at 6, at 6.30, by 9 — a bare hour only counts with a preposition in front.
            """\b(?:at|by|around)\s+\d{1,2}(?:[.]\d{2})?\b(?!\s*(?:%|percent|people|persons|items|pieces|kg|km|minutes?|mins?|hours?|hrs?|days?|weeks?|years?|st\b|nd\b|rd\b|th\b|/|-\d))""",
            // morning, in the evening, at night (day part with no clock)
            """\b(?:$DAYPART)\b""",
        ).joinToString("|"),
        OPTS,
    )

    private val LEADING_COMMAND = Regex(
        """^\s*(?:hey\s+mine[,!]?\s+|mine[,!]?\s+)?(?:please\s+)?(?:(?:can|could|would)\s+you\s+)?(?:please\s+)?""" +
            """(?:remind\s+me(?:\s+(?:to|about|that|of))?|gentle\s+reminder(?:\s+(?:to|for|about))?|""" +
            """set\s+(?:an?\s+)?(?:alarm|reminder|timer)(?:\s+(?:to|for|about))?|""" +
            """(?:create|add|make)\s+(?:an?\s+)?(?:task|reminder|todo|to-do)(?:\s+(?:to|for|about))?|""" +
            """alarm(?:\s+(?:to|for))?|reminder(?:\s+(?:to|for))?|todo\s*:|task\s*:)\s*""",
        OPTS,
    )
    private val DANGLING = Regex("""^(?:to|for|on|at|by|about|of|that|and|-|,|:)\s+|\s+(?:to|for|on|at|by|about|of|that|and|from|-|,|:)$""", OPTS)

    /** Returns null when the text holds no date, time or duration at all. */
    fun extract(text: String): Extraction? {
        if (text.isBlank()) return null
        val scan = TimeTextNormalizer.normalizeDigits(text)

        val durations = DURATION_RE.findAll(scan).map { Span(it.range.first, it.range.last + 1, text.substring(it.range.first, it.range.last + 1), Kind.DURATION) }.toList()
        val dates = DATE_RE.findAll(scan).map { Span(it.range.first, it.range.last + 1, text.substring(it.range.first, it.range.last + 1), Kind.DATE) }.toList()
        val times = TIME_RE.findAll(scan).map { Span(it.range.first, it.range.last + 1, text.substring(it.range.first, it.range.last + 1), Kind.TIME) }.toList()

        fun overlaps(a: Span, b: Span) = a.start < b.end && b.start < a.end

        // "in 2 days" is a date when a clock time comes with it, a duration otherwise.
        val inDays = Regex("""^\s*(?:in\s+\S+\s+days?|\S+\s+days?\s+from\s+(?:now|today))\s*$""", OPTS)

        val date = dates.firstOrNull { d -> times.none { t -> overlaps(t, d) && (t.end - t.start) > (d.end - d.start) } }
        val time = times.firstOrNull { t -> date == null || !overlaps(t, date) }

        val chosen: List<Span> = when {
            date != null && time != null -> listOf(date, time)
            date != null && inDays.matches(date.text) -> listOf(date.copy(kind = Kind.DURATION))
            date != null -> listOf(date)
            time != null -> {
                // A clock time and a duration both present ("in 10 minutes at 5pm") is a contradiction: keep the clock.
                listOf(time)
            }
            durations.isNotEmpty() -> listOf(durations.first())
            else -> return null
        }

        val due = when {
            chosen.size == 2 -> {
                val d = TimeTextNormalizer.normalize(chosen[0].text)
                val t = TimeTextNormalizer.normalize(chosen[1].text).removePrefix("at ").trim()
                "$d at $t"
            }
            else -> TimeTextNormalizer.normalize(chosen[0].text)
        }

        return Extraction(due, titleWithout(text, chosen), chosen.sortedBy { it.start })
    }

    /** The sentence with the given spans cut out and the command words stripped. */
    fun titleWithout(text: String, spans: List<Span>): String {
        val sb = StringBuilder()
        var pos = 0
        for (s in spans.sortedBy { it.start }) {
            if (s.start > pos) sb.append(text, pos, s.start)
            sb.append(' ')
            pos = maxOf(pos, s.end)
        }
        if (pos < text.length) sb.append(text, pos, text.length)
        var t = sb.toString().replace(Regex("""\s+"""), " ").trim()
        t = LEADING_COMMAND.replace(t, "").trim()
        // Strip connectors left hanging at either end, repeatedly ("to call mom at" → "call mom").
        var prev: String
        do {
            prev = t
            t = DANGLING.replace(t, "").trim().trim(',', ';', '.', '!', '?').trim()
        } while (t != prev)
        // Connectors left in the middle where a phrase was removed: "call mom on at" never survives,
        // but "call mom  to" style gaps are already collapsed above.
        return t.replace(Regex("""\s+(?:on|at)\s+(?=(?:to|for)\b)""", OPTS), " ")
    }
}
