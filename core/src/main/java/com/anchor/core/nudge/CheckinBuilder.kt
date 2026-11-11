// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.nudge

import com.anchor.core.context.PromptSanitizer
import com.anchor.core.storage.Storage
import com.anchor.core.time.Clock
import com.anchor.core.time.DateResolver
import java.util.Calendar
import java.util.TimeZone

class CheckinBuilder(private val storage: Storage, private val clock: Clock, private val dateResolver: DateResolver) {

    private fun safeTimeZone(tzId: String): TimeZone {
        return try {
            val tz = TimeZone.getTimeZone(tzId)
            if (tz.id == "GMT" && tzId != "GMT" && tzId != "UTC") TimeZone.getDefault() else tz
        } catch (_: Exception) {
            TimeZone.getDefault()
        }
    }

    suspend fun buildCurrentFacts(now: Long = clock.now()): String {
        val dayRule = storage.getEffectiveDayRule(now)
        val tz = safeTimeZone(dayRule.tzId)
        val cal = Calendar.getInstance(tz).apply { timeInMillis = now }
        val currentMin = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)

        // Blueprint 32 §3.1: Evening begins at day_end - 90 min (default 20:30 on 22:00 end)
        val eveningThreshold = dayRule.endMinute - 90
        val isEvening = currentMin >= eveningThreshold || currentMin < dayRule.startMinute

