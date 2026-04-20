package com.mine.app.ai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class UpdateMemoryGraphArgs(
    @SerialName("discard_transcript") val discardTranscript: Boolean = false,
    @SerialName("nodes_to_create") val nodesToCreate: List<NodeToCreate> = emptyList(),
    @SerialName("nodes_to_update") val nodesToUpdate: List<NodeToUpdate> = emptyList(),
    @SerialName("contradictions") val contradictions: List<Contradiction> = emptyList(),
    @SerialName("trigger_nudge") val triggerNudge: Boolean = false
)

@Serializable
data class NodeToCreate(
    @SerialName("content") val content: String,
    @SerialName("branch") val branch: String,
    @SerialName("confidence") val confidence: Float
)

@Serializable
data class NodeToUpdate(
    @SerialName("node_id") val nodeId: Int,
    @SerialName("new_status") val newStatus: String,
    @SerialName("appended_content") val appendedContent: String? = null
)

@Serializable
data class Contradiction(
    @SerialName("target_node_id") val targetNodeId: Int,
    @SerialName("new_content") val newContent: String
)
