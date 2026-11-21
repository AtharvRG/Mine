// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.recurrence

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

data class StreakResult(
    val currentStreak: Int,
    val bestStreak: Int,
    val isContinued: Boolean
)

object RecurrenceEngine {

    private const val MS_IN_HOUR = 3_600_000L
    private const val MS_IN_DAY = 86_400_000L

    /**
     * Calculates the next occurrence timestamp in milliseconds for a recurrence rule,
     * given the base time and the current instant.
     * Guaranteed to return a timestamp strictly in the future (> now).
     */
    fun nextOccurrence(
        rrule: String,
        baseTimeMillis: Long,
        now: Long,
        timeZone: TimeZone = TimeZone.getDefault()
    ): Long {
        val ruleUpper = rrule.trim().uppercase(Locale.ROOT)
        val interval = parseInterval(ruleUpper)

        val cal = Calendar.getInstance(timeZone).apply {
            timeInMillis = baseTimeMillis
        }

        val byDayMatch = Regex("""BYDAY=([A-Z,]+)""").find(ruleUpper)
        val targetDays: Set<Int>? = if (byDayMatch != null) {
            byDayMatch.groupValues[1].split(",").mapNotNull {
                when (it.trim()) {
                    "SU" -> Calendar.SUNDAY
                    "MO" -> Calendar.MONDAY
                    "TU" -> Calendar.TUESDAY
                    "WE" -> Calendar.WEDNESDAY
                    "TH" -> Calendar.THURSDAY
                    "FR" -> Calendar.FRIDAY
                    "SA" -> Calendar.SATURDAY
                    else -> null
                }
            }.toSet()
        } else if (ruleUpper == "WEEKDAYS") {
            setOf(Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY, Calendar.FRIDAY)
        } else null

        val stepAction: () -> Unit = when {
            ruleUpper.contains("FREQ=HOURLY") || ruleUpper == "HOURLY" -> {
                { cal.add(Calendar.HOUR_OF_DAY, interval) }
            }
            targetDays != null && targetDays.isNotEmpty() -> {
                {
                    do {
                        cal.add(Calendar.DAY_OF_YEAR, 1)
                    } while (cal.get(Calendar.DAY_OF_WEEK) !in targetDays)
                }
            }
            ruleUpper.contains("FREQ=WEEKLY") || ruleUpper == "WEEKLY" -> {
                { cal.add(Calendar.WEEK_OF_YEAR, interval) }
            }
            ruleUpper.contains("FREQ=MONTHLY") || ruleUpper == "MONTHLY" -> {
                { cal.add(Calendar.MONTH, interval) }
            }
            else -> {
                // Default: FREQ=DAILY
                { cal.add(Calendar.DAY_OF_YEAR, interval) }
            }
        }

        // Advance until the calculated time is strictly in the future
        var iterations = 0
        while (cal.timeInMillis <= now && iterations < 1000) {
            stepAction()
            iterations++
        }

        // Safety fallback: if rule failed to advance past now, jump to now + 1 day
        if (cal.timeInMillis <= now) {
            cal.timeInMillis = now + MS_IN_DAY
        }

        return cal.timeInMillis
    }

    /**
     * Evaluates a habit completion and computes streak advancement or reset.
     */
    fun evaluateHabitCompletion(
        rrule: String?,
        lastCompletedAt: Long?,
        currentStreak: Int,
        bestStreak: Int,
        now: Long
    ): StreakResult {
        if (lastCompletedAt == null) {
            val newStreak = 1
            return StreakResult(
                currentStreak = newStreak,
                bestStreak = maxOf(bestStreak, newStreak),
                isContinued = true
            )
        }

        val elapsed = (now - lastCompletedAt).coerceAtLeast(0L)

        val isDaily = rrule == null || rrule.contains("DAILY", ignoreCase = true) || rrule.equals("daily", ignoreCase = true)
        val isWeekly = rrule?.contains("WEEKLY", ignoreCase = true) == true || rrule?.equals("weekly", ignoreCase = true) == true
        val isMonthly = rrule?.contains("MONTHLY", ignoreCase = true) == true || rrule?.equals("monthly", ignoreCase = true) == true

        val gracePeriod = when {
            isDaily -> 36 * MS_IN_HOUR
            isWeekly -> 10 * MS_IN_DAY
            isMonthly -> 35 * MS_IN_DAY
            else -> 7 * MS_IN_DAY
        }

        val samePeriodWindow = when {
            isDaily -> 6 * MS_IN_HOUR
            isWeekly -> 2 * MS_IN_DAY
            isMonthly -> 7 * MS_IN_DAY
            else -> 6 * MS_IN_HOUR
        }

        if (elapsed < samePeriodWindow && currentStreak > 0) {
            // Already counted in this period
            return StreakResult(currentStreak, bestStreak, isContinued = true)
        }

        return if (elapsed <= gracePeriod) {
            val nextCurrent = currentStreak + 1
            StreakResult(
                currentStreak = nextCurrent,
                bestStreak = maxOf(bestStreak, nextCurrent),
                isContinued = true
            )
        } else {
            // Broken streak, restarts at 1
            StreakResult(
                currentStreak = 1,
                bestStreak = bestStreak,
                isContinued = false
            )
        }
    }

    private fun parseInterval(rule: String): Int {
        val match = Regex("""INTERVAL=(\d+)""").find(rule)
        return match?.groupValues?.get(1)?.toIntOrNull()?.coerceAtLeast(1) ?: 1
    }
}
