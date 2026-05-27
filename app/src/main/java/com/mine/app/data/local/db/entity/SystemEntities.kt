package com.mine.app.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "memory_queue",
    indices = [Index(value = ["status"])]
)
data class MemoryQueueEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    @ColumnInfo(name = "raw_text") val rawText: String? = null,
    @ColumnInfo(name = "audio_blob", typeAffinity = ColumnInfo.BLOB) val audioBlob: ByteArray? = null,
    @ColumnInfo(name = "speaker_label") val speakerLabel: String? = null,
    @ColumnInfo(name = "source", defaultValue = "'ambient'") val source: String = "ambient",
    @ColumnInfo(name = "status", defaultValue = "'pending'") val status: String = "pending",
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "api_queue",
    indices = [Index(value = ["priority", "created_at"])]
)
data class ApiQueueEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    @ColumnInfo(name = "endpoint") val endpoint: String,
    @ColumnInfo(name = "payload_blob", typeAffinity = ColumnInfo.BLOB) val payloadBlob: ByteArray,
    @ColumnInfo(name = "priority", defaultValue = "5") val priority: Int = 5,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "attempts", defaultValue = "0") val attempts: Int = 0,
    @ColumnInfo(name = "error") val error: String? = null
)

@Entity(
    tableName = "chat_messages",
    indices = [Index(value = ["day_timestamp", "created_at"])]
)
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    @ColumnInfo(name = "day_timestamp") val dayTimestamp: Long,
    @ColumnInfo(name = "role") val role: String,
    @ColumnInfo(name = "content") val content: String,
    @ColumnInfo(name = "is_nudge", defaultValue = "0") val isNudge: Boolean = false,
    @ColumnInfo(name = "thinking_trace") val thinkingTrace: String? = null,
    @ColumnInfo(name = "model_used") val modelUsed: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "rejected_logs")
data class RejectedLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    @ColumnInfo(name = "transcript") val transcript: String,
    @ColumnInfo(name = "reason") val reason: String,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "transcripts")
data class TranscriptEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    @ColumnInfo(name = "text") val text: String,
    @ColumnInfo(name = "speaker") val speaker: String,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis()
)
