package com.mine.app.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

@Composable
fun MainAppScreen(debugViewModel: DebugViewModel, chatViewModel: ChatViewModel) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Database", "Chat", "Ambient")
    val icons = listOf(Icons.Default.Storage, Icons.Default.Chat, Icons.Default.Mic)

    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { index, title ->
                    NavigationBarItem(
                        icon = { Icon(icons[index], contentDescription = title) },
                        label = { Text(title) },
                        selected = selectedTab == index,
                        onClick = { selectedTab = index }
                    )
                }
            }
        }
    ) { paddingValues ->
        Box(modifier = Modifier.padding(paddingValues).fillMaxSize()) {
            when (selectedTab) {
                0 -> DatabaseTab(debugViewModel)
                1 -> ChatScreen(chatViewModel) // The Chat UI we built earlier!
                2 -> AmbientTab(debugViewModel)
            }
        }
    }
}

@Composable
fun DatabaseTab(viewModel: DebugViewModel) {
    var manualTaskText by remember { mutableStateOf("") }
    val logs by viewModel.logs.collectAsState()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Database & Chronos Manager", style = MaterialTheme.typography.headlineSmall)
        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = manualTaskText,
            onValueChange = { manualTaskText = it },
            label = { Text("Add Manual Node") },
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

        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = { viewModel.forceChronosWakeup() },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9800))
        ) {
            Text("Force Proactive AI Check (Chronos)")
        }

        Spacer(modifier = Modifier.height(16.dp))
        TerminalView(logs = logs)
    }
}

@Composable
fun AmbientTab(viewModel: DebugViewModel) {
    val isOnline by viewModel.isOnline.collectAsState()
    val isRecording by viewModel.isRecording.collectAsState()
    val isAmbientRunning by viewModel.isAmbientRunning.collectAsState()
    val logs by viewModel.logs.collectAsState()
    val context = LocalContext.current

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.RECORD_AUDIO] == true) {
            viewModel.toggleRecording()
        } else {
            viewModel.appendLog("❌ Microphone permission denied.")
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (!isOnline) {
            Box(modifier = Modifier.fillMaxWidth().background(Color.Red).padding(8.dp), contentAlignment = Alignment.Center) {
                Text("OFFLINE MODE - AI Paused", color = Color.White)
            }
        }

        Column(modifier = Modifier.padding(16.dp)) {
            Text("Ambient Audio Engine", style = MaterialTheme.typography.headlineSmall)
            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = {
                    val intent = android.content.Intent(context, com.mine.app.service.AmbientListeningService::class.java)
                    if (isAmbientRunning) {
                        intent.action = "STOP"
                        context.startService(intent)
                        viewModel.setAmbientRunning(false)
                    } else {
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                            context.startForegroundService(intent)
                            viewModel.setAmbientRunning(true)
                        } else {
                            permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS))
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().height(60.dp),
                colors = ButtonDefaults.buttonColors(containerColor = if (isAmbientRunning) Color(0xFF4CAF50) else Color.DarkGray)
            ) {
                Text(if (isAmbientRunning) "🟢 Ambient Listening ON" else "⚪ Turn On Ambient Listening")
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        viewModel.toggleRecording()
                    } else {
                        permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS))
                    }
                },
                modifier = Modifier.fillMaxWidth().height(60.dp),
                colors = ButtonDefaults.buttonColors(containerColor = if (isRecording) Color.Red else Color(0xFF2196F3))
            ) {
                Text(if (isRecording) "🛑 Stop & Transcribe" else "🎙️ Manual Record Test")
            }

            Spacer(modifier = Modifier.height(16.dp))
            TerminalView(logs = logs)
        }
    }
}

// The Bulletproof Auto-Scrolling Terminal
@Composable
fun ColumnScope.TerminalView(logs: List<String>) {
    val listState = rememberLazyListState()
    
    // Auto-scroll to the bottom whenever the log list grows
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            listState.animateScrollToItem(logs.size - 1)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f)
            .background(Color.Black)
            .padding(8.dp)
    ) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            items(logs) { log ->
                Text(
                    text = log,
                    color = Color.Green,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }
        }
    }
}
