// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.mine.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.anchor.core.ambient.AmbientReport
import com.anchor.core.ambient.AmbientReportItem
import com.anchor.core.ui.UiAction

/**
 * Daily Ambient Report card in Chat & Memory Timeline per Blueprint 61 §3.8.
 */
@Composable
fun AmbientReportCard(
    report: AmbientReport,
    items: List<AmbientReportItem>,
    onAction: (UiAction) -> Unit = {}
) {
    if (items.isEmpty()) return

    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag("ambientReportCard-${report.id}"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Ambient Daily Report", style = MaterialTheme.typography.titleSmall)
                    Text("Day: ${report.dayKey}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                TextButton(
                    onClick = { onAction(UiAction.AcceptAllAmbientItems(report.id)) },
                    modifier = Modifier.testTag("acceptAllAmbientBtn")
                ) {
                    Text("Accept All")
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            items.forEach { item ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp))
                        .padding(8.dp)
                        .testTag("ambientReportItem-${item.id}"),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(item.summary, style = MaterialTheme.typography.bodyMedium)

                        val flags = item.flags ?: ""
                        if (flags.isNotBlank()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                if (flags.contains("attribution_uncertain")) {
                                    BadgePill("attribution uncertain", Color(0xFFE65100))
                                }
                                if (flags.contains("possible_duplicate")) {
                                    BadgePill("possible duplicate", Color(0xFF1565C0))
                                }
                            }
                        }
                    }

                    if (item.state == "pending") {
                        Row {
                            TextButton(
                                onClick = { onAction(UiAction.AcceptAmbientItem(item.id)) },
                                modifier = Modifier.testTag("acceptItemBtn-${item.id}")
                            ) {
                                Text("Accept")
                            }
                            TextButton(
                                onClick = { onAction(UiAction.RejectAmbientItem(item.id)) },
                                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                modifier = Modifier.testTag("rejectItemBtn-${item.id}")
                            ) {
                                Text("Reject")
                            }
                        }
                    } else {
                        Text(
                            item.state.replaceFirstChar { it.uppercase() },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.padding(horizontal = 8.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BadgePill(text: String, color: Color) {
    Box(
        modifier = Modifier
            .background(color.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = color)
    }
}
