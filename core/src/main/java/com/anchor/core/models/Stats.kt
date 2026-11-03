// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.models

data class ReliabilityState(
    val lastCheckAt: Long? = null,
    val score: String? = null,
    val issuesJson: String? = null,
    val sweeperHeartbeat: Long? = null,
    val p95LatencyMs: Int? = null
)

data class ReminderFire(
    val id: String,
    val reminderId: String,
    val firedAt: Long,
    val scheduledFor: Long,
    val latencyMs: Int,
    val outcome: String
)
