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
        // R-26: the read is paged, so one extra row is requested: a full page alone cannot
        // distinguish "exactly a page" from "more than a page". The second case is rows this pass
        // cannot see, and it must be reported rather than treated as a clean reconcile.
        val page = storage.getPendingReminders(Storage.PENDING_REMINDER_PAGE_LIMIT + 1)
        val truncated = page.size > Storage.PENDING_REMINDER_PAGE_LIMIT
        val pending = if (truncated) page.subList(0, Storage.PENDING_REMINDER_PAGE_LIMIT) else page
        val registered = storage.getRegisteredAlarmRecords()

        val pendingMap = pending.associateBy { it.id }
        val registeredMap = registered.associateBy { it.reminderId }

        // 1. Cancel and delete orphans (present in registry but no longer in pending)
        val toCancel = registeredMap.keys - pendingMap.keys
        for (orphanId in toCancel) {
            scheduler.cancel(orphanId)
            storage.deleteRegisteredAlarm(orphanId)
        }

        // 2. Schedule pending reminders within 60s grace; report the rest as overdue (H-01/R-01).
        //    Dropping them silently left a reminder that came due while the device was off with no
        //    alarm and no report unless the boot/app-open sweeper happened to run.
        val results = mutableListOf<ScheduleResult>()
        val validPending = mutableListOf<com.anchor.core.models.Reminder>()
        for (reminder in pending) {
            if (reminder.fireAtMillis >= now - GRACE_MS) {
                validPending.add(reminder)
                continue
            }
            // A registration for a past instant can never fire; clear it so it is not left stale.
            if (registeredMap.containsKey(reminder.id)) {
                scheduler.cancel(reminder.id)
                storage.deleteRegisteredAlarm(reminder.id)
            }
            // Dedupe on the fire instance: the same reminder id may miss twice after a reschedule.
            // R-54: an "errored" fire counts too — AlarmReceiver's fallback posts a notification
            // when storage is unreadable and records "errored" for this same instance; reporting it
            // overdue as well would surface a second notification for one fire.
            val lastMiss = storage.getReminderFire(reminder.id, "missed")
            val errored = storage.getReminderFire(reminder.id, "errored")
            val alreadyReported = lastMiss?.scheduledFor == reminder.fireAtMillis ||
                errored?.scheduledFor == reminder.fireAtMillis
            if (!alreadyReported) {
                results.add(
                    ScheduleResult.Overdue(
                        reminderId = reminder.id,
                        text = reminder.text,
                        scheduledFor = reminder.fireAtMillis,
                        overdueByMs = now - reminder.fireAtMillis
                    )
                )
            }
        }

        for (reminder in validPending) {
            val reg = registeredMap[reminder.id]
            val expectedApi = if (reminder.tier == com.anchor.core.models.AlarmTier.GENTLE) "rtc_wakeup" else "alarm_clock"
            val needsSchedule = force || reg == null || reg.registeredFor != reminder.fireAtMillis || reg.api != expectedApi
            if (needsSchedule) {
                val result = scheduler.schedule(reminder)
                results.add(result)
                if (result is ScheduleResult.Armed) {
                    storage.saveRegisteredAlarm(reminder.id, reminder.id.hashCode(), reminder.fireAtMillis, expectedApi)
                }
            }
        }

        // 3. Recompute reliability state. R-26: a truncated page is a failure of this pass, not a
        //    clean one — it is appended to the results the caller reads, and from there folded into
        //    the reliability issues below.
        if (truncated) {
            results.add(ScheduleResult.Failed(ISSUE_PENDING_TRUNCATED))
        }
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

            storage.saveReliabilityState(
                currentState.copy(
                    score = "at_risk",
                    issuesJson = encodeIssues(boundedIssues),
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

    private fun parseExistingIssues(jsonStr: String?): List<ReliabilityIssue> =
        ReliabilityIssueCodec.parse(jsonStr)

    companion object {
        /** How far past its fire instant a reminder is still armed rather than reported overdue. */
        const val GRACE_MS = 60000L

        /** R-26: the pending read returned a full page, so an unseen tail was not reconciled. */
        const val ISSUE_PENDING_TRUNCATED = "pending_reminders_truncated"

        fun encodeIssues(issues: List<ReliabilityIssue>): String = ReliabilityIssueCodec.encode(issues)
        fun parseIssues(jsonStr: String?): List<ReliabilityIssue> = ReliabilityIssueCodec.parse(jsonStr)
    }
}

/**
 * B3: Shared codec for the reliability_state.issues_json column so all writers
 * (AlarmReconciler, ChatController.reconcileAlarms) use one structured format:
 * a JSON array of {error, time} objects, bounded to 5 entries.
 */
object ReliabilityIssueCodec {
    fun encode(issues: List<ReliabilityIssue>): String = Json.encodeToString(
        ListSerializer(ReliabilityIssue.serializer()),
        issues
    )

    fun parse(jsonStr: String?): List<ReliabilityIssue> {
        if (jsonStr.isNullOrBlank()) return emptyList()
        return try {
            val element = Json.parseToJsonElement(jsonStr)
            when (element) {
                is JsonArray -> element.mapNotNull { item ->
                    try {
                        Json.decodeFromJsonElement(ReliabilityIssue.serializer(), item)
                    } catch (_: Exception) {
                        // Legacy plain-string entry (pre-B3 ChatController format):
                        // keep it as an issue with time 0 rather than silently dropping it.
                        (item as? JsonPrimitive)?.let { ReliabilityIssue(it.content, 0L) }
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
