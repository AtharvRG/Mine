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
        
        // Absolute calendar base
        val absCal = Calendar.getInstance().apply { 
            timeInMillis = now 
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        
        // Logical user day base (4am rollover for words like "tomorrow", "tonight")
        val isLateNight = absCal.get(Calendar.HOUR_OF_DAY) < 4
        val logicalCal = absCal.clone() as Calendar
        if (isLateNight) logicalCal.add(Calendar.DAY_OF_YEAR, -1)
        
        if (text == "in an hour") return now + 3_600_000L
        if (text == "in half an hour") return now + 1_800_000L
        Regex("""^in (\d+)\s*(min|minute|minutes)$""").find(text)?.let { return now + it.groupValues[1].toLong() * 60_000L }
        Regex("""^in (\d+)\s*(hour|hours)$""").find(text)?.let { return now + it.groupValues[1].toLong() * 3_600_000L }
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