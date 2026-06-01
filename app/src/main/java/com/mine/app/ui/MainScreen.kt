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
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import com.mine.app.util.PreferencesManager
import com.mine.app.util.PersonaType
import androidx.hilt.navigation.compose.hiltViewModel
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
    val tabs = listOf("Database", "Chat", "Ambient", "Settings")
    val icons = listOf(Icons.Default.Storage, Icons.Default.Chat, Icons.Default.Mic, Icons.Default.Settings)

    val showReflectionDialog by debugViewModel.showReflectionDialog.collectAsState()
    if (showReflectionDialog) {
        CallReflectionDialog(debugViewModel)
    }

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
                3 -> SettingsTab(debugViewModel.prefs)
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
    var showVault by remember { mutableStateOf(false) }

    if (showVault) {
        TranscriptVaultScreen(viewModel, onBack = { showVault = false })
    } else {
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
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED &&
                                ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
                                context.startForegroundService(intent)
                                viewModel.setAmbientRunning(true)
                            } else {
                                permissionLauncher.launch(arrayOf(
                                    Manifest.permission.RECORD_AUDIO,
                                    Manifest.permission.POST_NOTIFICATIONS,
                                    Manifest.permission.READ_PHONE_STATE
                                ))
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(60.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = if (isAmbientRunning) Color(0xFF4CAF50) else Color.DarkGray)
                ) {
                    Text(if (isAmbientRunning) "🟢 Ambient Listening ON" else "⚪ Turn On Ambient Listening")
                }

                Spacer(modifier = Modifier.height(8.dp))

                Button(
                    onClick = {
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                            viewModel.toggleRecording()
                        } else {
                            permissionLauncher.launch(arrayOf(
                                Manifest.permission.RECORD_AUDIO,
                                Manifest.permission.POST_NOTIFICATIONS,
                                Manifest.permission.READ_PHONE_STATE
                            ))
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(60.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = if (isRecording) Color.Red else Color(0xFF2196F3))
                ) {
                    Text(if (isRecording) "🛑 Stop & Transcribe" else "🎙️ Manual Record Test")
                }

                Spacer(modifier = Modifier.height(16.dp))

                // --- NEW: VAULT BUTTON ---
                Button(
                    onClick = {
                        viewModel.loadTranscripts()
                        showVault = true
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF9C27B0)) // Purple
                ) {
                    Text("📂 View Transcript Vault")
                }

                Spacer(modifier = Modifier.height(16.dp))
                TerminalView(logs = logs)
            }
        }
    }
}

@Composable
fun SettingsTab(prefs: com.mine.app.util.PreferencesManager = hiltViewModel<DebugViewModel>().prefs) {
    var isLocationEnabled by remember { mutableStateOf(prefs.isLocationContextEnabled) }
    var selectedPersona by remember { mutableStateOf(prefs.currentPersona) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(24.dp))

        // Location Toggle
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Contextual Location", style = MaterialTheme.typography.titleMedium)
                Text("Allows AI to know if you are on Wi-Fi or Cellular.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            }
            Switch(checked = isLocationEnabled, onCheckedChange = { isLocationEnabled = it; prefs.isLocationContextEnabled = it })
        }

        Spacer(modifier = Modifier.height(24.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(24.dp))

        // Persona Selector
        Text("Co-founder Persona", style = MaterialTheme.typography.titleMedium)
        Text("Choose how MineAI speaks to you.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        Spacer(modifier = Modifier.height(8.dp))

        PersonaType.entries.forEach { persona ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(
                    selected = selectedPersona == persona.name,
                    onClick = {
                        selectedPersona = persona.name
                        prefs.currentPersona = persona.name
                    }
                )
                Text(persona.name)
            }
        }
    }
}

@Composable
fun TranscriptVaultScreen(viewModel: DebugViewModel, onBack: () -> Unit) {
    val transcripts by viewModel.transcripts.collectAsState()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onBack) { Text("← Back") }
            Spacer(modifier = Modifier.width(16.dp))
            Text("Transcript Vault", style = MaterialTheme.typography.headlineSmall)
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (transcripts.isEmpty()) {
            Text("No transcripts found. Turn on Ambient Listening!", color = Color.Gray)
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(transcripts) { t ->
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("[${t.speaker}]", color = Color.Gray, fontSize = 12.sp)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(t.text, color = Color.White)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CallReflectionDialog(viewModel: DebugViewModel) {
    val isRecording by viewModel.isRecording.collectAsState()

    AlertDialog(
        onDismissRequest = { viewModel.setShowReflectionDialog(false) },
        title = { Text("Call Reflection") },
        text = { Text("What were the key takeaways or commitments from that call?") },
        confirmButton = {
            Button(
                onClick = {
                    if (isRecording) {
                        viewModel.processCallReflection()
                    } else {
                        viewModel.toggleRecording() // Starts recording
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = if (isRecording) Color.Red else Color(0xFF2196F3))
            ) {
                Text(if (isRecording) "🛑 Stop & Save" else "🎙️ Start Recording")
            }
        },
        dismissButton = {
            TextButton(onClick = { viewModel.setShowReflectionDialog(false) }) {
                Text("Cancel", color = Color.Gray)
            }
        }
    )
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
