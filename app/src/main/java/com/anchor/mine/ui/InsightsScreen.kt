// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.mine.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.anchor.core.insights.DayBucket
import com.anchor.core.insights.FullInsightsReport
import com.anchor.core.insights.GoalProgress
import com.anchor.core.insights.HabitStreakSummary
import com.anchor.core.insights.MoodPoint
import com.anchor.core.ui.UiAction
import com.anchor.core.ui.UiState

@Composable
fun InsightsScreen(
    state: UiState,
    onAction: (UiAction) -> Unit
) {
    var selectedDays by remember { mutableIntStateOf(7) }

    LaunchedEffect(selectedDays) {
        onAction(UiAction.LoadInsights(selectedDays))
    }

    val report = state.latestInsightsReport
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp)
            .testTag("insightsScreen")
    ) {
        Text(
            text = "Insights & Analytics",
            style = MaterialTheme.typography.headlineMedium
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Time window selector
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = selectedDays == 7,
                onClick = { selectedDays = 7 },
                label = { Text("7 Days") }
            )
            FilterChip(
                selected = selectedDays == 30,
                onClick = { selectedDays = 30 },
                label = { Text("30 Days") }
            )
            FilterChip(
                selected = selectedDays == 90,
                onClick = { selectedDays = 90 },
                label = { Text("90 Days") }
            )
            Spacer(modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { onAction(UiAction.LoadInsights(selectedDays)) }) {
                Text("Refresh")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (report == null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("No insights generated yet for this period.", style = MaterialTheme.typography.bodyLarge)
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(onClick = { onAction(UiAction.LoadInsights(selectedDays)) }) {
                        Text("Calculate Insights")
                    }
                }
            }
        } else {
            // 1. Weekly / Period Review Card
            WeeklyReviewSummaryCard(report)

            Spacer(modifier = Modifier.height(16.dp))

            // 2. 7-Day Completion Velocity Chart (week_bars)
            WeekBarsCard(report.weeklyReview.dailyBars)

            Spacer(modifier = Modifier.height(16.dp))

            // 3. 24-Hour Activity Heat Strip
            ActivityHeatStripCard(report.activityHeatStrip.hourlyCounts)

            Spacer(modifier = Modifier.height(16.dp))

            // 4. Goals Hierarchy Progress
            GoalProgressCard(report.goalProgress)

            Spacer(modifier = Modifier.height(16.dp))

            // 5. Habit Streaks Ranking
            HabitStreaksCard(report.habitStreaks)

            // 6. Reflection Mood Trend (if enabled)
            if (report.moodTrends.isNotEmpty()) {
                Spacer(modifier = Modifier.height(16.dp))
                MoodLineCard(report.moodTrends)
            }
        }
    }
}

@Composable
fun WeeklyReviewSummaryCard(report: FullInsightsReport) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Completion Velocity",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val pct = (report.weeklyReview.completionRate * 100).toInt()
                Text(
                    text = "$pct%",
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Column(horizontalAlignment = Alignment.End) {
                    Text("Created: ${report.weeklyReview.createdCount}", style = MaterialTheme.typography.bodyMedium)
                    Text("Completed: ${report.weeklyReview.completedCount}", style = MaterialTheme.typography.bodyMedium)
                    Text("Backlog: ${report.weeklyReview.carriedOverCount}", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
fun WeekBarsCard(bars: List<DayBucket>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Daily Created vs Closed", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(12.dp))
            if (bars.isEmpty()) {
                Text("No activity recorded for this period.", style = MaterialTheme.typography.bodySmall)
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    bars.forEach { bucket ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(bucket.dayKey.takeLast(5), style = MaterialTheme.typography.labelSmall)
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                verticalAlignment = Alignment.Bottom,
                                horizontalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                val createdHeight = (bucket.createdCount * 10).coerceIn(4, 80).dp
                                val completedHeight = (bucket.completedCount * 10).coerceIn(4, 80).dp
                                Box(
                                    modifier = Modifier
                                        .width(8.dp)
                                        .height(createdHeight)
                                        .background(Color(0xFF64B5F6), RoundedCornerShape(2.dp))
                                )
                                Box(
                                    modifier = Modifier
                                        .width(8.dp)
                                        .height(completedHeight)
                                        .background(Color(0xFF81C784), RoundedCornerShape(2.dp))
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("${bucket.completedCount}/${bucket.createdCount}", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ActivityHeatStripCard(bins: List<Int>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("24-Hour Activity Heat Strip", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            val maxCount = (bins.maxOrNull() ?: 1).coerceAtLeast(1)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                bins.forEachIndexed { hour, count ->
                    val intensity = (count.toFloat() / maxCount).coerceIn(0f, 1f)
                    val color = when {
                        count == 0 -> MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)
                        intensity < 0.33f -> Color(0xFFC8E6C9)
                        intensity < 0.66f -> Color(0xFF81C784)
                        else -> Color(0xFF2E7D32)
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(24.dp)
                            .padding(horizontal = 1.dp)
                            .background(color, RoundedCornerShape(2.dp))
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("12am", style = MaterialTheme.typography.labelSmall)
                Text("6am", style = MaterialTheme.typography.labelSmall)
                Text("12pm", style = MaterialTheme.typography.labelSmall)
                Text("6pm", style = MaterialTheme.typography.labelSmall)
                Text("11pm", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
fun GoalProgressCard(goals: List<GoalProgress>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Active Goals & Subtasks", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            if (goals.isEmpty()) {
                Text("No active goals found. Set items as goals or link subtasks via follow-ups.", style = MaterialTheme.typography.bodySmall)
            } else {
                goals.forEach { goal ->
                    Column(modifier = Modifier.padding(vertical = 6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(goal.goalTitle, style = MaterialTheme.typography.bodyMedium)
                            Text("${goal.completedSubTasks}/${goal.totalSubTasks}", style = MaterialTheme.typography.labelSmall)
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        val progress = if (goal.totalSubTasks > 0) goal.completedSubTasks.toFloat() / goal.totalSubTasks else 0f
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth().height(6.dp),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun HabitStreaksCard(streaks: List<HabitStreakSummary>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Habit Streaks", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            if (streaks.isEmpty()) {
                Text("No active habits tracked yet.", style = MaterialTheme.typography.bodySmall)
            } else {
                streaks.forEach { habit ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(habit.habitTitle, style = MaterialTheme.typography.bodyMedium)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("🔥 ${habit.currentStreak}d", style = MaterialTheme.typography.labelLarge, color = Color(0xFFE65100))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("(Best: ${habit.bestStreak}d)", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MoodLineCard(moodPoints: List<MoodPoint>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Reflection Mood Trend", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                moodPoints.forEach { point ->
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surface
                    ) {
                        Column(modifier = Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(point.dayKey.takeLast(5), style = MaterialTheme.typography.labelSmall)
                            Text(
                                text = "Score: ${"%.1f".format(point.score)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (point.score >= 0f) Color(0xFF2E7D32) else Color(0xFFC62828)
                            )
                        }
                    }
                }
            }
        }
    }
}
