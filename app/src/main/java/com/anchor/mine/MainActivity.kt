// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.mine

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.anchor.core.models.AlarmTier
import com.anchor.core.ui.ChatController
import com.anchor.core.ui.UiAction
import com.anchor.core.ui.UiState
import com.anchor.core.voice.VoiceSpeechSanitizer
import com.anchor.mine.ui.AmbientControlCard
import com.anchor.mine.ui.AmbientReportCard
import com.anchor.mine.voice.AudioRecorderHelper
import com.anchor.mine.voice.SherpaVoiceEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    /**
     * Blueprint 50 §3.14 / 52 §12: incremented by the launcher shortcut so the already-composed
     * chat screen focuses its composer. A counter, not a boolean: two shortcut taps in a row must
     * both focus, and a boolean would only fire on the first one.
     */
    private val focusComposerRequests = androidx.compose.runtime.mutableIntStateOf(0)

    /**
     * The shortcut opens MainActivity with `ACTION_VIEW` and no data. The activity is
     * `singleTop`, so an already-running chat receives it here instead of being recreated.
     */
    public override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShortcutIntent(intent)
        handleProviderImportIntent(intent)
        handleShareIntent(intent)
    }

    private fun handleShortcutIntent(intent: android.content.Intent?) {
        if (intent?.action != android.content.Intent.ACTION_VIEW) return
        if (intent.data?.scheme == "mine") return
        // Blueprint 52 §12: the shortcut can be turned off. The manifest shortcut cannot be
        // removed at runtime, so the switch is honoured here: the app still opens (the user
        // tapped it), it just does not grab the composer.
        val storage = (application as? MineApp)?.sharedStorage
        if (storage == null) {
            focusComposerRequests.intValue++
            return
        }
        lifecycleScope.launch {
            val enabled = runCatching {
                com.anchor.core.capture.QuickCapture.isShortcutEnabled(storage.await())
            }.getOrDefault(true)
            if (enabled) focusComposerRequests.intValue++
        }
    }

    private fun handleProviderImportIntent(intent: android.content.Intent?) {
        val data = intent?.data ?: return
        val schemeMatch = data.scheme?.equals("mine", ignoreCase = true) == true
        val hostMatch = data.host?.equals("provider", ignoreCase = true) == true || data.authority?.equals("provider", ignoreCase = true) == true
        if (schemeMatch && hostMatch) {
            viewModel.onAction(UiAction.ImportProviderConfig(data.toString()))
        }
    }

    private fun handleShareIntent(intent: android.content.Intent?) {
        if (intent?.action != android.content.Intent.ACTION_SEND) return
        if (intent.type != "text/plain") return
        val text = intent.getStringExtra(android.content.Intent.EXTRA_TEXT)
            ?: intent.getCharSequenceExtra(android.content.Intent.EXTRA_PROCESS_TEXT)?.toString()
        if (!text.isNullOrBlank()) {
            viewModel.onAction(UiAction.UpdateDraft(text))
            focusComposerRequests.intValue++
        }
    }

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

        // A cold start from the shortcut arrives here, not in onNewIntent.
        handleShortcutIntent(intent)
        handleProviderImportIntent(intent)
        handleShareIntent(intent)

        setContent {
            com.anchor.mine.ui.theme.MineTheme {
                val state by viewModel.state.collectAsState()
                LaunchedEffect(state.blockScreenshots) {
                    if (state.blockScreenshots) {
                        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
                    } else {
                        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
                    }
                }
                MineAppUI(viewModel, focusComposerRequests, onUnlockRequested = { showBiometricPrompt() })
            }
        }
    }

    private var lastPauseTimestamp: Long = 0L

    override fun onPause() {
        super.onPause()
        lastPauseTimestamp = System.currentTimeMillis()
    }

    override fun onResume() {
        super.onResume()
        // H-11 (R-15): the user may have toggled notifications in system settings while we were
        // stopped; nothing else observes that change.
        viewModel.refreshNotificationsBlocked()
        // Blueprint 41 §3.4: "an immediate attempt … whenever the user opens the app". A queue
        // left by an offline session is answered as soon as the user comes back to it.
        viewModel.onAction(UiAction.DrainQueuedTurns)

        if (lastPauseTimestamp > 0L) {
            val elapsedSec = (System.currentTimeMillis() - lastPauseTimestamp) / 1000L
            lastPauseTimestamp = 0L
            val state = viewModel.state.value
            if (state.appLockEnabled && !state.isAppLocked && elapsedSec >= state.relockTimeoutSec) {
                viewModel.onAction(UiAction.LockApp)
            }
        }
    }

    private fun showBiometricPrompt() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                val prompt = android.hardware.biometrics.BiometricPrompt.Builder(this)
                    .setTitle("Unlock Mine")
                    .setDescription("Authenticate to access your chats and memories")
                    .setAllowedAuthenticators(
                        android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG or
                        android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL
                    )
                    .build()
                val cancellationSignal = android.os.CancellationSignal()
                prompt.authenticate(
                    cancellationSignal,
                    mainExecutor,
                    object : android.hardware.biometrics.BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: android.hardware.biometrics.BiometricPrompt.AuthenticationResult?) {
                            super.onAuthenticationSucceeded(result)
                            viewModel.onAction(UiAction.UnlockApp)
                        }
                        override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                            super.onAuthenticationError(errorCode, errString)
                        }
                    }
                )
            } catch (e: Exception) {
                viewModel.onAction(UiAction.UnlockApp)
            }
        } else {
            viewModel.onAction(UiAction.UnlockApp)
        }
    }
}

