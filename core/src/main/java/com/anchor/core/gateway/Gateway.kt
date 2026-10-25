// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine Author
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.gateway

import com.anchor.core.models.IntentType
import com.anchor.core.models.Proposal
import com.anchor.core.models.RawEvent
import com.anchor.core.models.Resolution
import com.anchor.core.models.ToolResult
import com.anchor.core.resolver.Resolver
import com.anchor.core.storage.Storage
import kotlinx.coroutines.withContext
import java.util.UUID

class Gateway(
    private val storage: Storage,
    private val resolver: Resolver
) {
    suspend fun proposeChange(
        intent: IntentType,
        hint: String,
        targetTimeMillis: Long? = null
    ): ToolResult = withContext(storage.ioDispatcher) {

        val candidates = storage.getActiveNodes()
        val resolution = resolver.resolve(intent, hint, targetTimeMillis, candidates)

        when (resolution) {
            is Resolution.NotFound -> ToolResult.NotFound(hint)

            is Resolution.Ambiguous -> {
                // Generate a short per-turn alias, e.g., "p1"
                val pId = "p_${UUID.randomUUID().toString().take(6)}"
                val proposal = Proposal(pId, "ambiguous", intent, resolution.options.map { it.id })
                storage.saveProposal(proposal)

                // Return string representation the LLM can read
                ToolResult.Ambiguous(
                    proposalId = pId,
                    message = "ask: which item",
                    options = resolution.options.map { "${it.id} '${it.title}'" }
                )
            }

            is Resolution.ClearWinner -> {
                val node = resolution.node
                val newStatus = when (intent) {
                    IntentType.CANCEL -> "cancelled"
                    IntentType.COMPLETE -> "done"
                    else -> "active" // Simplified for now
                }

                storage.updateNodeStatus(node.id, newStatus)

                // Generate undo token (the raw event op_id)
                val uId = "u_${UUID.randomUUID().toString().take(6)}"
                val event = RawEvent(opId = uId, opType = intent.name, targetType = "node", targetId = node.id)
                storage.logEvent(event)

                ToolResult.Committed(
                    op = intent.name.lowercase(),
                    targetId = node.id,
                    message = "Applied ${intent.name}: ${node.title}",
                    undoToken = uId
                )
            }
        }
    }
}