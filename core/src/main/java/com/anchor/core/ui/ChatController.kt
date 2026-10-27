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
            is UiAction.SendMessage -> {
                if (_state.value.isChatLoading || _state.value.providerKey.isBlank()) return
                // Synchronously set loading state to prevent double-send race conditions
                _state.update { it.copy(isChatLoading = true) }
                handleSendMessage(action.text)
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

                var elapsed = 0
                while (alarmScheduler.lastFiredId.value != id && elapsed < 12000) {
                    delay(500)
                    elapsed += 500
                }
                _state.update { it.copy(selfTestStatus = if (alarmScheduler.lastFiredId.value == id) "Fired in ${elapsed/1000}s" else "Failed") }
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
                                // Strict JSON flashing prevention: hide entirely while streaming raw JSON
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
                        var humanResult = envelope.reply
                        var hasCommitted = false

                        envelope.calls.forEach { call ->
                            val result = gateway.executeTool(call, executedCalls)
                            sysContext += "Tool Result: " + when(result) {
                                is ToolResult.Committed -> {
                                    hasCommitted = true
                                    storage.getActiveReminders().forEach { alarmScheduler.schedule(it) }
                                    humanResult += "\n[Done: ${result.message}] (Undo: ${result.undoToken})"
                                    "Committed. ${result.message}"
                                }
                                is ToolResult.Ambiguous -> {
                                    humanResult += "\n[Clarify: ${result.message}]"
                                    "Ambiguous. Ask the user, then call confirm_change. ID: ${result.proposalId}, Options: ${result.options.joinToString()}"
                                }
                                is ToolResult.NotFound -> "Not Found. Ask user to clarify."
                                is ToolResult.Error -> "Error: ${result.reason}"
                            } + "\n"
                        }

                        val cleanText = if (envelope.reply.isBlank() && envelope.calls.isEmpty()) {
                            "Acknowledged."
                        } else {
                            humanResult.trim().ifBlank { "Executed tools silently." }
                        }

                        updateLastMessage(botId, cleanText)
                        storage.insertRawMessage(botId, "assistant", cleanText)

                        pack = pack + ChatMessage(botId, "assistant", cleanText)
                        pack = pack + ChatMessage("sys_${UUID.randomUUID()}", "system", sysContext)

                        if (loops == 4) {
                            val limitMsg = "Loop limit reached."
                            updateLastMessage("a_${UUID.randomUUID()}", limitMsg)
                            storage.insertRawMessage("a_${UUID.randomUUID()}", "assistant", limitMsg)
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
                refreshUI()
            } catch (e: Exception) {
                if (e is CancellationException) {
                    val msgs = _state.value.chatMessages
                    val last = msgs.lastOrNull()
                    if (last?.role == "assistant") {
                        if (last.content.isNullOrBlank()) _state.update { it.copy(chatMessages = msgs.dropLast(1)) }
                        else {
                            val intContent = last.content + " [Interrupted]"
                            updateLastMessage(last.id, intContent)
                            storage.insertRawMessage(last.id, "assistant", intContent)
                        }
                    }
                    throw e // MUST rethrow CancellationException
                }
                _state.update { it.copy(chatMessages = it.chatMessages + ChatMessage("err_${UUID.randomUUID()}", "system", "Error: ${e.message}")) }
            } finally {
                _state.update { it.copy(isChatLoading = false) }
            }
        }
    }

    private fun buildJsonPayload(pack: List<ChatMessage>): String {
        val instruction = """You are MineAI, a personal companion. You must use Envelope Mode JSON for EVERY reply: {"reply":"text to user","calls":[{"tool":"...","args":{...}}]}
Tools:
- propose_change (intent: CREATE|CANCEL|COMPLETE, hint: target text, due_text: e.g. 'tomorrow at 4pm', 'tonight', 'next monday' or omit if no time). Use when user states an appointment, plan, task, commitment.
- schedule_reminder (target_ref: node id, when_text: time).
- confirm_change (proposal: proposal id, choice: option id). Use when Ambiguous.
- undo (token: u_123456).

Examples:
CREATE: {"tool":"propose_change","args":{"intent":"CREATE", "title":"Buy milk", "due_text": "tomorrow at 4pm"}}
CANCEL: {"tool":"propose_change","args":{"intent":"CANCEL", "hint":"dinner"}}
COMPLETE: {"tool":"propose_change","args":{"intent":"COMPLETE", "hint":"report"}}
UNDO: {"tool":"undo","args":{"token":"u_123456"}}
CONFIRM: {"tool":"confirm_change","args":{"proposal":"p_123456","choice":"n_abc"}}

CRITICAL: The app generates save confirmations. Do NOT say "Done" or "Saved" unless you issue a tool call.
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
        _state.update { it.copy(chatMessages = storage.getRecentMessages(30), activeReminders = storage.getActiveReminders()) }
    }
}