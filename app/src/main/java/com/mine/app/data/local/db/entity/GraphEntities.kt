package com.mine.app.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "groups")
data class GroupEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "color", defaultValue = "'#4CAF50'") val color: String = "#4CAF50",
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "branches",
    foreignKeys = [
        ForeignKey(
            entity = GroupEntity::class,
            parentColumns = ["id"],
            childColumns = ["group_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["path"], unique = true), Index(value = ["group_id"])]
)
data class BranchEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    @ColumnInfo(name = "group_id") val groupId: Int,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "path") val path: String,
    @ColumnInfo(name = "parent_branch_id") val parentBranchId: Int? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "nodes",
    foreignKeys = [
        ForeignKey(
            entity = BranchEntity::class,
            parentColumns = ["id"],
            childColumns = ["branch_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["branch_id"]),
        Index(value = ["status"]),
        Index(value = ["last_accessed"])
    ]
)
data class NodeEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    @ColumnInfo(name = "branch_id") val branchId: Int,
    @ColumnInfo(name = "content") val content: String,
    @ColumnInfo(name = "status", defaultValue = "'active'") val status: String = "active",
    @ColumnInfo(name = "confidence", defaultValue = "1.0") val confidence: Float = 1.0f,
    @ColumnInfo(name = "source") val source: String? = null,
    @ColumnInfo(name = "speaker_label") val speakerLabel: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "last_accessed") val lastAccessed: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "edges",
    foreignKeys = [
        ForeignKey(entity = NodeEntity::class, parentColumns = ["id"], childColumns = ["source_node_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = NodeEntity::class, parentColumns = ["id"], childColumns = ["target_node_id"], onDelete = ForeignKey.CASCADE)
    ],
    indices = [Index(value = ["source_node_id"]), Index(value = ["target_node_id"])]
)
data class EdgeEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    @ColumnInfo(name = "source_node_id") val sourceNodeId: Int,
    @ColumnInfo(name = "target_node_id") val targetNodeId: Int,
    @ColumnInfo(name = "relation") val relation: String,
    @ColumnInfo(name = "strength", defaultValue = "0.8") val strength: Float = 0.8f,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "nodes_fts")
@Fts4(contentEntity = NodeEntity::class)
data class NodeFtsEntity(
    @ColumnInfo(name = "content") val content: String
)

@Entity(
    tableName = "node_embeddings",
    foreignKeys = [
        ForeignKey(
            entity = NodeEntity::class,
            parentColumns = ["id"],
            childColumns = ["node_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["node_id"])]

)
data class NodeEmbeddingEntity(
    @PrimaryKey val node_id: Int,
    @ColumnInfo(name = "embedding", typeAffinity = ColumnInfo.BLOB) val embedding: ByteArray
)

