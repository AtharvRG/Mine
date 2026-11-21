// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.sensing

import com.anchor.core.alarms.AlarmScheduler
import com.anchor.core.models.AgentWakeup
import com.anchor.core.storage.Storage
import com.anchor.core.time.Clock
import java.util.UUID

data class WalkGreeting(
    val message: String,
    val scheduledReturnWakeupId: String,
    val returnFireAt: Long
)

class WalkCompanionEvaluator(
    private val storage: Storage,
    private val clock: Clock,
    private val alarmScheduler: AlarmScheduler? = null
) {
    /**
     * Called when WALKING is detected and verified with physical movement.
     * Generates a warm walk greeting and arms an AgentWakeup 35 minutes later.
     */
    suspend fun evaluateWalkStarted(tsUtc: Long = clock.now()): WalkGreeting {
        val wakeupId = "aw_walk_${UUID.randomUUID().toString().take(6)}"
        val returnFireAt = tsUtc + 35 * 60_000L
        val wakeup = AgentWakeup(
            id = wakeupId,
            fireAt = returnFireAt,
            reason = "Walk return check-in",
            payloadJson = """{"type":"WALK_RETURN_CHECKIN","started_at":$tsUtc}""",
            state = "pending",
            createdAt = tsUtc
        )
        storage.saveAgentWakeup(wakeup)
        alarmScheduler?.scheduleAgentWakeup(wakeupId, returnFireAt)

        return WalkGreeting(
            message = "Heading out for a walk? Let me know if you need anything, or enjoy your stroll!",
            scheduledReturnWakeupId = wakeupId,
            returnFireAt = returnFireAt
        )
    }

    /**
     * Called when the walk return AgentWakeup fires to welcome the user back if they reached home.
     */
    fun evaluateWalkReturn(
        wakeup: AgentWakeup,
        currentState: ContextState
    ): String? {
        if (currentState.place == PlaceAxis.Home) {
            return "Welcome back from your walk! Hope it was refreshing."
        }
        return null
    }
}
