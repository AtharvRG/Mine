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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.first
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.anchor.core.models.AlarmTier
import com.anchor.core.ui.UiAction
import com.anchor.core.ui.UiState

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        // H-11 (R-15): the user just answered the prompt; the banner must reflect the answer.
        viewModel.refreshNotificationsBlocked()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        // SEC 9: Removed ACTION_REQUEST_SCHEDULE_EXACT_ALARM prompt entirely. setAlarmClock doesn't need it.

        setContent {
            com.anchor.mine.ui.theme.MineTheme {
                MineAppUI(viewModel)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // H-11 (R-15): the user may have toggled notifications in system settings while we were
        // stopped; nothing else observes that change.
        viewModel.refreshNotificationsBlocked()
    }
}

@Composable
fun MineAppUI(viewModel: MainViewModel) {
    val state by viewModel.state.collectAsState()
    MineAppUI(state, viewModel::onAction)
}

/** Stateless body of [MineAppUI], so the shell (tabs, banners) can be tested without a ViewModel. */
@Composable
fun MineAppUI(state: UiState, onAction: (UiAction) -> Unit) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Chat", "Reminders", "Provider", "Backup", "Debug")

    // B5: the export backend is built asynchronously in MineApp.onCreate. Until it completes the
    // Backup tab says so rather than offering buttons that would do nothing.
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as? MineApp
    var exportManager by remember { mutableStateOf<com.anchor.core.export.ExportManager?>(null) }
    LaunchedEffect(app) {
        exportManager = app?.let { runCatching { it.sharedExportManager.await() }.getOrNull() }
    }

    if (state.globalError != null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
            Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                Text(if (state.globalError == "KEYSTORE_LOST") "Keystore Key Lost" else "Fatal Error", color = MaterialTheme.colorScheme.error)
                Spacer(modifier = Modifier.height(16.dp))
                if (state.globalError == "KEYSTORE_LOST") {
                    Text("The database cannot be decrypted. You must reset the app to continue.", modifier = Modifier.padding(16.dp))
                    if (!state.showResetConfirm) {
                        Button(onClick = { onAction(UiAction.PromptResetDatabase) }) { Text("Reset Database") }
                    } else {
                        Text("Are you sure? This deletes everything.", color = MaterialTheme.colorScheme.error)
                        Row {
                            Button(onClick = { onAction(UiAction.ConfirmResetDatabase(true)) }) { Text("Yes, Delete") }
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(onClick = { onAction(UiAction.ConfirmResetDatabase(false)) }) { Text("Cancel") }
                        }
                    }
                } else {
                    Text(state.globalError!!)
                }
            }
        }
        return
    }

    Column(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
        // H-11 (R-15): shown on every tab, because a reminder that cannot notify is a lost
        // reminder no matter which screen the user is on.
        if (state.notificationsBlocked) {
            Text(
                text = "Notifications are off for Mine. Reminders will be scheduled but cannot alert you until you allow notifications in system settings.",
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer).padding(8.dp).testTag("notificationsBlockedBanner")
            )
        }
        PrimaryTabRow(selectedTabIndex = selectedTab) {
            tabs.forEachIndexed { index, title ->
                Tab(selected = selectedTab == index, onClick = { selectedTab = index }, text = { Text(title) })
            }
        }

        Box(modifier = Modifier.weight(1f).padding(16.dp)) {
            when (selectedTab) {
                0 -> ChatScreen(state, onAction)
                1 -> RemindersScreen(state, onAction)
                        // O-2: no key() here. The key remounted ProviderScreen on every config change,
                // and a remount mid-edit re-seeded the fields from the old state, which is how a
                // save produced the old value concatenated with the new one. ProviderScreen
                // already follows `state` through a LaunchedEffect.
                2 -> ProviderScreen(state) { ep, k, m -> onAction(UiAction.SaveProvider(ep, k, m)) }
                3 -> BackupScreen(exportManager)
                4 -> DebugScreen(state, onAction)
            }
        }
    }
}

