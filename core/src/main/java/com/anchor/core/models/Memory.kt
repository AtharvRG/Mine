// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.models

import kotlinx.serialization.Serializable

@Serializable
data class Node(
    val id: String,
    val type: String,
    val title: String,
    val status: String,
    val dueAt: Long? = null,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val closedAt: Long? = null
)

@Serializable
data class Reminder(
    val id: String,
    val text: String,
    val fireAtMillis: Long
)

// Used for JSON serialization in RawEvent
@Serializable
data class NodeStateSnapshot(val node: Node, val reminder: Reminder?)

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
    val content: String? // Null means redacted
)

data class Proposal(
    val id: String,
    val kind: String,
    val intent: IntentType,
    val optionsIds: List<String>,
    val status: String = "open" // Item 1e
)

@Serializable
data class DayRule(
    val id: String,
    val effectiveFrom: Long,
    val startMinute: Int,
    val endMinute: Int,
    val tzId: String,
    val createdAt: Long
)
