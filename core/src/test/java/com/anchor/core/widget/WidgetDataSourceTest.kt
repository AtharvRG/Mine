// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.widget

import com.anchor.core.SharedFakeStorage
import com.anchor.core.models.Node
import com.anchor.core.models.Reminder
import com.anchor.core.time.FakeClock
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetDataSourceTest {

    @Test
    fun testGetSnapshot_ReturnsPriorityTasksAndAlarmsCount() = runBlocking {
        val clock = FakeClock(100_000L)
        val storage = SharedFakeStorage(clock)
        val dataSource = WidgetDataSource(storage, clock = { clock.now() })

        val n1 = Node("n1", "task", "Urgent presentation prep", "active", dueAt = 90_000L, priority = 3)
        val n2 = Node("n2", "task", "Buy coffee beans", "active", dueAt = 120_000L, priority = 1)
        storage.saveNode(n1)
        storage.saveNode(n2)

        storage.saveReminder(Reminder("r1", "Presentation alarm", 90_000L, nodeId = "n1"))
        storage.saveReminder(Reminder("r2", "Coffee alarm", 120_000L, nodeId = "n2"))

        val snapshot = dataSource.getSnapshot(limit = 5)

        assertEquals("Snapshot must reflect 2 priority tasks", 2, snapshot.priorityTasks.size)
        assertEquals("n1", snapshot.priorityTasks[0].id)
        assertTrue("Past due task must be marked overdue", snapshot.priorityTasks[0].isOverdue)
        assertEquals("r2 is ahead of 100_000, so pending alarms count is 1", 1, snapshot.pendingAlarmsCount)
        assertEquals(100_000L, snapshot.timestampMs)
    }
}
