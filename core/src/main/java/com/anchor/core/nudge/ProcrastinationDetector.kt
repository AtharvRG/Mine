// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.nudge

import com.anchor.core.models.Node
import com.anchor.core.storage.Storage
import com.anchor.core.time.Clock
import com.anchor.core.time.DateResolver

data class ProcrastinationAnalysis(
    val mode: String, // "off", "gentle", "firm"
    val slippedNodes: List<Node>,
    val promptInstruction: String?,
    val breakExpired: Boolean = false
)

class ProcrastinationDetector(
    private val storage: Storage,
    private val clock: Clock,
    private val dateResolver: DateResolver
) {
    suspend fun analyze(now: Long = clock.now()): ProcrastinationAnalysis {
        val mode = storage.getSetting("procrastination.mode") ?: "gentle"
        if (mode == "off") {
            return ProcrastinationAnalysis("off", emptyList(), null)
        }

        val slipThreshold = storage.getSetting("procrastination.slip_threshold")?.toIntOrNull() ?: 2
        val staleCutoffMs = 30L * 24 * 3600 * 1000L
        val activeNodes = storage.getActiveNodes(50).filter {
            !it.localOnly && (maxOf(it.updatedAt, it.createdAt) == 0L || (now - maxOf(it.updatedAt, it.createdAt) <= staleCutoffMs))
        }
        val slippedNodes = activeNodes.filter {
            it.slipCount >= slipThreshold || (it.dueAt != null && it.dueAt < now - 48 * 3600_000L)
        }

        val breakUntilMs = storage.getSetting("break.until_ms")?.toLongOrNull()
        val breakExpired = breakUntilMs != null && breakUntilMs < now && (now - breakUntilMs) <= 2 * 3600_000L
        val hasOpenTaskToday = activeNodes.any { it.status == "active" && it.dueAt != null && it.dueAt <= now + 24 * 3600_000L }

        val breakNotice = if (breakExpired && hasOpenTaskToday && mode == "firm") {
            val breakTimeStr = dateResolver.formatTimeOnly(breakUntilMs!!)
            "The user's scheduled break ended $breakTimeStr. A task due today is still open. Save any off-topic idea or question from the user to a Topic Note Card via `upsert_topic_note` so it isn't lost, then firmly redirect them to complete 5-25 minutes on the slipped task first."
        } else null

        if (slippedNodes.isEmpty() && breakNotice == null) {
            return ProcrastinationAnalysis(mode, emptyList(), null, breakExpired = breakExpired)
        }

        val prompt = if (mode == "firm") {
            val items = if (slippedNodes.isNotEmpty()) {
                val list = slippedNodes.joinToString("\n") { n ->
                    val orig = n.originalDueAt?.let { dateResolver.format(it) } ?: "earlier"
                    "- '${n.title}' has slipped ${n.slipCount} times (originally $orig)."
                }
                """
                The user has repeatedly postponed the following critical task(s):
                $list
                RULE: Do NOT encourage scheduling or adding new low-priority tasks without first addressing these overdue tasks.
                Confront the user directly and empathetically with 3 clear options:
                1. Commit to a 5-minute micro-action right now.
                2. Realistically reschedule to a specific day next week.
                3. Drop/cancel the task guilt-free if it's no longer necessary.
                """.trimIndent()
            } else ""

            val combined = listOfNotNull(items.ifBlank { null }, breakNotice).joinToString("\n\n")
            "Procrastination Gate (FIRM):\n$combined"
        } else {
            // gentle mode
            val items = slippedNodes.joinToString("\n") { n ->
                val orig = n.originalDueAt?.let { dateResolver.format(it) } ?: "earlier"
                "Task '${n.title}' has slipped ${n.slipCount} times (originally $orig). When discussing this task, gently offer to break it down into a smaller 5-minute first step or ask if something is blocking them."
            }
            """
            Procrastination Guidance (GENTLE):
            $items
            """.trimIndent()
        }

        return ProcrastinationAnalysis(mode, slippedNodes, prompt, breakExpired = breakExpired)
    }
}
