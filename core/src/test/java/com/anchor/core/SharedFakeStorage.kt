// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core

import com.anchor.core.models.*
import com.anchor.core.storage.Storage
import com.anchor.core.storage.rankHomeNodes
import com.anchor.core.time.Clock
import com.anchor.core.time.SystemClock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.util.UUID

open class SharedFakeStorage(var clock: Clock = SystemClock()) : Storage {
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
    val dayRules = mutableListOf<DayRule>()

    override suspend fun getProactiveState(): ProactiveState = proactiveState
    override suspend fun saveProactiveState(state: ProactiveState) { proactiveState = state }
    override suspend fun getReliabilityState(): com.anchor.core.models.ReliabilityState = reliabilityState
    override suspend fun saveReliabilityState(state: com.anchor.core.models.ReliabilityState) { reliabilityState = state }
    override suspend fun recordReminderFire(fire: com.anchor.core.models.ReminderFire) { reminderFires.add(fire) }

    open override suspend fun insertRawMessage(id: String, role: String, content: String) {
        val dayKey = com.anchor.core.time.DayRollover.computeDayKey(clock.now(), getEffectiveDayRule(clock.now()))
        messages.add(ChatMessage(id, role, content, dayKey, clock.now()))
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
    override suspend fun hasRawMessage(id: String): Boolean = messages.any { it.id == id }
    override suspend fun getRecentMessages(limit: Int): List<ChatMessage> = messages.takeLast(limit)

    override suspend fun getSetting(key: String): String? = settings[key]
    override suspend fun saveSetting(key: String, value: String) { settings[key] = value }

    /** When set, [saveSettings] throws after writing this many entries (simulates a mid-write crash). */
    var failSettingsWriteAfter: Int? = null
    override suspend fun saveSettings(values: Map<String, String>) {
        val snapshot = HashMap(settings)
        try {
            values.entries.forEachIndexed { i, (k, v) ->
                failSettingsWriteAfter?.let { if (i >= it) throw IllegalStateException("simulated settings write failure") }
                settings[k] = v
            }
        } catch (e: Exception) {
            settings.clear()
            settings.putAll(snapshot)
            throw e
        }
    }

    override suspend fun getEffectiveDayRule(tsUtc: Long): DayRule {
        return dayRules.filter { it.effectiveFrom <= tsUtc }
            .maxByOrNull { it.effectiveFrom }
            ?: com.anchor.core.time.DayRollover.defaultRule(tsUtc)
    }

    override suspend fun saveDayRule(rule: DayRule) {
        dayRules.removeAll { it.id == rule.id }
        dayRules.add(rule)
    }

    override suspend fun getActiveNodes(limit: Int, cursor: String?): List<Node> {
        val openNodes = activeNodes.filter { it.status == "active" || it.status == "in_progress" || it.status == "paused" }
        val sorted = openNodes.sortedWith(
            compareBy<Node> { it.dueAt == null }
                .thenBy { it.dueAt }
                .thenBy { it.id }
        )
        if (cursor.isNullOrBlank()) {
            return sorted.take(limit)
        }
        val parts = cursor.split(":", limit = 2)
        val cursorDueAt = parts[0].toLongOrNull()
        val cursorId = parts.getOrElse(1) { "" }

        val filtered = sorted.filter { node ->
            if (cursorDueAt != null) {
                if (node.dueAt == null) {
                    true
                } else if (node.dueAt > cursorDueAt) {
                    true
                } else if (node.dueAt == cursorDueAt) {
                    node.id > cursorId
                } else {
                    false
                }
            } else {
                node.dueAt == null && node.id > cursorId
            }
        }
        return filtered.take(limit)
    }

    override suspend fun findActiveNodeByTitle(title: String): Node? {
        return findActiveNodesByTitle(title).firstOrNull()
    }

    override suspend fun findActiveNodesByTitle(title: String): List<Node> {
        val trimmed = title.trim()
        return activeNodes
            .filter {
                (it.status == "active" || it.status == "in_progress" || it.status == "paused") &&
                it.title.trim().equals(trimmed, ignoreCase = true)
            }
            .sortedWith(compareBy<Node> { it.dueAt == null }.thenBy { it.dueAt }.thenBy { it.id })
    }

    override suspend fun getNodesDueBetween(startMs: Long, endMs: Long, limit: Int): List<Node> {
        return activeNodes
            .filter { (it.status == "active" || it.status == "in_progress" || it.status == "paused") && !it.localOnly && it.dueAt != null && it.dueAt in startMs until endMs }
            .sortedWith(compareBy<Node> { it.dueAt }.thenBy { it.id })
            .take(limit)
    }

    override suspend fun getOverdueNodes(beforeMs: Long, limit: Int): List<Node> {
        return activeNodes
            .filter { (it.status == "active" || it.status == "in_progress" || it.status == "paused") && !it.localOnly && it.dueAt != null && it.dueAt < beforeMs }
            .sortedWith(compareBy<Node> { it.dueAt }.thenBy { it.id })
            .take(limit)
    }

    override suspend fun getRankedCandidates(now: Long, staleCutoffMs: Long, perTierLimit: Int): List<Node> {
        val freshActive = activeNodes.filter {
            (it.status == "active" || it.status == "in_progress" || it.status == "paused") &&
            !it.localOnly &&
            ((it.dueAt != null && it.dueAt > now) || maxOf(it.updatedAt, it.createdAt) == 0L || (now - maxOf(it.updatedAt, it.createdAt) <= staleCutoffMs))
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
            !it.localOnly &&
            ((it.dueAt != null && it.dueAt > now) || maxOf(it.updatedAt, it.createdAt) == 0L || (now - maxOf(it.updatedAt, it.createdAt) <= staleCutoffMs))
        }
        val totalOpen = nonStaleOpen.size
        val overdue = nonStaleOpen.count { it.dueAt != null && it.dueAt < now }
        return NodeCounts(totalOpen, overdue)
    }

    override suspend fun getStaleNodes(now: Long, cutoffMs: Long): List<Node> {
        return activeNodes.filter {
            (it.status == "active" || it.status == "in_progress" || it.status == "paused") &&
            (maxOf(it.updatedAt, it.createdAt) > 0L && (now - maxOf(it.updatedAt, it.createdAt) > cutoffMs)) &&
            (it.dueAt == null || it.dueAt <= now)
        }
    }

    // R-25: filters on the caller's clock, not the fake's own, so a test that moves time sees the
    // same set the production path would.
    // R-26: same fire-order page and bound as AndroidStorage — a fake that returns more than the
    // device would makes every test above the bound pass for the wrong reason.
    override suspend fun getActiveReminders(now: Long, limit: Int): List<Reminder> =
        synchronized(reminders) {
            reminders.filter { it.fireAtMillis > (now - 1000) }.sortedBy { it.fireAtMillis }.take(limit)
        }
    // R-26: same fire-order page as AndroidStorage, so a test can see the bound the device has.
    override suspend fun getPendingReminders(limit: Int): List<Reminder> =
        synchronized(reminders) { reminders.sortedBy { it.fireAtMillis }.take(limit) }

    val proposalCreatedAt = mutableMapOf<String, Long>()

    override suspend fun saveProposal(proposal: Proposal) {
        synchronized(savedProposals) {
            savedProposals.removeAll { it.id == proposal.id }
            savedProposals.add(proposal)
            proposalCreatedAt[proposal.id] = clock.now()
        }
    }

    override suspend fun getProposal(id: String): Proposal? = synchronized(savedProposals) {
        val created = proposalCreatedAt[id] ?: return null
        val expiryThreshold = clock.now() - (24 * 3600 * 1000L)
        if (created <= expiryThreshold) return null
        savedProposals.find { it.id == id }
    }

    override suspend fun consumeProposal(id: String) {
        synchronized(savedProposals) {
            val idx = savedProposals.indexOfFirst { it.id == id }
            if (idx != -1) {
                savedProposals[idx] = savedProposals[idx].copy(status = "consumed")
            }
        }
    }

    override suspend fun consumeProposalCas(id: String): Boolean {
        synchronized(savedProposals) {
            val idx = savedProposals.indexOfFirst { it.id == id }
            if (idx != -1 && savedProposals[idx].status == "open") {
                savedProposals[idx] = savedProposals[idx].copy(status = "consumed")
                return true
            }
            return false
        }
    }

    override suspend fun updateProposalStatus(id: String, status: String) {
        synchronized(savedProposals) {
            val idx = savedProposals.indexOfFirst { it.id == id }
            if (idx != -1) {
                savedProposals[idx] = savedProposals[idx].copy(status = status)
            }
        }
    }

    override suspend fun reopenProposal(id: String): Boolean = synchronized(savedProposals) {
        val idx = savedProposals.indexOfFirst { it.id == id }
        if (idx != -1 && savedProposals[idx].status == "consumed") {
            savedProposals[idx] = savedProposals[idx].copy(status = "open")
            true
        } else {
            false
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
    override suspend fun getRecentEvents(limit: Int): List<RawEvent> {
        val uiVisibleOps = setOf("CREATE", "CANCEL", "COMPLETE", "RESCHEDULE", "UPDATE", "SCHEDULE", "KEEP_STALE", "CANCEL_STALE", "DELETE", "CONFIRM", "ACCEPT", "REJECT", "LINK", "UNLINK")
        return loggedEvents
            .filter { it.undoOf == null && it.opType in uiVisibleOps }
            .takeLast(limit)
    }

    // P1-D 20 (D-121): Home queries, mirroring AndroidStorage.
    override suspend fun getHomePriorityList(now: Long, limit: Int): List<Node> {
        val freshActive = activeNodes.filter {
            (it.status == "active" || it.status == "in_progress" || it.status == "paused") &&
            !it.localOnly &&
            ((it.dueAt != null && it.dueAt > now) || maxOf(it.updatedAt, it.createdAt) == 0L || (now - maxOf(it.updatedAt, it.createdAt) <= 30L * 24 * 3600 * 1000L))
        }
        return rankHomeNodes(freshActive, now).take(limit)
    }

    override suspend fun getRecentlyClosed(now: Long, limit: Int): List<Node> {
        return activeNodes
            .filter { (it.status == "done" || it.status == "cancelled") && it.closedAt != null && (now - it.closedAt) < 24L * 3600 * 1000L }
            .sortedWith(compareByDescending<Node> { it.closedAt }.thenBy { it.id })
            .take(limit)
    }

    override suspend fun getLatestEventForTarget(targetType: String, targetId: String): RawEvent? {
        return loggedEvents
            .filter { it.targetType == targetType && it.targetId == targetId }
            .maxByOrNull { it.tsUtc }
    }

    override suspend fun createNodeAndCommit(node: Node, event: RawEvent, reminder: Reminder?, edge: Edge?, edgeEvent: RawEvent?, consumeProposalId: String?) {
        synchronized(this) {
            if (consumeProposalId != null) {
                val idx = savedProposals.indexOfFirst { it.id == consumeProposalId }
                if (idx == -1 || savedProposals[idx].status != "open") {
                    throw com.anchor.core.storage.ProposalAlreadyResolvedException("Proposal already resolved")
                }
                savedProposals[idx] = savedProposals[idx].copy(status = "consumed")
            }
            activeNodes.removeAll { it.id == node.id }
            activeNodes.add(node)
            if (reminder != null) {
                reminders.removeAll { it.id == reminder.id }
                reminders.add(reminder)
            }
            if (edge != null) {
                edges.removeAll { it.id == edge.id }
                edges.add(edge)
            }
            loggedEvents.add(event)
            if (edgeEvent != null) {
                loggedEvents.add(edgeEvent)
            }
        }
    }

    override suspend fun getOpenProposals(): List<Proposal> = synchronized(savedProposals) {
        val expiryThreshold = clock.now() - (24 * 3600 * 1000L)
        savedProposals.filter {
            it.status == "open" && it.id.isNotBlank() && (proposalCreatedAt[it.id] ?: 0L) > expiryThreshold
        }
    }
    override suspend fun getUrgentNodes(): List<Node> = activeNodes.filter { it.status == "active" || it.status == "in_progress" }.sortedWith(compareBy<Node> { it.dueAt == null }.thenBy { it.dueAt }).take(3)
    override suspend fun getActiveSoulFacts(): List<SoulFact> = soulFacts.filter { (it.status == "active" || it.status == "pinned") && !it.localOnly }

    override suspend fun updateNodeAndCommit(node: Node, event: RawEvent, deleteReminderId: String?, upsertReminder: Reminder?, consumeProposalId: String?, softDeleteEdgeIds: List<String>) {
        synchronized(this) {
            if (consumeProposalId != null) {
                val idx = savedProposals.indexOfFirst { it.id == consumeProposalId }
                if (idx == -1 || savedProposals[idx].status != "open") {
                    throw com.anchor.core.storage.ProposalAlreadyResolvedException("Proposal already resolved")
                }
                savedProposals[idx] = savedProposals[idx].copy(status = "consumed")
            }
            // R-05 parity: AndroidStorage aborts a zero-row update; the fake must not pretend
            // the node existed.
            if (activeNodes.none { it.id == node.id }) {
                throw IllegalStateException("updateNodeAndCommit: no row for node ${node.id}")
            }
            activeNodes.removeAll { it.id == node.id }
            activeNodes.add(node)
            if (deleteReminderId != null) reminders.removeAll { it.id == deleteReminderId }
            if (upsertReminder != null) {
                reminders.removeAll { it.id == upsertReminder.id }
                reminders.add(upsertReminder)
            }
            for (edgeId in softDeleteEdgeIds) {
                val idx = edges.indexOfFirst { it.id == edgeId }
                if (idx != -1) {
                    edges[idx] = edges[idx].copy(deletedAt = event.tsUtc, updatedAt = event.tsUtc)
                }
            }
            loggedEvents.add(event)
        }
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

    override suspend fun createReminderAndCommit(reminder: Reminder, event: RawEvent, consumeProposalId: String?) {
        synchronized(this) {
            if (consumeProposalId != null) {
                val idx = savedProposals.indexOfFirst { it.id == consumeProposalId }
                if (idx == -1 || savedProposals[idx].status != "open") {
                    throw com.anchor.core.storage.ProposalAlreadyResolvedException("Proposal already resolved")
                }
                savedProposals[idx] = savedProposals[idx].copy(status = "consumed")
            }
            reminders.removeAll { it.id == reminder.id }
            reminders.add(reminder)
            loggedEvents.add(event)
        }
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

        activeNodes.filter { it.status !in listOf("archived", "cancelled", "done") && !it.localOnly && it.title.contains(query, ignoreCase = true) }.forEach {
            results.add(SearchResult(it.id, it.type, it.title, ""))
        }
        soulFacts.filter { (it.status == "active" || it.status == "pinned") && !it.localOnly && it.statement.contains(query, ignoreCase = true) }.forEach {
            results.add(SearchResult(it.id, "soul_fact", it.category, it.statement))
        }
        messages.filter { it.role != "system" && it.content != null && it.content.contains(query, ignoreCase = true) }.forEach {
            results.add(SearchResult("chunk_${it.id}", "chat", it.dayKey ?: "", it.content!!))
        }
        return results.take(limit.coerceAtLeast(0))
    }

    private val registeredAlarms = mutableMapOf<String, com.anchor.core.models.RegisteredAlarm>()

    override suspend fun getRegisteredAlarms(): List<Pair<String, Long>> = registeredAlarms.values.map { it.reminderId to it.registeredFor }
    override suspend fun getRegisteredAlarmRecords(): List<com.anchor.core.models.RegisteredAlarm> = registeredAlarms.values.toList()
    override suspend fun saveRegisteredAlarm(reminderId: String, requestCode: Int, registeredFor: Long, api: String) {
        registeredAlarms[reminderId] = com.anchor.core.models.RegisteredAlarm(reminderId, registeredFor, api)
    }
    override suspend fun deleteRegisteredAlarm(reminderId: String) { registeredAlarms.remove(reminderId) }
    
    override suspend fun getProposedSoulFacts(): List<SoulFact> = soulFacts.filter { it.status == "proposed" }
    override suspend fun updateSoulFactStatus(id: String, status: String) {
        val idx = soulFacts.indexOfFirst { it.id == id }
        if (idx != -1) soulFacts[idx] = soulFacts[idx].copy(status = status)
    }
    override suspend fun updateSoulFactStatusAndCommit(id: String, status: String, event: RawEvent) {
        updateSoulFactStatus(id, status)
        loggedEvents.add(event)
    }

    override suspend fun getReminderByNodeId(nodeId: String): Reminder? = reminders.find { it.nodeId == nodeId }
    override suspend fun getReminderById(id: String): Reminder? = reminders.find { it.id == id }
    override suspend fun clearAlarmRegistry() { registeredAlarms.clear() }
    override suspend fun hasReminderFire(reminderId: String, outcome: String): Boolean = reminderFires.any { it.reminderId == reminderId && it.outcome == outcome }
    override suspend fun getReminderFire(reminderId: String, outcome: String): com.anchor.core.models.ReminderFire? =
        reminderFires.filter { it.reminderId == reminderId && it.outcome == outcome }.maxByOrNull { it.firedAt }

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
            edges[idx] = edges[idx].copy(deletedAt = clock.now())
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

    fun insertRawMessageWithDay(id: String, role: String, content: String, dayKey: String, tsUtc: Long = clock.now()) {
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

    override suspend fun getRemindersBetween(startMs: Long, endMs: Long, limit: Int): List<Reminder> {
        return reminders.filter { it.fireAtMillis in startMs..endMs }.sortedBy { it.fireAtMillis }.take(limit)
    }

    override suspend fun getRemindersForDay(startOfDayMs: Long, endOfDayMs: Long, limit: Int): List<Reminder> {
        return getRemindersBetween(startOfDayMs, endOfDayMs, limit)
    }

    val quoteSlots = mutableMapOf<Pair<QuoteSlot, String>, Quote>()

    override suspend fun getQuoteSlot(slot: QuoteSlot, dayKey: String): Quote? = quoteSlots[slot to dayKey]
    override suspend fun saveQuoteSlot(quote: Quote) {
        quoteSlots[quote.slot to quote.dayKey] = quote
    }

    override suspend fun getCompletedTodayCount(startOfDayMs: Long, endOfDayMs: Long): Int {
        return activeNodes.count { it.status == "done" && it.closedAt != null && it.closedAt >= startOfDayMs && it.closedAt < endOfDayMs }
    }
}