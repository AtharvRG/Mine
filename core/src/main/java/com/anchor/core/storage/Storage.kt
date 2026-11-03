// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.storage

import com.anchor.core.models.ChatMessage
import com.anchor.core.models.Edge
import com.anchor.core.models.Node
import com.anchor.core.models.NodeCounts
import com.anchor.core.models.Proposal
import com.anchor.core.models.RawEvent
import com.anchor.core.models.Reminder
import com.anchor.core.models.SearchResult
import com.anchor.core.models.SoulFact
import kotlinx.coroutines.CoroutineDispatcher

interface Storage {
    val ioDispatcher: CoroutineDispatcher

    suspend fun insertRawMessage(id: String, role: String, content: String)
    suspend fun redactMessage(id: String)
    suspend fun getRawMessage(id: String): String?
    suspend fun getRecentMessages(limit: Int): List<ChatMessage>

    suspend fun getSetting(key: String): String?
    suspend fun saveSetting(key: String, value: String)

    suspend fun getActiveNodes(limit: Int, cursor: String? = null): List<Node>
    suspend fun findActiveNodeByTitle(title: String): Node?
    suspend fun getNodesDueBetween(startMs: Long, endMs: Long, limit: Int): List<Node>
    suspend fun getOverdueNodes(beforeMs: Long, limit: Int): List<Node>
    suspend fun getRankedCandidates(now: Long, staleCutoffMs: Long, perTierLimit: Int = 25): List<Node>
    suspend fun getActiveNodeCounts(now: Long, staleCutoffMs: Long = 30L * 24 * 3600 * 1000L): NodeCounts
    suspend fun getStaleNodes(cutoffMs: Long = 30L * 24 * 3600 * 1000L, now: Long = System.currentTimeMillis()): List<Node>
    suspend fun getActiveReminders(): List<Reminder>
    suspend fun getPendingReminders(): List<Reminder>

    suspend fun saveProposal(proposal: Proposal)
    suspend fun getProposal(id: String): Proposal?
    suspend fun consumeProposal(id: String)

    suspend fun saveReminder(reminder: Reminder)
    suspend fun deleteReminder(id: String)
    suspend fun getDebugStats(): Map<String, String>

    suspend fun saveSoulFact(fact: SoulFact)
    suspend fun createSoulFactAndCommit(fact: SoulFact, event: RawEvent)
    suspend fun searchMemoryFts(query: String, limit: Int): List<SearchResult>

    suspend fun getNode(id: String): Node?
    suspend fun getEvent(opId: String): RawEvent?
    suspend fun hasUndo(token: String): Boolean

    suspend fun getProactiveState(): com.anchor.core.models.ProactiveState
    suspend fun saveProactiveState(state: com.anchor.core.models.ProactiveState)

    suspend fun getReliabilityState(): com.anchor.core.models.ReliabilityState
    suspend fun saveReliabilityState(state: com.anchor.core.models.ReliabilityState)
    suspend fun recordReminderFire(fire: com.anchor.core.models.ReminderFire)

    suspend fun getOpenProposals(): List<Proposal>
    suspend fun getUrgentNodes(): List<Node>
    suspend fun getActiveSoulFacts(): List<SoulFact>
    suspend fun getProposedSoulFacts(): List<SoulFact>
    suspend fun updateSoulFactStatus(id: String, status: String)
    suspend fun getReminderByNodeId(nodeId: String): Reminder?
    suspend fun clearAlarmRegistry()
    suspend fun hasReminderFire(reminderId: String, outcome: String): Boolean

    // Purely Transactional Gateways
    suspend fun createNodeAndCommit(node: Node, event: RawEvent, reminder: Reminder?)
    suspend fun updateNodeAndCommit(node: Node, event: RawEvent, deleteReminderId: String?, upsertReminder: Reminder? = null, consumeProposalId: String? = null)
    suspend fun undoChangeAndCommit(node: Node, event: RawEvent, restoreReminder: Reminder?)
    suspend fun createReminderAndCommit(reminder: Reminder, event: RawEvent)
    suspend fun deleteReminderAndCommit(reminderId: String, event: RawEvent)

    suspend fun saveEdge(edge: Edge)
    suspend fun getEdge(id: String): Edge?
    suspend fun getEdgesForSource(srcId: String, type: String? = null): List<Edge>
    suspend fun getEdgesForDestination(dstId: String, type: String? = null): List<Edge>
    suspend fun deleteEdge(id: String)
    suspend fun createEdgeAndCommit(edge: Edge, event: RawEvent)
    suspend fun deleteEdgeAndCommit(edgeId: String, event: RawEvent)

    suspend fun getRegisteredAlarms(): List<Pair<String, Long>>
    suspend fun saveRegisteredAlarm(reminderId: String, requestCode: Int, registeredFor: Long)
    suspend fun deleteRegisteredAlarm(reminderId: String)

    // Calendar & Day Navigation Queries (D-107, UI Spec E6)
    suspend fun getDaysWithMessages(startDayKey: String, endDayKey: String): Set<String>
    suspend fun getHeatMapCounts(startDayKey: String, endDayKey: String): Map<String, Int>
    suspend fun getMessagesForDay(dayKey: String): List<ChatMessage>
    suspend fun getRemindersBetween(startMs: Long, endMs: Long): List<Reminder>
    suspend fun getRemindersForDay(startOfDayMs: Long, endOfDayMs: Long): List<Reminder>

    // Quote Slots (D-108, Blueprint 40)
    suspend fun getQuoteSlot(slot: com.anchor.core.models.QuoteSlot, dayKey: String): com.anchor.core.models.Quote?
    suspend fun saveQuoteSlot(quote: com.anchor.core.models.Quote)
}

class TooManyActiveNodesException(val cap: Int) : Exception("Too many active items (exceeded $cap). Please be more specific.")

suspend fun Storage.getAllActiveNodesPaged(pageSize: Int = 200, hardCap: Int = 5000): List<Node> {
    val all = mutableListOf<Node>()
    var cursor: String? = null
    while (true) {
        val page = getActiveNodes(limit = pageSize, cursor = cursor)
        if (page.isEmpty()) break
        all.addAll(page)
        if (all.size > hardCap) {
            throw TooManyActiveNodesException(hardCap)
        }
        if (page.size < pageSize) break
        val last = page.last()
        cursor = "${last.dueAt ?: "null"}:${last.id}"
    }
    return all
}