@Composable
fun MineAppUI(
    viewModel: MainViewModel,
    focusComposerRequests: androidx.compose.runtime.State<Int> = remember { mutableIntStateOf(0) },
    onUnlockRequested: () -> Unit = {}
) {
    val state by viewModel.state.collectAsState()
    MineAppUI(state, viewModel::onAction, focusComposerRequests, onUnlockRequested)
}

/** Stateless body of [MineAppUI], so the shell (tabs, banners) can be tested without a ViewModel. */
@Composable
fun MineAppUI(
    state: UiState,
    onAction: (UiAction) -> Unit,
    focusComposerRequests: androidx.compose.runtime.State<Int> = remember { mutableIntStateOf(0) },
    onUnlockRequested: () -> Unit = { onAction(UiAction.UnlockApp) }
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    // "Memory" is appended, not inserted: the `when (selectedTab)` below and every existing test
    // that clicks a tab by title depend on 0..4 staying put.
    val tabs = listOf("Chat", "Reminders", "Provider", "Backup", "Debug", "Memory", "Board", "Calendar")

    // Row 20: the viewer reads storage only when it is first opened. `memoryLoaded` is the guard —
    // UiState is a new object on every update, so keying on the tab alone would re-read on each one.
    LaunchedEffect(selectedTab, state.memoryLoaded) {
        if (selectedTab == 5 && !state.memoryLoaded) onAction(UiAction.LoadMemory)
    }

    // Blueprint 31: one proactive pass per foreground session. Keyed on Unit, not on state — a
    // state-keyed effect would re-tick on every update and the engine would be asked to nudge
    // continuously. The engine's own gates (min gap, open thread, daily fuse) are what limit how
    // often a nudge actually goes out, not how often the tick runs.
    LaunchedEffect(Unit) {
        onAction(UiAction.TickProactive)
    }

    // B5: the export backend is built asynchronously in MineApp.onCreate. Until it completes the
    // Backup tab says so rather than offering buttons that would do nothing.
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as? MineApp
    var exportManager by remember { mutableStateOf<com.anchor.core.export.ExportManager?>(null) }
    var storage by remember { mutableStateOf<com.anchor.core.storage.Storage?>(null) }
    LaunchedEffect(app) {
        exportManager = app?.let { runCatching { it.sharedExportManager.await() }.getOrNull() }
        storage = app?.let { runCatching { it.sharedStorage.await() }.getOrNull() }
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

    // Blueprint 52 §3.1: the first-run flow owns the screen until it is finished. It sits after
    // the global-error branch (a keystore failure must still be resettable) and before the tabs.
    // `onboardingDone` is read from storage at controller start, so this is a real gate, not a
    // first-composition guess — a finished install never renders the flow, not even for a frame.
    if (!state.onboardingDone) {
        OnboardingScreen(state, onAction)
        return
    }

    if (state.appLockEnabled && state.isAppLocked) {
        LockScreen(onUnlock = onUnlockRequested)
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
        PrimaryScrollableTabRow(selectedTabIndex = selectedTab, edgePadding = 16.dp) {
            tabs.forEachIndexed { index, title ->
                Tab(selected = selectedTab == index, onClick = { selectedTab = index }, text = { Text(title) })
            }
        }

        Box(modifier = Modifier.weight(1f).padding(16.dp)) {
            when (selectedTab) {
                0 -> ChatScreen(state, onAction, focusComposerRequests = focusComposerRequests)
                1 -> RemindersScreen(state, onAction)
                        // O-2: no key() here. The key remounted ProviderScreen on every config change,
                // and a remount mid-edit re-seeded the fields from the old state, which is how a
                // save produced the old value concatenated with the new one. ProviderScreen
                // already follows `state` through a LaunchedEffect.
                2 -> ProviderScreen(state, onImport = { payload -> onAction(UiAction.ImportProviderConfig(payload)) }) { ep, k, m -> onAction(UiAction.SaveProvider(ep, k, m)) }
                3 -> BackupScreen(exportManager, storage, onAction = onAction)
                4 -> DebugScreen(state, onAction)
                5 -> MemoryScreen(state, onAction, storage = storage)
                6 -> com.anchor.mine.ui.BoardViewScreen(storage = storage, onActionClick = { id, status -> onAction(UiAction.UpdateNodeStatus(id, status)) })
                7 -> com.anchor.mine.ui.CalendarViewScreen(storage = storage)
            }
        }
    }
}

@Composable
fun ChatScreen(
    state: UiState,
    onAction: (UiAction) -> Unit,
    listState: LazyListState = rememberLazyListState(),
    focusComposerRequests: androidx.compose.runtime.State<Int> = remember { mutableIntStateOf(0) }
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
        val scope = rememberCoroutineScope()
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
        // Blueprint 41 §2: the banner that tells the user their words are kept. Derived from the
        // outbox rows, never stored on the message (blueprint 11:83).
        val queuedText = state.offlineQueuedText
        if (queuedText != null) {
            Text(queuedText, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp))
        }
        LazyColumn(modifier = Modifier.weight(1f), state = listState) {
            items(state.chatMessages) { msg ->
                val isUser = msg.role == "user"
                val align = if (isUser) Modifier.padding(start = 32.dp) else Modifier.padding(end = 32.dp)
                val color = if (isUser) Color.Blue.copy(alpha = 0.1f) else Color.Gray.copy(alpha = 0.1f)
                val textContent = msg.content ?: "[redacted]"
                val queued = msg.id in state.queuedChatIds

                Text(
                    text = if (queued) "${msg.role.uppercase()}: $textContent (queued)" else "${msg.role.uppercase()}: $textContent",
                    modifier = Modifier.fillMaxWidth().then(align).padding(4.dp).background(color).padding(8.dp)
                )
                if (!isUser) {
                    val context = LocalContext.current
                    Row(
                        modifier = Modifier.fillMaxWidth().then(align).padding(horizontal = 4.dp),
                        horizontalArrangement = Arrangement.End
                    ) {
                        Button(
                            onClick = {
                                val text = msg.content ?: ""
                                scope.launch(Dispatchers.Default) {
                                    val engine = SherpaVoiceEngine.getInstance(context)
                                    if (engine != null && engine.isTtsAvailable) {
                                        val chunks = VoiceSpeechSanitizer.chunkSentences(text)
                                        for (chunk in chunks) {
                                            val samples = engine.synthesize(chunk)
                                            engine.playBlocking(samples)
                                        }
                                    } else {
                                        withContext(Dispatchers.Main) {
                                            com.anchor.mine.voice.NativeTtsHelper.speak(context, text)
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.padding(2.dp)
                        ) {
                            Text("🔊 Speak")
                        }
                    }
                }
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

            // RENDER AMBIENT DAILY REPORT
            val ambientReport = state.latestAmbientReport
            if (ambientReport != null && state.ambientReportItems.isNotEmpty()) {
                item {
                    AmbientReportCard(
                        report = ambientReport,
                        items = state.ambientReportItems,
                        onAction = onAction
                    )
                }
            }
            
            if (state.isChatLoading) {
                item { Text("Typing...", modifier = Modifier.padding(8.dp)) }
            }
        }
        // Blueprint 50 §3.14 / 52 §12: the launcher shortcut opens "the composer focused". The
        // request counter is read here so a tap while this screen is already composed still
        // focuses; a first-composition-only effect would ignore the second tap.
        val composerFocus = remember { androidx.compose.ui.focus.FocusRequester() }
        LaunchedEffect(focusComposerRequests.value) {
            if (focusComposerRequests.value > 0) composerFocus.requestFocus()
        }
        val context = LocalContext.current
        var isRecording by remember { mutableStateOf(false) }
        val recorder = remember { AudioRecorderHelper() }
        var recordingSeconds by remember { mutableStateOf(0) }

        var hasMicPermission by remember {
            mutableStateOf(
                ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            )
        }
        val micPermissionLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission()
        ) { granted ->
            hasMicPermission = granted
            if (granted) {
                val started = recorder.startRecording(scope)
                if (started) isRecording = true
            }
        }

        LaunchedEffect(isRecording) {
            if (isRecording) {
                recordingSeconds = 0
                while (isRecording) {
                    delay(1000L)
                    recordingSeconds++
                }
            }
        }

        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = state.composerDraft,
                onValueChange = { onAction(UiAction.UpdateDraft(it)) },
                modifier = Modifier.weight(1f).focusRequester(composerFocus).testTag("composer")
            )
            IconButton(
                onClick = {
                    if (!isRecording) {
                        if (hasMicPermission) {
                            val started = recorder.startRecording(scope)
                            if (started) isRecording = true
                        } else {
                            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    } else {
                        isRecording = false
                        val samples = recorder.stopRecording()
                        scope.launch(Dispatchers.Default) {
                            val engine = SherpaVoiceEngine.getInstance(context)
                            val transcript = engine?.transcribe(samples) ?: ""
                            if (transcript.isNotBlank()) {
                                onAction(UiAction.UpdateDraft(transcript))
                            }
                        }
                    }
                },
                modifier = Modifier.testTag("micButton")
            ) {
                if (isRecording) {
                    Text("⏹ ${recordingSeconds}s", color = Color.Red)
                } else {
                    Text("🎤")
                }
            }
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
fun ProviderScreen(
    state: UiState,
    onImport: (String) -> Unit = {},
    onSave: (String, String, String) -> Unit
) {
    var ep by remember { mutableStateOf(state.providerEndpoint) }
    var key by remember { mutableStateOf(state.providerKey) }
    var mod by remember { mutableStateOf(state.providerModel) }
    var showImportDialog by remember { mutableStateOf(false) }
    var importPayload by remember { mutableStateOf("") }

    // The controller loads settings asynchronously, so the first frame is empty. Re-seed the
    // fields whenever the persisted config changes: without this the screen keeps showing the
    // empty first frame forever, and a save writes blanks over a valid config.
    LaunchedEffect(state.providerEndpoint, state.providerKey, state.providerModel) {
        ep = state.providerEndpoint
        key = state.providerKey
        mod = state.providerModel
    }

    if (showImportDialog) {
        AlertDialog(
            onDismissRequest = { showImportDialog = false },
            title = { Text("Import Provider Setup") },
            text = {
                Column {
                    Text("Paste JSON setup or mine:// link from a friend or QR code:")
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = importPayload,
                        onValueChange = { importPayload = it },
                        placeholder = { Text("{\"endpoint\": \"...\", \"model\": \"...\"}") },
                        modifier = Modifier.fillMaxWidth().testTag("importPayloadInput")
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onImport(importPayload)
                        showImportDialog = false
                        importPayload = ""
                    },
                    modifier = Modifier.testTag("confirmImportBtn")
                ) {
                    Text("Import")
                }
            },
            dismissButton = {
                TextButton(onClick = { showImportDialog = false }) {
                    Text("Cancel")
                }
            }
        )
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
        Row(modifier = Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onSave(ep, key, mod) }) {
                Text("Save")
            }
            OutlinedButton(
                onClick = { showImportDialog = true },
                modifier = Modifier.testTag("importProviderBtn")
            ) {
                Text("Import Setup")
            }
        }
    }
}

/**
 * Row 20, read-only slice of blueprint 51: Items and Soul straight from the tables (rule 1 — no
 * model call), the Inbox count, and a lock on local-only rows (rule 6). Remove, Erase, Trash,
 * editing and the other five areas are later slices; nothing here writes.
 */
@Composable
fun MemoryScreen(
    state: UiState,
    onAction: (UiAction) -> Unit = {},
    storage: com.anchor.core.storage.Storage? = null
) {
    Column(modifier = Modifier.testTag("memoryScreen")) {
        Text("Memory", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))
        Text("Inbox: ${state.inboxCount} waiting", modifier = Modifier.testTag("memoryInboxCount"))

        if (!state.memoryLoaded) {
            Spacer(modifier = Modifier.height(16.dp))
            Text("Loading…")
            return@Column
        }

        LazyColumn {
            item {
                AmbientControlCard(
                    storage = storage,
                    onDeleteAllClick = { onAction(UiAction.DeleteAllAmbientData) }
                )
                Spacer(modifier = Modifier.height(16.dp))
            }
            item {
                Spacer(modifier = Modifier.height(16.dp))
                Text("Items (${state.memoryNodes.size})", style = MaterialTheme.typography.titleSmall)
            }
            if (state.memoryNodes.isEmpty()) {
                item { Text("Nothing here yet.", modifier = Modifier.testTag("memoryItemsEmpty")) }
            }
            items(state.memoryNodes) { node ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("memoryNode-${node.id}"),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = node.title + if (node.localOnly) "  🔒" else "",
                        modifier = Modifier.weight(1f)
                    )
                    Row {
                        TextButton(
                            onClick = { onAction(UiAction.ArchiveNode(node.id)) },
                            modifier = Modifier.testTag("archiveBtn-${node.id}")
                        ) {
                            Text("Archive")
                        }
                        TextButton(
                            onClick = { onAction(UiAction.DeleteNode(node.id)) },
                            modifier = Modifier.testTag("deleteBtn-${node.id}")
                        ) {
                            Text("Delete")
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(16.dp))
                Text("Soul (${state.memorySoulFacts.size})", style = MaterialTheme.typography.titleSmall)
            }
            if (state.memorySoulFacts.isEmpty()) {
                item { Text("Nothing here yet.", modifier = Modifier.testTag("memorySoulEmpty")) }
            }
            items(state.memorySoulFacts) { fact ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("memoryFact-${fact.id}"),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = fact.statement + if (fact.localOnly) "  🔒" else "",
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        onClick = { onAction(UiAction.EraseSoulFact(fact.id)) },
                        modifier = Modifier.testTag("eraseFactBtn-${fact.id}")
                    ) {
                        Text("Erase")
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(16.dp))
                Text("Trash (${state.trashNodes.size})", style = MaterialTheme.typography.titleSmall, modifier = Modifier.testTag("trashHeader"))
            }
            if (state.trashNodes.isEmpty()) {
                item { Text("Trash is empty.", modifier = Modifier.testTag("memoryTrashEmpty")) }
            }
            items(state.trashNodes) { node ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("trashNode-${node.id}"),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = node.title + if (node.localOnly) "  🔒" else "",
                        modifier = Modifier.weight(1f)
                    )
                    Row {
                        TextButton(
                            onClick = { onAction(UiAction.RestoreNode(node.id)) },
                            modifier = Modifier.testTag("restoreBtn-${node.id}")
                        ) {
                            Text("Restore")
                        }
                        TextButton(
                            onClick = { onAction(UiAction.EraseNode(node.id)) },
                            modifier = Modifier.testTag("eraseBtn-${node.id}"),
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                        ) {
                            Text("Erase")
                        }
                    }
                }
            }
        }
    }
}

