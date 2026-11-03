// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.time

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

sealed class DateResolution {
    data class Exact(val timeMillis: Long) : DateResolution()
    data class NeedsClarification(val candidateMillis: List<Long>, val chipLabels: List<String>) : DateResolution()
    object Failed : DateResolution()
}

class DateResolver(
    private val clock: Clock,
    private val is24HourFormat: () -> Boolean = { false }
) {
    companion object {
        private val PREFIX_RE = Regex("""^(?:in|after|within)\s+""")
        private val UNIT_RE = Regex("""(\d+)\s*(seconds?|secs?|minutes?|mins?|hours?|hrs?|days?|s|m|h|d)\b""")
        private val AM_PM_REGEX = Regex("""^(?:at\s+)?(\d{1,2})(?::(\d{2}))?\s*(am|pm)$""")
        private val DAY_PART_REGEX = Regex("""^(?:at\s+)?(\d{1,2})(?::(\d{2}))?\s*(?:in the\s+|at\s+)?(morning|afternoon|evening|night)$""")
        private val H_REGEX = Regex("""^(?:at\s+)?(\d{1,2})h(\d{2})?$""")
        private val COLON_REGEX = Regex("""^(?:at\s+)?(\d{1,2}):(\d{2})$""")
        private val BARE_HOUR_REGEX = Regex("""^(?:at\s+)?(\d{1,2})$""")
    }

    fun parseDuration(text: String?): Long? {
        if (text == null) return null
        val normalized = normalizeDigits(text).lowercase(Locale.US).trim()
        if (normalized.isBlank()) return null
        if (normalized == "in a minute" || normalized == "in a min" || normalized == "in 1 min" || normalized == "in 1 minute") return 60_000L
        if (normalized == "in an hour" || normalized == "in 1 hr" || normalized == "in 1 hour") return 3_600_000L
        if (normalized == "in half an hour" || normalized == "in 30 mins") return 1_800_000L

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
            total += amount * when (unit[0]) {
                's' -> 1_000L; 'm' -> 60_000L; 'h' -> 3_600_000L; else -> 86_400_000L
            }
        }
        return if (total > 0) total else null
    }

    fun parse(dueText: String?, rolloverMinuteOfDay: Int = 240): Long? {
        return (resolve(dueText, rolloverMinuteOfDay) as? DateResolution.Exact)?.timeMillis
    }

    fun resolve(dueText: String?, rolloverMinuteOfDay: Int = 240): DateResolution {
        if (dueText == null) return DateResolution.Failed
        val normalized = normalizeDigits(dueText).lowercase(Locale.getDefault()).trim()
        if (normalized.isBlank()) return DateResolution.Failed

        val now = clock.now()
        val absCal = Calendar.getInstance().apply {
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

        // 2. Named dates without time
        if (normalized == "tomorrow") {
            logicalCal.add(Calendar.DAY_OF_YEAR, 1)
            logicalCal.set(Calendar.HOUR_OF_DAY, 9)
            logicalCal.set(Calendar.MINUTE, 0)
            return DateResolution.Exact(logicalCal.timeInMillis)
        }
        if (normalized == "tonight") {
            logicalCal.set(Calendar.HOUR_OF_DAY, 21)
            logicalCal.set(Calendar.MINUTE, 0)
            if (logicalCal.timeInMillis < now) logicalCal.add(Calendar.DAY_OF_YEAR, 1)
            return DateResolution.Exact(logicalCal.timeInMillis)
        }
        if (normalized == "next monday") {
            do { logicalCal.add(Calendar.DAY_OF_YEAR, 1) } while (logicalCal.get(Calendar.DAY_OF_WEEK) != Calendar.MONDAY)
            logicalCal.set(Calendar.HOUR_OF_DAY, 9)
            logicalCal.set(Calendar.MINUTE, 0)
            return DateResolution.Exact(logicalCal.timeInMillis)
        }

        // 3. Day prefix handling
        when {
            normalized.startsWith("tomorrow at ") || normalized.startsWith("tomorrow ") -> {
                val timePart = normalized.removePrefix("tomorrow at ").removePrefix("tomorrow ").trim()
                val parsed = parseTimePart(timePart) ?: return DateResolution.Failed
                logicalCal.add(Calendar.DAY_OF_YEAR, 1)
                return resolveTargetTime(parsed, logicalCal, now, allowPastRollForward = false)
            }
            normalized.startsWith("today at ") || normalized.startsWith("today ") -> {
                val timePart = normalized.removePrefix("today at ").removePrefix("today ").trim()
                val parsed = parseTimePart(timePart) ?: return DateResolution.Failed
                if (isLateNight) {
                    logicalCal.add(Calendar.DAY_OF_YEAR, 1)
                }
                return resolveTargetTime(parsed, logicalCal, now, allowPastRollForward = false)
            }
            normalized.startsWith("tonight at ") || normalized.startsWith("tonight ") -> {
                val timePart = normalized.removePrefix("tonight at ").removePrefix("tonight ").trim()
                val parsed = parseTimePart(timePart, defaultToEvening = true) ?: return DateResolution.Failed
                if (isLateNight) {
                    logicalCal.add(Calendar.DAY_OF_YEAR, 1)
                }
                if (parsed.hour == 0) {
                    logicalCal.add(Calendar.DAY_OF_YEAR, 1)
                }
                return resolveTargetTime(parsed, logicalCal, now, allowPastRollForward = false)
            }
            else -> {
                val timePart = if (normalized.startsWith("at ")) normalized.removePrefix("at ").trim() else normalized
                val parsed = parseTimePart(timePart) ?: return DateResolution.Failed
                return resolveTargetTime(parsed, absCal, now, allowPastRollForward = true)
            }
        }
    }

    private data class ParsedTime(
        val hour: Int,
        val minute: Int,
        val isAmbiguousBare12: Boolean
    )

    private fun parseTimePart(timeStr: String, defaultToEvening: Boolean = false): ParsedTime? {
        val text = timeStr.trim()

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
            if (part == "morning" || (part == "night" && hr == 12)) {
                if (hr == 12) hr = 0
            } else {
                if (hr < 12) hr += 12
            }
            return ParsedTime(hr, min, isAmbiguousBare12 = false)
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
                val finalHr = if (hr == 12) 0 else if (hr < 12) hr + 12 else hr
                return ParsedTime(finalHr, min, isAmbiguousBare12 = false)
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
                val finalHr = if (hr == 12) 0 else if (hr < 12) hr + 12 else hr
                return ParsedTime(finalHr, 0, isAmbiguousBare12 = false)
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
        allowPastRollForward: Boolean
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
                    return DateResolution.Failed
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
                if (amPassed && pmPassed) return DateResolution.Failed
                if (amPassed) return DateResolution.Exact(calPm.timeInMillis)
            }

            val chipLabels = listOf(
                String.format(Locale.US, "%02d:%02d", hrAm, parsed.minute),
                String.format(Locale.US, "%02d:%02d", hrPm, parsed.minute)
            )
            return DateResolution.NeedsClarification(
                candidateMillis = listOf(calAm.timeInMillis, calPm.timeInMillis),
                chipLabels = chipLabels
            )
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

    fun getLogicalDayStart(timeMillis: Long, rolloverMinuteOfDay: Int): Long {
        val cal = Calendar.getInstance().apply { timeInMillis = timeMillis }
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

    fun format(timeMillis: Long): String {
        val pattern = if (is24HourFormat()) "EEE d MMM, HH:mm" else "EEE d MMM, h:mm a"
        return SimpleDateFormat(pattern, Locale.US).format(timeMillis)
    }

    fun formatTimeOfDay(timeMillis: Long): String {
        val pattern = if (is24HourFormat()) "HH:mm" else "h:mm a"
        return SimpleDateFormat(pattern, Locale.US).format(timeMillis)
    }
}
