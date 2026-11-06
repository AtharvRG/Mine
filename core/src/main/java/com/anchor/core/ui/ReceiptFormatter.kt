// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.ui

import com.anchor.core.models.NodeStateSnapshot
import com.anchor.core.models.RawEvent
import com.anchor.core.models.Reminder
import com.anchor.core.models.SoulFact
import com.anchor.core.time.DateResolver
import kotlinx.serialization.json.Json

class ReceiptFormatter(private val dateResolver: DateResolver) {
    private val json = Json { ignoreUnknownKeys = true }

    fun format(event: RawEvent): String {
        return try {
            when (event.opType) {
                "CREATE" -> {
                    when (event.targetType) {
                        "node" -> {
                            val snapshot = event.afterJson?.let { json.decodeFromString<NodeStateSnapshot>(it) }
                            val node = snapshot?.node
                            if (node != null) {
                                if (node.dueAt != null) {
                                    "Created: ${node.title} (${dateResolver.format(node.dueAt)})"
                                } else {
                                    "Created: ${node.title}"
                                }
                            } else {
                                "Created: ${event.targetId}"
                            }
                        }
                        "soul_fact" -> {
                            val fact = event.afterJson?.let { json.decodeFromString<SoulFact>(it) }
                            if (fact != null) "Saved to Soul: ${fact.statement}" else "Saved to Soul: ${event.targetId}"
                        }
                        else -> "Created: ${event.targetId}"
                    }
                }
                "CANCEL" -> {
                    val snapshot = (event.beforeJson ?: event.afterJson)?.let { json.decodeFromString<NodeStateSnapshot>(it) }
                    val node = snapshot?.node
                    if (node != null) {
                        if (node.dueAt != null) {
                            "Cancelled: ${node.title} (${dateResolver.format(node.dueAt)})"
                        } else {
                            "Cancelled: ${node.title}"
                        }
                    } else {
                        "Cancelled: ${event.targetId}"
                    }
                }
                "COMPLETE" -> {
                    val snapshot = (event.beforeJson ?: event.afterJson)?.let { json.decodeFromString<NodeStateSnapshot>(it) }
                    val node = snapshot?.node
                    if (node != null) {
                        if (node.dueAt != null) {
                            "Completed: ${node.title} (${dateResolver.format(node.dueAt)})"
                        } else {
                            "Completed: ${node.title}"
                        }
                    } else {
                        "Completed: ${event.targetId}"
                    }
                }
                "UPDATE" -> {
                    // P1-B 16/P1-D 20: applyNodeUpdate writes before/after NodeStateSnapshot; the after-state titles the receipt.
                    val snapshot = (event.afterJson ?: event.beforeJson)?.let { json.decodeFromString<NodeStateSnapshot>(it) }
                    val node = snapshot?.node
                    if (node != null) {
                        if (node.dueAt != null) {
                            "Updated: ${node.title} (${dateResolver.format(node.dueAt)})"
                        } else {
                            "Updated: ${node.title}"
                        }
                    } else {
                        "Updated: ${event.targetId}"
                    }
                }
                "RESCHEDULE" -> {
                    val snapshot = (event.afterJson ?: event.beforeJson)?.let { json.decodeFromString<NodeStateSnapshot>(it) }
                    val node = snapshot?.node
                    if (node != null) {
                        if (node.dueAt != null) {
                            "Rescheduled: ${node.title} to ${dateResolver.format(node.dueAt)}"
                        } else {
                            "Rescheduled: ${node.title}"
                        }
                    } else {
                        "Rescheduled: ${event.targetId}"
                    }
                }
                "KEEP_STALE" -> {
                    val snapshot = (event.beforeJson ?: event.afterJson)?.let { json.decodeFromString<NodeStateSnapshot>(it) }
                    val node = snapshot?.node
                    if (node != null) "Kept: ${node.title}" else "Kept: ${event.targetId}"
                }
                "CANCEL_STALE" -> {
                    val snapshot = (event.beforeJson ?: event.afterJson)?.let { json.decodeFromString<NodeStateSnapshot>(it) }
                    val node = snapshot?.node
                    if (node != null) "Cancelled: ${node.title}" else "Cancelled: ${event.targetId}"
                }
                "SCHEDULE" -> {
                    val rem = event.afterJson?.let { json.decodeFromString<Reminder>(it) }
                    if (rem != null) {
                        "Scheduled reminder: ${rem.text} (${dateResolver.format(rem.fireAtMillis)})"
                    } else {
                        "Scheduled reminder: ${event.targetId}"
                    }
                }
                "DELETE" -> {
                    val rem = (event.beforeJson ?: event.afterJson)?.let { json.decodeFromString<Reminder>(it) }
                    if (rem != null) {
                        "Cancelled reminder: ${rem.text}"
                    } else {
                        "Cancelled reminder: ${event.targetId}"
                    }
                }
                "CONFIRM", "ACCEPT" -> {
                    if (event.targetType == "soul_fact") {
                        val fact = (event.afterJson ?: event.beforeJson)?.let { json.decodeFromString<SoulFact>(it) }
                        if (fact != null) "Saved to Soul: ${fact.statement}" else "Saved to Soul: ${event.targetId}"
                    } else {
                        "Confirmed: ${event.targetId}"
                    }
                }
                "REJECT" -> {
                    if (event.targetType == "soul_fact") {
                        "Dismissed Soul fact"
                    } else {
                        "Rejected: ${event.targetId}"
                    }
                }
                "LINK" -> "Linked follow-up"
                "UNLINK" -> "Removed follow-up link"
                else -> "${event.opType.lowercase().replaceFirstChar { it.uppercase() }}: ${event.targetId}"
            }
        } catch (_: Exception) {
            "${event.opType.lowercase().replaceFirstChar { it.uppercase() }}: ${event.targetId}"
        }
    }
}
