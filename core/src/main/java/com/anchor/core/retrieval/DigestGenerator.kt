// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.retrieval

import com.anchor.core.models.ChatMessage
import com.anchor.core.models.Node

data class DayDigest(
    val dayKey: String,
    val title: String,
    val completedTasks: List<String>,
    val openLoops: List<String>,
    val msgIds: List<String>,
    val summaryText: String
)

data class WeekDigest(
    val weekKey: String,
    val title: String,
    val totalCompleted: Int,
    val ongoingLoops: List<String>,
    val summaryText: String
)

data class MonthDigest(
    val monthKey: String,
    val title: String,
    val totalCompleted: Int,
    val summaryText: String
)

/**
 * Plain-code deterministic digest and summary generator (Blueprint 21 §3.7 / Roadmap P1.1 Item 12).
 *
 * Produces day, week, and month indexes of key points, completed tasks, and open loops
 * as a robust local fallback when remote LLM summaries are unavailable.
 */
class DigestGenerator {

    fun generateDayDigest(
        dayKey: String,
        messages: List<ChatMessage>,
        nodes: List<Node>
    ): DayDigest {
        val completed = nodes.filter { it.status == "done" || it.status == "closed" }.map { it.title }
        val openLoops = nodes.filter { it.status == "active" || it.status == "in_progress" }.map { it.title }
        val msgIds = messages.map { it.id }

        val sb = StringBuilder()
        sb.appendLine("## Day Digest: $dayKey")

        if (messages.isEmpty() && nodes.isEmpty()) {
            sb.appendLine("No messages or task updates recorded for this day.")
        } else {
            if (completed.isNotEmpty()) {
                sb.appendLine("### Completed Tasks (${completed.size})")
                completed.forEach { sb.appendLine("- Completed: $it") }
            }
            if (openLoops.isNotEmpty()) {
                sb.appendLine("### Open Loops (${openLoops.size})")
                openLoops.forEach { sb.appendLine("- Open loop: $it") }
            }
            if (messages.isNotEmpty()) {
                sb.appendLine("### Timeline Highlights")
                val keyMessages = messages.filter { !it.content.isNullOrBlank() }
                    .take(5)
                keyMessages.forEach { msg ->
                    val roleLabel = if (msg.role == "user") "You" else "Mine"
                    val snippet = msg.content?.take(60)?.replace("\n", " ") ?: ""
                    sb.appendLine("- $roleLabel: $snippet")
                }
            }
        }

        return DayDigest(
            dayKey = dayKey,
            title = "Summary for $dayKey",
            completedTasks = completed,
            openLoops = openLoops,
            msgIds = msgIds,
            summaryText = sb.toString().trim()
        )
    }

    fun generateWeekDigest(
        weekKey: String,
        dayDigests: List<DayDigest>
    ): WeekDigest {
        val totalCompleted = dayDigests.sumOf { it.completedTasks.size }
        val allOpen = dayDigests.flatMap { it.openLoops }.distinct()
        val allCompleted = dayDigests.flatMap { it.completedTasks }.toSet()
        // Ongoing loops are loops that were open and not later completed
        val ongoingLoops = allOpen.filter { !allCompleted.contains(it) }

        val sb = StringBuilder()
        sb.appendLine("## Week Digest: $weekKey")
        sb.appendLine("- Total Completed Tasks: $totalCompleted")
        sb.appendLine("- Active Days: ${dayDigests.size}")
        if (ongoingLoops.isNotEmpty()) {
            sb.appendLine("### Ongoing Loops")
            ongoingLoops.forEach { sb.appendLine("- $it") }
        }

        return WeekDigest(
            weekKey = weekKey,
            title = "Week $weekKey Summary",
            totalCompleted = totalCompleted,
            ongoingLoops = ongoingLoops,
            summaryText = sb.toString().trim()
        )
    }

    fun generateMonthDigest(
        monthKey: String,
        weekDigests: List<WeekDigest>
    ): MonthDigest {
        val totalCompleted = weekDigests.sumOf { it.totalCompleted }

        val sb = StringBuilder()
        sb.appendLine("## Month Digest: $monthKey")
        sb.appendLine("- Total Completed Tasks: $totalCompleted")
        sb.appendLine("- Weeks Covered: ${weekDigests.size}")

        return MonthDigest(
            monthKey = monthKey,
            title = "Month $monthKey Summary",
            totalCompleted = totalCompleted,
            summaryText = sb.toString().trim()
        )
    }
}
