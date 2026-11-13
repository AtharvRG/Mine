// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.insights

import com.anchor.core.SharedFakeStorage
import com.anchor.core.alarms.AlarmScheduler
import com.anchor.core.alarms.ScheduleResult
import com.anchor.core.models.DayRule
import com.anchor.core.models.Edge
import com.anchor.core.models.Node
import com.anchor.core.models.RawEvent
import com.anchor.core.models.Reminder
import com.anchor.core.models.SignalLog
import com.anchor.core.provider.HttpTransport
import com.anchor.core.resolver.Resolver
import com.anchor.core.time.Clock
import com.anchor.core.time.DateResolver
import com.anchor.core.time.DayRollover
import com.anchor.core.ui.ChatController
import com.anchor.core.ui.UiAction
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.util.TimeZone

import com.anchor.core.provider.Target
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow

private class FakeScheduler : AlarmScheduler {
    override val lastFiredId = MutableStateFlow<String?>(null)
    override fun schedule(reminder: Reminder): ScheduleResult = ScheduleResult.Armed
    override fun cancel(reminderId: String) {}
}

private class FakeTransport : HttpTransport {
    override suspend fun postStream(target: Target, body: String): Flow<String> = flow {
        emit("""data: {"choices":[{"delta":{"content":"{\"reply\":\"Understood.\",\"calls\":[]}"}}]}""")
        emit("data: [DONE]")
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class InsightsEngineTest {

    private val rule = DayRule("test_rule", 0L, 240, 1320, "UTC", 0L)
    private val engine = InsightsEngine(rule)

    @Test
    fun testWeeklyReview_ComputesCompletionRateAndDailyBars() {
        val startMs = 1700000000000L // arbitrary fixed time
        val endMs = startMs + 7 * 86_400_000L

        val createdNodes = listOf(
            Node("n1", "task", "Task 1", "active", createdAt = startMs + 1000L),
            Node("n2", "task", "Task 2", "active", createdAt = startMs + 86_400_000L + 2000L),
            Node("n3", "task", "Task 3", "active", createdAt = startMs + 2 * 86_400_000L + 3000L)
        )
        val completedNodes = listOf(
            Node("n1", "task", "Task 1", "done", createdAt = startMs + 1000L, closedAt = startMs + 5000L),
            Node("n2", "task", "Task 2", "done", createdAt = startMs + 86_400_000L + 2000L, closedAt = startMs + 86_400_000L + 6000L)
        )
        val cancelledEvents = listOf(
            RawEvent("ev1", "CANCEL", "node", "n4", tsUtc = startMs + 10_000L)
        )

        val stats = engine.buildWeeklyReview(
            startMs = startMs,
            endMs = endMs,
            createdNodes = createdNodes,
            completedNodes = completedNodes,
            cancelledEvents = cancelledEvents,
            openBacklogCount = 1,
            rule = rule
        )

        assertEquals(3, stats.createdCount)
        assertEquals(2, stats.completedCount)
        assertEquals(1, stats.cancelledCount)
        assertEquals(1, stats.carriedOverCount)
        // total = completed (2) + carriedOver (1) + created (3) = 6
        // rate = 2 / 6 = 33.333336%
        assertEquals(33.333336f, stats.completionRate, 0.01f)
        assertEquals(7, stats.dailyBars.size)
        assertEquals(listOf("Task 1", "Task 2"), stats.wins)
    }

    @Test
    fun testWeeklyReview_DivisionByZeroSafety_OnEmptyData() {
        val stats = engine.buildWeeklyReview(
            startMs = 1000L,
            endMs = 1000L + 7 * 86_400_000L,
            createdNodes = emptyList(),
            completedNodes = emptyList(),
            cancelledEvents = emptyList(),
            openBacklogCount = 0,
            rule = rule
        )
        assertEquals(0.0f, stats.completionRate, 0.001f)
        assertEquals(0, stats.createdCount)
        assertEquals(0, stats.completedCount)
        assertEquals(7, stats.dailyBars.size)
    }

    @Test
    fun testMonthlyReview_PartitionsWeeklyCompletionsAndTopHabits() {
        val startMs = 1700000000000L
        val endMs = startMs + 28 * 86_400_000L // 4 weeks

        val completedNodes = listOf(
            Node("n1", "task", "T1", "done", closedAt = startMs + 1 * 86_400_000L),
            Node("n2", "task", "T2", "done", closedAt = startMs + 2 * 86_400_000L),
            Node("n3", "task", "T3", "done", closedAt = startMs + 9 * 86_400_000L), // week 2
            Node("n4", "task", "T4", "done", closedAt = startMs + 23 * 86_400_000L) // week 4
        )

        val habitNodes = listOf(
            Node("h1", "habit", "Morning Meditation", "active", streakCurrent = 14, streakBest = 20),
            Node("h2", "habit", "Drink Water", "active", streakCurrent = 7, streakBest = 10),
            Node("h3", "habit", "Evening Walk", "active", streakCurrent = 0, streakBest = 5)
        )

        val monthly = engine.buildMonthlyReview(
            startMs = startMs,
            endMs = endMs,
            isMonthToDate = true,
            createdNodes = emptyList(),
            completedNodes = completedNodes,
            cancelledEvents = emptyList(),
            openBacklogCount = 2,
            habitNodes = habitNodes
        )

        assertTrue(monthly.isMonthToDate)
        assertEquals(4, monthly.weeklyCompletions.size)
        assertEquals(2, monthly.weeklyCompletions[0]) // week 1
        assertEquals(1, monthly.weeklyCompletions[1]) // week 2
        assertEquals(0, monthly.weeklyCompletions[2]) // week 3
        assertEquals(1, monthly.weeklyCompletions[3]) // week 4

        assertEquals(2, monthly.topHabitWins.size)
        assertEquals("Morning Meditation (14 streak)", monthly.topHabitWins[0])
        assertEquals("Drink Water (7 streak)", monthly.topHabitWins[1])
    }

    @Test
    fun testGoalProgress_ComputesHierarchyCompletion() {
        val goals = listOf(
            Node("g1", "goal", "Launch Mine v4", "active"),
            Node("g2", "goal", "Learn Spanish", "active")
        )
        val subtasks = mapOf(
            "st1" to Node("st1", "task", "Code Insights", "done"),
            "st2" to Node("st2", "task", "Verify on Phone", "done"),
            "st3" to Node("st3", "task", "Write Docs", "active"),
            "st4" to Node("st4", "task", "Learn Alphabet", "done")
        )
        val edges = listOf(
            Edge("e1", srcId = "st1", dstId = "g1", type = "part_of"),
            Edge("e2", srcId = "st2", dstId = "g1", type = "part_of"),
            Edge("e3", srcId = "st3", dstId = "g1", type = "part_of"),
            Edge("e4", srcId = "st4", dstId = "g2", type = "part_of")
        )

        val progress = engine.buildGoalProgress(goals, edges, subtasks + goals.associateBy { it.id })
        assertEquals(2, progress.size)

        val g1Progress = progress.first { it.goalNodeId == "g1" }
        assertEquals(3, g1Progress.totalSubTasks)
        assertEquals(2, g1Progress.completedSubTasks)
        assertEquals(66.666664f, g1Progress.progressPercent, 0.01f)

        val g2Progress = progress.first { it.goalNodeId == "g2" }
        assertEquals(1, g2Progress.totalSubTasks)
        assertEquals(1, g2Progress.completedSubTasks)
        assertEquals(100.0f, g2Progress.progressPercent, 0.01f)
    }

    @Test
    fun testActivityHeatStrip_ComputesHourlyDistributionAndPeakHour() {
        // Create synthetic timestamps across specific hours UTC
        val baseDay = 1700000000000L // Assume fixed UTC base
        val cal = java.util.Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            timeInMillis = baseDay
            set(java.util.Calendar.HOUR_OF_DAY, 9)
            set(java.util.Calendar.MINUTE, 15)
        }
        val ts9am_1 = cal.timeInMillis
        cal.set(java.util.Calendar.MINUTE, 45)
        val ts9am_2 = cal.timeInMillis

        cal.set(java.util.Calendar.HOUR_OF_DAY, 14)
        val ts2pm = cal.timeInMillis

        val heatStrip = engine.buildActivityHeatStrip(listOf(ts9am_1, ts9am_2, ts2pm), tzId = "UTC")
        assertEquals(24, heatStrip.hourlyCounts.size)
        assertEquals(2, heatStrip.hourlyCounts[9])
        assertEquals(1, heatStrip.hourlyCounts[14])
        assertEquals(0, heatStrip.hourlyCounts[0])
        assertEquals(9, heatStrip.peakHour)
        assertEquals(3, heatStrip.totalActions)
    }

    @Test
    fun testMoodTrends_OptInGating() {
        val signals = listOf(
            SignalLog("s1", 1700000000000L, "mood", value = 4.0, attrs = "Felt productive today"),
            SignalLog("s2", 1700086400000L, "mood", value = 5.0, attrs = "Great day")
        )

        // When opt-in is disabled, mood trends must return empty list
        val disabled = engine.buildMoodTrends(signals, optInEnabled = false, rule = rule)
        assertTrue(disabled.isEmpty())

        // When opt-in is enabled, returns points
        val enabled = engine.buildMoodTrends(signals, optInEnabled = true, rule = rule)
        assertEquals(2, enabled.size)
        assertEquals(4.0f, enabled[0].score, 0.01f)
        assertEquals("Felt productive today", enabled[0].note)
        assertEquals(5.0f, enabled[1].score, 0.01f)
    }

    @Test
    fun testStorage_GetFullInsightsReport_EndToEnd() = runTest {
        val storage = SharedFakeStorage()
        val now = 1700000000000L
        val weekAgo = now - 7 * 86_400_000L

        storage.activeNodes.add(Node("n1", "task", "Completed Task 1", "done", createdAt = weekAgo + 1000L, closedAt = now - 1000L))
        storage.activeNodes.add(Node("h1", "habit", "Daily Exercise", "active", streakCurrent = 5, streakBest = 10))
        storage.activeNodes.add(Node("g1", "goal", "Launch Project", "active"))
        storage.activeNodes.add(Node("st1", "task", "Write Code", "done"))
        storage.edges.add(Edge("e1", srcId = "st1", dstId = "g1", type = "part_of"))
        storage.recordSignal(SignalLog("s1", now - 5000L, "mood", value = 4.5, attrs = "Energetic"))

        val report = storage.getFullInsightsReport(weekAgo, now, now, includeMood = true)
        assertNotNull(report)
        assertEquals(1, report.weeklyReview.completedCount)
        assertEquals(1, report.habitStreaks.size)
        assertEquals(5, report.habitStreaks.first().currentStreak)
        assertEquals(1, report.goalProgress.size)
        assertEquals(100.0f, report.goalProgress.first().progressPercent, 0.01f)
        assertEquals(1, report.moodTrends.size)
        assertEquals(4.5f, report.moodTrends.first().score, 0.01f)
    }

    @Test
    fun testChatController_LoadInsightsAction() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val storage = SharedFakeStorage()
        val testClock = object : Clock {
            var current = 1700000000000L
            override fun now(): Long = current
        }
        val dateResolver = DateResolver(testClock)
        val checkinBuilder = com.anchor.core.nudge.CheckinBuilder(storage, testClock, dateResolver)
        val contextBuilder = com.anchor.core.context.ContextBuilder(storage, testClock, dateResolver, checkinBuilder)
        val orchestrator = com.anchor.core.provider.ProviderOrchestrator(com.anchor.core.provider.RateLimiter { testClock.now() }, FakeTransport())
        val gateway = com.anchor.core.gateway.Gateway(storage, Resolver(), dateResolver, testClock)
        val controller = ChatController(
            storage = storage,
            orchestrator = orchestrator,
            contextBuilder = contextBuilder,
            gateway = gateway,
            alarmScheduler = FakeScheduler(),
            coroutineScope = TestScope(testDispatcher),
            defaultDispatcher = testDispatcher,
            clock = testClock,
            dateResolver = dateResolver
        )

        storage.activeNodes.add(Node("n1", "task", "Test Task", "done", closedAt = testClock.now() - 5000L))

        controller.onAction(UiAction.LoadInsights(days = 7))
        testScheduler.advanceUntilIdle()

        val state = controller.state.value
        assertNotNull("latestInsightsReport must be populated", state.latestInsightsReport)
        assertEquals(1, state.latestInsightsReport?.weeklyReview?.completedCount)
    }
}
