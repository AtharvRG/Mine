package com.mine.app.ai

import android.util.Log
import com.mine.app.data.local.db.dao.GraphDao
import com.mine.app.data.remote.NimApi
import com.mine.app.data.remote.dto.*
import com.mine.app.util.VectorUtils
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import javax.inject.Inject

class CuratorAgent @Inject constructor(
    private val nimApi: NimApi,
    private val toolExecutor: ToolExecutor,
    private val graphDao: GraphDao
) {
    private val toolSchemaJson = """
    {
      "type": "object",
      "properties": {
        "discard_transcript": { "type": "boolean" },
        "memory_operations": {
          "type": "array",
          "items": {
            "type": "object",
            "properties": {
              "action": { "type": "string", "enum": ["create", "update", "contradict", "relate", "delete"] },
              "content": { "type": "string", "description": "The 3rd-person fact. Leave empty if action is delete." },
              "branch": { "type": "string" },
              "confidence": { "type": "number" },
              "target_node_id": { "type": "integer", "description": "The ID of the existing node you are updating, contradicting, relating to, or deleting." },
              "edge_label": { "type": "string", "enum": ["depends_on", "sub_task_of", "blocks", "related_to"] }
            },
            "required": ["action"]
          }
        },
        "trigger_nudge": { "type": "boolean" }
      },
      "required": ["discard_transcript", "memory_operations", "trigger_nudge"]
    }
    """.trimIndent()

    suspend fun processTranscript(apiKey: String, transcript: String): String {
        // 1. Convert the incoming transcript into a Vector
        val transcriptEmbReq = NimEmbeddingRequest(
            input = listOf(transcript),
            inputType = "query"
        )
        val transcriptVector = nimApi.getEmbeddings(apiKey, transcriptEmbReq).data[0].embedding.toFloatArray()

        // 2. Fetch all DB embeddings and run Cosine Similarity Math
        val allEmbeddings = graphDao.getAllEmbeddings()
        val scoredNodes = allEmbeddings.map { dbEmb ->
            val dbVector = VectorUtils.byteArrayToFloatArray(dbEmb.embedding)
            val score = VectorUtils.cosineSimilarity(transcriptVector, dbVector)
            Pair(dbEmb.node_id, score)
        }

        // 3. Take the top 5 most similar nodes (Threshold > 0.5 means it's somewhat related)
        val topNodeIds = scoredNodes.filter { it.second > 0.2f }
            .sortedByDescending { it.second }
            .take(5)
            .map { it.first }

        val contextBuilder = StringBuilder("EXISTING CONTEXT:\n")
        topNodeIds.forEach { id ->
            val node = graphDao.getNodeById(id)
            if (node != null) {
                contextBuilder.append("- ID ${node.id} (${node.branchId}): ${node.content}\n")
            }
        }

        val currentTime = com.mine.app.util.TimeUtils.getCurrentTimestamp()

        val systemPrompt = """
            [CURRENT SYSTEM TIME: $currentTime]
            
            You are MineAI-Curator. Extract high-signal facts from the transcript and map them to the database using the update_memory_graph tool.
            
            RULES:
            1. 3RD PERSON ONLY: All `content` MUST be written in the third person (e.g., "User is...").
            2. ACTION 'create': Use ONLY for completely new topics not found in the EXISTING CONTEXT.
            3. ACTION 'update': If the transcript adds details to an existing node, use this action and provide the `target_node_id`.
            4. ACTION 'contradict': If the user changes their mind or contradicts an existing node, use this action, provide `target_node_id`, and write the new state in `content`.
            5. ACTION 'relate': If the transcript introduces a new fact that is a sub-task, dependency, or blocker of an existing node, use this. Provide `target_node_id`, the new `content`, and an `edge_label` (depends_on, sub_task_of, blocks, related_to).
            6. ACTION 'delete': If the user explicitly says to forget, delete, or cancel a specific memory, use this action and provide the `target_node_id`.
            
            $contextBuilder
        """.trimIndent()



        val memoryTool = NimTool(
            type = "function",
            function = NimFunctionDefinition(
                name = "update_memory_graph",
                description = "Updates the user's memory graph.",
                parameters = Json.parseToJsonElement(toolSchemaJson).jsonObject
            )
        )

        val request = NimChatRequest(
            model = "openai/gpt-oss-20b",
            messages = listOf(
                NimMessage(role = "system", content = systemPrompt),
                NimMessage(role = "user", content = "TRANSCRIPT: $transcript")
            ),
            tools = listOf(memoryTool),
            toolChoice = "required",
            maxTokens = 4096
        )

        val response = nimApi.getChatCompletion(apiKey, request)
        val responseMessage = response.choices.firstOrNull()?.message

        val toolCall = responseMessage?.toolCalls?.firstOrNull()

        if (toolCall != null && toolCall.function.name == "update_memory_graph") {
            val jsonArguments = toolCall.function.arguments
            Log.d("CuratorAgent", "AI returned Tool JSON: $jsonArguments")

            val success = toolExecutor.executeUpdateMemoryGraph(apiKey, jsonArguments)
            return if (success) "Success! AI updated the database." else "Failed to execute DB transaction."
        }

        return "AI decided to discard the transcript (No tool called)."
    }
}
