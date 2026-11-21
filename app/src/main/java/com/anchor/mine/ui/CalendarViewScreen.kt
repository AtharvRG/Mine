// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.mine.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.anchor.core.models.Node
import com.anchor.core.storage.Storage
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Calendar Grid & Agenda View per Blueprint 51 §50 & Roadmap v1.1 Item 8.
 * Groups tasks due by dayKey.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun CalendarViewScreen(
    storage: Storage?,
    state: com.anchor.core.ui.UiState? = null,
    onAction: ((com.anchor.core.ui.UiAction) -> Unit)? = null,
    onNodeClick: (Node) -> Unit = {}
) {
    var calendarBuckets by remember { mutableStateOf<Map<String, List<Node>>>(emptyMap()) }
    var selectedDayKey by remember { mutableStateOf<String?>(null) }
    var bottomSheetDayKey by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var currentRule by remember { mutableStateOf(com.anchor.core.time.DayRollover.defaultRule()) }
    val scope = rememberCoroutineScope()

    val refreshCalendar = {
        if (storage != null) {
            scope.launch {
                isLoading = true
                val now = System.currentTimeMillis()
                val startMs = now - 7 * 86_400_000L
                val endMs = now + 30 * 86_400_000L
                val buckets = storage.getCalendarBuckets(startMs, endMs).toMutableMap()
                val rule = storage.getEffectiveDayRule(now)
                currentRule = rule
                val startDayKey = com.anchor.core.time.DayRollover.computeDayKey(startMs, rule)
                val endDayKey = com.anchor.core.time.DayRollover.computeDayKey(endMs, rule)
                val daysWithMsgs = try { storage.getDaysWithMessages(startDayKey, endDayKey) } catch (_: Exception) { emptySet() }
                val reminders = try { storage.getRemindersBetween(startMs, endMs) } catch (_: Exception) { emptyList() }

                for (day in daysWithMsgs) {
                    if (!buckets.containsKey(day)) {
                        buckets[day] = emptyList()
                    }
                }
                for (rem in reminders) {
                    val dayKey = com.anchor.core.time.DayRollover.computeDayKey(rem.fireAtMillis, rule)
                    if (!buckets.containsKey(dayKey)) {
                        buckets[dayKey] = emptyList()
                    }
                }
                calendarBuckets = buckets
                if (selectedDayKey == null) {
                    val todayKey = com.anchor.core.time.DayRollover.computeDayKey(now, rule)
                    selectedDayKey = todayKey
                    val startMs = com.anchor.core.time.DayRollover.getLogicalDayStart(now, rule)
                    val endMs = startMs + 86_400_000L - 1
                    onAction?.invoke(com.anchor.core.ui.UiAction.LoadDay(todayKey))
                    onAction?.invoke(com.anchor.core.ui.UiAction.ShowDayReminders(todayKey, startMs, endMs))
                }
                isLoading = false
            }
        }
    }

    LaunchedEffect(storage) {
        refreshCalendar()
    }

    Column(modifier = Modifier.fillMaxSize().padding(8.dp).testTag("calendarViewScreen")) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Calendar & Agenda", style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { refreshCalendar() }) {
                Text("Refresh")
            }
        }

        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Loading calendar...")
            }
            return
        }

        // Days list
        val dayKeys = calendarBuckets.keys.sorted()
        if (dayKeys.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No scheduled items in the next 30 days.")
            }
            return
        }

        LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(dayKeys) { dayKey ->
                val nodes = calendarBuckets[dayKey] ?: emptyList()
                val isSelected = selectedDayKey == dayKey

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable(
                            onClick = {
                                val nextSelected = if (isSelected) null else dayKey
                                selectedDayKey = nextSelected
                                if (nextSelected != null) {
                                    onAction?.invoke(com.anchor.core.ui.UiAction.LoadDay(nextSelected))
                                    try {
                                        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
                                            timeZone = com.anchor.core.time.DayRollover.safeTimeZone(currentRule.tzId)
                                        }
                                        val date = sdf.parse(nextSelected)
                                        if (date != null) {
                                            val startMs = com.anchor.core.time.DayRollover.getLogicalDayStart(date.time + 12 * 3600_000L, currentRule)
                                            val endMs = startMs + 86_400_000L - 1
                                            onAction?.invoke(com.anchor.core.ui.UiAction.ShowDayReminders(nextSelected, startMs, endMs))
                                        }
                                    } catch (e: Exception) {
                                        android.util.Log.w("CalendarViewScreen", "Failed parsing date for reminders: ${e.message}")
                                    }
                                } else {
                                    onAction?.invoke(com.anchor.core.ui.UiAction.ClearDaySelection)
                                }
                            },
                            onLongClick = {
                                bottomSheetDayKey = dayKey
                            }
                        )
                        .testTag("calendarDay-$dayKey"),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(dayKey, style = MaterialTheme.typography.titleSmall)
                            Text("${nodes.size} items", style = MaterialTheme.typography.bodySmall)
                        }

                        if (isSelected) {
                            Spacer(modifier = Modifier.height(8.dp))
                            val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
                            nodes.forEach { node ->
                                val dueAnnotated: AnnotatedString? = if (node.dueAt != null) {
                                    if (node.originalDueAt != null && node.originalDueAt != node.dueAt && node.slipCount > 0) {
                                        val oldDueStr = timeFormat.format(Date(node.originalDueAt!!))
                                        val newDueStr = timeFormat.format(Date(node.dueAt!!))
                                        buildAnnotatedString {
                                            withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))) {
                                                append(oldDueStr)
                                            }
                                            append(" → $newDueStr (Slipped ${node.slipCount}x)")
                                        }
                                    } else {
                                        AnnotatedString(timeFormat.format(Date(node.dueAt!!)))
                                    }
                                } else null
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp)
                                        .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(4.dp))
                                        .padding(8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(node.title, style = MaterialTheme.typography.bodyMedium)
                                        if (dueAnnotated != null) {
                                            Text(dueAnnotated, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                                        }
                                    }
                                    Text(node.status, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                                }
                            }

                            if (state != null && state.selectedDayReminders.isNotEmpty() && state.selectedDayKey == dayKey) {
                                Spacer(modifier = Modifier.height(6.dp))
                                Text("⏰ Reminders", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                state.selectedDayReminders.forEach { rem ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 2.dp)
                                            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(4.dp))
                                            .padding(6.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(rem.text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                                        Text(rem.tier.name.lowercase(), style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }

                            if (state != null && state.selectedDayMessages.isNotEmpty() && state.selectedDayKey == dayKey) {
                                Spacer(modifier = Modifier.height(6.dp))
                                Text("💬 Conversation (${state.selectedDayMessages.size})", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                                state.selectedDayMessages.take(5).forEach { msg ->
                                    Text(
                                        text = "${msg.role}: ${msg.content ?: "[redacted]"}",
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 2,
                                        modifier = Modifier.padding(vertical = 2.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        if (bottomSheetDayKey != null) {
            val sheetDay = bottomSheetDayKey!!
            val sheetNodes = calendarBuckets[sheetDay] ?: emptyList()
            ModalBottomSheet(
                onDismissRequest = { bottomSheetDayKey = null },
                modifier = Modifier.testTag("calendarDayBottomSheet")
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = "Day Agenda: $sheetDay",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                    if (sheetNodes.isEmpty()) {
                        Text("No scheduled items for this day.", style = MaterialTheme.typography.bodyMedium)
                    } else {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
                        ) {
                            items(sheetNodes) { node ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                                        .padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                        Text(node.title, style = MaterialTheme.typography.bodyMedium)
                                        Text(
                                            "Status: ${node.status}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (node.status == "done") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                                        )
                                    }
                                    Button(
                                        onClick = {
                                            val nextStatus = if (node.status == "done") "active" else "done"
                                            onAction?.invoke(com.anchor.core.ui.UiAction.UpdateNodeStatus(node.id, nextStatus))
                                            refreshCalendar()
                                        },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = if (node.status == "done") MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary
                                        )
                                    ) {
                                        Text(if (node.status == "done") "Reopen" else "Mark Done")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
