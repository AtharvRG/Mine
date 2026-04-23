package com.mine.app.ai

import android.util.Log
import com.mine.app.data.local.db.dao.GraphDao
import com.mine.app.data.remote.NimApi
import com.mine.app.data.remote.dto.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import javax.inject.Inject

class CuratorAgent @Inject constructor(
    private val nimApi: NimApi,
    private val toolExecutor: ToolExecutor,
    private val graphDao: GraphDao
) {
    // The exact schema parameters we designed
    private val toolSchemaJson = """
    {
      "type": "object",
      "properties": {
                "discard_transcript": { 
          "type": "boolean",
          "description": "Set to true ONLY if the ENTIRE transcript is useless. If you extract ANY nodes, this MUST be false."
        },

        "nodes_to_create": {
          "type": "array",
          "items": {
            "type": "object",
            "properties": {
              "content": { "type": "string" },
              "branch": { "type": "string", "enum": ["personal", "people", "work", "health", "finance", "purchases", "emotions", "tasks"] },
              "confidence": { "type": "number" }
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
              "new_status": { "type": "string", "enum": ["active", "completed", "archived"] },
              "appended_content": { "type": "string" }
            },
            "required": ["node_id", "new_status"]
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
        // 1. Fetch context from the database (What does the AI already know?)
        val relevantNodes = graphDao.searchNodes(transcript, limit = 5)
        val contextBuilder = StringBuilder("EXISTING CONTEXT:\n")
        relevantNodes.forEach { node ->
            contextBuilder.append("- ID ${node.id} (${node.branchId}): ${node.content}\n")
        }

        // 2. Build the System Prompt
        val systemPrompt = """
            You are MineAI-Curator. Extract high-signal facts from the transcript and map them to the database using the update_memory_graph tool.
            RULES:
            1. Discard filler and small talk.
            2. Keep commitments, facts, and events.
            3. If the user contradicts an existing fact, use the contradictions array.
            
            $contextBuilder
        """.trimIndent()

        // 3. Build the Tool Object
        val memoryTool = NimTool(
            type = "function",
            function = NimFunctionDefinition(
                name = "update_memory_graph",
                description = "Updates the user's memory graph.",
                parameters = Json.parseToJsonElement(toolSchemaJson).jsonObject
            )
        )

        // 4. Send to NVIDIA NIM
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

        // 5. Extract the Tool Call and Execute it!
        val toolCall = responseMessage?.toolCalls?.firstOrNull()

        if (toolCall != null && toolCall.function.name == "update_memory_graph") {
            val jsonArguments = toolCall.function.arguments
            Log.d("CuratorAgent", "AI returned Tool JSON: $jsonArguments")

            val success = toolExecutor.executeUpdateMemoryGraph(jsonArguments)
            return if (success) "Success! AI updated the database." else "Failed to execute DB transaction."
        }

        return "AI decided to discard the transcript (No tool called)."
    }
}
