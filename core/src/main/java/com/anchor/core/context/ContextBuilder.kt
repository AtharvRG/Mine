// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine Author
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.context

import com.anchor.core.models.ChatMessage
import com.anchor.core.storage.Storage
import kotlinx.coroutines.withContext

class ContextBuilder(
    private val storage: Storage
) {
    // Fast approximation: 3.5 chars per token
    private fun estimateTokens(text: String): Int = (text.length / 3.5).toInt()

    suspend fun buildPack(newUserMessage: String): List<ChatMessage> = withContext(storage.ioDispatcher) {
        val pack = mutableListOf<ChatMessage>()

        // 1. System Prompt (Budget: ~500 tokens)
        val systemRules = """
            You are MineAI, a personal companion. 
            Keep answers short and direct. No fluff.
        """.trimIndent()

        // 2. Active Nodes (Budget: ~700 tokens)
        val activeNodes = storage.getActiveNodes().filter { it.status == "active" }
        val nodesText = if (activeNodes.isEmpty()) {
            "No active items."
        } else {
            activeNodes.joinToString("\n") { "${it.id} | ${it.type} | ${it.title}" }
        }

        val fullSystemContent = "$systemRules\n\nActive Items:\n$nodesText"
        pack.add(ChatMessage("sys", "system", fullSystemContent))

        // 3. Recent Chat (Budget: ~1500 tokens)
        // We fetch up to 30 recent messages and trim from the oldest until it fits
        val recentHistory = storage.getRecentMessages(30)

        val allowedChatChars = (1500 * 3.5).toInt()
        var currentChars = estimateTokens(newUserMessage) * 3.5

        val includedHistory = mutableListOf<ChatMessage>()

        // Iterate backwards (newest to oldest)
        for (msg in recentHistory.reversed()) {
            val msgChars = msg.content.length
            if (currentChars + msgChars <= allowedChatChars) {
                includedHistory.add(0, msg) // prepend to keep chronological order
                currentChars += msgChars
            } else {
                break // Stop adding older messages to respect the budget
            }
        }

        pack.addAll(includedHistory)

        // 4. The actual new message from the user
        pack.add(ChatMessage("new", "user", newUserMessage))

        return@withContext pack
    }
}