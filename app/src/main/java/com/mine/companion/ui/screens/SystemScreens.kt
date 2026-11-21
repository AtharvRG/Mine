package com.mine.companion.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.anchor.core.notifications.AppScope
import com.anchor.core.notifications.ContentLevel
import com.anchor.core.notifications.SkipReason
import com.anchor.core.ui.UiAction
import com.mine.companion.store.AmbientSleepReason
import com.mine.companion.store.AmbientState
import com.mine.companion.store.ItemStatus
import com.mine.companion.store.MineAction
import com.mine.companion.store.ModalId
import com.mine.companion.store.ScreenId
import com.mine.companion.store.SettingKey
import com.mine.companion.store.mineDispatch
import com.mine.companion.store.mineSelect
import com.mine.companion.ui.icons.LIcon
import com.mine.companion.ui.icons.Lucide
import com.mine.companion.ui.modals.DateDialContent
import com.mine.companion.ui.modals.SideNavDrawerContent
import com.mine.companion.ui.modals.TogglePanelContent
import com.mine.companion.ui.primitives.GroupRow
import com.mine.companion.ui.primitives.GroupedSurface
import com.mine.companion.ui.primitives.MineField
import com.mine.companion.ui.primitives.Pressable
import com.mine.companion.ui.theme.Mine
import com.mine.companion.ui.theme.MineMotion
import com.mine.companion.ui.theme.MineType
import com.mine.companion.ui.theme.Shapes
import com.mine.companion.ui.theme.Txt
import com.mine.companion.ui.theme.num
import com.mine.companion.ui.theme.w
import java.text.DateFormat
import java.util.Date

/* ============================== S17 – S19 (standalone) ============================== */

@Composable
fun S17DateDial() = StandaloneSurface { DateDialContent(isStandaloneScreen = true) }

@Composable
fun S18TogglePanel() = StandaloneSurface { TogglePanelContent(isStandaloneScreen = true) }

@Composable
fun S19SideNav() = StandaloneSurface { SideNavDrawerContent(isStandaloneScreen = true) }

@Composable
private fun StandaloneSurface(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Mine.Surface0)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState()),
    ) { content() }
}

/* ================================== S25 Search ================================== */

private data class Hit(val kind: String, val title: String, val detail: String, val open: MineAction)

