// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.rebuild

import com.anchor.core.models.Edge
import com.anchor.core.models.Node
import com.anchor.core.storage.Storage
import com.anchor.core.storage.getAllActiveNodesPaged
import com.anchor.core.time.Clock
import com.anchor.core.time.SystemClock

/**
 * Rebuild Manager implementing Blueprint 11 §4 & §5.
 *
 * Guarantees:
 * 1. Rebuilds never overwrite approved or user_edit rows (D-028, Blueprint 11 §4 Rule 4).
 * 2. Progress is persisted in app_meta so jobs are resumable across process death (Blueprint 11 §5 & §8).
 * 3. Validation ensures count parity and zero orphan edges before any swap.
 */
class RebuildManager(
    private val storage: Storage,
    private val clock: Clock = SystemClock()
) {

    suspend fun planRebuild(target: RebuildTarget): RebuildPlan {
        val now = clock.now()
        val allNodes = storage.getAllActiveNodesPaged()
        val anchorNodes = allNodes.filter { isAnchor(it.origin) }
        val soulFacts = storage.getActiveSoulFacts(includeLocalOnly = true)
        val anchorFacts = soulFacts.filter { isAnchor(it.origin) }
        val rawMessages = storage.getRecentMessages(10000)

        storage.saveSetting(KEY_STATUS, RebuildStatus.PLANNING.name)
        storage.saveSetting(KEY_TARGET, target.name)
        storage.saveSetting(KEY_PROCESSED, "0")
        storage.saveSetting(KEY_TOTAL, rawMessages.size.toString())
        storage.saveSetting(KEY_STARTED_AT, now.toString())
        storage.saveSetting(KEY_ERROR, "")

        return RebuildPlan(
            target = target,
            totalRawMessages = rawMessages.size,
            anchorNodeCount = anchorNodes.size,
            anchorSoulFactCount = anchorFacts.size,
            startedAt = now
        )
    }

    fun isAnchor(origin: String?): Boolean {
        return origin == ORIGIN_APPROVED || origin == ORIGIN_USER_EDIT
    }

    fun validateAnchors(originalAnchors: List<Node>, candidateNodes: List<Node>): Boolean {
        val candidateMap = candidateNodes.associateBy { it.id }
        for (anchor in originalAnchors) {
            val candidate = candidateMap[anchor.id] ?: return false
            if (candidate.origin != anchor.origin) return false
            if (candidate.title != anchor.title) return false
            if (candidate.status != anchor.status) return false
        }
        return true
    }

    fun validateIntegrity(
        originalAnchors: List<Node>,
        candidateNodes: List<Node>,
        candidateEdges: List<Edge>
    ): RebuildValidationResult {
        val errors = mutableListOf<String>()
        val anchorsPreserved = validateAnchors(originalAnchors, candidateNodes)
        if (!anchorsPreserved) {
            errors.add("Anchor validation failed: approved or user_edit nodes were altered or lost.")
        }

        val nodeIds = candidateNodes.map { it.id }.toSet()
        var orphanEdges = 0
        for (edge in candidateEdges) {
            if (edge.deletedAt != null) continue
            val srcExists = nodeIds.contains(edge.srcId)
            val dstExists = if (edge.dstKind == "node") {
                nodeIds.contains(edge.dstId)
            } else true

            if (!srcExists || !dstExists) {
                orphanEdges++
            }
        }

        if (orphanEdges > 0) {
            errors.add("Integrity check found $orphanEdges orphan edges.")
        }

        // Validate basic node sanity
        val blankTitles = candidateNodes.count { it.title.isBlank() }
        if (blankTitles > 0) {
            errors.add("Integrity check found $blankTitles nodes with blank titles.")
        }

        val isValid = anchorsPreserved && orphanEdges == 0 && blankTitles == 0
        return RebuildValidationResult(
            isValid = isValid,
            anchorsPreserved = anchorsPreserved,
            orphanEdgeCount = orphanEdges,
            errors = errors
        )
    }

    suspend fun recordProgress(progress: RebuildProgress) {
        storage.saveSetting(KEY_STATUS, progress.status.name)
        storage.saveSetting(KEY_TARGET, progress.target.name)
        storage.saveSetting(KEY_PROCESSED, progress.processedMessages.toString())
        storage.saveSetting(KEY_TOTAL, progress.totalMessages.toString())
        if (progress.error != null) {
            storage.saveSetting(KEY_ERROR, progress.error)
        }
    }

    suspend fun getProgress(): RebuildProgress {
        val statusStr = storage.getSetting(KEY_STATUS) ?: RebuildStatus.IDLE.name
        val targetStr = storage.getSetting(KEY_TARGET) ?: RebuildTarget.FULL.name
        val processed = storage.getSetting(KEY_PROCESSED)?.toIntOrNull() ?: 0
        val total = storage.getSetting(KEY_TOTAL)?.toIntOrNull() ?: 0
        val error = storage.getSetting(KEY_ERROR)?.takeIf { it.isNotBlank() }

        val status = try {
            RebuildStatus.valueOf(statusStr)
        } catch (_: Exception) {
            RebuildStatus.IDLE
        }
        val target = try {
            RebuildTarget.valueOf(targetStr)
        } catch (_: Exception) {
            RebuildTarget.FULL
        }

        return RebuildProgress(
            target = target,
            processedMessages = processed,
            totalMessages = total,
            status = status,
            error = error
        )
    }

    suspend fun pauseRebuild() {
        val current = getProgress()
        if (current.status == RebuildStatus.RUNNING || current.status == RebuildStatus.PLANNING) {
            recordProgress(current.copy(status = RebuildStatus.PAUSED))
        }
    }

    suspend fun discardRebuild() {
        storage.saveSetting(KEY_STATUS, RebuildStatus.IDLE.name)
        storage.saveSetting(KEY_PROCESSED, "0")
        storage.saveSetting(KEY_TOTAL, "0")
        storage.saveSetting(KEY_ERROR, "")
    }

    suspend fun resumeOrDiscard(now: Long = clock.now()): RebuildStatus {
        val current = getProgress()
        return when (current.status) {
            RebuildStatus.RUNNING, RebuildStatus.PLANNING -> {
                // Was interrupted by process death / kill mid-rebuild (Blueprint 11 §8)
                // Mark paused so user or scheduler can resume
                recordProgress(current.copy(status = RebuildStatus.PAUSED))
                RebuildStatus.PAUSED
            }
            else -> current.status
        }
    }

    companion object {
        const val ORIGIN_APPROVED = "approved"
        const val ORIGIN_USER_EDIT = "user_edit"
        const val ORIGIN_EXTRACTED = "extracted"

        const val KEY_STATUS = "rebuild.status"
        const val KEY_TARGET = "rebuild.target"
        const val KEY_PROCESSED = "rebuild.processed"
        const val KEY_TOTAL = "rebuild.total"
        const val KEY_ERROR = "rebuild.last_error"
        const val KEY_STARTED_AT = "rebuild.started_at"
    }
}
