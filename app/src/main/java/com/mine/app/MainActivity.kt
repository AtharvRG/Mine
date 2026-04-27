package com.mine.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.mine.app.ui.DebugViewModel
import dagger.hilt.android.AndroidEntryPoint
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat


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
    val isRecording by viewModel.isRecording.collectAsState() // <-- NEW
    val scrollState = rememberScrollState()

    var manualTaskText by remember { mutableStateOf("") }
    val context = LocalContext.current

    // --- PERMISSION LAUNCHER ---
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            viewModel.toggleRecording()
        } else {
            viewModel.appendLog("❌ Microphone permission denied.")
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
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

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = { viewModel.runRealAiTest() },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF673AB7))
            ) {
                Text("Test Real NVIDIA NIM API")
            }

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = { viewModel.runFullCuratorLoop() },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE91E63))
            ) {
                Text("Run Full AI -> DB Loop")
            }

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = { viewModel.fetchAndDisplayDatabase() },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray)
            ) {
                Text("Refresh Database View")
            }

            Button(
                onClick = {
                    // Check for permission before recording
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        viewModel.toggleRecording()
                    } else {
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
                modifier = Modifier.fillMaxWidth().height(60.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isRecording) Color.Red else Color(0xFF2196F3)
                )
            ) {
                Text(if (isRecording) "🛑 Stop & Transcribe" else "🎙️ Start Recording")
            }

            Spacer(modifier = Modifier.height(16.dp))


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
