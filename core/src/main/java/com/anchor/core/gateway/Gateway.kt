// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.gateway

import com.anchor.core.models.*
import com.anchor.core.resolver.Resolver
import com.anchor.core.storage.Storage
import com.anchor.core.time.Clock
import com.anchor.core.time.DateResolver
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

class Gateway(
    private val storage: Storage, 
    private val resolver: Resolver,
    private val dateResolver: DateResolver,
    private val clock: Clock
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun executeTool(call: ToolCall, executedCalls: MutableSet<String> = mutableSetOf()): ToolResult {
        val args = EnvelopeParser.resolveArgs(call)
        val callHash = call.tool + args.toString()
        if (!executedCalls.add(callHash)) return ToolResult.Error("Duplicate tool call ignored")

        if (call.tool == "undo") return handleUndo(args["token"] ?: "")
        if (call.tool == "confirm_change") return handleConfirmChange(args["proposal"] ?: "", args["choice"] ?: "")
        if (call.tool != "propose_change") return ToolResult.Error("Tool not supported yet")
        
        val intentStr = args["intent"]?.uppercase() ?: return ToolResult.Error("Missing intent")
        val intent = try { IntentType.valueOf(intentStr) } catch(e: Exception) { return ToolResult.Error("Unknown intent") }
        val hint = args["hint"] ?: args["title"] ?: ""
        val dueText = args["due_text"]
        
        val targetTimeMillis = if (dueText.isNullOrBlank()) null else {
            dateResolver.parse(dueText) ?: return ToolResult.Error("Could not understand the time '$dueText'. Ask the user to clarify.")
        }

        return when (intent) {
            IntentType.CREATE -> {
                if (hint.isBlank()) return ToolResult.Error("Title cannot be blank")
                handleCreate(hint, args["type"] ?: "task", targetTimeMillis)
            }
            IntentType.CANCEL, IntentType.COMPLETE -> handleResolveChange(intent, hint, targetTimeMillis)
            else -> ToolResult.Error("$intent not supported yet")
        }
    }

    private suspend fun handleConfirmChange(proposalId: String, choice: String): ToolResult {
        val proposal = storage.getProposal(proposalId) ?: return ToolResult.Error("Proposal not found")
        if (proposal.status != "open") return ToolResult.Error("Proposal already resolved or expired")

        val nodeId = proposal.optionsIds.find { it == choice } ?: return ToolResult.Error("Invalid choice")
        val node = storage.getNode(nodeId) ?: return ToolResult.Error("Node not found")
        if (node.status != "active") return ToolResult.Error("Node is no longer active")

        storage.consumeProposal(proposalId)

        val now = clock.now()
        val newNode = node.copy(status = if (proposal.intent == IntentType.CANCEL) "cancelled" else "done", closedAt = now, updatedAt = now)
        val remId = "r_${node.id}"
        val activeRems = storage.getPendingReminders()
        val oldRem = activeRems.find { it.id == remId }

        val beforeJson = json.encodeToString(NodeStateSnapshot(node, oldRem))
        val afterJson = json.encodeToString(NodeStateSnapshot(newNode, null))

        val uId = "u_${UUID.randomUUID().toString().take(6)}"
        val event = RawEvent(uId, proposal.intent.name, "node", newNode.id, beforeJson = beforeJson, afterJson = afterJson)
        
        storage.updateNodeAndCommit(newNode, event, remId)
        return ToolResult.Committed(proposal.intent.name, newNode.id, "Applied ${proposal.intent.name}: ${newNode.title}", uId)
    }

    private suspend fun handleCreate(title: String, type: String, dueAt: Long?): ToolResult {
        val nId = "n_${UUID.randomUUID().toString().take(8)}"
        val now = clock.now()
        val node = Node(nId, type, title, "active", dueAt, createdAt = now, updatedAt = now)
        val reminder = dueAt?.let { Reminder("r_$nId", title, it) }
        
        val afterJson = json.encodeToString(NodeStateSnapshot(node, reminder))
        val uId = "u_${UUID.randomUUID().toString().take(6)}"
        val event = RawEvent(uId, IntentType.CREATE.name, "node", nId, afterJson = afterJson)
        
        storage.createNodeAndCommit(node, event, reminder)
        
        val timeStr = if (dueAt != null) ". Reminder set for ${dateResolver.format(dueAt)}" else ""
        return ToolResult.Committed(IntentType.CREATE.name, nId, "Created: $title$timeStr", uId)
    }

    private suspend fun handleResolveChange(intent: IntentType, hint: String, targetTimeMillis: Long?): ToolResult {
        val candidates = storage.getActiveNodes()
        val resolution = resolver.resolve(intent, hint, targetTimeMillis, candidates)

        return when (resolution) {
            is Resolution.NotFound -> ToolResult.NotFound(hint)
            is Resolution.Ambiguous -> {
                val pId = "p_${UUID.randomUUID().toString().take(6)}"
                storage.saveProposal(Proposal(pId, "ambiguous", intent, resolution.options.map { it.id }))
                ToolResult.Ambiguous(pId, "ask: which item", resolution.options.map { "${it.id} '${it.title}'" })
            }
            is Resolution.ClearWinner -> {
                val oldNode = resolution.node
                val now = clock.now()
                val newNode = oldNode.copy(
                    status = if (intent == IntentType.CANCEL) "cancelled" else "done",
                    closedAt = now, updatedAt = now
                )
                
                val remId = "r_${oldNode.id}"
                val activeRems = storage.getPendingReminders()
                val oldRem = activeRems.find { it.id == remId }

                val beforeJson = json.encodeToString(NodeStateSnapshot(oldNode, oldRem))
                val afterJson = json.encodeToString(NodeStateSnapshot(newNode, null))

                val uId = "u_${UUID.randomUUID().toString().take(6)}"
                val event = RawEvent(uId, intent.name, "node", newNode.id, beforeJson = beforeJson, afterJson = afterJson)
                
                storage.updateNodeAndCommit(newNode, event, remId)
                return ToolResult.Committed(intent.name, newNode.id, "Applied ${intent.name}: ${newNode.title}", uId)
            }
        }
    }

    private suspend fun handleUndo(token: String): ToolResult {
        if (storage.hasUndo(token)) return ToolResult.Error("Already undone")
        
        val event = storage.getEvent(token) ?: return ToolResult.Error("Undo token not found")
        val afterJson = event.afterJson ?: return ToolResult.Error("No after_json found")
        
        val currentNode = storage.getNode(event.targetId) ?: return ToolResult.Error("Node missing")
        val currentReminder = storage.getPendingReminders().find { it.id == "r_${event.targetId}" }
        val currentSnapshot = NodeStateSnapshot(currentNode, currentReminder)
        
        val expectedAfterSnapshot = try { json.decodeFromString<NodeStateSnapshot>(afterJson) } catch (e: Exception) { return ToolResult.Error("Corrupted JSON") }
        
        if (currentSnapshot != expectedAfterSnapshot) return ToolResult.Error("Node state has changed, cannot undo")
        
        val newEventId = "u_${UUID.randomUUID().toString().take(6)}"
        
        if (event.opType == "CREATE") {
            // Undo a CREATE: Mark cancelled, remove reminder
            val cancelledNode = currentNode.copy(status = "cancelled", closedAt = clock.now(), updatedAt = clock.now())
            val undoEvent = RawEvent(newEventId, "UNDO_CREATE", "node", currentNode.id, undoOf = token, beforeJson = afterJson, afterJson = json.encodeToString(NodeStateSnapshot(cancelledNode, null)))
            storage.updateNodeAndCommit(cancelledNode, undoEvent, "r_${currentNode.id}")
            return ToolResult.Committed("UNDO", currentNode.id, "Removed: ${currentNode.title}", newEventId)
        } else {
            val beforeJson = event.beforeJson ?: return ToolResult.Error("No previous state to restore")
            val snapshot = try { json.decodeFromString<NodeStateSnapshot>(beforeJson) } catch (e: Exception) { return ToolResult.Error("Corrupted state") }
            val undoEvent = RawEvent(newEventId, "UNDO", "node", snapshot.node.id, undoOf = token, beforeJson = afterJson, afterJson = beforeJson)
            storage.undoChangeAndCommit(snapshot.node, undoEvent, snapshot.reminder)
            return ToolResult.Committed("UNDO", snapshot.node.id, "Restored: ${snapshot.node.title}", newEventId)
        }
    }
}