@Composable
fun S25Search() {
    val dispatch = mineDispatch()
    val items = mineSelect { it.items }
    val notes = mineSelect { it.livingNotes }
    val soul = mineSelect { it.soulFacts }
    val reminders = mineSelect { it.reminders }
    var q by rememberSaveable { mutableStateOf("") }
    val query = q.trim().lowercase()
    val hits = remember(query, items, notes, soul, reminders) {
        if (query.isEmpty()) emptyList()
        else buildList {
            items.filter { query in it.title.lowercase() }.forEach {
                add(Hit("Task", it.title, it.dueLabel, MineAction.OpenModal(ModalId.M04, itemId = it.id)))
            }
            notes.filter { n -> query in n.title.lowercase() || n.bullets.any { query in it.lowercase() } }.forEach { n ->
                add(Hit("Note", n.title, n.bullets.firstOrNull { query in it.lowercase() } ?: n.updatedAt, MineAction.OpenModal(ModalId.M07, noteId = n.id)))
            }
            soul.filter { query in it.text.lowercase() }.forEach { add(Hit("About you", it.text, it.category.label, MineAction.Navigate(ScreenId.S20))) }
            reminders.filter { query in it.title.lowercase() }.forEach { add(Hit("Reminder", it.title, it.time, MineAction.Navigate(ScreenId.S21))) }
        }.take(60)
    }
    SettingsPage("Search", "Tasks, notes, reminders and what Mine knows about you.") {
        Box(
            Modifier
                .fillMaxWidth()
                .height(48.dp)
                .background(Mine.Surface2, Shapes.R14)
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LIcon(Lucide.Search, 16.dp, Mine.Ink3)
                MineField(q, { q = it }, MineType.Callout, Mine.Ink1, Modifier.fillMaxWidth(), placeholder = "Search")
            }
        }
        when {
            query.isEmpty() -> EmptyHint(Lucide.Search, "Start typing", "Results appear as you type. Everything is searched on this phone.")
            hits.isEmpty() -> EmptyHint(Lucide.Search, "Nothing matches “$q”", "Try a shorter word, or ask Mine in chat — it can search past conversations too.")
            else -> Section(header = "${hits.size} result${if (hits.size == 1) "" else "s"}") {
                hits.forEachIndexed { i, h ->
                    GroupRow(
                        title = h.title,
                        detail = "${h.kind} · ${h.detail}",
                        divider = i < hits.lastIndex,
                        onClick = { dispatch(h.open) },
                        trailing = { LIcon(Lucide.ChevronRight, 16.dp, Mine.Ink4) },
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyHint(glyph: com.mine.companion.ui.icons.LucideGlyph, title: String, body: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 48.dp, horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LIcon(glyph, 22.dp, Mine.Ink4)
        Txt(title, MineType.Callout.w(600), Mine.Ink1, textAlign = TextAlign.Center)
        Txt(body, MineType.Footnote, Mine.Ink3, textAlign = TextAlign.Center)
    }
}

/* ================================= S26 Settings ================================= */

@Composable
fun S26Settings() {
    val dispatch = mineDispatch()
    val e = mineSelect { it.engine }
    val p = mineSelect { it.platform }
    val ambientPaused = mineSelect { it.ambientListeningPaused }
    val devMode = mineSelect { it.devMode }
    val name = mineSelect { it.userName }
    val providerDetail = when {
        e.providerKey.isNotBlank() -> providerName(e.providerEndpoint) + (e.providerModel.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")
        e.onDeviceFallbackEnabled -> "On-device model only"
        else -> "Not connected"
    }
    SettingsPage("Settings", if (name.isNotBlank()) "Mine, set up for $name." else null, onBack = { dispatch(MineAction.Navigate(ScreenId.S14)) }) {
        if (e.providerKey.isBlank() && !e.onDeviceFallbackEnabled) {
            StatusCard(
                "Connect an AI provider", "Reminders and quick tasks already work. Chat needs a model.",
                Tone.Warn, Lucide.Zap, "Connect", { dispatch(MineAction.Navigate(ScreenId.S07)) },
            )
        }
        Section("Assistant") {
            NavRow("AI provider", providerDetail) { dispatch(MineAction.Navigate(ScreenId.S07)) }
            NavRow("Personality & focus", personaLabel(e.activePersonaId)) { dispatch(MineAction.Navigate(ScreenId.S18)) }
            NavRow("About you", "What Mine remembers") { dispatch(MineAction.Navigate(ScreenId.S20)) }
            NavRow("On-device AI", if (e.onDeviceFallbackEnabled) "Backup model on" else "Off", divider = false) { dispatch(MineAction.Navigate(ScreenId.S39)) }
        }
        Section("Listening & voice") {
            NavRow(
                "Listening & notifications",
                when {
                    ambientPaused -> "Off"
                    e.notificationReading.enabled -> "On · reading notifications"
                    else -> "On"
                },
            ) { dispatch(MineAction.Navigate(ScreenId.S36)) }
            NavRow("Voice", if (p.offlineSttInstalled || p.systemSttAvailable) "Hands-free ready" else "Needs a speech engine") { dispatch(MineAction.Navigate(ScreenId.S35)) }
            NavRow("Places", if (e.contextSensingEnabled) "On" else "Off", divider = false) { dispatch(MineAction.Navigate(ScreenId.S34)) }
        }
        Section("Reliability & data") {
            NavRow(
                "Alarms & reminders",
                if (e.atRisk || !p.notificationsGranted) "Needs attention" else "Working",
                badge = if (e.atRisk || !p.notificationsGranted) "!" else null,
            ) { dispatch(MineAction.Navigate(ScreenId.S27)) }
            NavRow("Backup & restore", if (e.autoBackupEnabled) "Weekly backups on" else "Manual") { dispatch(MineAction.Navigate(ScreenId.S28)) }
            NavRow("Privacy & security", if (e.appLockEnabled) "App lock on" else "App lock off") { dispatch(MineAction.Navigate(ScreenId.S38)) }
            NavRow("Insights", "Your week at a glance", divider = !devMode) { dispatch(MineAction.Navigate(ScreenId.S40)) }
            if (devMode) NavRow("Diagnostics", "Engine state and self-tests", divider = false) { dispatch(MineAction.Navigate(ScreenId.S30)) }
        }
        Section(footer = "Mine keeps everything in an encrypted database on this phone. Only what you send in chat reaches your AI provider, minus anything marked “on this phone only”.") {
            SwitchRow("Developer mode", "Shows diagnostics", devMode, divider = false) { dispatch(MineAction.ToggleSetting(SettingKey.devMode)) }
        }
    }
}

private fun providerName(endpoint: String): String {
    val host = runCatching { java.net.URI(endpoint).host }.getOrNull()?.removePrefix("api.")?.removePrefix("www.") ?: return "Custom"
    return when {
        "openai" in host -> "OpenAI"
        "anthropic" in host -> "Anthropic"
        "openrouter" in host -> "OpenRouter"
        "mistral" in host -> "Mistral"
        "groq" in host -> "Groq"
        "googleapis" in host || "gemini" in host -> "Google"
        "deepseek" in host -> "DeepSeek"
        "together" in host -> "Together"
        "localhost" in host || host.startsWith("192.168") || host.startsWith("10.") -> "Local server"
        else -> host
    }
}

private fun personaLabel(id: String) = when (id) {
    "cofounder" -> "Cofounder"
    "coach" -> "Coach"
    "chill_friend" -> "Chill friend"
    "minimal" -> "Minimal"
    else -> "Companion"
}

/* ========================= S07 (settings form) AI provider ========================= */

private data class Preset(val name: String, val endpoint: String, val model: String)

private val Presets = listOf(
    Preset("OpenAI", "https://api.openai.com/v1/chat/completions", "gpt-4o-mini"),
    Preset("OpenRouter", "https://openrouter.ai/api/v1/chat/completions", "openai/gpt-4o-mini"),
    Preset("Mistral", "https://api.mistral.ai/v1/chat/completions", "mistral-small-latest"),
    Preset("Groq", "https://api.groq.com/openai/v1/chat/completions", "llama-3.3-70b-versatile"),
)

/** The provider form, shared by onboarding (with a Continue) and Settings. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProviderForm(onSaved: (() -> Unit)? = null) {
    val dispatch = mineDispatch()
    val e = mineSelect { it.engine }
    val result = mineSelect { it.engine.lastActionResult?.takeIf { r -> r.tag == "provider-test" } }
    var endpoint by rememberSaveable(e.providerEndpoint) { mutableStateOf(e.providerEndpoint.ifBlank { Presets[0].endpoint }) }
    var model by rememberSaveable(e.providerModel) { mutableStateOf(e.providerModel.ifBlank { Presets[0].model }) }
    var key by rememberSaveable(e.providerKey) { mutableStateOf(e.providerKey) }
    var testing by remember { mutableStateOf(false) }
    var lastSeq by remember { mutableStateOf(result?.seq ?: 0L) }
    LaunchedEffect(result?.seq) { if (result != null && result.seq != lastSeq) testing = false }

    FlowRow(Modifier.fillMaxWidth().padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Presets.forEach { pr ->
            val on = endpoint == pr.endpoint
            Pressable(
                onClick = { endpoint = pr.endpoint; model = pr.model },
                feel = MineMotion.Press.Chip,
                modifier = Modifier.height(34.dp).background(if (on) Mine.Ink1 else Mine.Surface2, CircleShape).padding(horizontal = 14.dp),
            ) { Txt(pr.name, MineType.Footnote.w(600), if (on) Mine.Canvas else Mine.Ink1) }
        }
    }
    Section(footer = "Any OpenAI-compatible chat endpoint works. Your key is stored encrypted on this phone and sent only to this address.") {
        FieldRow("Address", endpoint, { endpoint = it }, "https://…/v1/chat/completions", keyboard = KeyboardType.Uri)
        FieldRow("Model", model, { model = it }, "gpt-4o-mini")
        FieldRow("API key", key, { key = it }, "sk-…", secret = true, divider = false)
    }
    if (result != null && result.seq != lastSeq || testing) {
        Spacer(Modifier.height(12.dp))
        when {
            testing -> StatusCard("Testing…", "Sending a one-word request.", Tone.Neutral, Lucide.Loader2)
            result != null && !result.isError -> StatusCard(result.message, "Streaming works. You're ready to chat.", Tone.Good, Lucide.Check)
            result != null -> StatusCard("Couldn't connect", result.message, Tone.Bad, Lucide.TriangleAlert)
        }
    }
    Spacer(Modifier.height(20.dp))
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        CapsuleButton("Test connection", primary = false, busy = testing, enabled = endpoint.isNotBlank() && key.isNotBlank()) {
            testing = true
            lastSeq = result?.seq ?: lastSeq
            dispatch(MineAction.Engine(UiAction.TestProvider(endpoint, key, model)))
        }
        CapsuleButton(if (onSaved != null) "Save and continue" else "Save", enabled = endpoint.isNotBlank() && key.isNotBlank()) {
            dispatch(MineAction.Engine(UiAction.SaveProvider(endpoint, key, model)))
            dispatch(MineAction.ShowToast("Provider saved"))
            onSaved?.invoke()
        }
    }
}

@Composable
fun ProviderSettingsScreen() {
    val e = mineSelect { it.engine }
    val dispatch = mineDispatch()
    SettingsPage("AI provider", "Bring your own model. Mine works with any OpenAI-compatible API.") {
        ProviderForm()
        Section("If your provider is down", "Mine tries this second provider before giving up, then falls back to the on-device model if it's on.") {
            NavRow("Backup provider", if (e.fallbackKey.isNotBlank()) providerName(e.fallbackEndpoint) else "None", divider = false) {
                dispatch(MineAction.Navigate(ScreenId.S39))
            }
        }
        if (e.providerCooldownExpiry != null) {
            Section {
                ActionRow("Retry now", Lucide.RefreshCw, divider = false) { dispatch(MineAction.Engine(UiAction.RetryNow)) }
            }
        }
    }
}

/* ============================ S27 Alarms & reliability ============================ */

@Composable
fun S27Reliability() {
    val dispatch = mineDispatch()
    val e = mineSelect { it.engine }
    val notifs = mineSelect { it.platform.notificationsGranted }
    val healthy = !e.atRisk && notifs
    SettingsPage("Alarms & reminders", "Exact alarms wake your phone even in Doze, so reminders arrive on time.") {
        when {
            !notifs -> StatusCard(
                "Notifications are off for Mine", "Reminders will be silent until you allow notifications.",
                Tone.Bad, Lucide.BellRing, "Allow", { dispatch(MineAction.RequestPermission(android.Manifest.permission.POST_NOTIFICATIONS)) },
            )
            e.atRisk -> StatusCard("Some reminders may not ring", e.schedulingError ?: "Android refused to schedule an alarm.", Tone.Bad, Lucide.TriangleAlert, "Check again", { dispatch(MineAction.Engine(UiAction.RunSelfTest)) })
            else -> StatusCard("Reminders are working", "Every pending alarm is registered with Android.", Tone.Good, Lucide.Check)
        }
        Section("Test", "A test alarm rings in 5 seconds. Lock your phone to see it wake up.") {
            ValueRow("Last test", e.selfTestStatus.ifBlank { "Never" })
            ActionRow("Ring a test alarm", Lucide.AlarmClock, divider = false) { dispatch(MineAction.Engine(UiAction.RunSelfTest)) }
        }
        if (e.missedReminders.isNotEmpty()) {
            Section("Missed while your phone was off") {
                e.missedReminders.forEachIndexed { i, m ->
                    GroupRow(
                        title = m.text,
                        detail = "Was due ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(m.scheduledFor))}",
                        divider = i < e.missedReminders.lastIndex,
                    )
                }
            }
        }
        Section("How alarms work", footer = e.alarmDisclosureText) {
            ValueRow("Pending reminders", "${e.activeReminders.size}")
            ValueRow("Scheduling failures", "${e.schedulingFailedCount}", divider = false, valueColor = if (e.schedulingFailedCount > 0) Mine.Urgent else Mine.Ink3)
        }
    }
}

/* =============================== S28 Backup & restore =============================== */

private enum class BackupMode { Export, Restore, Verify, Auto }

@Composable
fun S28Backup() {
    val dispatch = mineDispatch()
    val e = mineSelect { it.engine }
    val backup = mineSelect { it.platform.backup }
    var mode by remember { mutableStateOf<BackupMode?>(null) }
    var pass by remember { mutableStateOf("") }
    var includeKeys by remember { mutableStateOf(false) }
    val picked = remember { mutableStateOf<android.net.Uri?>(null) }

    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) { picked.value = uri; mode = BackupMode.Export }
    }
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { picked.value = uri; if (mode == null) mode = BackupMode.Restore }
    }
    val markdownPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        if (uri != null) dispatch(MineAction.ExportMarkdown(uri))
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) { picked.value = uri; mode = BackupMode.Auto }
    }

    SettingsPage("Backup & restore", "Encrypted with Argon2id and AES-256. Only your passphrase can open it.") {
        backup.message?.let { msg ->
            StatusCard(
                if (backup.busy) "Working…" else if (backup.isError) "That didn't work" else "Done",
                msg, if (backup.busy) Tone.Neutral else if (backup.isError) Tone.Bad else Tone.Good,
                if (backup.isError) Lucide.TriangleAlert else Lucide.Check,
            )
        }
        val m = mode
        if (m != null && picked.value != null) {
            Section(
                when (m) {
                    BackupMode.Export -> "Choose a passphrase"
                    BackupMode.Auto -> "Passphrase for weekly backups"
                    BackupMode.Restore -> "Passphrase for this backup"
                    BackupMode.Verify -> "Passphrase for this backup"
                },
                footer = when (m) {
                    BackupMode.Export, BackupMode.Auto -> "At least 8 characters. Write it down: without it, the backup can't be opened — not even by us."
                    BackupMode.Restore -> "Restoring replaces everything on this phone with the backup."
                    BackupMode.Verify -> "Checks the file opens with this passphrase. Nothing on this phone changes."
                },
            ) {
                FieldRow("Passphrase", pass, { pass = it }, "At least 8 characters", secret = true, divider = m == BackupMode.Export)
                if (m == BackupMode.Export) SwitchRow("Include API keys", "Only if this file stays somewhere safe", includeKeys, divider = false) { includeKeys = it }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CapsuleButton("Cancel", Modifier.weight(1f), primary = false) { mode = null; pass = ""; picked.value = null }
                CapsuleButton(
                    when (m) { BackupMode.Export -> "Encrypt & save"; BackupMode.Restore -> "Restore"; BackupMode.Verify -> "Check"; BackupMode.Auto -> "Turn on" },
                    Modifier.weight(1f), enabled = pass.length >= 8 || m == BackupMode.Verify && pass.isNotEmpty(), busy = backup.busy,
                ) {
                    val uri = picked.value ?: return@CapsuleButton
                    when (m) {
                        BackupMode.Export -> dispatch(MineAction.ExportBackup(uri, pass, includeKeys))
                        BackupMode.Restore -> dispatch(MineAction.ImportBackup(uri, pass, verifyOnly = false))
                        BackupMode.Verify -> dispatch(MineAction.ImportBackup(uri, pass, verifyOnly = true))
                        BackupMode.Auto -> dispatch(MineAction.SetAutoBackupFolder(uri, pass))
                    }
                    mode = null; pass = ""; picked.value = null
                }
            }
        }
        Section("Back up") {
            ActionRow("Export encrypted backup", Lucide.Download, enabled = !backup.busy) { exportPicker.launch("mine-backup-${java.time.LocalDate.now()}.mnex") }
            ActionRow("Export readable copy (Markdown)", Lucide.NotebookText, enabled = !backup.busy, divider = false) { markdownPicker.launch("mine-${java.time.LocalDate.now()}.md") }
        }
        Section("Restore") {
            ActionRow("Restore from a backup…", Lucide.Upload, enabled = !backup.busy) { mode = BackupMode.Restore; importPicker.launch(arrayOf("*/*")) }
            ActionRow("Check a backup opens", Lucide.Check, enabled = !backup.busy, divider = false) { mode = BackupMode.Verify; importPicker.launch(arrayOf("*/*")) }
        }
        Section("Automatic", footer = "Mine keeps the five most recent weekly backups in the folder you choose.") {
            SwitchRow("Weekly backups", if (e.autoBackupUri.isNotBlank()) "Saving to your chosen folder" else "Choose a folder to turn on", e.autoBackupEnabled, divider = false) { on ->
                if (on && e.autoBackupUri.isBlank()) folderPicker.launch(null)
                else dispatch(MineAction.Engine(UiAction.SetAutoBackup(on)))
            }
        }
    }
}

