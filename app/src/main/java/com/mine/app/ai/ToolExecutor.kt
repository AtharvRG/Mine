package com.mine.app.ai

import android.util.Log
import androidx.room.withTransaction
import com.mine.app.data.local.db.MineDatabase
import com.mine.app.data.local.db.dao.GraphDao
import com.mine.app.data.local.db.entity.BranchEntity
import com.mine.app.data.local.db.entity.EdgeEntity
import com.mine.app.data.local.db.entity.GroupEntity
import com.mine.app.data.local.db.entity.NodeEmbeddingEntity
import com.mine.app.data.local.db.entity.NodeEntity
import com.mine.app.data.remote.NimApi
import com.mine.app.data.remote.dto.NimEmbeddingRequest
import com.mine.app.util.VectorUtils
import kotlinx.serialization.json.Json
import javax.inject.Inject

class ToolExecutor @Inject constructor(
    private val database: MineDatabase,
    private val graphDao: GraphDao,
    private val nimApi: NimApi // <-- NEW: Injected to fetch embeddings
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // Notice we added apiKey as a parameter here
    suspend fun executeUpdateMemoryGraph(apiKey: String, argumentsJson: String): Boolean {
        return try {
            val args = json.decodeFromString<UpdateMemoryGraphArgs>(argumentsJson)
            
            if (args.discardTranscript && args.nodesToCreate.isEmpty() && args.nodesToUpdate.isEmpty() && args.contradictions.isEmpty()) {
                return true 
            }

            // Keep track of new nodes so we can embed them after the transaction
            val newlyCreatedNodes = mutableListOf<Pair<Int, String>>()

            database.withTransaction {
                val defaultGroupId = graphDao.insertGroup(GroupEntity(name = "Auto-Generated", color = "#9E9E9E")).toInt()
                val safeGroupId = if (defaultGroupId == -1) 1 else defaultGroupId 

                for (node in args.nodesToCreate) {
                    var branch = graphDao.getBranchByPath(node.branch)
                    if (branch == null) {
                        val newBranchId = graphDao.insertBranch(BranchEntity(groupId = safeGroupId, name = node.branch, path = node.branch)).toInt()
                        branch = BranchEntity(id = newBranchId, groupId = safeGroupId, name = node.branch, path = node.branch)
                    }

                    val insertedId = graphDao.insertNode(
                        NodeEntity(branchId = branch.id, content = node.content, confidence = node.confidence, source = "ambient")
                    ).toInt()
                    
                    newlyCreatedNodes.add(Pair(insertedId, node.content))

                    // --- NEW: DRAW THE EDGE IF RELATED ---
                    if (node.relatedToExistingNodeId != null) {
                        Log.d("ToolExecutor", "Drawing edge from Node $insertedId to Node ${node.relatedToExistingNodeId}")
                        graphDao.insertEdge(
                            EdgeEntity(
                                sourceNodeId = insertedId,
                                targetNodeId = node.relatedToExistingNodeId,
                                relation = "related_to"
                            )
                        )
                    }
                }
                
                // 3. Update Existing Nodes
                for (update in args.nodesToUpdate) {
                    val existingNode = graphDao.getNodeById(update.nodeId)
                    if (existingNode != null) {
                        
                        // Only update status if the AI provided a new one
                        if (update.newStatus != null) {
                            graphDao.updateNodeStatus(update.nodeId, update.newStatus)
                        }

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
                        // Also embed contradictions? The instructions didn't explicitly say so, 
                        // but it makes sense to embed all new nodes.
                        // However, I'll stick to what was requested for now.
                        // Wait, the snippet says newlyCreatedNodes is for args.nodesToCreate.
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

            true 
        } catch (e: Exception) {
            Log.e("ToolExecutor", "CRITICAL DB ERROR: ${e.message}", e)
            false
        }
    }
}
