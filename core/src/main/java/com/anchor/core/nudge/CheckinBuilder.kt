// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.nudge

import com.anchor.core.storage.Storage
import com.anchor.core.time.Clock
import com.anchor.core.time.DateResolver

class CheckinBuilder(private val storage: Storage, private val clock: Clock, private val dateResolver: DateResolver) {
    
    suspend fun buildMorningFacts(): String {
        val nodes = storage.getActiveNodes()
        val now = clock.now()
        
        val rolloverMin = storage.getSetting("day_start_minute")?.toIntOrNull() ?: 240
        val logicalTodayStart = dateResolver.getLogicalDayStart(now, rolloverMin)
        val logicalTodayEnd = logicalTodayStart + 86400000L // +24 hours
        
        // B14: Use strict logical calendar day boundaries, not a rolling +/- 24h window
        val dueToday = nodes.filter { it.dueAt != null && it.dueAt in logicalTodayStart until logicalTodayEnd }
        val overdue = nodes.filter { it.dueAt != null && it.dueAt < logicalTodayStart }
        
        val builder = java.lang.StringBuilder()
        builder.append("Today's Facts:\n")
        builder.append("- Due Today: ${dueToday.size}\n")
        if (overdue.isNotEmpty()) builder.append("- Overdue: ${overdue.size}\n")
        
        if (dueToday.isNotEmpty()) {
            builder.append("\nAgenda:\n")
            dueToday.sortedBy { it.dueAt }.take(5).forEach { 
                builder.append("  * ${it.title} (Due: ${dateResolver.format(it.dueAt!!)})\n")
            }
        }
        
        return builder.toString()
    }
}
