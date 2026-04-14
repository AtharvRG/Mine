package com.mine.app.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "groups")
data class GroupEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "color", defaultValue = "'#4CAF50'") val color: String = "#4CAF50",
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis()
)