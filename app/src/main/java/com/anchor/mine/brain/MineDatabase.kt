package com.anchor.mine.brain

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        TranscriptNode::class,
        MemoryNodeL2::class,
        VectorNodeL3::class,
        ChatSession::class,        // <-- ADDED
        ChatMessageEntity::class   // <-- ADDED
    ],
    version = 4,
    exportSchema = false
)
abstract class MineDatabase : RoomDatabase() {

    abstract fun memoryDao(): MemoryDao

    companion object {
        @Volatile
        private var INSTANCE: MineDatabase? = null

        fun getDatabase(context: Context): MineDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    MineDatabase::class.java,
                    "mine_brain.db"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}