// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.voice

/**
 * Pure Kotlin speech text cleaner and sentence chunker for TTS.
 * Blueprint 60 §3.3.
 *
 * Invariants:
 * - Strips code blocks, formatting, links, and XML tags.
 * - Enforces max length (default 1200 characters).
 * - Truncated text appends "The rest is in the chat."
 * - Sentence-by-sentence streaming playback chunks.
 */
object VoiceSpeechSanitizer {

    private const val TRUNCATION_NOTICE = "The rest is in the chat."

    /**
     * Cleans raw message text for speech synthesis by removing code fences, inline code,
     * XML tags, markdown symbols, and collapsing whitespace.
     */
    fun cleanText(text: String): String {
        return text
            .replace(Regex("""```[\s\S]*?```"""), "") // Strip code fences
            .replace(Regex("""`[^`]+`"""), "")        // Strip inline code
            .replace(Regex("""<[^>]+>"""), "")        // Strip XML/HTML tags
            .replace(Regex("""\[(.*?)\]\(.*?\)"""), "$1") // Strip links, keep anchor text
            .replace(Regex("""[*_~#>]+"""), "")       // Strip markdown markers
            .replace(Regex("""\s+"""), " ")           // Collapse whitespace
            .trim()
    }

    /**
     * Chunks text into sentence units for streaming TTS playback.
     * Capped at [maxChars]; if truncated, appends [TRUNCATION_NOTICE].
     */
    fun chunkSentences(rawText: String, maxChars: Int = 1200): List<String> {
        val cleaned = cleanText(rawText)
        if (cleaned.isBlank()) return emptyList()

        val textToSpeak = if (cleaned.length <= maxChars) {
            cleaned
        } else {
            // Truncate at last sentence boundary or space within budget
            val truncated = cleaned.take(maxChars)
            val lastBoundary = maxOf(
                truncated.lastIndexOf(". "),
                truncated.lastIndexOf("! "),
                truncated.lastIndexOf("? ")
            )
            val cutPoint = if (lastBoundary > maxChars / 2) lastBoundary + 1 else truncated.lastIndexOf(' ')
            val safeBase = if (cutPoint > 0) truncated.substring(0, cutPoint).trim() else truncated.trim()
            "$safeBase $TRUNCATION_NOTICE"
        }

        // Split by sentence terminators followed by whitespace or end of line
        val sentences = textToSpeak.split(Regex("""(?<=[.!?])\s+"""))
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        return if (sentences.isEmpty() && textToSpeak.isNotEmpty()) {
            listOf(textToSpeak)
        } else {
            sentences
        }
    }
}
