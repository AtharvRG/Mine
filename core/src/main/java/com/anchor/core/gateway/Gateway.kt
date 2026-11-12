// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.gateway

import com.anchor.core.models.*
import com.anchor.core.resolver.Resolver
import com.anchor.core.storage.ProposalAlreadyResolvedException
import com.anchor.core.storage.Storage
import com.anchor.core.storage.TooManyActiveNodesException
import com.anchor.core.storage.getAllActiveNodesPaged
import com.anchor.core.time.Clock
import com.anchor.core.time.DateResolution
import com.anchor.core.time.DateResolver
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Calendar
import java.util.TimeZone
import java.util.UUID

class Gateway(
    private val storage: Storage, 
    private val resolver: Resolver,
    private val dateResolver: DateResolver,
    private val clock: Clock,
    private val hybridRetriever: com.anchor.core.retrieval.HybridRetriever? = null,
    private val embedder: com.anchor.core.retrieval.Embedder? = null,
    private val alarmScheduler: com.anchor.core.alarms.AlarmScheduler? = null
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun executeTool(call: ToolCall, executedCalls: MutableSet<String> = mutableSetOf(), userMessage: String? = null, causeMsgId: String? = null): ToolResult {
        val args = EnvelopeParser.resolveArgs(call)
        // Keyed on the raw arguments: resolution drops placeholders, which would make two
        // genuinely different calls look identical and silently drop the second one.
        if (!executedCalls.add(EnvelopeParser.dedupeKey(call))) return ToolResult.Error("Duplicate tool call ignored")

        return when (call.tool) {
            "search_memory" -> {
                val query = args["query"] ?: ""
                val limit = (args["limit"]?.toIntOrNull() ?: 6).coerceIn(1, 12)
                val results = hybridRetriever?.search(query, limit) ?: storage.searchMemoryFts(query, limit)
                ToolResult.Data(formatSearchResults(results))
            }
            // Blueprint 23 §5: the original messages, for when exact earlier wording matters.
            "search_history" -> {
                val query = args["query"] ?: ""
                if (query.isBlank()) return ToolResult.Error("Missing query")
                val limit = (args["limit"]?.toIntOrNull() ?: 5).coerceIn(1, 10)
                ToolResult.Data(formatSearchResults(storage.searchHistory(query, limit)))
            }
            "soul_propose" -> {
                // B3: Cap statement length
                val statement = (args["statement"] ?: return ToolResult.Error("Missing statement")).take(500)
                val category = (args["category"] ?: "other").take(100)
                val confidence = args["confidence"]?.toDoubleOrNull() ?: 0.7

                // Blueprint 12: Suppress if previously rejected by user
                if (storage.isSoulFactSuppressed(statement)) {
                    return ToolResult.Error("Fact has been rejected by user and is suppressed")
                }

                // Blueprint 20 & 24 / Task R9: Sensitive categories forced to proposed, sensitive=true, localOnly=true
                val sensitiveCategories = setOf(
                    "health", "medical", "legal", "finance", "financial",
                    "sexual", "sexuality", "religion", "religious", "politics", "political", "immigration"
                )
                val catClean = category.trim().lowercase(java.util.Locale.US)
                val isSensitiveCat = sensitiveCategories.contains(catClean) || sensitiveCategories.any { catClean.contains(it) }

                val isSensitive = isSensitiveCat || args["sensitive"]?.equals("true", ignoreCase = true) == true
                val isLocalOnly = isSensitive || args["local_only"]?.equals("true", ignoreCase = true) == true

                val sMode = storage.getSetting("soul.mode") ?: "ask"
                val autoAccept = sMode == "auto" && !isSensitive && confidence >= 0.75
                val targetStatus = if (autoAccept) "active" else "proposed"

                val sId = "s_${UUID.randomUUID()}"
                val fact = SoulFact(
                    id = sId,
                    category = category,
                    statement = statement,
                    confidence = confidence,
                    source = "chat_ai",
                    origin = "extracted",
                    status = targetStatus,
                    createdAt = clock.now(),
                    localOnly = isLocalOnly,
                    sensitive = isSensitive
                )

                val uId = "u_${UUID.randomUUID()}"
                val event = RawEvent(uId, "CREATE", "soul_fact", sId, afterJson = json.encodeToString(fact), tsUtc = clock.now(), causeMsgId = causeMsgId)
                storage.createSoulFactAndCommit(fact, event)

                if (embedder != null && targetStatus == "active" && !fact.localOnly) {
                    val vec = embedder.embed(listOf(fact.statement), com.anchor.core.retrieval.Embedder.Kind.DOCUMENT).firstOrNull()
                    if (vec != null) {
                        storage.saveEmbedding("soul_fact", fact.id, embedder.version, vec)
                    }
                }

                val replyMsg = if (targetStatus == "active") "Added to Soul: $statement" else "Proposed to Soul: $statement"
                ToolResult.Committed("CREATE", sId, replyMsg, uId)
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
                val targetRef = args["target_ref"] ?: args["target"] ?: ""
                val whenText = args["when_text"] ?: args["when"] ?: ""
                val offsetText = args["offset_text"] ?: args["offset"]
                val inferred = args["inferred"]?.equals("true", ignoreCase = true) == true
                handleScheduleReminder(targetRef, whenText, offsetText, inferred, tier, causeMsgId)
            }
            "propose_change" -> {
                val intentStr = args["intent"]?.uppercase() ?: return ToolResult.Error("Missing intent")
                val intent = try { IntentType.valueOf(intentStr) } catch(e: Exception) { return ToolResult.Error("Unknown intent") }

                if (intent in listOf(IntentType.PAUSE, IntentType.RESUME)) {
                    return ToolResult.Error("${intent.name} is not supported yet")
                }

                if (intent == IntentType.UPDATE) {
                    return handleUpdate(args, causeMsgId)
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
                        val pId = "p_${UUID.randomUUID()}"
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
                            val matchingNodes = storage.findActiveNodesByTitle(title)
                            val duplicate = matchingNodes.firstOrNull { existing ->
                                isNearMatch(existing.dueAt, targetTimeMillis)
                            }
                            if (duplicate != null) {
                                val pId = "p_${UUID.randomUUID()}"
                                val dueStr = if (duplicate.dueAt != null) dateResolver.format(duplicate.dueAt) else null
                                val priority = args["priority"]?.toIntOrNull() ?: 0
                                val severity = args["severity"]?.toIntOrNull() ?: 0
                                val pinned = args["pinned"]?.toBooleanStrictOrNull() ?: false
                                val followUpTo = args["follow_up_to"] ?: args["follow_up_of"]
                                val payload = ProposalPayload(
                                    targetAtMillis = targetTimeMillis,
                                    title = title,
                                    type = safeType,
                                    followUpTo = followUpTo,
                                    priority = priority,
                                    severity = severity,
                                    pinned = pinned,
                                    tier = tier,
                                    targetId = duplicate.id,
                                    intent = IntentType.CREATE
                                )
                                val proposal = Proposal(
                                    id = pId,
                                    kind = "needs_confirm",
                                    intent = IntentType.CREATE,
                                    options = listOf(
                                        ProposalOption(duplicate.id, "Update existing", dueStr),
                                        ProposalOption("add_new", "Add anyway"),
                                        ProposalOption("cancel", "Cancel")
                                    ),
                                    payload = json.encodeToString(payload)
                                )
                                storage.saveProposal(proposal)
                                val msg = "Similar item already exists: ${duplicate.title}${if (dueStr != null) " at $dueStr" else ""}"
                                return ToolResult.NeedsConfirm(pId, msg, listOf("Update existing", "Add anyway", "Cancel"))
                            }
                        }

                        val priority = args["priority"]?.toIntOrNull() ?: 0
                        val severity = args["severity"]?.toIntOrNull() ?: 0
                        val pinned = args["pinned"]?.toBooleanStrictOrNull() ?: false
                        val followUpTo = args["follow_up_to"] ?: args["follow_up_of"]
                        val recurrenceRule = args["recurrence_rule"] ?: args["recurrence"] ?: args["rrule"]
                        handleCreate(title, safeType, targetTimeMillis, followUpTo, priority, severity, pinned, tier, recurrenceRule, causeMsgId)
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

    private suspend fun handleScheduleReminder(
        targetRef: String,
        whenText: String,
        offsetText: String? = null,
        inferred: Boolean = false,
        tier: AlarmTier = AlarmTier.ALARM,
        causeMsgId: String? = null
    ): ToolResult {
        val node = if (targetRef.isNotBlank()) storage.getNode(resolveRef(targetRef, "n")) else null
        if (targetRef.isNotBlank() && node == null) {
            return ToolResult.Error("unknown_ref: Target node not found for reference '$targetRef'")
        }

        val dayRule = storage.getEffectiveDayRule(clock.now())
        val rolloverMin = dayRule.startMinute
        val timeZone = TimeZone.getTimeZone(dayRule.tzId)

        var fireAt: Long = if (!offsetText.isNullOrBlank()) {
            if (node == null || node.dueAt == null) {
                return ToolResult.Error("Target node has no due date for relative offset")
            }
            val offsetMillis = dateResolver.parseOffsetDuration(offsetText)
                ?: return ToolResult.Error("Could not parse offset '$offsetText'")
            val calculated = node.dueAt - offsetMillis
            if (calculated <= clock.now()) {
                return ToolResult.Error("That time is in the past; tell me a future time.")
            }
            calculated
        } else {
            val timeResolution = dateResolver.resolve(whenText, rolloverMin, timeZone, severity = node?.severity)
            if (timeResolution is DateResolution.NeedsClarification) {
                val pId = "p_${UUID.randomUUID()}"
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
            (timeResolution as? DateResolution.Exact)?.timeMillis
                ?: return ToolResult.Error("Could not understand the time '$whenText'. Ask the user to clarify.")
        }

        // Quiet hours check for AI-inferred reminders (Blueprint 30 s3.3, D-035)
        // Explicit user reminders ring anyway (requested-class). AI-suggested ones are moved to day_start + 30 min.
        if (inferred) {
            val cal = Calendar.getInstance(timeZone).apply { timeInMillis = fireAt }
            val minuteOfDay = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
            val isOutsideDayWindow = minuteOfDay < dayRule.startMinute || minuteOfDay > dayRule.endMinute
            if (isOutsideDayWindow) {
                val targetMinute = dayRule.startMinute + 30
                if (minuteOfDay > dayRule.endMinute) {
                    cal.add(Calendar.DAY_OF_YEAR, 1)
                }
                cal.set(Calendar.HOUR_OF_DAY, targetMinute / 60)
                cal.set(Calendar.MINUTE, targetMinute % 60)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                fireAt = cal.timeInMillis
            }

            // Check AI-suggest setting: "ask" (default) vs "auto" (Blueprint 30 s3.3, Blueprint 52 line 174)
            val aiSuggestSetting = storage.getSetting("reminder.ai_suggest") ?: "ask"
            if (aiSuggestSetting != "auto") {
                val pId = "p_${UUID.randomUUID()}"
                val formattedTime = dateResolver.formatTimeOfDay(fireAt, timeZone)
                val opts = listOf(
                    ProposalOption("set", "Set $formattedTime"),
                    ProposalOption("change", "Change"),
                    ProposalOption("cancel", "Cancel")
                )
                val payload = ProposalPayload(
                    targetAtMillis = fireAt,
                    targetId = targetRef.ifBlank { null },
                    tier = tier
                )
                storage.saveProposal(Proposal(pId, "needs_confirm", IntentType.CREATE, opts, payload = json.encodeToString(payload)))
                return ToolResult.NeedsConfirm(
                    pId,
                    "Suggest reminder for $formattedTime",
                    listOf("Set $formattedTime", "Change", "Cancel")
                )
            }
        }

        // Duplicate reminder check (Blueprint 30 s3.3):
        // If a reminder for the same node already exists within 30 minutes, ask to update it instead of silently overwriting.
        if (node != null) {
            val pendingReminders = storage.getPendingReminders().filter { it.nodeId == node.id }
            val duplicate = pendingReminders.find { existing ->
                Math.abs(existing.fireAtMillis - fireAt) <= 30 * 60 * 1000L
            }
            if (duplicate != null) {
                val pId = "p_${UUID.randomUUID()}"
                val existingDueStr = dateResolver.formatTimeOfDay(duplicate.fireAtMillis, timeZone)
                val newDueStr = dateResolver.formatTimeOfDay(fireAt, timeZone)
                val opts = listOf(
                    ProposalOption("update", "Update existing"),
                    ProposalOption("cancel", "Cancel")
                )
                val payload = ProposalPayload(
                    targetAtMillis = fireAt,
                    targetId = node.id,
                    tier = tier
                )
                storage.saveProposal(Proposal(pId, "needs_confirm", IntentType.UPDATE, opts, payload = json.encodeToString(payload)))
                return ToolResult.NeedsConfirm(
                    pId,
                    "A reminder is already set for $existingDueStr. Update it to $newDueStr, or cancel?",
                    listOf("Update existing", "Cancel")
                )
            }
        }

        return handleScheduleReminderWithTime(targetRef, fireAt, tier, causeMsgId)
    }

    private suspend fun handleScheduleReminderWithTime(targetRef: String, fireAt: Long, tier: AlarmTier = AlarmTier.ALARM, causeMsgId: String? = null, consumeProposalId: String? = null): ToolResult {
        val node = if (targetRef.isNotBlank()) storage.getNode(resolveRef(targetRef, "n")) else null

        // A8: Reject bad target refs silently making orphan "Reminder"s
        if (targetRef.isNotBlank() && node == null) {
            if (consumeProposalId != null) {
                storage.consumeProposal(consumeProposalId)
            }
            return ToolResult.Error("unknown_ref: Target node not found for reference '$targetRef'")
        }

        val title = node?.title ?: "Reminder"
        val existingRem = if (node != null) storage.getReminderByNodeId(node.id) else null
        val rId = existingRem?.id ?: "rem_${UUID.randomUUID()}"
        val reminder = Reminder(rId, title, fireAt, nodeId = node?.id, tier = tier)
        val uId = "u_${UUID.randomUUID()}"

        val beforeJson = existingRem?.let { json.encodeToString(it) }
        val afterJson = json.encodeToString(reminder)
        val event = RawEvent(uId, "SCHEDULE", "reminder", rId, beforeJson = beforeJson, afterJson = afterJson, tsUtc = clock.now(), causeMsgId = causeMsgId)
        storage.createReminderAndCommit(reminder, event, consumeProposalId)

        return ToolResult.Committed("SCHEDULE", rId, "Reminder set for ${dateResolver.format(fireAt)}", uId)
    }

    private suspend fun handleConfirmChange(proposalId: String, choice: String, userMessage: String? = null): ToolResult {
        val resolvedProposalId = resolveRef(proposalId, "p")
        val proposal = storage.getProposal(resolvedProposalId)
            ?: return ToolResult.Error("unknown_ref: no proposal '$proposalId'")
        if (proposal.status != "open") return ToolResult.Error("Proposal already resolved")

        val chosenOption = proposal.options.find {
            it.id == choice || (choice == "update" && it.id != "add_new" && it.id != "cancel")
        } ?: return ToolResult.Error("Invalid choice")

        // H-13 (R-15): the overlap check below is "any shared token", so a reply that *rejects*
        // the option ("no, don't set dinner") matched it through "dinner" and committed. A
        // negated reply never confirms, whatever it names.
        if (userMessage != null && isNegated(userMessage, chosenOption, proposal)) {
            return ToolResult.Error("Cannot confirm proposal: the reply rejects it")
        }
        if (userMessage != null && !matchesUserMessage(userMessage, chosenOption, choice, proposal)) {
            return ToolResult.Error("Cannot confirm proposal: choice '$choice' does not match user's response")
        }

        if (proposal.kind == "needs_confirm") {
            val payload = proposal.payload?.let {
                try { json.decodeFromString<ProposalPayload>(it) } catch (e: Exception) { null }
            }

            return try {
                when {
                    choice == "cancel" || choice == "no" -> {
                        val consumed = storage.consumeProposalCas(proposal.id)
                        if (!consumed) return ToolResult.Error("Proposal already resolved")
                        // H-05 (R-09): no event was written, so there is nothing to undo. An empty
                        // token is not "no token" — the UI renders Undo on `undoToken != null`.
                        ToolResult.Committed("CANCEL", proposal.id, "Cancelled: no changes made", "")
                    }
                    choice == "set" -> {
                        val targetAtMillis = payload?.targetAtMillis ?: return ToolResult.Error("No target time specified")
                        val targetRef = payload.targetId ?: ""
                        val tier = payload.tier ?: AlarmTier.ALARM
                        handleScheduleReminderWithTime(targetRef, targetAtMillis, tier, causeMsgId = null, consumeProposalId = proposal.id)
                    }
                    choice == "add_new" -> {
                        val title = payload?.title ?: "Reminder"
                        val type = payload?.type ?: "task"
                        val targetAtMillis = payload?.targetAtMillis
                        val followUpTo = payload?.followUpTo
                        val priority = payload?.priority ?: 0
                        val severity = payload?.severity ?: 0
                        val pinned = payload?.pinned ?: false
                        val tier = payload?.tier ?: AlarmTier.ALARM
                        handleCreate(
                            title = title,
                            type = type,
                            dueAt = targetAtMillis,
                            followUpTo = followUpTo,
                            priority = priority,
                            severity = severity,
                            pinned = pinned,
                            tier = tier,
                            causeMsgId = null,
                            consumeProposalId = proposal.id
                        )
                    }
                    else -> {
                        val targetNodeId = if (choice != "update") choice else (payload?.targetId ?: return ToolResult.Error("Target node not found"))
                        val existingNode = storage.getNode(targetNodeId)
                        if (existingNode == null) {
                            if (storage.getProposal(resolvedProposalId)?.status != "open") {
                                return ToolResult.Error("Proposal already resolved")
                            }
                            storage.consumeProposal(proposal.id)
                            return ToolResult.Error("Node not found")
                        }
                        if (existingNode.status !in listOf("active", "in_progress", "paused")) {
                            if (storage.getProposal(resolvedProposalId)?.status != "open") {
                                return ToolResult.Error("Proposal already resolved")
                            }
                            storage.consumeProposal(proposal.id)
                            return ToolResult.Error("Node is no longer active")
                        }

                        val now = clock.now()
                        val targetTime = payload?.targetAtMillis ?: existingNode.dueAt
                        val updatedNode = existingNode.copy(dueAt = targetTime, updatedAt = now)
                        val oldRem = storage.getReminderByNodeId(existingNode.id) ?: storage.getPendingReminders().find { it.nodeId == existingNode.id || it.id == "r_${existingNode.id}" }
                        val targetTier = payload?.tier ?: oldRem?.tier ?: AlarmTier.ALARM
                        val newRem = if (targetTime != null) {
                            if (oldRem != null) {
                                oldRem.copy(text = updatedNode.title, fireAtMillis = targetTime, tier = targetTier)
                            } else {
                                Reminder("r_${existingNode.id}", updatedNode.title, targetTime, existingNode.id, tier = targetTier)
                            }
                        } else {
                            null
                        }
                        val deleteRemId = if (targetTime == null) oldRem?.id else null

                        val beforeJson = json.encodeToString(NodeStateSnapshot(existingNode, oldRem))
                        val afterJson = json.encodeToString(NodeStateSnapshot(updatedNode, newRem))
                        val uId = "u_${UUID.randomUUID()}"
                        val event = RawEvent(uId, "UPDATE", "node", updatedNode.id, beforeJson = beforeJson, afterJson = afterJson, tsUtc = now)

                        storage.updateNodeAndCommit(
                            node = updatedNode,
                            event = event,
                            deleteReminderId = deleteRemId,
                            upsertReminder = newRem,
                            consumeProposalId = proposal.id
                        )

                        val timeStr = if (targetTime != null) " (Due: ${dateResolver.format(targetTime)})" else ""
                        ToolResult.Committed("UPDATE", updatedNode.id, "Updated: ${updatedNode.title}$timeStr", uId)
                    }
                }
            } catch (e: ProposalAlreadyResolvedException) {
                ToolResult.Error("Proposal already resolved")
            } catch (t: Throwable) {
                storage.reopenProposal(proposal.id)
                throw t
            }
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
                            val matchingNodes = storage.findActiveNodesByTitle(title)
                            val duplicate = matchingNodes.firstOrNull { existing ->
                                isNearMatch(existing.dueAt, chosenMillis)
                            }
                            if (duplicate != null) {
                                val pId = "p_${UUID.randomUUID()}"
                                val dueStr = if (duplicate.dueAt != null) dateResolver.format(duplicate.dueAt) else null
                                val newPayload = payload?.copy(targetAtMillis = chosenMillis, targetId = duplicate.id) ?: ProposalPayload(targetAtMillis = chosenMillis, title = title, targetId = duplicate.id)
                                val newProposal = Proposal(
                                    id = pId,
                                    kind = "needs_confirm",
                                    intent = IntentType.CREATE,
                                    options = listOf(
                                        ProposalOption(duplicate.id, "Update existing", dueStr),
                                        ProposalOption("add_new", "Add anyway"),
                                        ProposalOption("cancel", "Cancel")
                                    ),
                                    payload = json.encodeToString(newPayload)
                                )
                                storage.consumeProposal(proposal.id)
                                storage.saveProposal(newProposal)
                                val msg = "Similar item already exists: ${duplicate.title}${if (dueStr != null) " at $dueStr" else ""}"
                                return ToolResult.NeedsConfirm(pId, msg, listOf("Update existing", "Add anyway", "Cancel"))
                            }
                        }

                        val type = payload?.type ?: "task"
                        val followUpTo = payload?.followUpTo
                        val priority = payload?.priority ?: 0
                        val severity = payload?.severity ?: 0
                        val pinned = payload?.pinned ?: false
                        val tier = payload?.tier ?: AlarmTier.ALARM
                        handleCreate(title, type, chosenMillis, followUpTo, priority, severity, pinned, tier, consumeProposalId = proposal.id)
                    }
                    IntentType.RESCHEDULE -> {
                        val hint = payload?.hint ?: ""
                        handleResolveChange(IntentType.RESCHEDULE, hint, timeForResolver = null, destinationTimeMillis = chosenMillis, consumeProposalId = proposal.id)
                    }
                    IntentType.UPDATE -> {
                        val targetRef = payload?.targetId
                        if (targetRef != null) {
                            // schedule_reminder time-chip proposal: shift the existing reminder's fire time.
                            val tier = payload.tier ?: AlarmTier.ALARM
                            handleScheduleReminderWithTime(targetRef, chosenMillis, tier, consumeProposalId = proposal.id)
                        } else {
                            // propose_change UPDATE time-chip proposal: apply the full field set to the resolved node.
                            val hint = payload?.hint ?: ""
                            if (hint.isBlank()) {
                                storage.consumeProposal(proposal.id)
                                ToolResult.Error("Missing hint: which item should be updated?")
                            } else {
                                resolveAndUpdate(
                                    hint = hint,
                                    newTitle = payload?.newTitle,
                                    newDueMillis = chosenMillis,
                                    newPriority = payload?.priority,
                                    newSeverity = payload?.severity,
                                    newPinned = payload?.pinned,
                                    newTier = payload?.tier,
                                    consumeProposalId = proposal.id
                                )
                            }
                        }
                    }
                    else -> ToolResult.Error("${proposal.intent.name} is not supported for time chips")
                }
            } catch (e: ProposalAlreadyResolvedException) {
                ToolResult.Error("Proposal already resolved")
            } catch (t: Throwable) {
                storage.reopenProposal(proposal.id)
                throw t
            }
        }

        val nodeId = choice
        val node = storage.getNode(nodeId)
        if (node == null) {
            if (storage.getProposal(resolvedProposalId)?.status != "open") {
                return ToolResult.Error("Proposal already resolved")
            }
            storage.consumeProposal(proposal.id)
            return ToolResult.Error("Node not found")
        }
        if (node.status !in listOf("active", "in_progress", "paused")) {
            if (storage.getProposal(resolvedProposalId)?.status != "open") {
                return ToolResult.Error("Proposal already resolved")
            }
            storage.consumeProposal(proposal.id)
            return ToolResult.Error("Node is no longer active")
        }

        val now = clock.now()
        val oldRem = storage.getReminderByNodeId(node.id) ?: storage.getPendingReminders().find { it.nodeId == node.id || it.id == "r_${node.id}" }

        return try {
            when (proposal.intent) {
                IntentType.CANCEL -> {
                    val newNode = node.copy(status = "cancelled", closedAt = now, updatedAt = now)
                    val beforeJson = json.encodeToString(NodeStateSnapshot(node, oldRem))
                    val afterJson = json.encodeToString(NodeStateSnapshot(newNode, null))
                    val uId = "u_${UUID.randomUUID()}"
                    val event = RawEvent(uId, "CANCEL", "node", newNode.id, beforeJson = beforeJson, afterJson = afterJson, tsUtc = now)
                    storage.updateNodeAndCommit(newNode, event, deleteReminderId = oldRem?.id, upsertReminder = null, consumeProposalId = proposal.id)
                    ToolResult.Committed("CANCEL", newNode.id, "Applied CANCEL: ${newNode.title}", uId)
                }
                IntentType.COMPLETE -> {
                    val isRecurring = node.type == "habit" || node.recurrenceRule != null || oldRem?.rrule != null
                    if (isRecurring) {
                        val rule = node.recurrenceRule ?: oldRem?.rrule ?: "FREQ=DAILY"
                        val streak = com.anchor.core.recurrence.RecurrenceEngine.evaluateHabitCompletion(
                            rrule = rule,
                            lastCompletedAt = node.lastCompletedAt,
                            currentStreak = node.streakCurrent,
                            bestStreak = node.streakBest,
                            now = now
                        )
                        val baseTime = node.dueAt ?: oldRem?.fireAtMillis ?: now
                        val nextDue = com.anchor.core.recurrence.RecurrenceEngine.nextOccurrence(rule, baseTime, now)
                        val newNode = node.copy(
                            status = "active",
                            dueAt = nextDue,
                            recurrenceRule = rule,
                            streakCurrent = streak.currentStreak,
                            streakBest = streak.bestStreak,
                            lastCompletedAt = now,
                            updatedAt = now,
                            closedAt = null
                        )
                        val remId = oldRem?.id ?: "r_${node.id}"
                        val existingTier = oldRem?.tier ?: AlarmTier.ALARM
                        val nextRem = (oldRem ?: Reminder(remId, node.title, nextDue, node.id, tier = existingTier, rrule = rule)).copy(
                            fireAtMillis = nextDue,
                            rrule = rule,
                            iteration = (oldRem?.iteration ?: 0) + 1
                        )
                        val beforeJson = json.encodeToString(NodeStateSnapshot(node, oldRem))
                        val afterJson = json.encodeToString(NodeStateSnapshot(newNode, nextRem))
                        val uId = "u_${UUID.randomUUID()}"
                        val event = RawEvent(uId, "COMPLETE", "node", newNode.id, beforeJson = beforeJson, afterJson = afterJson, tsUtc = now)
                        storage.updateNodeAndCommit(newNode, event, deleteReminderId = null, upsertReminder = nextRem, consumeProposalId = proposal.id)
                        ToolResult.Committed("COMPLETE", newNode.id, "Completed: ${newNode.title} (🔥 ${newNode.streakCurrent} streak)", uId)
                    } else {
                        val newNode = node.copy(status = "done", closedAt = now, updatedAt = now)
                        val beforeJson = json.encodeToString(NodeStateSnapshot(node, oldRem))
                        val afterJson = json.encodeToString(NodeStateSnapshot(newNode, null))
                        val uId = "u_${UUID.randomUUID()}"
                        val event = RawEvent(uId, "COMPLETE", "node", newNode.id, beforeJson = beforeJson, afterJson = afterJson, tsUtc = now)
                        storage.updateNodeAndCommit(newNode, event, deleteReminderId = oldRem?.id, upsertReminder = null, consumeProposalId = proposal.id)
                        ToolResult.Committed("COMPLETE", newNode.id, "Applied COMPLETE: ${newNode.title}", uId)
                    }
                }
                IntentType.RESCHEDULE -> {
                    val payload = proposal.payload?.let {
                        try { json.decodeFromString<ProposalPayload>(it) } catch (e: Exception) { null }
                    }
                    val target = payload?.targetAtMillis
                    if (target == null || target <= clock.now()) {
                        storage.consumeProposal(proposal.id)
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
                    val uId = "u_${UUID.randomUUID()}"
                    val event = RawEvent(uId, "RESCHEDULE", "node", newNode.id, beforeJson = beforeJson, afterJson = afterJson, tsUtc = now)
                    storage.updateNodeAndCommit(newNode, event, deleteReminderId = null, upsertReminder = newRem, consumeProposalId = proposal.id)
                    ToolResult.Committed("RESCHEDULE", newNode.id, "Rescheduled: ${newNode.title} to ${dateResolver.format(target)}", uId)
                }
                IntentType.UPDATE -> {
                    val payload = proposal.payload?.let {
                        try { json.decodeFromString<ProposalPayload>(it) } catch (e: Exception) { null }
                    }
                    val newTitle = payload?.newTitle
                    val targetDue = payload?.targetAtMillis
                    if (targetDue != null && targetDue <= clock.now()) {
                        storage.consumeProposal(proposal.id)
                        return ToolResult.Error("That time has already passed; tell me the new time again")
                    }
                    if (payload == null || (newTitle == null && targetDue == null && payload.priority == null &&
                        payload.severity == null && payload.pinned == null && payload.tier == null)
                    ) {
                        storage.consumeProposal(proposal.id)
                        return ToolResult.Error("No changes specified for UPDATE: provide new_title, due_text, priority, severity, pinned, or tier")
                    }
                    applyNodeUpdate(
                        oldNode = node,
                        newTitle = newTitle,
                        newDueMillis = targetDue,
                        newPriority = payload.priority,
                        newSeverity = payload.severity,
                        newPinned = payload.pinned,
                        newTier = payload.tier,
                        causeMsgId = null,
                        consumeProposalId = proposal.id
                    )
                }
                else -> ToolResult.Error("${proposal.intent.name} is not supported yet")
            }
        } catch (e: ProposalAlreadyResolvedException) {
            ToolResult.Error("Proposal already resolved")
        } catch (t: Throwable) {
            storage.reopenProposal(proposal.id)
            throw t
        }
    }

    /**
     * H-13 (R-15): true when the reply rejects rather than selects. Contractions are expanded
     * first ("don't" → "do not"), then any negation token rejects — a reply like "no, dinner" or
     * "not the dinner one" names the option and still rejects it. The one exception is a reply
     * that *is* the title of the item the option acts on ("not now review"): the user is naming
     * the item, not negating it, so the title's own words must not count against it. The item
     * title is read from the proposal payload — an option's own title is a label like "Update
     * existing", never the item name, so exempting it would exempt nothing.
     */
    private fun isNegated(userMessage: String, option: ProposalOption, proposal: Proposal): Boolean {
        val expanded = userMessage.trim().lowercase(java.util.Locale.US)
            .replace("’", "'")
            .replace("cannot", "can not")
            .replace("n't", " not")
        val tokens = expanded.split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
        val tokenSet = tokens.toSet()
        val itemTitle = proposal.payload?.let {
            try { json.decodeFromString<ProposalPayload>(it).title } catch (e: Exception) { null }
        }
        val titleTokens = itemTitle?.trim()?.lowercase(java.util.Locale.US)
            ?.split(Regex("[^a-z0-9]+"))?.filter { it.isNotEmpty() }.orEmpty()
        if (titleTokens.isNotEmpty() && tokenSet == titleTokens.toSet()) {
            return false
        }
        return tokens.any { it in NEGATION_TOKENS }
    }

    private fun matchesUserMessage(userMessage: String, option: ProposalOption, choice: String, proposal: Proposal): Boolean {
        val cleanMsg = userMessage.trim().lowercase(java.util.Locale.US)
        val cleanChoice = choice.trim().lowercase(java.util.Locale.US)
        val cleanTitle = option.title.trim().lowercase(java.util.Locale.US)
        // The item's own name is a valid way to select its option: a dedupe chip's option title
        // is "Update existing" and its id is the node id, so without this the only way to answer
        // "Similar item already exists: Not now review" by name is refused.
        val itemTitle = proposal.payload?.let {
            try { json.decodeFromString<ProposalPayload>(it).title } catch (e: Exception) { null }
        }?.trim()?.lowercase(java.util.Locale.US).orEmpty()

        if (cleanMsg == cleanChoice || cleanMsg == cleanTitle) return true
        if (itemTitle.isNotEmpty() && cleanMsg == itemTitle) return true
        if (cleanTitle.contains(cleanMsg) || cleanMsg.contains(cleanTitle)) return true

        val stopWords = setOf("the", "a", "an", "to", "for", "in", "on", "at", "my", "me", "i", "it", "this", "that", "please")
        val userTokens = cleanMsg.split(Regex("[^a-zA-Z0-9]+")).filter { it.length >= 2 && it !in stopWords }
        val optionTokens = cleanTitle.split(Regex("[^a-zA-Z0-9]+")).filter { it.length >= 2 && it !in stopWords }
        val choiceTokens = cleanChoice.split(Regex("[^a-zA-Z0-9]+")).filter { it.length >= 2 && it !in stopWords }
        val itemTokens = itemTitle.split(Regex("[^a-zA-Z0-9]+")).filter { it.length >= 2 && it !in stopWords }

        if (userTokens.any { it in optionTokens || it in choiceTokens || it in itemTokens }) return true

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
        recurrenceRule: String? = null,
        causeMsgId: String? = null,
        consumeProposalId: String? = null
    ): ToolResult {
        val followUpEdge: Edge?
        val followUpEdgeEvent: RawEvent?
        val nId = "n_${UUID.randomUUID()}"
        val now = clock.now()

        if (!followUpTo.isNullOrBlank()) {
            val srcNode = storage.getNode(followUpTo)
            if (srcNode == null) {
                return ToolResult.Error("Cannot create follow-up: source item '$followUpTo' not found")
            }
            val edgeId = "e_${UUID.randomUUID()}"
            val edge = Edge(
                id = edgeId,
                srcId = srcNode.id,
                dstId = nId,
                type = EdgeTypes.FOLLOW_UP,
                createdAt = now,
                updatedAt = now
            )
            val edgeEvent = RawEvent(
                opId = "u_${UUID.randomUUID()}",
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

        val effectiveRRule = recurrenceRule ?: if (type == "habit") "FREQ=DAILY" else null
        val node = Node(nId, type, title, "active", dueAt, createdAt = now, updatedAt = now, priority = priority, severity = severity, pinned = pinned, recurrenceRule = effectiveRRule)

        // Fix: Auto-create the reminder inherently so the LLM doesn't have to call a separate tool
        val reminder = dueAt?.let { Reminder("r_$nId", title, it, nId, tier = tier, rrule = effectiveRRule) }

        val afterJson = json.encodeToString(NodeStateSnapshot(node, reminder))
        val uId = "u_${UUID.randomUUID()}"
        val event = RawEvent(uId, IntentType.CREATE.name, "node", nId, afterJson = afterJson, tsUtc = now, causeMsgId = causeMsgId)

        storage.createNodeAndCommit(node, event, reminder, followUpEdge, followUpEdgeEvent, consumeProposalId)

        if (embedder != null && !node.localOnly) {
            val vec = embedder.embed(listOf(node.title), com.anchor.core.retrieval.Embedder.Kind.DOCUMENT).firstOrNull()
            if (vec != null) {
                storage.saveEmbedding("node", node.id, embedder.version, vec)
            }
        }

        val timeStr = if (dueAt != null) " (Due: ${dateResolver.format(dueAt)})" else ""
        return ToolResult.Committed(IntentType.CREATE.name, nId, "Created: $title$timeStr", uId)
    }

    private suspend fun handleUpdate(args: Map<String, String>, causeMsgId: String?): ToolResult {
        val hint = (args["hint"] ?: args["target"] ?: "").take(200)
        if (hint.isBlank()) {
            return ToolResult.Error("Missing hint: which item should be updated?")
        }

        // P1-B 16: UPDATE supports optional field changes; at least one change must be requested.
        val newTitle = args["new_title"]?.trim()?.take(200)
        val dueText = args["due_text"]?.trim()?.take(100)?.ifBlank { null }
        val newPriority = args["priority"]?.toIntOrNull()
        val newSeverity = args["severity"]?.toIntOrNull()
        val newPinned = args["pinned"]?.toBooleanStrictOrNull()
        val tierStr = args["tier"]?.trim()?.lowercase(java.util.Locale.US)
        val newTier = if (tierStr == "gentle") AlarmTier.GENTLE else if (tierStr == "alarm") AlarmTier.ALARM else null
        val newDueText = args["new_due_text"]?.trim()?.take(100)?.ifBlank { null } ?: dueText

        if (newTitle.isNullOrBlank() && newDueText.isNullOrBlank() && newPriority == null &&
            newSeverity == null && newPinned == null && newTier == null
        ) {
            return ToolResult.Error("No changes specified for UPDATE: provide new_title, due_text, priority, severity, pinned, or tier")
        }

        val dayRule = storage.getEffectiveDayRule(clock.now())
        val rolloverMin = dayRule.startMinute
        val timeZone = TimeZone.getTimeZone(dayRule.tzId)

        var targetDueMillis: Long? = null
        if (!newDueText.isNullOrBlank()) {
            when (val timeResolution = dateResolver.resolve(newDueText, rolloverMin, timeZone)) {
                is DateResolution.NeedsClarification -> {
                    val pId = "p_${UUID.randomUUID()}"
                    val opts = timeResolution.candidateMillis.map { millis ->
                        ProposalOption(millis.toString(), dateResolver.formatTimeOnly(millis, timeZone))
                    }
                    val payload = ProposalPayload(
                        intent = IntentType.UPDATE,
                        hint = hint,
                        newTitle = newTitle,
                        priority = newPriority,
                        severity = newSeverity,
                        pinned = newPinned,
                        tier = newTier
                    )
                    storage.saveProposal(Proposal(pId, "time", IntentType.UPDATE, opts, payload = json.encodeToString(payload)))
                    return ToolResult.NeedsTime(pId, timeResolution.chipLabels)
                }
                is DateResolution.PastTime -> return ToolResult.Error("That time has already passed; tell me the new time again")
                is DateResolution.Failed -> return ToolResult.Error("Could not understand the time '$newDueText'. Ask the user to clarify.")
                is DateResolution.Exact -> targetDueMillis = timeResolution.timeMillis
            }
        }

        return resolveAndUpdate(
            hint = hint,
            newTitle = newTitle,
            newDueMillis = targetDueMillis,
            newPriority = newPriority,
            newSeverity = newSeverity,
            newPinned = newPinned,
            newTier = newTier,
            causeMsgId = causeMsgId
        )
    }

    private suspend fun resolveAndUpdate(
        hint: String,
        newTitle: String?,
        newDueMillis: Long?,
        newPriority: Int?,
        newSeverity: Int?,
        newPinned: Boolean?,
        newTier: AlarmTier?,
        causeMsgId: String? = null,
        consumeProposalId: String? = null,
        resolvedNodeId: String? = null
    ): ToolResult {
        val oldNode = if (resolvedNodeId != null) {
            storage.getNode(resolvedNodeId)
        } else {
            val candidates = try {
                storage.getAllActiveNodesPaged(pageSize = 200, hardCap = 5000)
            } catch (e: TooManyActiveNodesException) {
                return ToolResult.Error("There are too many active items (${e.cap}+). Please be more specific with the item title or due time.")
            }
            when (val resolution = resolver.resolve(IntentType.UPDATE, hint, null, candidates)) {
                is Resolution.NotFound -> null
                is Resolution.Ambiguous -> {
                    if (consumeProposalId != null) storage.consumeProposal(consumeProposalId)
                    val pId = "p_${UUID.randomUUID()}"
                    val opts = resolution.options.map { node ->
                        val dueStr = if (node.dueAt != null) dateResolver.format(node.dueAt) else null
                        val fullTitle = if (dueStr != null) "${node.title} ($dueStr)" else node.title
                        ProposalOption(node.id, fullTitle, dueStr)
                    }
                    val payload = ProposalPayload(
                        intent = IntentType.UPDATE,
                        hint = hint,
                        newTitle = newTitle,
                        targetAtMillis = newDueMillis,
                        priority = newPriority,
                        severity = newSeverity,
                        pinned = newPinned,
                        tier = newTier
                    )
                    storage.saveProposal(Proposal(pId, "ambiguous", IntentType.UPDATE, opts, payload = json.encodeToString(payload)))
                    return ToolResult.Ambiguous(pId, "ask: which item", opts.map { "${it.id} '${it.title}'" })
                }
                is Resolution.ClearWinner -> resolution.node
            }
        }

        if (oldNode == null) {
            if (consumeProposalId != null) storage.consumeProposal(consumeProposalId)
            return ToolResult.NotFound(hint)
        }
        if (oldNode.status !in listOf("active", "in_progress", "paused")) {
            if (consumeProposalId != null) storage.consumeProposal(consumeProposalId)
            return ToolResult.Error("Node is no longer active")
        }

        return applyNodeUpdate(oldNode, newTitle, newDueMillis, newPriority, newSeverity, newPinned, newTier, causeMsgId, consumeProposalId)
    }

    private suspend fun applyNodeUpdate(
        oldNode: Node,
        newTitle: String?,
        newDueMillis: Long?,
        newPriority: Int?,
        newSeverity: Int?,
        newPinned: Boolean?,
        newTier: AlarmTier?,
        causeMsgId: String?,
        consumeProposalId: String?
    ): ToolResult {
        val now = clock.now()
        val oldRem = storage.getReminderByNodeId(oldNode.id) ?: storage.getPendingReminders().find { it.nodeId == oldNode.id || it.id == "r_${oldNode.id}" }

        val effectiveTitle = newTitle ?: oldNode.title
        val effectiveDue = newDueMillis ?: oldNode.dueAt
        val effectivePriority = newPriority ?: oldNode.priority
        val effectiveSeverity = newSeverity ?: oldNode.severity
        val effectivePinned = newPinned ?: oldNode.pinned

        // Tier lives on the reminder; an undated node has no reminder, so a tier-only UPDATE
        // would be a silent no-op. Fail honestly instead of reporting a false Committed.
        val tierIsOnlyChange = newTier != null && newTitle == null && newDueMillis == null &&
            newPriority == null && newSeverity == null && newPinned == null
        if (tierIsOnlyChange && effectiveDue == null) {
            if (consumeProposalId != null) storage.consumeProposal(consumeProposalId)
            return ToolResult.Error("Cannot change tier: '${oldNode.title}' has no due date, so there is no reminder to change. Set a due time first.")
        }

        val updatedNode = oldNode.copy(
            title = effectiveTitle,
            dueAt = effectiveDue,
            priority = effectivePriority,
            severity = effectiveSeverity,
            pinned = effectivePinned,
            updatedAt = now
        )

        // Reminder follows the node: text syncs to title, fire time syncs to due date, tier only changes when explicitly requested.
        val newRem = if (effectiveDue != null) {
            val tier = newTier ?: oldRem?.tier ?: AlarmTier.ALARM
            if (oldRem != null) {
                oldRem.copy(text = effectiveTitle, fireAtMillis = effectiveDue, tier = tier)
            } else {
                Reminder("r_${oldNode.id}", effectiveTitle, effectiveDue, oldNode.id, tier = tier)
            }
        } else {
            oldRem // undated node keeps its reminder untouched
        }

        val beforeJson = json.encodeToString(NodeStateSnapshot(oldNode, oldRem))
        val afterJson = json.encodeToString(NodeStateSnapshot(updatedNode, newRem))

        val uId = "u_${UUID.randomUUID()}"
        val event = RawEvent(uId, IntentType.UPDATE.name, "node", updatedNode.id, beforeJson = beforeJson, afterJson = afterJson, tsUtc = clock.now(), causeMsgId = causeMsgId)
        storage.updateNodeAndCommit(updatedNode, event, deleteReminderId = null, upsertReminder = newRem, consumeProposalId = consumeProposalId)
        if (embedder != null && !updatedNode.localOnly && newTitle != null && newTitle != oldNode.title) {
            val vec = embedder.embed(listOf(updatedNode.title), com.anchor.core.retrieval.Embedder.Kind.DOCUMENT).firstOrNull()
            if (vec != null) {
                storage.saveEmbedding("node", updatedNode.id, embedder.version, vec)
            }
        }

        val changes = mutableListOf<String>()
        changes.add("Updated: ${updatedNode.title}")
        if (newTitle != null && newTitle != oldNode.title) changes.add("title now '${updatedNode.title}'")
        if (newDueMillis != null) changes.add("due ${dateResolver.format(effectiveDue!!)}")
        if (newPriority != null && newPriority != oldNode.priority) changes.add("priority ${effectivePriority}")
        if (newSeverity != null && newSeverity != oldNode.severity) changes.add("severity ${effectiveSeverity}")
        if (newPinned != null && newPinned != oldNode.pinned) changes.add(if (effectivePinned) "pinned" else "unpinned")
        if (newTier != null && oldRem != null && newTier != oldRem.tier) changes.add("tier ${newTier.name.lowercase(java.util.Locale.US)}")

        return ToolResult.Committed(IntentType.UPDATE.name, updatedNode.id, changes.joinToString(", "), uId)
    }

    private suspend fun handleResolveChange(
        intent: IntentType,
        hint: String,
        timeForResolver: Long?,
        destinationTimeMillis: Long?,
        causeMsgId: String? = null,
        consumeProposalId: String? = null
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
            is Resolution.NotFound -> {
                if (consumeProposalId != null) {
                    storage.consumeProposal(consumeProposalId)
                }
                ToolResult.NotFound(hint)
            }
            is Resolution.Ambiguous -> {
                if (consumeProposalId != null) {
                    storage.consumeProposal(consumeProposalId)
                }
                val pId = "p_${UUID.randomUUID()}"
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

                // R-26: the pending read is paged, so it is only a fallback. A reminder outside
                // the page must still be found by its node, or RESCHEDULE would create a second row.
                val oldRem = storage.getReminderByNodeId(oldNode.id)
                    ?: storage.getPendingReminders().find { it.nodeId == oldNode.id || it.id == "r_${oldNode.id}" }
                val remId = oldRem?.id ?: "rem_${UUID.randomUUID()}"
                val existingTier = oldRem?.tier ?: AlarmTier.ALARM

                val isRecurringHabit = intent == IntentType.COMPLETE && (oldNode.type == "habit" || oldNode.recurrenceRule != null || oldRem?.rrule != null)

                val (newNode, newRem, deleteReminderId) = when {
                    intent == IntentType.CANCEL -> {
                        Triple(oldNode.copy(status = "cancelled", closedAt = now, updatedAt = now), null, oldRem?.id)
                    }
                    isRecurringHabit -> {
                        val rule = oldNode.recurrenceRule ?: oldRem?.rrule ?: "FREQ=DAILY"
                        val streak = com.anchor.core.recurrence.RecurrenceEngine.evaluateHabitCompletion(
                            rrule = rule,
                            lastCompletedAt = oldNode.lastCompletedAt,
                            currentStreak = oldNode.streakCurrent,
                            bestStreak = oldNode.streakBest,
                            now = now
                        )
                        val baseTime = oldNode.dueAt ?: oldRem?.fireAtMillis ?: now
                        val nextDue = com.anchor.core.recurrence.RecurrenceEngine.nextOccurrence(rule, baseTime, now)
                        val nodeRes = oldNode.copy(
                            status = "active",
                            dueAt = nextDue,
                            recurrenceRule = rule,
                            streakCurrent = streak.currentStreak,
                            streakBest = streak.bestStreak,
                            lastCompletedAt = now,
                            updatedAt = now,
                            closedAt = null
                        )
                        val nextRem = (oldRem ?: Reminder(remId, oldNode.title, nextDue, oldNode.id, tier = existingTier, rrule = rule)).copy(
                            fireAtMillis = nextDue,
                            rrule = rule,
                            iteration = (oldRem?.iteration ?: 0) + 1
                        )
                        Triple(nodeRes, nextRem, null)
                    }
                    intent == IntentType.COMPLETE -> {
                        Triple(oldNode.copy(status = "done", closedAt = now, updatedAt = now), null, oldRem?.id)
                    }
                    intent == IntentType.RESCHEDULE -> {
                        val destTime = destinationTimeMillis ?: return ToolResult.Error("To when?")
                        val nodeRes = oldNode.copy(dueAt = destTime, updatedAt = now)
                        val remRes = if (oldRem != null) {
                            oldRem.copy(text = nodeRes.title, fireAtMillis = destTime)
                        } else {
                            Reminder(remId, nodeRes.title, destTime, nodeRes.id, tier = existingTier)
                        }
                        Triple(nodeRes, remRes, null)
                    }
                    else -> return ToolResult.Error("${intent.name} is not supported yet")
                }

                val beforeJson = json.encodeToString(NodeStateSnapshot(oldNode, oldRem))
                val afterJson = json.encodeToString(NodeStateSnapshot(newNode, newRem))

                val uId = "u_${UUID.randomUUID()}"
                val event = RawEvent(uId, intent.name, "node", newNode.id, beforeJson = beforeJson, afterJson = afterJson, tsUtc = clock.now(), causeMsgId = causeMsgId)

                storage.updateNodeAndCommit(newNode, event, deleteReminderId, newRem, consumeProposalId)
                val receiptText = when {
                    isRecurringHabit -> "Completed: ${newNode.title} (🔥 ${newNode.streakCurrent} streak)"
                    intent == IntentType.RESCHEDULE -> {
                        val dest = destinationTimeMillis ?: return ToolResult.Error("To when?")
                        "Rescheduled: ${newNode.title} to ${dateResolver.format(dest)}"
                    }
                    else -> "Applied ${intent.name}: ${newNode.title}"
                }
                return ToolResult.Committed(intent.name, newNode.id, receiptText, uId)
            }
        }
    }

    /**
     * Blueprint 23 §2.2/§4: results carry a short per-turn alias, never a real id, and the
     * gateway maps the alias back. The map lives in this instance for the turn that produced
     * the results (one Gateway per chat controller); a new turn replaces it.
     */
    val aliasRegistry: AliasRegistry = AliasRegistry()

    fun aliasFor(prefix: String, targetId: String): String {
        return aliasRegistry.aliasFor(prefix, targetId)
    }

    /**
     * Resolves a tool argument that may be an alias (`n_1`) or a real id.
     * An alias-shaped ref the map never issued is passed through unchanged, because real ids
     * share the alias prefixes (proposals are `p_<hex6>`, soul facts `s_<hex8>`); the caller
     * answers `unknown_ref` when the subsequent storage lookup finds no such row.
     */
    private fun resolveRef(ref: String, prefix: String): String {
        return aliasRegistry.resolveRef(ref, prefix)
    }

    /**
     * Blueprint 23 §5 result format, one line per item:
     * `n3 | task | active | due Fri 23 Oct 18:00 | Write the report`,
     * `s2 | health | Is diabetic`, `m4 | Tue 14 Oct 21:10 | U: ... / A: ...`.
     * Chat hits whose chunk holds more than one message keep the blueprint's `day_key` slot.
     */
    private fun formatSearchResults(results: List<SearchResult>): String {
        if (results.isEmpty()) return "No results found."
        return results.joinToString("\n") { r ->
            when (r.kind) {
                "soul_fact" -> "${aliasFor("s", r.id)} | ${r.title} | ${r.details}"
                "chat" -> {
                    val whenText = r.tsUtc?.let { dateResolver.format(it) } ?: r.title
                    "${aliasFor("m", r.id.removePrefix("chunk_"))} | $whenText | ${r.details}"
                }
                "user", "assistant" -> {
                    val who = if (r.kind == "user") "U" else "A"
                    val whenText = r.tsUtc?.let { dateResolver.format(it) } ?: r.title
                    "${aliasFor("m", r.id)} | $whenText | $who: ${r.details}"
                }
                else -> {
                    val status = Regex("Status: (\\S+)").find(r.details)?.groupValues?.get(1) ?: r.kind
                    val due = Regex("Due: (\\d+)").find(r.details)?.groupValues?.get(1)?.toLongOrNull()
                    val dueText = due?.let { " | due ${dateResolver.format(it)}" } ?: ""
                    "${aliasFor("n", r.id)} | ${r.kind} | $status$dueText | ${r.title}"
                }
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
        val newEventId = "u_${UUID.randomUUID()}"

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
        val uId = "u_${UUID.randomUUID()}"
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
        val uId = "u_${UUID.randomUUID()}"
        val event = RawEvent(uId, "CANCEL_STALE", "node", nodeId, beforeJson = beforeJson, afterJson = afterJson, tsUtc = now)
        val deleteRemId = oldRem?.id ?: "r_$nodeId"
        storage.updateNodeAndCommit(newNode, event, deleteReminderId = deleteRemId, upsertReminder = null)
        return ToolResult.Committed("CANCEL_STALE", nodeId, "Cancelled: ${node.title}", uId)
    }

    suspend fun handleArchiveNode(nodeId: String): ToolResult {
        val node = storage.getNode(nodeId) ?: return ToolResult.Error("Node not found")
        if (node.status == "archived") return ToolResult.Error("Item is already archived")
        val now = clock.now()
        val oldRem = storage.getReminderByNodeId(nodeId) ?: storage.getPendingReminders().find { it.id == "r_$nodeId" }
        val beforeJson = json.encodeToString(NodeStateSnapshot(node, oldRem))
        val newNode = node.copy(status = "archived", closedAt = now, updatedAt = now)
        val afterJson = json.encodeToString(NodeStateSnapshot(newNode, null))
        val uId = "u_${UUID.randomUUID()}"
        val event = RawEvent(uId, "ARCHIVE_NODE", "node", nodeId, beforeJson = beforeJson, afterJson = afterJson, tsUtc = now)
        val deleteRemId = oldRem?.id ?: "r_$nodeId"
        storage.updateNodeAndCommit(newNode, event, deleteReminderId = deleteRemId, upsertReminder = null)
        return ToolResult.Committed("ARCHIVE_NODE", nodeId, "Archived: ${node.title}", uId)
    }

    suspend fun handleDeleteNode(nodeId: String): ToolResult {
        val node = storage.getNode(nodeId) ?: return ToolResult.Error("Node not found")
        if (node.status == "cancelled") return ToolResult.Error("Item is already deleted")
        val now = clock.now()
        val oldRem = storage.getReminderByNodeId(nodeId) ?: storage.getPendingReminders().find { it.id == "r_$nodeId" }
        val beforeJson = json.encodeToString(NodeStateSnapshot(node, oldRem))
        val newNode = node.copy(status = "cancelled", closedAt = now, updatedAt = now)
        val afterJson = json.encodeToString(NodeStateSnapshot(newNode, null))
        val uId = "u_${UUID.randomUUID()}"
        val event = RawEvent(uId, "DELETE_NODE", "node", nodeId, beforeJson = beforeJson, afterJson = afterJson, tsUtc = now)
        val deleteRemId = oldRem?.id ?: "r_$nodeId"
        storage.updateNodeAndCommit(newNode, event, deleteReminderId = deleteRemId, upsertReminder = null)
        return ToolResult.Committed("DELETE_NODE", nodeId, "Deleted: ${node.title}", uId)
    }

    suspend fun handleRestoreNode(nodeId: String): ToolResult {
        val node = storage.getNode(nodeId) ?: return ToolResult.Error("Node not found")
        if (node.status != "cancelled" && node.status != "archived") return ToolResult.Error("Item is not in trash")
        val now = clock.now()
        val oldRem = storage.getReminderByNodeId(nodeId) ?: storage.getPendingReminders().find { it.id == "r_$nodeId" }
        val beforeJson = json.encodeToString(NodeStateSnapshot(node, oldRem))
        val newNode = node.copy(status = "active", closedAt = null, updatedAt = now)
        val afterJson = json.encodeToString(NodeStateSnapshot(newNode, null))
        val uId = "u_${UUID.randomUUID()}"
        val event = RawEvent(uId, "RESTORE_NODE", "node", nodeId, beforeJson = beforeJson, afterJson = afterJson, tsUtc = now)
        storage.updateNodeAndCommit(newNode, event, deleteReminderId = null, upsertReminder = null)
        return ToolResult.Committed("RESTORE_NODE", nodeId, "Restored: ${node.title}", uId)
    }

    suspend fun handleActivateNode(nodeId: String): ToolResult {
        val node = storage.getNode(nodeId) ?: return ToolResult.Error("Node not found")
        val now = clock.now()
        val beforeJson = json.encodeToString(NodeStateSnapshot(node, null))
        val newNode = node.copy(status = "active", updatedAt = now)
        val afterJson = json.encodeToString(NodeStateSnapshot(newNode, null))
        val uId = "u_${UUID.randomUUID()}"
        val event = RawEvent(uId, "ACTIVATE_NODE", "node", nodeId, beforeJson = beforeJson, afterJson = afterJson, tsUtc = now)
        storage.updateNodeAndCommit(newNode, event, deleteReminderId = null, upsertReminder = null)
        return ToolResult.Committed("ACTIVATE_NODE", nodeId, "Activated: ${node.title}", uId)
    }

    suspend fun handleUpdateNodeStatus(nodeId: String, newStatus: String): ToolResult {
        val node = storage.getNode(nodeId) ?: return ToolResult.Error("Node not found")
        val now = clock.now()
        val oldRem = storage.getReminderByNodeId(nodeId) ?: storage.getPendingReminders().find { it.id == "r_$nodeId" }
        val beforeJson = json.encodeToString(NodeStateSnapshot(node, oldRem))
        val closedAt = if (newStatus == "done" || newStatus == "archived" || newStatus == "cancelled") now else null
        val deleteRemId = if (closedAt != null) (oldRem?.id ?: "r_$nodeId") else null
        val newNode = node.copy(status = newStatus, closedAt = closedAt, updatedAt = now)
        val afterJson = json.encodeToString(NodeStateSnapshot(newNode, if (deleteRemId != null) null else oldRem))
        val uId = "u_${UUID.randomUUID()}"
        val event = RawEvent(uId, "UPDATE_STATUS", "node", nodeId, beforeJson = beforeJson, afterJson = afterJson, tsUtc = now)
        storage.updateNodeAndCommit(newNode, event, deleteReminderId = deleteRemId, upsertReminder = null)
        if (deleteRemId != null) {
            alarmScheduler?.cancel(deleteRemId)
        }
        return ToolResult.Committed("UPDATE_STATUS", nodeId, "Updated status of ${node.title} to $newStatus", uId)
    }

    suspend fun handleEraseNode(nodeId: String): ToolResult {
        val node = storage.getNode(nodeId) ?: return ToolResult.Error("Node not found")
        val rem = storage.getReminderByNodeId(nodeId) ?: storage.getReminderById("r_$nodeId")
        if (rem != null) {
            alarmScheduler?.cancel(rem.id)
        }
        val uId = "u_${UUID.randomUUID()}"
        storage.eraseNode(nodeId)
        storage.deleteEmbedding("node", nodeId)
        return ToolResult.Committed("ERASE_NODE", nodeId, "Erased: ${node.title}", uId)
    }

    suspend fun handleEraseSoulFact(factId: String): ToolResult {
        val fact = storage.getActiveSoulFacts(includeLocalOnly = true).find { it.id == factId }
            ?: storage.getProposedSoulFacts().find { it.id == factId }
            ?: return ToolResult.Error("Soul fact not found")
        val uId = "u_${UUID.randomUUID()}"
        storage.eraseSoulFact(factId)
        storage.deleteEmbedding("soul_fact", factId)
        return ToolResult.Committed("ERASE_SOUL_FACT", factId, "Erased fact: ${fact.statement}", uId)
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

        val edgeId = "e_${UUID.randomUUID()}"
        val edge = Edge(
            id = edgeId,
            srcId = srcNodeId,
            dstId = dstNodeId,
            type = EdgeTypes.FOLLOW_UP,
            createdAt = clock.now(),
            updatedAt = clock.now()
        )
        val uId = "u_${UUID.randomUUID()}"
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
        val uId = "u_${UUID.randomUUID()}"
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

    companion object {
        const val DEDUPE_TIME_WINDOW_MS = 2 * 60 * 60 * 1000L // 2 hours

        /** Upper bound on the per-turn alias map before it is dropped wholesale. */
        private const val MAX_ALIASES = 500

        private val NEGATION_TOKENS = setOf(
            "no", "not", "never", "nor", "neither", "none", "without",
            "dont", "doesnt", "didnt", "wont", "isnt", "arent"
        )

        fun isNearMatch(existingDueAt: Long?, newDueAt: Long?): Boolean {
            if (existingDueAt != null && newDueAt != null) {
                return Math.abs(existingDueAt - newDueAt) <= DEDUPE_TIME_WINDOW_MS
            }
            return true
        }
    }
}
