// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.capture

import com.anchor.core.models.AlarmTier
import com.anchor.core.time.DateResolution
import com.anchor.core.time.DateResolver
import java.util.Locale

/**
 * Result of rule-based quick add parsing.
 */
data class QuickAddResult(
    val title: String,
    val dueAt: Long?,
    val dueText: String?,
    val tier: AlarmTier = AlarmTier.ALARM
)

/**
 * Deterministic, offline-first rule-based quick add parser (Blueprint 41 §3.6 / Blueprint 90 §3.3 Item 6).
 *
 * Extracts task title and due time expression from deterministic command patterns, resolving
 * timestamps using [DateResolver] without requiring an LLM or network connection.
 */
class RuleBasedQuickAdd(
    private val dateResolver: DateResolver
) {

    fun parse(input: String): QuickAddResult? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null

        val lower = trimmed.lowercase(Locale.ROOT)

        val isGentle = lower.startsWith("gentle reminder") || lower.startsWith("gentle remind")
        val tier = if (isGentle) AlarmTier.GENTLE else AlarmTier.ALARM

        // 1. Todo / Task prefix: "todo: <rest>" or "task: <rest>"
        val todoMatch = Regex("^(?:todo|task)\\s*:\\s*(.+)$", RegexOption.IGNORE_CASE).find(trimmed)
        if (todoMatch != null) {
            val content = todoMatch.groupValues[1].trim()
            return parseTrailingTime(content, tier) ?: QuickAddResult(
                title = content,
                dueAt = null,
                dueText = null,
                tier = tier
            )
        }

        // 2. Pattern: (remind me|gentle reminder|set alarm) (at|in) <time> (to|for) <task>
        // Example: "remind me at 6pm to call mom"
        val timeFirstRegex = Regex(
            "^(?:remind\\s+me|gentle\\s+reminder|set\\s+alarm)\\s+(at|in)\\s+(.+?)\\s+(?:to|for)\\s+(.+)$",
            RegexOption.IGNORE_CASE
        )
        val timeFirstMatch = timeFirstRegex.find(trimmed)
        if (timeFirstMatch != null) {
            val prep = timeFirstMatch.groupValues[1]
            val timePart = "$prep ${timeFirstMatch.groupValues[2].trim()}"
            val taskPart = timeFirstMatch.groupValues[3].trim()

            val resolution = runCatching { dateResolver.resolve(timePart) }.getOrNull()
            if (resolution is DateResolution.Exact) {
                return QuickAddResult(
                    title = taskPart,
                    dueAt = resolution.timeMillis,
                    dueText = timePart,
                    tier = tier
                )
            }
        }

        // 3. Pattern: (remind me|gentle reminder|set alarm) (to|for) <task> (at|in|tomorrow|on) <time>
        // Example: "remind me to check oven in 10 minutes"
        val taskFirstRegex = Regex(
            "^(?:remind\\s+me|gentle\\s+reminder|set\\s+alarm)\\s+(?:to|for)\\s+(.+?)\\s+((?:at|in|tomorrow|on)\\s+.+)$",
            RegexOption.IGNORE_CASE
        )
        val taskFirstMatch = taskFirstRegex.find(trimmed)
        if (taskFirstMatch != null) {
            val taskPart = taskFirstMatch.groupValues[1].trim()
            val timePart = taskFirstMatch.groupValues[2].trim()

            val resolution = runCatching { dateResolver.resolve(timePart) }.getOrNull()
            if (resolution is DateResolution.Exact) {
                return QuickAddResult(
                    title = taskPart,
                    dueAt = resolution.timeMillis,
                    dueText = timePart,
                    tier = tier
                )
            }
        }

        return null
    }

    private fun parseTrailingTime(content: String, tier: AlarmTier): QuickAddResult? {
        // Try to split at trailing time indicators: "tomorrow at ...", "at ...", "in ..."
        val splitRegex = Regex("^(.*?)\\s+((?:tomorrow|at|in|on)\\s+.+)$", RegexOption.IGNORE_CASE)
        val match = splitRegex.find(content) ?: return null

        val titleCandidate = match.groupValues[1].trim()
        val timeCandidate = match.groupValues[2].trim()

        if (titleCandidate.isEmpty() || timeCandidate.isEmpty()) return null

        val resolution = runCatching { dateResolver.resolve(timeCandidate) }.getOrNull()
        if (resolution is DateResolution.Exact) {
            return QuickAddResult(
                title = titleCandidate,
                dueAt = resolution.timeMillis,
                dueText = timeCandidate,
                tier = tier
            )
        }
        return null
    }
}
