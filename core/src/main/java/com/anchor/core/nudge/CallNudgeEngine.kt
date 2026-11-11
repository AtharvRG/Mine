// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.nudge

import com.anchor.core.models.GateResult
import com.anchor.core.models.NudgeCandidate
import com.anchor.core.models.ProactiveState
import com.anchor.core.storage.Storage
import com.anchor.core.time.Clock
import java.util.UUID

/**
 * Pure Kotlin engine managing post-call nudge detection, coalescing, gating, and learning stops.
 * Blueprint 44.
 *
 * Invariants:
 * - Pure Kotlin in `:core` (zero Android framework dependencies).
 * - Zero call audio recorded or buffered.
 * - Zero phone numbers recorded or stored.
 * - Minimum duration filter (< 45s ignored).
 * - Coalesces rapid calls within 10 minutes into 1 post-call candidate.
 * - Drops candidates after 3 hours without nagging.
 * - 3 consecutive ignored calls triggers a preference prompt.
 */
class CallNudgeEngine(
    private val storage: Storage,
    private val clock: Clock,
    private val nudgeEngine: NudgeEngine,
    private val configProvider: () -> CallNudgeConfig = { CallNudgeConfig() }
) {

    suspend fun onCallEnded(
        startedAt: Long,
        endedAt: Long,
        direction: String? = null,
        contactRef: String? = null
    ): CallNudgeDecision {
        val config = configProvider()
        val durationS = ((endedAt - startedAt) / 1000).toInt()
        val eventId = UUID.randomUUID().toString()

        // 1. Minimum duration filter (Blueprint 44 §3.1)
        if (durationS < config.minDurationS) {
            val shortEvent = CallEvent(
                id = eventId,
                startedAt = startedAt,
                endedAt = endedAt,
                durationS = durationS,
                direction = direction,
                contactRef = contactRef,
                state = CallNudgeState.IGNORED,
                createdAt = clock.now()
            )
            storage.saveCallEvent(shortEvent)
            return CallNudgeDecision.DropShortCall(eventId, durationS)
        }

        // 2. Coalescing window check (Blueprint 44 §3.2 / §3.3)
        val coalesceMs = config.coalesceMin * 60 * 1000L
        val recentEvents = storage.getRecentCallEvents(limit = 10)
        val existingPending = recentEvents.firstOrNull {
            it.state == CallNudgeState.PENDING && (endedAt - it.endedAt) <= coalesceMs
        }

        if (existingPending != null) {
            // Merge with existing pending candidate; extend fire time
            val newFireAt = endedAt + (config.delayS * 1000L)
            val mergedEvent = existingPending.copy(
                endedAt = endedAt,
                durationS = existingPending.durationS + durationS
            )
            storage.saveCallEvent(mergedEvent)
            return CallNudgeDecision.CoalesceWithExisting(
                eventId = eventId,
                existingId = existingPending.id,
                newFireAt = newFireAt
            )
        }

        // 3. New valid call candidate scheduled after delay_s
        val callEvent = CallEvent(
            id = eventId,
            startedAt = startedAt,
            endedAt = endedAt,
            durationS = durationS,
            direction = direction,
            contactRef = contactRef,
            state = CallNudgeState.PENDING,
            createdAt = clock.now()
        )
        storage.saveCallEvent(callEvent)

        val fireAt = endedAt + (config.delayS * 1000L)
        return CallNudgeDecision.ScheduleAfterDelay(eventId, fireAt)
    }

    suspend fun evaluateCandidate(
        eventId: String,
        state: ProactiveState,
        isUserPresent: Boolean = false,
        isInDayWindow: Boolean = true,
        currentDayKey: String? = null,
        isVehicle: Boolean = false
    ): CallNudgeDecision {
        val config = configProvider()
        val event = storage.getCallEvent(eventId) ?: return CallNudgeDecision.DropExpired(eventId)

        // 1. Expiry check (Blueprint 44 §3.3: default 3 hours)
        val expireMs = config.expireH * 3600 * 1000L
        if (clock.now() - event.endedAt >= expireMs) {
            storage.updateCallEventState(eventId, CallNudgeState.EXPIRED)
            return CallNudgeDecision.DropExpired(eventId)
        }

        // 2. Gate evaluation via NudgeEngine
        val candidate = NudgeCandidate(triggerType = "call_nudge", isCritical = false, baseValue = 35.0)
        return when (val gate = nudgeEngine.evaluate(candidate, state, isUserPresent, isInDayWindow, currentDayKey, isVehicle)) {
            is GateResult.Held -> CallNudgeDecision.Held(eventId, gate.reason)
            is GateResult.Pass -> CallNudgeDecision.ReadyToNudge(eventId)
        }
    }

    suspend fun recordCallTerminalOutcome(
        eventId: String,
        state: CallNudgeState,
        nudgeId: String? = null
    ) {
        val existing = storage.getCallEvent(eventId)
        if (existing != null) {
            storage.updateCallEventState(eventId, state, nudgeId)
        } else {
            // Direct terminal event for synthetic or test events
            storage.saveCallEvent(
                CallEvent(
                    id = eventId,
                    startedAt = clock.now() - 60_000L,
                    endedAt = clock.now(),
                    durationS = 60,
                    nudgeId = nudgeId,
                    state = state,
                    createdAt = clock.now()
                )
            )
        }
    }

    suspend fun shouldPromptPreference(): Boolean {
        // Blueprint 44 §3.3: After 3 call nudges in a row are ignored, ask preference
        val recent = storage.getRecentCallEvents(limit = 20)
            .filter { it.state == CallNudgeState.ANSWERED || it.state == CallNudgeState.SKIPPED || it.state == CallNudgeState.IGNORED }

        if (recent.size < 3) return false
        val lastThree = recent.take(3)
        return lastThree.all { it.state == CallNudgeState.IGNORED }
    }

    suspend fun getUnansweredCallsCount(dayStartUtc: Long, dayEndUtc: Long): Int {
        val config = configProvider()
        val events = storage.getCallEventsBetween(dayStartUtc, dayEndUtc)
        return events.count { it.durationS >= config.minDurationS && it.state != CallNudgeState.ANSWERED }
    }
}
