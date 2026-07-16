package com.mine.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mine.app.BuildConfig
import com.mine.app.ai.ChatAgent
import com.mine.app.data.local.db.dao.ChatDao
import com.mine.app.util.TimeUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val chatAgent: ChatAgent,
    private val chatDao: ChatDao
) : ViewModel() {

    // Reactive state for the current day
    private val currentDay = MutableStateFlow(TimeUtils.getStartOfDayTimestamp())

    // Automatically updates if the day changes
    @OptIn(ExperimentalCoroutinesApi::class)
    val chatMessages = currentDay.flatMapLatest { day ->
        chatDao.getRecentMessagesFlow(day, 50)
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun sendMessage(message: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Refresh the day timestamp just in case midnight passed while the app was open!
                currentDay.value = TimeUtils.getStartOfDayTimestamp()

                val apiKey = BuildConfig.NVIDIA_API_KEY_CHAT
                chatAgent.sendMessage(apiKey, message)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
