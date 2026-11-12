// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.alarms

import com.anchor.core.models.ReminderFire
import com.anchor.core.recurrence.RecurrenceEngine
import com.anchor.core.storage.Storage
import java.util.UUID

/**
 * Reconciles alarms that fired during Direct Boot (when Credential-Encrypted storage was locked)
 * with the decrypted CE database once the user unlocks the device.
 *
 * Blueprint 42 & Roadmap P1.1 Item 2:
 * Prevents duplicate missed notifications by retiring pending reminders and recording
 * a delivered [ReminderFire] entry in CE storage for every alarm fired under direct boot,
 * while advancing recurring reminders to their next occurrence.
 */
object DirectBootReconciler {

    data class FiredRecord(
        val id: String,
        val firedAt: Long,
        val scheduledFor: Long
    )

    suspend fun reconcileFiredAlarms(
        storage: Storage,
        records: List<FiredRecord>,
        alarmScheduler: AlarmScheduler? = null
    ) {
        for (rec in records) {
            val latency = (rec.firedAt - rec.scheduledFor).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
            storage.recordReminderFire(
                ReminderFire(
                    id = "f_${UUID.randomUUID()}",
                    reminderId = rec.id,
                    firedAt = rec.firedAt,
                    scheduledFor = rec.scheduledFor,
                    latencyMs = latency,
                    outcome = "delivered"
                )
            )
            val rem = storage.getReminderById(rec.id)
            if (rem != null && !rem.rrule.isNullOrBlank()) {
                val nextFire = RecurrenceEngine.nextOccurrence(rem.rrule, rem.fireAtMillis, rec.firedAt)
                val nextRem = rem.copy(
                    fireAtMillis = nextFire,
                    iteration = rem.iteration + 1
                )
                storage.saveReminder(nextRem)
                alarmScheduler?.schedule(nextRem)
            } else {
                storage.deleteReminder(rec.id)
            }
        }
    }
}
