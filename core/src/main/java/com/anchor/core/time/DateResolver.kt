// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.time

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class DateResolver(private val clock: Clock) {
    fun parse(dueText: String?): Long? {
        if (dueText == null) return null
        val text = dueText.lowercase(Locale.getDefault()).trim()
        val now = clock.now()

        val cal = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        // 4am rollover
        if (cal.get(Calendar.HOUR_OF_DAY) < 4) cal.add(Calendar.DAY_OF_YEAR, -1)

        if (text == "in an hour") return now + 3_600_000L
        if (text == "in half an hour") return now + 1_800_000L
        Regex("""^in (\d+)\s*(min|minute|minutes)$""").find(text)?.let { return now + it.groupValues[1].toLong() * 60_000L }
        Regex("""^in (\d+)\s*(hour|hours)$""").find(text)?.let { return now + it.groupValues[1].toLong() * 3_600_000L }
        Regex("""^in (\d+)\s*(day|days)$""").find(text)?.let { return now + it.groupValues[1].toLong() * 86_400_000L }

        val timeRegex = Regex("""(\d{1,2})(?::(\d{2}))?\s*(am|pm)""")

        if (text == "tomorrow") {
            cal.add(Calendar.DAY_OF_YEAR, 1)
            cal.set(Calendar.HOUR_OF_DAY, 9)
            cal.set(Calendar.MINUTE, 0)
            return cal.timeInMillis
        }
        if (text == "tonight") {
            cal.set(Calendar.HOUR_OF_DAY, 21) // 9pm
            cal.set(Calendar.MINUTE, 0)
            return cal.timeInMillis
        }
        if (text == "next monday") {
            do { cal.add(Calendar.DAY_OF_YEAR, 1) } while (cal.get(Calendar.DAY_OF_WEEK) != Calendar.MONDAY)
            cal.set(Calendar.HOUR_OF_DAY, 9)
            cal.set(Calendar.MINUTE, 0)
            return cal.timeInMillis
        }

        if (text.startsWith("tomorrow at ") || text.startsWith("tomorrow ")) {
            val match = timeRegex.find(text) ?: return null // Requires am/pm
            var hr = match.groupValues[1].toInt()
            val min = match.groupValues[2].takeIf { it.isNotEmpty() }?.toInt() ?: 0
            val ampm = match.groupValues[3]

            if (hr == 12 && ampm == "am") hr = 0
            else if (hr < 12 && ampm == "pm") hr += 12

            cal.add(Calendar.DAY_OF_YEAR, 1)
            cal.set(Calendar.HOUR_OF_DAY, hr)
            cal.set(Calendar.MINUTE, min)
            return cal.timeInMillis
        }

        if (text.startsWith("today at ") || text.startsWith("today ")) {
            val match = timeRegex.find(text) ?: return null
            var hr = match.groupValues[1].toInt()
            val min = match.groupValues[2].takeIf { it.isNotEmpty() }?.toInt() ?: 0
            val ampm = match.groupValues[3]

            if (hr == 12 && ampm == "am") hr = 0
            else if (hr < 12 && ampm == "pm") hr += 12

            cal.set(Calendar.HOUR_OF_DAY, hr)
            cal.set(Calendar.MINUTE, min)

            // "today" with a time already past returns an error (null), do not auto-shift to tomorrow.
            if (cal.timeInMillis < now) return null
            return cal.timeInMillis
        }

        if (text.startsWith("at ") || timeRegex.matches(text)) {
            val match = timeRegex.find(text) ?: return null
            var hr = match.groupValues[1].toInt()
            val min = match.groupValues[2].takeIf { it.isNotEmpty() }?.toInt() ?: 0
            val ampm = match.groupValues[3]

            if (hr == 12 && ampm == "am") hr = 0
            else if (hr < 12 && ampm == "pm") hr += 12

            cal.set(Calendar.HOUR_OF_DAY, hr)
            cal.set(Calendar.MINUTE, min)
            if (cal.timeInMillis < now) cal.add(Calendar.DAY_OF_YEAR, 1) // "at 5pm" shifts to tomorrow if passed
            return cal.timeInMillis
        }

        return null // Never guess
    }

    fun format(timeMillis: Long): String = SimpleDateFormat("EEE d MMM h:mm a", Locale.getDefault()).format(timeMillis)
}