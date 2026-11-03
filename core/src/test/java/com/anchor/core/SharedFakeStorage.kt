// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core

import com.anchor.core.models.*
import com.anchor.core.storage.Storage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.util.UUID

class SharedFakeStorage : Storage {
    override val ioDispatcher: CoroutineDispatcher = Dispatchers.Unconfined

    var activeNodes = mutableListOf<Node>()
    var messages = mutableListOf<ChatMessage>()
    var reminders = mutableListOf<Reminder>()
    var settings = mutableMapOf<String, String>()
    var proactiveState = ProactiveState()
    var reliabilityState = com.anchor.core.models.ReliabilityState()
    val reminderFires = mutableListOf<com.anchor.core.models.ReminderFire>()

    val loggedEvents = mutableListOf<RawEvent>()
    val savedProposals = mutableListOf<Proposal>()

    override suspend fun getProactiveState(): ProactiveState = proactiveState
    override suspend fun saveProactiveState(state: ProactiveState) { proactiveState = state }
    override suspend fun getReliabilityState(): com.anchor.core.models.ReliabilityState = reliabilityState
    override suspend fun saveReliabilityState(state: com.anchor.core.models.ReliabilityState) { reliabilityState = state }
    override suspend fun recordReminderFire(fire: com.anchor.core.models.ReminderFire) { reminderFires.add(fire) }

    override suspend fun insertRawMessage(id: String, role: String, content: String) {
        messages.add(ChatMessage(id, role, content))
    }

    override suspend fun redactMessage(id: String) {
        val idx = messages.indexOfFirst { it.id == id }
        if (idx != -1) {
            val msg = messages[idx]
            if (msg.content == null) throw Exception("raw_message is append-only except for redaction") // emulate strict double-redact fail
            messages[idx] = msg.copy(content = null)
            loggedEvents.add(RawEvent("u_${UUID.randomUUID()}", "REDACT", "message", id))
        }
    }

    override suspend fun getRawMessage(id: String): String? = messages.find { it.id == id }?.content
    override suspend fun getRecentMessages(limit: Int): List<ChatMessage> = messages.takeLast(limit)

    override suspend fun getSetting(key: String): String? = settings[key]
    override suspend fun saveSetting(key: String, value: String) { settings[key] = value }

    override suspend fun getActiveNodes(limit: Int, cursor: String?): List<Node> {
        val openNodes = activeNodes.filter { it.status == "active" || it.status == "in_progress" || it.status == "paused" }
        val sorted = openNodes.sortedWith(
            compareBy<Node> { it.dueAt == null }
                .thenBy { it.dueAt }
                .thenBy { it.id }
        )
        val startIdx = if (cursor != null) {
            val cursorId = cursor.substringAfter(":", cursor)
            val idx = sorted.indexOfFirst { it.id == cursorId }
            if (idx != -1) idx + 1 else 0
        } else 0

        return sorted.drop(startIdx).take(limit)
    }

    override suspend fun findActiveNodeByTitle(title: String): Node? {
        val trimmed = title.trim()
        return activeNodes.find {
            (it.status == "active" || it.status == "in_progress" || it.status == "paused") &&
            it.title.trim().equals(trimmed, ignoreCase = true)
        }
    }

    override suspend fun getNodesDueBetween(startMs: Long, endMs: Long, limit: Int): List<Node> {
        return activeNodes
            .filter { (it.status == "active" || it.status == "in_progress" || it.status == "paused") && it.dueAt != null && it.dueAt in startMs until endMs }
            .sortedWith(compareBy<Node> { it.dueAt }.thenBy { it.id })
            .take(limit)
    }

    override suspend fun getOverdueNodes(beforeMs: Long, limit: Int): List<Node> {
        return activeNodes
            .filter { (it.status == "active" || it.status == "in_progress" || it.status == "paused") && it.dueAt != null && it.dueAt < beforeMs }
            .sortedWith(compareBy<Node> { it.dueAt }.thenBy { it.id })
            .take(limit)
    }

