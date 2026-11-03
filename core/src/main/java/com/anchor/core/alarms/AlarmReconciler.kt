// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.alarms

import com.anchor.core.models.ReliabilityState
import com.anchor.core.storage.Storage
import com.anchor.core.time.Clock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@Serializable
data class ReliabilityIssue(
    val error: String,
    val time: Long
)

class AlarmReconciler(
    private val storage: Storage,
    private val scheduler: AlarmScheduler,
    private val clock: Clock
) {
    suspend fun reconcile(force: Boolean): List<ScheduleResult> {
        val now = clock.now()
        val pending = storage.getPendingReminders()
        val registered = storage.getRegisteredAlarms()

        val pendingMap = pending.associateBy { it.id }
        val registeredMap = registered.toMap()

        // 1. Cancel and delete orphans (present in registry but no longer in pending)
        val toCancel = registeredMap.keys - pendingMap.keys
        for (orphanId in toCancel) {
            scheduler.cancel(orphanId)
            storage.deleteRegisteredAlarm(orphanId)
        }

        // 2. Schedule pending reminders within 60s grace
        val validPending = pending.filter { it.fireAtMillis >= now - 60000L }
        val results = mutableListOf<ScheduleResult>()

        for (reminder in validPending) {
            val needsSchedule = force || registeredMap[reminder.id] != reminder.fireAtMillis
            if (needsSchedule) {
                val result = scheduler.schedule(reminder)
                results.add(result)
                if (result is ScheduleResult.Armed) {
                    storage.saveRegisteredAlarm(reminder.id, reminder.id.hashCode(), reminder.fireAtMillis)
                }
            }
        }

        // 3. Recompute reliability state
        val failures = results.filterIsInstance<ScheduleResult.Failed>()
        val currentState = storage.getReliabilityState()
        if (failures.isNotEmpty()) {
            val existing = parseExistingIssues(currentState.issuesJson)
            val updatedIssues = mutableListOf<ReliabilityIssue>()
            val newErrors = failures.map { it.reason }.toSet()
            for (issue in existing) {
                if (issue.error !in newErrors) {
                    updatedIssues.add(issue)
                }
            }
            val distinctFailures = failures.distinctBy { it.reason }
            for (f in distinctFailures) {
                updatedIssues.add(ReliabilityIssue(error = f.reason, time = now))
            }
            val boundedIssues = updatedIssues
                .sortedByDescending { it.time }
                .take(5)

            val issuesJsonStr = Json.encodeToString(
                ListSerializer(ReliabilityIssue.serializer()),
                boundedIssues
            )
            storage.saveReliabilityState(
                currentState.copy(
                    score = "at_risk",
                    issuesJson = issuesJsonStr,
                    lastCheckAt = now
                )
            )
        } else {
            // Clean pass: recover score to healthy and clear issues
            storage.saveReliabilityState(
                currentState.copy(
                    score = "healthy",
                    issuesJson = null,
                    lastCheckAt = now
                )
            )
        }

        return results
    }

    private fun parseExistingIssues(jsonStr: String?): List<ReliabilityIssue> {
        if (jsonStr.isNullOrBlank()) return emptyList()
        return try {
            val element = Json.parseToJsonElement(jsonStr)
            when (element) {
                is JsonArray -> {
                    element.mapNotNull { item ->
                        try {
                            Json.decodeFromJsonElement(ReliabilityIssue.serializer(), item)
                        } catch (_: Exception) {
                            null
                        }
                    }
                }
                is JsonObject -> {
                    val list = mutableListOf<ReliabilityIssue>()
                    var curr: JsonObject? = element
                    while (curr != null) {
                        val error = (curr["error"] as? JsonPrimitive)?.content
                        val time = (curr["time"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L
                        if (error != null) {
                            list.add(ReliabilityIssue(error, time))
                        }
                        curr = curr["previous"] as? JsonObject
                    }
                    list
                }
                else -> emptyList()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
