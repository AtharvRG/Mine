// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.consolidation

import com.anchor.core.models.Node
import com.anchor.core.models.SoulFact
import java.util.UUID

/**
 * Result of overnight memory consolidation.
 * Blueprint 21 §3.7, Blueprint 32 §3.4.
 */
data class DayDigest(
    val dayKey: String,
    val completedCount: Int,
    val openCount: Int,
    val summaryText: String,
    val openLoops: List<String>,
    val wins: List<String>,
    val generatedAt: Long
)

/**
 * Candidate Soul fact reflected during overnight consolidation.
 * Blueprint 12 §3.6.
 */
data class ReflectedSoulCandidate(
    val category: String,
    val statement: String,
    val confidence: Float,
    val reason: String
)

/**
 * Overnight Memory Consolidation and Sleep Reflection Engine.
 * Pure Kotlin, zero Android framework dependencies.
 *
 * Runs during late-evening check-in, overnight charging, or day rollover to
 * consolidate the day's experiences, detect behavioral patterns, and maintain
 * memory hygiene without hallucinations or data loss.
 */
class ConsolidationEngine {

    /**
     * Generates a deterministic day summary from closed and open nodes.
     */
    fun consolidateDay(
        dayKey: String,
        completedNodes: List<Node>,
        openNodes: List<Node>,
        recentChatMessages: List<String> = emptyList(),
        nowMs: Long
    ): DayDigest {
        val wins = completedNodes.map { it.title }.take(5)
        val openLoops = openNodes.filter { it.status in listOf("active", "in_progress", "paused") }
            .map { it.title }
            .take(5)

        val summarySb = StringBuilder()
        summarySb.append("Day $dayKey summary: ${completedNodes.size} tasks completed, ${openNodes.size} remaining open.")
        if (wins.isNotEmpty()) {
            summarySb.append(" Wins: ${wins.joinToString(", ")}.")
        }
        if (openLoops.isNotEmpty()) {
            summarySb.append(" Open loops: ${openLoops.joinToString(", ")}.")
        }

        return DayDigest(
            dayKey = dayKey,
            completedCount = completedNodes.size,
            openCount = openNodes.size,
            summaryText = summarySb.toString(),
            openLoops = openLoops,
            wins = wins,
            generatedAt = nowMs
        )
    }

    /**
     * Analyzes completed tasks and habit streaks to reflect potential Soul facts.
     * Blueprint 12 §3.5 & §3.6.
     */
    fun reflectSoulPatterns(
        completedNodes: List<Node>,
        existingFacts: List<SoulFact>,
        nowMs: Long
    ): List<ReflectedSoulCandidate> {
        val candidates = mutableListOf<ReflectedSoulCandidate>()

        // Pattern 1: High habit streaks (> 5 consecutive completions)
        val strongHabits = completedNodes.filter { it.streakCurrent >= 5 }
        for (habit in strongHabits) {
            val statement = "Consistent habit: ${habit.title.lowercase()}"
            val exists = existingFacts.any { it.statement.equals(statement, ignoreCase = true) }
            if (!exists) {
                candidates.add(
                    ReflectedSoulCandidate(
                        category = "habits_routines",
                        statement = statement,
                        confidence = 0.85f,
                        reason = "Maintained a streak of ${habit.streakCurrent} completions"
                    )
                )
            }
        }

        // Pattern 2: Work/study focus (multiple completed items in learning/work)
        val studyNodes = completedNodes.filter {
            it.title.contains("study", ignoreCase = true) ||
            it.title.contains("read", ignoreCase = true) ||
            it.title.contains("learn", ignoreCase = true)
        }
        if (studyNodes.size >= 3) {
            val statement = "Values continuous self-learning and reading"
            val exists = existingFacts.any { it.statement.equals(statement, ignoreCase = true) }
            if (!exists) {
                candidates.add(
                    ReflectedSoulCandidate(
                        category = "values",
                        statement = statement,
                        confidence = 0.75f,
                        reason = "Completed multiple study and learning tasks"
                    )
                )
            }
        }

        return candidates
    }

    /**
     * Converts a reflected soul candidate into a proposed SoulFact entity.
     */
    fun toProposedSoulFact(candidate: ReflectedSoulCandidate, nowMs: Long): SoulFact {
        return SoulFact(
            id = UUID.randomUUID().toString(),
            category = candidate.category,
            statement = candidate.statement,
            confidence = candidate.confidence.toDouble(),
            source = "ai_inferred",
            origin = "ai_inferred",
            status = "proposed",
            createdAt = nowMs
        )
    }
}