/* ================================= S29 Relations ================================= */

/** Items that share a living note are related; the note sits between them. */
@Composable
fun S29Graph() {
    val dispatch = mineDispatch()
    val items = mineSelect { it.items }
    val notes = mineSelect { it.livingNotes }
    val linked = remember(items, notes) {
        notes.mapNotNull { n ->
            val members = items.filter { it.linkedNoteId == n.id || it.id == n.linkedTaskId }
            if (members.isEmpty()) null else n to members
        }
    }
    SettingsPage("Connections", "How your tasks and living notes link together.") {
        if (linked.isEmpty()) {
            EmptyHint(Lucide.Link, "No connections yet", "When you ask Mine to keep notes for a task, the link shows up here.")
        } else {
            linked.forEach { (note, members) ->
                Section(note.title) {
                    Canvas(Modifier.fillMaxWidth().height(10.dp)) {
                        drawRoundRect(Mine.Accent.copy(alpha = 0.25f), size = Size(size.width * 0.3f, 3.dp.toPx()), topLeft = Offset(16.dp.toPx(), size.height / 2f), cornerRadius = CornerRadius(2f))
                    }
                    GroupRow(
                        title = "Open note",
                        detail = "${note.bullets.size} point${if (note.bullets.size == 1) "" else "s"} · ${if (note.accessMode == com.mine.companion.store.NoteAccessMode.read_only) "Read only for Mine" else "Mine can update it"}",
                        divider = true,
                        onClick = { dispatch(MineAction.OpenModal(ModalId.M07, noteId = note.id)) },
                        trailing = { LIcon(Lucide.NotebookText, 16.dp, Mine.Ink3) },
                    )
                    members.forEachIndexed { i, item ->
                        GroupRow(
                            title = item.title,
                            detail = item.dueLabel,
                            divider = i < members.lastIndex,
                            onClick = { dispatch(MineAction.OpenModal(ModalId.M04, itemId = item.id)) },
                            trailing = { LIcon(Lucide.ChevronRight, 16.dp, Mine.Ink4) },
                        )
                    }
                }
            }
        }
    }
}

