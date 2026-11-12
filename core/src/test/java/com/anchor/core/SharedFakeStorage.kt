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

    private val currentDayKeyOverride = ThreadLocal<String?>()

    open override suspend fun insertRawMessage(id: String, role: String, content: String) {
        val resolvedDayKey = currentDayKeyOverride.get() ?: run {
            val rule = getEffectiveDayRule(clock.now())
            var k = com.anchor.core.time.DayRollover.computeDayKey(clock.now(), rule)
            val lastDayKey = messages.lastOrNull { it.dayKey != null }?.dayKey
            if (lastDayKey != null && k < lastDayKey) k = lastDayKey
            k
        }
        messages.add(ChatMessage(id, role, content, resolvedDayKey, clock.now()))
    }

    open override suspend fun insertRawMessage(
        id: String,
        role: String,
        content: String,
        causeMsgId: String?,
        dayKey: String?
    ) {
        var resolvedDayKey = dayKey
        if (resolvedDayKey == null && causeMsgId != null) {
            resolvedDayKey = messages.find { it.id == causeMsgId }?.dayKey
        }
        if (resolvedDayKey == null) {
            val rule = getEffectiveDayRule(clock.now())
            resolvedDayKey = com.anchor.core.time.DayRollover.computeDayKey(clock.now(), rule)
            val lastDayKey = messages.lastOrNull { it.dayKey != null }?.dayKey
            if (lastDayKey != null && resolvedDayKey < lastDayKey) {
                resolvedDayKey = lastDayKey
            }
        }
        currentDayKeyOverride.set(resolvedDayKey)
        try {
            insertRawMessage(id, role, content)
        } finally {
            currentDayKeyOverride.remove()
        }
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

    override suspend fun getActiveNodes(limit: Int, cursor: String?): List<Node> = synchronized(this) {
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
        filtered.take(limit)
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

    override suspend fun getActiveNodeCounts(now: Long, staleCutoffMs: Long): NodeCounts = synchronized(this) {
        val nonStaleOpen = activeNodes.filter {
            (it.status == "active" || it.status == "in_progress" || it.status == "paused") &&
            !it.localOnly &&
            ((it.dueAt != null && it.dueAt > now) || maxOf(it.updatedAt, it.createdAt) == 0L || (now - maxOf(it.updatedAt, it.createdAt) <= staleCutoffMs))
        }
        val totalOpen = nonStaleOpen.size
        val overdue = nonStaleOpen.count { it.dueAt != null && it.dueAt < now }
        NodeCounts(totalOpen, overdue)
    }

    override suspend fun getStaleNodes(now: Long, cutoffMs: Long): List<Node> = synchronized(this) {
        activeNodes.filter {
            (it.status == "active" || it.status == "in_progress" || it.status == "paused") &&
            !it.localOnly &&
            (maxOf(it.updatedAt, it.createdAt) > 0L && (now - maxOf(it.updatedAt, it.createdAt) > cutoffMs)) &&
            (it.dueAt == null || it.dueAt <= now)
        }.sortedByDescending { it.createdAt }
    }

    override suspend fun getTrashNodes(now: Long, limit: Int): List<Node> = synchronized(this) {
        val thirtyDaysAgo = now - (30L * 24 * 3600 * 1000L)
        activeNodes.filter { it.status == "cancelled" && ((it.closedAt ?: it.updatedAt) >= thirtyDaysAgo) }
            .sortedByDescending { it.closedAt ?: it.updatedAt }
            .take(limit)
    }

    override suspend fun eraseNode(nodeId: String): Unit = synchronized(this) {
        activeNodes.removeAll { it.id == nodeId }
        reminders.removeAll { it.nodeId == nodeId }
        edges.removeAll { it.srcId == nodeId || it.dstId == nodeId }
        for (i in loggedEvents.indices) {
            if (loggedEvents[i].targetId == nodeId) {
                loggedEvents[i] = loggedEvents[i].copy(beforeJson = null, afterJson = null)
            }
        }
    }

    override suspend fun eraseSoulFact(factId: String): Unit = synchronized(this) {
        soulFacts.removeAll { it.id == factId }
        for (i in loggedEvents.indices) {
            if (loggedEvents[i].targetId == factId) {
                loggedEvents[i] = loggedEvents[i].copy(beforeJson = null, afterJson = null)
            }
        }
    }

    override suspend fun isSoulFactSuppressed(statement: String): Boolean = synchronized(this) {
        soulFacts.any { it.status == "rejected" && it.statement.trim().equals(statement.trim(), ignoreCase = true) }
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
    override suspend fun getDebugStats(): Map<String, String> = mapOf(
        "Rows in raw_message" to messages.size.toString(),
        "Rows in raw_event" to loggedEvents.size.toString(),
        "Rows in node" to activeNodes.size.toString(),
        "Rows in edge" to edges.size.toString(),
        "Rows in reminder" to reminders.size.toString()
    )

    override suspend fun getNode(id: String): Node? = activeNodes.find { it.id == id }
    override suspend fun getEvent(opId: String): RawEvent? = loggedEvents.find { it.opId == opId }
    override suspend fun hasUndo(token: String): Boolean = loggedEvents.any { it.undoOf == token }
    override suspend fun getUndoneOpIds(sinceUtc: Long): Set<String> =
        loggedEvents.filter { it.undoOf != null && (sinceUtc == 0L || it.tsUtc >= sinceUtc) }
            .mapNotNull { it.undoOf }
            .toSet()
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
    override suspend fun getUrgentNodes(): List<Node> = activeNodes.filter { (it.status == "active" || it.status == "in_progress") && !it.localOnly }.sortedWith(compareBy<Node> { it.dueAt == null }.thenBy { it.dueAt }).take(3)
    override suspend fun getActiveSoulFacts(includeLocalOnly: Boolean): List<SoulFact> = soulFacts.filter { (it.status == "active" || it.status == "pinned") && (includeLocalOnly || !it.localOnly) }

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
    override suspend fun searchMemoryFts(query: String, limit: Int): List<SearchResult> = synchronized(this) {
        val results = mutableListOf<SearchResult>()
        if (query.isBlank()) return results
        val sanitized = query.replace(Regex("[^\\p{L}\\p{M}\\p{N}\\s]"), "").trim()
        val tokens = sanitized.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return results

        activeNodes.filter { node ->
            node.status !in listOf("archived", "cancelled", "done") &&
                !node.localOnly &&
                tokens.all { token -> node.title.contains(token, ignoreCase = true) }
        }.forEach {
            results.add(SearchResult(it.id, it.type, it.title, ""))
        }
        soulFacts.filter { fact ->
            (fact.status == "active" || fact.status == "pinned") &&
                !fact.localOnly &&
                tokens.all { token -> fact.statement.contains(token, ignoreCase = true) || fact.category.contains(token, ignoreCase = true) }
        }.forEach {
            results.add(SearchResult(it.id, "soul_fact", it.category, it.statement))
        }
        messages.filter { msg ->
            msg.role != "system" &&
                msg.content != null &&
                tokens.all { token -> msg.content.contains(token, ignoreCase = true) }
        }.forEach {
            results.add(SearchResult("chunk_${it.id}", "chat", it.dayKey ?: "", it.content!!, it.tsUtc))
        }
        return results.take(limit.coerceAtLeast(0))
    }

    override suspend fun searchHistory(query: String, limit: Int): List<SearchResult> = synchronized(this) {
        if (query.isBlank()) return emptyList()
        val sanitized = query.replace(Regex("[^\\p{L}\\p{M}\\p{N}\\s]"), "").trim()
        val tokens = sanitized.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return emptyList()

        return messages
            .filter { msg ->
                msg.role != "system" &&
                    msg.content != null &&
                    tokens.all { token -> msg.content.contains(token, ignoreCase = true) }
            }
            .sortedByDescending { it.tsUtc }
            .map { SearchResult(it.id, it.role, it.dayKey ?: "", it.content!!, it.tsUtc) }
            .take(limit.coerceAtLeast(0))
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

    override suspend fun getRecentReminderFires(limit: Int): List<com.anchor.core.models.ReminderFire> =
        synchronized(this) {
            reminderFires.sortedByDescending { it.firedAt }.take(limit.coerceAtLeast(0))
        }

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

    override suspend fun getGraphNeighborhood(nodeId: String, maxHops: Int, cap: Int): List<Node> = synchronized(this) {
        val visitedNodeIds = mutableSetOf<String>()
        var currentFrontier = setOf(nodeId)
        val resultNodes = mutableListOf<Node>()

        visitedNodeIds.add(nodeId)

        for (hop in 1..maxHops) {
            val nextFrontier = mutableSetOf<String>()
            for (currId in currentFrontier) {
                val outgoing = edges.filter { it.srcId == currId && it.deletedAt == null }.map { it.dstId }
                val incoming = edges.filter { it.dstId == currId && it.deletedAt == null }.map { it.srcId }
                for (neighborId in outgoing + incoming) {
                    if (neighborId !in visitedNodeIds) {
                        visitedNodeIds.add(neighborId)
                        nextFrontier.add(neighborId)
                        val node = activeNodes.find { it.id == neighborId }
                        if (node != null && node.status != "archived" && node.status != "cancelled") {
                            resultNodes.add(node)
                            if (resultNodes.size >= cap) return@synchronized resultNodes
                        }
                    }
                }
            }
            currentFrontier = nextFrontier
            if (currentFrontier.isEmpty()) break
        }
        resultNodes
    }

    override suspend fun getCalendarBuckets(startMs: Long, endMs: Long): Map<String, List<Node>> {
        val rule = getEffectiveDayRule(startMs)
        return synchronized(this) {
            val matchingNodes = activeNodes.filter {
                it.dueAt != null && it.dueAt in startMs..endMs &&
                it.status != "archived" && it.status != "cancelled"
            }
            matchingNodes.groupBy {
                com.anchor.core.time.DayRollover.computeDayKey(it.dueAt!!, rule)
            }
        }
    }

    override suspend fun getBoardViewNodes(limitPerColumn: Int): Map<String, List<Node>> = synchronized(this) {
        val nonArchived = activeNodes.filter { it.status != "archived" && it.status != "cancelled" }
        val todo = nonArchived.filter { it.status == "active" || it.status == "paused" }.take(limitPerColumn)
        val inProgress = nonArchived.filter { it.status == "in_progress" }.take(limitPerColumn)
        val done = nonArchived.filter { it.status == "done" || it.status == "closed" }.take(limitPerColumn)

        mapOf(
            "todo" to todo,
            "in_progress" to inProgress,
            "done" to done
        )
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

    /** Ordered like the real table: priority ASC, then created_at ASC. */
    val outbox = mutableListOf<OutboxJob>()

    override suspend fun enqueueOutbox(job: OutboxJob) {
        if (outbox.none { it.idemKey == job.idemKey }) outbox.add(job)
    }

    override suspend fun claimOutbox(id: String, nextTryAt: Long, now: Long) {
        val idx = outbox.indexOfFirst { it.id == id }
        if (idx != -1) {
            val job = outbox[idx]
            outbox[idx] = job.copy(nextTryAt = nextTryAt, updatedAt = now)
        }
    }

    override suspend fun getDueOutbox(jobType: String, now: Long): List<OutboxJob> =
        outbox.filter { it.jobType == jobType && it.status == OutboxJob.STATUS_PENDING && it.nextTryAt <= now }
            .sortedWith(compareBy({ it.priority }, { it.createdAt }, { it.id }))

    override suspend fun getPendingOutbox(jobType: String): List<OutboxJob> =
        outbox.filter { it.jobType == jobType && it.status == OutboxJob.STATUS_PENDING }
            .sortedWith(compareBy({ it.priority }, { it.createdAt }, { it.id }))

    override suspend fun markOutboxDone(id: String, now: Long) {
        val i = outbox.indexOfFirst { it.id == id }
        if (i >= 0) outbox[i] = outbox[i].copy(status = OutboxJob.STATUS_DONE, updatedAt = now)
    }

    override suspend fun markOutboxFailed(id: String, now: Long, nextTryAt: Long, error: String) {
        val i = outbox.indexOfFirst { it.id == id }
        if (i >= 0) outbox[i] = outbox[i].copy(
            status = OutboxJob.STATUS_PENDING, attempts = outbox[i].attempts + 1,
            nextTryAt = nextTryAt, updatedAt = now, lastError = error
        )
    }

    override suspend fun dropStaleOutbox(now: Long): Int = synchronized(this) {
        var count = 0
        for (i in outbox.indices) {
            val job = outbox[i]
            if (job.status == OutboxJob.STATUS_PENDING && job.deadlineAt != null && job.deadlineAt < now) {
                outbox[i] = job.copy(status = OutboxJob.STATUS_DROPPED, updatedAt = now)
                count++
            }
        }
        count
    }

    val signals = mutableListOf<com.anchor.core.models.SignalLog>()
    val learnedPrefs = mutableListOf<com.anchor.core.models.LearnedPref>()

    override suspend fun recordSignal(signal: com.anchor.core.models.SignalLog) {
        synchronized(this) {
            signals.add(signal)
        }
    }

    override suspend fun getSignals(kind: String?, sinceUtc: Long, limit: Int): List<com.anchor.core.models.SignalLog> =
        synchronized(this) {
            signals.filter { (kind == null || it.kind == kind) && it.tsUtc >= sinceUtc }
                .sortedBy { it.tsUtc }
                .take(limit.coerceAtLeast(0))
        }

    override suspend fun getLearnedPref(key: String, scope: String): com.anchor.core.models.LearnedPref? =
        synchronized(this) {
            learnedPrefs.find { it.key == key && it.scope == scope }
        }

    override suspend fun getAllLearnedPrefs(): List<com.anchor.core.models.LearnedPref> =
        synchronized(this) {
            learnedPrefs.toList()
        }

    override suspend fun saveLearnedPref(pref: com.anchor.core.models.LearnedPref) {
        synchronized(this) {
            val idx = learnedPrefs.indexOfFirst { it.key == pref.key && it.scope == pref.scope }
            if (idx >= 0) {
                learnedPrefs[idx] = pref
            } else {
                learnedPrefs.add(pref)
            }
        }
    }

    override suspend fun resetLearning(preserveLocked: Boolean) {
        synchronized(this) {
            if (preserveLocked) {
                learnedPrefs.removeAll { !it.locked }
            } else {
                learnedPrefs.clear()
            }
        }
    }

    fun saveNode(node: Node) {
        synchronized(this) {
            activeNodes.removeAll { it.id == node.id }
            activeNodes.add(node)
        }
    }

    val embeddings = mutableMapOf<Triple<String, String, String>, com.anchor.core.retrieval.QuantVector>()

    override suspend fun saveEmbedding(
        ownerType: String,
        ownerId: String,
        modelVersion: String,
        vector: com.anchor.core.retrieval.QuantVector
    ) {
        synchronized(this) {
            embeddings[Triple(ownerType, ownerId, modelVersion)] = vector
        }
    }

    override suspend fun getEmbeddings(
        ownerType: String,
        modelVersion: String
    ): List<Pair<String, com.anchor.core.retrieval.QuantVector>> = synchronized(this) {
        embeddings.entries
            .filter { it.key.first == ownerType && it.key.third == modelVersion }
            .map { it.key.second to it.value }
    }

    override suspend fun deleteEmbedding(ownerType: String, ownerId: String) {
        synchronized(this) {
            embeddings.keys.filter { it.first == ownerType && it.second == ownerId }
                .toList()
                .forEach { embeddings.remove(it) }
        }
    }

    // Blueprint 43: Context Sensing (Places and Signals)
    val places = mutableListOf<com.anchor.core.sensing.Place>()
    val placeSignals = mutableMapOf<String, com.anchor.core.sensing.PlaceSignal>()
    val sensingEvents = mutableListOf<com.anchor.core.sensing.SensingEvent>()

    override suspend fun savePlace(place: com.anchor.core.sensing.Place, signal: com.anchor.core.sensing.PlaceSignal?) {
        synchronized(this) {
            val idx = places.indexOfFirst { it.id == place.id }
            if (idx != -1) places[idx] = place else places.add(place)
            if (signal != null) {
                placeSignals[place.id] = signal
            }
        }
    }

    override suspend fun getPlaces(): List<com.anchor.core.sensing.Place> = synchronized(this) {
        places.filter { it.deletedAt == null }
    }

    override suspend fun getPlace(id: String): com.anchor.core.sensing.Place? = synchronized(this) {
        places.find { it.id == id && it.deletedAt == null }
    }

    override suspend fun getPlaceSignals(): List<Pair<com.anchor.core.sensing.Place, com.anchor.core.sensing.PlaceSignal>> = synchronized(this) {
        places.filter { it.deletedAt == null }.mapNotNull { place ->
            val sig = placeSignals[place.id]
            if (sig != null) place to sig else null
        }
    }

    override suspend fun deletePlace(id: String, deletedAt: Long) {
        synchronized(this) {
            val idx = places.indexOfFirst { it.id == id }
            if (idx != -1) {
                places[idx] = places[idx].copy(deletedAt = deletedAt)
            }
        }
    }

    override suspend fun recordSensingEvent(event: com.anchor.core.sensing.SensingEvent) {
        synchronized(this) {
            sensingEvents.add(event)
        }
    }

    override suspend fun getRecentSensingEvents(limit: Int): List<com.anchor.core.sensing.SensingEvent> = synchronized(this) {
        sensingEvents.sortedByDescending { it.tsUtc }.take(limit.coerceAtLeast(0))
    }

    override suspend fun pruneSensingEvents(olderThanUtc: Long): Int = synchronized(this) {
        val initial = sensingEvents.size
        sensingEvents.removeAll { it.tsUtc < olderThanUtc }
        initial - sensingEvents.size
    }

    // Blueprint 44: Call Nudge (Call Events)
    val callEvents = mutableListOf<com.anchor.core.nudge.CallEvent>()

    override suspend fun saveCallEvent(callEvent: com.anchor.core.nudge.CallEvent) {
        synchronized(this) {
            val idx = callEvents.indexOfFirst { it.id == callEvent.id }
            if (idx != -1) callEvents[idx] = callEvent else callEvents.add(callEvent)
        }
    }

    override suspend fun getCallEvent(id: String): com.anchor.core.nudge.CallEvent? = synchronized(this) {
        callEvents.find { it.id == id }
    }

    override suspend fun getRecentCallEvents(limit: Int): List<com.anchor.core.nudge.CallEvent> = synchronized(this) {
        callEvents.sortedWith(
            compareByDescending<com.anchor.core.nudge.CallEvent> { it.endedAt }
                .thenByDescending { it.createdAt }
                .thenByDescending { it.id }
        ).take(limit.coerceAtLeast(0))
    }

    override suspend fun getCallEventsBetween(startUtc: Long, endUtc: Long): List<com.anchor.core.nudge.CallEvent> = synchronized(this) {
        callEvents.filter { it.endedAt in startUtc..endUtc }
    }

    override suspend fun updateCallEventState(id: String, state: com.anchor.core.nudge.CallNudgeState, nudgeId: String?) {
        synchronized(this) {
            val idx = callEvents.indexOfFirst { it.id == id }
            if (idx != -1) {
                val current = callEvents[idx]
                callEvents[idx] = current.copy(state = state, nudgeId = nudgeId ?: current.nudgeId)
            }
        }
    }

    override suspend fun pruneCallEvents(olderThanUtc: Long): Int = synchronized(this) {
        val initial = callEvents.size
        callEvents.removeAll { it.endedAt < olderThanUtc }
        initial - callEvents.size
    }

    // Blueprint 60: Voice (Voice Jobs & Model Assets)
    val voiceJobs = mutableListOf<com.anchor.core.voice.VoiceJob>()
    val modelAssets = mutableListOf<com.anchor.core.voice.ModelAsset>()

    override suspend fun saveVoiceJob(job: com.anchor.core.voice.VoiceJob) {
        synchronized(this) {
            val idx = voiceJobs.indexOfFirst { it.id == job.id }
            if (idx != -1) voiceJobs[idx] = job else voiceJobs.add(job)
        }
    }

    override suspend fun getVoiceJob(id: String): com.anchor.core.voice.VoiceJob? = synchronized(this) {
        voiceJobs.find { it.id == id }
    }

    override suspend fun getRecentVoiceJobs(limit: Int): List<com.anchor.core.voice.VoiceJob> = synchronized(this) {
        voiceJobs.sortedByDescending { it.updatedAt }.take(limit.coerceAtLeast(0))
    }

    override suspend fun updateVoiceJobState(id: String, state: com.anchor.core.voice.VoiceJobState, error: String?) {
        synchronized(this) {
            val idx = voiceJobs.indexOfFirst { it.id == id }
            if (idx != -1) {
                val current = voiceJobs[idx]
                voiceJobs[idx] = current.copy(state = state, error = error ?: current.error, updatedAt = clock.now())
            }
        }
    }

    override suspend fun saveModelAsset(asset: com.anchor.core.voice.ModelAsset) {
        synchronized(this) {
            val idx = modelAssets.indexOfFirst { it.id == asset.id }
            if (idx != -1) modelAssets[idx] = asset else modelAssets.add(asset)
        }
    }

    override suspend fun getModelAssets(): List<com.anchor.core.voice.ModelAsset> = synchronized(this) {
        modelAssets.toList()
    }

    override suspend fun getModelAsset(id: String): com.anchor.core.voice.ModelAsset? = synchronized(this) {
        modelAssets.find { it.id == id }
    }

    override suspend fun pruneOperationalLogs(now: Long): Int = synchronized(this) {
        val fireCutoff = now - 90 * 86_400_000L
        val outboxCutoff = now - 7 * 86_400_000L

        reminderFires.removeAll { it.firedAt < fireCutoff }
        outbox.removeAll { it.status in listOf(OutboxJob.STATUS_DONE, OutboxJob.STATUS_DROPPED) && it.updatedAt < outboxCutoff }
        1
    }

    // Ambient storage in SharedFakeStorage
    private val voiceprints = mutableListOf<com.anchor.core.ambient.Voiceprint>()
    private val ambientSessions = mutableListOf<com.anchor.core.ambient.AmbientSession>()
    private val ambientWindows = mutableListOf<com.anchor.core.ambient.AmbientWindow>()
    private val ambientSegments = mutableListOf<com.anchor.core.ambient.AmbientSegment>()
    private val ambientReports = mutableListOf<com.anchor.core.ambient.AmbientReport>()
    private val ambientReportItems = mutableListOf<com.anchor.core.ambient.AmbientReportItem>()
    private val dayDigests = mutableMapOf<String, com.anchor.core.consolidation.DayDigest>()

    override suspend fun saveVoiceprint(voiceprint: com.anchor.core.ambient.Voiceprint) {
        synchronized(this) {
            voiceprints.clear()
            voiceprints.add(voiceprint)
        }
    }
    override suspend fun getVoiceprint(): com.anchor.core.ambient.Voiceprint? = synchronized(this) {
        voiceprints.firstOrNull()
    }
    override suspend fun deleteVoiceprint() {
        synchronized(this) { voiceprints.clear() }
    }

    override suspend fun saveAmbientSession(session: com.anchor.core.ambient.AmbientSession) {
        synchronized(this) {
            val idx = ambientSessions.indexOfFirst { it.id == session.id }
            if (idx != -1) ambientSessions[idx] = session else ambientSessions.add(session)
        }
    }
    override suspend fun getRecentAmbientSessions(limit: Int): List<com.anchor.core.ambient.AmbientSession> = synchronized(this) {
        ambientSessions.sortedByDescending { it.startedAt }.take(limit.coerceAtLeast(0))
    }

    override suspend fun saveAmbientWindow(window: com.anchor.core.ambient.AmbientWindow) {
        synchronized(this) {
            val idx = ambientWindows.indexOfFirst { it.id == window.id }
            if (idx != -1) ambientWindows[idx] = window else ambientWindows.add(window)
        }
    }
    override suspend fun getAmbientWindows(sessionId: String): List<com.anchor.core.ambient.AmbientWindow> = synchronized(this) {
        ambientWindows.filter { it.sessionId == sessionId }
    }

    override suspend fun saveAmbientSegment(segment: com.anchor.core.ambient.AmbientSegment) {
        synchronized(this) {
            val idx = ambientSegments.indexOfFirst { it.id == segment.id }
            if (idx != -1) ambientSegments[idx] = segment else ambientSegments.add(segment)
        }
    }
    override suspend fun getAmbientSegments(windowId: String): List<com.anchor.core.ambient.AmbientSegment> = synchronized(this) {
        ambientSegments.filter { it.windowId == windowId }
    }

    override suspend fun saveAmbientReport(report: com.anchor.core.ambient.AmbientReport) {
        synchronized(this) {
            val idx = ambientReports.indexOfFirst { it.id == report.id }
            if (idx != -1) ambientReports[idx] = report else ambientReports.add(report)
        }
    }
    override suspend fun getAmbientReport(id: String): com.anchor.core.ambient.AmbientReport? = synchronized(this) {
        ambientReports.find { it.id == id }
    }
    override suspend fun getLatestAmbientReport(): com.anchor.core.ambient.AmbientReport? = synchronized(this) {
        ambientReports.maxByOrNull { it.generatedAt }
    }
    override suspend fun updateAmbientReportState(id: String, state: String) {
        synchronized(this) {
            val idx = ambientReports.indexOfFirst { it.id == id }
            if (idx != -1) ambientReports[idx] = ambientReports[idx].copy(state = state)
        }
    }

    override suspend fun saveAmbientReportItem(item: com.anchor.core.ambient.AmbientReportItem) {
        synchronized(this) {
            val idx = ambientReportItems.indexOfFirst { it.id == item.id }
            if (idx != -1) ambientReportItems[idx] = item else ambientReportItems.add(item)
        }
    }
    override suspend fun getAmbientReportItems(reportId: String): List<com.anchor.core.ambient.AmbientReportItem> = synchronized(this) {
        ambientReportItems.filter { it.reportId == reportId }
    }
    override suspend fun updateAmbientReportItemState(id: String, state: String, reviewedAt: Long?) {
        synchronized(this) {
            val idx = ambientReportItems.indexOfFirst { it.id == id }
            if (idx != -1) ambientReportItems[idx] = ambientReportItems[idx].copy(state = state, reviewedAt = reviewedAt)
        }
    }

    override suspend fun pruneAmbientStaging(now: Long): Int = synchronized(this) {
        val segmentCountBefore = ambientSegments.size
        ambientSegments.removeAll { it.expiresAt < now }
        val proposalCountBefore = ambientReportItems.size
        ambientReportItems.removeAll { it.kind == "proposal" && it.state == "pending" && it.reviewedAt == null }
        (segmentCountBefore - ambientSegments.size) + (proposalCountBefore - ambientReportItems.size)
    }

    override suspend fun deleteAllAmbientData(): Int = synchronized(this) {
        val count = ambientSegments.size + ambientReportItems.size + ambientReports.size + ambientWindows.size
        ambientSegments.clear()
        ambientReportItems.clear()
        ambientReports.clear()
        ambientWindows.clear()
        ambientSessions.clear()
        voiceprints.clear()
        count
    }

    override suspend fun saveDayDigest(digest: com.anchor.core.consolidation.DayDigest) {
        synchronized(this) { dayDigests[digest.dayKey] = digest }
    }
    override suspend fun getDayDigest(dayKey: String): com.anchor.core.consolidation.DayDigest? = synchronized(this) {
        dayDigests[dayKey]
    }
}