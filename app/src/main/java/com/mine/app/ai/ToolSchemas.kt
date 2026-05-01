package com.mine.app.ai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class UpdateMemoryGraphArgs(
    @SerialName("discard_transcript") val discardTranscript: Boolean = false,
    @SerialName("memory_operations") val memoryOperations: List<MemoryOperation> = emptyList(),
    @SerialName("trigger_nudge") val triggerNudge: Boolean = false
)

@Serializable
data class MemoryOperation(
    @SerialName("action") val action: String, // "create", "update", "contradict"
    @SerialName("content") val content: String,
    @SerialName("branch") val branch: String? = null, // Required for "create"
    @SerialName("confidence") val confidence: Float? = null,
    @SerialName("target_node_id") val targetNodeId: Int? = null // Required for "update" and "contradict"
)