/* ================================= S30 Diagnostics ================================= */

@Composable
fun S30Debug() {
    val dispatch = mineDispatch()
    val e = mineSelect { it.engine }
    val p = mineSelect { it.platform }
    var confirmReset by remember { mutableStateOf(false) }
    SettingsPage("Diagnostics", "For troubleshooting. Nothing here leaves your phone.") {
        Section("Engine") {
            ValueRow("Proactive gate", e.nudgeGate ?: "Not run yet")
            ValueRow("Queued messages", "${e.queuedChatIds.size}")
            ValueRow("Open proposals", "${e.openProposals.size}")
            ValueRow("Self-test", e.selfTestStatus, divider = false)
        }
        Section("Device") {
            ValueRow("Microphone", if (p.micGranted) "Allowed" else "Not allowed")
            ValueRow("Notifications", if (p.notificationsGranted) "Allowed" else "Blocked")
            ValueRow("Notification access", if (p.notificationAccess) (if (p.listenerConnected) "Connected" else "Granted") else "Not granted")
            ValueRow("Offline speech", if (p.offlineSttInstalled) "Installed" else "Not installed")
            ValueRow("Battery saver", if (p.lowPower) "On" else "Off", divider = false)
        }
        Section("Storage") {
            Txt(e.debugStats, MineType.Footnote.num(), Mine.Ink2, Modifier.padding(16.dp))
        }
        Section {
            ActionRow("Refresh", Lucide.RefreshCw) { dispatch(MineAction.Engine(UiAction.RefreshStats)) }
            ActionRow("Run a proactive check now", Lucide.Sparkles) { dispatch(MineAction.Engine(UiAction.TickProactive)) }
            ActionRow(if (confirmReset) "Tap again to erase everything" else "Reset database…", Lucide.Trash2, tint = Mine.Urgent, divider = false) {
                if (confirmReset) dispatch(MineAction.Engine(UiAction.ConfirmResetDatabase(true))) else confirmReset = true
            }
        }
    }
}

/* ============================ S31 – S40 System screens ============================ */

