// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.alarms

import com.anchor.core.SharedFakeStorage
import com.anchor.core.models.AlarmTier
import com.anchor.core.models.Reminder
import com.anchor.core.storage.Storage
import com.anchor.core.time.FakeClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmReconcilerTest {

    private class RecordingScheduler(
        var scheduleResultProvider: (Reminder) -> ScheduleResult = { ScheduleResult.Armed }
    ) : AlarmScheduler {
        override val lastFiredId: StateFlow<String?> = MutableStateFlow(null)
        val scheduled = mutableListOf<Reminder>()
        val canceled = mutableListOf<String>()

        override fun schedule(reminder: Reminder): ScheduleResult {
            val result = scheduleResultProvider(reminder)
            if (result is ScheduleResult.Armed) {
                scheduled.add(reminder)
            }
            return result
        }

        override fun cancel(reminderId: String) {
            canceled.add(reminderId)
        }
    }

    @Test
    fun testReconcile_ForceFalse_OnlySchedulesWhenFireTimeChangedOrMissing() = runBlocking {
        val clock = FakeClock(1000L)
        val storage = SharedFakeStorage()
        val scheduler = RecordingScheduler()

        val r1 = Reminder("r1", "Unchanged", 5000L, tier = AlarmTier.ALARM)
        val r2 = Reminder("r2", "New Reminder", 6000L, tier = AlarmTier.GENTLE)
        val r3 = Reminder("r3", "Rescheduled", 7000L, tier = AlarmTier.ALARM)

        storage.saveReminder(r1)
        storage.saveReminder(r2)
        storage.saveReminder(r3)

        // r1 is already registered with matching fire time
        storage.saveRegisteredAlarm("r1", "r1".hashCode(), 5000L)
        // r3 is registered with OLD fire time (e.g., 6500L before reschedule)
        storage.saveRegisteredAlarm("r3", "r3".hashCode(), 6500L)
        // r2 is not registered at all

        val reconciler = AlarmReconciler(storage, scheduler, clock)
        val results = reconciler.reconcile(force = false)

        // r1 should NOT be scheduled
        assertFalse("r1 with identical fire time must not be rescheduled", scheduler.scheduled.any { it.id == "r1" })
        // r2 and r3 MUST be scheduled
        assertTrue("r2 (missing in registry) must be scheduled", scheduler.scheduled.any { it.id == "r2" })
        assertTrue("r3 (changed fire time) must be scheduled", scheduler.scheduled.any { it.id == "r3" })

        // Check registry is updated
        val registeredMap = storage.getRegisteredAlarms().toMap()
        assertEquals(5000L, registeredMap["r1"])
        assertEquals(6000L, registeredMap["r2"])
        assertEquals(7000L, registeredMap["r3"])
    }

    @Test
    fun testReconcile_TierMismatch_ReschedulesAndUpdatesApi() = runBlocking {
        val clock = FakeClock(1000L)
        val storage = SharedFakeStorage()
        val scheduler = RecordingScheduler()

        // r1 is gentle, but previously registered as alarm_clock
        val r1 = Reminder("r1", "Gentle Task", 5000L, tier = AlarmTier.GENTLE)
        storage.saveReminder(r1)
        storage.saveRegisteredAlarm("r1", "r1".hashCode(), 5000L, api = "alarm_clock")

        val reconciler = AlarmReconciler(storage, scheduler, clock)
        reconciler.reconcile(force = false)

        assertTrue("r1 with tier/api mismatch must be rescheduled", scheduler.scheduled.any { it.id == "r1" })
        val updatedReg = storage.getRegisteredAlarmRecords().find { it.reminderId == "r1" }
        assertEquals("rtc_wakeup", updatedReg?.api)
    }

    @Test
    fun testReconcile_ForceFalse_CancelsOrphans() = runBlocking {
        val clock = FakeClock(1000L)
        val storage = SharedFakeStorage()
        val scheduler = RecordingScheduler()

        // Registered alarm exists, but no pending reminder exists in storage
        storage.saveRegisteredAlarm("r_orphan", "r_orphan".hashCode(), 8000L)

        val reconciler = AlarmReconciler(storage, scheduler, clock)
        reconciler.reconcile(force = false)

        assertTrue("Orphan must be cancelled on scheduler", scheduler.canceled.contains("r_orphan"))
        assertFalse("Orphan must be removed from storage registry", storage.getRegisteredAlarms().any { it.first == "r_orphan" })
    }

    @Test
    fun testReconcile_ForceTrue_ReschedulesAllValidPending() = runBlocking {
        val clock = FakeClock(1000L)
        val storage = SharedFakeStorage()
        val scheduler = RecordingScheduler()

        val r1 = Reminder("r1", "Task 1", 5000L)
        val r2 = Reminder("r2", "Task 2", 6000L)
        storage.saveReminder(r1)
        storage.saveReminder(r2)

        // Both are registered with matching fire times
        storage.saveRegisteredAlarm("r1", "r1".hashCode(), 5000L)
        storage.saveRegisteredAlarm("r2", "r2".hashCode(), 6000L)

        val reconciler = AlarmReconciler(storage, scheduler, clock)
        reconciler.reconcile(force = true)

        assertEquals("force=true must reschedule all pending reminders", 2, scheduler.scheduled.size)
        assertTrue(scheduler.scheduled.any { it.id == "r1" })
        assertTrue(scheduler.scheduled.any { it.id == "r2" })
    }

    @Test
    fun testReconcile_GracePeriod_SkipsExpiredPastGrace() = runBlocking {
        val clock = FakeClock(100_000L)
        val storage = SharedFakeStorage()
        val scheduler = RecordingScheduler()

        val rExpired = Reminder("r_exp", "Expired", 30_000L) // > 60s past clock.now
        val rGrace = Reminder("r_grace", "Within Grace", 50_000L) // 50s past clock.now (within 60s grace)
        val rFuture = Reminder("r_fut", "Future", 120_000L)

        storage.saveReminder(rExpired)
        storage.saveReminder(rGrace)
        storage.saveReminder(rFuture)

        val reconciler = AlarmReconciler(storage, scheduler, clock)
        reconciler.reconcile(force = true)

        assertFalse("Expired past 60s grace must not be scheduled", scheduler.scheduled.any { it.id == "r_exp" })
        assertTrue("Within grace must be scheduled", scheduler.scheduled.any { it.id == "r_grace" })
        assertTrue("Future reminder must be scheduled", scheduler.scheduled.any { it.id == "r_fut" })
    }

    @Test
    fun testReconcile_OverduePastGrace_IsReportedNotDropped() = runBlocking {
        // H-01 (R-01): a reminder that came due while the device was off must not vanish from
        // reconcile. It is not re-armed (arming a past instant fires immediately), it is reported.
        val clock = FakeClock(1_000_000L)
        val storage = SharedFakeStorage()
        val scheduler = RecordingScheduler()

        val rOverdue = Reminder("r_overdue", "Missed while off", 400_000L) // 10 minutes overdue
        storage.saveReminder(rOverdue)

        val reconciler = AlarmReconciler(storage, scheduler, clock)
        val results = reconciler.reconcile(force = true)

        assertFalse("An overdue reminder must not be re-armed in the past", scheduler.scheduled.any { it.id == "r_overdue" })
        assertEquals("The overdue reminder must be reported exactly once", 1, results.size)
        val overdue = results.single() as ScheduleResult.Overdue
        assertEquals("r_overdue", overdue.reminderId)
        assertEquals("Missed while off", overdue.text)
        assertEquals(400_000L, overdue.scheduledFor)
        assertEquals(600_000L, overdue.overdueByMs)
        assertEquals("A reported miss is not a scheduling failure", "healthy", storage.getReliabilityState().score)
    }

    @Test
    fun testReconcile_OverdueRegisteredAlarm_IsCancelledAndReported() = runBlocking {
        val clock = FakeClock(1_000_000L)
        val storage = SharedFakeStorage()
        val scheduler = RecordingScheduler()

        val rOverdue = Reminder("r_overdue", "Missed while off", 400_000L)
        storage.saveReminder(rOverdue)
        storage.saveRegisteredAlarm("r_overdue", "r_overdue".hashCode(), 400_000L)

        val reconciler = AlarmReconciler(storage, scheduler, clock)
        val results = reconciler.reconcile(force = false)

        assertTrue("A stale registration for a past instant must be cancelled", scheduler.canceled.contains("r_overdue"))
        assertFalse("A stale registration must leave the registry", storage.getRegisteredAlarms().any { it.first == "r_overdue" })
        assertTrue("The overdue reminder must still be reported", results.any { it is ScheduleResult.Overdue })
    }

    @Test
    fun testReconcile_OverdueReportedOncePerFireInstance() = runBlocking {
        // The same reminder id can miss twice after a reschedule; dedupe on the fire instance.
        val clock = FakeClock(1_000_000L)
        val storage = SharedFakeStorage()
        val scheduler = RecordingScheduler()
        storage.saveReminder(Reminder("r1", "Missed", 400_000L))
        val reconciler = AlarmReconciler(storage, scheduler, clock)

        // The boot/app-open sweeper already recorded this instance as missed; reconcile must not
        // report a reminder the user has already been told about.
        storage.recordReminderFire(
            com.anchor.core.models.ReminderFire("f_seed", "r1", 900_000L, 400_000L, 500_000, "missed")
        )
        assertEquals("An instance the sweeper already reported is not re-reported", 0, reconciler.reconcile(force = false).count { it is ScheduleResult.Overdue })

        // Rescheduled to a new instance that also misses: a different fire, so it is reported.
        storage.saveReminder(Reminder("r1", "Missed", 950_000L))
        clock.currentTime = 1_100_000L
        assertEquals("A rescheduled instance that also misses is reported", 1, reconciler.reconcile(force = false).count { it is ScheduleResult.Overdue })
    }

    @Test
    fun testReconcile_OverdueErroredInstance_IsNotReportedAgain() = runBlocking {
        // R-54: when AlarmReceiver's fallback cannot read storage it still posts a notification
        // and records outcome="errored" for this fire instance. Reconcile must treat that as
        // "already surfaced" — otherwise the user gets a second notification for one fire.
        val clock = FakeClock(1_000_000L)
        val storage = SharedFakeStorage()
        val scheduler = RecordingScheduler()
        storage.saveReminder(Reminder("r1", "Missed", 400_000L))
        storage.recordReminderFire(
            com.anchor.core.models.ReminderFire("f_err", "r1", 900_000L, 400_000L, 0, "errored")
        )

        val reconciler = AlarmReconciler(storage, scheduler, clock)
        assertEquals(
            "An instance already surfaced as errored must not be reported overdue again",
            0,
            reconciler.reconcile(force = false).count { it is ScheduleResult.Overdue }
        )

        // A different fire instance for the same id is still reported: dedupe is per instance.
        storage.saveReminder(Reminder("r1", "Missed", 950_000L))
        clock.currentTime = 1_100_000L
        assertEquals(
            "A rescheduled instance with no errored/missed record is still reported",
            1,
            reconciler.reconcile(force = false).count { it is ScheduleResult.Overdue }
        )
    }

    @Test
    fun testReconcile_SchedulerFailed_UpdatesReliabilityState() = runBlocking {
        val clock = FakeClock(1000L)
        val storage = SharedFakeStorage()
        val failureReason = "SecurityException: caller lacks SCHEDULE_EXACT_ALARM"
        val scheduler = RecordingScheduler {
            ScheduleResult.Failed(failureReason)
        }

        val r1 = Reminder("r1", "Task", 5000L)
        storage.saveReminder(r1)

        val reconciler = AlarmReconciler(storage, scheduler, clock)
        val results = reconciler.reconcile(force = true)

        assertEquals(1, results.size)
        assertTrue(results[0] is ScheduleResult.Failed)
        assertEquals(failureReason, (results[0] as ScheduleResult.Failed).reason)

        val relState = storage.getReliabilityState()
        assertEquals("at_risk", relState.score)
        assertTrue("issuesJson must contain failure reason", relState.issuesJson?.contains("SecurityException") == true)
        assertFalse("Failed alarm must not be saved in registry", storage.getRegisteredAlarms().any { it.first == "r1" })
    }

    @Test
    fun testReconcile_FailThenSucceed_ScoreRecoversToHealthy() = runBlocking {
        val clock = FakeClock(1000L)
        val storage = SharedFakeStorage()
        var shouldFail = true
        val scheduler = RecordingScheduler {
            if (shouldFail) ScheduleResult.Failed("TransientFailure") else ScheduleResult.Armed
        }

        val r1 = Reminder("r1", "Task 1", 5000L)
        storage.saveReminder(r1)

        val reconciler = AlarmReconciler(storage, scheduler, clock)

        // Pass 1: fails
        val results1 = reconciler.reconcile(force = true)
        assertEquals(1, results1.size)
        assertTrue(results1[0] is ScheduleResult.Failed)
        var state = storage.getReliabilityState()
        assertEquals("at_risk", state.score)
        assertTrue(state.issuesJson?.contains("TransientFailure") == true)

        // Pass 2: succeeds (clean pass)
        shouldFail = false
        val results2 = reconciler.reconcile(force = true)
        assertEquals(1, results2.size)
        assertTrue(results2[0] is ScheduleResult.Armed)
        state = storage.getReliabilityState()
        assertEquals("healthy", state.score)
        assertEquals(null, state.issuesJson)
    }

    @Test
    fun testReconcile_100Failures_IssuesJsonSizeBounded() = runBlocking {
        val clock = FakeClock(1000L)
        val storage = SharedFakeStorage()
        var currentReason = "Error 0"
        val scheduler = RecordingScheduler {
            ScheduleResult.Failed(currentReason)
        }

        val reconciler = AlarmReconciler(storage, scheduler, clock)

        // Run 100 passes with 100 distinct error messages
        for (i in 1..100) {
            clock.currentTime += 1000L
            currentReason = "Distinct failure reason #$i"
            val r = Reminder("r_$i", "Task $i", clock.now() + 5000L)
            storage.saveReminder(r)
            reconciler.reconcile(force = false)
        }

        val state = storage.getReliabilityState()
        assertEquals("at_risk", state.score)
        val issuesJson = state.issuesJson
        org.junit.Assert.assertNotNull(issuesJson)

        // Parse JSON to verify at most 5 entries
        val parsed = kotlinx.serialization.json.Json.parseToJsonElement(issuesJson!!) as kotlinx.serialization.json.JsonArray
        assertEquals("Max 5 distinct recent issues allowed", 5, parsed.size)
        // String size must be strictly bounded (< 800 chars)
        assertTrue("issuesJson size must be bounded (< 800 chars)", issuesJson.length < 800)
        // Must contain the most recent failure (100)
        assertTrue(issuesJson.contains("Distinct failure reason #100"))
        // Must NOT contain an old failure (e.g., #1)
        assertFalse(issuesJson.contains("Distinct failure reason #1\""))
    }

    // R-26: getPendingReminders is paged. A full page means rows exist that this reconcile pass
    // cannot see; silently treating the page as the whole set would leave the tail unarmed with
    // no signal at all. The pass must still arm what it can and must report the truncation.
    @Test
    fun testReconcile_FullPendingPage_ReportsTruncationInsteadOfSilentlyDroppingTail() = runBlocking {
        val clock = FakeClock(1000L)
        val storage = SharedFakeStorage()
        val scheduler = RecordingScheduler()
        val limit = Storage.PENDING_REMINDER_PAGE_LIMIT

        // One more reminder than a page holds, all in the future so all are armable.
        for (i in 0 until limit + 1) {
            storage.saveReminder(Reminder("r$i", "Reminder $i", 5000L + i, tier = AlarmTier.ALARM))
        }

        val results = AlarmReconciler(storage, scheduler, clock).reconcile(force = false)

        assertEquals("every visible reminder must still be armed", limit, scheduler.scheduled.size)
        val reportedAsTruncated = results.any { it is ScheduleResult.Failed && it.reason == AlarmReconciler.ISSUE_PENDING_TRUNCATED }
        assertTrue(
            "a full page must be reported, not silently treated as the whole set; results=${results.map { it.toString() }}",
            reportedAsTruncated
        )
        val state = storage.getReliabilityState()
        assertTrue(
            "reliability state must carry the truncation issue",
            state.issuesJson?.contains(AlarmReconciler.ISSUE_PENDING_TRUNCATED) == true
        )
    }

    // R-26: below the page size nothing changes — no spurious issue on a normal pass.
    @Test
    fun testReconcile_PartialPendingPage_DoesNotReportTruncation() = runBlocking {
        val clock = FakeClock(1000L)
        val storage = SharedFakeStorage()
        val scheduler = RecordingScheduler()
        storage.saveReminder(Reminder("r1", "Only one", 5000L, tier = AlarmTier.ALARM))

        val results = AlarmReconciler(storage, scheduler, clock).reconcile(force = false)

        assertFalse(
            "a partial page is a complete set",
            results.any { it is ScheduleResult.Failed && it.reason == AlarmReconciler.ISSUE_PENDING_TRUNCATED }
        )
        assertEquals(1, scheduler.scheduled.size)
    }
}
