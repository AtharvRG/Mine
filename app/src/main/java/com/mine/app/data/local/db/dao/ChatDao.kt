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

    // Only fetch messages from TODAY
    @Query("SELECT * FROM chat_messages WHERE day_timestamp = :startOfDay ORDER BY created_at DESC LIMIT :limit")
    fun getRecentMessagesFlow(startOfDay: Long, limit: Int = 50): Flow<List<ChatMessageEntity>>

    @Query("SELECT * FROM chat_messages ORDER BY created_at DESC LIMIT :limit")
    suspend fun getRecentMessagesList(limit: Int = 10): List<ChatMessageEntity>

    @Query("SELECT * FROM chat_messages WHERE day_timestamp < :startOfToday ORDER BY created_at ASC")
    suspend fun getOldMessagesList(startOfToday: Long): List<ChatMessageEntity>

    @Query("DELETE FROM chat_messages WHERE day_timestamp < :startOfToday")
    suspend fun deleteOldMessages(startOfToday: Long): Int
}
