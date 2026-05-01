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
        "discard_transcript": { 
          "type": "boolean",
          "description": "Set to true ONLY if the ENTIRE transcript is useless."
        },
        "nodes_to_create": {
          "type": "array",
          "items": {
            "type": "object",
            "properties": {
              "content": { "type": "string" },
              "branch": { "type": "string", "enum": ["personal", "people", "work", "health", "finance", "purchases", "emotions", "tasks"] },
              "confidence": { "type": "number" },
              "related_to_existing_node_id": { "type": ["integer", "null"], "description": "If this new node is a continuation or related to a node in the EXISTING CONTEXT, put that node's ID here." }
            },
            "required": ["content", "branch", "confidence"]
          }
        },
        "nodes_to_update": {
          "type": "array",
          "items": {
            "type": "object",
            "properties": {
              "node_id": { "type": "integer" },
              "new_status": { "type": "string", "enum": ["active", "completed", "archived", "trashed"] },
              "appended_content": { "type": "string", "description": "Use this to add new details to an existing node instead of creating a new one." }
            },
            "required": ["node_id"]
          }
        },
        "contradictions": {
          "type": "array",
          "items": {
            "type": "object",
            "properties": {
              "target_node_id": { "type": "integer" },
              "new_content": { "type": "string" }
            },
            "required": ["target_node_id", "new_content"]
          }
        },
        "trigger_nudge": { "type": "boolean" }
      },
      "required": ["discard_transcript", "nodes_to_create", "nodes_to_update", "contradictions", "trigger_nudge"]
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
        val topNodeIds = scoredNodes.filter { it.second > 0.5f }
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

        // 4. Build the STRICT System Prompt
        val systemPrompt = """
            You are MineAI-Curator. Extract high-signal facts from the transcript and map them to the database using the update_memory_graph tool.
            
            RULES:
            1. Discard filler and small talk.
            2. Keep commitments, facts, and events.
            3. 3RD PERSON ONLY: All `content` and `appended_content` MUST be written in the third person (e.g., "User is...", "User wants..."). NEVER use "I", "me", or "my".
            4. AGGRESSIVE DEDUPLICATION: Look closely at the EXISTING CONTEXT. If the transcript is just an update, emotion, or progress report on an existing node, DO NOT create a new node. You MUST use `nodes_to_update` and put the new info in `appended_content`.
            5. EDGES: If you absolutely MUST create a new node, but it relates to an existing node, you MUST provide the `related_to_existing_node_id`.
            6. DELETION: If the user explicitly says to delete, forget, or remove a memory, use `nodes_to_update` and set `new_status` to "trashed".
            
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
            toolChoice = "auto"
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
