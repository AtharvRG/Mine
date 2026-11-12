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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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

/**
 * Kanban-style Items Board View per Blueprint 51 §50/§199 & Roadmap v1.1 Item 8.
 * Groups tasks into 3 columns: "todo", "in_progress", and "done".
 */
@Composable
fun BoardViewScreen(
    storage: Storage?,
    onActionClick: (nodeId: String, newStatus: String) -> Unit = { _, _ -> }
) {
    var boardNodes by remember { mutableStateOf<Map<String, List<Node>>>(emptyMap()) }
    var isLoading by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    val refreshBoard = {
        if (storage != null) {
            scope.launch {
                isLoading = true
                boardNodes = storage.getBoardViewNodes(limitPerColumn = 30)
                isLoading = false
            }
        }
    }

    LaunchedEffect(storage) {
        refreshBoard()
    }

    Column(modifier = Modifier.fillMaxSize().padding(8.dp).testTag("boardViewScreen")) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Items Board", style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { refreshBoard() }) {
                Text("Refresh")
            }
        }

        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Loading board...")
            }
            return
        }

        val scrollState = rememberScrollState()
        Row(
            modifier = Modifier.fillMaxSize().horizontalScroll(scrollState),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            BoardColumn(
                title = "To Do",
                nodes = boardNodes["todo"] ?: emptyList(),
                tag = "boardCol-todo",
                onAction = { id -> onActionClick(id, "in_progress"); refreshBoard() },
                actionLabel = "Start"
            )

            BoardColumn(
                title = "In Progress",
                nodes = boardNodes["in_progress"] ?: emptyList(),
                tag = "boardCol-in_progress",
                onAction = { id -> onActionClick(id, "done"); refreshBoard() },
                actionLabel = "Done"
            )

            BoardColumn(
                title = "Done",
                nodes = boardNodes["done"] ?: emptyList(),
                tag = "boardCol-done",
                onAction = { id -> onActionClick(id, "active"); refreshBoard() },
                actionLabel = "Reopen"
            )
        }
    }
}

@Composable
private fun BoardColumn(
    title: String,
    nodes: List<Node>,
    tag: String,
    onAction: (String) -> Unit,
    actionLabel: String
) {
    Column(
        modifier = Modifier
            .width(260.dp)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
            .padding(8.dp)
            .testTag(tag)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text("(${nodes.size})", style = MaterialTheme.typography.bodySmall)
        }

        if (nodes.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                Text("No items", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(nodes) { node ->
                    Card(
                        modifier = Modifier.fillMaxWidth().testTag("boardCard-${node.id}"),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                    ) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            Text(node.title, style = MaterialTheme.typography.bodyMedium)
                            if (node.streakCurrent > 0) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text("🔥 Streak: ${node.streakCurrent} d", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            }
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                TextButton(onClick = { onAction(node.id) }) {
                                    Text(actionLabel, style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