/**
 * Blueprint 52 §3.1, the v1 slice: four steps — Welcome, Privacy consent, Your day, Soul seed.
 * The two mandatory ones are the consent and the day window; the Soul questions are all
 * skippable and a skipped answer simply writes nothing (blueprint 52 §3.2).
 *
 * The consent text is blueprint 16 §3.3 verbatim, and it cannot be continued past without
 * accepting — that is the one hard gate in the flow.
 */
@Composable
fun OnboardingScreen(state: UiState, onAction: (UiAction) -> Unit) {
    var consentAccepted by remember { mutableStateOf(false) }
    var startHour by remember { mutableIntStateOf(4) }
    var endHour by remember { mutableIntStateOf(22) }

    Column(
        modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars).padding(16.dp).testTag("onboardingScreen")
    ) {
        Text("Step ${state.onboardingStep + 1} of ${ChatController.ONBOARDING_LAST_STEP + 1}", style = MaterialTheme.typography.labelMedium)
        Spacer(modifier = Modifier.height(12.dp))

        state.onboardingError?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("onboardingError"))
            Spacer(modifier = Modifier.height(8.dp))
        }

        Column(modifier = Modifier.weight(1f)) {
            when (state.onboardingStep) {
                0 -> {
                    Text("Welcome to Mine", style = MaterialTheme.typography.headlineSmall)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("You own your data. Your chats and memories stay on this phone, encrypted.")
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("The AI reads your words. When you chat, your message and the memories that matter go to the provider you choose.")
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Backup is yours. There is no Mine account and no Mine server.")
                }
                1 -> {
                    Text("Privacy", style = MaterialTheme.typography.headlineSmall)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("Your data stays on this phone.", style = MaterialTheme.typography.titleSmall)
                    Text("Mine saves your chats, memories, and settings only on your device, encrypted. There is no Mine account and no Mine server.")
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("But the AI has to read your words.", style = MaterialTheme.typography.titleSmall)
                    Text("When you chat, Mine sends the current message and the parts of your memory that matter right now to the AI provider you set up. That provider's own rules decide how they handle it. Choose a provider you trust.")
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("You are in control.", style = MaterialTheme.typography.titleSmall)
                    Text("You can mark any memory \"keep local only\", and it will never be sent. You can erase things any time.")
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Your backup is your job.", style = MaterialTheme.typography.titleSmall)
                    Text("If you lose your phone and your export file or its passphrase, your data is gone. Nobody can recover it for you.")
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Checkbox(
                            checked = consentAccepted,
                            onCheckedChange = { consentAccepted = it },
                            modifier = Modifier.testTag("consentCheckbox")
                        )
                        Text("I understand and accept")
                    }
                }
                2 -> {
                    Text("Your day", style = MaterialTheme.typography.headlineSmall)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("When does your day start and end? Mine keeps its check-ins and quiet time inside it. The window may cross midnight.")
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Day starts: %02d:00".format(startHour), modifier = Modifier.testTag("dayStartLabel"))
                    Slider(
                        value = startHour.toFloat(),
                        onValueChange = { startHour = it.toInt() },
                        valueRange = 0f..23f,
                        steps = 22,
                        modifier = Modifier.testTag("dayStartSlider")
                    )
                    Text("Day ends: %02d:00".format(endHour), modifier = Modifier.testTag("dayEndLabel"))
                    Slider(
                        value = endHour.toFloat(),
                        onValueChange = { endHour = it.toInt() },
                        valueRange = 0f..23f,
                        steps = 22,
                        modifier = Modifier.testTag("dayEndSlider")
                    )
                    val hours = ((endHour - startHour + 24) % 24)
                    Text(
                        when {
                            hours == 0 -> "That is a zero-hour day; pick a longer window."
                            hours < 10 -> "That is a short day. I will fit my check-ins in, but things may pile up. Your call."
                            hours > 20 -> "That leaves me very little quiet time. Fine, but please sleep sometime."
                            else -> "A $hours-hour day."
                        },
                        modifier = Modifier.testTag("dayLengthHint")
                    )
                }
                else -> {
                    Text("A little about you", style = MaterialTheme.typography.headlineSmall)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("These become things Mine remembers about you. Every one is optional.")
                    Spacer(modifier = Modifier.height(12.dp))
                    LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(SOUL_SEED_QUESTIONS) { q ->
                            Column {
                                Text(q.prompt, style = MaterialTheme.typography.titleSmall)
                                OutlinedTextField(
                                    value = state.onboardingAnswers[q.category] ?: "",
                                    onValueChange = { onAction(UiAction.OnboardingAnswer(q.category, it)) },
                                    modifier = Modifier.fillMaxWidth().testTag("soulAnswer-${q.category}"),
                                    singleLine = true
                                )
                                if (q.category == "health") {
                                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                        Checkbox(
                                            checked = state.onboardingHealthLocalOnly,
                                            onCheckedChange = { onAction(UiAction.OnboardingHealthLocalOnly(it)) },
                                            modifier = Modifier.testTag("healthLocalOnlySwitch")
                                        )
                                        Text("Keep this on this phone only")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.onboardingStep > 0) {
                OutlinedButton(onClick = { onAction(UiAction.OnboardingBack) }, modifier = Modifier.testTag("onboardingBack")) {
                    Text("Back")
                }
            }
            Spacer(modifier = Modifier.weight(1f))
            when (state.onboardingStep) {
                0 -> Button(onClick = { onAction(UiAction.OnboardingNext) }, modifier = Modifier.testTag("onboardingNext")) { Text("Get started") }
                1 -> Button(
                    onClick = { onAction(UiAction.OnboardingNext) },
                    enabled = consentAccepted,
                    modifier = Modifier.testTag("onboardingNext")
                ) { Text("Continue") }
                2 -> Button(
                    onClick = { onAction(UiAction.OnboardingConfirmDay(startHour * 60, endHour * 60)) },
                    modifier = Modifier.testTag("onboardingConfirmDay")
                ) { Text("Confirm") }
                else -> Button(onClick = { onAction(UiAction.OnboardingFinish) }, modifier = Modifier.testTag("onboardingFinish")) {
                    Text("Finish")
                }
            }
        }
    }
}

/** Blueprint 52 §3.2: the six light questions, in the order the blueprint asks them. */
data class SoulSeedQuestion(val category: String, val prompt: String)

val SOUL_SEED_QUESTIONS = listOf(
    SoulSeedQuestion("identity", "What do you do most days (study, work, both, something else)?"),
    SoulSeedQuestion("habits_routines", "When do you usually wake up and go to sleep?"),
    SoulSeedQuestion("health", "Anything about your health I should always keep in mind? (optional)"),
    SoulSeedQuestion("communication_style", "How do you like replies: short, balanced, or detailed?"),
    SoulSeedQuestion("dislikes", "Things you dislike or that annoy you?"),
    SoulSeedQuestion("goals_longterm", "What do you most want Mine to help with?")
)

@Composable
fun LockScreen(onUnlock: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .testTag("appLockScreen"),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(24.dp)
        ) {
            Text(
                text = "🔒",
                style = MaterialTheme.typography.displayMedium
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Mine is Locked",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Authenticate to access your chats and memories.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(
                onClick = onUnlock,
                modifier = Modifier.testTag("unlockAppButton")
            ) {
                Text("Unlock Mine")
            }
        }
    }
}

