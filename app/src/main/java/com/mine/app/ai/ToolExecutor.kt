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

            database.withTransaction {
                val defaultGroupId = graphDao.insertGroup(GroupEntity(name = "Auto-Generated", color = "#9E9E9E")).toInt()
                val safeGroupId = if (defaultGroupId == -1) 1 else defaultGroupId

                Log.d("ToolExecutor", "Using Group ID: $safeGroupId")

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

                for (contra in args.contradictions) {
                    val targetNode = graphDao.getNodeById(contra.targetNodeId)
                    if (targetNode != null) {
                        val newNodeId = graphDao.insertNode(
                            NodeEntity(
                                branchId = targetNode.branchId,
                                content = contra.newContent,
                                confidence = 1.0f,
                                source = "ambient"
                            )
                        ).toInt()

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

            Log.d("ToolExecutor", "Transaction Complete!")
            true
        } catch (e: Exception) {
            Log.e("ToolExecutor", "CRITICAL DB ERROR: ${e.message}", e)
            false
        }
    }
}
