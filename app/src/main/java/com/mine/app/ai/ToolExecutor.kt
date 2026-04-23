package com.mine.app.ai

import android.util.Log
import androidx.room.withTransaction
import com.mine.app.data.local.db.MineDatabase
import com.mine.app.data.local.db.dao.GraphDao
import com.mine.app.data.local.db.entity.BranchEntity
import com.mine.app.data.local.db.entity.EdgeEntity
import com.mine.app.data.local.db.entity.GroupEntity
import com.mine.app.data.local.db.entity.NodeEntity
import kotlinx.serialization.json.Json
import javax.inject.Inject

class ToolExecutor @Inject constructor(
    private val database: MineDatabase,
    private val graphDao: GraphDao
) {
    // Lenient JSON parser in case the LLM makes slight formatting mistakes
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    suspend fun executeUpdateMemoryGraph(argumentsJson: String): Boolean {
        return try {
            val args = json.decodeFromString<UpdateMemoryGraphArgs>(argumentsJson)

            if (args.discardTranscript && args.nodesToCreate.isEmpty() && args.nodesToUpdate.isEmpty() && args.contradictions.isEmpty()) {
                Log.d("ToolExecutor", "Discarding transcript. No action taken.")
                return true
            }

            Log.d("ToolExecutor", "Starting SQLite Transaction...")

            // Execute everything inside a single SQLite Transaction
            database.withTransaction {

                // 1. Ensure a default group exists for dynamic branches
                val defaultGroupId = graphDao.insertGroup(GroupEntity(name = "Auto-Generated", color = "#9E9E9E")).toInt()
                val safeGroupId = if (defaultGroupId == -1) 1 else defaultGroupId

                Log.d("ToolExecutor", "Using Group ID: $safeGroupId")

                // 2. Create New Nodes
                for (node in args.nodesToCreate) {
                    Log.d("ToolExecutor", "Attempting to insert node: ${node.content}")

                    var branch = graphDao.getBranchByPath(node.branch)
                    if (branch == null) {
                        Log.d("ToolExecutor", "Branch '${node.branch}' not found. Creating it...")
                        val newBranchId = graphDao.insertBranch(
                            BranchEntity(groupId = safeGroupId, name = node.branch, path = node.branch)
                        ).toInt()
                        branch = BranchEntity(id = newBranchId, groupId = safeGroupId, name = node.branch, path = node.branch)
                    }

                    val insertedId = graphDao.insertNode(
                        NodeEntity(
                            branchId = branch.id,
                            content = node.content,
                            confidence = node.confidence,
                            source = "ambient"
                        )
                    )
                    Log.d("ToolExecutor", "Successfully inserted Node ID: $insertedId")
                }

                // 3. Update Existing Nodes
                for (update in args.nodesToUpdate) {
                    val existingNode = graphDao.getNodeById(update.nodeId)
                    if (existingNode != null) {
                        graphDao.updateNodeStatus(update.nodeId, update.newStatus)

                        if (!update.appendedContent.isNullOrBlank()) {
                            val updatedContent = "${existingNode.content}\nUpdate: ${update.appendedContent}"
                            graphDao.updateNodeContent(update.nodeId, updatedContent)
                        }
                    }
                }

                // 4. Handle Contradictions (Create new node + draw 'contradicts' edge)
                for (contra in args.contradictions) {
                    val targetNode = graphDao.getNodeById(contra.targetNodeId)
                    if (targetNode != null) {
                        // Create the new state node
                        val newNodeId = graphDao.insertNode(
                            NodeEntity(
                                branchId = targetNode.branchId,
                                content = contra.newContent,
                                confidence = 1.0f,
                                source = "ambient"
                            )
                        ).toInt()

                        // Draw the edge connecting them
                        graphDao.insertEdge(
                            EdgeEntity(
                                sourceNodeId = newNodeId,
                                targetNodeId = targetNode.id,
                                relation = "contradicts"
                            )
                        )
                    }
                }
            }
            true // Transaction committed successfully
        } catch (e: Exception) {
            Log.e("ToolExecutor", "CRITICAL DB ERROR: ${e.message}", e)
            false // Transaction rolled back
        }

    }
}
