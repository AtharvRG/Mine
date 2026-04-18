package com.anchor.mine.brain

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MemoryDao {

    @Insert
    fun insertTranscript(node: TranscriptNode): Long

    @Query("SELECT * FROM memory_nodes_l1 ORDER BY timestamp DESC")
    fun getAllTranscripts(): Flow<List<TranscriptNode>>

    @Query("SELECT * FROM memory_nodes_l1 WHERE isProcessed = 0 ORDER BY timestamp ASC")
    fun getUnprocessedTranscripts(): List<TranscriptNode>

    @Query("UPDATE memory_nodes_l1 SET isProcessed = 1 WHERE isProcessed = 0")
    fun markAllAsProcessed()

    @Query("DELETE FROM memory_nodes_l1")
    fun wipeMemory(): Int

    // --- L2 MEMORY ---
    @Insert
    fun insertL2Memory(node: MemoryNodeL2): Long

    @Query("SELECT * FROM memory_nodes_l2 WHERE isReviewed = 0 ORDER BY timestamp DESC LIMIT 1")
    fun getLatestUnreviewedMemory(): MemoryNodeL2?

    @Query("UPDATE memory_nodes_l2 SET summaryText = :newText, isReviewed = 1 WHERE id = :id")
    fun commitL2Memory(id: Int, newText: String)

    // FIXED: Removed 'suspend' from here!
    @Query("SELECT * FROM memory_nodes_l2 WHERE isReviewed = 1 ORDER BY timestamp DESC LIMIT 10")
    fun getRecentMemoriesForChat(): List<MemoryNodeL2>

    // --- L3 MEMORY (Vector Embeddings) ---
    @Insert
    fun insertVectorNode(node: VectorNodeL3)

    // Pulls all vectors so we can do local math comparisons!
    @Query("SELECT * FROM memory_nodes_l3")
    fun getAllVectors(): List<VectorNodeL3>

    // --- CHAT HISTORY ---

    @Insert
    fun createChatSession(session: ChatSession): Long

    @Query("SELECT * FROM chat_sessions ORDER BY timestamp DESC")
    fun getAllChatSessions(): kotlinx.coroutines.flow.Flow<List<ChatSession>>

    @Insert
    fun insertChatMessage(message: ChatMessageEntity): Long

    @Query("SELECT * FROM chat_messages WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    fun getMessagesForSession(sessionId: Int): List<ChatMessageEntity>

    // For Auto-Titling
    @Query("UPDATE chat_sessions SET title = :newTitle WHERE sessionId = :sessionId")
    fun updateChatTitle(sessionId: Int, newTitle: String)

    // For Deleting a Chat
    @Query("DELETE FROM chat_sessions WHERE sessionId = :sessionId")
    fun deleteChatSession(sessionId: Int)

    // For Semantic Search (We will pull all AI/User messages that have embeddings)
    @Query("SELECT * FROM chat_messages WHERE vectorEmbedding IS NOT NULL")
    fun getAllSearchableMessages(): List<ChatMessageEntity>
}