// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.ui

import com.anchor.core.alarms.AlarmReceiver
import com.anchor.core.alarms.AlarmScheduler
import com.anchor.core.alarms.Reminder
import com.anchor.core.context.ContextBuilder
import com.anchor.core.gateway.EnvelopeParser
import com.anchor.core.gateway.Gateway
import com.anchor.core.models.ChatMessage
import com.anchor.core.models.ToolResult
import com.anchor.core.provider.ChatRequest
import com.anchor.core.provider.ProviderOrchestrator
import com.anchor.core.provider.RequestMessage
import com.anchor.core.provider.StreamEvent
import com.anchor.core.provider.Target
import com.anchor.core.storage.Storage
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class ChatController(
    private val storage: Storage,
    private val orchestrator: ProviderOrchestrator,
    private val contextBuilder: ContextBuilder,
    private val gateway: Gateway,
    private val alarmScheduler: AlarmScheduler,
    private val coroutineScope: CoroutineScope
) {
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var currentStreamJob: Job? = null

    init {
        coroutineScope.launch {
            val ep = storage.getSetting("ep") ?: "https://api.openai.com/v1/chat/completions"
            val key = storage.getSetting("key") ?: ""
            val mod = storage.getSetting("mod") ?: "gpt-4o-mini"
            _state.update { it.copy(
                activeReminders = storage.getActiveReminders(),
                chatMessages = storage.getRecentMessages(30),
                providerEndpoint = ep, providerKey = key, providerModel = mod
            )}
        }
    }

    fun onAction(action: UiAction) {
        when (action) {
            is UiAction.SendMessage -> handleSendMessage(action.text)
            is UiAction.Stop -> {
                currentStreamJob?.cancel()
                _state.update { it.copy(isChatLoading = false) }
            }
            is UiAction.SaveProvider -> {
                coroutineScope.launch {
                    storage.saveSetting("ep", action.endpoint)
                    storage.saveSetting("key", action.key)
                    storage.saveSetting("mod", action.model)
                    _state.update { it.copy(providerEndpoint = action.endpoint, providerKey = action.key, providerModel = action.model) }
                }
            }
            is UiAction.CancelReminder -> {
                coroutineScope.launch {
                    storage.deleteReminder(action.id)
                    alarmScheduler.cancel(action.id)
                    _state.update { it.copy(activeReminders = storage.getActiveReminders()) }
                }
            }
            is UiAction.RunSelfTest -> {
                _state.update { it.copy(selfTestStatus = "Armed for 5 seconds...") }
                val id = "test_${System.currentTimeMillis()}"
                AlarmReceiver.lastFiredId = null
                alarmScheduler.schedule(Reminder(id, "Self Test Fired!", System.currentTimeMillis() + 5000L))
                
                coroutineScope.launch {
                    var elapsed = 0
                    while (AlarmReceiver.lastFiredId != id && elapsed < 10000) {
                        kotlinx.coroutines.delay(500)
                        elapsed += 500
                    }
                    _state.update { it.copy(selfTestStatus = if (AlarmReceiver.lastFiredId == id) "Fired Successfully!" else "Failed") }
                }
            }
            // MISSING LOGIC ADDED BACK:
            is UiAction.ScheduleIn -> {
                val id = "r_${System.currentTimeMillis()}"
                val fireAt = System.currentTimeMillis() + (action.minutes * 60 * 1000L)
                val reminder = Reminder(id, action.text, fireAt)
                
                alarmScheduler.schedule(reminder)
                coroutineScope.launch {
                    storage.saveReminder(reminder)
                    _state.update { it.copy(activeReminders = storage.getActiveReminders()) }
                }
            }
        }
    }

    private fun handleSendMessage(text: String) {
        if (state.value.isChatLoading || state.value.providerKey.isBlank()) return

        currentStreamJob = coroutineScope.launch {
            val userMsgId = "u_${System.currentTimeMillis()}"
            storage.insertRawMessage(userMsgId, "user", text)
            _state.update { it.copy(chatMessages = storage.getRecentMessages(30), isChatLoading = true) }

            try {
                val pack = contextBuilder.buildPack()
                val payload = buildJsonPayload(pack)
                val target = Target(state.value.providerEndpoint, state.value.providerKey, 50)

                var rawResponse = ""
                var lastUpdate = System.currentTimeMillis()

                val botId = "a_${System.currentTimeMillis()}"
                _state.update { it.copy(chatMessages = it.chatMessages + ChatMessage(botId, "assistant", "")) }

                orchestrator.chat(listOf(target), payload).collect { event ->
                    if (event is StreamEvent.TextDelta) {
                        rawResponse += event.text
                        // Throttle UI updates to 100ms
                        if (System.currentTimeMillis() - lastUpdate > 100) {
                            updateLastMessage(botId, rawResponse)
                            lastUpdate = System.currentTimeMillis()
                        }
                    }
                }

                // Process Envelope Mode (Gateway)
                val envelope = EnvelopeParser.parse(rawResponse)
                val finalText = if (envelope != null) {
                    var out = envelope.reply
                    envelope.calls.forEach { call ->
                        val result = gateway.executeTool(call)
                        if (result is ToolResult.Committed) {
                            // If a reminder was created by D-100, schedule the real OS alarm
                            storage.getActiveReminders().forEach { alarmScheduler.schedule(it) }
                            out += "\n[Done: ${result.message}]"
                        } else if (result is ToolResult.Ambiguous) {
                            out += "\n[Clarify: ${result.message} -> ${result.options}]"
                        }
                    }
                    out
                } else rawResponse

                storage.insertRawMessage(botId, "assistant", finalText)
                _state.update { it.copy(chatMessages = storage.getRecentMessages(30), activeReminders = storage.getActiveReminders(), isChatLoading = false) }

            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _state.update { it.copy(isChatLoading = false, chatMessages = it.chatMessages + ChatMessage("err", "system", "Error: ${e.message}")) }
            }
        }
    }

    private fun buildJsonPayload(pack: List<ChatMessage>): String {
        // Tweak the instruction so the AI knows EXACTLY what text to use for the date
        val instruction = "You must use Envelope Mode JSON: {\"reply\":\"Got it!\",\"calls\":[{\"tool\":\"propose_change\",\"args\":{\"intent\":\"CREATE\",\"title\":\"Task name\",\"due_text\":\"in 2 minutes\"}}]}"
        
        val messages = mutableListOf<com.anchor.core.provider.RequestMessage>()
        messages.add(com.anchor.core.provider.RequestMessage("system", instruction))
        
        pack.forEach { 
            messages.add(com.anchor.core.provider.RequestMessage(it.role, it.content))
        }
        
        val request = com.anchor.core.provider.ChatRequest(
            model = state.value.providerModel,
            stream = true,
            messages = messages
        )
        
        return kotlinx.serialization.json.Json.encodeToString(request)
    }

    private fun updateLastMessage(id: String, content: String) {
        val msgs = state.value.chatMessages.toMutableList()
        val idx = msgs.indexOfLast { it.id == id }
        if (idx != -1) msgs[idx] = ChatMessage(id, "assistant", content)
        _state.value = state.value.copy(chatMessages = msgs)
    }
}