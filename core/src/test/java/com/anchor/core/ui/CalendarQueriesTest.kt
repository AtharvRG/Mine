// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.ui

import com.anchor.core.SharedFakeStorage
import com.anchor.core.alarms.AlarmScheduler
import com.anchor.core.context.ContextBuilder
import com.anchor.core.gateway.Gateway
import com.anchor.core.models.AlarmTier
import com.anchor.core.models.ChatMessage
import com.anchor.core.models.Reminder
import com.anchor.core.provider.ProviderOrchestrator
import com.anchor.core.provider.RateLimiter
import com.anchor.core.resolver.Resolver
import com.anchor.core.time.Clock
import com.anchor.core.time.DateResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CalendarQueriesTest {

    private class FakeClock(private var time: Long) : Clock {
        override fun now(): Long = time
    }

    private class FakeAlarmScheduler : AlarmScheduler {
        override val lastFiredId = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
        override fun schedule(reminder: Reminder) = com.anchor.core.alarms.ScheduleResult.Armed
        override fun cancel(reminderId: String) {}
    }

    private class FakeHttpTransport : com.anchor.core.provider.HttpTransport {
        override suspend fun postStream(target: com.anchor.core.provider.Target, body: String): kotlinx.coroutines.flow.Flow<String> =
            kotlinx.coroutines.flow.flowOf()
    }

    @Test
    fun testStorage_CalendarQueries() = runBlocking {
        val storage = SharedFakeStorage()

        // 1. Insert messages across days
        storage.insertRawMessageWithDay("m1", "user", "Hello Day 1", "2026-11-01", 1000L)
        storage.insertRawMessageWithDay("m2", "assistant", "Hi there!", "2026-11-01", 2000L)

        storage.insertRawMessageWithDay("m3", "user", "Morning Day 2", "2026-11-02", 10000L)
        storage.insertRawMessageWithDay("m4", "assistant", "Good morning", "2026-11-02", 11000L)
        storage.insertRawMessageWithDay("m5", "user", "Afternoon task", "2026-11-02", 12000L)
        storage.insertRawMessageWithDay("m6", "assistant", "Got it", "2026-11-02", 13000L)
        storage.insertRawMessageWithDay("m7", "user", "Evening wrap-up", "2026-11-02", 14000L)

        storage.insertRawMessageWithDay("m8", "user", "Sole message on Day 3", "2026-11-03", 20000L)

        // 2. Insert reminders
        val r1 = Reminder("r1", "Doctor appointment", 10500L, "n1", AlarmTier.ALARM)
        val r2 = Reminder("r2", "Call electrician", 13500L, "n2", AlarmTier.GENTLE)
        val r3 = Reminder("r3", "Dentist next week", 30000L, "n3", AlarmTier.ALARM)
        storage.saveReminder(r1)
        storage.saveReminder(r2)
        storage.saveReminder(r3)

        // 3. Verify getDaysWithMessages
        val activeDays = storage.getDaysWithMessages("2026-11-01", "2026-11-03")
        assertEquals(setOf("2026-11-01", "2026-11-02", "2026-11-03"), activeDays)

        val emptyRange = storage.getDaysWithMessages("2026-11-04", "2026-11-10")
        assertTrue(emptyRange.isEmpty())

        // 4. Verify getHeatMapCounts
        val heatMap = storage.getHeatMapCounts("2026-11-01", "2026-11-03")
        assertEquals(3, heatMap.size)
        assertEquals(2, heatMap["2026-11-01"])
        assertEquals(5, heatMap["2026-11-02"])
        assertEquals(1, heatMap["2026-11-03"])

        // 5. Verify getMessagesForDay (chronological)
        val day2Msgs = storage.getMessagesForDay("2026-11-02")
        assertEquals(5, day2Msgs.size)
        assertEquals("m3", day2Msgs[0].id)
        assertEquals("m7", day2Msgs[4].id)

        // 6. Verify getRemindersForDay
        val day2Reminders = storage.getRemindersForDay(10000L, 15000L)
        assertEquals(2, day2Reminders.size)
        assertEquals("r1", day2Reminders[0].id)
        assertEquals("r2", day2Reminders[1].id)
    }

    @Test
    fun testChatController_DayNavigationAndTooltip() = runBlocking {
        val testDispatcher = UnconfinedTestDispatcher()
        val scope = CoroutineScope(testDispatcher)
        val storage = SharedFakeStorage()
        val clock = FakeClock(50000L)
        val dateResolver = DateResolver(clock) { true }
        val gateway = Gateway(storage, Resolver(), dateResolver, clock)
        val orchestrator = ProviderOrchestrator(RateLimiter { clock.now() }, FakeHttpTransport())
        val checkinBuilder = com.anchor.core.nudge.CheckinBuilder(storage, clock, dateResolver)
        val contextBuilder = ContextBuilder(storage, clock, dateResolver, checkinBuilder)

        // Setup storage data
        storage.insertRawMessageWithDay("m_day1", "user", "Day 1 query", "2026-11-02", 10000L)
        storage.insertRawMessageWithDay("m_day2", "assistant", "Day 1 reply", "2026-11-02", 11000L)
        val reminder = Reminder("rem_day", "Submit taxes", 12000L, "n_tax", AlarmTier.ALARM)
        storage.saveReminder(reminder)

        val controller = ChatController(
            storage,
            orchestrator,
            contextBuilder,
            gateway,
            FakeAlarmScheduler(),
            scope,
            testDispatcher,
            clock
        )

        // D-107: Tap on date with conversation opens that day's conversation
        controller.onAction(UiAction.LoadDay("2026-11-02"))
        assertEquals("2026-11-02", controller.state.value.selectedDayKey)
        assertEquals(2, controller.state.value.selectedDayMessages.size)
        assertEquals("m_day1", controller.state.value.selectedDayMessages[0].id)

        // D-107: Long-press shows reminders tooltip/sheet
        controller.onAction(UiAction.ShowDayReminders("2026-11-02", 10000L, 15000L))
        assertEquals(1, controller.state.value.selectedDayReminders.size)
        assertEquals("rem_day", controller.state.value.selectedDayReminders[0].id)

        // Clear selection
        controller.onAction(UiAction.ClearDaySelection)
        assertNull(controller.state.value.selectedDayKey)
        assertTrue(controller.state.value.selectedDayMessages.isEmpty())
        assertTrue(controller.state.value.selectedDayReminders.isEmpty())
    }
}
