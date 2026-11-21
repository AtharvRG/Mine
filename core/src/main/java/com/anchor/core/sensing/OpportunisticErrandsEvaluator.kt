// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.sensing

import com.anchor.core.models.Node
import com.anchor.core.storage.Storage
import com.anchor.core.time.Clock
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

data class ErrandNudge(
    val nodeId: String,
    val title: String,
    val checklist: List<String>,
    val message: String
)

class OpportunisticErrandsEvaluator(
    private val storage: Storage,
    private val clock: Clock
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Called when the user legitimately leaves home (CONTEXT_LEFT_HOME).
     * Surfaces active opportunistic errands and attaches checklists from linked topic notes.
     */
    suspend fun evaluateLeaveHome(now: Long = clock.now()): List<ErrandNudge> {
        val activeNodes = storage.getActiveNodes(50)
        val errands = activeNodes.filter {
            it.contextTrigger == "ON_LEAVE_HOME" && it.status == "active"
        }

        return errands.map { errand ->
            val refs = storage.getReferencesForNode(errand.id)
            val notes = refs.mapNotNull { storage.getTopicNote(it.noteId) }
            val noteBullets = notes.flatMap { note ->
                try {
                    json.decodeFromString<List<String>>(note.bulletsJson)
                } catch (_: Exception) {
                    note.content.lines().map { l -> l.removePrefix("-").removePrefix("*").trim() }.filter { it.isNotBlank() }
                }
            }

            val message = if (noteBullets.isNotEmpty()) {
                "Heading out? Don't forget '${errand.title}':\n" + noteBullets.joinToString("\n") { "• $it" }
            } else {
                "Heading out? Don't forget your errand: '${errand.title}'"
            }

            ErrandNudge(
                nodeId = errand.id,
                title = errand.title,
                checklist = noteBullets,
                message = message
            )
        }
    }
}
