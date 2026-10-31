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

        return when (call.tool) {
            "search_memory" -> {
                val query = args["query"] ?: ""
                val limit = args["limit"]?.toIntOrNull() ?: 6
                val results = storage.searchMemoryFts(query, limit)
                val text = if (results.isEmpty()) "No results found." else results.joinToString("\n") { "${it.id} | ${it.kind} | ${it.title} | ${it.details}" }
                ToolResult.Data(text)
            }
            "soul_propose" -> {
                val statement = args["statement"] ?: return ToolResult.Error("Missing statement")
                val category = args["category"] ?: "other"
                val confidence = args["confidence"]?.toDoubleOrNull() ?: 0.7
                val sId = "s_${UUID.randomUUID().toString().take(8)}"
                val fact = SoulFact(sId, category, statement, confidence, "chat_ai", "extracted", "proposed", clock.now())
                
                val uId = "u_${UUID.randomUUID().toString().take(6)}"
                val event = RawEvent(uId, "CREATE", "soul_fact", sId, afterJson = json.encodeToString(fact))
                storage.createSoulFactAndCommit(fact, event)
                
                ToolResult.Committed("CREATE", sId, "Proposed to Soul: $statement", uId)
            }
            "undo" -> handleUndo(args["token"] ?: "")
            "confirm_change" -> handleConfirmChange(args["proposal"] ?: "", args["choice"] ?: "")
            "schedule_reminder" -> handleScheduleReminder(args["target_ref"] ?: "", args["when_text"] ?: "")
            "propose_change" -> {
                val intentStr = args["intent"]?.uppercase() ?: return ToolResult.Error("Missing intent")
                val intent = try { IntentType.valueOf(intentStr) } catch(e: Exception) { return ToolResult.Error("Unknown intent") }
                val hint = (args["hint"] ?: args["title"] ?: "").take(200) // B3: Length cap
                val dueText = args["due_text"]
                
                val targetTimeMillis = if (dueText.isNullOrBlank()) null else {
                    dateResolver.parse(dueText) ?: return ToolResult.Error("Could not understand the time '$dueText'. Ask the user to clarify.")
                }

                when (intent) {
                    IntentType.CREATE -> {
                        if (hint.isBlank()) return ToolResult.Error("Title cannot be blank")
                        // B3: Validate Type
                        val type = (args["type"] ?: "task").lowercase()
                        val validTypes = listOf("task", "event", "note", "habit")
                        val safeType = if (type in validTypes) type else "task"
                        
                        // B4: Deduplicate
                        val active = storage.getActiveNodes()
                        val duplicate = active.find { it.title.equals(hint, ignoreCase = true) && it.status != "done" && it.status != "cancelled" }
                        if (duplicate != null) {
                            return ToolResult.Error("An active item named '${duplicate.title}' already exists.")
                        }
                        
                        handleCreate(hint, safeType, targetTimeMillis)
                    }
                    IntentType.CANCEL, IntentType.COMPLETE, IntentType.UPDATE, IntentType.RESCHEDULE -> handleResolveChange(intent, hint, targetTimeMillis)
                    else -> ToolResult.Error("$intent not supported yet")
                }
            }
            else -> ToolResult.Error("Tool not supported yet")
        }
    }

    private suspend fun handleScheduleReminder(targetRef: String, whenText: String): ToolResult {
        val fireAt = dateResolver.parse(whenText) ?: return ToolResult.Error("Could not understand the time '$whenText'. Ask the user to clarify.")
        val node = if (targetRef.isNotBlank()) storage.getNode(targetRef) else null
        
        // A8: Reject bad target refs silently making orphan "Reminder"s
        if (targetRef.isNotBlank() && node == null) {
            return ToolResult.Error("Target node not found for reference '$targetRef'")
        }
        
        val title = node?.title ?: "Reminder"
        val rId = if (node != null) "r_${node.id}" else "r_${UUID.randomUUID().toString().take(8)}"
        
        val reminder = Reminder(rId, title, fireAt)
        val uId = "u_${UUID.randomUUID().toString().take(6)}"
        
        val event = RawEvent(uId, "SCHEDULE", "reminder", rId, afterJson = json.encodeToString(reminder))
        storage.createReminderAndCommit(reminder, event)
        
        return ToolResult.Committed("SCHEDULE", rId, "Reminder set for ${dateResolver.format(fireAt)}", uId)
    }

    private suspend fun handleConfirmChange(proposalId: String, choice: String): ToolResult {
        val proposal = storage.getProposal(proposalId) ?: return ToolResult.Error("Proposal not found")
        if (proposal.status != "open") return ToolResult.Error("Proposal already resolved or expired")

        val nodeId = proposal.optionsIds.find { it == choice } ?: return ToolResult.Error("Invalid choice")
        val node = storage.getNode(nodeId) ?: return ToolResult.Error("Node not found")
        if (node.status != "active" && node.status != "in_progress") return ToolResult.Error("Node is no longer active")

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
        
        // BP 30: Propose change creates node only. Alarms require schedule_reminder.
        val afterJson = json.encodeToString(NodeStateSnapshot(node, null))
        val uId = "u_${UUID.randomUUID().toString().take(6)}"
        val event = RawEvent(uId, IntentType.CREATE.name, "node", nId, afterJson = afterJson)
        
        storage.createNodeAndCommit(node, event, null)
        
        val timeStr = if (dueAt != null) " (Due: ${dateResolver.format(dueAt)})" else ""
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
                
                val newNode = when (intent) {
                    IntentType.CANCEL -> oldNode.copy(status = "cancelled", closedAt = now, updatedAt = now)
                    IntentType.COMPLETE -> oldNode.copy(status = "done", closedAt = now, updatedAt = now)
                    IntentType.RESCHEDULE -> oldNode.copy(dueAt = targetTimeMillis ?: oldNode.dueAt, updatedAt = now)
                    else -> oldNode.copy(updatedAt = now) // UPDATE
                }
                
                val remId = "r_${oldNode.id}"
                val activeRems = storage.getPendingReminders()
                val oldRem = activeRems.find { it.id == remId }
                
                // Keep old reminder on UPDATE. Recreate at new time on RESCHEDULE. Drop on CANCEL/COMPLETE.
                val newRem = if (intent == IntentType.RESCHEDULE && targetTimeMillis != null) {
                    Reminder(remId, newNode.title, targetTimeMillis)
                } else if (intent == IntentType.UPDATE) {
                    oldRem 
                } else null
                
                val deleteReminderId = if (intent == IntentType.CANCEL || intent == IntentType.COMPLETE) remId else null

                val beforeJson = json.encodeToString(NodeStateSnapshot(oldNode, oldRem))
                val afterJson = json.encodeToString(NodeStateSnapshot(newNode, newRem))

                val uId = "u_${UUID.randomUUID().toString().take(6)}"
                val event = RawEvent(uId, intent.name, "node", newNode.id, beforeJson = beforeJson, afterJson = afterJson)
                
                storage.updateNodeAndCommit(newNode, event, deleteReminderId, newRem)
                return ToolResult.Committed(intent.name, newNode.id, "Applied ${intent.name}: ${newNode.title}", uId)
            }
        }
    }

    private suspend fun handleUndo(token: String): ToolResult {
        if (storage.hasUndo(token)) return ToolResult.Error("Already undone")
        val event = storage.getEvent(token) ?: return ToolResult.Error("Undo token not found")
        val afterJson = event.afterJson ?: return ToolResult.Error("No after_json found")
        val newEventId = "u_${UUID.randomUUID().toString().take(6)}"

        if (event.targetType == "reminder") {
            if (event.opType == "SCHEDULE") {
                val undoEvent = RawEvent(newEventId, "UNDO_SCHEDULE", "reminder", event.targetId, undoOf = token, beforeJson = afterJson, afterJson = null)
                storage.deleteReminderAndCommit(event.targetId, undoEvent)
                return ToolResult.Committed("UNDO", event.targetId, "Removed reminder", newEventId)
            }
            return ToolResult.Error("Undo for this reminder operation not supported")
        }

        val currentNode = storage.getNode(event.targetId) ?: return ToolResult.Error("Node missing")
        val currentReminder = storage.getPendingReminders().find { it.id == "r_${event.targetId}" }
        val currentSnapshot = NodeStateSnapshot(currentNode, currentReminder)
        val expectedAfterSnapshot = try { json.decodeFromString<NodeStateSnapshot>(afterJson) } catch (e: Exception) { return ToolResult.Error("Corrupted JSON") }
        
        if (currentSnapshot != expectedAfterSnapshot) return ToolResult.Error("Node state has changed, cannot undo")
        
        if (event.opType == "CREATE") {
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
