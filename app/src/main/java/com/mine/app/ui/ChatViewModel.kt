package com.mine.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mine.app.BuildConfig
import com.mine.app.ai.ChatAgent
import com.mine.app.data.local.db.dao.ChatDao
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val chatAgent: ChatAgent,
    chatDao: ChatDao
) : ViewModel() {

    // Automatically streams the chat history from SQLite to the UI
    val chatMessages = chatDao.getRecentMessagesFlow(com.mine.app.util.TimeUtils.getStartOfDayTimestamp(), 50)
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun sendMessage(message: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val apiKey = BuildConfig.NVIDIA_API_KEY_CHAT
                chatAgent.sendMessage(apiKey, message)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
