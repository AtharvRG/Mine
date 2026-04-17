package com.mine.app.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.mine.app.data.local.db.entity.BranchEntity
import com.mine.app.data.local.db.entity.EdgeEntity
import com.mine.app.data.local.db.entity.GroupEntity
import com.mine.app.data.local.db.entity.NodeEntity

@Dao
interface GraphDao {

    // --- INSERTIONS ---
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertGroup(group: GroupEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertBranch(branch: BranchEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNode(node: NodeEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEdge(edge: EdgeEntity): Long

    // --- QUERIES ---
    @Query("SELECT * FROM branches WHERE path = :path LIMIT 1")
    suspend fun getBranchByPath(path: String): BranchEntity?

    @Query("SELECT * FROM nodes WHERE id = :nodeId LIMIT 1")
    suspend fun getNodeById(nodeId: Int): NodeEntity?

    // The Magic Search: Joins the FTS4 table with the real nodes table
    @Query("""
        SELECT nodes.* FROM nodes 
        JOIN nodes_fts ON nodes.id = nodes_fts.rowid 
        WHERE nodes_fts MATCH :searchQuery 
        ORDER BY nodes.last_accessed DESC 
        LIMIT :limit
    """)
    suspend fun searchNodes(searchQuery: String, limit: Int = 5): List<NodeEntity>

    // --- UPDATES ---
    @Query("UPDATE nodes SET status = :status, updated_at = :timestamp WHERE id = :nodeId")
    suspend fun updateNodeStatus(nodeId: Int, status: String, timestamp: Long = System.currentTimeMillis()): Int

    @Query("UPDATE nodes SET content = :newContent, updated_at = :timestamp WHERE id = :nodeId")
    suspend fun updateNodeContent(nodeId: Int, newContent: String, timestamp: Long = System.currentTimeMillis()): Int



}
