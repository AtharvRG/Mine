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
import org.junit.Assert.assertTrue
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

        manager.runSweeper()

        // Verify r_missed was swept and logged, BUT NOT DELETED BY SWEEPER!
        // The AlarmReceiver deletes it, so it should still exist in storage right now.
        org.junit.Assert.assertEquals(3, storage.reminders.size)
        
        org.junit.Assert.assertEquals(1, storage.reminderFires.size)
        org.junit.Assert.assertEquals("missed", storage.reminderFires[0].outcome)
        org.junit.Assert.assertEquals("r_missed", storage.reminderFires[0].reminderId)

        org.junit.Assert.assertEquals(100000L, storage.reliabilityState.sweeperHeartbeat)
    }

    @Test
    fun testSweeper_DoesNotLogSameMissedReminderTwice() = runBlocking {
        val storage = SharedFakeStorage()
        val clock = FakeClock(100000L)
        val manager = ReliabilityManager(storage, clock)

        storage.saveReminder(Reminder("r_missed", "Way past", 10000L))

        val sweptFirst = manager.runSweeper()
        assertEquals(1, sweptFirst.size)
        assertEquals(1, storage.reminderFires.size)

        // Second run: must not re-log or re-notify
        val sweptSecond = manager.runSweeper()
        assertEquals("Must not sweep already-handled missed reminder", 0, sweptSecond.size)
        assertEquals("Must not create duplicate fire log", 1, storage.reminderFires.size)
    }

    @Test
    fun testH4_SecondDistinctMiss_ForSameReminderId_IsNotSwallowed() = runBlocking {
        // H4: a reminder rescheduled on the same node keeps its id ("r_<nodeId>"), so the same
        // reminder id can legitimately miss twice at two different fire times. Deduping on
        // (reminderId, "missed") alone silently swallows the second miss: no fire row, no
        // "Missed:" notification.
        val storage = SharedFakeStorage()
        val clock = FakeClock(100000L)
        val manager = ReliabilityManager(storage, clock)

        storage.saveReminder(Reminder("r_missed", "Way past", 10000L))
        val firstSweep = manager.runSweeper()
        assertEquals("First miss must be swept", 1, firstSweep.size)
        assertEquals(1, storage.reminderFires.size)

        // The user reschedules the same node: same reminder id, new fire time. It is then missed
        // again - the AlarmReceiver never ran, so the row is still pending.
        clock.currentTime += 200000L
        storage.saveReminder(Reminder("r_missed", "Way past", 150000L))

        val secondSweep = manager.runSweeper()
        assertEquals("Second distinct miss must be swept", 1, secondSweep.size)
        assertEquals("Second distinct miss must be logged", 2, storage.reminderFires.size)
        assertTrue("Both misses must carry their own scheduledFor",
            storage.reminderFires.map { it.scheduledFor }.containsAll(listOf(10000L, 150000L)))
    }

    @Test
    fun testH4_SameFireTime_IsStillDeduped() = runBlocking {
        // Guard: dedupe must still hold for the same fire instance. A sweeper that runs twice
        // before the receiver deletes the row must not double-log or double-notify.
        val storage = SharedFakeStorage()
        val clock = FakeClock(100000L)
        val manager = ReliabilityManager(storage, clock)

        storage.saveReminder(Reminder("r_missed", "Way past", 10000L))
        assertEquals(1, manager.runSweeper().size)

        clock.currentTime += 5000L
        assertEquals("Same fire instance must not re-notify", 0, manager.runSweeper().size)
        assertEquals("Same fire instance must not re-log", 1, storage.reminderFires.size)
    }
}