        if (isEvening) {
            val logicalDayOfWeek = if (currentMin < dayRule.startMinute) {
                val prevCal = Calendar.getInstance(tz).apply { timeInMillis = now - 86400000L }
                prevCal.get(Calendar.DAY_OF_WEEK)
            } else {
                cal.get(Calendar.DAY_OF_WEEK)
            }
            return if (logicalDayOfWeek == Calendar.SUNDAY) {
                "${buildWeeklyFacts(now)}\n\n${buildEveningFacts(now)}"
            } else {
                buildEveningFacts(now)
            }
        } else {
            return buildMorningFacts(now)
        }
    }

    fun buildTemplateNarration(facts: String): String {
        val sb = StringBuilder()
        if (facts.contains("Today's Facts:")) {
            sb.append("Good morning! ")
            val agendaLines = facts.lines().filter { it.trimStart().startsWith("* ") }
            if (agendaLines.isNotEmpty()) {
                sb.append("Here is what's scheduled for today:\n")
                agendaLines.forEach { sb.append(it).append("\n") }
            } else {
                sb.append("You have a clear schedule today.")
            }
        } else if (facts.contains("Evening Facts:")) {
            if (facts.contains("Weekly Review Facts:")) {
                sb.append("Good evening! Here is your weekly review and evening recap:\n")
            } else {
                sb.append("Good evening! ")
            }
            val taskLines = facts.lines().filter { it.trimStart().startsWith("* ") }
            if (taskLines.isNotEmpty()) {
                sb.append("Here is a recap of what was on the radar:\n")
                taskLines.forEach { sb.append(it).append("\n") }
            }
            sb.append("\nHow did the day feel?")
        } else {
            sb.append("Here is your briefing:\n").append(facts)
        }
        return sb.toString().trim()
    }

    suspend fun buildMorningFacts(now: Long = clock.now()): String {
        val dayRule = storage.getEffectiveDayRule(now)
        val rolloverMin = dayRule.startMinute
        val logicalTodayStart = dateResolver.getLogicalDayStart(now, rolloverMin)
        val logicalTodayEnd = logicalTodayStart + 86400000L // +24 hours

        // B14: Use strict logical calendar day boundaries, not a rolling +/- 24h window
        val staleCutoffMs = 30L * 24 * 3600 * 1000L
        val rawDueToday = storage.getNodesDueBetween(logicalTodayStart, logicalTodayEnd, limit = 5000)
        val dueToday = rawDueToday.filter {
            !it.localOnly && (maxOf(it.updatedAt, it.createdAt) == 0L || (now - maxOf(it.updatedAt, it.createdAt) <= staleCutoffMs))
        }
        val overdueCount = storage.getActiveNodeCounts(logicalTodayStart, staleCutoffMs).overdue

        val builder = java.lang.StringBuilder()
        builder.append("Today's Facts:\n")
        builder.append("- Due Today: ${dueToday.size}\n")
        if (overdueCount > 0) builder.append("- Overdue: $overdueCount\n")

        if (dueToday.isNotEmpty()) {
            builder.append("\nAgenda:\n")
            dueToday.take(5).forEach {
                val sanitizedTitle = PromptSanitizer.sanitize(it.title, 80)
                // H-10 (R-14): was `it.dueAt!!`. The query filters nulls today, but a null here
                // would NPE every context build; an undated row is listed by title instead.
                val due = it.dueAt?.let { at -> " (Due: ${dateResolver.format(at)})" }.orEmpty()
                builder.append("  * $sanitizedTitle$due\n")
            }
        }

        return builder.toString()
    }

    suspend fun buildEveningFacts(now: Long = clock.now()): String {
        val dayRule = storage.getEffectiveDayRule(now)
        val rolloverMin = dayRule.startMinute
        val logicalTodayStart = dateResolver.getLogicalDayStart(now, rolloverMin)
        val logicalTodayEnd = logicalTodayStart + 86400000L // +24 hours
        val logicalTomorrowEnd = logicalTodayEnd + 86400000L

        val staleCutoffMs = 30L * 24 * 3600 * 1000L
        val recentlyClosed = storage.getRecentlyClosed(now, limit = 20).filter {
            !it.localOnly && it.closedAt != null && it.closedAt >= logicalTodayStart
        }
        val openCounts = storage.getActiveNodeCounts(logicalTodayStart, staleCutoffMs)
        val topRemaining = storage.getHomePriorityList(now, limit = 5).filter { !it.localOnly }
        val rawDueTomorrow = storage.getNodesDueBetween(logicalTodayEnd, logicalTomorrowEnd, limit = 5000)
        val dueTomorrow = rawDueTomorrow.filter {
            !it.localOnly && (maxOf(it.updatedAt, it.createdAt) == 0L || (now - maxOf(it.updatedAt, it.createdAt) <= staleCutoffMs))
        }

        val builder = java.lang.StringBuilder()
        builder.append("Evening Facts:\n")
        builder.append("- Completed Today: ${recentlyClosed.size}\n")
        if (recentlyClosed.isNotEmpty()) {
            recentlyClosed.take(5).forEach {
                builder.append("  * ${PromptSanitizer.sanitize(it.title, 80)}\n")
            }
        }
        builder.append("- Still Open: ${openCounts.totalOpen}\n")
        if (topRemaining.isNotEmpty()) {
            builder.append("\nTop Remaining:\n")
            topRemaining.take(3).forEach {
                val due = it.dueAt?.let { at -> " (Due: ${dateResolver.format(at)})" }.orEmpty()
                builder.append("  * ${PromptSanitizer.sanitize(it.title, 80)}$due\n")
            }
        }
        if (dueTomorrow.isNotEmpty()) {
            builder.append("\nTomorrow Preview:\n")
            dueTomorrow.take(5).forEach {
                val due = it.dueAt?.let { at -> " (Due: ${dateResolver.format(at)})" }.orEmpty()
                builder.append("  * ${PromptSanitizer.sanitize(it.title, 80)}$due\n")
            }
        }

        return builder.toString()
    }

    suspend fun buildWeeklyFacts(now: Long = clock.now()): String {
        val dayRule = storage.getEffectiveDayRule(now)
        val rolloverMin = dayRule.startMinute
        val logicalTodayStart = dateResolver.getLogicalDayStart(now, rolloverMin)
        val oneWeekAgo = logicalTodayStart - 7 * 86400000L
        val staleCutoffMs = 30L * 24 * 3600 * 1000L

        val recentlyClosed = storage.getRecentlyClosed(now, limit = 50).filter {
            !it.localOnly && it.closedAt != null && it.closedAt >= oneWeekAgo
        }
        val openCounts = storage.getActiveNodeCounts(now, staleCutoffMs)

        val builder = java.lang.StringBuilder()
        builder.append("Weekly Review Facts:\n")
        builder.append("- Completed Recent: ${recentlyClosed.size}\n")
        if (recentlyClosed.isNotEmpty()) {
            recentlyClosed.take(5).forEach {
                builder.append("  * Won: ${PromptSanitizer.sanitize(it.title, 80)}\n")
            }
        }
        builder.append("- Open Backlog: ${openCounts.totalOpen} (Overdue: ${openCounts.overdue})\n")

        return builder.toString()
    }
}
