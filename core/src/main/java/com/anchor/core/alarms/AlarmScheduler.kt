// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine Author
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.alarms

data class Reminder(
    val id: String,
    val text: String,
    val fireAtMillis: Long
)

interface AlarmScheduler {
    fun schedule(reminder: Reminder)
    fun cancel(reminderId: String)
}