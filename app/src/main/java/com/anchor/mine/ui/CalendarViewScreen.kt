// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.mine.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
@Composable
fun CalendarViewScreen(
    storage: Storage?,
    onNodeClick: (Node) -> Unit = {}
) {
    var calendarBuckets by remember { mutableStateOf<Map<String, List<Node>>>(emptyMap()) }
    var selectedDayKey by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    val refreshCalendar = {
        if (storage != null) {
            scope.launch {
                isLoading = true
                val now = System.currentTimeMillis()
                val startMs = now - 7 * 86_400_000L
                val endMs = now + 30 * 86_400_000L
                val buckets = storage.getCalendarBuckets(startMs, endMs)
                calendarBuckets = buckets
                if (selectedDayKey == null) {
                    val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                    selectedDayKey = sdf.format(Date(now))
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
                        .clickable { selectedDayKey = if (isSelected) null else dayKey }
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
                            nodes.forEach { node ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp)
                                        .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(4.dp))
                                        .padding(8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(node.title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                    Text(node.status, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
