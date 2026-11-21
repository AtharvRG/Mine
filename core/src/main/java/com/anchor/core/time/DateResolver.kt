// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.time

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

sealed class DateResolution {
    data class Exact(val timeMillis: Long) : DateResolution()
    data class NeedsClarification(val candidateMillis: List<Long>, val chipLabels: List<String>) : DateResolution()
    data object PastTime : DateResolution()
    object Failed : DateResolution()
}

class DateResolver(
    private val clock: Clock,
    val is24HourFormat: () -> Boolean = { false },
    timeZone: TimeZone? = null
) {
    private val explicitTimeZone: TimeZone? = timeZone
    val timeZone: TimeZone
        get() = explicitTimeZone ?: TimeZone.getDefault()

    constructor(clock: Clock, is24HourFormat: () -> Boolean) : this(clock, is24HourFormat, null)
    companion object {
        private val PREFIX_RE = Regex("""^(?:in|after|within)\s+""")
        private val UNIT_RE = Regex("""(\d+)\s*(seconds?|secs?|minutes?|mins?|hours?|hrs?|days?|weeks?|wks?|s|m|h|d|w)\b""")
        private val AM_PM_REGEX = Regex("""^(?:at\s+)?(\d{1,2})(?::(\d{2}))?\s*(am|pm)$""")
        private val DAY_PART_REGEX = Regex("""^(?:at\s+)?(\d{1,2})(?::(\d{2}))?\s*(?:in the\s+|at\s+)?(morning|afternoon|evening|night)$""")
        private val H_REGEX = Regex("""^(?:at\s+)?(\d{1,2})h(\d{2})?$""")
        private val COLON_REGEX = Regex("""^(?:at\s+)?(\d{1,2}):(\d{2})$""")
        private val BARE_HOUR_REGEX = Regex("""^(?:at\s+)?(\d{1,2})$""")

        private val MONTHS = mapOf(
            "jan" to Calendar.JANUARY, "january" to Calendar.JANUARY,
            "feb" to Calendar.FEBRUARY, "february" to Calendar.FEBRUARY,
            "mar" to Calendar.MARCH, "march" to Calendar.MARCH,
            "apr" to Calendar.APRIL, "april" to Calendar.APRIL,
            "may" to Calendar.MAY,
            "jun" to Calendar.JUNE, "june" to Calendar.JUNE,
            "jul" to Calendar.JULY, "july" to Calendar.JULY,
            "aug" to Calendar.AUGUST, "august" to Calendar.AUGUST,
            "sep" to Calendar.SEPTEMBER, "september" to Calendar.SEPTEMBER,
            "oct" to Calendar.OCTOBER, "october" to Calendar.OCTOBER,
            "nov" to Calendar.NOVEMBER, "november" to Calendar.NOVEMBER,
            "dec" to Calendar.DECEMBER, "december" to Calendar.DECEMBER
        )
        private val ISO_DATE_REGEX = Regex("""^(\d{4})-(\d{1,2})-(\d{1,2})(?:\s+(?:at\s+)?(.+))?$""")
        private val MONTH_FIRST_REGEX = Regex(
            """^(jan|january|feb|february|mar|march|apr|april|may|jun|june|jul|july|aug|august|sep|september|oct|october|nov|november|dec|december)\s+(\d{1,2})(?:st|nd|rd|th)?(?:\s+(\d{4}))?(?:\s+(?:at\s+)?(.+))?$"""
        )
        private val DAY_FIRST_REGEX = Regex(
            """^(\d{1,2})(?:st|nd|rd|th)?\s+(?:of\s+)?(jan|january|feb|february|mar|march|apr|april|may|jun|june|jul|july|aug|august|sep|september|oct|october|nov|november|dec|december)(?:\s+(\d{4}))?(?:\s+(?:at\s+)?(.+))?$"""
        )

        private val WEEKDAYS = mapOf(
            "sunday" to Calendar.SUNDAY, "sun" to Calendar.SUNDAY,
            "monday" to Calendar.MONDAY, "mon" to Calendar.MONDAY,
            "tuesday" to Calendar.TUESDAY, "tue" to Calendar.TUESDAY, "tues" to Calendar.TUESDAY,
            "wednesday" to Calendar.WEDNESDAY, "wed" to Calendar.WEDNESDAY,
            "thursday" to Calendar.THURSDAY, "thu" to Calendar.THURSDAY, "thur" to Calendar.THURSDAY, "thurs" to Calendar.THURSDAY,
            "friday" to Calendar.FRIDAY, "fri" to Calendar.FRIDAY,
            "saturday" to Calendar.SATURDAY, "sat" to Calendar.SATURDAY
        )
        private const val WEEKDAY_WORDS = "sunday|sun|monday|mon|tuesday|tue|tues|wednesday|wed|thursday|thu|thur|thurs|friday|fri|saturday|sat"
        private val WEEKDAY_WITH_DATE_REGEX = Regex(
            """^(?:on\s+)?($WEEKDAY_WORDS)\.?\s+((?:the\s+)?\d{1,2}(?:st|nd|rd|th)(?:\s+.*)?|\d{1,2}\s+(?:of\s+)?(?:jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\b.*|(?:jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\.?\s+\d{1,2}.*|\d{1,4}[/.-]\d{1,2}.*)$"""
        )
        private val DAY_AFTER_TOMORROW_REGEX = Regex("""^(?:the\s+)?day\s+after\s+tomorrow(?:\s+(?:at\s+)?(.+))?$""")
        /** "in 3 days at 5pm", "3 days from now at 5pm" → (3, "5pm"). */
        private val IN_N_DAYS_AT_REGEX = Regex("""^(?:in\s+)?(\d{1,3})\s+days?(?:\s+from\s+(?:now|today))?\s+(?:at\s+)?(.+)$""")
        private val NEXT_WEEK_AT_REGEX = Regex("""^next\s+week\s+(?:at\s+)?(.+)$""")
        private val NEXT_MONTH_REGEX = Regex("""^next\s+month(?:\s+(?:at\s+)?(.*))?$""")
        /** "the 24th", "24th at 5pm" — but not "24th nov", which the month rules read. */
        private val ORDINAL_DAY_REGEX = Regex("""^(?:the\s+)?(\d{1,2})(?:st|nd|rd|th)(?!\s+(?:of\s+)?(?:jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec))(?:\s+(?:at\s+)?(.+))?$""")
        private val NUMERIC_DATE_REGEX = Regex(
            """^(\d{4}|\d{1,2})[/.-](\d{1,2})(?:[/.-](\d{2}|\d{4}))?(?:\s+(?:at\s+)?(.+))?$"""
        )
        private val ISO_INSTANT_REGEX = Regex("""^\d{4}-\d{2}-\d{2}t\d{2}:\d{2}(:\d{2}(?:\.\d+)?)?(z|[+-]\d{2}:?\d{2})?$""")
        private val ZONE_WORDS = mapOf(
            "utc" to "UTC", "gmt" to "GMT", "ist" to "Asia/Kolkata",
            "pst" to "America/Los_Angeles", "pdt" to "America/Los_Angeles", "pt" to "America/Los_Angeles",
            "est" to "America/New_York", "edt" to "America/New_York", "et" to "America/New_York",
            "cst" to "America/Chicago", "cdt" to "America/Chicago", "mst" to "America/Denver", "mdt" to "America/Denver",
            "bst" to "Europe/London", "cet" to "Europe/Paris", "cest" to "Europe/Paris",
            "jst" to "Asia/Tokyo", "sgt" to "Asia/Singapore", "aest" to "Australia/Sydney",
        )
        private val ZONE_SUFFIX_REGEX = Regex("""\s(utc|gmt|ist|pst|pdt|pt|est|edt|et|cst|cdt|mst|mdt|bst|cet|cest|jst|sgt|aest|local time|local)(?:\s+time)?$""")

        private val WEEKDAY_REGEX = Regex(
            """^(?:(on\s+next|this\s+coming|coming|on|this|next|last|past)\s+)?""" +
            """(sunday|sun|monday|mon|tuesday|tue|tues|wednesday|wed|thursday|thu|thur|thurs|friday|fri|saturday|sat)""" +
            """(?:\s+(?:at\s+)?(.+))?$"""
        )
    }

    fun parseDuration(text: String?): Long? {
        if (text == null) return null
        val normalized = normalizeDigits(text).lowercase(Locale.US).trim()
        if (normalized.isBlank()) return null
        if (normalized == "in a minute" || normalized == "in a min" || normalized == "in 1 min" || normalized == "in 1 minute" || normalized == "a minute") return 60_000L
        if (normalized == "in an hour" || normalized == "in 1 hr" || normalized == "in 1 hour" || normalized == "an hour") return 3_600_000L
        if (normalized == "in half an hour" || normalized == "in a half hour" || normalized == "half an hour" || normalized == "in 30 mins") return 1_800_000L
        if (normalized == "in a day" || normalized == "in 1 day" || normalized == "a day") return 86_400_000L
        if (normalized == "in a week" || normalized == "in 1 week" || normalized == "a week") return 7 * 86_400_000L

        var body = normalized
        var hasPrefix = false
        PREFIX_RE.find(body)?.let { body = body.substring(it.value.length); hasPrefix = true }
        body = body.replace(Regex("""\band\b|,"""), " ").trim()
        val matches = UNIT_RE.findAll(body).toList()
        if (matches.isEmpty() || UNIT_RE.replace(body, "").isNotBlank()) return null
        var total = 0L
        for (m in matches) {
            val unit = m.groupValues[2]
            if (unit.length == 1 && !hasPrefix) return null   // "15h" must stay 3pm (D-103)
            val amount = m.groupValues[1].toLongOrNull() ?: return null
            if (amount > 100_000L) return null
            total += amount * when (unit[0]) {
                's' -> 1_000L; 'm' -> 60_000L; 'h' -> 3_600_000L; 'w' -> 7 * 86_400_000L; else -> 86_400_000L
            }
        }
        return if (total in 1..366L * 86_400_000L) total else null
    }

    fun parseOffsetDuration(text: String?): Long? {
        if (text == null) return null
        val normalized = normalizeDigits(text).lowercase(Locale.US).trim()
        val cleaned = normalized
            .replace(Regex("""\b(?:before|prior to|prior|ahead of|ahead)\b"""), "")
            .trim()
        return parseDuration(cleaned)
    }

    fun parse(dueText: String?, rolloverMinuteOfDay: Int = 240, timeZone: TimeZone? = null, severity: Int? = null): Long? {
        return (resolve(dueText, rolloverMinuteOfDay, timeZone, severity) as? DateResolution.Exact)?.timeMillis
    }

    private data class DayPartWindow(val startMinute: Int, val endMinute: Int, val defaultMinute: Int)

    private fun resolveDayPartWindow(part: String, severity: Int?): Pair<Int, Int>? {
        val window = when (part) {
            "morning" -> DayPartWindow(7 * 60, 11 * 60, 9 * 60)
            "afternoon" -> DayPartWindow(12 * 60, 16 * 60, 14 * 60)
            "evening" -> DayPartWindow(17 * 60, 20 * 60, 19 * 60)
            "night" -> DayPartWindow(20 * 60, 23 * 60, 21 * 60)
            else -> return null
        }
        val targetMinuteOfDay = if (severity == null) {
            window.defaultMinute
        } else {
            val duration = window.endMinute - window.startMinute
            val percent = when (severity) {
                3 -> 10
                2 -> 35
                1 -> 60
                0 -> 75
                else -> 75
            }
            window.startMinute + (duration * percent / 100)
        }
        return Pair(targetMinuteOfDay / 60, targetMinuteOfDay % 60)
    }

    /**
     * Resolves [dueText] to an instant, or says why it can't.
     *
     * The text is first rewritten by [TimeTextNormalizer] ("24th Nov at 3.40 p.m." →
     * "24th nov at 3:40pm"). A trailing zone ("9am EST", "15:00 UTC") switches the zone for this
     * one resolution. If the canonical text still doesn't resolve, the date and time phrases are
     * pulled out of it with [TimeExpressionExtractor] ("at 5pm tomorrow" → "tomorrow at 5pm") —
     * but only when nothing else is left over, so "3 days before the 24th" or "not tomorrow but
     * friday" still fail and the caller asks, instead of the resolver silently picking a part.
     */
    fun resolve(
        dueText: String?,
        rolloverMinuteOfDay: Int = 240,
        timeZone: TimeZone? = null,
        severity: Int? = null
    ): DateResolution {
        if (dueText == null) return DateResolution.Failed
        val raw = normalizeDigits(dueText).lowercase(Locale.ROOT).trim()
        if (raw.isBlank()) return DateResolution.Failed

        var effectiveTz = timeZone ?: this.timeZone
        var text = TimeTextNormalizer.normalize(raw)

        val zoneMatch = ZONE_SUFFIX_REGEX.find(text)
        if (zoneMatch != null) {
            val zoneWord = zoneMatch.groupValues[1]
            if (zoneWord != "local" && zoneWord != "local time") {
                effectiveTz = ZONE_WORDS[zoneWord]?.let { TimeZone.getTimeZone(it) } ?: return DateResolution.Failed
            }
            text = text.removeRange(zoneMatch.range).replace(Regex("""\s+"""), " ").trim()
            if (text.isBlank()) return DateResolution.Failed
        }

        resolveIsoInstant(text, effectiveTz)?.let { return it }

        val first = resolveCanonical(text, rolloverMinuteOfDay, effectiveTz, severity)
        if (first != DateResolution.Failed) return first

        val extraction = TimeExpressionExtractor.extract(text) ?: return first
        if (extraction.title.isNotBlank()) return first
        val canonical = TimeTextNormalizer.normalize(extraction.dueText)
        if (canonical == text) return first
        return resolveCanonical(canonical, rolloverMinuteOfDay, effectiveTz, severity)
    }

    private fun resolveCanonical(
        normalized: String,
        rolloverMinuteOfDay: Int,
        effectiveTz: TimeZone,
        severity: Int?
    ): DateResolution {
        val now = clock.now()
        val absCal = Calendar.getInstance(effectiveTz).apply {
            timeInMillis = now
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        val currentMinuteOfDay = absCal.get(Calendar.HOUR_OF_DAY) * 60 + absCal.get(Calendar.MINUTE)
        val isLateNight = currentMinuteOfDay < rolloverMinuteOfDay

        val logicalCal = absCal.clone() as Calendar
        if (isLateNight) logicalCal.add(Calendar.DAY_OF_YEAR, -1)

        // 1. Relative durations
        parseDuration(normalized)?.let {
            return DateResolution.Exact(now + it)
        }

        // 1b. Forms added with the normalizer: weekday + date, day after tomorrow, "in 3 days at 5",
        // next week/month with a time, "the 24th", numeric dates (24/11, 11/24 asks).
        resolveExtended(normalized, absCal, now, effectiveTz, severity, rolloverMinuteOfDay)?.let { return it }

        // 2. Named dates without time
        if (normalized == "soon") {
            return DateResolution.Exact(now + 30 * 60 * 1000L)
        }
        if (normalized == "later") {
            return DateResolution.Exact(now + 2 * 60 * 60 * 1000L)
        }

        if (normalized == "yesterday" || normalized.startsWith("yesterday at ") || normalized.startsWith("yesterday ")) {
            return DateResolution.PastTime
        }
        if (normalized == "tomorrow") {
            val tomCal = absCal.clone() as Calendar
            tomCal.add(Calendar.DAY_OF_YEAR, 1)
            tomCal.set(Calendar.HOUR_OF_DAY, 9)
            tomCal.set(Calendar.MINUTE, 0)
            tomCal.set(Calendar.SECOND, 0)
            tomCal.set(Calendar.MILLISECOND, 0)
            return DateResolution.Exact(tomCal.timeInMillis)
        }
        if (normalized == "next week") {
            val nwCal = absCal.clone() as Calendar
            nwCal.add(Calendar.DAY_OF_YEAR, 7)
            nwCal.set(Calendar.HOUR_OF_DAY, 9)
            nwCal.set(Calendar.MINUTE, 0)
            nwCal.set(Calendar.SECOND, 0)
            nwCal.set(Calendar.MILLISECOND, 0)
            return DateResolution.Exact(nwCal.timeInMillis)
        }
        if (normalized == "tonight") {
            val (hr, min) = resolveDayPartWindow("night", severity) ?: Pair(21, 0)
            logicalCal.set(Calendar.HOUR_OF_DAY, hr)
            logicalCal.set(Calendar.MINUTE, min)
            logicalCal.set(Calendar.SECOND, 0)
            logicalCal.set(Calendar.MILLISECOND, 0)
            if (logicalCal.timeInMillis < now) logicalCal.add(Calendar.DAY_OF_YEAR, 1)
            return DateResolution.Exact(logicalCal.timeInMillis)
        }

        if (normalized == "this morning" || normalized == "today morning") {
            val (hr, min) = resolveDayPartWindow("morning", severity) ?: Pair(9, 0)
            val targetCal = absCal.clone() as Calendar
            targetCal.set(Calendar.HOUR_OF_DAY, hr)
            targetCal.set(Calendar.MINUTE, min)
            targetCal.set(Calendar.SECOND, 0)
            targetCal.set(Calendar.MILLISECOND, 0)
            return if (targetCal.timeInMillis < now) DateResolution.PastTime else DateResolution.Exact(targetCal.timeInMillis)
        }
        if (normalized == "this afternoon" || normalized == "today afternoon") {
            val (hr, min) = resolveDayPartWindow("afternoon", severity) ?: Pair(14, 0)
            val targetCal = absCal.clone() as Calendar
            targetCal.set(Calendar.HOUR_OF_DAY, hr)
            targetCal.set(Calendar.MINUTE, min)
            targetCal.set(Calendar.SECOND, 0)
            targetCal.set(Calendar.MILLISECOND, 0)
            return if (targetCal.timeInMillis < now) DateResolution.PastTime else DateResolution.Exact(targetCal.timeInMillis)
        }
        if (normalized == "this evening" || normalized == "today evening") {
            val (hr, min) = resolveDayPartWindow("evening", severity) ?: Pair(19, 0)
            val targetCal = absCal.clone() as Calendar
            targetCal.set(Calendar.HOUR_OF_DAY, hr)
            targetCal.set(Calendar.MINUTE, min)
            targetCal.set(Calendar.SECOND, 0)
            targetCal.set(Calendar.MILLISECOND, 0)
            return if (targetCal.timeInMillis < now) DateResolution.PastTime else DateResolution.Exact(targetCal.timeInMillis)
        }
        if (normalized == "this night" || normalized == "today night") {
            val (hr, min) = resolveDayPartWindow("night", severity) ?: Pair(21, 0)
            val targetCal = absCal.clone() as Calendar
            targetCal.set(Calendar.HOUR_OF_DAY, hr)
            targetCal.set(Calendar.MINUTE, min)
            targetCal.set(Calendar.SECOND, 0)
            targetCal.set(Calendar.MILLISECOND, 0)
            return if (targetCal.timeInMillis < now) DateResolution.PastTime else DateResolution.Exact(targetCal.timeInMillis)
        }

        val bareDayPart = when (normalized) {
            "morning", "in the morning" -> "morning"
            "afternoon", "in the afternoon" -> "afternoon"
            "evening", "in the evening" -> "evening"
            "night", "at night" -> "night"
            else -> null
        }
        if (bareDayPart != null) {
            val (hr, min) = resolveDayPartWindow(bareDayPart, severity) ?: Pair(9, 0)
            val targetCal = absCal.clone() as Calendar
            targetCal.set(Calendar.HOUR_OF_DAY, hr)
            targetCal.set(Calendar.MINUTE, min)
            targetCal.set(Calendar.SECOND, 0)
            targetCal.set(Calendar.MILLISECOND, 0)
            if (targetCal.timeInMillis < now) {
                targetCal.add(Calendar.DAY_OF_YEAR, 1)
            }
            return DateResolution.Exact(targetCal.timeInMillis)
        }

        if (normalized == "tomorrow morning") {
            val (hr, min) = resolveDayPartWindow("morning", severity) ?: Pair(9, 0)
            val tomCal = absCal.clone() as Calendar
            tomCal.add(Calendar.DAY_OF_YEAR, 1)
            tomCal.set(Calendar.HOUR_OF_DAY, hr)
            tomCal.set(Calendar.MINUTE, min)
            tomCal.set(Calendar.SECOND, 0)
            tomCal.set(Calendar.MILLISECOND, 0)
            return DateResolution.Exact(tomCal.timeInMillis)
        }
        if (normalized == "tomorrow afternoon") {
            val (hr, min) = resolveDayPartWindow("afternoon", severity) ?: Pair(14, 0)
            val tomCal = absCal.clone() as Calendar
            tomCal.add(Calendar.DAY_OF_YEAR, 1)
            tomCal.set(Calendar.HOUR_OF_DAY, hr)
            tomCal.set(Calendar.MINUTE, min)
            tomCal.set(Calendar.SECOND, 0)
            tomCal.set(Calendar.MILLISECOND, 0)
            return DateResolution.Exact(tomCal.timeInMillis)
        }
        if (normalized == "tomorrow evening") {
            val (hr, min) = resolveDayPartWindow("evening", severity) ?: Pair(19, 0)
            val tomCal = absCal.clone() as Calendar
            tomCal.add(Calendar.DAY_OF_YEAR, 1)
            tomCal.set(Calendar.HOUR_OF_DAY, hr)
            tomCal.set(Calendar.MINUTE, min)
            tomCal.set(Calendar.SECOND, 0)
            tomCal.set(Calendar.MILLISECOND, 0)
            return DateResolution.Exact(tomCal.timeInMillis)
        }
        if (normalized == "tomorrow night") {
            val (hr, min) = resolveDayPartWindow("night", severity) ?: Pair(21, 0)
            val tomCal = absCal.clone() as Calendar
            tomCal.add(Calendar.DAY_OF_YEAR, 1)
            tomCal.set(Calendar.HOUR_OF_DAY, hr)
            tomCal.set(Calendar.MINUTE, min)
            tomCal.set(Calendar.SECOND, 0)
            tomCal.set(Calendar.MILLISECOND, 0)
            return DateResolution.Exact(tomCal.timeInMillis)
        }

        val weekdayMatch = WEEKDAY_REGEX.find(normalized)
        if (weekdayMatch != null) {
            val prefix = weekdayMatch.groupValues[1].lowercase(Locale.US)
            if (prefix.contains("last") || prefix.contains("past")) {
                return DateResolution.PastTime
            }
            val dayName = weekdayMatch.groupValues[2].lowercase(Locale.US)
            val timePart = weekdayMatch.groupValues[3].takeIf { it.isNotBlank() }
            val targetDay = WEEKDAYS[dayName]
            if (targetDay != null) {
                val targetCal = logicalCal.clone() as Calendar
                val isSameDay = targetCal.get(Calendar.DAY_OF_WEEK) == targetDay
                val isNext = prefix.contains("next")
                if (isSameDay && !isNext) {
                    if (timePart == null) {
                        targetCal.set(Calendar.HOUR_OF_DAY, 9)
                        targetCal.set(Calendar.MINUTE, 0)
                        targetCal.set(Calendar.SECOND, 0)
                        targetCal.set(Calendar.MILLISECOND, 0)
                        if (targetCal.timeInMillis > now) {
                            return DateResolution.Exact(targetCal.timeInMillis)
                        } else {
                            do {
                                targetCal.add(Calendar.DAY_OF_YEAR, 1)
                            } while (targetCal.get(Calendar.DAY_OF_WEEK) != targetDay)
                            return DateResolution.Exact(targetCal.timeInMillis)
                        }
                    } else {
                        val parsed = parseTimePart(timePart, severity = severity) ?: return DateResolution.Failed
                        val sameDayResolution = resolveTargetTime(parsed, targetCal, now, allowPastRollForward = false, timeZone = effectiveTz)
                        val hasFutureOption = when (sameDayResolution) {
                            is DateResolution.Exact -> sameDayResolution.timeMillis > now
                            is DateResolution.NeedsClarification -> sameDayResolution.candidateMillis.any { it > now }
                            else -> false
                        }
                        if (hasFutureOption) {
                            return sameDayResolution
                        } else {
                            do {
                                targetCal.add(Calendar.DAY_OF_YEAR, 1)
                            } while (targetCal.get(Calendar.DAY_OF_WEEK) != targetDay)
                            return resolveTargetTime(parsed, targetCal, now, allowPastRollForward = false, timeZone = effectiveTz)
                        }
                    }
                } else {
                    do {
                        targetCal.add(Calendar.DAY_OF_YEAR, 1)
                    } while (targetCal.get(Calendar.DAY_OF_WEEK) != targetDay)

                    if (timePart == null) {
                        targetCal.set(Calendar.HOUR_OF_DAY, 9)
                        targetCal.set(Calendar.MINUTE, 0)
                        targetCal.set(Calendar.SECOND, 0)
                        targetCal.set(Calendar.MILLISECOND, 0)
                        return DateResolution.Exact(targetCal.timeInMillis)
                    } else {
                        val parsed = parseTimePart(timePart, severity = severity) ?: return DateResolution.Failed
                        return resolveTargetTime(parsed, targetCal, now, allowPastRollForward = false, timeZone = effectiveTz)
                    }
                }
            }
        }

        // 2b. Explicit Calendar Dates (ISO 8601 & Month-Day)
        val isoMatch = ISO_DATE_REGEX.find(normalized)
        val monthFirstMatch = if (isoMatch == null) MONTH_FIRST_REGEX.find(normalized) else null
        val dayFirstMatch = if (isoMatch == null && monthFirstMatch == null) DAY_FIRST_REGEX.find(normalized) else null

        if (isoMatch != null || monthFirstMatch != null || dayFirstMatch != null) {
            val targetCal = absCal.clone() as Calendar
            val yearGroup: String?
            val monthNum: Int
            val dayNum: Int
            val timePart: String?

            if (isoMatch != null) {
                yearGroup = isoMatch.groupValues[1]
                monthNum = (isoMatch.groupValues[2].toIntOrNull() ?: 1) - 1
                dayNum = isoMatch.groupValues[3].toIntOrNull() ?: 1
                timePart = isoMatch.groupValues[4].takeIf { it.isNotBlank() }
            } else if (monthFirstMatch != null) {
                monthNum = MONTHS[monthFirstMatch.groupValues[1].lowercase(Locale.US)] ?: 0
                dayNum = monthFirstMatch.groupValues[2].toIntOrNull() ?: 1
                yearGroup = monthFirstMatch.groupValues[3].takeIf { it.isNotBlank() }
                timePart = monthFirstMatch.groupValues[4].takeIf { it.isNotBlank() }
            } else {
                dayNum = dayFirstMatch!!.groupValues[1].toIntOrNull() ?: 1
                monthNum = MONTHS[dayFirstMatch.groupValues[2].lowercase(Locale.US)] ?: 0
                yearGroup = dayFirstMatch.groupValues[3].takeIf { it.isNotBlank() }
                timePart = dayFirstMatch.groupValues[4].takeIf { it.isNotBlank() }
            }

            val curYear = absCal.get(Calendar.YEAR)
            val resolvedYear = yearGroup?.toIntOrNull() ?: curYear
            // A lenient Calendar turns "31 nov" into 1 Dec without a word. An impossible date is
            // a question for the user, never a silent shift.
            if (!isValidDate(resolvedYear, monthNum, dayNum) &&
                !(yearGroup == null && isValidDate(curYear + 1, monthNum, dayNum))
            ) return DateResolution.Failed
            targetCal.set(Calendar.YEAR, resolvedYear)
            targetCal.set(Calendar.MONTH, monthNum)
            targetCal.set(Calendar.DAY_OF_MONTH, dayNum)

            if (timePart == null) {
                targetCal.set(Calendar.HOUR_OF_DAY, 9)
                targetCal.set(Calendar.MINUTE, 0)
                targetCal.set(Calendar.SECOND, 0)
                targetCal.set(Calendar.MILLISECOND, 0)
                if (yearGroup == null && targetCal.timeInMillis < now) {
                    targetCal.set(Calendar.YEAR, curYear + 1)
                }
                return if (targetCal.timeInMillis < now) DateResolution.PastTime else DateResolution.Exact(targetCal.timeInMillis)
            } else {
                val parsed = parseTimePart(timePart, severity = severity) ?: return DateResolution.Failed
                if (yearGroup == null) {
                    val testCal = targetCal.clone() as Calendar
                    when (val res = resolveTargetTime(parsed, testCal, now, allowPastRollForward = false, timeZone = effectiveTz)) {
                        is DateResolution.Exact -> {
                            if (res.timeMillis < now) targetCal.set(Calendar.YEAR, curYear + 1)
                        }
                        is DateResolution.NeedsClarification -> {
                            if (res.candidateMillis.all { it < now }) targetCal.set(Calendar.YEAR, curYear + 1)
                        }
                        else -> {}
                    }
                }
                return resolveTargetTime(parsed, targetCal, now, allowPastRollForward = false, timeZone = effectiveTz)
            }
        }

        // 3. Day prefix handling
        when {
            normalized.startsWith("tomorrow at ") || normalized.startsWith("tomorrow ") -> {
                val timePart = normalized.removePrefix("tomorrow at ").removePrefix("tomorrow ").trim()
                val parsed = parseTimePart(timePart, severity = severity) ?: return DateResolution.Failed
                val tomCal = absCal.clone() as Calendar
                tomCal.add(Calendar.DAY_OF_YEAR, 1)
                return resolveTargetTime(parsed, tomCal, now, allowPastRollForward = false, timeZone = effectiveTz)
            }
            normalized.startsWith("today at ") || normalized.startsWith("today ") -> {
                val timePart = normalized.removePrefix("today at ").removePrefix("today ").trim()
                val parsed = parseTimePart(timePart, severity = severity) ?: return DateResolution.Failed
                if (isLateNight) {
                    logicalCal.add(Calendar.DAY_OF_YEAR, 1)
                }
                return resolveTargetTime(parsed, logicalCal, now, allowPastRollForward = false, timeZone = effectiveTz)
            }
            normalized.startsWith("tonight at ") || normalized.startsWith("tonight ") -> {
                val timePart = normalized.removePrefix("tonight at ").removePrefix("tonight ").trim()
                val parsed = parseTimePart(timePart, defaultToEvening = true, severity = severity) ?: return DateResolution.Failed
                if (isLateNight) {
                    logicalCal.add(Calendar.DAY_OF_YEAR, 1)
                }
                if (parsed.hour == 0 || (parsed.hour < 5 && absCal.get(Calendar.HOUR_OF_DAY) >= 12)) {
                    logicalCal.add(Calendar.DAY_OF_YEAR, 1)
                }
                val allowRoll = parsed.isAmbiguousBare12
                return resolveTargetTime(parsed, logicalCal, now, allowPastRollForward = allowRoll, timeZone = effectiveTz)
            }
            else -> {
                val timePart = if (normalized.startsWith("at ")) normalized.removePrefix("at ").trim() else normalized
                val parsed = parseTimePart(timePart, severity = severity) ?: return DateResolution.Failed
                return resolveTargetTime(parsed, absCal, now, allowPastRollForward = true, timeZone = effectiveTz)
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Extended forms
    // ---------------------------------------------------------------------------------------

    private fun resolveExtended(
        text: String,
        absCal: Calendar,
        now: Long,
        tz: TimeZone,
        severity: Int?,
        rolloverMinuteOfDay: Int
    ): DateResolution? {
        // "mon 24 nov", "friday the 27th": the weekday must agree with the date, or we ask.
        WEEKDAY_WITH_DATE_REGEX.matchEntire(text)?.let { m ->
            val wanted = WEEKDAYS[m.groupValues[1]] ?: return null
            val rest = m.groupValues[2]
            return when (val r = resolveCanonical(rest, rolloverMinuteOfDay, tz, severity)) {
                is DateResolution.Exact -> if (weekdayOf(r.timeMillis, tz) == wanted) r else DateResolution.Failed
                is DateResolution.NeedsClarification -> {
                    val keep = r.candidateMillis.indices.filter { weekdayOf(r.candidateMillis[it], tz) == wanted }
                    when (keep.size) {
                        0 -> DateResolution.Failed
                        1 -> DateResolution.Exact(r.candidateMillis[keep[0]])
                        else -> DateResolution.NeedsClarification(keep.map { r.candidateMillis[it] }, keep.map { r.chipLabels[it] })
                    }
                }
                else -> r
            }
        }

        DAY_AFTER_TOMORROW_REGEX.matchEntire(text)?.let { m ->
            val cal = absCal.clone() as Calendar
            cal.add(Calendar.DAY_OF_YEAR, 2)
            return atTimeOrDefault(cal, m.groupValues[1], now, tz, severity)
        }

        IN_N_DAYS_AT_REGEX.matchEntire(text)?.let { m ->
            val n = m.groupValues[1].toIntOrNull() ?: return null
            if (n !in 1..366) return DateResolution.Failed
            val cal = absCal.clone() as Calendar
            cal.add(Calendar.DAY_OF_YEAR, n)
            return atTimeOrDefault(cal, m.groupValues[2], now, tz, severity)
        }

        NEXT_WEEK_AT_REGEX.matchEntire(text)?.let { m ->
            val cal = absCal.clone() as Calendar
            cal.add(Calendar.DAY_OF_YEAR, 7)
            return atTimeOrDefault(cal, m.groupValues[1], now, tz, severity)
        }

        NEXT_MONTH_REGEX.matchEntire(text)?.let { m ->
            val cal = absCal.clone() as Calendar
            cal.add(Calendar.MONTH, 1)
            return atTimeOrDefault(cal, m.groupValues[1], now, tz, severity)
        }

        // "the 24th", "24th at 5pm": this month if still ahead, otherwise the next month that has that day.
        ORDINAL_DAY_REGEX.matchEntire(text)?.let { m ->
            val day = m.groupValues[1].toIntOrNull() ?: return null
            if (day !in 1..31) return DateResolution.Failed
            val timePart = m.groupValues[2].takeIf { it.isNotBlank() }
            val parsed = timePart?.let { parseTimePart(it, severity = severity) ?: return DateResolution.Failed }
            for (ahead in 0..2) {
                val cal = absCal.clone() as Calendar
                cal.set(Calendar.DAY_OF_MONTH, 1)
                cal.add(Calendar.MONTH, ahead)
                if (!isValidDate(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), day)) continue
                cal.set(Calendar.DAY_OF_MONTH, day)
                val r = if (parsed == null) defaultMorning(cal, now)
                else resolveTargetTime(parsed, cal, now, allowPastRollForward = false, timeZone = tz)
                if (r is DateResolution.Exact || r is DateResolution.NeedsClarification) return r
            }
            return DateResolution.PastTime
        }

        NUMERIC_DATE_REGEX.matchEntire(text)?.let { m ->
            return resolveNumericDate(m, absCal, now, tz, severity)
        }

        return null
    }

    private fun atTimeOrDefault(cal: Calendar, timePart: String, now: Long, tz: TimeZone, severity: Int?): DateResolution {
        if (timePart.isBlank()) return defaultMorning(cal, now)
        val parsed = parseTimePart(timePart.trim(), severity = severity) ?: return DateResolution.Failed
        return resolveTargetTime(parsed, cal, now, allowPastRollForward = false, timeZone = tz)
    }

    private fun defaultMorning(cal: Calendar, now: Long): DateResolution {
        val c = cal.clone() as Calendar
        c.set(Calendar.HOUR_OF_DAY, 9)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        return if (c.timeInMillis < now) DateResolution.PastTime else DateResolution.Exact(c.timeInMillis)
    }

    /**
     * "24/11", "24/11/2026", "2026/11/24", "24-11-26", "24.11.2026". When both readings are
     * possible ("5/6"), both dates are offered as chips: the resolver never picks a locale.
     */
    private fun resolveNumericDate(m: MatchResult, absCal: Calendar, now: Long, tz: TimeZone, severity: Int?): DateResolution {
        val a = m.groupValues[1]
        val b = m.groupValues[2].toIntOrNull() ?: return DateResolution.Failed
        val c = m.groupValues[3].takeIf { it.isNotBlank() }
        val timePart = m.groupValues[4].takeIf { it.isNotBlank() }
        val curYear = absCal.get(Calendar.YEAR)

        val readings = mutableListOf<Triple<Int?, Int, Int>>() // (year or null, month0, day)
        if (a.length == 4) {
            val d = c?.toIntOrNull() ?: return DateResolution.Failed
            readings += Triple(a.toInt(), b - 1, d)
        } else {
            val x = a.toIntOrNull() ?: return DateResolution.Failed
            val year = c?.let { y -> y.toInt().let { if (y.length == 2) 2000 + it else it } }
            when {
                x > 12 && b in 1..12 -> readings += Triple(year, b - 1, x)
                b > 12 && x in 1..12 -> readings += Triple(year, x - 1, b)
                x in 1..12 && b in 1..12 -> {
                    readings += Triple(year, b - 1, x)            // day/month first (as written in most of the world)
                    if (x != b) readings += Triple(year, x - 1, b) // month/day
                }
                else -> return DateResolution.Failed
            }
        }

        val parsed = timePart?.let { parseTimePart(it, severity = severity) ?: return DateResolution.Failed }
        val candidates = mutableListOf<Long>()
        for ((year, month0, day) in readings) {
            val years = if (year != null) listOf(year) else listOf(curYear, curYear + 1)
            for (y in years) {
                if (!isValidDate(y, month0, day)) continue
                val cal = absCal.clone() as Calendar
                cal.set(Calendar.DAY_OF_MONTH, 1)
                cal.set(Calendar.YEAR, y)
                cal.set(Calendar.MONTH, month0)
                cal.set(Calendar.DAY_OF_MONTH, day)
                val r = if (parsed == null) defaultMorning(cal, now)
                else resolveTargetTime(parsed, cal, now, allowPastRollForward = false, timeZone = tz)
                val found = when (r) {
                    is DateResolution.Exact -> listOf(r.timeMillis)
                    is DateResolution.NeedsClarification -> r.candidateMillis.filter { it > now }
                    else -> emptyList()
                }
                if (found.isNotEmpty()) {
                    candidates += found
                    break // the nearest year that works; never also offer the year after
                }
            }
        }
        val distinct = candidates.distinct().sorted().take(4)
        return when (distinct.size) {
            0 -> DateResolution.PastTime
            1 -> DateResolution.Exact(distinct[0])
            else -> DateResolution.NeedsClarification(distinct, distinct.map { formatChipLabel(it, now, tz) })
        }
    }

    /** "2026-11-24T15:40:00Z", "2026-11-24T21:10+05:30", or without a zone (read in the user's zone). */
    private fun resolveIsoInstant(text: String, tz: TimeZone): DateResolution? {
        val m = ISO_INSTANT_REGEX.matchEntire(text) ?: return null
        val millis = try {
            val upper = text.uppercase(Locale.ROOT)
            if (m.groupValues[2].isNotEmpty()) {
                val withColon = upper.replace(Regex("""([+-]\d{2})(\d{2})$"""), "$1:$2")
                java.time.OffsetDateTime.parse(withColon).toInstant().toEpochMilli()
            } else {
                java.time.LocalDateTime.parse(upper.let { if (m.groupValues[1].isEmpty()) "$it:00" else it })
                    .atZone(tz.toZoneId()).toInstant().toEpochMilli()
            }
        } catch (_: Exception) {
            return DateResolution.Failed
        }
        return if (millis < clock.now()) DateResolution.PastTime else DateResolution.Exact(millis)
    }

    private fun isValidDate(year: Int, month0: Int, day: Int): Boolean {
        if (month0 !in 0..11 || day < 1) return false
        val c = Calendar.getInstance()
        c.clear()
        c.set(Calendar.YEAR, year)
        c.set(Calendar.MONTH, month0)
        return day <= c.getActualMaximum(Calendar.DAY_OF_MONTH)
    }

    private fun weekdayOf(millis: Long, tz: TimeZone): Int =
        Calendar.getInstance(tz).apply { timeInMillis = millis }.get(Calendar.DAY_OF_WEEK)

    private data class ParsedTime(
        val hour: Int,
        val minute: Int,
        val isAmbiguousBare12: Boolean
    )

    private fun parseTimePart(timeStr: String, defaultToEvening: Boolean = false, severity: Int? = null): ParsedTime? {
        val text = timeStr.trim().lowercase(Locale.US)

        // Pattern 0: named day-part words ("morning", "afternoon", "evening", "night", "in the morning", etc.)
        when (text) {
            "morning", "in the morning" -> {
                val (hr, min) = resolveDayPartWindow("morning", severity) ?: Pair(9, 0)
                return ParsedTime(hr, min, isAmbiguousBare12 = false)
            }
            "afternoon", "in the afternoon" -> {
                val (hr, min) = resolveDayPartWindow("afternoon", severity) ?: Pair(14, 0)
                return ParsedTime(hr, min, isAmbiguousBare12 = false)
            }
            "evening", "in the evening" -> {
                val (hr, min) = resolveDayPartWindow("evening", severity) ?: Pair(19, 0)
                return ParsedTime(hr, min, isAmbiguousBare12 = false)
            }
            "night", "at night" -> {
                val (hr, min) = resolveDayPartWindow("night", severity) ?: Pair(21, 0)
                return ParsedTime(hr, min, isAmbiguousBare12 = false)
            }
        }

        // Pattern 1: explicit am/pm
        AM_PM_REGEX.find(text)?.let { m ->
            var hr = m.groupValues[1].toInt()
            val min = m.groupValues[2].takeIf { it.isNotEmpty() }?.toInt() ?: 0
            val ampm = m.groupValues[3]
            if (hr !in 1..12 || min !in 0..59) return null
            if (hr == 12 && ampm == "am") hr = 0
            else if (hr < 12 && ampm == "pm") hr += 12
            return ParsedTime(hr, min, isAmbiguousBare12 = false)
        }

        // Pattern 2: day-part words ("3 in the evening", "3 in the morning", "3 in the afternoon", "3 at night")
        DAY_PART_REGEX.find(text)?.let { m ->
            var hr = m.groupValues[1].toInt()
            val min = m.groupValues[2].takeIf { it.isNotEmpty() }?.toInt() ?: 0
            val part = m.groupValues[3]
            if (hr !in 1..12 || min !in 0..59) return null

            when (part) {
                "night" -> {
                    when (hr) {
                        12 -> return ParsedTime(0, min, isAmbiguousBare12 = false)
                        in 6..11 -> return ParsedTime(hr + 12, min, isAmbiguousBare12 = false)
                        else -> return ParsedTime(hr, min, isAmbiguousBare12 = true)
                    }
                }
                "evening" -> {
                    when (hr) {
                        12 -> return ParsedTime(12, min, isAmbiguousBare12 = true)
                        else -> return ParsedTime(if (hr < 12) hr + 12 else hr, min, isAmbiguousBare12 = false)
                    }
                }
                "afternoon" -> {
                    when (hr) {
                        12 -> return ParsedTime(12, min, isAmbiguousBare12 = true)
                        in 1..5 -> return ParsedTime(hr + 12, min, isAmbiguousBare12 = false)
                        else -> return ParsedTime(hr, min, isAmbiguousBare12 = true)
                    }
                }
                "morning" -> {
                    when (hr) {
                        12 -> return ParsedTime(0, min, isAmbiguousBare12 = false)
                        else -> return ParsedTime(hr, min, isAmbiguousBare12 = false)
                    }
                }
                else -> return null
            }
        }

        // Pattern 3: "15h" or "15h30"
        H_REGEX.find(text)?.let { m ->
            val hr = m.groupValues[1].toInt()
            val min = m.groupValues[2].takeIf { it.isNotEmpty() }?.toInt() ?: 0
            if (hr !in 0..23 || min !in 0..59) return null
            return ParsedTime(hr, min, isAmbiguousBare12 = false)
        }

        // Pattern 4: 4-digit compact 24-hour ("1530", "0030")
        val cleanDigits = if (text.startsWith("at ")) text.removePrefix("at ").trim() else text
        if (cleanDigits.length == 4 && cleanDigits.all { it.isDigit() }) {
            val hr = cleanDigits.substring(0, 2).toInt()
            val min = cleanDigits.substring(2, 4).toInt()
            if (hr in 0..23 && min in 0..59) {
                return ParsedTime(hr, min, isAmbiguousBare12 = false)
            }
        }

        // Pattern 5: colon format "HH:mm" or "H:mm"
        COLON_REGEX.find(text)?.let { m ->
            val hr = m.groupValues[1].toInt()
            val min = m.groupValues[2].toInt()
            if (hr !in 0..23 || min !in 0..59) return null
            if (defaultToEvening) {
                return when (hr) {
                    12 -> ParsedTime(0, min, isAmbiguousBare12 = false)
                    in 6..11 -> ParsedTime(hr + 12, min, isAmbiguousBare12 = false)
                    in 1..5 -> ParsedTime(hr, min, isAmbiguousBare12 = true)
                    else -> ParsedTime(hr, min, isAmbiguousBare12 = false)
                }
            }
            if (hr == 0 || hr in 13..23) {
                return ParsedTime(hr, min, isAmbiguousBare12 = false)
            }
            return ParsedTime(hr, min, isAmbiguousBare12 = true)
        }

        // Pattern 6: bare hour "15", "5", etc.
        BARE_HOUR_REGEX.find(text)?.let { m ->
            val hr = m.groupValues[1].toInt()
            if (hr !in 0..23) return null
            if (defaultToEvening) {
                return when (hr) {
                    12 -> ParsedTime(0, 0, isAmbiguousBare12 = false)
                    in 6..11 -> ParsedTime(hr + 12, 0, isAmbiguousBare12 = false)
                    in 1..5 -> ParsedTime(hr, 0, isAmbiguousBare12 = true)
                    else -> ParsedTime(hr, 0, isAmbiguousBare12 = false)
                }
            }
            if (hr == 0 || hr in 13..23) {
                return ParsedTime(hr, 0, isAmbiguousBare12 = false)
            }
            return ParsedTime(hr, 0, isAmbiguousBare12 = true)
        }

        return null
    }

    private fun resolveTargetTime(
        parsed: ParsedTime,
        baseCal: Calendar,
        now: Long,
        allowPastRollForward: Boolean,
        timeZone: TimeZone = this.timeZone
    ): DateResolution {
        if (!parsed.isAmbiguousBare12) {
            val targetCal = baseCal.clone() as Calendar
            targetCal.set(Calendar.HOUR_OF_DAY, parsed.hour)
            targetCal.set(Calendar.MINUTE, parsed.minute)
            targetCal.set(Calendar.SECOND, 0)
            targetCal.set(Calendar.MILLISECOND, 0)

            if (targetCal.timeInMillis < now) {
                if (allowPastRollForward) {
                    targetCal.add(Calendar.DAY_OF_YEAR, 1)
                } else {
                    return DateResolution.PastTime
                }
            }
            return DateResolution.Exact(targetCal.timeInMillis)
        } else {
            val hrAm = if (parsed.hour == 12) 0 else parsed.hour
            val hrPm = if (parsed.hour == 12) 12 else parsed.hour + 12

            val calAm = baseCal.clone() as Calendar
            calAm.set(Calendar.HOUR_OF_DAY, hrAm)
            calAm.set(Calendar.MINUTE, parsed.minute)
            calAm.set(Calendar.SECOND, 0)
            calAm.set(Calendar.MILLISECOND, 0)

            val calPm = baseCal.clone() as Calendar
            calPm.set(Calendar.HOUR_OF_DAY, hrPm)
            calPm.set(Calendar.MINUTE, parsed.minute)
            calPm.set(Calendar.SECOND, 0)
            calPm.set(Calendar.MILLISECOND, 0)

            if (allowPastRollForward) {
                if (calAm.timeInMillis < now) calAm.add(Calendar.DAY_OF_YEAR, 1)
                if (calPm.timeInMillis < now) calPm.add(Calendar.DAY_OF_YEAR, 1)
            } else {
                val amPassed = calAm.timeInMillis < now
                val pmPassed = calPm.timeInMillis < now
                if (amPassed && pmPassed) return DateResolution.PastTime
                if (amPassed) return DateResolution.Exact(calPm.timeInMillis)
            }

            val chipLabels = listOf(
                formatTimeOfDay(calAm.timeInMillis, timeZone),
                formatTimeOfDay(calPm.timeInMillis, timeZone)
            )
            return DateResolution.NeedsClarification(
                candidateMillis = listOf(calAm.timeInMillis, calPm.timeInMillis),
                chipLabels = chipLabels
            )
        }
    }

    fun formatTimeOnly(timeMillis: Long, timeZone: TimeZone? = null): String {
        return formatChipLabel(timeMillis, timeZone = timeZone)
    }

    fun formatChipLabel(timeMillis: Long, now: Long = clock.now(), timeZone: TimeZone? = null): String {
        val effectiveTz = timeZone ?: this.timeZone
        val calNow = Calendar.getInstance(effectiveTz).apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val calTarget = Calendar.getInstance(effectiveTz).apply {
            timeInMillis = timeMillis
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val timeStr = formatTimeOfDay(timeMillis, effectiveTz)
        val diffDays = ((calTarget.timeInMillis - calNow.timeInMillis) / 86_400_000L).toInt()
        return when {
            diffDays == 1 -> "Tomorrow $timeStr"
            diffDays in 2..6 -> {
                val dayName = SimpleDateFormat("EEE", Locale.US).apply { this.timeZone = effectiveTz }.format(timeMillis)
                "$dayName $timeStr"
            }
            diffDays > 6 -> {
                val dateStr = SimpleDateFormat("EEE d MMM", Locale.US).apply { this.timeZone = effectiveTz }.format(timeMillis)
                "$dateStr, $timeStr"
            }
            else -> timeStr
        }
    }

    private fun normalizeDigits(input: String): String {
        val sb = StringBuilder(input.length)
        var i = 0
        while (i < input.length) {
            val codePoint = input.codePointAt(i)
            val digit = Character.digit(codePoint, 10)
            if (digit in 0..9) {
                sb.append(digit)
            } else {
                sb.appendCodePoint(codePoint)
            }
            i += Character.charCount(codePoint)
        }
        return sb.toString()
    }

    fun getLogicalDayStart(timeMillis: Long, rolloverMinuteOfDay: Int, timeZone: TimeZone? = null): Long {
        val effectiveTz = timeZone ?: this.timeZone
        val cal = Calendar.getInstance(effectiveTz).apply { timeInMillis = timeMillis }
        val currentMin = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        if (currentMin < rolloverMinuteOfDay) {
            cal.add(Calendar.DAY_OF_YEAR, -1)
        }
        cal.set(Calendar.HOUR_OF_DAY, rolloverMinuteOfDay / 60)
        cal.set(Calendar.MINUTE, rolloverMinuteOfDay % 60)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    fun format(timeMillis: Long, timeZone: TimeZone? = null): String {
        val effectiveTz = timeZone ?: this.timeZone
        val pattern = if (is24HourFormat()) "EEE d MMM, HH:mm" else "EEE d MMM, h:mm a"
        return SimpleDateFormat(pattern, Locale.US).apply { this.timeZone = effectiveTz }.format(timeMillis)
    }

    fun formatTimeOfDay(timeMillis: Long, timeZone: TimeZone? = null): String {
        val effectiveTz = timeZone ?: this.timeZone
        val pattern = if (is24HourFormat()) "HH:mm" else "h:mm a"
        return SimpleDateFormat(pattern, Locale.US).apply { this.timeZone = effectiveTz }.format(timeMillis)
    }
}
