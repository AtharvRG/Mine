// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine Author
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.models

/**
 * Represents a discrete item in the user's memory graph (task, event, etc.)
 */
data class Node(
    val id: String,
    val type: String, // "task", "event", etc.
    val title: String,
    val status: String, // "active", "done", "cancelled"
    val dueAt: Long? = null // Epoch millis
)

enum class IntentType {
    CREATE, UPDATE, COMPLETE, CANCEL, RESCHEDULE, PAUSE, RESUME
}

sealed interface Resolution {
    data class ClearWinner(val node: Node) : Resolution
    data class Ambiguous(val options: List<Node>) : Resolution
    data object NotFound : Resolution
}

data class ChatMessage(
    val id: String,
    val role: String, // "system", "user", "assistant"
    val content: String
)