    override suspend fun getRankedCandidates(now: Long, staleCutoffMs: Long, perTierLimit: Int): List<Node> {
        val freshActive = activeNodes.filter {
            (it.status == "active" || it.status == "in_progress" || it.status == "paused") &&
            (maxOf(it.updatedAt, it.createdAt) == 0L || (now - maxOf(it.updatedAt, it.createdAt) <= staleCutoffMs))
        }

        val tier1 = freshActive.filter { it.dueAt != null && it.dueAt < now }
            .sortedWith(compareBy<Node> { it.dueAt }.thenBy { it.id })
            .take(perTierLimit)

        val tier2 = freshActive.filter { it.status == "in_progress" }
            .sortedWith(compareByDescending<Node> { maxOf(it.updatedAt, it.createdAt) }.thenBy { it.id })
            .take(perTierLimit)

        val tier3 = freshActive.filter { it.dueAt != null && it.dueAt in now..(now + 48L * 3600 * 1000L) }
            .sortedWith(compareBy<Node> { it.dueAt }.thenBy { it.id })
            .take(perTierLimit)

        val tier4 = freshActive.filter { it.pinned }
            .sortedWith(compareByDescending<Node> { maxOf(it.updatedAt, it.createdAt) }.thenBy { it.id })
            .take(perTierLimit)

        val tier5 = freshActive.filter { it.severity > 0 }
            .sortedWith(compareByDescending<Node> { it.severity }.thenByDescending { maxOf(it.updatedAt, it.createdAt) }.thenBy { it.id })
            .take(perTierLimit)

        val tier6 = freshActive
            .sortedWith(compareByDescending<Node> { maxOf(it.updatedAt, it.createdAt) }.thenBy { it.id })
            .take(perTierLimit)

        val union = LinkedHashMap<String, Node>()
        for (node in tier1) union.putIfAbsent(node.id, node)
        for (node in tier2) union.putIfAbsent(node.id, node)
        for (node in tier3) union.putIfAbsent(node.id, node)
        for (node in tier4) union.putIfAbsent(node.id, node)
        for (node in tier5) union.putIfAbsent(node.id, node)
        for (node in tier6) union.putIfAbsent(node.id, node)
        return union.values.toList()
    }

    override suspend fun getActiveNodeCounts(now: Long, staleCutoffMs: Long): NodeCounts {
        val nonStaleOpen = activeNodes.filter {
            (it.status == "active" || it.status == "in_progress" || it.status == "paused") &&
            (maxOf(it.updatedAt, it.createdAt) == 0L || (now - maxOf(it.updatedAt, it.createdAt) <= staleCutoffMs))
        }
        val totalOpen = nonStaleOpen.size
        val overdue = nonStaleOpen.count { it.dueAt != null && it.dueAt < now }
        return NodeCounts(totalOpen, overdue)
    }

    override suspend fun getStaleNodes(cutoffMs: Long, now: Long): List<Node> {
        return activeNodes.filter {
            (it.status == "active" || it.status == "in_progress" || it.status == "paused") &&
            (maxOf(it.updatedAt, it.createdAt) > 0L && (now - maxOf(it.updatedAt, it.createdAt) > cutoffMs))
        }
    }

    override suspend fun getActiveReminders(): List<Reminder> = reminders.filter { it.fireAtMillis > (System.currentTimeMillis() - 1000) }
    override suspend fun getPendingReminders(): List<Reminder> = reminders

    override suspend fun saveProposal(proposal: Proposal) {
        savedProposals.removeAll { it.id == proposal.id }
        savedProposals.add(proposal)
    }

    override suspend fun getProposal(id: String): Proposal? = savedProposals.find { it.id == id }
    override suspend fun consumeProposal(id: String) {
        val idx = savedProposals.indexOfFirst { it.id == id }
        if (idx != -1) {
            savedProposals[idx] = savedProposals[idx].copy(status = "consumed")
        }
    }

    override suspend fun saveReminder(reminder: Reminder) {
        reminders.removeAll { it.id == reminder.id }
        reminders.add(reminder)
    }
    override suspend fun deleteReminder(id: String) { reminders.removeAll { it.id == id } }
    override suspend fun getDebugStats(): Map<String, String> = emptyMap()

