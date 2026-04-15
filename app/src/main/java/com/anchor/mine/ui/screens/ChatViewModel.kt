package com.anchor.mine.ui.screens

import android.app.Application
import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.anchor.mine.brain.ChatMessageEntity
import com.anchor.mine.brain.ChatSession
import com.anchor.mine.brain.MineDatabase
import com.anchor.mine.network.NimClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ChatMessage(
    val text: String,
    val isUser: Boolean,
    val isThinking: Boolean = false,
    val thinkingProcess: String? = null
)

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    val messages = mutableStateListOf<ChatMessage>()
    private val nimClient = NimClient(application)
    private val db = MineDatabase.getDatabase(application)

    private var hasHandledInitialMessage = false
    private var currentSessionId: Int? = null

    fun handleInitialMessage(message: String, useThinkingModel: Boolean) {
        if (message.isNotBlank() && !hasHandledInitialMessage) {
            hasHandledInitialMessage = true
            sendMessage(message, useThinkingModel)
        }
    }

    fun loadChatHistory(sessionId: Int) {
        // Don't reload if we are already in this session
        if (currentSessionId == sessionId) return

        currentSessionId = sessionId
        messages.clear() // Clear the default greeting

        viewModelScope.launch(Dispatchers.IO) {
            val history = db.memoryDao().getMessagesForSession(sessionId)

            val chatMessages = history.map { entity ->
                ChatMessage(
                    text = entity.text,
                    isUser = entity.isUser,
                    isThinking = false, // Old messages are already done thinking
                    thinkingProcess = entity.thinkingProcess
                )
            }

            withContext(Dispatchers.Main) {
                messages.addAll(chatMessages)
            }
        }
    }

    fun sendMessage(userText: String, useThinkingModel: Boolean) {
        if (userText.isBlank()) return

        Log.d("ChatEngine", "1. User message received: $userText")
        messages.add(ChatMessage(userText, isUser = true))

        val aiMessageIndex = messages.size
        messages.add(ChatMessage("", isUser = false, isThinking = true, thinkingProcess = if (useThinkingModel) "" else null))

        viewModelScope.launch(Dispatchers.IO) {

            // --- DATABASE SETUP ---
            if (currentSessionId == null) {
                val newSessionId = db.memoryDao().createChatSession(ChatSession(title = "New Chat"))
                currentSessionId = newSessionId.toInt()
                launch {
                    val generatedTitle = nimClient.generateChatTitle(userText)
                    db.memoryDao().updateChatTitle(newSessionId.toInt(), generatedTitle)
                    Log.d("ChatEngine", "2. Auto-Titled Chat: $generatedTitle")
                }
            }

            // 3. DATABASE: Save the User's message WITH its Vector Embedding!
            val userEmbedding = nimClient.getEmbedding(userText, "passage")
            val userMsgEntity = ChatMessageEntity(
                sessionId = currentSessionId!!,
                text = userText,
                isUser = true,
                vectorEmbedding = if (userEmbedding != "[]") userEmbedding else null
            )
            db.memoryDao().insertChatMessage(userMsgEntity)

            // --- VECTOR SEARCH ---
            Log.d("ChatEngine", "3. Starting Vector Search...")
            val questionEmbeddingJson = nimClient.getEmbedding(userText, "query")
            val topMemories = if (questionEmbeddingJson != "[]") {
                val questionVector = com.anchor.mine.brain.VectorMath.parseVector(questionEmbeddingJson)
                val allVectors = db.memoryDao().getAllVectors()

                allVectors.map { node ->
                    val nodeVector = com.anchor.mine.brain.VectorMath.parseVector(node.vectorEmbedding)
                    val score = com.anchor.mine.brain.VectorMath.cosineSimilarity(questionVector, nodeVector)
                    Pair(score, node.textContent)
                }.sortedByDescending { it.first }.take(3).map { it.second }
            } else emptyList()
            Log.d("ChatEngine", "4. Vector Search Complete. Found ${topMemories.size} relevant memories.")

            // --- STREAMING ---
            var currentText = ""
            var currentThinking = if (useThinkingModel) "" else null

            Log.d("ChatEngine", "5. Opening Stream to Nvidia NIM...")
            nimClient.streamChatBot(userText, topMemories, useThinkingModel).collect { update ->
                if (update.isFinished) {
                    Log.d("ChatEngine", "7. Stream Finished!")
                    withContext(Dispatchers.Main) {
                        // Force recomposition using .copy()
                        messages[aiMessageIndex] = messages[aiMessageIndex].copy(isThinking = false)
                    }

                    db.memoryDao().insertChatMessage(ChatMessageEntity(
                        sessionId = currentSessionId!!, text = currentText, isUser = false, thinkingProcess = currentThinking
                    ))
                } else {
                    currentText += update.contentDelta
                    if (useThinkingModel && update.thinkingDelta.isNotEmpty()) {
                        currentThinking = (currentThinking ?: "") + update.thinkingDelta
                    }

                    withContext(Dispatchers.Main) {
                        // Force recomposition using .copy()
                        messages[aiMessageIndex] = messages[aiMessageIndex].copy(
                            text = currentText,
                            thinkingProcess = currentThinking
                        )
                    }
                }
            }
        }
    }
}