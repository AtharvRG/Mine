package com.mine.app.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4 // <-- Changed to Fts4[<vertex-ai-rich-citation-chip>1</vertex-ai-rich-citation-chip>][<vertex-ai-rich-citation-chip>2</vertex-ai-rich-citation-chip>]

@Entity(tableName = "nodes_fts")
@Fts4(contentEntity = NodeEntity::class) // <-- Changed to Fts4 and contentEntity
data class NodeFtsEntity(
    @ColumnInfo(name = "content") val content: String
)