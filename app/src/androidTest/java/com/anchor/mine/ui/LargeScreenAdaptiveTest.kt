// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.mine.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.anchor.core.insights.ActivityHeatStrip
import com.anchor.core.insights.DayBucket
import com.anchor.core.insights.FullInsightsReport
import com.anchor.core.insights.GoalProgress
import com.anchor.core.insights.HabitStreakSummary
import com.anchor.core.insights.MonthlyReviewStats
import com.anchor.core.insights.MoodPoint
import com.anchor.core.insights.WeeklyReviewStats
import com.anchor.core.ui.UiState
import org.junit.Rule
import org.junit.Test

class LargeScreenAdaptiveTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun testInsightsScreen_RendersAllAnalyticsCards() {
        val dummyReport = FullInsightsReport(
            generatedAt = 1_700_000_000_000L,
            weeklyReview = WeeklyReviewStats(
                startMs = 1_700_000_000_000L - 7 * 86_400_000L,
                endMs = 1_700_000_000_000L,
                createdCount = 10,
                completedCount = 8,
                cancelledCount = 0,
                carriedOverCount = 2,
                completionRate = 0.8f,
                dailyBars = listOf(
                    DayBucket("2026-11-12", 5, 4),
                    DayBucket("2026-11-13", 5, 4)
                ),
                wins = listOf("Completed Project Titanium")
            ),
            monthlyReview = MonthlyReviewStats(
                startMs = 1_700_000_000_000L - 30 * 86_400_000L,
                endMs = 1_700_000_000_000L,
                isMonthToDate = false,
                createdCount = 40,
                completedCount = 35,
                cancelledCount = 0,
                carriedOverCount = 5,
                completionRate = 0.875f,
                weeklyCompletions = listOf(10, 8, 9, 8),
                topHabitWins = listOf("Morning Jog")
            ),
            goalProgress = listOf(
                GoalProgress("g1", "Complete Project Titanium", 10, 8, 0.8f)
            ),
            habitStreaks = listOf(
                HabitStreakSummary("h1", "Morning Jog", 5, 12, 1_700_000_000_000L, null)
            ),
            activityHeatStrip = ActivityHeatStrip(
                hourlyCounts = List(24) { if (it in 9..17) 5 else 0 },
                peakHour = 14,
                totalActions = 45
            ),
            moodTrends = listOf(
                MoodPoint("2026-11-13", 0.75f, "Great day", 1_700_000_000_000L)
            )
        )

        compose.setContent {
            InsightsScreen(
                state = UiState(latestInsightsReport = dummyReport),
                onAction = {}
            )
        }

        compose.onNodeWithTag("insightsScreen").assertIsDisplayed()
        compose.onNodeWithText("Completion Velocity").assertIsDisplayed()
        compose.onNodeWithText("80%").assertIsDisplayed()
        compose.onNodeWithText("Daily Created vs Closed").assertIsDisplayed()
        compose.onNodeWithText("24-Hour Activity Heat Strip").assertIsDisplayed()
        compose.onNodeWithText("Active Goals & Subtasks").assertIsDisplayed()
        compose.onNodeWithText("Complete Project Titanium").assertIsDisplayed()
        compose.onNodeWithText("Habit Streaks").assertIsDisplayed()
        compose.onNodeWithText("Morning Jog").assertIsDisplayed()
    }

    @Test
    fun testInsightsScreen_EmptyReport_RendersCalculatePrompt() {
        compose.setContent {
            InsightsScreen(
                state = UiState(latestInsightsReport = null),
                onAction = {}
            )
        }

        compose.onNodeWithTag("insightsScreen").assertIsDisplayed()
        compose.onNodeWithText("No insights generated yet for this period.").assertIsDisplayed()
        compose.onNodeWithText("Calculate Insights").assertIsDisplayed()
    }
}