@Composable
fun S31S40SystemScreen(screenId: ScreenId) {
    when (screenId) {
        ScreenId.S31 -> S27Reliability()
        ScreenId.S32 -> S38Privacy()
        ScreenId.S33 -> ProviderSettingsScreen()
        ScreenId.S34 -> S34Places()
        ScreenId.S35 -> S35Voice()
        ScreenId.S36 -> S36Listening()
        ScreenId.S37 -> S37Heard()
        ScreenId.S38 -> S38Privacy()
        ScreenId.S39 -> S39OnDevice()
        else -> S40Insights()
    }
}

/* =================================== S34 Places =================================== */

@Composable
fun S34Places() {
    val dispatch = mineDispatch()
    val on = mineSelect { it.contextSensing5GGuard }
    val label = mineSelect { it.platform.contextLabel }
    val errands = mineSelect { s -> s.items.filter { it.triggerType == com.mine.companion.store.TriggerType.leave_home && it.status != ItemStatus.done } }
    SettingsPage("Places", "Reminders that fire when you leave home, and a gentle walk companion.") {
        Section(footer = "Mine uses your Wi-Fi network and motion, never GPS history. Nothing about where you go is sent anywhere.") {
            SwitchRow("Place sensing", label ?: if (on) "Learning where home is" else "Off", on, divider = on) { dispatch(MineAction.ToggleSetting(SettingKey.contextSensing5GGuard)) }
            if (on) ActionRow("Use this Wi-Fi as Home", Lucide.Wifi, divider = false) { dispatch(MineAction.Engine(UiAction.SetCurrentWifiAsHome)) }
        }
        Section("When I leave home") {
            if (errands.isEmpty()) {
                GroupRow("Nothing waiting", detail = "Say “remind me to buy milk when I leave”.", divider = false)
            } else errands.forEachIndexed { i, it ->
                GroupRow(it.title, detail = it.fallbackTime?.let { t -> "Or at $t if you don't leave" } ?: "Fires as you leave", divider = i < errands.lastIndex,
                    onClick = { dispatch(MineAction.OpenModal(ModalId.M04, itemId = it.id)) })
            }
        }
    }
}

/* =================================== S35 Voice =================================== */

@Composable
fun S35Voice() {
    val dispatch = mineDispatch()
    val p = mineSelect { it.platform }
    val voiceReplies = mineSelect { it.voiceRepliesEnabled }
    val stt = when {
        p.offlineSttInstalled -> "On-device (private, works offline)"
        p.systemSttAvailable -> "Android speech recognition"
        else -> "Not available"
    }
    SettingsPage("Voice", "Talk to Mine hands-free. Say “stop” or tap × to end.") {
        if (!p.micGranted) {
            StatusCard("Microphone access needed", "Voice and listening can't hear you yet.", Tone.Warn, Lucide.Mic, "Allow",
                { dispatch(MineAction.RequestPermission(android.Manifest.permission.RECORD_AUDIO)) })
        }
        Section {
            ActionRow("Start a voice conversation", Lucide.AudioLines, divider = false) {
                dispatch(MineAction.Navigate(ScreenId.S15))
                dispatch(MineAction.StartVoice)
            }
        }
        Section("Replies", "When on, Mine reads its answers aloud after you type, too.") {
            SwitchRow("Read replies aloud", null, voiceReplies, divider = false) { dispatch(MineAction.ToggleSetting(SettingKey.voiceRepliesEnabled)) }
        }
        Section("Engines", footer = if (!p.offlineSttInstalled) "For fully offline, private voice, install the Sherpa-ONNX speech models into Mine's files folder." else null) {
            ValueRow("Speech to text", stt)
            ValueRow("Text to speech", if (p.offlineTtsInstalled) "On-device Kokoro" else "Android voice", divider = false)
        }
    }
}

