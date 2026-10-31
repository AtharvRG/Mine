// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.reliability

import com.anchor.core.SharedFakeStorage
import com.anchor.core.models.Reminder
import com.anchor.core.time.FakeClock
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class ReliabilityManagerTest {

    @Test
    fun testSweeper_CatchesMissedReminders_AndUpdatesHeartbeat() = runBlocking {
        val storage = SharedFakeStorage()
        val clock = FakeClock(100000L)
        val manager = ReliabilityManager(storage, clock)

        // Reminder scheduled for 80,000L (20 seconds ago) -> Within grace period, ignored by sweeper
        storage.saveReminder(Reminder("r_grace", "Just passed", 80000L))
        // Reminder scheduled for 10,000L (90 seconds ago) -> Past grace period, MUST be swept
        storage.saveReminder(Reminder("r_missed", "Way past", 10000L))
        // Reminder scheduled for 150,000L (future) -> Ignored
        storage.saveReminder(Reminder("r_future", "Future", 150000L))

        val missed = manager.runSweeper()

        // Verify r_missed was swept and logged
        assertEquals(1, missed.size)
        assertEquals("r_missed", missed[0].id)
        assertEquals(3, storage.reminders.size)
        assertEquals(1, storage.reminderFires.size)
        assertEquals("missed", storage.reminderFires[0].outcome)
        assertEquals("r_missed", storage.reminderFires[0].reminderId)
        
        // Verify heartbeat updated
        assertEquals(100000L, storage.reliabilityState.sweeperHeartbeat)
    }
}
