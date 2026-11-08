// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.ui

import com.anchor.core.SharedFakeStorage
import com.anchor.core.alarms.AlarmScheduler
import com.anchor.core.alarms.ScheduleResult
import com.anchor.core.context.ContextBuilder
import com.anchor.core.gateway.Gateway
import com.anchor.core.models.RawEvent
import com.anchor.core.models.Reminder
import com.anchor.core.nudge.CheckinBuilder
import com.anchor.core.provider.HttpTransport
import com.anchor.core.provider.ProviderOrchestrator
import com.anchor.core.provider.RateLimiter
import com.anchor.core.provider.Target
import com.anchor.core.resolver.Resolver
import com.anchor.core.time.DateResolver
import com.anchor.core.time.FakeClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Failing-first coverage for the two untested ChatController UI actions:
 *  - RunSelfTest must not leave a phantom reminder row when arming fails.
 *  - CancelReminder must produce an undoable event even when the row is already gone.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SelfTestAndCancelReminderTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val controllerScope = CoroutineScope(testDispatcher + SupervisorJob())

    private class FixedScheduler(
        val scheduleResult: ScheduleResult,
        val healthResult: ScheduleResult = ScheduleResult.Armed
    ) : AlarmScheduler {
        override val lastFiredId = MutableStateFlow<String?>(null)
        val cancelled = mutableListOf<String>()
        override fun schedule(reminder: Reminder): ScheduleResult = scheduleResult
        override fun cancel(reminderId: String) { cancelled.add(reminderId) }
        override fun checkHealth(): ScheduleResult = healthResult
    }

    private fun newController(
        storage: SharedFakeStorage,
        clock: FakeClock,
        scheduler: AlarmScheduler
    ): ChatController {
        val dateResolver = DateResolver(clock)
        val gateway = Gateway(storage, Resolver(), dateResolver, clock)
        val checkinBuilder = CheckinBuilder(storage, clock, dateResolver)
        val contextBuilder = ContextBuilder(storage, clock, dateResolver, checkinBuilder)
        val fakeTransport = object : HttpTransport {
            override suspend fun postStream(target: Target, body: String): Flow<String> = emptyFlow()
        }
        val orchestrator = ProviderOrchestrator(RateLimiter { clock.now() }, fakeTransport)
        return ChatController(
            storage = storage,
            orchestrator = orchestrator,
            contextBuilder = contextBuilder,
            gateway = gateway,
            alarmScheduler = scheduler,
            coroutineScope = controllerScope,
            defaultDispatcher = testDispatcher,
            clock = clock
        )
    }

    @Test
    fun testRunSelfTest_ScheduleFailed_LeavesNoReminderRow() = runTest(testDispatcher) {
        val clock = FakeClock(1700000000000L)
        val storage = SharedFakeStorage()
        // checkHealth passes (notification settings OK) but schedule fails (exact-alarm policy).
        val scheduler = FixedScheduler(
            scheduleResult = ScheduleResult.Failed("Exact alarm permission denied"),
            healthResult = ScheduleResult.Armed
        )
        val controller = newController(storage, clock, scheduler)

        controller.onAction(UiAction.RunSelfTest)

        assertEquals(
            "A failed self-test must not persist a phantom reminder row",
            0,
            storage.getPendingReminders().size
        )
        assertTrue(
            "selfTestStatus must report the failure",
            controller.state.value.selfTestStatus.startsWith("Failed")
        )
        assertEquals(1, controller.state.value.schedulingFailedCount)
    }

    @Test
    fun testRunSelfTest_ScheduleArmed_PersistsArmedReminder() = runTest(testDispatcher) {
        val clock = FakeClock(1700000000000L)
        val storage = SharedFakeStorage()
        val scheduler = FixedScheduler(ScheduleResult.Armed)
        val controller = newController(storage, clock, scheduler)

        controller.onAction(UiAction.RunSelfTest)

        val pending = storage.getPendingReminders()
        assertEquals(1, pending.size)
        assertEquals(clock.now() + 5000L, pending[0].fireAtMillis)
        assertEquals("Armed for 5 seconds...", controller.state.value.selfTestStatus)
    }

    @Test
    fun testRunSelfTest_HealthFailedButArmSucceeds_StillArmsAndWarns() = runTest(testDispatcher) {
        // checkHealth() can only see notification settings, so a "Failed" there is not proof the
        // arm will fail. Blocking the self-test on it denies the user the one test that shows
        // alarms work, and labels their working reminders broken. Arm, then warn.
        val clock = FakeClock(1700000000000L)
        val storage = SharedFakeStorage()
        val scheduler = FixedScheduler(
            scheduleResult = ScheduleResult.Armed,
            healthResult = ScheduleResult.Failed("Notifications are disabled for Mine in system settings")
        )
        val controller = newController(storage, clock, scheduler)

        controller.onAction(UiAction.RunSelfTest)

        assertEquals(
            "A health pre-check failure must not stop the arm from being attempted",
            1,
            storage.getPendingReminders().size
        )
        val status = controller.state.value.selfTestStatus
        assertTrue("Status must report the arm: $status", status.startsWith("Armed"))
        assertTrue("Status must carry the notification warning: $status", status.contains("Notifications are disabled"))
        assertEquals("Notifications are disabled for Mine in system settings", controller.state.value.schedulingError)
    }

    @Test
    fun testCancelReminder_MissingRow_StillCancelsAlarmAndWritesNoUndoableDelete() = runTest(testDispatcher) {
        val clock = FakeClock(1700000000000L)
        val storage = SharedFakeStorage()
        val scheduler = FixedScheduler(ScheduleResult.Armed)
        val controller = newController(storage, clock, scheduler)

        // Row already gone (e.g. cancelled from another surface); UI still holds a stale chip.
        controller.onAction(UiAction.CancelReminder("r_ghost"))

        assertTrue("A stale chip must still cancel the OS alarm", scheduler.cancelled.contains("r_ghost"))
        assertEquals(0, storage.getPendingReminders().size)
        val event = storage.getRecentEvents(10).lastOrNull { it.opType == "DELETE" && it.targetId == "r_ghost" }
        assertEquals(
            "Nothing was deleted, so no DELETE receipt may claim otherwise",
            null,
            event
        )
    }

    @Test
    fun testCancelReminder_ExistingRow_EmitsUndoableEventAndCancelsAlarm() = runTest(testDispatcher) {
        val clock = FakeClock(1700000000000L)
        val storage = SharedFakeStorage()
        val scheduler = FixedScheduler(ScheduleResult.Armed)
        val controller = newController(storage, clock, scheduler)

        val reminder = Reminder("r_water", "Drink water", clock.now() + 60000L, "n_water")
        storage.saveReminder(reminder)

        controller.onAction(UiAction.CancelReminder("r_water"))

        assertEquals(0, storage.getPendingReminders().size)
        assertTrue("Alarm must be cancelled", scheduler.cancelled.contains("r_water"))
        val event = storage.getRecentEvents(10).lastOrNull { it.opType == "DELETE" && it.targetId == "r_water" }
        assertNotNull(event)
        assertNotNull(event!!.beforeJson)
        assertTrue(event.beforeJson!!.contains("Drink water"))
    }

    @Test
    fun testCancelReminder_AlarmCancelThrows_KeepsRowAndRegistration() = runTest(testDispatcher) {
        // H-08 (R-12): the row used to be deleted before the OS alarm was cancelled. When cancel()
        // threw, the row was gone but the alarm stayed armed, and reconcile could not find it: it
        // only cancels an orphan registration, and the registration was deleted in the same pass.
        // The reminder then fired for something the user had cancelled and could no longer see.
        val clock = FakeClock(1700000000000L)
        val storage = SharedFakeStorage()
        val scheduler = object : AlarmScheduler {
            override val lastFiredId = MutableStateFlow<String?>(null)
            override fun schedule(reminder: Reminder): ScheduleResult = ScheduleResult.Armed
            override fun cancel(reminderId: String) { throw SecurityException("alarm permission revoked") }
        }
        val controller = newController(storage, clock, scheduler)

        val reminder = Reminder("r_pill", "Take pills", clock.now() + 60000L, "n_pill")
        storage.saveReminder(reminder)
        storage.saveRegisteredAlarm("r_pill", 42, reminder.fireAtMillis)

        controller.onAction(UiAction.CancelReminder("r_pill"))

        assertNotNull(
            "A failed OS cancel must leave the row so the alarm is still reachable",
            storage.getPendingReminders().find { it.id == "r_pill" }
        )
        assertTrue(
            "The registration must survive a failed cancel, or reconcile cannot clear the orphan",
            storage.getRegisteredAlarms().any { it.first == "r_pill" }
        )
        assertEquals(
            "Nothing was deleted, so no DELETE receipt may claim otherwise",
            null,
            storage.getRecentEvents(10).lastOrNull { it.opType == "DELETE" && it.targetId == "r_pill" }
        )
    }
}