/* ============================ S36 Listening & notifications ============================ */

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun S36Listening() {
    val dispatch = mineDispatch()
    val e = mineSelect { it.engine }
    val p = mineSelect { it.platform }
    val ambient = mineSelect { it.ambientState }
    val paused = mineSelect { it.ambientListeningPaused }
    val s = e.notificationReading
    var confirmDelete by remember { mutableStateOf(false) }

    val ambientDetail = when (ambient) {
        is AmbientState.Listening -> "Listening now"
        is AmbientState.Processing -> "Making sense of what was said"
        is AmbientState.Sleeping -> "Asleep · ${ambient.reason.display}"
        AmbientState.Off -> "Off"
    }
    SettingsPage("Listening", "Mine can quietly notice commitments in conversation — and read your notifications to you while it does.") {
        Section(footer = "Audio is processed on this phone and discarded. Only the commitments you approve are kept.") {
            SwitchRow("Ambient listening", ambientDetail, !paused, divider = !paused) { dispatch(MineAction.ToggleSetting(SettingKey.ambientListeningPaused)) }
            if (!paused) {
                ActionRow("Remember the last 2 minutes", Lucide.Clock) { dispatch(MineAction.Engine(UiAction.BookmarkAmbientLast2Min)) }
                NavRow("Review what was heard", "${e.ambientReportItems.count { it.state == "pending" }} waiting · ${p.heard.size} notifications", divider = false) {
                    dispatch(MineAction.Navigate(ScreenId.S37))
                }
            }
        }

        SectionHeader("Read notifications aloud")
        if (s.enabled && !p.notificationAccess) {
            StatusCard("Allow notification access", "Android asks once. Mine only reads notifications while listening is on.", Tone.Warn, Lucide.Bell, "Open settings",
                { dispatch(MineAction.OpenNotificationAccess) })
            Spacer(Modifier.height(10.dp))
        } else if (s.enabled && paused) {
            StatusCard("Waiting for listening", "Notifications are read only while ambient listening is on.", Tone.Neutral, Lucide.Info, "Turn listening on",
                { dispatch(MineAction.SetAmbientListening(true)) })
            Spacer(Modifier.height(10.dp))
        }
        GroupedSurface {
            SwitchRow("Read notifications", if (s.enabled) "While Mine is listening" else "Off", s.enabled, divider = s.enabled) {
                dispatch(MineAction.SetNotificationReading(s.copy(enabled = it)))
            }
            if (s.enabled) {
                ChoiceRow("How much to say", ContentLevel.entries.toList(), s.contentLevel, {
                    when (it) { ContentLevel.FULL -> "Everything"; ContentLevel.SENDER_ONLY -> "Who it's from"; ContentLevel.APP_ONLY -> "Just the app" }
                }) {
                    dispatch(MineAction.SetNotificationReading(s.copy(contentLevel = it)))
                }
                ChoiceRow("From", listOf(AppScope.MESSAGES, AppScope.ALL, AppScope.ALLOWLIST), s.scope, {
                    when (it) { AppScope.MESSAGES -> "Messages"; AppScope.ALL -> "All apps"; AppScope.ALLOWLIST -> "Chosen" }
                }) { dispatch(MineAction.SetNotificationReading(s.copy(scope = it))) }
                SwitchRow("Only with headphones", "Keeps messages private around others", s.headphonesOnly) {
                    dispatch(MineAction.SetNotificationReading(s.copy(headphonesOnly = it)))
                }
                SwitchRow("Quiet outside my day", "Stays silent between ${minuteLabel(e.profile.dayEndMinute)} and ${minuteLabel(e.profile.dayStartMinute)}", s.respectQuietHours) {
                    dispatch(MineAction.SetNotificationReading(s.copy(respectQuietHours = it)))
                }
                SwitchRow("Turn plans into suggestions", "“Call me at 6” becomes something to review", s.captureSuggestions) {
                    dispatch(MineAction.SetNotificationReading(s.copy(captureSuggestions = it)))
                }
                ActionRow("Hear an example", Lucide.Volume2, divider = false) { dispatch(MineAction.TestNotificationVoice) }
            }
        }
        if (s.enabled) {
            SectionFooter("Codes, card and phone numbers are never read out. Mine never reads its own notifications, calls, alarms or media controls, and holds back during calls and voice chats.")
            val apps = (p.recentApps.keys + s.allowedPackages + s.blockedPackages).distinct().sortedBy { (p.recentApps[it] ?: it).lowercase() }
            if (apps.isNotEmpty()) {
                SectionHeader("Apps")
                GroupedSurface {
                    apps.forEachIndexed { i, pkg ->
                        val label = p.recentApps[pkg] ?: pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() }
                        val state = when {
                            pkg in s.blockedPackages -> "Muted"
                            pkg in s.allowedPackages -> "Always read"
                            else -> "Default"
                        }
                        ValueRow(label, state, divider = i < apps.lastIndex, valueColor = if (state == "Muted") Mine.Urgent else Mine.Ink3) {
                            // Cycle Default → Always read → Muted → Default.
                            val next = when (state) {
                                "Default" -> s.copy(allowedPackages = s.allowedPackages + pkg, blockedPackages = s.blockedPackages - pkg)
                                "Always read" -> s.copy(allowedPackages = s.allowedPackages - pkg, blockedPackages = s.blockedPackages + pkg)
                                else -> s.copy(allowedPackages = s.allowedPackages - pkg, blockedPackages = s.blockedPackages - pkg)
                            }
                            dispatch(MineAction.SetNotificationReading(next))
                        }
                    }
                }
                SectionFooter("Tap an app to cycle between Default, Always read and Muted. Apps appear here after they send a notification.")
            }
        }

        Section("Privacy") {
            ValueRow("Your voiceprint", if (e.voiceprintEnrolled) "Enrolled" else "Not enrolled", detail = "Helps tell your voice from others nearby")
            ActionRow(if (confirmDelete) "Tap again to delete all listening data" else "Delete all listening data", Lucide.Trash2, tint = Mine.Urgent, divider = false) {
                if (confirmDelete) { dispatch(MineAction.Engine(UiAction.DeleteAllAmbientData)); confirmDelete = false } else confirmDelete = true
            }
        }
    }
}

private fun minuteLabel(m: Int): String = "%02d:%02d".format((m / 60) % 24, m % 60)

/* ================================ S37 Heard & review ================================ */

