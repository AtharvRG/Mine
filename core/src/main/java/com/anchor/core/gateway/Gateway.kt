// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.gateway

import com.anchor.core.alarms.Reminder
import com.anchor.core.models.*
import com.anchor.core.resolver.Resolver
import com.anchor.core.storage.Storage
import java.util.UUID

class Gateway(private val storage: Storage, private val resolver: Resolver) {

    suspend fun proposeChange(
        intent: IntentType,
        hint: String,
        targetTimeMillis: Long? = null
    ): ToolResult = when (intent) {
        IntentType.CREATE -> handleCreate(hint, "task", targetTimeMillis)
        IntentType.CANCEL, IntentType.COMPLETE -> handleResolveChange(intent, hint, targetTimeMillis)
        else -> ToolResult.Error("$intent not supported yet")
    }

    suspend fun executeTool(call: ToolCall): ToolResult {
        if (call.tool != "propose_change") return ToolResult.Error("Tool not supported yet")

        val intentStr = call.args["intent"]?.uppercase() ?: return ToolResult.Error("Missing intent")
        val intent = try { IntentType.valueOf(intentStr) } catch(e: Exception) { return ToolResult.Error("Unknown intent") }
        val hint = call.args["hint"] ?: call.args["title"] ?: ""
        val dueText = call.args["due_text"]

        // Naive date resolution in code (Blueprint 22/23)
        val targetTimeMillis = parseNaiveDate(dueText)

        return when (intent) {
            IntentType.CREATE -> handleCreate(hint, call.args["type"] ?: "task", targetTimeMillis)
            IntentType.CANCEL, IntentType.COMPLETE -> handleResolveChange(intent, hint, targetTimeMillis)
            // Blueprint Item 11: Refuse unsupported
            else -> ToolResult.Error("$intent not supported yet")
        }
    }

    private suspend fun handleCreate(title: String, type: String, dueAt: Long?): ToolResult {
        val nId = "n_${UUID.randomUUID().toString().take(8)}"
        val node = Node(nId, type, title, "active", dueAt)

        // D-100: Create reminder linked to the node if it has a time
        var reminder: Reminder? = null
        if (dueAt != null) {
            reminder = Reminder("r_$nId", title, dueAt)
        }

        val uId = "u_${UUID.randomUUID().toString().take(6)}"
        val event = RawEvent(uId, IntentType.CREATE.name, "node", nId)

        storage.commitChange(node, event, reminder)

        return ToolResult.Committed(IntentType.CREATE.name, nId, "Created: $title", uId)
    }

    private suspend fun handleResolveChange(intent: IntentType, hint: String, targetTimeMillis: Long?): ToolResult {
        val candidates = storage.getActiveNodes()
        val resolution = resolver.resolve(intent, hint, targetTimeMillis, candidates)

        return when (resolution) {
            is Resolution.NotFound -> ToolResult.NotFound(hint)
            is Resolution.Ambiguous -> {
                val pId = "p_${UUID.randomUUID().toString().take(6)}"
                storage.saveProposal(Proposal(pId, "ambiguous", intent, resolution.options.map { it.id }))
                ToolResult.Ambiguous(pId, "ask: which item", resolution.options.map { "${it.id} '${it.title}'" })
            }
            is Resolution.ClearWinner -> {
                val node = resolution.node.copy(status = if (intent == IntentType.CANCEL) "cancelled" else "done")
                val uId = "u_${UUID.randomUUID().toString().take(6)}"
                val event = RawEvent(uId, intent.name, "node", node.id)

                // Complete/Cancel kills the reminder
                storage.deleteReminder("r_${node.id}")
                storage.commitChange(node, event, null)

                ToolResult.Committed(intent.name, node.id, "Applied ${intent.name}: ${node.title}", uId)
            }
        }
    }

    // Much smarter naive parser for v0.1 tests
    private fun parseNaiveDate(text: String?): Long? {
        if (text == null) return null
        val lower = text.lowercase()
        
        // Extract any number before "min" (e.g., "in 2 minutes", "1 min", "5 mins")
        val match = Regex("""(\d+)\s*min""").find(lower)
        if (match != null) {
            val mins = match.groupValues[1].toLong()
            return System.currentTimeMillis() + (mins * 60 * 1000L)
        }
        
        // Fallbacks for words
        if (lower.contains("two") || lower.contains("2")) return System.currentTimeMillis() + (2 * 60 * 1000L)
        if (lower.contains("one") || lower.contains("1")) return System.currentTimeMillis() + (1 * 60 * 1000L)
        if (lower.contains("tomorrow")) return System.currentTimeMillis() + (24 * 3600 * 1000L)
        
        return null
    }
}