// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.context

import com.anchor.core.models.ChatMessage
import com.anchor.core.storage.Storage
import com.anchor.core.time.Clock
import com.anchor.core.time.DateResolver
import kotlinx.coroutines.withContext

class ContextBuilder(
    private val storage: Storage,
    private val clock: Clock,
    private val dateResolver: DateResolver,
    private val checkinBuilder: com.anchor.core.nudge.CheckinBuilder
) {
    suspend fun buildPack(): List<ChatMessage> = withContext(storage.ioDispatcher) {
        val pack = mutableListOf<ChatMessage>()
        val systemRules = "You are MineAI, a personal companion. Keep answers short and direct."
        
        // A5: Inject current time context
        val nowStr = dateResolver.format(clock.now())

        val activeNodes = storage.getActiveNodes()
        val nodesText = if (activeNodes.isEmpty()) "No active items."
        else activeNodes.joinToString("\n") { 
            val due = if (it.dueAt != null) "Due: ${dateResolver.format(it.dueAt)}" else "No time"
            "${it.id} | ${it.type} | ${it.title} | $due" 
        }

        // A1 & A6: Inject open proposals and soul facts into context so the LLM isn't blind
        val openProposals = storage.getOpenProposals()
        val proposalsText = if (openProposals.isEmpty()) "No open proposals."
        else openProposals.joinToString("\n") { "Proposal ${it.id} (${it.intent}): Needs clarification. Options: ${it.optionsIds.joinToString()}" }

        val soulFacts = storage.getActiveSoulFacts()
        val soulText = if (soulFacts.isEmpty()) "No soul facts."
        else soulFacts.joinToString("\n") { "${it.category}: ${it.statement}" }

        // D1: Wire the CheckinBuilder into the prompt so the LLM knows the factual agenda
        val checkinFacts = checkinBuilder.buildMorningFacts()

        pack.add(ChatMessage("sys", "system", "$systemRules\n\nNow: $nowStr\n\n$checkinFacts\n\nSoul:\n$soulText\n\nActive Items:\n$nodesText\n\nPending Clarifications:\n$proposalsText"))

        val recentHistory = storage.getRecentMessages(30)
        // B12: Use 2.0 chars per token for safer Hindi/Unicode handling budget
        val allowedChatChars = (1500 * 2.0).toInt()
        var currentChars = 0
        val includedHistory = mutableListOf<ChatMessage>()

        for (msg in recentHistory.reversed()) {
            if (msg.content == null) continue 
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
