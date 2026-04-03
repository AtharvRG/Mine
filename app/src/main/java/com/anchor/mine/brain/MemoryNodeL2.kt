package com.anchor.mine.brain

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "memory_nodes_l2")
data class MemoryNodeL2(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val summaryText: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isReviewed: Boolean = false
)

// --- NEW: The L3 Vector Memory ---
@Entity(tableName = "memory_nodes_l3")
data class VectorNodeL3(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val sourceL2Id: Int, // Links back to the original journal entry
    val textContent: String, // The actual fact (e.g., "Sarah likes lattes")
    val vectorEmbedding: String // Stored as a JSON array of floats!
)