@Composable
fun ChatScreen(
    state: UiState,
    onAction: (UiAction) -> Unit,
    listState: LazyListState = rememberLazyListState()
) {
    val totalItems = state.chatMessages.size + state.receipts.size + state.openProposals.size + state.proposedSoulFacts.size + state.staleItems.size + (if (state.isChatLoading) 1 else 0)
    LaunchedEffect(totalItems) {
        // H-09 (R-13): the sum above assumes every counted field is rendered as exactly one
        // LazyColumn item. Scroll to what the list actually laid out, clamped, so a mismatch
        // cannot throw an out-of-bounds index into the coroutine.
        //
        // H-09b: a LaunchedEffect starts after the first composition commits but BEFORE the
        // LazyColumn's first measure, so `totalItemsCount` is 0 on entry and the old `>= 0`
        // guard silently skipped the scroll on the very first load — the list stayed at the
        // top. Wait for the first non-empty layout before computing the target.
        val lastIndex = snapshotFlow { listState.layoutInfo.totalItemsCount }
            .first { it > 0 } - 1
        if (lastIndex >= 0) {
            listState.animateScrollToItem(lastIndex)
        }
    }

    // SEC 4: Added imePadding to prevent keyboard overlap
    Column(modifier = Modifier.fillMaxSize().imePadding()) {
        val quote = state.homeQuote
        if (quote != null) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "\"${quote.text}\"",
                        style = MaterialTheme.typography.bodyMedium,
                        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "${quote.slot.name.lowercase().replaceFirstChar { it.uppercase() }} reflection",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }
        }

        val connErr = state.connectionError
        if (connErr != null) {
            Text(connErr, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(8.dp))
        }
        LazyColumn(modifier = Modifier.weight(1f), state = listState) {
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
            
            // RENDER RECEIPTS (Tool Results & Undo)
            items(state.receipts) { receipt ->
                Row(modifier = Modifier.fillMaxWidth().padding(start = 32.dp, top = 4.dp, bottom = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    val prefix = if (receipt.isError) "✗ " else "✓ "
                    val color = if (receipt.isError) Color.Red else Color.Gray
                    Text("$prefix${receipt.message}", color = color, modifier = Modifier.weight(1f))
                    val undoToken = receipt.undoToken
                    if (undoToken != null) {
                        Button(onClick = { onAction(UiAction.Undo(undoToken)) }) { Text("Undo") }
                    }
                }
            }
            
            // RENDER PROPOSALS (Ambiguity Chips)
            items(state.openProposals) { prop ->
                Column(modifier = Modifier.fillMaxWidth().padding(8.dp).background(Color.Yellow.copy(alpha=0.2f)).padding(8.dp)) {
                    Text(if (prop.kind == "time") "Clarify: Which time?" else "Clarify: Which one?")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        // Use the new structured options to display the real title on the button!
                        prop.options.forEach { opt ->
                            Button(onClick = { onAction(UiAction.ConfirmProposal(prop.id, opt.id)) }) {
                                Text(opt.title)
                            }
                        }
                    }
                }
            }

            // RENDER STALE ITEMS (Keep / Cancel)
            items(state.staleItems) { staleNode ->
                Column(modifier = Modifier.fillMaxWidth().padding(8.dp).background(Color.Magenta.copy(alpha=0.1f)).padding(8.dp)) {
                    Text("Stale (30+ days idle): ${staleNode.title}")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onAction(UiAction.KeepStale(staleNode.id)) }) { Text("Keep") }
                        Button(onClick = { onAction(UiAction.CancelStale(staleNode.id)) }) { Text("Cancel") }
                    }
                }
            }
            
            // RENDER SOUL PROPOSALS (Accept/Reject)
            items(state.proposedSoulFacts) { fact ->
                Column(modifier = Modifier.fillMaxWidth().padding(8.dp).background(Color.Cyan.copy(alpha=0.2f)).padding(8.dp)) {
                    Text("Learn: ${fact.statement}?")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onAction(UiAction.AcceptSoulFact(fact.id)) }) { Text("Yes") }
                        Button(onClick = { onAction(UiAction.RejectSoulFact(fact.id)) }) { Text("No") }
                    }
                }
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
            if (state.isChatLoading) {
                Button(onClick = { onAction(UiAction.Stop) }) { Text("Stop") }
            } else {
                Button(onClick = { if (state.composerDraft.isNotBlank()) onAction(UiAction.SendMessage) }) { Text("Send") }
            }
        }
    }
}

@Composable
fun RemindersScreen(state: UiState, onAction: (UiAction) -> Unit) {
    Column {
        Button(onClick = { onAction(UiAction.RunSelfTest) }) {
            Text("Run 5-sec Self Test")
        }
        Text("Test Status: ${state.selfTestStatus}")
        // A reminder that cannot notify is a lost reminder. reconcileAlarms() records this on
        // every refresh, so it must be visible without the user running the self-test.
        state.schedulingError?.let {
            Text("Reminders may not notify: $it", color = MaterialTheme.colorScheme.error)
        }
        // H-01 (R-01): reminders that came due while the device was off are reported, not dropped.
        state.missedReminders.forEach { missed ->
            val minutes = missed.overdueByMs / 60_000L
            val ago = if (minutes >= 1) "$minutes min ago" else "${missed.overdueByMs / 1000L} sec ago"
            Text("Missed: ${missed.text} (due $ago)", color = MaterialTheme.colorScheme.error)
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = state.alarmDisclosureText,
            style = MaterialTheme.typography.bodySmall,
            color = Color.Gray
        )

        Spacer(modifier = Modifier.height(16.dp))
        Text("Active Reminders:")
        LazyColumn {
            items(state.activeReminders) { r ->
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    val badge = if (r.tier == AlarmTier.GENTLE) "[GENTLE]" else "[ALARM]"
                    Text("$badge ID: ${r.id.take(8)} | ${r.text}")
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

    // The controller loads settings asynchronously, so the first frame is empty. Re-seed the
    // fields whenever the persisted config changes: without this the screen keeps showing the
    // empty first frame forever, and a save writes blanks over a valid config.
    LaunchedEffect(state.providerEndpoint, state.providerKey, state.providerModel) {
        ep = state.providerEndpoint
        key = state.providerKey
        mod = state.providerModel
    }

    Column {
        OutlinedTextField(value = ep, onValueChange = { ep = it }, label = { Text("Endpoint") }, modifier = Modifier.fillMaxWidth().testTag("providerEndpoint"))
        OutlinedTextField(
            value = key,
            onValueChange = { key = it },
            label = { Text("API Key") },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth().testTag("providerKey")
        )
        OutlinedTextField(value = mod, onValueChange = { mod = it }, label = { Text("Model") }, modifier = Modifier.fillMaxWidth().testTag("providerModel"))
        Button(onClick = { onSave(ep, key, mod) }) {
            Text("Save")
        }
    }
}

@Composable
fun DebugScreen(state: UiState, onAction: (UiAction) -> Unit) {
    Column(modifier = Modifier.padding(8.dp)) {
        Text("=== Storage Stats ===", style = MaterialTheme.typography.titleMedium)
        Text(state.debugStats)
        Spacer(modifier = Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onAction(UiAction.RefreshStats) }) { Text("Refresh Stats") }
            // Export/Import live on their own tab (BackupScreen, B5).
        }
    }
}
