// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.reliability

import com.anchor.core.storage.Storage
import com.anchor.core.time.Clock
import com.anchor.core.models.ReminderFire
import java.util.UUID

class ReliabilityManager(
    private val storage: Storage,
    private val clock: Clock
) {
    // BP 42 (3.6): The Sweeper job. Finds armed reminders with fire_at in the past
    // and handles them as missed (recording the latency and cleaning up).
    suspend fun runSweeper() {
        val now = clock.now()
        val pending = storage.getPendingReminders()
        
        // 60-second grace period before officially calling it "missed" by the sweeper,
        // to avoid racing with the actual AlarmReceiver if the system is slightly lagging.
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
            
            // Note: In the real Android adapter, we would also trigger a 
            // "Missed: <text>" local notification here.
        }
        
        // Update heartbeat so the UI knows background work is still alive
        val state = storage.getReliabilityState()
        storage.saveReliabilityState(state.copy(sweeperHeartbeat = now))
    }
}
