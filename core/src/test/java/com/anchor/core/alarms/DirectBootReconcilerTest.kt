// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.alarms

import com.anchor.core.SharedFakeStorage
import com.anchor.core.models.AlarmTier
import com.anchor.core.models.Reminder
import com.anchor.core.reliability.ReliabilityManager
import com.anchor.core.time.Clock
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectBootReconcilerTest {

    private class TestClock(var currentTime: Long) : Clock {
        override fun now(): Long = currentTime
    }

    @Test
    fun testDirectBootReconciler_ReconcilesFiredAlarms_AndSuppressesDuplicateSweeperNotification() = runBlocking {
        val storage = SharedFakeStorage()
        val clock = TestClock(200_000L)
        val reliabilityManager = ReliabilityManager(storage, clock)

        // Reminder scheduled for 80_000L
        val rem = Reminder(
            id = "rem_1",
            text = "Take morning medicine",
            fireAtMillis = 80_000L,
            tier = AlarmTier.ALARM
        )
        storage.saveReminder(rem)

        // Verify that without reconciliation, sweeper would detect it as missed
        // (80_000 < 200_000 - 60_000 = 140_000)
        assertEquals(1, storage.getPendingReminders().size)

        // Now simulate DirectBootReconciler running after phone unlock
        val firedRecords = listOf(
            DirectBootReconciler.FiredRecord(
                id = "rem_1",
                firedAt = 80_500L,
                scheduledFor = 80_000L
            )
        )
        DirectBootReconciler.reconcileFiredAlarms(storage, firedRecords)

        // 1. Pending reminder must be deleted
        assertNull("Pending reminder must be deleted after direct boot fire", storage.getReminderById("rem_1"))
        assertTrue("Pending reminders list must be empty", storage.getPendingReminders().isEmpty())

        // 2. Delivered fire record must be written
        val deliveredFire = storage.getReminderFire("rem_1", "delivered")
        assertNotNull("Delivered fire record must exist", deliveredFire)
        assertEquals("rem_1", deliveredFire?.reminderId)
        assertEquals(80_500L, deliveredFire?.firedAt)
        assertEquals(80_000L, deliveredFire?.scheduledFor)
        assertEquals(500, deliveredFire?.latencyMs)
        assertEquals("delivered", deliveredFire?.outcome)

        // 3. Sweeper must NOT detect any missed reminders (no duplicate notification!)
        val missed = reliabilityManager.runSweeper()
        assertTrue("Sweeper must return 0 missed reminders after direct boot reconciliation", missed.isEmpty())
    }

    @Test
    fun testDirectBootReconciler_HandlesEmptyOrUnknownAlarmsGracefully() = runBlocking {
        val storage = SharedFakeStorage()

        // Empty list does nothing
        DirectBootReconciler.reconcileFiredAlarms(storage, emptyList())

        // Unknown reminder ID records the fire record safely without throwing
        val unknownRecord = listOf(
            DirectBootReconciler.FiredRecord(
                id = "unknown_rem",
                firedAt = 100_000L,
                scheduledFor = 99_000L
            )
        )
        DirectBootReconciler.reconcileFiredAlarms(storage, unknownRecord)

        val fire = storage.getReminderFire("unknown_rem", "delivered")
        assertNotNull(fire)
        assertEquals(1000, fire?.latencyMs)
    }
}
