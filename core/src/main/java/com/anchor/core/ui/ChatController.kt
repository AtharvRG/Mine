// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.ui

import com.anchor.core.alarms.AlarmScheduler
import com.anchor.core.context.ContextBuilder
import com.anchor.core.gateway.EnvelopeParser
import com.anchor.core.gateway.Gateway
import com.anchor.core.models.ChatMessage
import com.anchor.core.models.Reminder
import com.anchor.core.models.ToolResult
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

    init {
        coroutineScope.launch(defaultDispatcher) {
            _state.update { it.copy(
                activeReminders = storage.getActiveReminders(),
                chatMessages = storage.getRecentMessages(30),
                providerEndpoint = storage.getSetting("ep") ?: "https://api.openai.com/v1/chat/completions",
                providerKey = storage.getSetting("key") ?: "",
                providerModel = storage.getSetting("mod") ?: "gpt-4o-mini",
                debugStats = storage.getDebugStats().entries.joinToString("\n") { e -> "${e.key}: ${e.value}" }
            )}
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
                currentStreamJob?.cancel()
                _state.update { it.copy(isChatLoading = false) }
            }
            is UiAction.SaveProvider -> coroutineScope.launch(defaultDispatcher) {
                storage.saveSetting("ep", action.endpoint)
                storage.saveSetting("key", action.key)
                storage.saveSetting("mod", action.model)
                _state.update { it.copy(providerEndpoint = action.endpoint, providerKey = action.key, providerModel = action.model) }
            }
            is UiAction.CancelReminder -> coroutineScope.launch(defaultDispatcher) {
                storage.deleteReminder(action.id)
                alarmScheduler.cancel(action.id)
                _state.update { it.copy(activeReminders = storage.getActiveReminders()) }
            }
            is UiAction.RunSelfTest -> coroutineScope.launch(defaultDispatcher) {
                _state.update { it.copy(selfTestStatus = "Armed for 10 seconds...") }
                val id = "test_${System.currentTimeMillis()}"
                val fireAt = clock.now() + 10000L
                val reminder = Reminder(id, "Self Test Fired!", fireAt)
                storage.saveReminder(reminder)
                alarmScheduler.schedule(reminder)
            }
            is UiAction.RefreshStats -> coroutineScope.launch(defaultDispatcher) {
                _state.update { it.copy(debugStats = storage.getDebugStats().entries.joinToString("\n") { e -> "${e.key}: ${e.value}" }) }
            }
            is UiAction.ScheduleIn -> {}
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
                    var lastUpdate = clock.now()

                    _state.update { it.copy(chatMessages = it.chatMessages + ChatMessage(botId, "assistant", "")) }

                    orchestrator.chat(listOf(target), payload).collect { event ->
                        if (event is StreamEvent.Clear) {
                            botResponse.clear()
                        } else if (event is StreamEvent.TextDelta) {
                            botResponse.append(event.text)
                            if (clock.now() - lastUpdate > 100) {
                                val trimmed = botResponse.toString().trimStart()
                                val display = if (trimmed.startsWith("{") || trimmed.startsWith("```") || trimmed.startsWith("[")) "Thinking..." else botResponse.toString()
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

                        envelope.calls.forEach { call ->
                            val result = gateway.executeTool(call, executedCalls)
                            sysContext += "Tool Result: " + when(result) {
                                is ToolResult.Committed -> {
                                    hasCommitted = true
                                    "Committed. ${result.message}"
                                }
                                is ToolResult.Ambiguous -> {
                                    // A6: End turn on Ambiguous so UI handles choice selection via Chips
                                    turnComplete = true
                                    "Ambiguous. UI will ask user. ID: ${result.proposalId}"
                                }
                                is ToolResult.NotFound -> "Not Found."
                                is ToolResult.Data -> "Search Results:\n${result.text}"
                                is ToolResult.Error -> "Error: ${result.reason}"
                            } + "\n"
                        }
                        
                        // A15: Store exactly what the model replied, do not pollute it with [Done...] UI hacks.
                        // The React UI will render the receipts natively from the operation log.
                        val cleanText = envelope.reply.trim().ifBlank { "Acknowledged." }
                        updateLastMessage(botId, cleanText)
                        storage.insertRawMessage(botId, "assistant", cleanText)
                        
                        pack = pack + ChatMessage(botId, "assistant", cleanText)
                        pack = pack + ChatMessage("sys_${UUID.randomUUID()}", "user", sysContext)
                        
                        // A11: Loop limit logic fixed
                        if (!turnComplete && loops == 4) {
                            val limitMsg = "Loop limit reached."
                            updateLastMessage(botId, limitMsg)
                            storage.insertRawMessage(botId, "assistant", limitMsg)
                            turnComplete = true
                        }
                        
                        if (envelope.calls.isEmpty() || hasCommitted) {
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
        // A3 & A4: Strict prompt restored with schedule_reminder examples
        val instruction = """You are MineAI, a personal companion. You must use Envelope Mode JSON for EVERY reply: {"reply":"text to user","calls":[{"tool":"...","args":{...}}]}
Tools:
- propose_change (intent: CREATE|CANCEL|COMPLETE, hint: target text, due_text: e.g. 'tomorrow at 4pm', 'tonight', 'next monday' or omit if no time). MUST use when user states an appointment, plan, task, commitment.
- schedule_reminder (target_ref: node id, when_text: time). Use to set alarms.
- confirm_change (proposal: proposal id, choice: option id). Use when Ambiguous.
- undo (token: u_123456).
- search_memory (query: string, limit: int).
- soul_propose (statement: string, category: string). Use when you notice facts, constraints, or preferences about the user.

Examples:
CREATE: {"tool":"propose_change","args":{"intent":"CREATE", "title":"Buy milk", "due_text": "tomorrow at 4pm"}}
SCHEDULE ALARM: {"tool":"schedule_reminder","args":{"target_ref":"n_abc", "when_text": "tomorrow at 4pm"}}
CANCEL: {"tool":"propose_change","args":{"intent":"CANCEL", "hint":"dinner"}}
UNDO: {"tool":"undo","args":{"token":"u_123456"}}
SOUL: {"tool":"soul_propose","args":{"statement":"Is diabetic","category":"health"}}

CRITICAL: Do NOT say "Done" or "Saved" unless you issue a tool call.
"""
        val messages = mutableListOf<com.anchor.core.provider.RequestMessage>()
        messages.add(com.anchor.core.provider.RequestMessage("system", instruction))
        pack.filter { it.content != null }.forEach { messages.add(com.anchor.core.provider.RequestMessage(it.role, it.content!!)) }
        return kotlinx.serialization.json.Json.encodeToString(com.anchor.core.provider.ChatRequest(_state.value.providerModel, true, messages))
    }

    private fun updateLastMessage(id: String, content: String) {
        val msgs = _state.value.chatMessages.toMutableList()
        val idx = msgs.indexOfLast { it.id == id }
        if (idx != -1) msgs[idx] = ChatMessage(id, "assistant", content)
        _state.value = _state.value.copy(chatMessages = msgs)
    }

    private suspend fun refreshUI() {
        storage.getActiveReminders().forEach { alarmScheduler.schedule(it) }
        _state.update { it.copy(chatMessages = storage.getRecentMessages(30), activeReminders = storage.getActiveReminders()) }
    }
}
