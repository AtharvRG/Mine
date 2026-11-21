// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.agent

import com.anchor.core.models.AgentWakeup
import com.anchor.core.storage.Storage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

data class WakeupEvaluationResult(
    val wakeupId: String,
    val shouldNotify: Boolean,
    val notificationText: String?,
    val actionTaken: String,
    val snoozeUntilMs: Long? = null
)

object AgentWakeupEvaluator {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun evaluate(
        wakeup: AgentWakeup,
        storage: Storage,
        now: Long,
        contextEngine: com.anchor.core.sensing.ContextEngine? = null
    ): WakeupEvaluationResult {
        var targetNodeId: String? = null
        var messageIfNotDone: String? = null
        var cancelIfDone = false
        var wakeupType: String? = null
        var startedAt: Long? = null
        var jsonElement: JsonObject? = null

        if (!wakeup.payloadJson.isNullOrBlank()) {
            try {
                val element = json.parseToJsonElement(wakeup.payloadJson)
                if (element is JsonObject) {
                    jsonElement = element
                    wakeupType = element["type"]?.let { (it as? JsonPrimitive)?.content }
                    startedAt = element["started_at"]?.let { (it as? JsonPrimitive)?.content?.toLongOrNull() }
                    targetNodeId = element["node_id"]?.let { (it as? JsonPrimitive)?.content }
                    messageIfNotDone = element["message_if_not_done"]?.let { (it as? JsonPrimitive)?.content }
                    cancelIfDone = element["cancel_if_done"]?.let { (it as? JsonPrimitive)?.booleanOrNull } ?: false
                }
            } catch (_: Exception) {}
        }

        if (wakeupType == "DAY_ANCHOR" || wakeup.reason.startsWith("DAY_ANCHOR_")) {
            val dayRule = storage.getEffectiveDayRule(now)
            val dayKey = com.anchor.core.time.DayRollover.computeDayKey(now, dayRule)
            val inDayWindow = com.anchor.core.time.DayRollover.isInDayWindow(now, dayRule)
            val vehicleHold = contextEngine?.isVehicleHoldActive() ?: false
            val proactiveState = storage.getProactiveState()
            val nudgeEngine = com.anchor.core.nudge.NudgeEngine(object : com.anchor.core.time.Clock {
                override fun now() = now
            })
            val anchorPhase = jsonElement?.get("phase")?.let { (it as? JsonPrimitive)?.content }
                ?: if (wakeup.reason.contains("morning", ignoreCase = true)) "morning"
                else if (wakeup.reason.contains("evening", ignoreCase = true)) "evening"
                else "midday"

            val messageText = when (anchorPhase) {
                "morning" -> "Good morning! Here is what's on your agenda today."
                "evening" -> "Evening wrap-up: ready to review today's achievements?"
                else -> "Midday check-in: how is your day progressing?"
            }
            val candidate = com.anchor.core.models.NudgeCandidate(
                triggerType = "day_anchor_$anchorPhase",
                isCritical = false,
                baseValue = 35.0
            )
            val gate = nudgeEngine.evaluate(
                candidate = candidate,
                state = proactiveState,
                isUserPresent = false,
                isInDayWindow = inDayWindow,
                currentDayKey = dayKey,
                isVehicle = vehicleHold
            )
            return if (gate is com.anchor.core.models.GateResult.Pass) {
                WakeupEvaluationResult(
                    wakeupId = wakeup.id,
                    shouldNotify = true,
                    notificationText = messageText,
                    actionTaken = "notified_day_anchor"
                )
            } else {
                val reason = (gate as? com.anchor.core.models.GateResult.Held)?.reason ?: "held"
                WakeupEvaluationResult(
                    wakeupId = wakeup.id,
                    shouldNotify = false,
                    notificationText = null,
                    actionTaken = "suppressed_nudge_$reason"
                )
            }
        }

        if (wakeupType == "WALK_RETURN_CHECKIN" || wakeup.reason.contains("walk return", ignoreCase = true)) {
            val currentState = contextEngine?.getState() ?: com.anchor.core.sensing.ContextState()
            val greeting = com.anchor.core.sensing.WalkCompanionEvaluator(storage, object : com.anchor.core.time.Clock {
                override fun now() = now
            }).evaluateWalkReturn(wakeup, currentState)

            if (greeting != null) {
                return WakeupEvaluationResult(
                    wakeupId = wakeup.id,
                    shouldNotify = true,
                    notificationText = greeting,
                    actionTaken = "notified"
                )
            } else {
                val walkStart = startedAt ?: wakeup.createdAt
                if (walkStart > 0 && (now - walkStart) >= 2 * 3600_000L) {
                    return WakeupEvaluationResult(
                        wakeupId = wakeup.id,
                        shouldNotify = false,
                        notificationText = null,
                        actionTaken = "cancelled_walk_timeout"
                    )
                } else {
                    return WakeupEvaluationResult(
                        wakeupId = wakeup.id,
                        shouldNotify = false,
                        notificationText = null,
                        actionTaken = "snooze_15m",
                        snoozeUntilMs = now + 15 * 60_000L
                    )
                }
            }
        }

        if (targetNodeId != null) {
            val node = storage.getNode(targetNodeId)
            if (node != null && (node.status == "done" || node.status == "cancelled")) {
                return WakeupEvaluationResult(
                    wakeupId = wakeup.id,
                    shouldNotify = false,
                    notificationText = null,
                    actionTaken = "suppressed_task_already_${node.status}"
                )
            }
        }

        // Check Quiet Hours and Vehicle Hold: Postpone by 30 minutes instead of dropping or notifying
        val dayRule = storage.getEffectiveDayRule(now)
        val inDayWindow = com.anchor.core.time.DayRollover.isInDayWindow(now, dayRule)
        val vehicleHold = contextEngine?.isVehicleHoldActive() ?: false
        if (!inDayWindow || vehicleHold) {
            return WakeupEvaluationResult(
                wakeupId = wakeup.id,
                shouldNotify = false,
                notificationText = null,
                actionTaken = "snooze_30m",
                snoozeUntilMs = now + 30 * 60_000L
            )
        }

        val message = messageIfNotDone ?: wakeup.reason.ifBlank { "Checking in on your plans." }
        return WakeupEvaluationResult(
            wakeupId = wakeup.id,
            shouldNotify = true,
            notificationText = message,
            actionTaken = "notified"
        )
    }
}