@Composable
fun S37Heard() {
    val dispatch = mineDispatch()
    val e = mineSelect { it.engine }
    val heard = mineSelect { it.platform.heard }
    val pending = e.ambientReportItems.filter { it.state == "pending" }
    SettingsPage("Heard while listening", "Review commitments before they become tasks. Nothing here is kept unless you add it.") {
        if (pending.isNotEmpty()) {
            Section("To review", footer = null) {
                pending.forEachIndexed { i, item ->
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Txt(item.summary, MineType.Callout.w(500), Mine.Ink1)
                        val flags = item.flags.orEmpty()
                        Txt(
                            when {
                                "from_notification" in flags -> "From a notification"
                                "attribution_uncertain" in flags -> "Heard nearby · not sure you said it"
                                "manual_bookmark" in flags -> "From a moment you saved"
                                else -> "Heard in conversation"
                            },
                            MineType.Footnote, Mine.Ink3,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Pressable(onClick = { dispatch(MineAction.Engine(UiAction.AcceptAmbientItem(item.id))) }, modifier = Modifier.height(34.dp).background(Mine.Ink1, CircleShape).padding(horizontal = 14.dp)) {
                                Txt("Add to my list", MineType.Footnote.w(600), Mine.Canvas)
                            }
                            Pressable(onClick = { dispatch(MineAction.Engine(UiAction.RejectAmbientItem(item.id))) }, modifier = Modifier.height(34.dp).background(Mine.Surface2, CircleShape).padding(horizontal = 14.dp)) {
                                Txt("Dismiss", MineType.Footnote.w(600), Mine.Ink2)
                            }
                        }
                    }
                    if (i < pending.lastIndex) Box(Modifier.fillMaxWidth().padding(start = 16.dp).height(0.5.dp).background(Mine.Hairline))
                }
            }
            val report = e.latestAmbientReport
            if (report != null && pending.size > 1) {
                Spacer(Modifier.height(10.dp))
                CapsuleButton("Add all ${pending.size}", primary = false) { dispatch(MineAction.Engine(UiAction.AcceptAllAmbientItems(report.id))) }
            }
        }
        SectionHeader("Notifications")
        if (heard.isEmpty()) {
            GroupedSurface { GroupRow("Nothing yet", detail = "Notifications Mine hears while listening appear here until you close the app.", divider = false) }
        } else {
            GroupedSurface {
                heard.take(40).forEachIndexed { i, h ->
                    val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(h.postedAt))
                    GroupRow(
                        title = listOfNotNull(h.appLabel, h.from).joinToString(" · "),
                        detail = "$time · " + if (h.spoken) h.preview else "${h.holdReason?.label ?: "Not read"} · ${h.preview}",
                        divider = i < minOf(heard.size, 40) - 1,
                        trailing = {
                            val sug = h.suggestion
                            when {
                                sug != null && h.suggestionSaved -> Txt("In review", MineType.Caption.w(500), Mine.Settled)
                                sug != null -> Pressable(
                                    onClick = { dispatch(MineAction.SaveHeardSuggestion(h.id)) },
                                    modifier = Modifier.height(30.dp).background(Mine.Surface3, CircleShape).padding(horizontal = 10.dp),
                                ) { Txt("Suggest", MineType.Caption.w(600), Mine.Ink1) }
                                h.spoken -> LIcon(Lucide.Volume2, 15.dp, Mine.Ink3)
                                h.holdReason == SkipReason.QUIET_HOURS -> LIcon(Lucide.MoonStar, 15.dp, Mine.Ink4)
                                else -> Unit
                            }
                        },
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            CapsuleButton("Clear list", primary = false) { dispatch(MineAction.ClearHeardFeed) }
        }
    }
}

/* =============================== S38 Privacy & security =============================== */

@Composable
fun S38Privacy() {
    val dispatch = mineDispatch()
    val e = mineSelect { it.engine }
    val relockOptions = listOf(0, 30, 60, 300, 900)
    SettingsPage("Privacy & security", "Who can open Mine, and what your AI provider sees.") {
        Section("App lock", footer = "Uses your fingerprint, face or screen lock.") {
            SwitchRow("Require unlock", null, e.appLockEnabled, divider = e.appLockEnabled) { dispatch(MineAction.Engine(UiAction.SetAppLock(it))) }
            if (e.appLockEnabled) {
                ChoiceRow("Lock again after", relockOptions, relockOptions.minBy { kotlin.math.abs(it - e.relockTimeoutSec) }, {
                    when (it) { 0 -> "Now"; 30 -> "30 s"; 60 -> "1 min"; 300 -> "5 min"; else -> "15 min" }
                }, divider = false) { dispatch(MineAction.Engine(UiAction.SetRelockTimeout(it))) }
            }
        }
        Section("Screen") {
            SwitchRow("Block screenshots", "Also hides Mine in the app switcher", e.blockScreenshots, divider = false) { dispatch(MineAction.Engine(UiAction.SetBlockScreenshots(it))) }
        }
        Section("Notifications", footer = "Applies to Mine's own reminders and to notifications it reads aloud.") {
            SwitchRow("Hide content on lock screen", "Shows “Reminder” instead of the text", e.hideNotificationContent, divider = false) {
                dispatch(MineAction.Engine(UiAction.SetHideNotificationContent(it)))
            }
        }
        Section("What your AI sees", footer = "Anything marked “on this phone only” is never included, whatever these say.") {
            SwitchRow("Compact context", "Sends less history: cheaper, a little less aware", e.compactMode) { dispatch(MineAction.Engine(UiAction.SetCompactMode(it))) }
            SwitchRow("Mood check-ins", "Lets Mine ask how you're feeling", e.moodTrackingEnabled, divider = false) { dispatch(MineAction.Engine(UiAction.SetMoodTracking(it))) }
        }
    }
}

/* ================================ S39 On-device & backup AI ================================ */

@Composable
fun S39OnDevice() {
    val dispatch = mineDispatch()
    val e = mineSelect { it.engine }
    var ep by rememberSaveable(e.fallbackEndpoint) { mutableStateOf(e.fallbackEndpoint) }
    var key by rememberSaveable(e.fallbackKey) { mutableStateOf(e.fallbackKey) }
    var model by rememberSaveable(e.fallbackModel) { mutableStateOf(e.fallbackModel) }
    SettingsPage("Backups for your AI", "So Mine still answers when your main provider can't.") {
        Section(footer = "Runs a small model on this phone when you're offline or your providers fail. Slower and simpler, but private.") {
            SwitchRow("On-device model", if (e.onDeviceFallbackEnabled) "On" else "Off", e.onDeviceFallbackEnabled, divider = false) {
                dispatch(MineAction.Engine(UiAction.SetOnDeviceFallback(it)))
            }
        }
        Section("Second provider") {
            FieldRow("Address", ep, { ep = it }, "https://…/v1/chat/completions", keyboard = KeyboardType.Uri)
            FieldRow("Model", model, { model = it }, "Optional")
            FieldRow("API key", key, { key = it }, "sk-…", secret = true, divider = false)
        }
        Spacer(Modifier.height(14.dp))
        CapsuleButton("Save second provider", enabled = ep.isNotBlank() && key.isNotBlank()) {
            dispatch(MineAction.Engine(UiAction.SaveFallbackProvider(ep, key, model)))
            dispatch(MineAction.ShowToast("Second provider saved"))
        }
    }
}

/* ==================================== S40 Insights ==================================== */

@Composable
fun S40Insights() {
    val dispatch = mineDispatch()
    val report = mineSelect { it.engine.latestInsightsReport }
    LaunchedEffect(Unit) { dispatch(MineAction.Engine(UiAction.LoadInsights(7))) }
    SettingsPage("Your week", "What you got done, and when you do your best work.") {
        if (report == null) {
            EmptyHint(Lucide.ChartNoAxesColumn, "Gathering your week", "Insights appear once you've added and finished a few things.")
            return@SettingsPage
        }
        val w = report.weeklyReview
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile("Done", "${w.completedCount}", Modifier.weight(1f))
            StatTile("Added", "${w.createdCount}", Modifier.weight(1f))
            StatTile("Rate", "${(w.completionRate.let { if (it <= 1f) it * 100 else it }).toInt()}%", Modifier.weight(1f))
        }
        SectionHeader("Last 7 days")
        GroupedSurface {
            val maxV = (w.dailyBars.maxOfOrNull { maxOf(it.createdCount, it.completedCount) } ?: 1).coerceAtLeast(1)
            val accent = Mine.Accent
            val track = Mine.Surface3
            Canvas(Modifier.fillMaxWidth().height(140.dp).padding(16.dp)) {
                val n = w.dailyBars.size.coerceAtLeast(1)
                val gap = 10.dp.toPx()
                val bw = (size.width - gap * (n - 1)) / n
                w.dailyBars.forEachIndexed { i, b ->
                    val x = i * (bw + gap)
                    drawRoundRect(track, Offset(x, 0f), Size(bw, size.height), CornerRadius(6.dp.toPx()))
                    val h = size.height * b.completedCount / maxV
                    drawRoundRect(accent, Offset(x, size.height - h), Size(bw, h), CornerRadius(6.dp.toPx()))
                }
            }
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                w.dailyBars.forEach { b ->
                    val d = runCatching { java.time.LocalDate.parse(b.dayKey).dayOfWeek.getDisplayName(java.time.format.TextStyle.NARROW, java.util.Locale.getDefault()) }.getOrDefault("·")
                    Txt(d, MineType.Caption, Mine.Ink3)
                }
            }
        }
        val heat = report.activityHeatStrip
        if (heat.totalActions > 0) {
            Section("When you get things done", footer = "Busiest around ${"%02d:00".format(heat.peakHour)}.") {
                val accent = Mine.Accent
                val maxH = (heat.hourlyCounts.maxOrNull() ?: 1).coerceAtLeast(1)
                Canvas(Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 16.dp, vertical = 12.dp)) {
                    val cw = size.width / 24f
                    heat.hourlyCounts.forEachIndexed { h, c ->
                        drawRoundRect(accent.copy(alpha = 0.08f + 0.92f * c / maxH), Offset(h * cw + 1f, 0f), Size(cw - 2f, size.height), CornerRadius(3.dp.toPx()))
                    }
                }
            }
        }
        if (report.habitStreaks.isNotEmpty()) {
            Section("Habits") {
                report.habitStreaks.forEachIndexed { i, h ->
                    ValueRow(h.habitTitle, "${h.currentStreak} day${if (h.currentStreak == 1) "" else "s"}", detail = "Best: ${h.bestStreak}", divider = i < report.habitStreaks.lastIndex)
                }
            }
        }
        if (report.goalProgress.isNotEmpty()) {
            Section("Goals") {
                report.goalProgress.forEachIndexed { i, g ->
                    val pct = (if (g.progressPercent <= 1f) g.progressPercent * 100 else g.progressPercent).toInt().coerceIn(0, 100)
                    ValueRow(g.goalTitle, "$pct%", detail = "${g.completedSubTasks} of ${g.totalSubTasks} steps", divider = i < report.goalProgress.lastIndex)
                }
            }
        }
        if (w.wins.isNotEmpty()) {
            Section("Wins") {
                w.wins.take(5).forEachIndexed { i, win -> GroupRow(win, divider = i < minOf(w.wins.size, 5) - 1) }
            }
        }
    }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier) {
    Column(
        modifier.background(Mine.Surface1, Shapes.Group).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Txt(value, MineType.Title.num(), Mine.Ink1)
        Txt(label, MineType.Footnote, Mine.Ink3)
    }
}

