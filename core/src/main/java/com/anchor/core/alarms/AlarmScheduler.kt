// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.alarms

import com.anchor.core.models.Reminder
import kotlinx.coroutines.flow.StateFlow

sealed class ScheduleResult {
    data object Armed : ScheduleResult()
    data class Failed(val reason: String) : ScheduleResult()
}

interface AlarmScheduler {
    val lastFiredId: StateFlow<String?>
    fun schedule(reminder: Reminder): ScheduleResult
    fun cancel(reminderId: String)
}
