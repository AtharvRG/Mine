// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.export

import com.anchor.core.storage.Storage
import java.io.File

/**
 * Human-readable Markdown export per Blueprint 15 §3.4 and Roadmap v1.1 Item 4.
 * Generates an unencrypted Markdown document containing items, habits, soul facts,
 * and daily chat timeline, preceded by a prominent plain text security warning.
 * This is a one-way export and is NOT importable.
 */
class ReadableMarkdownExporter(
    private val storage: Storage
) {

    suspend fun generateMarkdown(): String {
        val sb = StringBuilder()

        // 1. Loud plain-text security warning header
        sb.appendLine("# Mine Data Export (Human-Readable Markdown)")
        sb.appendLine()
        sb.appendLine("> ⚠️ **WARNING: UNENCRYPTED PLAIN TEXT EXPORT**")
        sb.appendLine("> This file contains your unencrypted notes, tasks, habits, soul facts, and conversation history.")
        sb.appendLine("> Anyone with access to this file can read your personal data.")
        sb.appendLine("> This export is intended for human reading and archiving ONLY. It cannot be re-imported into Mine.")
        sb.appendLine()
        sb.appendLine("---")
        sb.appendLine()

        // 2. Active Items & Habits
        val activeNodes = storage.getActiveNodes(limit = 10_000)
        sb.appendLine("## Items & Habits (${activeNodes.size})")
        sb.appendLine()
        if (activeNodes.isEmpty()) {
            sb.appendLine("_No active items._")
        } else {
            for (node in activeNodes) {
                sb.append("- [ ] **${node.title}** (`${node.type}`, status: `${node.status}`)")
                if (node.dueAt != null) {
                    sb.append(" — Due: ${node.dueAt}")
                }
                if (node.type == "habit" || node.recurrenceRule != null) {
                    sb.append(" — Streak: ${node.streakCurrent} (Best: ${node.streakBest})")
                    if (node.recurrenceRule != null) {
                        sb.append(" [${node.recurrenceRule}]")
                    }
                }
                sb.appendLine()
            }
        }
        sb.appendLine()

        // 3. Soul Facts
        val soulFacts = storage.getActiveSoulFacts(includeLocalOnly = true)
        sb.appendLine("## Soul Facts & Preferences (${soulFacts.size})")
        sb.appendLine()
        if (soulFacts.isEmpty()) {
            sb.appendLine("_No soul facts._")
        } else {
            for (fact in soulFacts) {
                sb.appendLine("- **[${fact.category}]** ${fact.statement} *(confidence: ${fact.confidence}, status: ${fact.status})*")
            }
        }
        sb.appendLine()

        // 4. Conversation History Grouped by Day
        val messages = storage.getRecentMessages(limit = 100_000)
        val grouped = messages.groupBy { it.dayKey ?: "Undated" }

        sb.appendLine("## Conversation History")
        sb.appendLine()
        if (messages.isEmpty()) {
            sb.appendLine("_No messages._")
        } else {
            for ((dayKey, dayMessages) in grouped) {
                sb.appendLine("## Day: $dayKey")
                sb.appendLine()
                for (msg in dayMessages) {
                    val roleLabel = when (msg.role.lowercase()) {
                        "user" -> "**User**"
                        "assistant" -> "**Assistant**"
                        "system" -> "_System_"
                        else -> "**${msg.role}**"
                    }
                    val body = if (msg.content == null) {
                        "_[Redacted]_"
                    } else {
                        msg.content
                    }
                    sb.appendLine("$roleLabel: $body")
                    sb.appendLine()
                }
            }
        }

        return sb.toString()
    }

    suspend fun exportToFile(file: File) {
        val markdown = generateMarkdown()
        file.writeText(markdown, Charsets.UTF_8)
    }
}
