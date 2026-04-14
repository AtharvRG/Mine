package com.mine.app.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "edges",
    foreignKeys = [
        ForeignKey(
            entity = NodeEntity::class,
            parentColumns = ["id"],
            childColumns = ["source_node_id"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = NodeEntity::class,
            parentColumns = ["id"],
            childColumns = ["target_node_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["source_node_id"]),
        Index(value = ["target_node_id"])
    ]
)
data class EdgeEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    @ColumnInfo(name = "source_node_id") val sourceNodeId: Int,
    @ColumnInfo(name = "target_node_id") val targetNodeId: Int,
    @ColumnInfo(name = "relation") val relation: String,
    @ColumnInfo(name = "strength", defaultValue = "0.8") val strength: Float = 0.8f,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis()
)