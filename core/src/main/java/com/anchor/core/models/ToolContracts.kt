// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.models

sealed interface ToolResult {
    data class Committed(val op: String, val targetId: String, val message: String, val undoToken: String) : ToolResult
    data class Ambiguous(val proposalId: String, val message: String, val options: List<String>) : ToolResult
    data class NeedsConfirm(val proposalId: String, val message: String, val options: List<String>) : ToolResult
    data class NeedsTime(val proposalId: String, val chipLabels: List<String>) : ToolResult
    data class NotFound(val hint: String) : ToolResult
    data class Error(val reason: String) : ToolResult
    data class Data(val text: String) : ToolResult
}

data class RawEvent(
    val opId: String,
    val opType: String,
    val targetType: String,
    val targetId: String,
    val undoOf: String? = null,
    val beforeJson: String? = null,
    val afterJson: String? = null,
    val tsUtc: Long = 0L, // B16: Track time for Undo expiration
    val causeMsgId: String? = null
)
