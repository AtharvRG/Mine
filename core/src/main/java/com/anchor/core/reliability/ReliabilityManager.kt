// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.reliability

import com.anchor.core.alarms.AlarmReconciler
import com.anchor.core.models.ReliabilityState
import com.anchor.core.models.Reminder
import com.anchor.core.models.ReminderFire
import com.anchor.core.storage.Storage
import com.anchor.core.time.Clock
import java.util.UUID

class ReliabilityManager(
    private val storage: Storage,
    private val clock: Clock
) {
    // BP 42 (3.6): The Sweeper job. Finds armed reminders with fire_at in the past
    // and handles them as missed (recording the latency and cleaning up).
    suspend fun runSweeper(): List<Reminder> {
        val now = clock.now()
        val pending = storage.getPendingReminders()
        val missed = pending.filter { it.fireAtMillis < now - 60000L }
        
        val toNotify = mutableListOf<Reminder>()
        
        for (reminder in missed) {
            // SEC 10: Check if we already logged this fire to prevent duplicate sweeping.
            // H4: dedupe on the fire instance, not on (reminderId, "missed") alone. A reminder
            // rescheduled on the same node keeps its id ("r_<nodeId>"), so the same id can miss
            // twice at two different fire times; deduping on the id would swallow the second miss.
            val lastMiss = storage.getReminderFire(reminder.id, "missed")
            if (lastMiss == null || lastMiss.scheduledFor != reminder.fireAtMillis) {
                val fire = ReminderFire(
                    id = "f_${UUID.randomUUID()}",
                    reminderId = reminder.id,
                    firedAt = now,
                    scheduledFor = reminder.fireAtMillis,
                    latencyMs = (now - reminder.fireAtMillis).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(),
                    outcome = "missed"
                )
                storage.recordReminderFire(fire)
                toNotify.add(reminder)
            }
        }
        
        val state = storage.getReliabilityState()
        storage.saveReliabilityState(state.copy(sweeperHeartbeat = now))
        evaluateReliabilityScore()
        
        return toNotify
    }

    /**
     * Blueprint 42 §3.8 & §4: Evaluates the system reliability score ("healthy", "at_risk", "degraded")
     * based on p95 latency over recent fires, missed fires, sweeper heartbeat staleness, and reconciler issues.
     */
    suspend fun evaluateReliabilityScore(): ReliabilityState {
        val now = clock.now()
        val state = storage.getReliabilityState()
        val recentFires = storage.getRecentReminderFires(20)

        val p95Latency = if (recentFires.isNotEmpty()) {
            val latencies = recentFires.map { it.latencyMs.toLong() }.sorted()
            val index = (latencies.size * 0.95).toInt().coerceAtMost(latencies.size - 1)
            latencies[index].toInt()
        } else {
            state.p95LatencyMs
        }

        val hasMissed = recentFires.any { it.outcome == "missed" }
        val isHeartbeatStale = state.sweeperHeartbeat != null && (now - state.sweeperHeartbeat) > 45 * 60_000L
        val existingIssues = AlarmReconciler.parseIssues(state.issuesJson)

        val newScore = when {
            hasMissed -> "degraded"
            existingIssues.isNotEmpty() -> "at_risk"
            (p95Latency != null && p95Latency > 120_000) -> "at_risk"
            isHeartbeatStale -> "at_risk"
            else -> "healthy"
        }

        val updatedState = state.copy(
            lastCheckAt = now,
            score = newScore,
            p95LatencyMs = p95Latency
        )
        storage.saveReliabilityState(updatedState)
        return updatedState
    }

    /**
     * Blueprint 42 §3.8: Computes the p95 latency across recent fires.
     */
    suspend fun computeP95LatencyMs(sampleSize: Int = 20): Int? {
        val recentFires = storage.getRecentReminderFires(sampleSize)
        if (recentFires.isEmpty()) return null
        val latencies = recentFires.map { it.latencyMs.toLong() }.sorted()
        val index = (latencies.size * 0.95).toInt().coerceAtMost(latencies.size - 1)
        return latencies[index].toInt()
    }

    /**
     * Blueprint 42 §3.4: Checks for force-stop gaps upon app startup.
     * A gap is detected if the sweeper heartbeat is stale (> 45 min) and pending reminders are overdue.
     */
    suspend fun detectStartupGap(): Boolean {
        val now = clock.now()
        val state = storage.getReliabilityState()
        val heartbeat = state.sweeperHeartbeat ?: return false
        val isHeartbeatStale = (now - heartbeat) > 45 * 60_000L
        if (!isHeartbeatStale) return false

        val pending = storage.getPendingReminders()
        val hasOverdue = pending.any { it.fireAtMillis < now - 60_000L }
        return hasOverdue
    }
}