    override suspend fun getNode(id: String): Node? = activeNodes.find { it.id == id }
    override suspend fun getEvent(opId: String): RawEvent? = loggedEvents.find { it.opId == opId }
    override suspend fun hasUndo(token: String): Boolean = loggedEvents.any { it.undoOf == token }

    override suspend fun createNodeAndCommit(node: Node, event: RawEvent, reminder: Reminder?) {
        activeNodes.removeAll { it.id == node.id }
        activeNodes.add(node)
        if (reminder != null) {
            reminders.removeAll { it.id == reminder.id }
            reminders.add(reminder)
        }
        loggedEvents.add(event)
    }

    override suspend fun getOpenProposals(): List<Proposal> = savedProposals.filter { it.status == "open" && it.id.isNotBlank() }
    override suspend fun getUrgentNodes(): List<Node> = activeNodes.filter { it.status == "active" || it.status == "in_progress" }.sortedWith(compareBy<Node> { it.dueAt == null }.thenBy { it.dueAt }).take(3)
    override suspend fun getActiveSoulFacts(): List<SoulFact> = soulFacts.filter { it.status == "active" || it.status == "pinned" }

    override suspend fun updateNodeAndCommit(node: Node, event: RawEvent, deleteReminderId: String?, upsertReminder: Reminder?, consumeProposalId: String?) {
        activeNodes.removeAll { it.id == node.id }
        activeNodes.add(node)
        if (deleteReminderId != null) reminders.removeAll { it.id == deleteReminderId }
        if (upsertReminder != null) {
            reminders.removeAll { it.id == upsertReminder.id }
            reminders.add(upsertReminder)
        }
        if (consumeProposalId != null) {
            val idx = savedProposals.indexOfFirst { it.id == consumeProposalId }
            if (idx != -1) savedProposals[idx] = savedProposals[idx].copy(status = "consumed")
        }
        loggedEvents.add(event)
    }

    override suspend fun undoChangeAndCommit(node: Node, event: RawEvent, restoreReminder: Reminder?) {
        activeNodes.removeAll { it.id == node.id }
        activeNodes.add(node)
        if (restoreReminder != null) {
            reminders.removeAll { it.id == restoreReminder.id }
            reminders.add(restoreReminder)
        }
        loggedEvents.add(event)
    }

    override suspend fun createReminderAndCommit(reminder: Reminder, event: RawEvent) {
        reminders.removeAll { it.id == reminder.id }
        reminders.add(reminder)
        loggedEvents.add(event)
    }

    override suspend fun deleteReminderAndCommit(reminderId: String, event: RawEvent) {
        reminders.removeAll { it.id == reminderId }
        loggedEvents.add(event)
    }

    var soulFacts = mutableListOf<SoulFact>()

    override suspend fun saveSoulFact(fact: SoulFact) {
        soulFacts.removeAll { it.id == fact.id }
        soulFacts.add(fact)
    }
    
    override suspend fun createSoulFactAndCommit(fact: SoulFact, event: RawEvent) {
        soulFacts.removeAll { it.id == fact.id }
        soulFacts.add(fact)
        loggedEvents.add(event)
    }
    override suspend fun searchMemoryFts(query: String, limit: Int): List<SearchResult> {
        val results = mutableListOf<SearchResult>()
        if (query.isBlank()) return results
        
        activeNodes.filter { it.title.contains(query, ignoreCase = true) }.forEach {
            results.add(SearchResult(it.id, it.type, it.title, ""))
        }
        soulFacts.filter { it.statement.contains(query, ignoreCase = true) }.forEach {
            results.add(SearchResult(it.id, "soul_fact", it.category, it.statement))
        }
        return results.take(limit)
    }

    private val registeredAlarms = mutableMapOf<String, Long>()
    
