// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.rebuild

import kotlinx.serialization.Serializable

/**
 * Rebuild targets supported by Blueprint 11 §5.
 */
@Serializable
enum class RebuildTarget {
    DERIVED_NODES,
    DERIVED_EDGES,
    EMBEDDINGS,
    FTS,
    FULL
}

/**
 * Rebuild execution status tracked in app_meta (Blueprint 11 §5 & §8).
 */
@Serializable
enum class RebuildStatus {
    IDLE,
    PLANNING,
    RUNNING,
    VALIDATING,
    COMPLETED,
    FAILED,
    PAUSED
}

/**
 * Plan for a rebuild operation.
 */
@Serializable
data class RebuildPlan(
    val target: RebuildTarget,
    val totalRawMessages: Int,
    val anchorNodeCount: Int,
    val anchorSoulFactCount: Int,
    val startedAt: Long
)

/**
 * Progress snapshot of a rebuild operation, resumable across app restarts.
 */
@Serializable
data class RebuildProgress(
    val target: RebuildTarget,
    val processedMessages: Int,
    val totalMessages: Int,
    val status: RebuildStatus,
    val error: String? = null
)

/**
 * Result of validating rebuilt candidate data against Blueprint 11 rules.
 */
@Serializable
data class RebuildValidationResult(
    val isValid: Boolean,
    val anchorsPreserved: Boolean,
    val orphanEdgeCount: Int,
    val errors: List<String> = emptyList()
)
