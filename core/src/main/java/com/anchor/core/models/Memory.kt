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
    val closedAt: Long? = null,
    val priority: Int = 0,
    val severity: Int = 0,
    val pinned: Boolean = false,
    val localOnly: Boolean = false,
    val sensitive: Boolean = false
)

@Serializable
data class NodeCounts(
    val totalOpen: Int,
    val overdue: Int
)

@Serializable
enum class AlarmTier {
    ALARM,
    GENTLE
}

@Serializable
data class Reminder(
    val id: String,
    val text: String,
    val fireAtMillis: Long,
    val nodeId: String? = null, // D5: Explicit relational tie to the Node
    val tier: AlarmTier = AlarmTier.ALARM
)

@Serializable
data class RegisteredAlarm(
    val reminderId: String,
    val registeredFor: Long,
    val api: String = "alarm_clock"
)

object EdgeTypes {
    const val FOLLOW_UP = "follow_up"
}

@Serializable
data class Edge(
    val id: String,
    val srcId: String,
    val dstId: String,
    val type: String = EdgeTypes.FOLLOW_UP,
    val dstKind: String = "node",
    val weight: Double = 1.0,
    val source: String = "user",
    val origin: String = "manual",
    val sourceMsgIds: String? = null,
    val attrs: String? = null,
    val hlc: String? = null,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val deletedAt: Long? = null
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
    val content: String?, // Null means redacted
    val dayKey: String? = null,
    val tsUtc: Long = 0L
)

@Serializable
data class ProposalOption(val id: String, val title: String, val dueTime: String? = null)

@Serializable
data class ProposalPayload(
    val targetAtMillis: Long? = null,
    val title: String? = null,
    val type: String? = null,
    val followUpTo: String? = null,
    val tier: AlarmTier? = null,
    val intent: IntentType? = null,
    val hint: String? = null,
    val priority: Int? = null,
    val severity: Int? = null,
    val pinned: Boolean? = null,
    val targetId: String? = null,
    val untitled: Boolean = false,
    val label: String? = null,
    val newTitle: String? = null
)

@Serializable
data class Proposal(
    val id: String,
    val kind: String,
    val intent: IntentType,
    val options: List<ProposalOption>,
    val status: String = "open",
    val payload: String? = null
)

@Serializable
data class DayRule(
    val id: String,
    val effectiveFrom: Long,
    val startMinute: Int,
    val endMinute: Int = 1320,
    val tzId: String,
    val createdAt: Long
)

@Serializable
data class SoulFact(
    val id: String,
    val category: String,
    val statement: String,
    val confidence: Double,
    val source: String,
    val origin: String,
    val status: String,
    val createdAt: Long,
    val localOnly: Boolean = false,
    val sensitive: Boolean = false
)

@Serializable
data class SearchResult(
    val id: String,
    val kind: String,
    val title: String,
    val details: String,
    /** Epoch ms of the underlying row, when the kind has a meaningful time (chat chunks). */
    val tsUtc: Long? = null
)
