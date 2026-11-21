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
        val sw = java.io.StringWriter()
        writeMarkdownTo(sw)
        return sw.toString()
    }

    suspend fun writeMarkdownTo(writer: java.io.Writer) {
        // 1. Loud plain-text security warning header
        writer.write("# Mine Data Export (Human-Readable Markdown)\n\n")
        writer.write("> ⚠️ **WARNING: UNENCRYPTED PLAIN TEXT EXPORT**\n")
        writer.write("> This file contains your unencrypted notes, tasks, habits, soul facts, and conversation history.\n")
        writer.write("> Anyone with access to this file can read your personal data.\n")
        writer.write("> This export is intended for human reading and archiving ONLY. It cannot be re-imported into Mine.\n\n")
        writer.write("---\n\n")

        // 2. Active Items & Habits
        val activeNodes = storage.getActiveNodes(limit = 10_000)
        writer.write("## Items & Habits (${activeNodes.size})\n\n")
        if (activeNodes.isEmpty()) {
            writer.write("_No active items._\n\n")
        } else {
            for (node in activeNodes) {
                writer.write("- [ ] **${node.title}** (`${node.type}`, status: `${node.status}`)")
                if (node.dueAt != null) {
                    writer.write(" — Due: ${node.dueAt}")
                }
                if (node.type == "habit" || node.recurrenceRule != null) {
                    writer.write(" — Streak: ${node.streakCurrent} (Best: ${node.streakBest})")
                    if (node.recurrenceRule != null) {
                        writer.write(" [${node.recurrenceRule}]")
                    }
                }
                writer.write("\n")
            }
            writer.write("\n")
        }

        // 3. Soul Facts
        val soulFacts = storage.getActiveSoulFacts(includeLocalOnly = true)
        writer.write("## Soul Facts & Preferences (${soulFacts.size})\n\n")
        if (soulFacts.isEmpty()) {
            writer.write("_No soul facts._\n\n")
        } else {
            for (fact in soulFacts) {
                writer.write("- **[${fact.category}]** ${fact.statement} *(confidence: ${fact.confidence}, status: ${fact.status})*\n")
            }
            writer.write("\n")
        }

        // 4. Living Topic Notes
        val topicNotes = storage.getAllTopicNotes()
        writer.write("## Living Topic Notes (${topicNotes.size})\n\n")
        if (topicNotes.isEmpty()) {
            writer.write("_No living topic notes._\n\n")
        } else {
            for (note in topicNotes) {
                writer.write("### ${note.topic}${if (note.pinned) " 📌" else ""}\n\n")
                if (note.content.isNotBlank()) {
                    writer.write("${note.content}\n\n")
                } else {
                    writer.write("_Empty note._\n\n")
                }
            }
        }

        // 5. Conversation History Grouped by Day (Streamed to avoid OOM)
        val messages = storage.getRecentMessages(limit = 100_000)
        val grouped = messages.groupBy { it.dayKey ?: "Undated" }

        writer.write("## Conversation History\n\n")
        if (messages.isEmpty()) {
            writer.write("_No messages._\n")
        } else {
            for ((dayKey, dayMessages) in grouped) {
                writer.write("## Day: $dayKey\n\n")
                val digest = storage.getDayDigest(dayKey)
                if (digest != null) {
                    writer.write("> **Day Digest:** ${digest.summaryText}\n\n")
                }
                for (msg in dayMessages) {
                    val roleLabel = when (msg.role.lowercase()) {
                        "user" -> "**User**"
                        "assistant" -> "**Assistant**"
                        "system" -> "_System_"
                        else -> "**${msg.role}**"
                    }
                    val body = msg.content ?: "_[Redacted]_"
                    writer.write("$roleLabel: $body\n\n")
                }
            }
        }
        writer.flush()
    }

    suspend fun exportToFile(file: File) {
        file.bufferedWriter(Charsets.UTF_8).use { writer ->
            writeMarkdownTo(writer)
        }
    }
}
