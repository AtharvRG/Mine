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

    val loggedEvents = mutableListOf<RawEvent>()
    val savedProposals = mutableListOf<Proposal>()

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

    override suspend fun getActiveNodes(): List<Node> = activeNodes.filter { it.status == "active" || it.status == "in_progress" || it.status == "paused" }
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

    override suspend fun updateNodeAndCommit(node: Node, event: RawEvent, deleteReminderId: String?) {
        activeNodes.removeAll { it.id == node.id }
        activeNodes.add(node)
        if (deleteReminderId != null) {
            reminders.removeAll { it.id == deleteReminderId }
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
}