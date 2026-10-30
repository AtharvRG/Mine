// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.nudge

import com.anchor.core.storage.Storage
import com.anchor.core.time.Clock

class CheckinBuilder(private val storage: Storage, private val clock: Clock) {
    
    // BP 32 (3.2): Code computes the facts block so the LLM doesn't hallucinate metrics.
    suspend fun buildMorningFacts(): String {
        val nodes = storage.getActiveNodes()
        val now = clock.now()
        
        val dueToday = nodes.filter { it.dueAt != null && Math.abs(it.dueAt - now) < 86400000L }
        val overdue = nodes.filter { it.dueAt != null && it.dueAt < now - 86400000L }
        
        val builder = java.lang.StringBuilder()
        builder.append("FACTS:\n")
        builder.append("- Due Today: ${dueToday.size}\n")
        if (overdue.isNotEmpty()) builder.append("- Overdue: ${overdue.size}\n")
        
        if (dueToday.isNotEmpty()) {
            builder.append("\nAgenda:\n")
            dueToday.sortedBy { it.dueAt }.take(5).forEach { 
                builder.append("  * ${it.title}\n")
            }
        } else {
            builder.append("\nAgenda: Empty\n")
        }
        
        return builder.toString()
    }
}
