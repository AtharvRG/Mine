package com.mine.app.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.mine.app.data.local.db.entity.BranchEntity
import com.mine.app.data.local.db.entity.EdgeEntity
import com.mine.app.data.local.db.entity.GroupEntity
import com.mine.app.data.local.db.entity.NodeEmbeddingEntity
import com.mine.app.data.local.db.entity.NodeEntity

@Dao
interface GraphDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertGroup(group: GroupEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertBranch(branch: BranchEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNode(node: NodeEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEdge(edge: EdgeEntity): Long

    @Query("SELECT * FROM branches WHERE path = :path LIMIT 1")
    suspend fun getBranchByPath(path: String): BranchEntity?

    @Query("SELECT * FROM nodes WHERE id = :nodeId LIMIT 1")
    suspend fun getNodeById(nodeId: Int): NodeEntity?

    @Query("""
        SELECT nodes.* FROM nodes 
        JOIN nodes_fts ON nodes.id = nodes_fts.rowid 
        WHERE nodes_fts MATCH :searchQuery 
        ORDER BY nodes.last_accessed DESC 
        LIMIT :limit
    """)
    suspend fun searchNodes(searchQuery: String, limit: Int = 5): List<NodeEntity>

    @Query("SELECT * FROM nodes WHERE status = 'active' ORDER BY updated_at DESC LIMIT :limit")
    suspend fun getActiveNodes(limit: Int = 30): List<NodeEntity>

    @Query("SELECT * FROM nodes ORDER BY created_at DESC LIMIT :limit")
    suspend fun getAllNodes(limit: Int = 50): List<NodeEntity>

    @Query("UPDATE nodes SET status = :status, updated_at = :timestamp WHERE id = :nodeId")
    suspend fun updateNodeStatus(nodeId: Int, status: String, timestamp: Long = System.currentTimeMillis()): Int

    @Query("UPDATE nodes SET content = :newContent, updated_at = :timestamp WHERE id = :nodeId")
    suspend fun updateNodeContent(nodeId: Int, newContent: String, timestamp: Long = System.currentTimeMillis()): Int

    @Query("SELECT * FROM edges ORDER BY created_at DESC LIMIT :limit")
    suspend fun getAllEdges(limit: Int = 50): List<EdgeEntity>

    // --- VECTOR DB QUERIES ---
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNodeEmbedding(embedding: NodeEmbeddingEntity): Long

    @Query("SELECT * FROM node_embeddings")
    suspend fun getAllEmbeddings(): List<NodeEmbeddingEntity>
}
