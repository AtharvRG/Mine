// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.insights

import kotlinx.serialization.Serializable

/**
 * Daily bucket for week_bars native chart card.
 * Blueprint 32 §3.6.
 */
@Serializable
data class DayBucket(
    val dayKey: String,
    val createdCount: Int,
    val completedCount: Int
)

/**
 * Weekly Review aggregated statistics and chart card source.
 * Blueprint 32 §3.5 & §3.6.
 */
@Serializable
data class WeeklyReviewStats(
    val startMs: Long,
    val endMs: Long,
    val createdCount: Int,
    val completedCount: Int,
    val cancelledCount: Int,
    val carriedOverCount: Int,
    val completionRate: Float,
    val dailyBars: List<DayBucket>,
    val wins: List<String>
)

/**
 * Monthly Review and Month-to-Date aggregated statistics.
 * Blueprint 32 §3.5, Parking Lot P-007.
 */
@Serializable
data class MonthlyReviewStats(
    val startMs: Long,
    val endMs: Long,
    val isMonthToDate: Boolean,
    val createdCount: Int,
    val completedCount: Int,
    val cancelledCount: Int,
    val carriedOverCount: Int,
    val completionRate: Float,
    val weeklyCompletions: List<Int>,
    val topHabitWins: List<String>
)

/**
 * Goal progress roll-up from sub-tasks and part_of edges.
 * Blueprint 32 §3.6 (goal_progress native card).
 */
@Serializable
data class GoalProgress(
    val goalNodeId: String,
    val goalTitle: String,
    val totalSubTasks: Int,
    val completedSubTasks: Int,
    val progressPercent: Float
)

/**
 * Habit streak status and consistency summary.
 * Blueprint 32 §3.6 (streaks native card).
 */
@Serializable
data class HabitStreakSummary(
    val habitNodeId: String,
    val habitTitle: String,
    val currentStreak: Int,
    val bestStreak: Int,
    val lastCompletedAt: Long?,
    val dueAt: Long?
)

/**
 * 24-hour activity heat strip across the hours of the day (0..23).
 * Blueprint 32 §3.6 (activity_strip native card).
 */
@Serializable
data class ActivityHeatStrip(
    val hourlyCounts: List<Int>, // Size 24: index 0..23
    val peakHour: Int,
    val totalActions: Int
)

/**
 * Self-reported day feel / sentiment over time.
 * Blueprint 32 §3.6, Parking Lot P-008 (opt-in mood_line card).
 */
@Serializable
data class MoodPoint(
    val dayKey: String,
    val score: Float,
    val note: String?,
    val recordedAt: Long
)

/**
 * Unified container for all Insights & Analytics data.
 * v4 Roadmap line 52.
 */
@Serializable
data class FullInsightsReport(
    val generatedAt: Long,
    val weeklyReview: WeeklyReviewStats,
    val monthlyReview: MonthlyReviewStats,
    val goalProgress: List<GoalProgress>,
    val habitStreaks: List<HabitStreakSummary>,
    val activityHeatStrip: ActivityHeatStrip,
    val moodTrends: List<MoodPoint>
)
