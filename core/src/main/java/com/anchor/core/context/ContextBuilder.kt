// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.context

import com.anchor.core.models.ChatMessage
import com.anchor.core.storage.Storage
import kotlinx.coroutines.withContext

class ContextBuilder(private val storage: Storage) {

    suspend fun buildPack(): List<ChatMessage> = withContext(storage.ioDispatcher) {
        val pack = mutableListOf<ChatMessage>()
        val systemRules = "You are MineAI, a personal companion. Keep answers short and direct."

        val activeNodes = storage.getActiveNodes().filter { it.status == "active" }
        val nodesText = if (activeNodes.isEmpty()) "No active items."
        else activeNodes.joinToString("\n") { "${it.id} | ${it.type} | ${it.title}" }

        pack.add(ChatMessage("sys", "system", "$systemRules\n\nActive Items:\n$nodesText"))

        val recentHistory = storage.getRecentMessages(30)
        val allowedChatChars = (1500 * 3.5).toInt()
        var currentChars = 0
        val includedHistory = mutableListOf<ChatMessage>()

        // Walk backwards, skipping redacted messages entirely from context pack
        for (msg in recentHistory.reversed()) {
            if (msg.content == null) continue // Item 4
            val msgChars = msg.content.length
            if (currentChars + msgChars <= allowedChatChars) {
                includedHistory.add(0, msg)
                currentChars += msgChars
            } else break
        }

        pack.addAll(includedHistory)
        return@withContext pack
    }
}
