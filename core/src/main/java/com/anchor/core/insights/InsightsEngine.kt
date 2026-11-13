// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.insights

import com.anchor.core.models.DayRule
import com.anchor.core.models.Edge
import com.anchor.core.models.Node
import com.anchor.core.models.RawEvent
import com.anchor.core.models.SignalLog
import com.anchor.core.time.DayRollover
import java.util.Calendar
import java.util.TimeZone

/**
 * Pure Kotlin Insights and Analytics Computation Engine.
 * Blueprint 32 §3.5 & §3.6, Parking Lot P-007, P-008.
 *
 * Computes deterministic statistics, habit streaks, goal rollups,
 * 24-hour activity heat strips, and opt-in mood trends without LLM hallucinations.
 */
class InsightsEngine(
    private val dayRule: DayRule = DayRollover.defaultRule()
) {

    /**
     * Computes weekly review statistics and 7-day daily bars for the week_bars card.
     */
    fun buildWeeklyReview(
        startMs: Long,
        endMs: Long,
        createdNodes: List<Node>,
        completedNodes: List<Node>,
        cancelledEvents: List<RawEvent>,
        openBacklogCount: Int,
        rule: DayRule = dayRule
    ): WeeklyReviewStats {
        val created = createdNodes.size
        val completed = completedNodes.size
        val cancelled = cancelledEvents.size
        val carriedOver = openBacklogCount
        val total = completed + carriedOver + created
        val rate = if (total > 0) (completed.toFloat() / total.toFloat()) * 100f else 0.0f

        // Group by dayKey using DayRollover
        val createdByDay = createdNodes.groupBy { DayRollover.computeDayKey(it.createdAt, rule) }
        val completedByDay = completedNodes.groupBy {
            val at = it.closedAt ?: it.updatedAt
            DayRollover.computeDayKey(at, rule)
        }

        // Generate list of 7 consecutive days starting from startMs
        val dailyBars = mutableListOf<DayBucket>()
        var step = startMs
        val stepEnd = maxOf(endMs, startMs + 6 * 86_400_000L)
        val seenDays = mutableSetOf<String>()
        while (step <= stepEnd && dailyBars.size < 7) {
            val dKey = DayRollover.computeDayKey(step, rule)
            if (dKey !in seenDays) {
                seenDays.add(dKey)
                dailyBars.add(
                    DayBucket(
                        dayKey = dKey,
                        createdCount = createdByDay[dKey]?.size ?: 0,
                        completedCount = completedByDay[dKey]?.size ?: 0
                    )
                )
            }
            step += 86_400_000L
        }

        val wins = completedNodes.map { it.title }.take(5)

        return WeeklyReviewStats(
            startMs = startMs,
            endMs = endMs,
            createdCount = created,
            completedCount = completed,
            cancelledCount = cancelled,
            carriedOverCount = carriedOver,
            completionRate = rate,
            dailyBars = dailyBars,
            wins = wins
        )
    }

    /**
     * Computes monthly review or month-to-date review (P-007).
     */
    fun buildMonthlyReview(
        startMs: Long,
        endMs: Long,
        isMonthToDate: Boolean,
        createdNodes: List<Node>,
        completedNodes: List<Node>,
        cancelledEvents: List<RawEvent>,
        openBacklogCount: Int,
        habitNodes: List<Node>
    ): MonthlyReviewStats {
        val created = createdNodes.size
        val completed = completedNodes.size
        val cancelled = cancelledEvents.size
        val carriedOver = openBacklogCount
        val total = completed + carriedOver + created
        val rate = if (total > 0) (completed.toFloat() / total.toFloat()) * 100f else 0.0f

        val weekSpanMs = 7 * 86_400_000L
        val weeklyCompletions = mutableListOf<Int>()
        var wStart = startMs
        while (wStart < endMs) {
            val wEnd = minOf(endMs, wStart + weekSpanMs)
            val wCount = completedNodes.count {
                val at = it.closedAt ?: it.updatedAt
                at in wStart until wEnd
            }
            weeklyCompletions.add(wCount)
            wStart += weekSpanMs
        }

        val topHabitWins = habitNodes
            .filter { it.streakCurrent > 0 }
            .sortedByDescending { it.streakCurrent }
            .map { "${it.title} (${it.streakCurrent} streak)" }
            .take(3)

        return MonthlyReviewStats(
            startMs = startMs,
            endMs = endMs,
            isMonthToDate = isMonthToDate,
            createdCount = created,
            completedCount = completed,
            cancelledCount = cancelled,
            carriedOverCount = carriedOver,
            completionRate = rate,
            weeklyCompletions = weeklyCompletions,
            topHabitWins = topHabitWins
        )
    }

    /**
     * Computes goal progress roll-up from sub-tasks and part_of edges.
     */
    fun buildGoalProgress(
        goals: List<Node>,
        edges: List<Edge>,
        allNodesMap: Map<String, Node>
    ): List<GoalProgress> {
        val progressList = mutableListOf<GoalProgress>()
        for (goal in goals) {
            val subTaskIds = edges.filter { edge ->
                (edge.dstId == goal.id && edge.type in listOf("part_of", "subtask")) ||
                (edge.srcId == goal.id && edge.type in listOf("has_subtask", "parent_of"))
            }.map { if (it.dstId == goal.id) it.srcId else it.dstId }.toSet()

            val subtasks = subTaskIds.mapNotNull { allNodesMap[it] }
            val total = subtasks.size
            val done = subtasks.count { it.status in listOf("done", "completed") }
            val percent = if (total > 0) (done.toFloat() / total.toFloat()) * 100f else 0f

            progressList.add(
                GoalProgress(
                    goalNodeId = goal.id,
                    goalTitle = goal.title,
                    totalSubTasks = total,
                    completedSubTasks = done,
                    progressPercent = percent
                )
            )
        }
        return progressList
    }

    /**
     * Summarizes habit streaks.
     */
    fun buildHabitStreaks(habitNodes: List<Node>): List<HabitStreakSummary> {
        return habitNodes.map {
            HabitStreakSummary(
                habitNodeId = it.id,
                habitTitle = it.title,
                currentStreak = it.streakCurrent,
                bestStreak = it.streakBest,
                lastCompletedAt = it.lastCompletedAt,
                dueAt = it.dueAt
            )
        }.sortedByDescending { it.currentStreak }
    }

    /**
     * Computes 24-hour activity heat strip across the hours of the day (0..23).
     */
    fun buildActivityHeatStrip(
        actionTimestamps: List<Long>,
        tzId: String = dayRule.tzId
    ): ActivityHeatStrip {
        val tz = try { TimeZone.getTimeZone(tzId) } catch (_: Exception) { TimeZone.getDefault() }
        val cal = Calendar.getInstance(tz)
        val bins = IntArray(24)

        for (ts in actionTimestamps) {
            cal.timeInMillis = ts
            val hour = cal.get(Calendar.HOUR_OF_DAY)
            if (hour in 0..23) {
                bins[hour]++
            }
        }

        val peakHour = bins.indices.maxByOrNull { bins[it] } ?: 0
        return ActivityHeatStrip(
            hourlyCounts = bins.toList(),
            peakHour = peakHour,
            totalActions = actionTimestamps.size
        )
    }

    /**
     * Slices opt-in mood trends from signal logs.
     * Blueprint 32 §3.6, Parking Lot P-008.
     */
    fun buildMoodTrends(
        moodSignals: List<SignalLog>,
        optInEnabled: Boolean,
        rule: DayRule = dayRule
    ): List<MoodPoint> {
        if (!optInEnabled) return emptyList()

        return moodSignals.map { signal ->
            val scoreVal = signal.value?.toFloat() ?: 3.0f
            val noteVal = signal.attrs
            val dKey = DayRollover.computeDayKey(signal.tsUtc, rule)
            MoodPoint(
                dayKey = dKey,
                score = scoreVal,
                note = noteVal,
                recordedAt = signal.tsUtc
            )
        }.sortedBy { it.recordedAt }
    }
}
