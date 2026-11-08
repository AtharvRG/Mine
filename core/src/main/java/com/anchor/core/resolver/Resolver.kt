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
            "cancel", "delete", "remove", "complete", "finish", "done",
            "update", "rename", "change", "edit", "mark"
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

    private fun normalizeTitle(text: String): String {
        return text.lowercase(Locale.getDefault())
            .replace("’", "'")
            .replace(Regex("'s\\b"), "")
            .replace(Regex("[^\\p{L}\\p{M}\\p{N}\\s]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")
    }

    private fun isExactMatch(title: String, hint: String, hintTokens: List<String>): Boolean {
        val normTitle = normalizeTitle(title)
        val normHint = normalizeTitle(hint)
        if (normTitle.isNotBlank() && normTitle == normHint) return true

        // Strip common leading intent action words from hint: cancel, complete, delete, remove, finish, done, reschedule, update, rename
        val strippedHint = hint.replace(Regex("^(cancel|complete|delete|remove|finish|done|reschedule|update|rename)\\s+", RegexOption.IGNORE_CASE), "")
        val normStripped = normalizeTitle(strippedHint)
        if (normTitle.isNotBlank() && normTitle == normStripped) return true

        // Token-level exact match (ignoring stopwords)
        val titleTokens = tokenize(title)
        if (titleTokens.isNotEmpty() && titleTokens == hintTokens) return true

        return false
    }

    fun resolve(intent: IntentType, hint: String, targetTimeMillis: Long?, candidates: List<Node>): Resolution {
        if (candidates.isEmpty()) return Resolution.NotFound

        val hintTokens = tokenize(hint)
        if (hintTokens.isEmpty()) return Resolution.NotFound

        // Prefer exact normalised title match: if two items both contain the hint (e.g. "Dinner" vs "Dinner with Alice" for hint "dinner"),
        // the exact title match wins without ambiguity.
        val exactMatches = candidates.filter { isExactMatch(it.title, hint, hintTokens) }
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

        val scores = candidates.map { node -> node to calculateScore(node, hint, targetTimeMillis) }
            .sortedByDescending { it.second.total }

        val top = scores[0]

        val isClosing = intent == IntentType.CANCEL || intent == IntentType.COMPLETE
        val autoCommitThreshold = if (isClosing) 0.80 else 0.70
        val requiredMargin = if (isClosing) 0.25 else 0.20

        // Time Override: If target time is specified, prefer candidate matching the time if text matches threshold
        if (targetTimeMillis != null) {
            val timeMatches = scores.filter { (node, _) -> node.dueAt != null && Math.abs(node.dueAt - targetTimeMillis) < (1000L * 60 * 60 * 24) }
            if (timeMatches.size == 1 && timeMatches[0].second.textMatch >= autoCommitThreshold) {
                return Resolution.ClearWinner(timeMatches[0].first)
            }
        }

        // Single candidate: Must meet auto-commit threshold, otherwise show a chip.
        // H-14 (R-19): this runs before the 0.30 floor below, because with exactly one candidate
        // there is nothing to disambiguate against — saying "not found" about the only item the
        // user is looking at is worse than asking. A zero-overlap candidate still gets NotFound:
        // an unrelated hint is not evidence for the item, so there is nothing to confirm.
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