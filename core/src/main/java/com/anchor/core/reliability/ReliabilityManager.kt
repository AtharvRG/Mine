// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.reliability

import com.anchor.core.models.Reminder
import com.anchor.core.models.ReminderFire
import com.anchor.core.storage.Storage
import com.anchor.core.time.Clock
import java.util.UUID

class ReliabilityManager(
    private val storage: Storage,
    private val clock: Clock
) {
    // BP 42 (3.6): The Sweeper job. Finds armed reminders with fire_at in the past
    // and handles them as missed (recording the latency and cleaning up).
    suspend fun runSweeper(): List<Reminder> {
        val now = clock.now()
        val pending = storage.getPendingReminders()
        
        // 60-second grace period before officially calling it "missed" by the sweeper
        val missed = pending.filter { it.fireAtMillis < now - 60000L }
        
        for (reminder in missed) {
            val fire = ReminderFire(
                id = "f_${UUID.randomUUID().toString().take(8)}",
                reminderId = reminder.id,
                firedAt = now,
                scheduledFor = reminder.fireAtMillis,
                latencyMs = (now - reminder.fireAtMillis).toInt(),
                outcome = "missed"
            )
            storage.recordReminderFire(fire)
            storage.deleteReminder(reminder.id)
        }
        
        val state = storage.getReliabilityState()
        storage.saveReliabilityState(state.copy(sweeperHeartbeat = now))
        
        // A14: Return the missed reminders so the Android layer can notify the user BEFORE they vanish
        return missed
    }
}
