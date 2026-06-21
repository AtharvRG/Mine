package com.mine.app.ai

import android.util.Log
import androidx.room.withTransaction
import com.mine.app.data.local.db.MineDatabase
import com.mine.app.data.local.db.dao.ChatDao
import com.mine.app.data.local.db.dao.GraphDao
import com.mine.app.data.local.db.entity.BranchEntity
import com.mine.app.data.local.db.entity.EdgeEntity
import com.mine.app.data.local.db.entity.GroupEntity
import com.mine.app.data.local.db.entity.NodeEmbeddingEntity
import com.mine.app.data.local.db.entity.NodeEntity
import com.mine.app.data.remote.NimApi
import com.mine.app.data.remote.dto.NimEmbeddingRequest
import com.mine.app.util.NotificationHelper
import com.mine.app.util.VectorUtils
import kotlinx.serialization.json.Json
import javax.inject.Inject

class ToolExecutor @Inject constructor(
    private val database: MineDatabase,
    private val graphDao: GraphDao,
    private val nimApi: NimApi,
    private val notificationHelper: NotificationHelper, // <-- NEW
    private val chatDao: ChatDao // <-- NEW
) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // Notice we added apiKey as a parameter here
    suspend fun executeUpdateMemoryGraph(apiKey: String, argumentsJson: String): Boolean {
        return try {
            val args = json.decodeFromString<UpdateMemoryGraphArgs>(argumentsJson)
            
            if (args.discardTranscript && args.memoryOperations.isEmpty()) {
                return true 
            }

            // Keep track of new nodes so we can embed them after the transaction
            val newlyCreatedNodes = mutableListOf<Pair<Int, String>>()

            database.withTransaction {
                val defaultGroupId = graphDao.insertGroup(GroupEntity(name = "Auto-Generated", color = "#9E9E9E")).toInt()
                val safeGroupId = if (defaultGroupId == -1) 1 else defaultGroupId 

                for (op in args.memoryOperations) {
                    when (op.action) {
                        "create" -> {
                            if (op.content != null) {
                                val branchName = op.branch ?: "personal"
                                var branch = graphDao.getBranchByPath(branchName)
                                if (branch == null) {
                                    val newBranchId = graphDao.insertBranch(BranchEntity(groupId = safeGroupId, name = branchName, path = branchName)).toInt()
                                    branch = BranchEntity(id = newBranchId, groupId = safeGroupId, name = branchName, path = branchName)
                                }
                                val insertedId = graphDao.insertNode(
                                    NodeEntity(branchId = branch.id, content = op.content, confidence = op.confidence ?: 1.0f, source = "ambient")
                                ).toInt()
                                newlyCreatedNodes.add(Pair(insertedId, op.content))
                            }
                        }
                        "update" -> {
                            if (op.targetNodeId != null && op.content != null) {
                                val existingNode = graphDao.getNodeById(op.targetNodeId)
                                if (existingNode != null) {
                                    val updatedContent = "${existingNode.content}\nUpdate: ${op.content}"
                                    graphDao.updateNodeContent(op.targetNodeId, updatedContent)
                                }
                            }
                        }
                        "contradict" -> {
                            if (op.targetNodeId != null && op.content != null) {
                                val targetNode = graphDao.getNodeById(op.targetNodeId)
                                if (targetNode != null) {
                                    val newNodeId = graphDao.insertNode(
                                        NodeEntity(branchId = targetNode.branchId, content = op.content, confidence = 1.0f, source = "ambient")
                                    ).toInt()
                                    newlyCreatedNodes.add(Pair(newNodeId, op.content))
                                    graphDao.insertEdge(EdgeEntity(sourceNodeId = newNodeId, targetNodeId = targetNode.id, relation = "contradicts"))
                                }
                            }
                        }
                        "relate" -> {
                            if (op.targetNodeId != null && op.content != null) {
                                val targetNode = graphDao.getNodeById(op.targetNodeId)
                                if (targetNode != null) {
                                    val branchName = op.branch ?: "personal"
                                    var branch = graphDao.getBranchByPath(branchName)
                                    if (branch == null) {
                                        val newBranchId = graphDao.insertBranch(BranchEntity(groupId = safeGroupId, name = branchName, path = branchName)).toInt()
                                        branch = BranchEntity(id = newBranchId, groupId = safeGroupId, name = branchName, path = branchName)
                                    }
                                    val newNodeId = graphDao.insertNode(
                                        NodeEntity(branchId = branch.id, content = op.content, confidence = op.confidence ?: 1.0f, source = "ambient")
                                    ).toInt()
                                    newlyCreatedNodes.add(Pair(newNodeId, op.content))

                                    val relation = op.edgeLabel ?: "related_to"
                                    graphDao.insertEdge(EdgeEntity(sourceNodeId = newNodeId, targetNodeId = targetNode.id, relation = relation))
                                }
                            }
                        }
                        "delete" -> {
                            if (op.targetNodeId != null) {
                                graphDao.updateNodeStatus(op.targetNodeId, "trashed")
                                Log.d("ToolExecutor", "Node ${op.targetNodeId} moved to trash.")
                            }
                        }
                    }
                }
            }
            
            // --- NEW: GENERATE AND SAVE EMBEDDINGS ---
            if (newlyCreatedNodes.isNotEmpty()) {
                Log.d("ToolExecutor", "Fetching embeddings for ${newlyCreatedNodes.size} new nodes...")
                val request = NimEmbeddingRequest(
                    input = newlyCreatedNodes.map { it.second },
                    inputType = "passage"
                )
                val response = nimApi.getEmbeddings(apiKey, request)
                
                // Save the vectors to SQLite as BLOBs
                response.data.forEachIndexed { index, embeddingData ->
                    val nodeId = newlyCreatedNodes[index].first
                    val floatArray = embeddingData.embedding.toFloatArray()
                    val byteArray = VectorUtils.floatArrayToByteArray(floatArray)
                    graphDao.insertNodeEmbedding(NodeEmbeddingEntity(nodeId, byteArray))
                }
                Log.d("ToolExecutor", "Embeddings saved successfully!")
            }

            // --- NEW: FIRE THE NUDGE ---
            // Check the root level first, then check if any operation has it
            val finalNudgeMessage = args.nudgeMessage ?: args.memoryOperations.firstNotNullOfOrNull { it.nudgeMessage }

            if (args.triggerNudge && !finalNudgeMessage.isNullOrBlank()) {
                Log.d("ToolExecutor", "FIRING NUDGE: $finalNudgeMessage")
                notificationHelper.showNudgeNotification(finalNudgeMessage)
                chatDao.insertMessage(
                    com.mine.app.data.local.db.entity.ChatMessageEntity(
                        dayTimestamp = com.mine.app.util.TimeUtils.getStartOfDayTimestamp(),
                        role = "mine", 
                        content = "⚡ $finalNudgeMessage",
                        isNudge = true
                    )
                )
            }

            true 
        } catch (e: Exception) {
            Log.e("ToolExecutor", "CRITICAL DB ERROR: ${e.message}", e)
            false
        }
    }
}
