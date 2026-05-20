package com.mine.app.ai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class UpdateMemoryGraphArgs(
    @SerialName("discard_transcript") val discardTranscript: Boolean = false,
    @SerialName("memory_operations") val memoryOperations: List<MemoryOperation> = emptyList(),
    @SerialName("trigger_nudge") val triggerNudge: Boolean = false,
    @SerialName("nudge_message") val nudgeMessage: String? = null
)

@Serializable
data class MemoryOperation(
    @SerialName("action") val action: String, // "create", "update", "contradict", "relate", "delete"
    @SerialName("content") val content: String? = null, // Nullable because 'delete' doesn't need content
    @SerialName("branch") val branch: String? = null,
    @SerialName("confidence") val confidence: Float? = null,
    @SerialName("target_node_id") val targetNodeId: Int? = null,
    @SerialName("edge_label") val edgeLabel: String? = null, // "depends_on", "sub_task_of", "blocks", "related_to"
    @SerialName("nudge_message") val nudgeMessage: String? = null // <-- NEW: Catch it if the AI puts it here
)

