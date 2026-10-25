// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine Author
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.models

sealed interface ToolResult {
    data class Committed(val op: String, val targetId: String, val message: String, val undoToken: String) : ToolResult
    data class Ambiguous(val proposalId: String, val message: String, val options: List<String>) : ToolResult
    data class NotFound(val hint: String) : ToolResult
    data class Error(val reason: String) : ToolResult
}

data class Proposal(
    val id: String, // e.g., "p1"
    val kind: String, // "ambiguous", "needs_confirm"
    val intent: IntentType,
    val optionsIds: List<String>
)

data class RawEvent(
    val opId: String,
    val opType: String,
    val targetType: String,
    val targetId: String,
    val undoOf: String? = null
)