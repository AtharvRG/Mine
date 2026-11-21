// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.mine.alarms

import android.content.Context
import com.anchor.core.models.AgentWakeup
import com.anchor.core.storage.Storage
import com.anchor.core.time.Clock
import com.anchor.core.time.DayRollover
import com.anchor.core.time.SystemClock
import java.util.Locale
import java.util.UUID

/**
 * P1-13: Schedules proactive anchors (day_start+3h, midday, day_end-90min)
 * running NudgeEngine evaluation when the app is closed.
 */
object ProactiveAnchorScheduler {

    suspend fun scheduleNextAnchors(
        context: Context,
        storage: Storage,
        clock: Clock = SystemClock()
    ) {
        val now = clock.now()
        val dayRule = storage.getEffectiveDayRule(now)
        val dayStart = DayRollover.getLogicalDayStart(now, dayRule)

        val startMin = dayRule.startMinute
        val endMin = if (dayRule.endMinute > startMin) dayRule.endMinute else dayRule.endMinute + 1440

        val morningMs = dayStart + 3 * 3600_000L
        val middayMs = dayStart + (((startMin + endMin) / 2) - startMin) * 60_000L
        val eveningMs = dayStart + (endMin - startMin - 90) * 60_000L

        val candidates = listOf(
            "morning" to morningMs,
            "midday" to middayMs,
            "evening" to eveningMs
        )

        val nextCandidate = candidates.firstOrNull { it.second > now + 60_000L }
            ?: run {
                // If all passed for today, arm tomorrow morning
                val tomorrowStart = dayStart + 86_400_000L
                "morning" to (tomorrowStart + 3 * 3600_000L)
            }

        val phase = nextCandidate.first
        val targetMs = nextCandidate.second

        val pending = storage.getPendingAgentWakeups(targetMs + 10 * 60_000L)
        val alreadyScheduled = pending.any {
            (it.reason.startsWith("DAY_ANCHOR_") || it.payloadJson?.contains("DAY_ANCHOR") == true) &&
                kotlin.math.abs(it.fireAt - targetMs) < 15 * 60_000L
        }
        if (alreadyScheduled) return

        val wakeupId = "wu_anchor_${UUID.randomUUID()}"
        val payload = """{"type":"DAY_ANCHOR","phase":"$phase"}"""
        val wakeup = AgentWakeup(
            id = wakeupId,
            fireAt = targetMs,
            reason = "DAY_ANCHOR_${phase.uppercase(Locale.US)}",
            payloadJson = payload,
            state = "pending",
            createdAt = now
        )
        storage.saveAgentWakeup(wakeup)
        AndroidAlarmScheduler(context).scheduleAgentWakeup(wakeupId, targetMs)
    }
}
