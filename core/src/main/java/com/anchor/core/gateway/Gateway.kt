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
                // B3: Cap statement length
                val statement = (args["statement"] ?: return ToolResult.Error("Missing statement")).take(500)
                val category = (args["category"] ?: "other").take(100)
                val confidence = args["confidence"]?.toDoubleOrNull() ?: 0.7
                
                val sId = "s_${UUID.randomUUID().toString().take(8)}"
                val fact = SoulFact(sId, category, statement, confidence, "chat_ai", "extracted", "proposed", clock.now())
                
                val uId = "u_${UUID.randomUUID().toString().take(6)}"
                val event = RawEvent(uId, "CREATE", "soul_fact", sId, afterJson = json.encodeToString(fact), tsUtc = clock.now())
                storage.createSoulFactAndCommit(fact, event)
                
                ToolResult.Committed("CREATE", sId, "Proposed to Soul: $statement", uId)
            }
            "undo" -> handleUndo(args["token"] ?: "")
            "confirm_change" -> handleConfirmChange(args["proposal"] ?: "", args["choice"] ?: "")
            "schedule_reminder" -> {
                val tierStr = args["tier"] ?: "alarm"
                val tier = if (tierStr.equals("gentle", ignoreCase = true)) AlarmTier.GENTLE else AlarmTier.ALARM
                handleScheduleReminder(args["target_ref"] ?: args["target"] ?: "", args["when_text"] ?: args["when"] ?: "", tier)
            }
            "propose_change" -> {
                val intentStr = args["intent"]?.uppercase() ?: return ToolResult.Error("Missing intent")
                val intent = try { IntentType.valueOf(intentStr) } catch(e: Exception) { return ToolResult.Error("Unknown intent") }
                
                // B3: Hard cap LLM hallucinated lengths to prevent DB/UI bloat
                val hint = (args["hint"] ?: args["title"] ?: "").take(200) 
                val dueText = (args["due_text"] ?: "").take(100)
                
                val rolloverMin = storage.getSetting("day_start_minute")?.toIntOrNull() ?: 240
                
                val targetTimeMillis = if (dueText.isBlank()) null else {
                    dateResolver.parse(dueText, rolloverMin) ?: return ToolResult.Error("Could not understand the time '$dueText'. Ask the user to clarify.")
                }

                when (intent) {
                    IntentType.CREATE -> {
                        if (hint.isBlank()) return ToolResult.Error("Title cannot be blank")
                        val type = (args["type"] ?: "task").lowercase().take(50)
                        val validTypes = listOf("task", "event", "note", "habit")
                        val safeType = if (type in validTypes) type else "task"
                        
                        val active = storage.getActiveNodes()
                        val duplicate = active.find { it.title.equals(hint, ignoreCase = true) && it.status != "done" && it.status != "cancelled" }
                        if (duplicate != null) {
                            return ToolResult.Error("An active item named '${duplicate.title}' already exists.")
                        }

                        val followUpTo = args["follow_up_to"] ?: args["follow_up_of"]
                        handleCreate(hint, safeType, targetTimeMillis, followUpTo)
                    }
                    IntentType.CANCEL, IntentType.COMPLETE, IntentType.UPDATE, IntentType.RESCHEDULE -> handleResolveChange(intent, hint, targetTimeMillis)
                    else -> ToolResult.Error("$intent not supported yet")
                }
            }
            else -> ToolResult.Error("Tool not supported yet")
        }
    }

    private suspend fun handleScheduleReminder(targetRef: String, whenText: String, tier: AlarmTier = AlarmTier.ALARM): ToolResult {
        val fireAt = dateResolver.parse(whenText, storage.getSetting("day_start_minute")?.toIntOrNull() ?: 240) ?: return ToolResult.Error("Could not understand the time '$whenText'. Ask the user to clarify.")
        val node = if (targetRef.isNotBlank()) storage.getNode(targetRef) else null

        // A8: Reject bad target refs silently making orphan "Reminder"s
        if (targetRef.isNotBlank() && node == null) {
            return ToolResult.Error("Target node not found for reference '$targetRef'")
        }

        val title = node?.title ?: "Reminder"
        val existingRem = if (node != null) storage.getReminderByNodeId(node.id) else null
        val rId = existingRem?.id ?: "rem_${UUID.randomUUID().toString().take(8)}"
        val reminder = Reminder(rId, title, fireAt, nodeId = node?.id, tier = tier)
        val uId = "u_${UUID.randomUUID().toString().take(6)}"

        val beforeJson = existingRem?.let { json.encodeToString(it) }
        val afterJson = json.encodeToString(reminder)
        val event = RawEvent(uId, "SCHEDULE", "reminder", rId, beforeJson = beforeJson, afterJson = afterJson, tsUtc = clock.now())
        storage.createReminderAndCommit(reminder, event)

        return ToolResult.Committed("SCHEDULE", rId, "Reminder set for ${dateResolver.format(fireAt)}", uId)
    }

    private suspend fun handleConfirmChange(proposalId: String, choice: String): ToolResult {
        val proposal = storage.getProposal(proposalId) ?: return ToolResult.Error("Proposal not found")
        if (proposal.status != "open") return ToolResult.Error("Proposal already resolved")

        val nodeId = proposal.options.find { it.id == choice }?.id ?: return ToolResult.Error("Invalid choice")
        val node = storage.getNode(nodeId) ?: return ToolResult.Error("Node not found")
        if (node.status != "active" && node.status != "in_progress") return ToolResult.Error("Node is no longer active")

        val now = clock.now()
        val newNode = node.copy(status = if (proposal.intent == IntentType.CANCEL) "cancelled" else "done", closedAt = now, updatedAt = now)
        
        // ISSUE 3: Relational alarm lookup prevents orphaned alarms
        val oldRem = storage.getReminderByNodeId(newNode.id)
        val remId = oldRem?.id
        val deleteReminderId = if (proposal.intent == IntentType.CANCEL || proposal.intent == IntentType.COMPLETE) remId else null

        val beforeJson = json.encodeToString(NodeStateSnapshot(node, oldRem))
        val afterJson = json.encodeToString(NodeStateSnapshot(newNode, null))

        val uId = "u_${UUID.randomUUID().toString().take(6)}"
        val event = RawEvent(uId, proposal.intent.name, "node", newNode.id, beforeJson = beforeJson, afterJson = afterJson, tsUtc = clock.now())
        
        storage.updateNodeAndCommit(newNode, event, deleteReminderId = deleteReminderId, upsertReminder = null, consumeProposalId = proposalId)
        return ToolResult.Committed(proposal.intent.name, newNode.id, "Applied ${proposal.intent.name}: ${newNode.title}", uId)
    }

    private suspend fun handleCreate(title: String, type: String, dueAt: Long?, followUpTo: String? = null): ToolResult {
        val nId = "n_${UUID.randomUUID().toString().take(8)}"
        val now = clock.now()
        val node = Node(nId, type, title, "active", dueAt, createdAt = now, updatedAt = now)

        // Fix: Auto-create the reminder inherently so the LLM doesn't have to call a separate tool
        val reminder = dueAt?.let { Reminder("r_$nId", title, it, nId) }

        val afterJson = json.encodeToString(NodeStateSnapshot(node, reminder))
        val uId = "u_${UUID.randomUUID().toString().take(6)}"
        val event = RawEvent(uId, IntentType.CREATE.name, "node", nId, afterJson = afterJson, tsUtc = clock.now())

        storage.createNodeAndCommit(node, event, reminder)

        if (!followUpTo.isNullOrBlank()) {
            val srcNode = storage.getNode(followUpTo)
            if (srcNode != null) {
                val edgeId = "e_${UUID.randomUUID().toString().take(8)}"
                val edge = Edge(
                    id = edgeId,
                    srcId = srcNode.id,
                    dstId = nId,
                    type = EdgeTypes.FOLLOW_UP,
                    createdAt = clock.now(),
                    updatedAt = clock.now()
                )
                val edgeEvent = RawEvent(
                    opId = "u_${UUID.randomUUID().toString().take(6)}",
                    opType = "LINK",
                    targetType = "edge",
                    targetId = edgeId,
                    afterJson = json.encodeToString(edge),
                    tsUtc = clock.now()
                )
                storage.createEdgeAndCommit(edge, edgeEvent)
            }
        }

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
                val opts = resolution.options.map { node ->
                    val dueStr = if (node.dueAt != null) dateResolver.format(node.dueAt) else null
                    val fullTitle = if (dueStr != null) "${node.title} ($dueStr)" else node.title
                    ProposalOption(node.id, fullTitle, dueStr)
                }
                storage.saveProposal(Proposal(pId, "ambiguous", intent, opts))
                ToolResult.Ambiguous(pId, "ask: which item", opts.map { "${it.id} '${it.title}'" })
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
                
                val activeRems = storage.getPendingReminders()
                // D5: Fetch cleanly by relational node ID, not by fragile string prefix
                val oldRem = activeRems.find { it.nodeId == oldNode.id || it.id == "r_${oldNode.id}" }
                val remId = oldRem?.id ?: "rem_${UUID.randomUUID().toString().take(8)}"
                
                val newRem = if (intent == IntentType.RESCHEDULE && targetTimeMillis != null) {
                    Reminder(remId, newNode.title, targetTimeMillis, newNode.id)
                } else if (intent == IntentType.UPDATE) {
                    oldRem 
                } else null
                
                val deleteReminderId = if (intent == IntentType.CANCEL || intent == IntentType.COMPLETE) oldRem?.id else null

                val beforeJson = json.encodeToString(NodeStateSnapshot(oldNode, oldRem))
                val afterJson = json.encodeToString(NodeStateSnapshot(newNode, newRem))

                val uId = "u_${UUID.randomUUID().toString().take(6)}"
                val event = RawEvent(uId, intent.name, "node", newNode.id, beforeJson = beforeJson, afterJson = afterJson, tsUtc = clock.now())
                
                storage.updateNodeAndCommit(newNode, event, deleteReminderId, newRem)
                return ToolResult.Committed(intent.name, newNode.id, "Applied ${intent.name}: ${newNode.title}", uId)
            }
        }
    }

    private suspend fun handleUndo(token: String): ToolResult {
        if (storage.hasUndo(token)) return ToolResult.Error("Already undone")
        val event = storage.getEvent(token) ?: return ToolResult.Error("Undo token not found")
        
        // B16: Enforce 24-hour limit on Undo operations
        if (clock.now() - event.tsUtc > 24 * 3600 * 1000L) {
            return ToolResult.Error("Cannot undo changes older than 24 hours.")
        }
        
        val afterJson = event.afterJson ?: return ToolResult.Error("No after_json found")
        val newEventId = "u_${UUID.randomUUID().toString().take(6)}"

        if (event.targetType == "reminder") {
            if (event.opType == "SCHEDULE") {
                if (event.beforeJson != null) {
                    val prevReminder = try { json.decodeFromString<Reminder>(event.beforeJson) } catch (e: Exception) { null }
                    if (prevReminder != null) {
                        val undoEvent = RawEvent(newEventId, "UNDO_SCHEDULE", "reminder", event.targetId, undoOf = token, beforeJson = afterJson, afterJson = event.beforeJson, tsUtc = clock.now())
                        storage.createReminderAndCommit(prevReminder, undoEvent)
                        return ToolResult.Committed("UNDO", event.targetId, "Restored previous reminder", newEventId)
                    }
                }
                val undoEvent = RawEvent(newEventId, "UNDO_SCHEDULE", "reminder", event.targetId, undoOf = token, beforeJson = afterJson, afterJson = null, tsUtc = clock.now())
                storage.deleteReminderAndCommit(event.targetId, undoEvent)
                return ToolResult.Committed("UNDO", event.targetId, "Removed reminder", newEventId)
            }
            return ToolResult.Error("Undo for this reminder operation not supported")
        }

        if (event.targetType == "edge") {
            if (event.opType == "LINK") {
                val undoEvent = RawEvent(newEventId, "UNDO_LINK", "edge", event.targetId, undoOf = token, beforeJson = afterJson, afterJson = null, tsUtc = clock.now())
                storage.deleteEdgeAndCommit(event.targetId, undoEvent)
                return ToolResult.Committed("UNDO", event.targetId, "Removed follow-up link", newEventId)
            }
            return ToolResult.Error("Undo for this edge operation not supported")
        }

        val currentNode = storage.getNode(event.targetId) ?: return ToolResult.Error("Node missing")
        val currentReminder = storage.getReminderByNodeId(currentNode.id) ?: storage.getPendingReminders().find { it.id == "r_${event.targetId}" }
        val currentSnapshot = NodeStateSnapshot(currentNode, currentReminder)
        val expectedAfterSnapshot = try { json.decodeFromString<NodeStateSnapshot>(afterJson) } catch (e: Exception) { return ToolResult.Error("Corrupted JSON") }
        
        if (currentSnapshot != expectedAfterSnapshot) return ToolResult.Error("Node state has changed, cannot undo")
        
        if (event.opType == "CREATE") {
            val cancelledNode = currentNode.copy(status = "cancelled", closedAt = clock.now(), updatedAt = clock.now())
            val undoEvent = RawEvent(newEventId, "UNDO_CREATE", "node", currentNode.id, undoOf = token, beforeJson = afterJson, afterJson = json.encodeToString(NodeStateSnapshot(cancelledNode, null)), tsUtc = clock.now())
            val deleteRemId = currentReminder?.id ?: "r_${currentNode.id}"
            storage.updateNodeAndCommit(cancelledNode, undoEvent, deleteRemId)
            return ToolResult.Committed("UNDO", currentNode.id, "Removed: ${currentNode.title}", newEventId)
        } else {
            val beforeJson = event.beforeJson ?: return ToolResult.Error("No previous state to restore")
            val snapshot = try { json.decodeFromString<NodeStateSnapshot>(beforeJson) } catch (e: Exception) { return ToolResult.Error("Corrupted state") }
            val undoEvent = RawEvent(newEventId, "UNDO", "node", snapshot.node.id, undoOf = token, beforeJson = afterJson, afterJson = beforeJson, tsUtc = clock.now())
            
            // ISSUE: If the snapshot has NO reminder, we must explicitly delete the current reminder that might have been created by RESCHEDULE
            val remIdToDelete = if (snapshot.reminder == null) currentReminder?.id else null
            storage.updateNodeAndCommit(snapshot.node, undoEvent, deleteReminderId = remIdToDelete, upsertReminder = snapshot.reminder)
            
            return ToolResult.Committed("UNDO", snapshot.node.id, "Restored: ${snapshot.node.title}", newEventId)
        }
    }
}
