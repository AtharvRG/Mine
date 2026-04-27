package com.mine.app.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class NimChatRequest(
    @SerialName("model") val model: String,
    @SerialName("messages") val messages: List<NimMessage>,
    @SerialName("temperature") val temperature: Double? = 0.7,
    @SerialName("max_tokens") val maxTokens: Int? = 1024,
    @SerialName("tools") val tools: List<NimTool>? = null,
    @SerialName("tool_choice") val toolChoice: String? = null
)

@Serializable
data class NimMessage(
    @SerialName("role") val role: String,
    @SerialName("content") val content: String? = null,
    @SerialName("reasoning_content") val reasoningContent: String? = null,
    @SerialName("tool_calls") val toolCalls: List<NimToolCall>? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null
)

@Serializable
data class NimTool(
    @SerialName("type") val type: String = "function",
    @SerialName("function") val function: NimFunctionDefinition
)

@Serializable
data class NimFunctionDefinition(
    @SerialName("name") val name: String,
    @SerialName("description") val description: String,
    @SerialName("parameters") val parameters: JsonObject
)

@Serializable
data class NimToolCall(
    @SerialName("id") val id: String,
    @SerialName("type") val type: String,
    @SerialName("function") val function: NimFunctionCall
)

@Serializable
data class NimFunctionCall(
    @SerialName("name") val name: String,
    @SerialName("arguments") val arguments: String
)

@Serializable
data class NimChatResponse(
    @SerialName("id") val id: String,
    @SerialName("model") val model: String,
    @SerialName("choices") val choices: List<NimChoice>
)

@Serializable
data class NimChoice(
    @SerialName("index") val index: Int,
    @SerialName("message") val message: NimMessage,
    @SerialName("finish_reason") val finishReason: String? = null
)
