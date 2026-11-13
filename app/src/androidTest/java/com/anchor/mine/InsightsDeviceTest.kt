// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.mine

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.anchor.core.models.Edge
import com.anchor.core.models.Node
import com.anchor.core.models.RawEvent
import com.anchor.core.models.SignalLog
import com.anchor.mine.storage.AndroidStorage
import com.anchor.mine.storage.DatabaseProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Connected instrumentation test on physical device verifying the v4 Insights & Analytics
 * queries over encrypted SQLCipher storage (Blueprint 32 §3.5-§3.6 / P-007, P-008).
 */
@RunWith(AndroidJUnit4::class)
class InsightsDeviceTest {

    private lateinit var storage: AndroidStorage
    private lateinit var dbHelper: androidx.sqlite.db.SupportSQLiteOpenHelper
    private val dbName = "mine_insights_device_test.db"

    @Before
    fun setup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(dbName)
        val key = ByteArray(32) { 4 }
        dbHelper = DatabaseProvider.getHelper(context, key, dbName)
        storage = AndroidStorage(dbHelper)
    }

    @After
    fun teardown() {
        dbHelper.close()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(dbName)
    }

    @Test
    fun testInsightsQueries_OverRealSqlCipherStorage() = runBlocking {
        val now = 1700000000000L
        val weekAgo = now - 7 * 86_400_000L

        // 1. Insert created and closed nodes
        val n1 = Node("n_ins_1", "task", "Submit Tax Return", "done", createdAt = weekAgo + 10_000L, closedAt = now - 5_000L)
        val ev1 = RawEvent("ev_ins_1", "CREATE", "node", n1.id, tsUtc = weekAgo + 10_000L)
        storage.createNodeAndCommit(n1, ev1, null)

        val n2 = Node("n_ins_2", "task", "Active Todo", "active", createdAt = now - 100_000L)
        val ev2 = RawEvent("ev_ins_2", "CREATE", "node", n2.id, tsUtc = now - 100_000L)
        storage.createNodeAndCommit(n2, ev2, null)

        // 2. Goal and subtask with edge
        val goal = Node("g_ins_1", "goal", "Launch Mine v4", "active", createdAt = weekAgo + 20_000L)
        val evGoal = RawEvent("ev_g_1", "CREATE", "node", goal.id, tsUtc = weekAgo + 20_000L)
        storage.createNodeAndCommit(goal, evGoal, null)

        val edge = Edge("edge_ins_1", srcId = n1.id, dstId = goal.id, type = "part_of")
        storage.saveEdge(edge)

        // 3. Habit with streak
        val habit = Node("h_ins_1", "habit", "Daily Meditation", "active", streakCurrent = 8, streakBest = 12, recurrenceRule = "FREQ=DAILY", createdAt = weekAgo)
        val evHabit = RawEvent("ev_h_1", "CREATE", "node", habit.id, tsUtc = weekAgo)
        storage.createNodeAndCommit(habit, evHabit, null)

        // 4. User actions and mood signal
        storage.insertRawMessage("m_ins_1", "user", "Hello assistant")
        storage.recordSignal(SignalLog("sig_ins_1", now - 2000L, "mood", value = 4.0, attrs = "Good day"))

        // Query range verification
        val created = storage.getNodesCreatedBetween(weekAgo, now)
        assertTrue("Created list must contain nodes", created.any { it.id == n1.id })

        val closed = storage.getNodesClosedBetween(weekAgo, now)
        assertEquals("Closed list must have 1 completed item", 1, closed.size)
        assertEquals(n1.id, closed.first().id)

        val goals = storage.getGoalNodes()
        assertEquals(1, goals.size)
        assertEquals("Launch Mine v4", goals.first().title)

        val habits = storage.getHabitNodes()
        assertEquals(1, habits.size)
        assertEquals(8, habits.first().streakCurrent)

        // 5. Full Insights Report calculation
        val report = storage.getFullInsightsReport(weekAgo, now, now, includeMood = true)
        assertNotNull(report)
        assertEquals(1, report.weeklyReview.completedCount)
        assertEquals(listOf("Submit Tax Return"), report.weeklyReview.wins)
        assertEquals(7, report.weeklyReview.dailyBars.size)

        assertEquals(1, report.goalProgress.size)
        val gp = report.goalProgress.first()
        assertEquals("Launch Mine v4", gp.goalTitle)
        assertEquals(1, gp.totalSubTasks)
        assertEquals(1, gp.completedSubTasks)
        assertEquals(100.0f, gp.progressPercent, 0.01f)

        assertEquals(1, report.habitStreaks.size)
        assertEquals(8, report.habitStreaks.first().currentStreak)

        assertEquals(24, report.activityHeatStrip.hourlyCounts.size)
        assertTrue("Activity count must be > 0", report.activityHeatStrip.totalActions > 0)

        assertEquals(1, report.moodTrends.size)
        assertEquals(4.0f, report.moodTrends.first().score, 0.01f)
    }
}
