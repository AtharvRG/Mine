package com.anchor.mine.brain

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

// Represents a single conversation thread in the Nav Drawer
@Entity(tableName = "chat_sessions")
data class ChatSession(
    @PrimaryKey(autoGenerate = true)
    val sessionId: Int = 0,
    val title: String = "New Chat", // Will be auto-updated by the AI
    val timestamp: Long = System.currentTimeMillis()
)

// Represents a single bubble inside a chat session
@Entity(
    tableName = "chat_messages",
    foreignKeys = [
        ForeignKey(
            entity = ChatSession::class,
            parentColumns = ["sessionId"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE // If you delete the chat, delete its messages!
        )
    ]
)
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true)
    val messageId: Int = 0,
    val sessionId: Int,
    val text: String,
    val isUser: Boolean,
    val thinkingProcess: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val vectorEmbedding: String? = null // For the semantic search later!
)