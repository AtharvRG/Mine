// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.alarms

import com.anchor.core.models.Reminder
import kotlinx.coroutines.flow.StateFlow

sealed class ScheduleResult {
    data object Armed : ScheduleResult()
    data class Failed(val reason: String) : ScheduleResult()

    /**
     * H-01 (R-01): a pending reminder whose fire instant is already more than the grace period in
     * the past. It is NOT re-armed (arming a past instant fires immediately); it is reported so the
     * caller can tell the user it was missed. [overdueByMs] is always > 0.
     */
    data class Overdue(
        val reminderId: String,
        val text: String,
        val scheduledFor: Long,
        val overdueByMs: Long
    ) : ScheduleResult()
}

interface AlarmScheduler {
    val lastFiredId: StateFlow<String?>
    fun schedule(reminder: Reminder): ScheduleResult
    fun cancel(reminderId: String)
    fun checkHealth(): ScheduleResult = ScheduleResult.Armed
    fun scheduleAgentWakeup(wakeupId: String, fireAtMillis: Long): ScheduleResult = ScheduleResult.Armed
    fun cancelAgentWakeup(wakeupId: String) {}
}
