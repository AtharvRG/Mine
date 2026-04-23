package com.mine.app

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.mine.app.ui.DebugViewModel
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val viewModel: DebugViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    DebugScreen(viewModel)
                }
            }
        }
    }
}

@Composable
fun DebugScreen(viewModel: DebugViewModel) {
    val logText by viewModel.logText.collectAsState()
    val isOnline by viewModel.isOnline.collectAsState()
    val scrollState = rememberScrollState()
    var manualTaskText by remember { mutableStateOf("") }
    var isRecordingUI by remember { mutableStateOf(false) }

    // Request Microphone Permission on Launch
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            viewModel.appendLog("❌ Microphone permission denied! Voice features disabled.")
        } else {
            viewModel.appendLog("✅ Microphone permission granted.")
        }
    }

    LaunchedEffect(Unit) {
        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    Column(modifier = Modifier.fillMaxSize()) {

        // --- THE OFFLINE BANNER ---
        if (!isOnline) {
            Box(
                modifier = Modifier.fillMaxWidth().background(Color.Red).padding(8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("OFFLINE MODE - AI Paused. Manual entry only.", color = Color.White)
            }
        } else {
            Box(
                modifier = Modifier.fillMaxWidth().background(Color(0xFF4CAF50)).padding(8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("ONLINE - Connected to MineAI", color = Color.White)
            }
        }

        Column(modifier = Modifier.padding(16.dp)) {

            // --- HOLD TO RECORD BUTTON (The Ears) ---
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(80.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (isRecordingUI) Color.Red else Color(0xFF2196F3))
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onPress = {
                                // Finger goes down
                                isRecordingUI = true
                                viewModel.startRecording()

                                tryAwaitRelease() // Wait for finger to lift

                                // Finger goes up
                                isRecordingUI = false
                                viewModel.stopRecordingAndProcess()
                            }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (isRecordingUI) "🎙️ RECORDING... RELEASE TO SEND" else "🎤 HOLD TO RECORD",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(16.dp))

            // --- MANUAL ENTRY UI ---
            OutlinedTextField(
                value = manualTaskText,
                onValueChange = { manualTaskText = it },
                label = { Text("Add Manual To-Do") },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = {
                    if (manualTaskText.isNotBlank()) {
                        viewModel.addManualTodo(manualTaskText)
                        manualTaskText = ""
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Save to Graph Database")
            }

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = { viewModel.fetchAndDisplayDatabase() },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray)
            ) {
                Text("Refresh Database View")
            }

            Spacer(modifier = Modifier.height(16.dp))

            // --- THE TERMINAL ---
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(Color.Black)
                    .padding(8.dp)
                    .verticalScroll(scrollState)
            ) {
                Text(
                    text = logText,
                    color = Color.Green,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}
