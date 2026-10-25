// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine Author
// Part of Mine. See LICENSE in the repository root.

package com.anchor.mine

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.anchor.core.alarms.Reminder
import com.anchor.core.context.ContextBuilder
import com.anchor.core.models.ChatMessage
import com.anchor.core.provider.*
import com.anchor.mine.alarms.AndroidAlarmScheduler
import com.anchor.mine.alarms.AlarmReceiver
import com.anchor.mine.provider.AndroidHttpTransport
import com.anchor.mine.storage.AndroidStorage
import com.anchor.mine.storage.DatabaseProvider
import com.anchor.mine.storage.DbKeyProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

data class AppState(
    val chatMessages: List<ChatMessage> = emptyList(),
    val isChatLoading: Boolean = false,
    val activeReminders: List<Reminder> = emptyList(),
    val selfTestStatus: String = "Idle",
    val providerEndpoint: String = "https://api.openai.com/v1/chat/completions",
    val providerKey: String = "",
    val providerModel: String = "gpt-4o-mini",
    val debugStats: String = "Rows: 0"
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = _state.asStateFlow()

    private val alarmScheduler = AndroidAlarmScheduler(application)
    private val storage: AndroidStorage
    private val contextBuilder: ContextBuilder
    private val orchestrator: ProviderOrchestrator

    init {
        val key = DbKeyProvider(application).getOrGenerateKey()
        val dbHelper = DatabaseProvider.getHelper(application, key)
        storage = AndroidStorage(dbHelper)
        contextBuilder = ContextBuilder(storage)

        val rateLimiter = RateLimiter { System.currentTimeMillis() }
        orchestrator = ProviderOrchestrator(rateLimiter, AndroidHttpTransport())

        viewModelScope.launch {
            val reminders = storage.getActiveReminders()
            val history = storage.getRecentMessages(30)
            _state.update { it.copy(activeReminders = reminders, chatMessages = history) }
        }
    }

    fun sendMessage(text: String) {
        if (state.value.providerKey.isBlank()) {
            _state.update { it.copy(chatMessages = it.chatMessages + ChatMessage("sys", "system", "Error: Enter API Key in Provider tab first.")) }
            return
        }

        val userMsgId = "u_${System.currentTimeMillis()}"
        _state.update { it.copy(chatMessages = it.chatMessages + ChatMessage(userMsgId, "user", text), isChatLoading = true) }

        viewModelScope.launch {
            try {
                storage.insertRawMessage(userMsgId, text)

                val pack = contextBuilder.buildPack(text)

                // Formulate the JSON payload
                val messagesArray = JSONArray()

                // Inject our strict instruction to intercept Reminders (Blueprint 23 Envelope Mode)
                val systemInstruction = """
                    You are MineAI. If the user asks you to remind them about something, YOU MUST reply with exactly this JSON and nothing else:
                    {"tool": "reminder", "text": "Task description", "minutes": 2}
                    If they just want to chat, reply normally.
                """.trimIndent()

                messagesArray.put(JSONObject().put("role", "system").put("content", systemInstruction))

                pack.forEach { msg ->
                    if (msg.role != "system") { // Skip builder's system msg for this test
                        messagesArray.put(JSONObject().put("role", msg.role).put("content", msg.content))
                    }
                }

                val payload = JSONObject().apply {
                    put("model", state.value.providerModel)
                    put("messages", messagesArray)
                    put("stream", true)
                }.toString()

                val target = Target(id = state.value.providerEndpoint, key = state.value.providerKey, limitRpm = 50)

                var botResponse = ""
                orchestrator.chat(listOf(target), payload).collect { event ->
                    if (event is StreamEvent.TextDelta) {
                        botResponse += event.text
                    }
                }

                // Clean markdown block ticks if the LLM added them
                val cleanResponse = botResponse.replace("```json", "").replace("```", "").trim()

                // Check if the LLM outputted our Tool Envelope
                if (cleanResponse.contains("\"tool\"") && cleanResponse.contains("reminder")) {
                    try {
                        val json = JSONObject(cleanResponse)
                        val rText = json.getString("text")
                        val rMins = json.getInt("minutes")
                        scheduleReminder(rMins, rText)
                        botResponse = "Got it! I will remind you about '$rText' in $rMins minutes."
                    } catch (e: Exception) {
                        botResponse = "Failed to parse tool: $cleanResponse"
                    }
                }

                val botId = "a_${System.currentTimeMillis()}"
                storage.insertRawMessage(botId, botResponse)

                _state.update { it.copy(
                    chatMessages = it.chatMessages + ChatMessage(botId, "assistant", botResponse),
                    isChatLoading = false
                ) }

            } catch (e: Exception) {
                _state.update { it.copy(
                    chatMessages = it.chatMessages + ChatMessage("err", "system", "Failed: ${e.message}"),
                    isChatLoading = false
                ) }
            }
        }
    }

    fun scheduleReminder(minutes: Int, text: String) {
        val id = "r_${System.currentTimeMillis()}"
        val fireAt = System.currentTimeMillis() + (minutes * 60 * 1000L)
        val reminder = Reminder(id, text, fireAt)

        alarmScheduler.schedule(reminder)

        viewModelScope.launch {
            storage.saveReminder(reminder)
            _state.update { it.copy(activeReminders = storage.getActiveReminders()) }
        }
    }

    fun runAlarmSelfTest() {
        // [Existing Self Test Code Remains Unchanged]
        _state.update { it.copy(selfTestStatus = "Armed for 5 seconds...") }
        val id = "test_${System.currentTimeMillis()}"
        AlarmReceiver.lastFiredId = null
        alarmScheduler.schedule(Reminder(id, "Self Test Fired!", System.currentTimeMillis() + 5000L))
        viewModelScope.launch {
            var elapsed = 0
            while (AlarmReceiver.lastFiredId != id && elapsed < 10000) { delay(500); elapsed += 500 }
            _state.update { it.copy(selfTestStatus = if (AlarmReceiver.lastFiredId == id) "Fired Successfully!" else "Failed") }
        }
    }

    fun saveProvider(endpoint: String, key: String, model: String) {
        _state.update { it.copy(providerEndpoint = endpoint, providerKey = key, providerModel = model) }
    }
}