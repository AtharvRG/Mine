// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.mine

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.anchor.core.ui.UiAction
import com.anchor.core.ui.UiState

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        setContent {
            MineAppUI(viewModel)
        }
    }
}

@Composable
fun MineAppUI(viewModel: MainViewModel) {
    val state by viewModel.state.collectAsState()
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Chat", "Reminders", "Provider", "Debug")

    if (state.globalError != null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
            Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                Text("Fatal Error\n\n${state.globalError}", color = MaterialTheme.colorScheme.error)
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = { viewModel.onAction(UiAction.ResetDatabase) }) {
                    Text("Reset Database")
                }
            }
        }
        return
    }

    Column(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
        TabRow(selectedTabIndex = selectedTab) {
            tabs.forEachIndexed { index, title ->
                Tab(selected = selectedTab == index, onClick = { selectedTab = index }, text = { Text(title) })
            }
        }

        Box(modifier = Modifier.weight(1f).padding(16.dp)) {
            when (selectedTab) {
                0 -> ChatScreen(state, viewModel::onAction)
                1 -> RemindersScreen(state, viewModel::onAction)
                // Item 7: Using 'key' forces recomposition when DB loads
                2 -> key(state.providerEndpoint) { ProviderScreen(state) { ep, k, m -> viewModel.onAction(UiAction.SaveProvider(ep, k, m)) } }
                3 -> DebugScreen(state, viewModel::onAction)
            }
        }
    }
}

@Composable
fun ChatScreen(state: UiState, onAction: (UiAction) -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        val connErr = state.connectionError
        if (connErr != null) {
            Text(connErr, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(8.dp))
        }
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(state.chatMessages) { msg ->
                val isUser = msg.role == "user"
                val align = if (isUser) Modifier.padding(start = 32.dp) else Modifier.padding(end = 32.dp)
                val color = if (isUser) Color.Blue.copy(alpha = 0.1f) else Color.Gray.copy(alpha = 0.1f)

                val textContent = msg.content ?: "[redacted]"

                Text(
                    text = "${msg.role.uppercase()}: $textContent",
                    modifier = Modifier.fillMaxWidth().then(align).padding(4.dp).background(color).padding(8.dp)
                )
            }
            if (state.isChatLoading) {
                item { Text("Typing...", modifier = Modifier.padding(8.dp)) }
            }
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = state.composerDraft,
                onValueChange = { onAction(UiAction.UpdateDraft(it)) },
                modifier = Modifier.weight(1f)
            )
            Button(onClick = { if (state.composerDraft.isNotBlank()) onAction(UiAction.SendMessage) }) {
                Text("Send")
            }
        }
    }
}

@Composable
fun RemindersScreen(state: UiState, onAction: (UiAction) -> Unit) {
    Column {
        Button(onClick = { onAction(UiAction.ScheduleIn(1, "Test reminder 1 min")) }) {
            Text("Set Reminder in 1 min")
        }
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = { onAction(UiAction.RunSelfTest) }) {
            Text("Run 5-sec Self Test")
        }
        Text("Test Status: ${state.selfTestStatus}")
        
        Spacer(modifier = Modifier.height(16.dp))
        Text("Active Reminders:")
        LazyColumn {
            items(state.activeReminders) { r ->
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("ID: ${r.id.take(8)} | ${r.text}")
                    Button(onClick = { onAction(UiAction.CancelReminder(r.id)) }) { Text("X") }
                }
            }
        }
    }
}

@Composable
fun ProviderScreen(state: UiState, onSave: (String, String, String) -> Unit) {
    var ep by remember { mutableStateOf(state.providerEndpoint) }
    var key by remember { mutableStateOf(state.providerKey) }
    var mod by remember { mutableStateOf(state.providerModel) }

    Column {
        OutlinedTextField(value = ep, onValueChange = { ep = it }, label = { Text("Endpoint") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            value = key,
            onValueChange = { key = it },
            label = { Text("API Key") },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(value = mod, onValueChange = { mod = it }, label = { Text("Model") }, modifier = Modifier.fillMaxWidth())
        Button(onClick = { onSave(ep, key, mod) }) {
            Text("Save")
        }
    }
}

@Composable
fun DebugScreen(state: UiState, onAction: (UiAction) -> Unit) {
    Column {
        Text("=== Storage Stats ===", style = MaterialTheme.typography.titleMedium)
        Text(state.debugStats)
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = { onAction(UiAction.RefreshStats) }) {
            Text("Refresh Stats")
        }
    }
}
