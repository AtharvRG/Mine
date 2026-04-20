package com.mine.app.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.mine.app.data.local.db.entity.ApiQueueEntity
import com.mine.app.data.local.db.entity.MemoryQueueEntity

@Dao
interface QueueDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMemory(memory: MemoryQueueEntity): Long

    @Query("SELECT * FROM memory_queue WHERE status = 'pending' ORDER BY created_at ASC LIMIT :limit")
    suspend fun getPendingMemories(limit: Int = 10): List<MemoryQueueEntity>

    @Query("UPDATE memory_queue SET status = :status WHERE id = :id")
    suspend fun updateMemoryStatus(id: Int, status: String): Int

    @Query("DELETE FROM memory_queue WHERE id = :id")
    suspend fun deleteMemory(id: Int): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertApiTask(task: ApiQueueEntity): Long

    // Fetches API tasks sorted by Priority (1 is highest) then by oldest first
    @Query("SELECT * FROM api_queue ORDER BY priority ASC, created_at ASC LIMIT :limit")
    suspend fun getPendingApiTasks(limit: Int = 5): List<ApiQueueEntity>

    @Query("DELETE FROM api_queue WHERE id = :id")
    suspend fun deleteApiTask(id: Int): Int
}
