// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core

import com.anchor.core.alarms.Reminder
import com.anchor.core.models.*
import com.anchor.core.storage.Storage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

class SharedFakeStorage : Storage {
    override val ioDispatcher: CoroutineDispatcher = Dispatchers.Unconfined
    
    var activeNodes = mutableListOf<Node>()
    var messages = mutableListOf<ChatMessage>()
    var reminders = mutableListOf<Reminder>()
    var settings = mutableMapOf<String, String>()
    
    val loggedEvents = mutableListOf<RawEvent>()
    val savedProposals = mutableListOf<Proposal>()

    override suspend fun insertRawMessage(id: String, role: String, content: String) {
        messages.add(ChatMessage(id, role, content))
    }
    override suspend fun getRawMessage(id: String): String? = messages.find { it.id == id }?.content
    override suspend fun getRecentMessages(limit: Int): List<ChatMessage> = messages.takeLast(limit)
    
    override suspend fun getSetting(key: String): String? = settings[key]
    override suspend fun saveSetting(key: String, value: String) { settings[key] = value }
    
    override suspend fun getActiveNodes(): List<Node> = activeNodes.filter { it.status == "active" }
    override suspend fun updateNodeStatus(nodeId: String, newStatus: String) {
        activeNodes = activeNodes.map { if (it.id == nodeId) it.copy(status = newStatus) else it }.toMutableList()
    }
    override suspend fun logEvent(event: RawEvent) { loggedEvents.add(event) }
    override suspend fun commitChange(node: Node?, event: RawEvent, reminder: Reminder?) {
        if (node != null) {
            activeNodes.removeAll { it.id == node.id }
            activeNodes.add(node)
        }
        if (reminder != null) {
            reminders.removeAll { it.id == reminder.id }
            reminders.add(reminder)
        }
        loggedEvents.add(event)
    }
    override suspend fun saveProposal(proposal: Proposal) { savedProposals.add(proposal) }

    override suspend fun saveReminder(reminder: Reminder) {
        reminders.removeAll { it.id == reminder.id }
        reminders.add(reminder)
    }
    override suspend fun getActiveReminders(): List<Reminder> = reminders
    override suspend fun deleteReminder(id: String) { reminders.removeAll { it.id == id } }
    override suspend fun getDebugStats(): Map<String, String> = emptyMap()
}
