package com.mine.app.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.mine.app.data.local.db.entity.ChatMessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: ChatMessageEntity): Long

    // Returns a Flow so the UI automatically updates when a new message or nudge arrives
    @Query("SELECT * FROM chat_messages ORDER BY created_at DESC LIMIT :limit")
    fun getRecentMessagesFlow(limit: Int = 50): Flow<List<ChatMessageEntity>>

    // Standard suspend function for the Context Assembler to grab recent history
    @Query("SELECT * FROM chat_messages ORDER BY created_at DESC LIMIT :limit")
    suspend fun getRecentMessagesList(limit: Int = 10): List<ChatMessageEntity>
}
