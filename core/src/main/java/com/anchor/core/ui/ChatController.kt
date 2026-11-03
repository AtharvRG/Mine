// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.ui

import com.anchor.core.alarms.AlarmScheduler
import com.anchor.core.context.ContextBuilder
import com.anchor.core.gateway.EnvelopeParser
import com.anchor.core.gateway.Gateway
import com.anchor.core.models.*
import com.anchor.core.provider.ProviderOrchestrator
import com.anchor.core.provider.StreamEvent
import com.anchor.core.provider.Target
import com.anchor.core.storage.Storage
import com.anchor.core.time.Clock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

class ChatController(
    private val storage: Storage,
    private val orchestrator: ProviderOrchestrator,
    private val contextBuilder: ContextBuilder,
    private val gateway: Gateway,
    private val alarmScheduler: AlarmScheduler,
    private val coroutineScope: CoroutineScope,
    private val defaultDispatcher: CoroutineDispatcher,
    private val clock: Clock
) {
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()
    private var currentStreamJob: Job? = null
    private val json = Json { ignoreUnknownKeys = true }

    init {
        coroutineScope.launch(defaultDispatcher) {
            reconcileAlarms()

            _state.update { it.copy(
                activeReminders = storage.getActiveReminders(),
                chatMessages = storage.getRecentMessages(30),
                providerEndpoint = storage.getSetting("ep") ?: "https://api.openai.com/v1/chat/completions",
                providerKey = storage.getSetting("key") ?: "",
                providerModel = storage.getSetting("mod") ?: "gpt-4o-mini",
                debugStats = storage.getDebugStats().entries.joinToString("\n") { e -> "${e.key}: ${e.value}" },
                openProposals = storage.getOpenProposals(),
                proposedSoulFacts = storage.getProposedSoulFacts(), // SEC 8
                inboxCount = storage.getOpenProposals().size + storage.getProposedSoulFacts().size,
                urgentItems = storage.getUrgentNodes()
            )}
        }
        coroutineScope.launch(defaultDispatcher) {
            alarmScheduler.lastFiredId.collect { firedId ->
                if (firedId != null) {
                    refreshUI()
                }
            }
        }
    }

    fun onAction(action: UiAction) {
        when (action) {
            is UiAction.UpdateDraft -> _state.update { it.copy(composerDraft = action.text) }
            is UiAction.SendMessage -> {
                val text = _state.value.composerDraft
                if (_state.value.isChatLoading || text.isBlank()) return
                
                // A12: Check API key without losing draft
                if (_state.value.providerKey.isBlank()) {
                    _state.update { it.copy(connectionError = "Connect an AI provider in Settings first.") }
                    return
                }
                
                _state.update { it.copy(isChatLoading = true, composerDraft = "", connectionError = null) }
                handleSendMessage(text)
            }
            is UiAction.Stop -> {
                // B10: Cancel job, but do NOT set isChatLoading = false here.
                // The finally block of handleSendMessage will do it, preventing job overlap.
                currentStreamJob?.cancel()
            }
            is UiAction.SaveProvider -> coroutineScope.launch(defaultDispatcher) {
                // ISSUE 1: Keep the endpoint intact for users pointing at specific proxies
                val ep = action.endpoint.trim()
                val key = action.key.trim()
                val mod = action.model.trim()
                storage.saveSetting("ep", ep)
                storage.saveSetting("key", key)
                storage.saveSetting("mod", mod)
                _state.update { it.copy(providerEndpoint = ep, providerKey = key, providerModel = mod) }
            }
            is UiAction.CancelReminder -> coroutineScope.launch(defaultDispatcher) {
                storage.deleteReminder(action.id)
                alarmScheduler.cancel(action.id)
                _state.update { it.copy(activeReminders = storage.getActiveReminders()) }
            }
            is UiAction.RunSelfTest -> coroutineScope.launch(defaultDispatcher) {
                _state.update { it.copy(selfTestStatus = "Armed for 5 seconds...") }
                val id = "test_${System.currentTimeMillis()}"
                val fireAt = clock.now() + 5000L
                val reminder = Reminder(id, "Self Test Fired!", fireAt)
                storage.saveReminder(reminder)
                alarmScheduler.schedule(reminder)
                storage.saveRegisteredAlarm(id, id.hashCode(), fireAt)
                _state.update { it.copy(activeReminders = storage.getActiveReminders()) }
            }
            is UiAction.AcceptSoulFact -> coroutineScope.launch(defaultDispatcher) {
                storage.updateSoulFactStatus(action.factId, "active")
                refreshUI()
            }
            is UiAction.RejectSoulFact -> coroutineScope.launch(defaultDispatcher) {
                storage.updateSoulFactStatus(action.factId, "rejected")
                refreshUI()
            }
            is UiAction.RefreshStats -> coroutineScope.launch(defaultDispatcher) {
                refreshUI()
                _state.update { it.copy(debugStats = storage.getDebugStats().entries.joinToString("\n") { e -> "${e.key}: ${e.value}" }) }
            }
            is UiAction.Undo -> coroutineScope.launch(defaultDispatcher) {
                // D-033: Chip taps call gateway directly. Instant, no LLM.
                val call = com.anchor.core.gateway.ToolCall("undo", mapOf("token" to kotlinx.serialization.json.JsonPrimitive(action.token)))
                val result = gateway.executeTool(call)
                when (result) {
                    is ToolResult.Committed -> {
                        _state.update { it.copy(receipts = it.receipts + ReceiptState(UUID.randomUUID().toString(), result.message, result.undoToken, isError = false)) }
                    }
                    is ToolResult.Error -> {
                        _state.update { it.copy(receipts = it.receipts + ReceiptState(UUID.randomUUID().toString(), result.reason, null, isError = true)) }
                    }
                    else -> {}
                }
                refreshUI()
            }
            is UiAction.ConfirmProposal -> coroutineScope.launch(defaultDispatcher) {
                val call = com.anchor.core.gateway.ToolCall("confirm_change", mapOf("proposal" to kotlinx.serialization.json.JsonPrimitive(action.proposalId), "choice" to kotlinx.serialization.json.JsonPrimitive(action.choiceId)))
                val result = gateway.executeTool(call)
                when (result) {
                    is ToolResult.Committed -> {
                        _state.update { it.copy(receipts = it.receipts + ReceiptState(UUID.randomUUID().toString(), result.message, result.undoToken, isError = false)) }
                    }
                    is ToolResult.Error -> {
                        _state.update { it.copy(receipts = it.receipts + ReceiptState(UUID.randomUUID().toString(), result.reason, null, isError = true)) }
                    }
                    is ToolResult.NotFound -> {
                        _state.update { it.copy(receipts = it.receipts + ReceiptState(UUID.randomUUID().toString(), "Item not found: ${result.hint}", null, isError = true)) }
                    }
                    else -> {}
                }
                refreshUI()
            }
            is UiAction.AddFollowUp -> coroutineScope.launch(defaultDispatcher) {
                val edgeId = "e_${UUID.randomUUID().toString().take(8)}"
                val edge = Edge(
                    id = edgeId,
                    srcId = action.srcNodeId,
                    dstId = action.dstNodeId,
                    type = EdgeTypes.FOLLOW_UP,
                    createdAt = clock.now(),
                    updatedAt = clock.now()
                )
                val event = RawEvent(
                    opId = "u_${UUID.randomUUID().toString().take(6)}",
                    opType = "LINK",
                    targetType = "edge",
                    targetId = edgeId,
                    afterJson = json.encodeToString(edge),
                    tsUtc = clock.now()
                )
                storage.createEdgeAndCommit(edge, event)
                refreshUI()
            }
            is UiAction.RemoveFollowUp -> coroutineScope.launch(defaultDispatcher) {
                val event = RawEvent(
                    opId = "u_${UUID.randomUUID().toString().take(6)}",
                    opType = "UNLINK",
                    targetType = "edge",
                    targetId = action.edgeId,
                    tsUtc = clock.now()
                )
                storage.deleteEdgeAndCommit(action.edgeId, event)
                refreshUI()
            }
            is UiAction.LoadDay -> coroutineScope.launch(defaultDispatcher) {
                val msgs = storage.getMessagesForDay(action.dayKey)
                _state.update { it.copy(selectedDayKey = action.dayKey, selectedDayMessages = msgs) }
            }
            is UiAction.ShowDayReminders -> coroutineScope.launch(defaultDispatcher) {
                val rems = storage.getRemindersForDay(action.startOfDayMs, action.endOfDayMs)
                _state.update { it.copy(selectedDayReminders = rems) }
            }
            is UiAction.ClearDaySelection -> {
                _state.update {
                    it.copy(
                        selectedDayKey = null,
                        selectedDayMessages = emptyList(),
                        selectedDayReminders = emptyList()
                    )
                }
            }
            else -> {}
        }
    }

    private fun handleSendMessage(text: String) {
        currentStreamJob = coroutineScope.launch(defaultDispatcher) {
            try {
                val userMsgId = "u_${UUID.randomUUID()}"
                storage.insertRawMessage(userMsgId, "user", text)
                refreshUI()

                var pack = contextBuilder.buildPack()
                var turnComplete = false
                var loops = 0
                val executedCalls = mutableSetOf<String>()

                while (!turnComplete && loops < 4) {
                    loops++
                    val payload = buildJsonPayload(pack)
                    val target = Target(_state.value.providerEndpoint, _state.value.providerKey, 50)
                    
                    val botId = "a_${UUID.randomUUID()}"
                    val botResponse = java.lang.StringBuilder()
                    var lastUpdate = 0L

                    _state.update { it.copy(chatMessages = it.chatMessages + ChatMessage(botId, "assistant", "")) }

                    orchestrator.chat(listOf(target), payload).collect { event ->
                        if (event is StreamEvent.Clear) {
                            botResponse.clear()
                        } else if (event is StreamEvent.TextDelta) {
                            botResponse.append(event.text)
                            if (clock.now() - lastUpdate > 100) {
                                // B7: Incremental streaming of JSON 'reply' field natively
                                val currentText = botResponse.toString()
                                val display = extractReplyFromJsonStream(currentText)
                                updateLastMessage(botId, display)
                                lastUpdate = clock.now()
                            }
                        }
                    }

                    val rawBot = botResponse.toString()
                    val envelope = EnvelopeParser.parse(rawBot)
                    
                    if (envelope != null) {
                        var sysContext = ""
                        var hasCommitted = false
                        val turnReceipts = mutableListOf<ReceiptState>()
                        val turnErrors = mutableListOf<String>()

                        envelope.calls.forEach { call ->
                            val result = gateway.executeTool(call, executedCalls)
                            sysContext += "Tool Result: " + when(result) {
                                is ToolResult.Committed -> {
                                    hasCommitted = true
                                    turnReceipts.add(ReceiptState(UUID.randomUUID().toString(), result.message, result.undoToken, isError = false))
                                    "Committed. ${result.message}"
                                }
                                is ToolResult.Ambiguous -> {
                                    turnComplete = true
                                    "Ambiguous. UI will ask user. ID: ${result.proposalId}"
                                }
                                is ToolResult.NotFound -> {
                                    turnReceipts.add(ReceiptState(UUID.randomUUID().toString(), "Not found: ${result.hint}", null, isError = true))
                                    "Not Found."
                                }
                                is ToolResult.Data -> "Search Results:\n${result.text}"
                                is ToolResult.Error -> {
                                    turnErrors.add(result.reason)
                                    turnReceipts.add(ReceiptState(UUID.randomUUID().toString(), result.reason, null, isError = true))
                                    "Error: ${result.reason}"
                                }
                            } + "\n"
                        }

                        _state.update { it.copy(receipts = it.receipts + turnReceipts) }

                        // Stop storing model "Done!" prose when receipts already show the change
                        val rawReply = envelope.reply.trim()
                        val isGenericDone = rawReply.lowercase().removeSuffix(".").removeSuffix("!") in setOf("done", "saved", "added", "acknowledged", "ok")
                        val baseText = if (isGenericDone && hasCommitted && turnReceipts.isNotEmpty() && turnErrors.isEmpty()) {
                            "" // Suppress generic "Done!" when commit receipts exist
                        } else {
                            rawReply
                        }

                        val finalAssistantText = if (turnErrors.isNotEmpty()) {
                            if (baseText.isNotBlank()) "$baseText\n\n(Error: ${turnErrors.joinToString("; ")})"
                            else "Error: ${turnErrors.joinToString("; ")}"
                        } else baseText

                        // Avoid inserting empty placeholder messages if we purely generated receipts
                        if (finalAssistantText.isNotBlank()) {
                            updateLastMessage(botId, finalAssistantText)
                            storage.insertRawMessage(botId, "assistant", finalAssistantText)
                            pack = pack + ChatMessage(botId, "assistant", finalAssistantText)
                        } else {
                            _state.update { it.copy(chatMessages = it.chatMessages.dropLast(1)) }
                        }
                        
                        if (sysContext.isNotBlank()) {
                            pack = pack + ChatMessage("sys_${UUID.randomUUID()}", "user", "System feedback: $sysContext")
                        }
                        
                        if (envelope.calls.isEmpty() || hasCommitted || turnErrors.isNotEmpty()) {
                            turnComplete = true
                        }

                        // SEC 6: Use unique ID to prevent PK violations when loop limit is hit without completing
                        if (!turnComplete && loops >= 4) {
                            val limitMsg = "Loop limit reached."
                            val limitId = "a_${UUID.randomUUID()}"
                            _state.update { it.copy(chatMessages = it.chatMessages + ChatMessage(limitId, "assistant", limitMsg)) }
                            storage.insertRawMessage(limitId, "assistant", limitMsg)
                            turnComplete = true
                        }
                    } else {
                        val finalTxt = rawBot.ifBlank { "..." }
                        updateLastMessage(botId, finalTxt)
                        storage.insertRawMessage(botId, "assistant", finalTxt)
                        turnComplete = true
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) {
                    // A9: Must use NonCancellable to persist partial message while coroutine is tearing down
                    withContext(NonCancellable) {
                        val msgs = _state.value.chatMessages
                        val last = msgs.lastOrNull()
                        if (last?.role == "assistant" && !last.content.isNullOrBlank()) {
                            val intContent = last.content + " [Interrupted]"
                            updateLastMessage(last.id, intContent)
                            storage.insertRawMessage(last.id, "assistant", intContent)
                        }
                    }
                    throw e
                }
                // A10: Provider error refreshes UI properly below in finally
                _state.update { it.copy(connectionError = "Error: ${e.message}") }
                val msgs = _state.value.chatMessages
                if (msgs.lastOrNull()?.role == "assistant") {
                    _state.update { it.copy(chatMessages = msgs.dropLast(1)) } // Remove "Thinking..."
                }
            } finally {
                _state.update { it.copy(isChatLoading = false) }
                refreshUI()
            }
        }
    }

    private fun buildJsonPayload(pack: List<ChatMessage>): String {
        // ISSUE 3: Removed schedule_reminder from prompt to prevent double-alarm creation.
        val instruction = """You are MineAI, a personal companion. You must use Envelope Mode JSON for EVERY reply: {"reply":"text to user","calls":[{"tool":"...","args":{...}}]}
Tools:
- propose_change (intent: CREATE|CANCEL|COMPLETE|RESCHEDULE, hint: target text, due_text: copy the user's own words for the time exactly (e.g. "in 10 seconds", "in 2 mins", "tomorrow at 4pm"). Never compute a clock time or date yourself, and never reuse the "Now:" format. Omit if no time. untitled: optional "true", label: optional "alarm"|"reminder"|"notification"). MUST use when user states an appointment, plan, task, commitment.
- confirm_change (proposal: proposal id, choice: option id). Use when Ambiguous.
- undo (token: u_123456).
- search_memory (query: string, limit: int).
- soul_propose (statement: string, category: string). Use to record facts or preferences. Do NOT use sensitive categories (health, medical, legal) unless the user explicitly commands it.

Rules:
- If the user asks to be reminded, cued, notified or alarmed with a time but gives no subject, make no tool call. Reply with one short question ("For what?") and calls: []. If they say there's no reason ("just an alarm", "no particular reason"), call propose_change CREATE with untitled=true and label set to the word they used (alarm, reminder or notification). Send no title.

Examples:
CREATE: {"tool":"propose_change","args":{"intent":"CREATE", "title":"Buy milk", "due_text": "tomorrow at 4pm"}}
RESCHEDULE: {"tool":"propose_change","args":{"intent":"RESCHEDULE", "hint":"dinner", "due_text":"6pm"}}
UNTITLED: {"tool":"propose_change","args":{"intent":"CREATE", "untitled":"true", "label":"alarm", "due_text":"in 2 min 32 seconds"}}
UNDO: {"tool":"undo","args":{"token":"u_123456"}}

CRITICAL: Do NOT say "Done" or "Saved" unless you issue a tool call.
"""
        val messages = mutableListOf<com.anchor.core.provider.RequestMessage>()
        messages.add(com.anchor.core.provider.RequestMessage("system", instruction))
        pack.filter { it.content != null }.forEach { messages.add(com.anchor.core.provider.RequestMessage(it.role, it.content!!)) }
        return kotlinx.serialization.json.Json.encodeToString(com.anchor.core.provider.ChatRequest(_state.value.providerModel, true, messages))
    }

    private fun updateLastMessage(id: String, content: String) {
        // B9: Use the thread-safe StateFlow.update {} transaction block to prevent 
        // concurrent typing inputs from being overwritten by incoming LLM network chunks.
        _state.update { curr ->
            val msgs = curr.chatMessages.toMutableList()
            val idx = msgs.indexOfLast { it.id == id }
            if (idx != -1) msgs[idx] = ChatMessage(id, "assistant", content)
            curr.copy(chatMessages = msgs)
        }
    }

    private suspend fun reconcileAlarms() {
        val now = clock.now()
        val pending = storage.getPendingReminders()
        val registered = storage.getRegisteredAlarms()

        val pendingMap = pending.associate { it.id to it.fireAtMillis }
        val registeredMap = registered.associate { it.first to it.second }

        val toCancel = registeredMap.keys - pendingMap.keys
        toCancel.forEach { id ->
            alarmScheduler.cancel(id)
            storage.deleteRegisteredAlarm(id)
        }

        // Treat registry as a hint, not ground truth: force-stop clears OS alarms.
        // Re-arm all active pending reminders (future or within 60s grace).
        val toSchedule = pending.filter { it.fireAtMillis >= now - 60000L }
        toSchedule.forEach { r ->
            alarmScheduler.schedule(r)
            storage.saveRegisteredAlarm(r.id, r.id.hashCode(), r.fireAtMillis)
        }
    }

    private suspend fun refreshUI() {
        reconcileAlarms()
        val currentSize = _state.value.chatMessages.size
        val fetchCount = kotlin.math.max(30, currentSize)
        
        val proposals = storage.getOpenProposals()
        val soulFacts = storage.getProposedSoulFacts()
        
        _state.update { it.copy(
            chatMessages = storage.getRecentMessages(fetchCount), 
            activeReminders = storage.getActiveReminders(),
            openProposals = proposals,
            proposedSoulFacts = soulFacts,
            inboxCount = proposals.size + soulFacts.size,
            urgentItems = storage.getUrgentNodes()
        ) }
    }

    private fun extractReplyFromJsonStream(jsonStream: String): String {
        val replyMarker = "\"reply\":\""
        val start = jsonStream.indexOf(replyMarker)
        if (start == -1) {
            return if (jsonStream.trim().startsWith("{")) "Thinking..." else jsonStream
        }
        
        val textStart = start + replyMarker.length
        var end = jsonStream.indexOf("\"", textStart)
        while (end != -1 && jsonStream[end - 1] == '\\') {
            end = jsonStream.indexOf("\"", end + 1)
        }
        
        if (end == -1) {
            return jsonStream.substring(textStart).replace("\\\"", "\"").replace("\\n", "\n")
        }
        return jsonStream.substring(textStart, end).replace("\\\"", "\"").replace("\\n", "\n")
    }
}
