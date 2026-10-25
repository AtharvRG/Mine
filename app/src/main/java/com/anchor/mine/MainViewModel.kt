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
import com.anchor.mine.alarms.AndroidAlarmScheduler
import com.anchor.mine.alarms.AlarmReceiver
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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

    init {
        // Dummy welcome message
        _state.update {
            it.copy(chatMessages = listOf(ChatMessage("sys1", "assistant", "Hello. Set up provider, then chat.")))
        }
    }

    // --- CHAT ACTIONS ---
    fun sendMessage(text: String) {
        val userMsg = ChatMessage("u_${System.currentTimeMillis()}", "user", text)
        _state.update { it.copy(
            chatMessages = it.chatMessages + userMsg,
            isChatLoading = true
        ) }

        viewModelScope.launch {
            // Note: We bypass the real provider for Phase B's early test,
            // just echoing back to prove UI wiring works before full HTTP integration.
            delay(1000)
            val botMsg = ChatMessage("a_${System.currentTimeMillis()}", "assistant", "Echo: $text")

            _state.update { it.copy(
                chatMessages = it.chatMessages + botMsg,
                isChatLoading = false
            ) }
        }
    }

    // --- REMINDER ACTIONS ---
    fun scheduleReminder(minutes: Int, text: String) {
        val id = "r_${System.currentTimeMillis()}"
        val fireAt = System.currentTimeMillis() + (minutes * 60 * 1000L)
        val reminder = Reminder(id, text, fireAt)

        alarmScheduler.schedule(reminder)

        _state.update { it.copy(
            activeReminders = it.activeReminders + reminder
        ) }
    }

    fun runAlarmSelfTest() {
        _state.update { it.copy(selfTestStatus = "Armed for 5 seconds...") }
        val id = "test_${System.currentTimeMillis()}"

        AlarmReceiver.lastFiredId = null
        alarmScheduler.schedule(Reminder(id, "Self Test Fired!", System.currentTimeMillis() + 5000L))

        viewModelScope.launch {
            var elapsed = 0
            while (AlarmReceiver.lastFiredId != id && elapsed < 10000) {
                delay(500)
                elapsed += 500
            }
            if (AlarmReceiver.lastFiredId == id) {
                _state.update { it.copy(selfTestStatus = "Fired Successfully!") }
            } else {
                _state.update { it.copy(selfTestStatus = "Failed/Timeout") }
            }
        }
    }

    // --- PROVIDER ACTIONS ---
    fun saveProvider(endpoint: String, key: String, model: String) {
        _state.update { it.copy(
            providerEndpoint = endpoint,
            providerKey = key,
            providerModel = model
        ) }
    }
}