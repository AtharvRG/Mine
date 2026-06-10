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
    val graphNodes by viewModel.graphNodes.collectAsState()

    // Load nodes when the tab is opened
    LaunchedEffect(Unit) { viewModel.loadGraphNodes() }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Graph Editor", style = MaterialTheme.typography.headlineSmall)
        Spacer(modifier = Modifier.height(16.dp))

        // Manual Entry
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = manualTaskText,
                onValueChange = { manualTaskText = it },
                label = { Text("Add Manual Node") },
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = {
                    if (manualTaskText.isNotBlank()) {
                        viewModel.addManualTodo(manualTaskText)
                        manualTaskText = ""
                    }
                }
            ) { Text("Add") }
        }

        Spacer(modifier = Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(8.dp))

        // The Interactive Graph List
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(graphNodes) { node ->
                NodeEditorCard(node = node, viewModel = viewModel)
            }
        }
    }
}

@Composable
fun NodeEditorCard(node: com.mine.app.data.local.db.entity.NodeEntity, viewModel: DebugViewModel) {
    var isEditing by remember { mutableStateOf(false) }
    var editText by remember { mutableStateOf(node.content) }

    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        colors = CardDefaults.cardColors(
            containerColor = when (node.status) {
                "completed" -> Color(0xFF1B5E20) // Dark Green
                "trashed" -> Color(0xFFB71C1C) // Dark Red
                else -> Color(0xFF1E1E1E) // Default Dark Gray
            }
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text("ID: ${node.id} | Branch: ${node.branchId}", color = Color.Gray, fontSize = 12.sp)
                Text(node.status.uppercase(), color = Color.Gray, fontSize = 12.sp)
            }
            
            Spacer(modifier = Modifier.height(8.dp))

            if (isEditing) {
                OutlinedTextField(
                    value = editText,
                    onValueChange = { editText = it },
                    modifier = Modifier.fillMaxWidth()
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { isEditing = false }) { Text("Cancel", color = Color.Gray) }
                    TextButton(onClick = { 
                        viewModel.updateNodeText(node.id, editText)
                        isEditing = false 
                    }) { Text("Save", color = Color(0xFF2196F3)) }
                }
            } else {
                Text(node.content, color = Color.White)
                Spacer(modifier = Modifier.height(12.dp))
                
                // Action Buttons
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    if (node.status == "trashed") {
                        // If it's in the trash, offer Restore or Permanent Delete
                        TextButton(onClick = { viewModel.updateNodeStatus(node.id, "active") }) { 
                            Text("Restore", color = Color.White) 
                        }
                        TextButton(onClick = { viewModel.deleteNodePermanently(node.id) }) { 
                            Text("Delete Forever", color = Color.White) 
                        }
                    } else {
                        // Normal active/completed buttons
                        TextButton(onClick = { isEditing = true }) { Text("Edit", color = Color(0xFF2196F3)) }
                        
                        if (node.status != "completed") {
                            TextButton(onClick = { viewModel.updateNodeStatus(node.id, "completed") }) { 
                                Text("Complete", color = Color(0xFF4CAF50)) 
                            }
                        }

                        TextButton(onClick = { viewModel.updateNodeStatus(node.id, "trashed") }) { 
                            Text("Trash", color = Color(0xFFF44336)) 
                        }
                    }
                }
            }
        }
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

        val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            if (permissions[Manifest.permission.RECORD_AUDIO] == true) viewModel.toggleRecording()
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
                        } else {
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                context.startForegroundService(intent)
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

                Spacer(modifier = Modifier.height(8.dp))

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
                HorizontalDivider()
                Spacer(modifier = Modifier.height(8.dp))

                // --- RESTORED DEBUG CONTROLS ---
                Text("Debug Controls", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Button(
                        onClick = { viewModel.fetchAndDisplayDatabase() },
                        modifier = Modifier.weight(1f).padding(end = 4.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray)
                    ) {
                        Text("Print DB State")
                    }
                    Button(
                        onClick = { viewModel.forceChronosWakeup() },
                        modifier = Modifier.weight(1f).padding(start = 4.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9800))
                    ) {
                        Text("Force Chronos")
                    }
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
                            Text("[${t.speaker}] - ${java.util.Date(t.createdAt)}", color = Color.Gray, fontSize = 12.sp)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(t.text, color = Color.White)
                            
                            Spacer(modifier = Modifier.height(8.dp))
                            // --- NEW: EXTRACT BUTTON ---
                            OutlinedButton(
                                onClick = { viewModel.extractTranscriptToGraph(t) },
                                modifier = Modifier.align(Alignment.End)
                            ) {
                                Text("Extract to Graph", color = Color(0xFF9C27B0))
                            }
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
