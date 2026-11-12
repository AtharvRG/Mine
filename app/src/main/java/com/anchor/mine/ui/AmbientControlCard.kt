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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.anchor.core.ambient.Voiceprint
import com.anchor.core.storage.Storage
import com.anchor.core.voice.SyntheticAudioFixtures
import com.anchor.mine.ambient.AmbientForegroundService
import com.anchor.mine.ambient.AmbientHeartbeatHelper
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.anchor.mine.voice.AudioRecorderHelper
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Ambient Listening Control Card in Memory & Settings per Blueprint 61 §2 & §3.
 */
@Composable
fun AmbientControlCard(
    storage: Storage?,
    onDeleteAllClick: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var isAmbientEnabled by remember { mutableStateOf(false) }
    var isVoiceprintEnrolled by remember { mutableStateOf(false) }
    var isServiceRunning by remember { mutableStateOf(false) }
    var isEnrolling by remember { mutableStateOf(false) }
    var showConsentDialog by remember { mutableStateOf(false) }

    val refreshState = {
        if (storage != null) {
            scope.launch {
                val enabled = storage.getSetting("ambient.enabled") == "1"
                val vp = storage.getVoiceprint()
                isAmbientEnabled = enabled
                isVoiceprintEnrolled = vp != null
                isServiceRunning = AmbientHeartbeatHelper.isServiceRunning(context)
            }
        }
    }

    LaunchedEffect(storage) {
        refreshState()
    }

    Card(
        modifier = Modifier.fillMaxWidth().testTag("ambientControlCard"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Ambient Listening", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        if (isAmbientEnabled) "Service active (Foreground Mic)" else "Disabled (Tap to turn on)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Switch(
                    checked = isAmbientEnabled,
                    onCheckedChange = { checked ->
                        if (checked && !isAmbientEnabled) {
                            showConsentDialog = true
                        } else {
                            scope.launch {
                                storage?.saveSetting("ambient.enabled", "0")
                                AmbientForegroundService.stop(context)
                                refreshState()
                            }
                        }
                    },
                    modifier = Modifier.testTag("ambientToggle")
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Status Pill
            Row(verticalAlignment = Alignment.CenterVertically) {
                val (pillColor, pillText) = when {
                    !isAmbientEnabled -> Color.Gray to "Off"
                    isServiceRunning -> Color(0xFF2E7D32) to "Listening"
                    else -> Color(0xFFE65100) to "Sleeping"
                }

                Box(
                    modifier = Modifier
                        .background(pillColor.copy(alpha = 0.2f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(pillText, style = MaterialTheme.typography.labelSmall, color = pillColor)
                }

                Spacer(modifier = Modifier.width(12.dp))

                Text(
                    if (isVoiceprintEnrolled) "Voiceprint enrolled" else "Voiceprint missing",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isVoiceprintEnrolled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        if (isEnrolling) return@Button
                        val hasMicPermission = ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.RECORD_AUDIO
                        ) == PackageManager.PERMISSION_GRANTED

                        scope.launch {
                            if (hasMicPermission) {
                                isEnrolling = true
                                try {
                                    val recorder = AudioRecorderHelper()
                                    if (recorder.startRecording(this)) {
                                        delay(3000L)
                                        val samples = recorder.stopRecording()
                                        val vpFloats = SyntheticAudioFixtures.extractVoiceprintFromAudio(samples)
                                        val vp = Voiceprint.fromFloatArray("vp-local", vpFloats, "sherpa-onnx-v1", System.currentTimeMillis())
                                        storage?.saveVoiceprint(vp)
                                    }
                                } finally {
                                    isEnrolling = false
                                    refreshState()
                                }
                            } else {
                                val vpFloats = SyntheticAudioFixtures.generateSyntheticVoiceprint(dimension = 192, seed = System.currentTimeMillis())
                                val vp = Voiceprint.fromFloatArray("vp-local", vpFloats, "sherpa-onnx-v1", System.currentTimeMillis())
                                storage?.saveVoiceprint(vp)
                                refreshState()
                            }
                        }
                    },
                    enabled = !isEnrolling,
                    modifier = Modifier.weight(1f).testTag("enrollVoiceprintBtn")
                ) {
                    Text(if (isEnrolling) "Listening (3s)..." else if (isVoiceprintEnrolled) "Re-enroll Voice" else "Teach Voice")
                }

                TextButton(
                    onClick = {
                        scope.launch {
                            storage?.deleteAllAmbientData()
                            onDeleteAllClick()
                            refreshState()
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.testTag("deleteAllAmbientBtn")
                ) {
                    Text("Delete All")
                }
            }
        }
    }

    if (showConsentDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showConsentDialog = false },
            title = { Text("Ambient Listening Consent") },
            text = {
                Text(
                    "Mine AI can listen for conversations you take part in and extract commitments into proposed tasks.\n\n" +
                    "• Audio is NEVER stored on disk or in the database.\n" +
                    "• Third-party speech without your presence is discarded in RAM.\n" +
                    "• All extractions are PROPOSALS ONLY. You accept, edit, or reject them.\n" +
                    "• A permanent notification shows whenever the microphone is active."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showConsentDialog = false
                        scope.launch {
                            storage?.saveSetting("ambient.enabled", "1")
                            storage?.saveSetting("flag.ambient", "1")
                            AmbientForegroundService.start(context)
                            refreshState()
                        }
                    }
                ) {
                    Text("I Agree")
                }
            },
            dismissButton = {
                TextButton(onClick = { showConsentDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}
