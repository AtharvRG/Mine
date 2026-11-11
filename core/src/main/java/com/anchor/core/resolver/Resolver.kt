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
        // Blueprint 22 §3.4 weights
        private const val WEIGHT_TEXT = 0.35
        private const val WEIGHT_TIME = 0.25
        private const val WEIGHT_RECENCY = 0.20
        private const val WEIGHT_STATUS = 0.10
        private const val WEIGHT_TYPE = 0.10

        private val STOPWORDS = setOf(
            // Prepositions, articles, conjunctions, pronouns
            "with", "the", "and", "for", "to", "at", "my", "a", "an", "of", "on", "in",
            "about", "by", "from", "or", "as", "is", "it", "this", "that",
            // Relative time stopwords
            "tomorrow", "today", "yesterday", "tonight", "am", "pm",
            // Command words & conversational filler
            "cancel", "delete", "remove", "complete", "finish", "done",
            "update", "rename", "change", "edit", "mark", "reschedule", "postpone", "move", "drop", "clear",
            "please", "can", "could", "would", "i", "you", "me", "want", "need"
        )
    }

    private fun tokenize(text: String): List<String> {
        val normalized = text.lowercase(Locale.ROOT)
            .replace("’", "'")
            .replace("‘", "'")
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

    private fun normalizeTitle(text: String): String {
        return text.lowercase(Locale.ROOT)
            .replace("’", "'")
            .replace("‘", "'")
            .replace(Regex("'s\\b"), "")
            .replace(Regex("[^\\p{L}\\p{M}\\p{N}\\s]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")
    }

    private fun isExactMatch(title: String, hint: String, hintTokens: List<String>): Boolean {
        val normTitle = normalizeTitle(title)
        val normHint = normalizeTitle(hint)
        if (normTitle.isNotBlank() && normTitle == normHint) return true

        // Strip common leading intent action words from hint
        val strippedHint = hint.replace(
            Regex("^(cancel|complete|delete|remove|finish|done|reschedule|update|rename|postpone|move|mark)\\s+", RegexOption.IGNORE_CASE),
            ""
        )
        val normStripped = normalizeTitle(strippedHint)
        if (normTitle.isNotBlank() && normTitle == normStripped) return true

        // Token-level exact match (ignoring stopwords)
        val titleTokens = tokenize(title)
        if (titleTokens.isNotEmpty() && titleTokens == hintTokens) return true

        return false
    }

    fun resolve(
        intent: IntentType,
        hint: String,
        targetTimeMillis: Long?,
        candidates: List<Node>,
        recentlyDiscussedNodeIds: List<String> = emptyList()
    ): Resolution {
        if (candidates.isEmpty()) return Resolution.NotFound

        val isClosing = intent == IntentType.CANCEL || intent == IntentType.COMPLETE || intent == IntentType.RESCHEDULE

        // Blueprint 22 §3.3: Closing and updating intents look at open states first.
        val candidatePool = if (isClosing) {
            val openCandidates = candidates.filter { it.status == "active" || it.status == "open" }
            if (openCandidates.isNotEmpty()) openCandidates else candidates
        } else {
            candidates
        }

        val hintTokens = tokenize(hint)

        // Prefer exact normalised title match: if two items both contain the hint (e.g. "Dinner" vs "Dinner with Alice" for hint "dinner"),
        // the exact title match wins without ambiguity.
        val exactMatches = candidatePool.filter { isExactMatch(it.title, hint, hintTokens) }
        if (exactMatches.isNotEmpty()) {
            if (exactMatches.size == 1) {
                if (targetTimeMillis == null) {
                    return Resolution.ClearWinner(exactMatches[0])
                } else {
                    val due = exactMatches[0].dueAt
                    if (due == null || Math.abs(due - targetTimeMillis) < (1000L * 60 * 60 * 24)) {
                        return Resolution.ClearWinner(exactMatches[0])
                    }
                }
            } else {
                // Multiple items with the exact same title
                if (targetTimeMillis != null) {
                    val timeMatches = exactMatches.filter { it.dueAt != null && Math.abs(it.dueAt - targetTimeMillis) < (1000L * 60 * 60 * 24) }
                    if (timeMatches.size == 1) {
                        return Resolution.ClearWinner(timeMatches[0])
                    }
                }
                return Resolution.Ambiguous(exactMatches)
            }
        }

        if (hintTokens.isEmpty()) return Resolution.NotFound

        val scores = candidatePool.map { node ->
            node to calculateScore(node, hintTokens, intent, targetTimeMillis, recentlyDiscussedNodeIds)
        }.sortedByDescending { it.second.total }

        val top = scores[0]

        val autoCommitThreshold = if (isClosing) 0.80 else 0.70
        val requiredMargin = if (isClosing) 0.25 else 0.20

        // Time Override per Blueprint 22 §3.4: If target time is specified, prefer candidate matching the time if text matches threshold
        if (targetTimeMillis != null) {
            val timeMatches = scores.filter { (node, _) ->
                node.dueAt != null && Math.abs(node.dueAt - targetTimeMillis) < (1000L * 60 * 60 * 24)
            }
            if (timeMatches.size == 1 && timeMatches[0].second.textMatch >= autoCommitThreshold) {
                return Resolution.ClearWinner(timeMatches[0].first)
            }
        }

        // Single candidate: Must meet auto-commit threshold, otherwise show a chip.
        // H-14 (R-19): this runs before the 0.30 floor below, because with exactly one candidate
        // there is nothing to disambiguate against. A zero-overlap candidate still gets NotFound.
        if (scores.size == 1) {
            return when {
                top.second.textMatch >= autoCommitThreshold -> Resolution.ClearWinner(top.first)
                top.second.textMatch > 0.0 -> Resolution.Ambiguous(listOf(top.first))
                else -> Resolution.NotFound
            }
        }

        // Strict baseline: Must have at least 30% word match (multi-candidate path only)
        if (top.second.textMatch < 0.30) return Resolution.NotFound

        val second = scores[1]
        val textMargin = top.second.textMatch - second.second.textMatch
        val totalMargin = top.second.total - second.second.total

        if (top.second.textMatch >= autoCommitThreshold && (textMargin >= requiredMargin || totalMargin >= requiredMargin)) {
            return Resolution.ClearWinner(top.first)
        }

        val ambiguousOptions = scores
            .filter { it.second.textMatch >= 0.30 && it.second.total >= top.second.total - 0.30 }
            .map { it.first }
            .ifEmpty { listOf(top.first) }

        return Resolution.Ambiguous(ambiguousOptions)
    }

    data class ScoreBreakdown(
        val textMatch: Double,
        val timeMatch: Double?,
        val recencyMatch: Double?,
        val statusMatch: Double,
        val typeMatch: Double,
        val total: Double
    )

    private fun calculateScore(
        node: Node,
        hintTokens: List<String>,
        intent: IntentType,
        targetTimeMillis: Long?,
        recentlyDiscussedNodeIds: List<String>
    ): ScoreBreakdown {
        val titleTokens = tokenize(node.title)

        val textScore = if (hintTokens.isNotEmpty()) {
            hintTokens.count { hw -> titleTokens.contains(hw) }.toDouble() / hintTokens.size
        } else {
            0.0
        }

        // Time match
        val timeScore: Double? = if (targetTimeMillis != null) {
            if (node.dueAt != null) {
                val diffDays = Math.abs(node.dueAt - targetTimeMillis) / (1000.0 * 60 * 60 * 24)
                if (diffDays < 1.0) 1.0 else Math.max(0.0, 1.0 - diffDays / 7.0)
            } else {
                0.0
            }
        } else {
            null
        }

        // Recency match
        val recencyScore: Double? = if (recentlyDiscussedNodeIds.isNotEmpty()) {
            if (node.id in recentlyDiscussedNodeIds) 1.0 else 0.0
        } else {
            null
        }

        // Status fit
        val isClosing = intent == IntentType.CANCEL || intent == IntentType.COMPLETE || intent == IntentType.RESCHEDULE
        val statusScore = if (isClosing) {
            if (node.status == "active" || node.status == "open") 1.0 else 0.0
        } else {
            1.0
        }

        // Type fit
        val typeScore = 1.0

        // Normalise weights over active signals
        val activeTextWeight = WEIGHT_TEXT
        val activeStatusWeight = WEIGHT_STATUS
        val activeTypeWeight = WEIGHT_TYPE
        val activeTimeWeight = if (timeScore != null) WEIGHT_TIME else 0.0
        val activeRecencyWeight = if (recencyScore != null) WEIGHT_RECENCY else 0.0

        val totalWeight = activeTextWeight + activeStatusWeight + activeTypeWeight + activeTimeWeight + activeRecencyWeight
        val weightedSum = (textScore * activeTextWeight) +
            (statusScore * activeStatusWeight) +
            (typeScore * activeTypeWeight) +
            ((timeScore ?: 0.0) * activeTimeWeight) +
            ((recencyScore ?: 0.0) * activeRecencyWeight)

        val normalizedTotal = if (totalWeight > 0.0) weightedSum / totalWeight else textScore

        return ScoreBreakdown(
            textMatch = textScore,
            timeMatch = timeScore,
            recencyMatch = recencyScore,
            statusMatch = statusScore,
            typeMatch = typeScore,
            total = normalizedTotal
        )
    }
}
