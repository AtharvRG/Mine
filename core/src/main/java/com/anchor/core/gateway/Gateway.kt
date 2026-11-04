// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.gateway

import com.anchor.core.models.*
import com.anchor.core.resolver.Resolver
import com.anchor.core.storage.Storage
import com.anchor.core.storage.TooManyActiveNodesException
import com.anchor.core.storage.getAllActiveNodesPaged
import com.anchor.core.time.Clock
import com.anchor.core.time.DateResolution
import com.anchor.core.time.DateResolver
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.TimeZone
import java.util.UUID

class Gateway(
    private val storage: Storage, 
    private val resolver: Resolver,
    private val dateResolver: DateResolver,
    private val clock: Clock
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun executeTool(call: ToolCall, executedCalls: MutableSet<String> = mutableSetOf(), userMessage: String? = null, causeMsgId: String? = null): ToolResult {
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

                // Blueprint 20 & 24 / Task R9: Sensitive categories forced to proposed, sensitive=true, localOnly=true
                val sensitiveCategories = setOf(
                    "health", "medical", "legal", "finance", "financial",
                    "sexual", "sexuality", "religion", "religious", "politics", "political", "immigration"
                )
                val catClean = category.trim().lowercase(java.util.Locale.US)
                val isSensitiveCat = sensitiveCategories.contains(catClean) || sensitiveCategories.any { catClean.contains(it) }

                val isSensitive = isSensitiveCat || args["sensitive"]?.equals("true", ignoreCase = true) == true
                val isLocalOnly = isSensitive || args["local_only"]?.equals("true", ignoreCase = true) == true

                val sId = "s_${UUID.randomUUID().toString().take(8)}"
                val fact = SoulFact(
                    id = sId,
                    category = category,
                    statement = statement,
                    confidence = confidence,
                    source = "chat_ai",
                    origin = "extracted",
                    status = "proposed",
                    createdAt = clock.now(),
                    localOnly = isLocalOnly,
                    sensitive = isSensitive
                )

                val uId = "u_${UUID.randomUUID().toString().take(6)}"
                val event = RawEvent(uId, "CREATE", "soul_fact", sId, afterJson = json.encodeToString(fact), tsUtc = clock.now(), causeMsgId = causeMsgId)
                storage.createSoulFactAndCommit(fact, event)

                ToolResult.Committed("CREATE", sId, "Proposed to Soul: $statement", uId)
            }
            "undo" -> handleUndo(args["token"] ?: "")
            "confirm_change" -> handleConfirmChange(args["proposal"] ?: "", args["choice"] ?: "", userMessage)
            "keep_stale" -> handleKeepStale(args["node_id"] ?: args["id"] ?: "")
            "cancel_stale" -> handleCancelStale(args["node_id"] ?: args["id"] ?: "")
            "add_follow_up" -> {
                val src = args["src_id"] ?: args["src"] ?: args["from"] ?: ""
                val dst = args["dst_id"] ?: args["dst"] ?: args["to"] ?: ""
                handleAddFollowUp(src, dst, causeMsgId)
            }
            "remove_follow_up" -> {
                val edgeId = args["edge_id"] ?: args["id"] ?: ""
                handleRemoveFollowUp(edgeId, causeMsgId)
            }
            "schedule_reminder" -> {
                val tierStr = args["tier"] ?: "alarm"
                val tier = if (tierStr.equals("gentle", ignoreCase = true)) AlarmTier.GENTLE else AlarmTier.ALARM
                handleScheduleReminder(args["target_ref"] ?: args["target"] ?: "", args["when_text"] ?: args["when"] ?: "", tier, causeMsgId)
            }
            "propose_change" -> {
                val intentStr = args["intent"]?.uppercase() ?: return ToolResult.Error("Missing intent")
                val intent = try { IntentType.valueOf(intentStr) } catch(e: Exception) { return ToolResult.Error("Unknown intent") }

                if (intent in listOf(IntentType.UPDATE, IntentType.PAUSE, IntentType.RESUME)) {
                    return ToolResult.Error("${intent.name} is not supported yet")
                }

                val followUpTo = args["follow_up_to"] ?: args["follow_up_of"]
                if (intent == IntentType.CREATE && !followUpTo.isNullOrBlank()) {
                    val srcNode = storage.getNode(followUpTo)
                    if (srcNode == null) {
                        return ToolResult.Error("Cannot create follow-up: source item '$followUpTo' not found")
                    }
                }

                // B3: Hard cap LLM hallucinated lengths to prevent DB/UI bloat
                val hint = (args["hint"] ?: args["title"] ?: "").take(200)
                val dueText = (args["due_text"] ?: "").take(100)

                val tierStr = args["tier"]?.trim()?.lowercase(java.util.Locale.US)
                val tier = if (tierStr == "gentle") AlarmTier.GENTLE else AlarmTier.ALARM

                val isUntitled = args["untitled"]?.equals("true", ignoreCase = true) == true
                val rawLabel = args["label"]?.trim()?.lowercase(java.util.Locale.US)
                val label = when (rawLabel) {
                    "reminder" -> "Reminder"
                    "notification" -> "Notification"
                    else -> "Alarm"
                }

                if (intent == IntentType.CREATE) {
                    if (!isUntitled && hint.isBlank()) {
                        return ToolResult.Error("Missing title: ask the user what it is for.")
                    }
                    if (isUntitled && hint.isBlank() && dueText.isBlank()) {
                        return ToolResult.Error("Missing title: ask the user what it is for.")
                    }
                    if (!isUntitled) {
                        val duplicate = storage.findActiveNodeByTitle(hint)
                        if (duplicate != null) {
                            return ToolResult.Error("An active item named '${duplicate.title}' already exists.")
                        }
                    }
                }

                if (intent == IntentType.RESCHEDULE && dueText.isBlank()) {
                    return ToolResult.Error("To when?")
                }

                val dayRule = storage.getEffectiveDayRule(clock.now())
                val rolloverMin = dayRule.startMinute
                val timeZone = TimeZone.getTimeZone(dayRule.tzId)

                val timeResolution = if (dueText.isBlank()) null else dateResolver.resolve(dueText, rolloverMin, timeZone)

                if (timeResolution is DateResolution.NeedsClarification) {
                    if (intent == IntentType.CREATE || intent == IntentType.RESCHEDULE) {
                        val pId = "p_${UUID.randomUUID().toString().take(6)}"
                        val opts = timeResolution.candidateMillis.map { millis ->
                            ProposalOption(millis.toString(), dateResolver.formatTimeOnly(millis, timeZone))
                        }
                        val type = (args["type"] ?: "task").lowercase().take(50)
                        val validTypes = listOf("task", "event", "note", "habit")
                        val safeType = if (type in validTypes) type else "task"
                        val priority = args["priority"]?.toIntOrNull() ?: 0
                        val severity = args["severity"]?.toIntOrNull() ?: 0
                        val pinned = args["pinned"]?.toBooleanStrictOrNull() ?: false
                        val followUpTo = args["follow_up_to"] ?: args["follow_up_of"]

                        val payload = ProposalPayload(
                            intent = intent,
                            title = hint,
                            type = safeType,
                            followUpTo = followUpTo,
                            tier = tier,
                            hint = hint,
                            priority = priority,
                            severity = severity,
                            pinned = pinned,
                            untitled = isUntitled,
                            label = label
                        )
                        storage.saveProposal(Proposal(pId, "time", intent, opts, payload = json.encodeToString(payload)))
                        return ToolResult.NeedsTime(pId, timeResolution.chipLabels)
                    }
                }

                val targetTimeMillis = when (timeResolution) {
                    null -> null
                    is DateResolution.Exact -> timeResolution.timeMillis
                    is DateResolution.NeedsClarification -> null
                    is DateResolution.PastTime -> {
                        if (intent == IntentType.RESCHEDULE) {
                            return ToolResult.Error("That time has already passed; tell me the new time again")
                        } else {
                            return ToolResult.Error("That time is in the past; tell me a future time.")
                        }
                    }
                    is DateResolution.Failed -> {
                        if (intent in listOf(IntentType.CANCEL, IntentType.COMPLETE)) null
                        else return ToolResult.Error("Could not understand the time '$dueText'. Ask the user to clarify.")
                    }
                }

                if (intent == IntentType.RESCHEDULE) {
                    if (targetTimeMillis == null && timeResolution !is DateResolution.NeedsClarification) {
                        return ToolResult.Error("To when?")
                    }
                    if (targetTimeMillis != null && targetTimeMillis <= clock.now()) {
                        return ToolResult.Error("That time has already passed; tell me the new time again")
                    }
                }

                when (intent) {
                    IntentType.CREATE -> {
                        val isAutoTitled = isUntitled && targetTimeMillis != null && hint.isBlank()
                        val title = if (isAutoTitled) {
                            val durationMillis = dateResolver.parseDuration(dueText)
                            if (durationMillis != null) {
                                val durationSec = durationMillis / 1000L
                                val hrs = durationSec / 3600L
                                val mins = (durationSec % 3600L) / 60L
                                val secs = durationSec % 60L
                                if (hrs > 0) {
                                    String.format(java.util.Locale.US, "%d:%02d:%02d %s", hrs, mins, secs, label)
                                } else {
                                    String.format(java.util.Locale.US, "%02d:%02d %s", mins, secs, label)
                                }
                            } else {
                                val timeStr = dateResolver.formatTimeOfDay(targetTimeMillis)
                                "$timeStr $label"
                            }
                        } else {
                            hint
                        }

                        if (title.isBlank()) return ToolResult.Error("Missing title: ask the user what it is for.")
                        val type = (args["type"] ?: "task").lowercase().take(50)
                        val validTypes = listOf("task", "event", "note", "habit")
                        val safeType = if (type in validTypes) type else "task"

                        if (!isAutoTitled) {
                            val duplicate = storage.findActiveNodeByTitle(title)
                            if (duplicate != null) {
                                return ToolResult.Error("An active item named '${duplicate.title}' already exists.")
                            }
                        }

                        val priority = args["priority"]?.toIntOrNull() ?: 0
                        val severity = args["severity"]?.toIntOrNull() ?: 0
                        val pinned = args["pinned"]?.toBooleanStrictOrNull() ?: false
                        val followUpTo = args["follow_up_to"] ?: args["follow_up_of"]
                        handleCreate(title, safeType, targetTimeMillis, followUpTo, priority, severity, pinned, tier, causeMsgId)
                    }
                    IntentType.CANCEL, IntentType.COMPLETE, IntentType.RESCHEDULE -> {
                        val timeForResolver = if (intent == IntentType.RESCHEDULE) null else targetTimeMillis
                        handleResolveChange(intent, hint, timeForResolver, targetTimeMillis, causeMsgId)
                    }
                    else -> ToolResult.Error("${intent.name} is not supported yet")
                }
            }
            else -> ToolResult.Error("Tool not supported yet")
        }
    }

    private suspend fun handleScheduleReminder(targetRef: String, whenText: String, tier: AlarmTier = AlarmTier.ALARM, causeMsgId: String? = null): ToolResult {
        val dayRule = storage.getEffectiveDayRule(clock.now())
        val rolloverMin = dayRule.startMinute
        val timeZone = TimeZone.getTimeZone(dayRule.tzId)
        val timeResolution = dateResolver.resolve(whenText, rolloverMin, timeZone)
        if (timeResolution is DateResolution.NeedsClarification) {
            val pId = "p_${UUID.randomUUID().toString().take(6)}"
            val opts = timeResolution.candidateMillis.map { millis ->
                ProposalOption(millis.toString(), dateResolver.formatTimeOnly(millis, timeZone))
            }
            val payload = ProposalPayload(
                intent = IntentType.UPDATE,
                targetId = targetRef,
                tier = tier
            )
            storage.saveProposal(Proposal(pId, "time", IntentType.UPDATE, opts, payload = json.encodeToString(payload)))
            return ToolResult.NeedsTime(pId, timeResolution.chipLabels)
        }
        if (timeResolution is DateResolution.PastTime) {
            return ToolResult.Error("That time is in the past; tell me a future time.")
        }
        val fireAt = (timeResolution as? DateResolution.Exact)?.timeMillis
            ?: return ToolResult.Error("Could not understand the time '$whenText'. Ask the user to clarify.")
        return handleScheduleReminderWithTime(targetRef, fireAt, tier, causeMsgId)
    }

    private suspend fun handleScheduleReminderWithTime(targetRef: String, fireAt: Long, tier: AlarmTier = AlarmTier.ALARM, causeMsgId: String? = null): ToolResult {
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
        val event = RawEvent(uId, "SCHEDULE", "reminder", rId, beforeJson = beforeJson, afterJson = afterJson, tsUtc = clock.now(), causeMsgId = causeMsgId)
        storage.createReminderAndCommit(reminder, event)

        return ToolResult.Committed("SCHEDULE", rId, "Reminder set for ${dateResolver.format(fireAt)}", uId)
    }

    private suspend fun handleConfirmChange(proposalId: String, choice: String, userMessage: String? = null): ToolResult {
        val proposal = storage.getProposal(proposalId) ?: return ToolResult.Error("Proposal not found")
        if (proposal.status != "open") return ToolResult.Error("Proposal already resolved")

        val chosenOption = proposal.options.find { it.id == choice } ?: return ToolResult.Error("Invalid choice")

        if (userMessage != null && !matchesUserMessage(userMessage, chosenOption, choice)) {
            return ToolResult.Error("Cannot confirm proposal: choice '$choice' does not match user's response")
        }

        if (!storage.consumeProposalCas(proposalId)) {
            return ToolResult.Error("Proposal already resolved")
        }

        if (proposal.kind == "time") {
            val chosenMillis = choice.toLongOrNull() ?: return ToolResult.Error("Invalid time choice")
            if (chosenMillis <= clock.now()) {
                return ToolResult.Error("That time has already passed; tell me the new time again")
            }

            val payload = proposal.payload?.let {
                try { json.decodeFromString<ProposalPayload>(it) } catch (e: Exception) { null }
            }

            return try {
                when (proposal.intent) {
                    IntentType.CREATE -> {
                        val isUntitled = payload?.untitled == true
                        val title = if (isUntitled && payload.title.isNullOrBlank()) {
                            val rawLabel = payload.label?.trim()?.lowercase(java.util.Locale.US)
                            val label = when (rawLabel) {
                                "reminder" -> "Reminder"
                                "notification" -> "Notification"
                                else -> "Alarm"
                            }
                            val timeStr = dateResolver.formatTimeOfDay(chosenMillis)
                            "$timeStr $label"
                        } else {
                            payload?.title?.ifBlank { null } ?: "Reminder"
                        }

                        if (!isUntitled) {
                            val duplicate = storage.findActiveNodeByTitle(title)
                            if (duplicate != null) {
                                storage.updateProposalStatus(proposal.id, "open")
                                return ToolResult.Error("An active item named '${duplicate.title}' already exists.")
                            }
                        }

                        val type = payload?.type ?: "task"
                        val followUpTo = payload?.followUpTo
                        val priority = payload?.priority ?: 0
                        val severity = payload?.severity ?: 0
                        val pinned = payload?.pinned ?: false
                        val tier = payload?.tier ?: AlarmTier.ALARM
                        val res = handleCreate(title, type, chosenMillis, followUpTo, priority, severity, pinned, tier)
                        if (res is ToolResult.Error) {
                            storage.updateProposalStatus(proposal.id, "open")
                        }
                        res
                    }
                    IntentType.RESCHEDULE -> {
                        val hint = payload?.hint ?: ""
                        val res = handleResolveChange(IntentType.RESCHEDULE, hint, timeForResolver = null, destinationTimeMillis = chosenMillis)
                        if (res is ToolResult.Error) {
                            storage.updateProposalStatus(proposal.id, "open")
                        }
                        res
                    }
                    IntentType.UPDATE -> {
                        val targetRef = payload?.targetId ?: ""
                        val tier = payload?.tier ?: AlarmTier.ALARM
                        val res = handleScheduleReminderWithTime(targetRef, chosenMillis, tier)
                        if (res is ToolResult.Error) {
                            storage.updateProposalStatus(proposal.id, "open")
                        }
                        res
                    }
                    else -> ToolResult.Error("${proposal.intent.name} is not supported for time chips")
                }
            } catch (t: Throwable) {
                storage.updateProposalStatus(proposal.id, "open")
                throw t
            }
        }

        val nodeId = choice
        val node = storage.getNode(nodeId) ?: return ToolResult.Error("Node not found")
        if (node.status !in listOf("active", "in_progress", "paused")) return ToolResult.Error("Node is no longer active")

        val now = clock.now()
        val oldRem = storage.getReminderByNodeId(node.id) ?: storage.getPendingReminders().find { it.nodeId == node.id || it.id == "r_${node.id}" }

        return try {
            when (proposal.intent) {
                IntentType.CANCEL -> {
                    val newNode = node.copy(status = "cancelled", closedAt = now, updatedAt = now)
                    val beforeJson = json.encodeToString(NodeStateSnapshot(node, oldRem))
                    val afterJson = json.encodeToString(NodeStateSnapshot(newNode, null))
                    val uId = "u_${UUID.randomUUID().toString().take(6)}"
                    val event = RawEvent(uId, "CANCEL", "node", newNode.id, beforeJson = beforeJson, afterJson = afterJson, tsUtc = now)
                    storage.updateNodeAndCommit(newNode, event, deleteReminderId = oldRem?.id, upsertReminder = null)
                    ToolResult.Committed("CANCEL", newNode.id, "Applied CANCEL: ${newNode.title}", uId)
                }
                IntentType.COMPLETE -> {
                    val newNode = node.copy(status = "done", closedAt = now, updatedAt = now)
                    val beforeJson = json.encodeToString(NodeStateSnapshot(node, oldRem))
                    val afterJson = json.encodeToString(NodeStateSnapshot(newNode, null))
                    val uId = "u_${UUID.randomUUID().toString().take(6)}"
                    val event = RawEvent(uId, "COMPLETE", "node", newNode.id, beforeJson = beforeJson, afterJson = afterJson, tsUtc = now)
                    storage.updateNodeAndCommit(newNode, event, deleteReminderId = oldRem?.id, upsertReminder = null)
                    ToolResult.Committed("COMPLETE", newNode.id, "Applied COMPLETE: ${newNode.title}", uId)
                }
                IntentType.RESCHEDULE -> {
                    val payload = proposal.payload?.let {
                        try { json.decodeFromString<ProposalPayload>(it) } catch (e: Exception) { null }
                    }
                    val target = payload?.targetAtMillis
                    if (target == null || target <= clock.now()) {
                        return ToolResult.Error("That time has already passed; tell me the new time again")
                    }
                    val newNode = node.copy(dueAt = target, updatedAt = now)
                    val remId = oldRem?.id ?: "r_${node.id}"
                    val existingTier = oldRem?.tier ?: AlarmTier.ALARM
                    val newRem = if (oldRem != null) {
                        oldRem.copy(text = newNode.title, fireAtMillis = target)
                    } else {
                        Reminder(remId, newNode.title, target, node.id, tier = existingTier)
                    }
                    val beforeJson = json.encodeToString(NodeStateSnapshot(node, oldRem))
                    val afterJson = json.encodeToString(NodeStateSnapshot(newNode, newRem))
                    val uId = "u_${UUID.randomUUID().toString().take(6)}"
                    val event = RawEvent(uId, "RESCHEDULE", "node", newNode.id, beforeJson = beforeJson, afterJson = afterJson, tsUtc = now)
                    storage.updateNodeAndCommit(newNode, event, deleteReminderId = null, upsertReminder = newRem)
                    ToolResult.Committed("RESCHEDULE", newNode.id, "Rescheduled: ${newNode.title} to ${dateResolver.format(target)}", uId)
                }
                else -> ToolResult.Error("${proposal.intent.name} is not supported yet")
            }
        } catch (t: Throwable) {
            storage.updateProposalStatus(proposal.id, "open")
            throw t
        }
    }

    private fun matchesUserMessage(userMessage: String, option: ProposalOption, choice: String): Boolean {
        val cleanMsg = userMessage.trim().lowercase(java.util.Locale.US)
        val cleanChoice = choice.trim().lowercase(java.util.Locale.US)
        val cleanTitle = option.title.trim().lowercase(java.util.Locale.US)

        if (cleanMsg == cleanChoice || cleanMsg == cleanTitle) return true
        if (cleanTitle.contains(cleanMsg) || cleanMsg.contains(cleanTitle)) return true

        val stopWords = setOf("the", "a", "an", "to", "for", "in", "on", "at", "my", "me", "i", "it", "this", "that", "please", "yes", "no")
        val userTokens = cleanMsg.split(Regex("[^a-zA-Z0-9]+")).filter { it.length >= 2 && it !in stopWords }
        val optionTokens = cleanTitle.split(Regex("[^a-zA-Z0-9]+")).filter { it.length >= 2 && it !in stopWords }

        if (userTokens.any { it in optionTokens }) return true

        val chosenMillis = choice.toLongOrNull()
        if (chosenMillis != null) {
            val formattedTime = dateResolver.formatTimeOnly(chosenMillis).lowercase(java.util.Locale.US)
            if (cleanMsg.contains(formattedTime) || formattedTime.contains(cleanMsg)) return true
        }

        return false
    }

    private suspend fun handleCreate(
        title: String,
        type: String,
        dueAt: Long?,
        followUpTo: String? = null,
        priority: Int = 0,
        severity: Int = 0,
        pinned: Boolean = false,
        tier: AlarmTier = AlarmTier.ALARM,
        causeMsgId: String? = null
    ): ToolResult {
        val followUpEdge: Edge?
        val followUpEdgeEvent: RawEvent?
        val nId = "n_${UUID.randomUUID().toString().take(8)}"
        val now = clock.now()

        if (!followUpTo.isNullOrBlank()) {
            val srcNode = storage.getNode(followUpTo)
            if (srcNode == null) {
                return ToolResult.Error("Cannot create follow-up: source item '$followUpTo' not found")
            }
            val edgeId = "e_${UUID.randomUUID().toString().take(8)}"
            val edge = Edge(
                id = edgeId,
                srcId = srcNode.id,
                dstId = nId,
                type = EdgeTypes.FOLLOW_UP,
                createdAt = now,
                updatedAt = now
            )
            val edgeEvent = RawEvent(
                opId = "u_${UUID.randomUUID().toString().take(6)}",
                opType = "LINK",
                targetType = "edge",
                targetId = edgeId,
                afterJson = json.encodeToString(edge),
                tsUtc = now,
                causeMsgId = causeMsgId
            )
            followUpEdge = edge
            followUpEdgeEvent = edgeEvent
        } else {
            followUpEdge = null
            followUpEdgeEvent = null
        }

        val node = Node(nId, type, title, "active", dueAt, createdAt = now, updatedAt = now, priority = priority, severity = severity, pinned = pinned)

        // Fix: Auto-create the reminder inherently so the LLM doesn't have to call a separate tool
        val reminder = dueAt?.let { Reminder("r_$nId", title, it, nId, tier = tier) }

        val afterJson = json.encodeToString(NodeStateSnapshot(node, reminder))
        val uId = "u_${UUID.randomUUID().toString().take(6)}"
        val event = RawEvent(uId, IntentType.CREATE.name, "node", nId, afterJson = afterJson, tsUtc = now, causeMsgId = causeMsgId)

        storage.createNodeAndCommit(node, event, reminder, followUpEdge, followUpEdgeEvent)

        val timeStr = if (dueAt != null) " (Due: ${dateResolver.format(dueAt)})" else ""
        return ToolResult.Committed(IntentType.CREATE.name, nId, "Created: $title$timeStr", uId)
    }

    private suspend fun handleResolveChange(
        intent: IntentType,
        hint: String,
        timeForResolver: Long?,
        destinationTimeMillis: Long?,
        causeMsgId: String? = null
    ): ToolResult {
        if (intent == IntentType.RESCHEDULE && destinationTimeMillis == null) {
            return ToolResult.Error("To when?")
        }
        val candidates = try {
            storage.getAllActiveNodesPaged(pageSize = 200, hardCap = 5000)
        } catch (e: TooManyActiveNodesException) {
            return ToolResult.Error("There are too many active items (${e.cap}+). Please be more specific with the item title or due time.")
        }
        val resolution = resolver.resolve(intent, hint, timeForResolver, candidates)

        return when (resolution) {
            is Resolution.NotFound -> ToolResult.NotFound(hint)
            is Resolution.Ambiguous -> {
                val pId = "p_${UUID.randomUUID().toString().take(6)}"
                val opts = resolution.options.map { node ->
                    val dueStr = if (node.dueAt != null) dateResolver.format(node.dueAt) else null
                    val fullTitle = if (dueStr != null) "${node.title} ($dueStr)" else node.title
                    ProposalOption(node.id, fullTitle, dueStr)
                }
                val payloadJson = if (intent == IntentType.RESCHEDULE) {
                    json.encodeToString(ProposalPayload(targetAtMillis = destinationTimeMillis))
                } else null
                storage.saveProposal(Proposal(pId, "ambiguous", intent, opts, payload = payloadJson))
                ToolResult.Ambiguous(pId, "ask: which item", opts.map { "${it.id} '${it.title}'" })
            }
            is Resolution.ClearWinner -> {
                val oldNode = resolution.node
                val now = clock.now()

                val newNode = when (intent) {
                    IntentType.CANCEL -> oldNode.copy(status = "cancelled", closedAt = now, updatedAt = now)
                    IntentType.COMPLETE -> oldNode.copy(status = "done", closedAt = now, updatedAt = now)
                    IntentType.RESCHEDULE -> {
                        val destTime = destinationTimeMillis ?: return ToolResult.Error("To when?")
                        oldNode.copy(dueAt = destTime, updatedAt = now)
                    }
                    else -> return ToolResult.Error("${intent.name} is not supported yet")
                }

                val activeRems = storage.getPendingReminders()
                // D5: Fetch cleanly by relational node ID, not by fragile string prefix
                val oldRem = storage.getReminderByNodeId(oldNode.id) ?: activeRems.find { it.nodeId == oldNode.id || it.id == "r_${oldNode.id}" }
                val remId = oldRem?.id ?: "rem_${UUID.randomUUID().toString().take(8)}"
                val existingTier = oldRem?.tier ?: AlarmTier.ALARM

                val newRem = if (intent == IntentType.RESCHEDULE) {
                    val dest = destinationTimeMillis ?: return ToolResult.Error("To when?")
                    if (oldRem != null) {
                        oldRem.copy(text = newNode.title, fireAtMillis = dest)
                    } else {
                        Reminder(remId, newNode.title, dest, newNode.id, tier = existingTier)
                    }
                } else null

                val deleteReminderId = if (intent == IntentType.CANCEL || intent == IntentType.COMPLETE) oldRem?.id else null

                val beforeJson = json.encodeToString(NodeStateSnapshot(oldNode, oldRem))
                val afterJson = json.encodeToString(NodeStateSnapshot(newNode, newRem))

                val uId = "u_${UUID.randomUUID().toString().take(6)}"
                val event = RawEvent(uId, intent.name, "node", newNode.id, beforeJson = beforeJson, afterJson = afterJson, tsUtc = clock.now(), causeMsgId = causeMsgId)

                storage.updateNodeAndCommit(newNode, event, deleteReminderId, newRem)
                val receiptText = if (intent == IntentType.RESCHEDULE) {
                    val dest = destinationTimeMillis ?: return ToolResult.Error("To when?")
                    "Rescheduled: ${newNode.title} to ${dateResolver.format(dest)}"
                } else {
                    "Applied ${intent.name}: ${newNode.title}"
                }
                return ToolResult.Committed(intent.name, newNode.id, receiptText, uId)
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

        val afterJson = event.afterJson
        val newEventId = "u_${UUID.randomUUID().toString().take(6)}"

        if (event.targetType == "reminder") {
            if (event.opType == "SCHEDULE") {
                if (afterJson == null) return ToolResult.Error("No after_json found")
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
            if (event.opType == "DELETE") {
                if (event.beforeJson != null) {
                    val prevReminder = try { json.decodeFromString<Reminder>(event.beforeJson) } catch (e: Exception) { null }
                    if (prevReminder != null) {
                        val undoEvent = RawEvent(newEventId, "UNDO_DELETE", "reminder", event.targetId, undoOf = token, beforeJson = null, afterJson = event.beforeJson, tsUtc = clock.now())
                        storage.createReminderAndCommit(prevReminder, undoEvent)
                        return ToolResult.Committed("UNDO", event.targetId, "Restored reminder: ${prevReminder.text}", newEventId)
                    }
                }
            }
            return ToolResult.Error("Undo for this reminder operation not supported")
        }

        if (event.targetType == "soul_fact") {
            if (afterJson == null) return ToolResult.Error("No after_json found")
            if (event.opType == "CREATE") {
                val undoEvent = RawEvent(newEventId, "UNDO_CREATE", "soul_fact", event.targetId, undoOf = token, beforeJson = afterJson, afterJson = null, tsUtc = clock.now())
                storage.updateSoulFactStatusAndCommit(event.targetId, "rejected", undoEvent)
                return ToolResult.Committed("UNDO", event.targetId, "Undid soul fact proposal", newEventId)
            }
            if (event.opType == "ACCEPT" || event.opType == "REJECT") {
                val undoEvent = RawEvent(newEventId, "UNDO_" + event.opType, "soul_fact", event.targetId, undoOf = token, beforeJson = afterJson, afterJson = event.beforeJson, tsUtc = clock.now())
                storage.updateSoulFactStatusAndCommit(event.targetId, "proposed", undoEvent)
                return ToolResult.Committed("UNDO", event.targetId, "Restored soul fact to proposed", newEventId)
            }
            return ToolResult.Error("Undo for this soul fact operation not supported")
        }

        if (event.targetType == "edge") {
            if (event.opType == "LINK") {
                if (afterJson == null) return ToolResult.Error("No after_json found")
                val undoEvent = RawEvent(newEventId, "UNDO_LINK", "edge", event.targetId, undoOf = token, beforeJson = afterJson, afterJson = null, tsUtc = clock.now())
                storage.deleteEdgeAndCommit(event.targetId, undoEvent)
                return ToolResult.Committed("UNDO", event.targetId, "Removed follow-up link", newEventId)
            }
            if (event.opType == "UNLINK") {
                val beforeJson = event.beforeJson ?: return ToolResult.Error("No previous edge state to restore")
                val edge = try { json.decodeFromString<Edge>(beforeJson) } catch (e: Exception) { return ToolResult.Error("Corrupted edge state") }
                val restoredEdge = edge.copy(deletedAt = null, updatedAt = clock.now())
                val undoEvent = RawEvent(newEventId, "UNDO_UNLINK", "edge", event.targetId, undoOf = token, beforeJson = afterJson, afterJson = json.encodeToString(restoredEdge), tsUtc = clock.now())
                storage.createEdgeAndCommit(restoredEdge, undoEvent)
                return ToolResult.Committed("UNDO", event.targetId, "Restored follow-up link", newEventId)
            }
            return ToolResult.Error("Undo for this edge operation not supported")
        }

        if (afterJson == null) return ToolResult.Error("No after_json found")

        val currentNode = storage.getNode(event.targetId) ?: return ToolResult.Error("Node missing")
        val currentReminder = storage.getReminderByNodeId(currentNode.id) ?: storage.getPendingReminders().find { it.id == "r_${event.targetId}" }
        val currentSnapshot = NodeStateSnapshot(currentNode, currentReminder)
        val expectedAfterSnapshot = try { json.decodeFromString<NodeStateSnapshot>(afterJson) } catch (e: Exception) { return ToolResult.Error("Corrupted JSON") }

        if (currentSnapshot != expectedAfterSnapshot) return ToolResult.Error("Node state has changed, cannot undo")

        if (event.opType == "CREATE") {
            val cancelledNode = currentNode.copy(status = "cancelled", closedAt = clock.now(), updatedAt = clock.now())
            val undoEvent = RawEvent(newEventId, "UNDO_CREATE", "node", currentNode.id, undoOf = token, beforeJson = afterJson, afterJson = json.encodeToString(NodeStateSnapshot(cancelledNode, null)), tsUtc = clock.now())
            val deleteRemId = currentReminder?.id ?: "r_${currentNode.id}"
            val edgesIn = storage.getEdgesForDestination(currentNode.id)
            val edgesOut = storage.getEdgesForSource(currentNode.id)
            val edgeIds = (edgesIn + edgesOut).map { it.id }.distinct()
            storage.updateNodeAndCommit(cancelledNode, undoEvent, deleteRemId, softDeleteEdgeIds = edgeIds)
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

    suspend fun handleKeepStale(nodeId: String): ToolResult {
        val node = storage.getNode(nodeId) ?: return ToolResult.Error("Node not found")
        val now = clock.now()
        val oldRem = storage.getReminderByNodeId(nodeId) ?: storage.getPendingReminders().find { it.id == "r_$nodeId" }
        val beforeJson = json.encodeToString(NodeStateSnapshot(node, oldRem))
        val newNode = node.copy(updatedAt = now)
        val afterJson = json.encodeToString(NodeStateSnapshot(newNode, oldRem))
        val uId = "u_${UUID.randomUUID().toString().take(6)}"
        val event = RawEvent(uId, "KEEP_STALE", "node", nodeId, beforeJson = beforeJson, afterJson = afterJson, tsUtc = now)
        storage.updateNodeAndCommit(newNode, event, deleteReminderId = null, upsertReminder = null)
        return ToolResult.Committed("KEEP_STALE", nodeId, "Kept: ${node.title}", uId)
    }

    suspend fun handleCancelStale(nodeId: String): ToolResult {
        val node = storage.getNode(nodeId) ?: return ToolResult.Error("Node not found")
        val now = clock.now()
        val oldRem = storage.getReminderByNodeId(nodeId) ?: storage.getPendingReminders().find { it.id == "r_$nodeId" }
        val beforeJson = json.encodeToString(NodeStateSnapshot(node, oldRem))
        val newNode = node.copy(status = "cancelled", closedAt = now, updatedAt = now)
        val afterJson = json.encodeToString(NodeStateSnapshot(newNode, null))
        val uId = "u_${UUID.randomUUID().toString().take(6)}"
        val event = RawEvent(uId, "CANCEL_STALE", "node", nodeId, beforeJson = beforeJson, afterJson = afterJson, tsUtc = now)
        val deleteRemId = oldRem?.id ?: "r_$nodeId"
        storage.updateNodeAndCommit(newNode, event, deleteReminderId = deleteRemId, upsertReminder = null)
        return ToolResult.Committed("CANCEL_STALE", nodeId, "Cancelled: ${node.title}", uId)
    }

    suspend fun handleAddFollowUp(srcNodeId: String, dstNodeId: String, causeMsgId: String? = null): ToolResult {
        if (srcNodeId == dstNodeId) {
            return ToolResult.Error("Cannot link an item to itself (self-loop detected)")
        }
        val srcNode = storage.getNode(srcNodeId) ?: return ToolResult.Error("Source item '$srcNodeId' not found")
        val dstNode = storage.getNode(dstNodeId) ?: return ToolResult.Error("Destination item '$dstNodeId' not found")

        val existingEdges = storage.getEdgesForSource(srcNodeId, EdgeTypes.FOLLOW_UP)
        if (existingEdges.any { it.dstId == dstNodeId }) {
            return ToolResult.Error("Follow-up link from '${srcNode.title}' to '${dstNode.title}' already exists")
        }

        if (causesCycle(srcNodeId, dstNodeId)) {
            return ToolResult.Error("Cannot link: adding this follow-up would create a cyclic dependency")
        }

        val edgeId = "e_${UUID.randomUUID().toString().take(8)}"
        val edge = Edge(
            id = edgeId,
            srcId = srcNodeId,
            dstId = dstNodeId,
            type = EdgeTypes.FOLLOW_UP,
            createdAt = clock.now(),
            updatedAt = clock.now()
        )
        val uId = "u_${UUID.randomUUID().toString().take(6)}"
        val event = RawEvent(
            opId = uId,
            opType = "LINK",
            targetType = "edge",
            targetId = edgeId,
            afterJson = json.encodeToString(edge),
            tsUtc = clock.now(),
            causeMsgId = causeMsgId
        )
        storage.createEdgeAndCommit(edge, event)
        return ToolResult.Committed("LINK", edgeId, "Linked follow-up: ${srcNode.title} -> ${dstNode.title}", uId)
    }

    suspend fun handleRemoveFollowUp(edgeId: String, causeMsgId: String? = null): ToolResult {
        val edge = storage.getEdge(edgeId) ?: return ToolResult.Error("Follow-up link '$edgeId' not found or already removed")
        val uId = "u_${UUID.randomUUID().toString().take(6)}"
        val beforeJson = json.encodeToString(edge)
        val event = RawEvent(
            opId = uId,
            opType = "UNLINK",
            targetType = "edge",
            targetId = edgeId,
            beforeJson = beforeJson,
            afterJson = null,
            tsUtc = clock.now(),
            causeMsgId = causeMsgId
        )
        storage.deleteEdgeAndCommit(edgeId, event)
        return ToolResult.Committed("UNLINK", edgeId, "Removed follow-up link", uId)
    }

    private suspend fun causesCycle(srcId: String, dstId: String, maxDepth: Int = 20): Boolean {
        // If dstId can reach srcId along active follow_up edges, adding srcId -> dstId creates a cycle.
        val visited = mutableSetOf<String>()
        val queue = ArrayDeque<Pair<String, Int>>()
        queue.add(dstId to 0)
        visited.add(dstId)

        while (queue.isNotEmpty()) {
            val (current, depth) = queue.removeFirst()
            if (current == srcId) return true
            if (depth >= maxDepth) continue

            val outgoing = storage.getEdgesForSource(current, EdgeTypes.FOLLOW_UP)
            for (edge in outgoing) {
                if (edge.dstId == srcId) return true
                if (visited.add(edge.dstId)) {
                    queue.add(edge.dstId to depth + 1)
                }
            }
        }
        return false
    }
}
