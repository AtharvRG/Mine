// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.time

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class DateResolver(private val clock: Clock) {
    fun parse(dueText: String?, rolloverMinuteOfDay: Int = 240): Long? {
        if (dueText == null) return null
        val text = dueText.lowercase(Locale.getDefault()).trim()
        val now = clock.now()
        
        // Absolute calendar base
        val absCal = Calendar.getInstance().apply { 
            timeInMillis = now 
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        
        // B14: Use dynamic rolloverMinuteOfDay instead of hardcoded 4AM
        val currentMinuteOfDay = absCal.get(Calendar.HOUR_OF_DAY) * 60 + absCal.get(Calendar.MINUTE)
        val isLateNight = currentMinuteOfDay < rolloverMinuteOfDay
        
        val logicalCal = absCal.clone() as Calendar
        if (isLateNight) logicalCal.add(Calendar.DAY_OF_YEAR, -1)
        
        if (text == "in an hour" || text == "in 1 hr" || text == "in 1 hour") return now + 3_600_000L
        if (text == "in half an hour" || text == "in 30 mins") return now + 1_800_000L
        Regex("""^in (\d+)\s*(min|minute|minutes|mins)$""").find(text)?.let { return now + it.groupValues[1].toLong() * 60_000L }
        Regex("""^in (\d+)\s*(hr|hour|hours)$""").find(text)?.let { return now + it.groupValues[1].toLong() * 3_600_000L }
        Regex("""^in (\d+)\s*(day|days)$""").find(text)?.let { return now + it.groupValues[1].toLong() * 86_400_000L }

        val timeRegex = Regex("""(\d{1,2})(?::(\d{2}))?\s*(am|pm)""")
        
        if (text == "tomorrow") {
            logicalCal.add(Calendar.DAY_OF_YEAR, 1)
            logicalCal.set(Calendar.HOUR_OF_DAY, 9)
            logicalCal.set(Calendar.MINUTE, 0)
            return logicalCal.timeInMillis
        }
        if (text == "tonight") {
            // "Tonight" means 21:00 of the logical day
            logicalCal.set(Calendar.HOUR_OF_DAY, 21)
            logicalCal.set(Calendar.MINUTE, 0)
            if (logicalCal.timeInMillis < now) logicalCal.add(Calendar.DAY_OF_YEAR, 1)
            return logicalCal.timeInMillis
        }
        if (text == "next monday") {
            do { logicalCal.add(Calendar.DAY_OF_YEAR, 1) } while (logicalCal.get(Calendar.DAY_OF_WEEK) != Calendar.MONDAY)
            logicalCal.set(Calendar.HOUR_OF_DAY, 9)
            logicalCal.set(Calendar.MINUTE, 0)
            return logicalCal.timeInMillis
        }
        
        if (text.startsWith("tomorrow at ") || text.startsWith("tomorrow ")) {
            val match = timeRegex.find(text) ?: return null 
            var hr = match.groupValues[1].toInt()
            val min = match.groupValues[2].takeIf { it.isNotEmpty() }?.toInt() ?: 0
            val ampm = match.groupValues[3]
            
            // B14: Validate HR/MIN boundaries
            if (hr !in 1..12 || min !in 0..59) return null

            if (hr == 12 && ampm == "am") hr = 0
            else if (hr < 12 && ampm == "pm") hr += 12
            
            logicalCal.add(Calendar.DAY_OF_YEAR, 1)
            logicalCal.set(Calendar.HOUR_OF_DAY, hr)
            logicalCal.set(Calendar.MINUTE, min)
            return logicalCal.timeInMillis
        }
        
        if (text.startsWith("today at ") || text.startsWith("today ")) {
            val match = timeRegex.find(text) ?: return null
            var hr = match.groupValues[1].toInt()
            val min = match.groupValues[2].takeIf { it.isNotEmpty() }?.toInt() ?: 0
            val ampm = match.groupValues[3]
            
            // B14: Validate HR/MIN boundaries
            if (hr !in 1..12 || min !in 0..59) return null

            if (hr == 12 && ampm == "am") hr = 0
            else if (hr < 12 && ampm == "pm") hr += 12
            
            logicalCal.set(Calendar.HOUR_OF_DAY, hr)
            logicalCal.set(Calendar.MINUTE, min)
            
            // If it's 2am, and they say "today at 7pm", logicalCal mapped to yesterday. 
            // We must push it forward to the absolute today if it passed.
            if (isLateNight && logicalCal.timeInMillis < now) {
                logicalCal.add(Calendar.DAY_OF_YEAR, 1)
            }
            if (logicalCal.timeInMillis < now) return null // Genuinely in the past
            return logicalCal.timeInMillis
        }
        
        if (text.startsWith("at ") || timeRegex.matches(text)) {
            val match = timeRegex.find(text) ?: return null
            var hr = match.groupValues[1].toInt()
            val min = match.groupValues[2].takeIf { it.isNotEmpty() }?.toInt() ?: 0
            val ampm = match.groupValues[3]
            
            // B14: Validate HR/MIN boundaries
            if (hr !in 1..12 || min !in 0..59) return null

            if (hr == 12 && ampm == "am") hr = 0
            else if (hr < 12 && ampm == "pm") hr += 12
            
            absCal.set(Calendar.HOUR_OF_DAY, hr)
            absCal.set(Calendar.MINUTE, min)
            if (absCal.timeInMillis < now) absCal.add(Calendar.DAY_OF_YEAR, 1) 
            return absCal.timeInMillis
        }
        
        return null // Never guess
    }

    fun format(timeMillis: Long): String = SimpleDateFormat("EEE d MMM h:mm a", Locale.getDefault()).format(timeMillis)
}