// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.widget

import com.anchor.core.models.Node
import com.anchor.core.models.Reminder
import com.anchor.core.storage.Storage

data class WidgetTaskItem(
    val id: String,
    val title: String,
    val type: String,
    val dueAt: Long?,
    val isOverdue: Boolean
)

data class WidgetSnapshot(
    val priorityTasks: List<WidgetTaskItem>,
    val pendingAlarmsCount: Int,
    val timestampMs: Long
)

/**
 * Blueprint 50 §241 / Roadmap v1.1 Item 7:
 * Data source providing prioritized tasks and upcoming alarm counts for home screen widgets.
 */
class WidgetDataSource(
    private val storage: Storage,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    suspend fun getSnapshot(limit: Int = 5): WidgetSnapshot {
        val now = clock()
        val priorityNodes = storage.getHomePriorityList(now, limit)
        val pendingReminders = storage.getPendingReminders(limit = 50)
            .filter { it.fireAtMillis > now }

        val taskItems = priorityNodes.map { node ->
            val due = node.dueAt
            WidgetTaskItem(
                id = node.id,
                title = node.title,
                type = node.type,
                dueAt = due,
                isOverdue = due != null && due < now
            )
        }

        return WidgetSnapshot(
            priorityTasks = taskItems,
            pendingAlarmsCount = pendingReminders.size,
            timestampMs = now
        )
    }
}