@Composable
fun DebugScreen(state: UiState, onAction: (UiAction) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(8.dp)
            .testTag("debugScreen"),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Section 9: Privacy and Security (Blueprint 16 §3.2 & Blueprint 52 §3.4)
        item {
            Text("=== Privacy & Security ===", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("App Lock", style = MaterialTheme.typography.titleSmall)
                    Text("Require biometric or screen lock authentication", style = MaterialTheme.typography.bodySmall)
                }
                Switch(
                    checked = state.appLockEnabled,
                    onCheckedChange = { onAction(UiAction.SetAppLock(it)) },
                    modifier = Modifier.testTag("appLockSwitch")
                )
            }
        }

        if (state.appLockEnabled) {
            item {
                Column {
                    Text("Relock Timeout", style = MaterialTheme.typography.titleSmall)
                    Text("Time in background before Mine relocks", style = MaterialTheme.typography.bodySmall)
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.testTag("relockTimeoutSelector")
                    ) {
                        val timeouts = listOf(
                            0 to "Immediately",
                            60 to "1 min",
                            300 to "5 min",
                            1800 to "30 min"
                        )
                        timeouts.forEach { (sec, label) ->
                            FilterChip(
                                selected = state.relockTimeoutSec == sec,
                                onClick = { onAction(UiAction.SetRelockTimeout(sec)) },
                                label = { Text(label) },
                                modifier = Modifier.testTag("relockTimeout-$sec")
                            )
                        }
                    }
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Block Screenshots & Recents", style = MaterialTheme.typography.titleSmall)
                    Text("Protect content from screenshots and task switcher preview", style = MaterialTheme.typography.bodySmall)
                }
                Switch(
                    checked = state.blockScreenshots,
                    onCheckedChange = { onAction(UiAction.SetBlockScreenshots(it)) },
                    modifier = Modifier.testTag("blockScreenshotsSwitch")
                )
            }
        }

        // Section 2: AI and Compact Mode (Blueprint 20 §3.4 & Blueprint 52 §3.4)
        item {
            Text("=== AI & Performance ===", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Compact Context Mode", style = MaterialTheme.typography.titleSmall)
                    Text("Reduces token budget for lower memory and smaller models", style = MaterialTheme.typography.bodySmall)
                }
                Switch(
                    checked = state.compactMode,
                    onCheckedChange = { onAction(UiAction.SetCompactMode(it)) },
                    modifier = Modifier.testTag("compactModeSwitch")
                )
            }
        }

        // Section 12: About Mine (Blueprint 92 §2 & Blueprint 94)
        item {
            Text("=== About Mine ===", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", modifier = Modifier.testTag("aboutVersion"))
                Text("Database Schema: ${com.anchor.mine.storage.MineDatabaseCallback.DATABASE_VERSION}", modifier = Modifier.testTag("aboutSchemaVersion"))
                Text("Export Format Version: 1", modifier = Modifier.testTag("aboutExportVersion"))
                Text("License: GPL-3.0-or-later", modifier = Modifier.testTag("aboutLicense"))
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = {
                        val intent = android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse("https://github.com/anchormind/mine/releases")
                        ).apply {
                            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(intent)
                    },
                    modifier = Modifier.testTag("checkForUpdatesButton")
                ) {
                    Text("Check for Updates")
                }
            }
        }

        // Storage Stats
        item {
            Text("=== Storage Stats ===", style = MaterialTheme.typography.titleMedium)
            Text(state.debugStats, modifier = Modifier.testTag("storageStatsText"))
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = { onAction(UiAction.RefreshStats) },
                modifier = Modifier.testTag("refreshStatsButton")
            ) {
                Text("Refresh Stats")
            }
        }
    }
}
