// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.reliability

import com.anchor.core.SharedFakeStorage
import com.anchor.core.models.Reminder
import com.anchor.core.time.FakeClock
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun testEvaluateReliabilityScore_CleanState_ReturnsHealthy() = runBlocking {
        val clock = FakeClock(100000L)
        val storage = SharedFakeStorage(clock)
        val manager = ReliabilityManager(storage, clock)

        storage.saveReliabilityState(com.anchor.core.models.ReliabilityState(sweeperHeartbeat = 100000L))
        val state = manager.evaluateReliabilityScore()
        assertEquals("healthy", state.score)
    }

    @Test
    fun testEvaluateReliabilityScore_HighLatency_ReturnsAtRisk() = runBlocking {
        val clock = FakeClock(100000L)
        val storage = SharedFakeStorage(clock)
        val manager = ReliabilityManager(storage, clock)

        storage.saveReliabilityState(com.anchor.core.models.ReliabilityState(sweeperHeartbeat = 100000L))
        // Add 20 fires with 150s (150,000ms) latency > 2 min threshold
        for (i in 1..20) {
            storage.recordReminderFire(
                com.anchor.core.models.ReminderFire(
                    id = "f_$i",
                    reminderId = "r_$i",
                    firedAt = 100000L,
                    scheduledFor = 100000L - 150000L,
                    latencyMs = 150000,
                    outcome = "fired"
                )
            )
        }

        val state = manager.evaluateReliabilityScore()
        assertEquals("at_risk", state.score)
        assertEquals(150000, state.p95LatencyMs)
    }

    @Test
    fun testEvaluateReliabilityScore_MissedReminders_ReturnsDegraded() = runBlocking {
        val clock = FakeClock(100000L)
        val storage = SharedFakeStorage(clock)
        val manager = ReliabilityManager(storage, clock)

        storage.saveReliabilityState(com.anchor.core.models.ReliabilityState(sweeperHeartbeat = 100000L))
        storage.recordReminderFire(
            com.anchor.core.models.ReminderFire(
                id = "f_miss",
                reminderId = "r_1",
                firedAt = 100000L,
                scheduledFor = 50000L,
                latencyMs = 50000,
                outcome = "missed"
            )
        )

        val state = manager.evaluateReliabilityScore()
        assertEquals("degraded", state.score)
    }

    @Test
    fun testComputeP95LatencyMs_Empty_ReturnsNull() = runBlocking {
        val clock = FakeClock(100000L)
        val storage = SharedFakeStorage(clock)
        val manager = ReliabilityManager(storage, clock)

        val p95 = manager.computeP95LatencyMs()
        org.junit.Assert.assertNull(p95)
    }

    @Test
    fun testComputeP95LatencyMs_ComputesPercentileCorrectly() = runBlocking {
        val clock = FakeClock(100000L)
        val storage = SharedFakeStorage(clock)
        val manager = ReliabilityManager(storage, clock)

        // Add 20 fires with latencies 1..20 seconds (1000..20000 ms)
        for (i in 1..20) {
            storage.recordReminderFire(
                com.anchor.core.models.ReminderFire(
                    id = "f_$i",
                    reminderId = "r_$i",
                    firedAt = 100000L,
                    scheduledFor = 100000L - (i * 1000L),
                    latencyMs = i * 1000,
                    outcome = "delivered"
                )
            )
        }

        val p95 = manager.computeP95LatencyMs(20)
        // 95th percentile of 20 items: index 19 (the 20th item) = 20000 ms
        assertEquals(20000, p95)
    }

    @Test
    fun testDetectStartupGap_StaleHeartbeatAndOverdueReminder_ReturnsTrue() = runBlocking {
        val clock = FakeClock(100000L + 50 * 60_000L) // 50 mins after 100000L
        val storage = SharedFakeStorage(clock)
        val manager = ReliabilityManager(storage, clock)

        // Heartbeat was at 100000L (50 mins ago > 45 mins)
        storage.saveReliabilityState(com.anchor.core.models.ReliabilityState(sweeperHeartbeat = 100000L))
        // Pending reminder was due at 100000L + 10 mins (40 mins ago, way overdue)
        storage.saveReminder(Reminder("r_overdue", "Call mom", 100000L + 10 * 60_000L))

        val gapDetected = manager.detectStartupGap()
        assertTrue("Force stop gap must be detected when heartbeat is stale and reminder is overdue", gapDetected)
    }

    @Test
    fun testDetectStartupGap_FreshHeartbeat_ReturnsFalse() = runBlocking {
        val clock = FakeClock(100000L + 5 * 60_000L) // 5 mins after 100000L
        val storage = SharedFakeStorage(clock)
        val manager = ReliabilityManager(storage, clock)

        // Heartbeat was at 100000L (only 5 mins ago)
        storage.saveReliabilityState(com.anchor.core.models.ReliabilityState(sweeperHeartbeat = 100000L))
        storage.saveReminder(Reminder("r_overdue", "Call mom", 100000L + 2 * 60_000L))

        val gapDetected = manager.detectStartupGap()
        assertFalse("No gap when heartbeat is fresh", gapDetected)
    }

    @Test
    fun testSweeper_PrunesExpiredOperationalLogs() = runBlocking {
        val now = 100_000_000_000L
        val clock = FakeClock(now)
        val storage = SharedFakeStorage(clock)
        val manager = ReliabilityManager(storage, clock)

        // Seed old reminder fire (95 days old) and recent reminder fire (5 days old)
        val day = 86_400_000L
        storage.recordReminderFire(com.anchor.core.models.ReminderFire("rf_old", "r_old", now - (95 * day), now - (95 * day), 0, "delivered"))
        storage.recordReminderFire(com.anchor.core.models.ReminderFire("rf_new", "r_new", now - (5 * day), now - (5 * day), 0, "delivered"))

        manager.runSweeper()

        // r_old should be pruned, r_new should remain
        assertEquals(1, storage.reminderFires.size)
        assertEquals("r_new", storage.reminderFires[0].reminderId)
    }
}
