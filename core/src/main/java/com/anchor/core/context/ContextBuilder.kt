// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.context

import com.anchor.core.models.ChatMessage
import com.anchor.core.models.Node
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
        val now = clock.now()
        val nowStr = dateResolver.format(now)

        // D-104: Paged, deterministic code-ranking, token-budget capped active items
        val staleCutoffMs = 30L * 24 * 3600 * 1000L
        val counts = storage.getActiveNodeCounts(now, staleCutoffMs)
        val candidates = storage.getRankedCandidates(now, staleCutoffMs, perTierLimit = 25)

        val rankedNodes = candidates.sortedWith(createDeterministicNodeComparator(now))

        val maxTokens = 700
        val maxChars = (maxTokens * 2.5).toInt()
        var currentChars = 0
        val shownNodes = mutableListOf<Node>()
        for (node in rankedNodes.take(25)) {
            val due = if (node.dueAt != null) "Due: ${dateResolver.format(node.dueAt)}" else "No time"
            val line = "${node.id} | ${node.type} | ${node.title} | $due"
            if (shownNodes.isNotEmpty() && currentChars + line.length > maxChars) {
                break
            }
            shownNodes.add(node)
            currentChars += line.length
        }

        val unshownTotal = maxOf(0, counts.totalOpen - shownNodes.size)
        val shownOverdue = shownNodes.count { it.dueAt != null && it.dueAt < now }
        val unshownOverdue = maxOf(0, counts.overdue - shownOverdue)
        val countLine = "$unshownTotal more open items not shown ($unshownOverdue overdue)"

        val nodesText = if (shownNodes.isEmpty()) {
            "No active items.\n$countLine"
        } else {
            val itemsLines = shownNodes.joinToString("\n") {
                val due = if (it.dueAt != null) "Due: ${dateResolver.format(it.dueAt)}" else "No time"
                "${it.id} | ${it.type} | ${it.title} | $due"
            }
            "$itemsLines\n$countLine"
        }

        // A1 & A6: Inject open proposals and soul facts into context with character budgets
        val openProposals = storage.getOpenProposals()
        val maxProposalChars = 500
        var currentProposalChars = 0
        val shownProposals = mutableListOf<String>()
        for (prop in openProposals.take(5)) {
            val line = "Proposal ${prop.id} (${prop.intent}): Needs clarification. Options: ${prop.options.joinToString { opt -> opt.id }}"
            if (shownProposals.isNotEmpty() && currentProposalChars + line.length > maxProposalChars) {
                break
            }
            shownProposals.add(line)
            currentProposalChars += line.length
        }
        val proposalsText = if (shownProposals.isEmpty()) "No open proposals."
        else shownProposals.joinToString("\n")

        val soulFacts = storage.getActiveSoulFacts()
        val maxSoulChars = 1250
        var currentSoulChars = 0
        val shownSoulFacts = mutableListOf<String>()
        for (fact in soulFacts) {
            val line = "${fact.category}: ${fact.statement}"
            if (shownSoulFacts.isNotEmpty() && currentSoulChars + line.length > maxSoulChars) {
                break
            }
            shownSoulFacts.add(line)
            currentSoulChars += line.length
        }
        val soulText = if (shownSoulFacts.isEmpty()) "No soul facts."
        else shownSoulFacts.joinToString("\n")

        // D1: Wire the CheckinBuilder into the prompt so the LLM knows the factual agenda
        val checkinFacts = checkinBuilder.buildMorningFacts()

        pack.add(ChatMessage("sys", "system", "$systemRules\n\nNow: $nowStr\n\n$checkinFacts\n\nSoul:\n$soulText\n\nActive Items:\n$nodesText\n\nPending Clarifications:\n$proposalsText"))

        val recentHistory = storage.getRecentMessages(30)
        // B12: Use 2.0 chars per token for safer Hindi/Unicode handling budget
        val allowedChatChars = (1500 * 2.0).toInt()
        var currentCharsHistory = 0
        val includedHistory = mutableListOf<ChatMessage>()

        for (msg in recentHistory.reversed()) {
            if (msg.content == null) continue
            val msgChars = msg.content.length
            if (currentCharsHistory + msgChars <= allowedChatChars) {
                includedHistory.add(0, msg)
                currentCharsHistory += msgChars
            } else break
        }

        pack.addAll(includedHistory)
        return@withContext pack
    }

    companion object {
        private fun rankTier(node: Node, now: Long): Int {
            if (node.dueAt != null && node.dueAt < now) return 1 // Overdue
            if (node.status == "in_progress") return 2 // In progress
            if (node.dueAt != null && node.dueAt in now..(now + 48L * 3600 * 1000L)) return 3 // Due within 48h
            if (node.pinned) return 4 // Pinned
            if (node.severity > 0) return 5 // Severity
            return 6 // Regular
        }

        fun createDeterministicNodeComparator(now: Long): Comparator<Node> = Comparator { a, b ->
            val tierA = rankTier(a, now)
            val tierB = rankTier(b, now)
            if (tierA != tierB) return@Comparator tierA.compareTo(tierB)

            when (tierA) {
                1 -> { // Overdue: earliest due date first, then id
                    val dueComp = (a.dueAt ?: 0L).compareTo(b.dueAt ?: 0L)
                    if (dueComp != 0) dueComp else a.id.compareTo(b.id)
                }
                2 -> { // In progress: due date first (if any), then severity DESC, then recency DESC
                    val dueA = a.dueAt ?: Long.MAX_VALUE
                    val dueB = b.dueAt ?: Long.MAX_VALUE
                    if (dueA != dueB) dueA.compareTo(dueB)
                    else if (a.severity != b.severity) b.severity.compareTo(a.severity)
                    else {
                        val recA = maxOf(a.updatedAt, a.createdAt)
                        val recB = maxOf(b.updatedAt, b.createdAt)
                        if (recA != recB) recB.compareTo(recA) else a.id.compareTo(b.id)
                    }
                }
                3 -> { // Due within 48h: earliest due date first, then id
                    val dueComp = (a.dueAt ?: 0L).compareTo(b.dueAt ?: 0L)
                    if (dueComp != 0) dueComp else a.id.compareTo(b.id)
                }
                4 -> { // Pinned: severity DESC, then recency DESC
                    if (a.severity != b.severity) b.severity.compareTo(a.severity)
                    else {
                        val recA = maxOf(a.updatedAt, a.createdAt)
                        val recB = maxOf(b.updatedAt, b.createdAt)
                        if (recA != recB) recB.compareTo(recA) else a.id.compareTo(b.id)
                    }
                }
                5 -> { // Severity: highest severity first, then recency DESC
                    if (a.severity != b.severity) b.severity.compareTo(a.severity)
                    else {
                        val recA = maxOf(a.updatedAt, a.createdAt)
                        val recB = maxOf(b.updatedAt, b.createdAt)
                        if (recA != recB) recB.compareTo(recA) else a.id.compareTo(b.id)
                    }
                }
                else -> { // Recency / Priority
                    if (a.priority != b.priority) b.priority.compareTo(a.priority)
                    else {
                        val recA = maxOf(a.updatedAt, a.createdAt)
                        val recB = maxOf(b.updatedAt, b.createdAt)
                        if (recA != recB) recB.compareTo(recA) else a.id.compareTo(b.id)
                    }
                }
            }
        }
    }
}
