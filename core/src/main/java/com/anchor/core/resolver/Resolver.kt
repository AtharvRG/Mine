// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine Author
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.resolver

import com.anchor.core.models.IntentType
import com.anchor.core.models.Node
import com.anchor.core.models.Resolution
import java.util.Locale

class Resolver {

    fun resolve(
        intent: IntentType,
        hint: String,
        targetTimeMillis: Long?,
        candidates: List<Node>
    ): Resolution {
        if (candidates.isEmpty()) return Resolution.NotFound

        val scores = candidates.map { node ->
            node to calculateScore(node, hint, targetTimeMillis)
        }.sortedByDescending { it.second }

        val top = scores[0]

        // Must have at least *some* resemblance to the query to avoid wild guesses
        if (top.second < 0.30) return Resolution.NotFound

        // Blueprint 22 (3.4): The exact-time override rule.
        // "If the user's own words contain a time or distinguishing detail that matches
        // exactly one candidate, that candidate wins regardless of margin."
        if (targetTimeMillis != null) {
            val timeMatches = scores.filter { (node, _) ->
                node.dueAt != null && Math.abs(node.dueAt - targetTimeMillis) < (1000L * 60 * 60 * 24)
            }
            if (timeMatches.size == 1 && timeMatches[0].second >= 0.30) {
                return Resolution.ClearWinner(timeMatches[0].first)
            }
        }

        if (scores.size == 1) {
            return Resolution.ClearWinner(top.first)
        }

        val second = scores[1]
        val margin = top.second - second.second

        // Blueprint 22 (3.4): Closing intents require high confidence
        val isClosing = intent == IntentType.CANCEL || intent == IntentType.COMPLETE
        val requiredScore = if (isClosing) 0.85 else 0.75
        val requiredMargin = if (isClosing) 0.25 else 0.20

        if (top.second >= requiredScore && margin >= requiredMargin) {
            return Resolution.ClearWinner(top.first)
        }

        // Return the top candidates that are close to each other for the user to pick from
        val ambiguousOptions = scores
            .filter { it.second >= top.second - 0.30 }
            .map { it.first }

        return Resolution.Ambiguous(ambiguousOptions)
    }

    private fun calculateScore(node: Node, hint: String, targetTimeMillis: Long?): Double {
        var score = 0.0
        val hintLower = hint.lowercase(Locale.getDefault())
        val titleLower = node.title.lowercase(Locale.getDefault())

        val hintWords = hintLower.replace("'", "").split(" ", "-", "_").filter { it.length > 2 }
        val titleWords = titleLower.replace("'", "").split(" ", "-", "_")

        // BATCH 1 FIX: Must have a real textual overlap to even be considered.
        val hasTextOverlap = titleLower.contains(hintLower) || hintLower.contains(titleLower) ||
            hintWords.any { hw -> titleWords.any { tw -> tw.contains(hw) } }

        if (!hasTextOverlap) return 0.0

        // 1. Text Match (Blueprint max 0.35)
        if (titleLower.contains(hintLower) || hintLower.contains(titleLower)) {
            score += 0.35
        } else {
            // Partial word overlap
            val matches = hintWords.count { hintWord -> titleWords.any { it.contains(hintWord) } }

            if (hintWords.isNotEmpty()) {
                score += (matches.toDouble() / hintWords.size) * 0.35
            }
        }

        // 2. Time Match (Blueprint max 0.25)
        if (targetTimeMillis != null && node.dueAt != null) {
            val diff = Math.abs(node.dueAt - targetTimeMillis)
            val diffDays = diff / (1000 * 60 * 60 * 24).toDouble()

            if (diffDays < 1.0) {
                score += 0.25
            } else if (diffDays < 7.0) {
                score += 0.05
            }
        } else if (targetTimeMillis == null) {
            score += 0.15 // Baseline if no time specified, prevents everything falling to 0
        }

        // 3. Status Fit (Blueprint max 0.10)
        if (node.status == "active") {
            score += 0.10
        }

        // 4. Type Fit (Assumed pre-filtered for now)
        score += 0.10

        return score
    }
}