    override suspend fun getRegisteredAlarms(): List<Pair<String, Long>> = registeredAlarms.map { it.key to it.value }
    override suspend fun saveRegisteredAlarm(reminderId: String, requestCode: Int, registeredFor: Long) { registeredAlarms[reminderId] = registeredFor }
    override suspend fun deleteRegisteredAlarm(reminderId: String) { registeredAlarms.remove(reminderId) }
    
    override suspend fun getProposedSoulFacts(): List<SoulFact> = soulFacts.filter { it.status == "proposed" }
    override suspend fun updateSoulFactStatus(id: String, status: String) {
        val idx = soulFacts.indexOfFirst { it.id == id }
        if (idx != -1) soulFacts[idx] = soulFacts[idx].copy(status = status)
    }

    override suspend fun getReminderByNodeId(nodeId: String): Reminder? = reminders.find { it.nodeId == nodeId }
    override suspend fun clearAlarmRegistry() { registeredAlarms.clear() }
    override suspend fun hasReminderFire(reminderId: String, outcome: String): Boolean = reminderFires.any { it.reminderId == reminderId && it.outcome == outcome }

    val edges = mutableListOf<Edge>()

    override suspend fun saveEdge(edge: Edge) {
        val idx = edges.indexOfFirst { it.id == edge.id }
        if (idx != -1) edges[idx] = edge else edges.add(edge)
    }

    override suspend fun getEdge(id: String): Edge? = edges.find { it.id == id && it.deletedAt == null }

    override suspend fun getEdgesForSource(srcId: String, type: String?): List<Edge> {
        return edges.filter { it.srcId == srcId && (type == null || it.type == type) && it.deletedAt == null }
    }

    override suspend fun getEdgesForDestination(dstId: String, type: String?): List<Edge> {
        return edges.filter { it.dstId == dstId && (type == null || it.type == type) && it.deletedAt == null }
    }

    override suspend fun deleteEdge(id: String) {
        val idx = edges.indexOfFirst { it.id == id }
        if (idx != -1) {
            edges[idx] = edges[idx].copy(deletedAt = System.currentTimeMillis())
        }
    }

    override suspend fun createEdgeAndCommit(edge: Edge, event: RawEvent) {
        saveEdge(edge)
        loggedEvents.add(event)
    }

    override suspend fun deleteEdgeAndCommit(edgeId: String, event: RawEvent) {
        deleteEdge(edgeId)
        loggedEvents.add(event)
    }

    fun insertRawMessageWithDay(id: String, role: String, content: String, dayKey: String, tsUtc: Long = System.currentTimeMillis()) {
        messages.add(ChatMessage(id, role, content, dayKey, tsUtc))
    }

    override suspend fun getDaysWithMessages(startDayKey: String, endDayKey: String): Set<String> {
        return messages
            .filter { it.dayKey != null && it.dayKey in startDayKey..endDayKey && it.content != null }
            .mapNotNull { it.dayKey }
            .toSet()
    }

    override suspend fun getHeatMapCounts(startDayKey: String, endDayKey: String): Map<String, Int> {
        return messages
            .filter { it.dayKey != null && it.dayKey in startDayKey..endDayKey }
            .groupBy { it.dayKey!! }
            .mapValues { it.value.size }
    }

    override suspend fun getMessagesForDay(dayKey: String): List<ChatMessage> {
        return messages.filter { it.dayKey == dayKey }
    }

    override suspend fun getRemindersBetween(startMs: Long, endMs: Long): List<Reminder> {
        return reminders.filter { it.fireAtMillis in startMs..endMs }.sortedBy { it.fireAtMillis }
    }

    override suspend fun getRemindersForDay(startOfDayMs: Long, endOfDayMs: Long): List<Reminder> {
        return getRemindersBetween(startOfDayMs, endOfDayMs)
    }

    val quoteSlots = mutableMapOf<Pair<QuoteSlot, String>, Quote>()

    override suspend fun getQuoteSlot(slot: QuoteSlot, dayKey: String): Quote? = quoteSlots[slot to dayKey]
    override suspend fun saveQuoteSlot(quote: Quote) {
        quoteSlots[quote.slot to quote.dayKey] = quote
    }
}