// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.nudge

import com.anchor.core.models.GateResult
import com.anchor.core.models.NudgeCandidate
import com.anchor.core.models.ProactiveState
import com.anchor.core.time.Clock
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

class NudgeEngine(private val clock: Clock) {

    // BP 31 (3.3): Threshold formula T0 * max(0.3, 1 - hours/4)
    fun calculateThreshold(state: ProactiveState): Double {
        val lastContact = state.lastContactAt ?: return 30.0 * 0.3 // Default low if never contacted
        val hoursSince = (clock.now() - lastContact) / (1000.0 * 3600.0)
        return 30.0 * max(0.3, 1.0 - (hoursSince / 4.0))
    }

    // BP 31 (3.6): Gap = min(60 * 2^step, 360)
    fun calculateGapMinutes(state: ProactiveState): Long {
        val gap = 60.0 * 2.0.pow(state.backoffStep.toDouble())
        return min(gap, 360.0).toLong()
    }

    fun evaluate(candidate: NudgeCandidate, state: ProactiveState, isUserPresent: Boolean = false, isInDayWindow: Boolean = true): GateResult {
        // 1. Paused
        if (state.pausedUntil != null && state.pausedUntil > clock.now()) {
            if (!candidate.isCritical) return GateResult.Held("paused")
        }
        
        // 2. Day Window
        if (!isInDayWindow && !candidate.isCritical) {
            return GateResult.Held("outside_window")
        }

        // 3. Open Thread
        if (state.openThreadMsgId != null && !candidate.isCritical) {
            return GateResult.Held("open_thread")
        }

        // 4. Minimum Gap
        val gapMs = calculateGapMinutes(state) * 60 * 1000L
        if (state.lastSentAt != null && clock.now() - state.lastSentAt < gapMs) {
            if (!candidate.isCritical) return GateResult.Held("min_gap")
        }

        // 5. Hidden Fuse (e.g., max 40 per day)
        if (state.sentToday >= 40) return GateResult.Held("fuse_tripped")

        return GateResult.Pass
    }

    fun onNudgeSent(state: ProactiveState, msgId: String, ts: Long): ProactiveState {
        return state.copy(
            openThreadMsgId = msgId,
            threadOpenedAt = ts,
            lastSentAt = ts,
            sentToday = state.sentToday + 1
        )
    }

    fun processIgnore(state: ProactiveState, ts: Long): ProactiveState {
        val opened = state.threadOpenedAt ?: return state
        // BP 31 (3.6): Ignore window is 3 hours
        if (ts - opened >= 3 * 3600 * 1000L) {
            return state.copy(
                openThreadMsgId = null,
                backoffStep = min(state.backoffStep + 1, 5) // Cap step to prevent overflow
            )
        }
        return state
    }

    fun processReply(state: ProactiveState, ts: Long): ProactiveState {
        return state.copy(
            openThreadMsgId = null,
            backoffStep = 0,
            lastContactAt = ts,
            silentDays = 0
        )
    }
}