/* ============================= Root-level system surfaces ============================= */

/** Shown when storage could not start. Never a dead end: restore, retry or reset. */
@Composable
fun BootRecoveryScreen(error: String) {
    val dispatch = mineDispatch()
    val keystoreLost = error == "KEYSTORE_LOST"
    var confirm by remember { mutableStateOf(false) }
    val restorePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) dispatch(MineAction.ShowToast("Open Mine after resetting, then restore from Settings → Backup"))
    }
    Column(
        Modifier.fillMaxSize().background(Mine.Canvas).statusBarsPadding().navigationBarsPadding().padding(24.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Spacer(Modifier.height(24.dp))
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(52.dp).background(Mine.Urgent.copy(alpha = 0.14f), Shapes.R16), contentAlignment = Alignment.Center) {
                LIcon(Lucide.Lock, 24.dp, Mine.Urgent)
            }
            Txt(if (keystoreLost) "Mine can't unlock your data" else "Mine couldn't start", MineType.LargeTitle, Mine.Ink1)
            Txt(
                if (keystoreLost) "Android cleared the key that protects Mine's database (this can happen after a security update or a screen-lock change). Your data is still encrypted on the phone, but it can't be opened without that key."
                else "Something went wrong opening the encrypted database.\n\n$error",
                MineType.Body, Mine.Ink2,
            )
            if (keystoreLost) Txt("If you have a backup, reset and then restore it from Settings → Backup.", MineType.Callout, Mine.Ink3)
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (!keystoreLost) CapsuleButton("Try again", primary = true) { dispatch(MineAction.Engine(UiAction.ReloadStorage)) }
            CapsuleButton(if (confirm) "Tap again to erase and start fresh" else "Reset Mine", primary = keystoreLost) {
                if (confirm) dispatch(MineAction.Engine(UiAction.ConfirmResetDatabase(true))) else confirm = true
            }
            if (keystoreLost) CapsuleButton("I'll restore a backup after resetting", primary = false) { restorePicker.launch(arrayOf("*/*")) }
        }
    }
}

/** Shown over everything while the app lock is engaged. */
@Composable
fun LockScreen(onUnlock: () -> Unit) {
    LaunchedEffect(Unit) { onUnlock() }
    Column(
        Modifier.fillMaxSize().background(Mine.Canvas).statusBarsPadding().navigationBarsPadding().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.size(64.dp).background(Mine.Surface2, CircleShape), contentAlignment = Alignment.Center) { LIcon(Lucide.Lock, 26.dp, Mine.Ink1) }
        Spacer(Modifier.height(18.dp))
        Txt("Mine is locked", MineType.Title, Mine.Ink1)
        Spacer(Modifier.height(6.dp))
        Txt("Unlock to see your chats and memories.", MineType.Callout, Mine.Ink3, textAlign = TextAlign.Center)
        Spacer(Modifier.height(28.dp))
        CapsuleButton("Unlock", Modifier.width(200.dp)) { onUnlock() }
    }
}

/** A deep link or QR code offered new provider settings: confirm before anything changes. */
@Composable
fun ProviderImportPrompt() {
    val dispatch = mineDispatch()
    val pending = mineSelect { it.engine.pendingProviderImport } ?: return
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.BottomCenter) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(Mine.Surface1, Shapes.SheetTop)
                .navigationBarsPadding()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Txt("Use this AI provider?", MineType.Title, Mine.Ink1)
            Txt(pending.endpoint, MineType.Callout, Mine.Ink2, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (pending.model.isNotBlank()) Txt("Model: ${pending.model}", MineType.Footnote, Mine.Ink3)
            Txt("Only accept links you created or trust. Your messages will be sent to this address.", MineType.Footnote, Mine.Ink3)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CapsuleButton("Not now", Modifier.weight(1f), primary = false) { dispatch(MineAction.Engine(UiAction.RejectProviderImport)) }
                CapsuleButton("Use it", Modifier.weight(1f)) { dispatch(MineAction.Engine(UiAction.ConfirmProviderImport)) }
            }
        }
    }
}
