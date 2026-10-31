// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.storage

import com.anchor.core.models.ChatMessage
import com.anchor.core.models.Node
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

    suspend fun getActiveNodes(): List<Node>
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
    suspend fun getActiveSoulFacts(): List<SoulFact>
    suspend fun getProposedSoulFacts(): List<SoulFact>
    suspend fun updateSoulFactStatus(id: String, status: String)

    // Purely Transactional Gateways
    suspend fun createNodeAndCommit(node: Node, event: RawEvent, reminder: Reminder?)
    suspend fun updateNodeAndCommit(node: Node, event: RawEvent, deleteReminderId: String?, upsertReminder: Reminder? = null)
    suspend fun undoChangeAndCommit(node: Node, event: RawEvent, restoreReminder: Reminder?)
    suspend fun createReminderAndCommit(reminder: Reminder, event: RawEvent)
    suspend fun deleteReminderAndCommit(reminderId: String, event: RawEvent)

    suspend fun getRegisteredAlarms(): List<Pair<String, Long>>
    suspend fun saveRegisteredAlarm(reminderId: String, requestCode: Int, registeredFor: Long)
    suspend fun deleteRegisteredAlarm(reminderId: String)
}