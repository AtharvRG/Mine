// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.resolver

import com.anchor.core.models.IntentType
import com.anchor.core.models.Node
import com.anchor.core.models.Resolution
import java.util.Locale

class Resolver {
    companion object {
        private val STOPWORDS = setOf(
            "with", "the", "and", "for", "to", "at", "my", "a", "an", "of", "on", "in",
            "tomorrow", "today", "yesterday", "tonight", "am", "pm",
            "cancel", "delete", "remove", "complete", "finish", "done"
        )
    }

    private fun tokenize(text: String): List<String> {
        val normalized = text.lowercase(Locale.getDefault())
            .replace("’", "'")
            .replace(Regex("'s\\b"), "") // Remove simple possessives

        val tokens = mutableListOf<String>()
        // Unicode aware: Letters, Marks, Numbers
        val matcher = Regex("[\\p{L}\\p{M}\\p{N}]+").findAll(normalized)
        for (match in matcher) {
            if (match.value !in STOPWORDS) {
                tokens.add(match.value)
            }
        }
        return tokens
    }

    fun resolve(intent: IntentType, hint: String, targetTimeMillis: Long?, candidates: List<Node>): Resolution {
        if (candidates.isEmpty()) return Resolution.NotFound

        val scores = candidates.map { node -> node to calculateScore(node, hint, targetTimeMillis) }
            .sortedByDescending { it.second.total }

        val top = scores[0]
        
        // Strict baseline: Must have at least 30% word match
        if (top.second.textMatch < 0.30) return Resolution.NotFound 

        // Clear winner by pure text distinction
        if (scores.size == 1 || (top.second.textMatch - scores[1].second.textMatch >= 0.30)) {
            return Resolution.ClearWinner(top.first)
        }

        // Time Override
        if (targetTimeMillis != null) {
            val timeMatches = scores.filter { (node, _) -> node.dueAt != null && Math.abs(node.dueAt - targetTimeMillis) < (1000L * 60 * 60 * 24) }
            if (timeMatches.size == 1 && timeMatches[0].second.textMatch >= 0.30) return Resolution.ClearWinner(timeMatches[0].first)
        }

        val second = scores[1]
        val margin = top.second.total - second.second.total
        val isClosing = intent == IntentType.CANCEL || intent == IntentType.COMPLETE
        
        // Simplified thresholds: Pure overlap ratio (0.0 to 1.0) + Time bonus
        val requiredScore = if (isClosing) 0.80 else 0.70
        val requiredMargin = if (isClosing) 0.25 else 0.20

        if (top.second.total >= requiredScore && margin >= requiredMargin) {
            return Resolution.ClearWinner(top.first)
        }

        val ambiguousOptions = scores.filter { it.second.total >= top.second.total - 0.30 }.map { it.first }
        return Resolution.Ambiguous(ambiguousOptions)
    }

    data class ScoreBreakdown(val textMatch: Double, val total: Double)

    private fun calculateScore(node: Node, hint: String, targetTimeMillis: Long?): ScoreBreakdown {
        val hintTokens = tokenize(hint)
        val titleTokens = tokenize(node.title)

        val textScore = if (hintTokens.isNotEmpty()) {
            hintTokens.count { hw -> titleTokens.contains(hw) }.toDouble() / hintTokens.size
        } else {
            return ScoreBreakdown(0.0, 0.0)
        }

        var total = textScore
        
        // Time Bonus: Massive boost for same-day match
        if (targetTimeMillis != null && node.dueAt != null) {
            val diffDays = Math.abs(node.dueAt - targetTimeMillis) / (1000 * 60 * 60 * 24).toDouble()
            if (diffDays < 1.0) total += 0.5 
        }
        
        return ScoreBreakdown(textScore, total)
    }
}