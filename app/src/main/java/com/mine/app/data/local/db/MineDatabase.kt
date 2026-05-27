package com.mine.app.data.local.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.mine.app.data.local.db.dao.*
import com.mine.app.data.local.db.entity.*

@Database(
    entities = [
        GroupEntity::class,
        BranchEntity::class,
        NodeEntity::class,
        EdgeEntity::class,
        NodeFtsEntity::class,
        MemoryQueueEntity::class,
        ApiQueueEntity::class,
        ChatMessageEntity::class,
        RejectedLogEntity::class,
        NodeEmbeddingEntity::class,
        TranscriptEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class MineDatabase : RoomDatabase() {
    abstract fun graphDao(): GraphDao
    abstract fun queueDao(): QueueDao
    abstract fun chatDao(): ChatDao
}
