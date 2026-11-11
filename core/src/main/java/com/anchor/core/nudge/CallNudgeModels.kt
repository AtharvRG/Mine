// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.nudge

/**
 * Operational, device-local representation of a completed phone call.
 * Blueprint 44 §4.
 *
 * Privacy Invariants:
 * - Zero audio recorded or buffered.
 * - Zero phone numbers recorded or stored.
 * - [contactRef] holds only a contact display name when know_contact is enabled.
 * - Device-local only, excluded from exports, pruned after 30 days.
 */
data class CallEvent(
    val id: String,
    val startedAt: Long,
    val endedAt: Long,
    val durationS: Int,
    val direction: String? = null,
    val contactRef: String? = null,
    val nudgeId: String? = null,
    val state: CallNudgeState = CallNudgeState.PENDING,
    val createdAt: Long = endedAt
)

enum class CallNudgeState(val wire: String) {
    PENDING("pending"),
    NUDGED("nudged"),
    ANSWERED("answered"),
    SKIPPED("skipped"),
    EXPIRED("expired"),
    IGNORED("ignored");

    companion object {
        fun fromWire(value: String): CallNudgeState =
            entries.firstOrNull { it.wire.equals(value, ignoreCase = true) } ?: PENDING
    }
}

/**
 * Configuration tuning for call nudges.
 * Blueprint 44 §5.
 */
data class CallNudgeConfig(
    val minDurationS: Int = 45,
    val delayS: Int = 20,
    val coalesceMin: Int = 10,
    val expireH: Int = 3,
    val voiceAction: Boolean = true,
    val eveningRecap: Boolean = true,
    val knowContact: Boolean = false
)

/**
 * Outcomes of evaluating or processing a call event in [CallNudgeEngine].
 */
sealed interface CallNudgeDecision {
    data class ScheduleAfterDelay(val eventId: String, val fireAt: Long) : CallNudgeDecision
    data class CoalesceWithExisting(val eventId: String, val existingId: String, val newFireAt: Long) : CallNudgeDecision
    data class DropShortCall(val eventId: String, val durationS: Int) : CallNudgeDecision
    data class ReadyToNudge(val eventId: String) : CallNudgeDecision
    data class Held(val eventId: String, val reason: String) : CallNudgeDecision
    data class DropExpired(val eventId: String) : CallNudgeDecision
}
