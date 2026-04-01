package com.anchor.mine.brain

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "memory_nodes_l1")
data class TranscriptNode(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isProcessed: Boolean = false // We will use this later for the LLM Graph cleanup!
)