// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.models

data class ProactiveState(
    val openThreadMsgId: String? = null,
    val threadOpenedAt: Long? = null,
    val backoffStep: Int = 0,
    val lastSentAt: Long? = null,
    val lastContactAt: Long? = null,
    val pausedUntil: Long? = null,
    val silentDays: Int = 0,
    val sentToday: Int = 0,
    val fuseTrippedAt: Long? = null,
    val dayKey: String? = null
)

data class NudgeCandidate(
    val triggerType: String,
    val isCritical: Boolean,
    val baseValue: Double = 35.0 // Medium default
)

sealed interface GateResult {
    data object Pass : GateResult
    data class Held(val reason: String) : GateResult
}
