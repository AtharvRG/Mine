package com.mine.companion.store

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.anchor.core.ambient.AmbientSleepReason as EngineSleepReason
import com.anchor.core.ambient.AmbientState as EngineAmbientState
import com.anchor.core.export.ExportController
import com.anchor.core.export.MIN_PASSPHRASE_LENGTH
import com.anchor.core.ui.UiAction
import com.anchor.core.ui.UiState
import com.anchor.mine.AppGraph
import com.anchor.mine.MineApp
import com.anchor.mine.ambient.AmbientForegroundService
import com.anchor.mine.ambient.AmbientStatusBus
import com.anchor.mine.notifications.NotificationReader
import com.anchor.mine.startupFailureMessage
import com.anchor.mine.voice.Speaker
import com.anchor.mine.voice.VoiceModelPaths
import com.anchor.mine.voice.VoiceSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * The bridge between the companion UI and the engine.
 *
 * One StateFlow feeds every screen. It is the reducer's UI state (navigation, sheets, toasts)
 * merged with the engine's live state (mapped by [EngineMapper]) and the device's state
 * (permissions, voice, notification reading). Actions run through the pure reducer first, so
 * taps feel instant, and then through [effect], which does the real work in the engine.
 *
 * Nothing here blocks the main thread: engine calls launch on the engine's own scope, mapping
 * runs on a background dispatcher, and only the final state hops to the UI.
 */
class MineStore(application: Application) : AndroidViewModel(application) {

    private val app get() = getApplication<Application>()
    private val _state = MutableStateFlow(MineSeed.initial)
    val state: StateFlow<MineUiState> = _state.asStateFlow()

    private val _permissionRequests = MutableSharedFlow<String>(extraBufferCapacity = 4)
    /** The Activity collects this and shows the system permission prompt. */
    val permissionRequests: SharedFlow<String> = _permissionRequests.asSharedFlow()

    private val mapper = EngineMapper()
    private var graph: AppGraph? = null
    private val pendingEngine = ConcurrentLinkedQueue<UiAction>()
    private var toastJob: Job? = null
    private var nameJob: Job? = null
    private var lastResultSeq = 0L
    private var hydrated = false
    private var startVoiceAfterMic = false
    private var startAmbientAfterMic = false
    private var exportController: ExportController? = null

    /** Optimistic guesses kept until the engine catches up (or a few seconds pass). */
    private val itemStatusOverlay = ConcurrentHashMap<String, Pair<ItemStatus, Long>>()
    private val removedReminderOverlay = ConcurrentHashMap<String, Long>()
    private val platform = MutableStateFlow(PlatformSnapshot())

    init {
        Speaker.init(application)
        viewModelScope.launch { boot() }
    }

    // =======================================================================================
    // Boot
    // =======================================================================================

    private suspend fun boot() {
        refreshPlatform()
        val g = try {
            val mineApp = app as MineApp
            withTimeout(30_000) { mineApp.ensureAppGraphInitialized().await() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            _state.update { it.copy(bootError = startupFailureMessage(e), isHydrating = false) }
            return
        }
        graph = g
        while (true) g.chatController.onAction(pendingEngine.poll() ?: break)

        val chat = g.chatController
        chat.onAction(UiAction.OpenHome)
        chat.onAction(UiAction.LoadMemory)
        chat.onAction(UiAction.TickProactive)
        chat.onAction(UiAction.DrainQueuedTurns)

        viewModelScope.launch(Dispatchers.IO) { recoverMissedAndCaptures(g) }
        viewModelScope.launch { collect(g) }
        viewModelScope.launch { platformTicker() }
    }

    /** Fires the missed-reminder sweeper and sends captures stored while the app was dead. */
    private suspend fun recoverMissedAndCaptures(g: AppGraph) {
        runCatching {
            g.reliabilityManager.runSweeper().forEach { missed ->
                app.sendBroadcast(Intent(app, com.anchor.mine.alarms.AlarmReceiver::class.java).apply {
                    putExtra("EXTRA_ID", missed.id)
                    putExtra("EXTRA_TEXT", missed.text)
                    putExtra("EXTRA_TIER", missed.tier.name)
                    putExtra("EXTRA_MISSED", true)
                    putExtra("EXTRA_SCHEDULED_FOR", missed.fireAtMillis)
                })
            }
        }
        runCatching {
            for (captureId in com.anchor.core.capture.QuickCapture.pending(g.storage)) {
                val text = g.storage.getRawMessage(captureId)
                if (!text.isNullOrBlank()) g.chatController.sendText(text, captureId)
            }
        }
    }

    private suspend fun collect(g: AppGraph) {
        val device = combine(
            AmbientStatusBus.state,
            VoiceSession.ui,
            NotificationReader.status,
            Speaker.speakingId,
            platform,
        ) { ambient, voice, reader, speakingId, plat -> Device(ambient, voice, reader, speakingId, plat) }

        combine(g.chatController.state, device) { engine, dev -> engine to dev }
            .conflate()
            .flowOn(Dispatchers.Default)
            .collect { (engine, dev) ->
                val merged = withContext(Dispatchers.Default) { merge(_state.value, engine, dev) }
                _state.value = merged
                onEngineSideEffects(engine, dev)
            }
    }

    private data class Device(
        val ambient: EngineAmbientState,
        val voice: VoiceSession.Ui,
        val reader: NotificationReader.Status,
        val speakingId: String?,
        val platform: PlatformSnapshot,
    )

    // =======================================================================================
    // Merge: engine + device → UI state
    // =======================================================================================

    private fun merge(prev: MineUiState, e: UiState, d: Device): MineUiState {
        val now = System.currentTimeMillis()
        val slices = mapper.map(e, now, d.platform.is24Hour, prev.selectedPastDate.takeIf { it.isNotBlank() })

        // Optimistic overlays: drop each once the engine agrees or it has aged out.
        val items = slices.items.mapNotNull { item ->
            val o = itemStatusOverlay[item.id]
            when {
                o == null -> item
                o.first == item.status || now - o.second > OVERLAY_TTL_MS -> { itemStatusOverlay.remove(item.id); item }
                o.first == ItemStatus.trashed || o.first == ItemStatus.archived -> null
                else -> item.copy(status = o.first)
            }
        }
        removedReminderOverlay.entries.removeIf { now - it.value > OVERLAY_TTL_MS }
        val reminders = slices.reminders.filter { !removedReminderOverlay.containsKey(it.id) }

        val profile = e.profile
        val voicePhase = when (d.voice.phase) {
            VoiceSession.Phase.IDLE -> VoicePhase.idle
            VoiceSession.Phase.LISTENING -> VoicePhase.listening
            VoiceSession.Phase.THINKING -> VoicePhase.thinking
            VoiceSession.Phase.SPEAKING -> VoicePhase.speaking
            VoiceSession.Phase.PAUSED -> VoicePhase.paused
        }
        val ambientEnabled = profile.prefs["ambient.enabled"] == "1" || e.ambientEnabled
        val uiAmbient = toUiAmbient(d.ambient, ambientEnabled)

        val past = if (slices.pastDayMessages.isNotEmpty()) prev.pastDayMessages + slices.pastDayMessages else prev.pastDayMessages
        val loaded = e.initialLoadComplete || e.globalError != null

        return prev.copy(
            engine = e,
            bootError = e.globalError ?: prev.bootError,
            isHydrating = !loaded,
            items = items,
            recentlyClosedIds = slices.recentlyClosedIds,
            reminders = reminders,
            todayMessages = slices.todayMessages,
            pastDayMessages = past,
            livingNotes = slices.livingNotes,
            soulFacts = slices.soulFacts,
            agentWakeups = slices.agentWakeups,
            inboxItems = slices.inboxItems,
            statusChip = slices.statusChip,
            reliability = slices.reliability,
            chatBusy = e.isChatLoading,
            chatError = slices.chatError,
            queuedChatCount = e.queuedChatIds.size,
            providerCooldownUntil = e.providerCooldownExpiry?.takeIf { it > now },
            userName = if (prev.currentScreen == ScreenId.S04) prev.userName else profile.name,
            selectedLanguages = profile.languages,
            dayStart = minuteLabel(profile.dayStartMinute),
            dayEnd = minuteLabel(profile.dayEndMinute),
            persona = personaFromEngine(e.activePersonaId),
            personaId = e.activePersonaId,
            procrastinationMode = runCatching { ProcrastinationMode.valueOf(e.procrastinationMode) }.getOrDefault(ProcrastinationMode.gentle),
            breakMinutesLeft = profile.breakUntilMs?.let { (((it - now) + 59_999) / 60_000).toInt().coerceAtLeast(1) },
            nudgesPausedUntil = profile.nudgesPausedUntilMs?.let { "until ${mapper.timeOnly(it, d.platform.is24Hour)}" },
            reasoningEnabled = profile.pref("chat.reasoning", true),
            contextSensing5GGuard = e.contextSensingEnabled,
            callNudgeEnabled = e.postCallNudgesEnabled,
            voiceRepliesEnabled = profile.pref("voice.replies", false),
            ambientListeningPaused = !ambientEnabled || uiAmbient is AmbientState.Off ||
                (uiAmbient is AmbientState.Sleeping && uiAmbient.reason == AmbientSleepReason.user_paused),
            ambientState = uiAmbient,
            smartWebSearchEnabled = e.webSearchEnabled,
            handsFreeActive = voicePhase != VoicePhase.idle,
            handsFreeState = when (voicePhase) {
                VoicePhase.listening -> HandsFreeState.LISTENING
                VoicePhase.thinking -> HandsFreeState.PROCESSING
                VoicePhase.speaking -> HandsFreeState.SPEAKING
                else -> HandsFreeState.IDLE
            },
            soulAutoMode = e.soulAutoAcceptMode,
            reducedMotion = profile.pref("ui.reduced_motion", false),
            devMode = profile.pref("ui.dev_mode", false),
            consentAccepted = if (!e.onboardingDone) prev.consentAccepted else true,
            onboardingDone = e.onboardingDone,
            platform = d.platform.copy(
                notificationAccess = d.reader.accessGranted,
                listenerConnected = d.reader.listenerConnected,
                heard = d.reader.feed,
                heardPendingCatchUp = d.reader.pendingCatchUp,
                recentApps = d.reader.recentApps,
                voice = VoiceUi(voicePhase, d.voice.heard, d.voice.reply, d.voice.problem),
                speakingId = d.speakingId,
            ),
        )
    }

    @Suppress("UNUSED_PARAMETER")
    private fun toUiAmbient(s: EngineAmbientState, enabled: Boolean): AmbientState = when (s) {
        is EngineAmbientState.Off -> AmbientState.Off
        is EngineAmbientState.Listening -> AmbientState.Listening
        is EngineAmbientState.Processing -> AmbientState.Processing
        is EngineAmbientState.Sleeping -> AmbientState.Sleeping(
            when (s.reason) {
                EngineSleepReason.USER_PAUSED -> AmbientSleepReason.user_paused
                EngineSleepReason.OUTSIDE_WINDOW -> AmbientSleepReason.outside_window
                EngineSleepReason.BATTERY_LOW -> AmbientSleepReason.battery_low
                EngineSleepReason.THERMAL_PAUSE -> AmbientSleepReason.thermal_pause
                EngineSleepReason.CHARGING_ONLY -> AmbientSleepReason.charging_only
                EngineSleepReason.MIC_BUSY -> AmbientSleepReason.mic_busy
                EngineSleepReason.PAUSE_PLACE -> AmbientSleepReason.pause_place
                EngineSleepReason.NO_STT -> AmbientSleepReason.no_stt
                EngineSleepReason.DAILY_CAP_REACHED -> AmbientSleepReason.daily_cap_reached
            }
        )
    }

    /** Runs after each merge: toasts for action results, notification-reader preferences, hydration. */
    private fun onEngineSideEffects(e: UiState, d: Device) {
        if (!hydrated && e.initialLoadComplete) {
            hydrated = true
            onFirstLoad(e)
        }
        NotificationReader.updateFromEngine(
            e.notificationReading, e.hideNotificationContent,
            e.profile.dayStartMinute, e.profile.dayEndMinute, app,
        )
        val r = e.lastActionResult
        if (r != null && r.seq > lastResultSeq) {
            lastResultSeq = r.seq
            if (r.message.isNotBlank()) {
                showToast(r.message, r.undoToken?.let { MineAction.Undo(it) })
            }
        }
    }

    private fun onFirstLoad(e: UiState) {
        // A fresh install goes to the welcome flow; everyone else lands on Home.
        if (!e.onboardingDone && _state.value.currentScreen == ScreenId.S14) {
            _state.update { it.copy(currentScreen = ScreenId.S02, screenHistory = listOf(ScreenId.S02)) }
        }
        // Ambient listening was on last time: bring the service back while we are in the foreground.
        if ((e.profile.prefs["ambient.enabled"] == "1" || e.ambientEnabled) && !AmbientStatusBus.isRunning && hasMic()) {
            runCatching { AmbientForegroundService.start(app) }
        }
    }

    // =======================================================================================
    // Dispatch
    // =======================================================================================

    fun dispatch(action: MineAction) {
        val before = _state.value
        val undo = if (action == MineAction.RunToastUndo) before.toastUndo else null
        _state.update { mineReducer(it, action) }
        effect(action, before)
        if (undo != null) dispatch(undo)
        val after = _state.value
        if (after.toastMessage != before.toastMessage || after.toastUndo != before.toastUndo) scheduleToastDismiss(after)
    }

    private fun engine(a: UiAction) {
        val g = graph
        if (g == null) pendingEngine.add(a) else g.chatController.onAction(a)
    }

    private fun showToast(message: String, undo: MineAction? = null) {
        _state.update { it.copy(toastMessage = message, toastUndo = undo) }
        scheduleToastDismiss(_state.value)
    }

    /** Toasts with an Undo stay longer: the user needs time to reach for it. */
    private fun scheduleToastDismiss(s: MineUiState) {
        toastJob?.cancel()
        if (s.toastMessage == null) return
        val hold = if (s.toastUndo != null) 5_000L else 2_600L
        toastJob = viewModelScope.launch {
            delay(hold)
            _state.update { it.copy(toastMessage = null, toastUndo = null) }
        }
    }

    @Suppress("CyclomaticComplexMethod")
    private fun effect(action: MineAction, before: MineUiState) {
        when (action) {
            is MineAction.Navigate -> when (action.screen) {
                ScreenId.S20, ScreenId.S22, ScreenId.S29 -> engine(UiAction.LoadMemory)
                ScreenId.S14 -> engine(UiAction.OpenHome)
                ScreenId.S40 -> engine(UiAction.LoadInsights(7))
                ScreenId.S30 -> engine(UiAction.RefreshStats)
                else -> Unit
            }

            is MineAction.SelectPastDate -> {
                val date = runCatching { LocalDate.parse(action.date) }.getOrNull()
                if (date != null) {
                    val label = mapper.pastDayLabel(date)
                    _state.update { it.copy(selectedPastDate = label) }
                    val start = date.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                    engine(UiAction.LoadDay(date.toString()))
                    engine(UiAction.ShowDayReminders(date.toString(), start, start + 86_400_000L))
                }
            }

            is MineAction.CompleteItem -> {
                itemStatusOverlay[action.itemId] = ItemStatus.done to System.currentTimeMillis()
                engine(UiAction.CompleteNode(action.itemId, tag = "complete"))
            }
            is MineAction.UndoCompleteItem -> {
                val token = before.engine.recentlyClosed.firstOrNull { it.node.id == action.itemId }?.undoToken
                itemStatusOverlay[action.itemId] = ItemStatus.todo to System.currentTimeMillis()
                if (token != null) engine(UiAction.Undo(token)) else engine(UiAction.UpdateNodeStatus(action.itemId, "active"))
            }
            is MineAction.RescheduleItem -> engine(UiAction.RescheduleNode(action.itemId, naturalWhen(action.newDue), tag = "reschedule"))
            is MineAction.UndoRescheduleItem -> {
                val r = before.engine.lastActionResult
                if (r != null && r.tag == "reschedule" && r.targetId == action.itemId && r.undoToken != null) engine(UiAction.Undo(r.undoToken!!))
                else showToast("Nothing to undo for this item")
            }
            is MineAction.UpdateItemStatus -> {
                itemStatusOverlay[action.itemId] = action.status to System.currentTimeMillis()
                when (action.status) {
                    ItemStatus.done -> engine(UiAction.CompleteNode(action.itemId, tag = "complete"))
                    ItemStatus.todo -> engine(UiAction.UpdateNodeStatus(action.itemId, "active"))
                    ItemStatus.in_progress -> engine(UiAction.UpdateNodeStatus(action.itemId, "in_progress"))
                    ItemStatus.paused -> engine(UiAction.UpdateNodeStatus(action.itemId, "paused"))
                    ItemStatus.archived -> engine(UiAction.ArchiveNode(action.itemId))
                    ItemStatus.trashed -> engine(UiAction.DeleteNode(action.itemId))
                }
            }
            is MineAction.ToggleItemLocalOnly -> {
                val cur = before.items.find { it.id == action.itemId }?.localOnly ?: false
                engine(UiAction.SetNodeLocalOnly(action.itemId, !cur))
            }
            is MineAction.QuickAddOfflineItem -> if (action.rawText.isNotBlank()) engine(UiAction.QuickAdd(action.rawText, tag = "quickadd"))

            is MineAction.TogglePinNote -> {
                val n = before.livingNotes.find { it.id == action.noteId }
                if (n != null && (n.pinned || before.livingNotes.count { it.pinned } < 3)) engine(UiAction.TogglePinTopicNote(action.noteId))
            }
            is MineAction.ToggleNoteAccessMode -> {
                val n = before.livingNotes.find { it.id == action.noteId } ?: return
                val next = if (n.accessMode == NoteAccessMode.read_only) "read_write" else "read_only"
                engine(UiAction.SetNoteReferenceMode(action.noteId, next))
            }
            is MineAction.AddNoteBullet -> if (action.bullet.isNotBlank()) engine(UiAction.AddTopicNoteBullet(action.noteId, action.bullet.trim()))
            is MineAction.CreateLivingNote -> if (action.title.isNotBlank()) {
                val bullets = action.firstBullet.lines().map { it.trim() }.filter { it.isNotEmpty() }
                engine(UiAction.CreateTopicNote(action.title.trim(), bullets, pinned = before.livingNotes.count { it.pinned } < 3))
            }

            is MineAction.ResolveAmbiguity -> {
                val prompt = before.todayMessages.firstOrNull { it.id == action.messageId }?.ambiguityPrompt
                    ?: before.inboxItems.firstOrNull { it.id == action.messageId }?.let { inb -> AmbiguityPrompt("", null, inb.choices, inb.id.removePrefix("p:")) }
                val pid = prompt?.proposalId
                if (pid != null) engine(UiAction.ConfirmProposal(pid, action.optionId))
            }
            is MineAction.UndoReceipt -> {
                val token = before.todayMessages.firstOrNull { it.id == action.messageId }?.receipts?.firstOrNull { it.id == action.receiptId }?.undoToken
                if (token != null) engine(UiAction.Undo(token)) else showToast("This change can't be undone")
            }
            is MineAction.SendChatMessage -> if (action.text.isNotBlank()) {
                Speaker.stopNotifications()
                engine(UiAction.SendText(action.text.trim()))
            }

            is MineAction.AddReminder -> engine(
                UiAction.CreateReminder(
                    title = action.title,
                    whenText = naturalWhen(action.time),
                    gentle = action.tier == AlarmTierUi.GENTLE,
                    leaveHome = action.triggerType == TriggerType.leave_home,
                    fallbackWhenText = if (action.triggerType == TriggerType.leave_home) naturalWhen(action.time).ifBlank { null } else null,
                    tag = "reminder",
                )
            )
            is MineAction.SnoozeReminder -> engine(UiAction.SnoozeReminder(action.reminderId, action.minutes, tag = "snooze"))
            is MineAction.CancelReminder -> {
                removedReminderOverlay[action.reminderId] = System.currentTimeMillis()
                engine(UiAction.CancelReminderUndoable(action.reminderId, tag = "cancel-reminder"))
            }

            is MineAction.ResolveInboxItem -> resolveInbox(action.inboxId, action.accept, before)
            is MineAction.ToggleSoulLocalOnly -> {
                val cur = before.soulFacts.find { it.id == action.soulId }?.localOnly ?: false
                engine(UiAction.SetSoulFactLocalOnly(action.soulId, !cur))
                engine(UiAction.LoadMemory)
            }

            is MineAction.SetPersona -> engine(UiAction.SetPersona(personaToEngine(action.persona)))
            is MineAction.SetProcrastinationMode -> engine(UiAction.SetProcrastinationMode(action.mode.name))
            is MineAction.PauseNudges -> engine(UiAction.PauseNudges(pauseUntil(action.until), tag = "pause-nudges"))
            is MineAction.StartBreak -> engine(UiAction.StartBreak(action.minutes, tag = "break"))
            MineAction.EndBreak -> engine(UiAction.EndBreak(tag = "break"))

            is MineAction.ToggleSetting -> toggle(action.key, before)

            is MineAction.SetUserName -> {
                nameJob?.cancel()
                nameJob = viewModelScope.launch {
                    delay(450)
                    engine(UiAction.SetUserName(action.name, tag = "silent"))
                }
            }
            is MineAction.ToggleLanguage -> engine(UiAction.SetLanguages(_state.value.selectedLanguages, tag = "silent"))
            is MineAction.SetConsent -> engine(UiAction.SetPref("consent.accepted", if (action.accepted) "1" else "0"))
            is MineAction.SetOnboardingFastPath -> Unit
            is MineAction.SetDayBounds -> {
                val s = parseMinute(action.start)
                val e = parseMinute(action.end)
                if (s != null && e != null) engine(UiAction.SetDayWindow(s, e, tag = "silent"))
            }
            is MineAction.SetDayWindow -> engine(UiAction.SetDayWindow(action.startMinute, action.endMinute, tag = "silent"))
            MineAction.FinishOnboarding -> {
                engine(UiAction.SetPref("consent.accepted", "1"))
                engine(UiAction.OnboardingFinish)
                _state.update { it.copy(currentScreen = ScreenId.S14, screenHistory = listOf(ScreenId.S14), navDirection = 1) }
            }

            is MineAction.Engine -> when (val a = action.action) {
                is UiAction.SetCurrentWifiAsHome -> viewModelScope.launch(Dispatchers.IO) {
                    val ok = (app as? MineApp)?.contextSensingManager?.enrollCurrentWifiAsHome() ?: false
                    showToast(if (ok) "This Wi-Fi is now Home" else "Connect to your home Wi-Fi first, then try again")
                }
                is UiAction.BookmarkAmbientLast2Min -> {
                    if (AmbientStatusBus.isRunning) AmbientForegroundService.bookmark(app)
                    else showToast("Turn on listening first")
                    engine(a)
                }
                is UiAction.ConfirmResetDatabase -> if (a.confirm) viewModelScope.launch(Dispatchers.IO) {
                    runCatching { (app as MineApp).resetDatabase() }
                        .onFailure { showToast("Couldn't reset: ${it.message ?: "unknown error"}") }
                }
                is UiAction.PromptResetDatabase -> Unit
                else -> engine(a)
            }
            MineAction.RetryFailedMessage -> engine(UiAction.RetryLastMessage)
            MineAction.DismissChatError -> engine(UiAction.DismissConnectionError)
            MineAction.StopReply -> engine(UiAction.Stop)
            is MineAction.SpeakMessage -> VoiceSession.speakMessage(app, action.messageId, action.text)
            MineAction.StartVoice -> startVoice()
            MineAction.StopVoice -> VoiceSession.stop()
            MineAction.PauseVoice -> VoiceSession.pause()
            MineAction.ResumeVoice -> VoiceSession.resume()
            is MineAction.Undo -> {
                engine(UiAction.Undo(action.token))
                showToast(action.confirmation)
            }

            is MineAction.SetNotificationReading -> {
                engine(UiAction.SaveNotificationReading(action.settings, tag = "notif"))
                if (action.settings.enabled && !NotificationReader.hasAccess(app)) openNotificationAccess()
            }
            MineAction.OpenNotificationAccess -> openNotificationAccess()
            MineAction.ClearHeardFeed -> NotificationReader.clearFeed(app)
            MineAction.TestNotificationVoice -> NotificationReader.speakSample(app)
            is MineAction.SaveHeardSuggestion -> {
                val s = before.platform.heard.firstOrNull { it.id == action.heardId }?.suggestion
                if (s != null) engine(UiAction.SaveNotificationSuggestion(s, tag = "suggestion"))
            }

            is MineAction.RequestPermission -> _permissionRequests.tryEmit(action.permission)
            MineAction.RefreshPlatform -> viewModelScope.launch { refreshPlatform() }
            is MineAction.SetAmbientListening -> setAmbient(action.on)

            is MineAction.ExportBackup -> backupExport(action.uri, action.passphrase, action.includeKeys)
            is MineAction.ImportBackup -> backupImport(action.uri, action.passphrase, action.verifyOnly)
            is MineAction.ExportMarkdown -> backupMarkdown(action.uri)
            is MineAction.SetAutoBackupFolder -> autoBackup(action.uri, action.passphrase)

            else -> Unit
        }
    }

    private fun toggle(key: SettingKey, before: MineUiState) {
        val next = !before.setting(key)
        when (key) {
            SettingKey.reasoningEnabled -> engine(UiAction.SetPref("chat.reasoning", if (next) "1" else "0"))
            SettingKey.contextSensing5GGuard -> {
                if (next && !granted(Manifest.permission.ACCESS_FINE_LOCATION)) _permissionRequests.tryEmit(Manifest.permission.ACCESS_FINE_LOCATION)
                if (next && !granted(Manifest.permission.ACTIVITY_RECOGNITION)) _permissionRequests.tryEmit(Manifest.permission.ACTIVITY_RECOGNITION)
                engine(UiAction.SetContextSensing(next))
                (app as? MineApp)?.contextSensingManager?.checkAndApplySettings()
            }
            SettingKey.callNudgeEnabled -> {
                if (next && !granted(Manifest.permission.READ_PHONE_STATE)) _permissionRequests.tryEmit(Manifest.permission.READ_PHONE_STATE)
                engine(UiAction.SetPostCallNudges(next))
            }
            SettingKey.voiceRepliesEnabled -> engine(UiAction.SetPref("voice.replies", if (next) "1" else "0"))
            SettingKey.ambientListeningPaused -> setAmbient(on = before.ambientListeningPaused)
            SettingKey.smartWebSearchEnabled -> engine(UiAction.SetWebSearch(next))
            SettingKey.handsFreeActive -> if (next) startVoice() else VoiceSession.stop()
            SettingKey.soulAutoMode -> engine(UiAction.SetSoulAutoAcceptMode(next))
            SettingKey.reducedMotion -> engine(UiAction.SetPref("ui.reduced_motion", if (next) "1" else "0"))
            SettingKey.devMode -> engine(UiAction.SetPref("ui.dev_mode", if (next) "1" else "0"))
        }
    }

    private fun resolveInbox(id: String, accept: Boolean, before: MineUiState) {
        val raw = id.substringAfter(':')
        when {
            id.startsWith("p:") -> {
                val choice = if (accept) before.inboxItems.firstOrNull { it.id == id }?.choices?.firstOrNull()?.id ?: "cancel" else "cancel"
                engine(UiAction.ConfirmProposal(raw, choice))
            }
            id.startsWith("s:") -> engine(if (accept) UiAction.AcceptSoulFact(raw) else UiAction.RejectSoulFact(raw))
            id.startsWith("a:") -> engine(if (accept) UiAction.AcceptAmbientItem(raw) else UiAction.RejectAmbientItem(raw))
            id.startsWith("st:") -> engine(if (accept) UiAction.KeepStale(raw) else UiAction.CancelStale(raw))
        }
        showToast(if (accept) "Done" else "Dismissed")
    }

    // =======================================================================================
    // Voice, ambient, notification access
    // =======================================================================================

    private fun startVoice() {
        val g = graph ?: return showToast("Mine is still starting up")
        if (!hasMic()) {
            startVoiceAfterMic = true
            _permissionRequests.tryEmit(Manifest.permission.RECORD_AUDIO)
            return
        }
        Speaker.stopNotifications()
        VoiceSession.start(app, g.chatController)
    }

    private fun setAmbient(on: Boolean) {
        if (on) {
            if (!hasMic()) {
                startAmbientAfterMic = true
                _permissionRequests.tryEmit(Manifest.permission.RECORD_AUDIO)
                return
            }
            engine(UiAction.SetPref("ambient.enabled", "1"))
            runCatching { AmbientForegroundService.start(app) }.onFailure { showToast("Couldn't start listening: ${it.message ?: "unknown error"}") }
        } else {
            engine(UiAction.SetPref("ambient.enabled", "0"))
            AmbientForegroundService.stop(app)
            Speaker.stopNotifications()
        }
    }

    private fun openNotificationAccess() {
        runCatching { app.startActivity(NotificationReader.accessSettingsIntent(app)) }
            .onFailure {
                runCatching {
                    app.startActivity(Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
    }

    /** Called by the Activity after a permission prompt is answered. */
    fun onPermissionResult(permission: String, granted: Boolean) {
        viewModelScope.launch { refreshPlatform() }
        if (permission == Manifest.permission.RECORD_AUDIO) {
            if (granted && startVoiceAfterMic) startVoice()
            if (granted && startAmbientAfterMic) setAmbient(true)
            if (!granted && (startVoiceAfterMic || startAmbientAfterMic)) showToast("Microphone access is needed for that")
            startVoiceAfterMic = false
            startAmbientAfterMic = false
        }
    }

    /** Called from the Activity's onResume: the user may have changed permissions in Settings. */
    fun onResume() {
        viewModelScope.launch { refreshPlatform() }
        NotificationReader.refreshAccess(app)
        engine(UiAction.DrainQueuedTurns)
    }

    fun onExternalText(text: String) {
        platform.update { it.copy(composerPrefill = text, composerFocusTick = it.composerFocusTick + 1) }
        _state.update { it.copy(currentScreen = ScreenId.S15, screenHistory = (it.screenHistory + ScreenId.S15).takeLast(12), activeModal = null) }
    }

    fun onFocusComposer() {
        platform.update { it.copy(composerFocusTick = it.composerFocusTick + 1) }
        _state.update { it.copy(currentScreen = ScreenId.S15, screenHistory = (it.screenHistory + ScreenId.S15).takeLast(12), activeModal = null) }
    }

    fun consumePrefill() = platform.update { it.copy(composerPrefill = null) }

    private suspend fun platformTicker() {
        while (true) {
            delay(30_000)
            refreshPlatform()
        }
    }

    /** Last known "away from home" reading, to notice the moment the user leaves. */
    @Volatile private var wasAway: Boolean? = null

    private suspend fun refreshPlatform() = withContext(Dispatchers.IO) {
        runCatching {
            val st = (app as? MineApp)?.contextEngine?.getState()
            val away = st?.place is com.anchor.core.sensing.PlaceAxis.Away
            if (wasAway == false && away && _state.value.items.any { it.triggerType == TriggerType.leave_home && it.status != ItemStatus.done }) {
                _state.update { it.copy(walkNotificationVisible = true) }
            }
            if (st != null) wasAway = away
        }
        val paths = runCatching { VoiceModelPaths.resolve(app) }.getOrNull()
        val ctxLabel = runCatching {
            val st = (app as? MineApp)?.contextEngine?.getState()
            if (st == null || !(_state.value.contextSensing5GGuard)) null
            else {
                val place = when (val p = st.place) {
                    is com.anchor.core.sensing.PlaceAxis.Home -> "At home"
                    is com.anchor.core.sensing.PlaceAxis.Away -> "Away"
                    is com.anchor.core.sensing.PlaceAxis.SavedPlace -> "At ${p.label}"
                    else -> null
                }
                val motion = when (st.motion) {
                    com.anchor.core.sensing.MotionAxis.STILL -> "still"
                    com.anchor.core.sensing.MotionAxis.WALKING -> "walking"
                    com.anchor.core.sensing.MotionAxis.VEHICLE -> "on the move"
                    else -> null
                }
                listOfNotNull(place, motion).joinToString(" · ").ifBlank { null }
            }
        }.getOrNull()
        platform.update {
            it.copy(
                micGranted = hasMic(),
                notificationsGranted = NotificationManagerCompat.from(app).areNotificationsEnabled(),
                offlineSttInstalled = paths?.sttComplete == true,
                offlineTtsInstalled = paths?.ttsComplete == true,
                systemSttAvailable = android.speech.SpeechRecognizer.isRecognitionAvailable(app),
                is24Hour = android.text.format.DateFormat.is24HourFormat(app),
                lowPower = (app.getSystemService(android.content.Context.POWER_SERVICE) as? android.os.PowerManager)?.isPowerSaveMode == true,
                contextLabel = ctxLabel,
            )
        }
    }

    private fun hasMic() = granted(Manifest.permission.RECORD_AUDIO)
    private fun granted(p: String) = app.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    // =======================================================================================
    // Backup
    // =======================================================================================

    private suspend fun backupController(): ExportController? {
        exportController?.let { return it }
        val mineApp = app as? MineApp ?: return null
        val mgr = runCatching { withTimeout(10_000) { mineApp.sharedExportManager.await() } }.getOrNull() ?: return null
        return ExportController(mgr, viewModelScope).also { exportController = it }
    }

    private fun setBackup(busy: Boolean, message: String?, isError: Boolean = false) =
        platform.update { it.copy(backup = it.backup.copy(busy = busy, message = message, isError = isError)) }

    private fun backupExport(uri: Uri, passphrase: String, includeKeys: Boolean) = viewModelScope.launch(Dispatchers.IO) {
        if (passphrase.length < MIN_PASSPHRASE_LENGTH) return@launch setBackup(false, "Use at least $MIN_PASSPHRASE_LENGTH characters.", true)
        val c = backupController() ?: return@launch setBackup(false, "Backup isn't ready yet. Try again in a moment.", true)
        setBackup(true, "Encrypting your backup…")
        val tmp = File.createTempFile("mine_export", ".mnex", app.cacheDir)
        try {
            if (c.runExport(tmp, passphrase, includeKeys)) {
                app.contentResolver.openOutputStream(uri, "wt")?.use { out -> tmp.inputStream().use { it.copyTo(out) } }
                    ?: error("Couldn't open the file you picked")
                platform.update { it.copy(backup = BackupUi(false, c.state.value.result ?: "Backup saved.", false, System.currentTimeMillis())) }
            } else {
                setBackup(false, c.state.value.result ?: "Backup failed.", true)
            }
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            setBackup(false, "The backup was made but couldn't be saved there: ${e.message ?: e.javaClass.simpleName}", true)
        } finally {
            tmp.delete()
        }
    }

    private fun backupImport(uri: Uri, passphrase: String, verifyOnly: Boolean) = viewModelScope.launch(Dispatchers.IO) {
        val c = backupController() ?: return@launch setBackup(false, "Backup isn't ready yet. Try again in a moment.", true)
        setBackup(true, if (verifyOnly) "Checking the backup…" else "Restoring…")
        val tmp = File.createTempFile("mine_import", ".mnex", app.cacheDir)
        try {
            app.contentResolver.openInputStream(uri)?.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                ?: error("Couldn't read the file you picked")
            val ok = if (verifyOnly) c.runVerify(tmp, passphrase) else c.runImport(tmp, passphrase)
            val msg = c.state.value.result ?: if (ok) "Done." else "That didn't work."
            setBackup(false, if (!ok && msg.contains("tag", true)) "Wrong passphrase, or the file is damaged." else msg, !ok)
            if (ok && !verifyOnly) engine(UiAction.ReloadStorage)
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            setBackup(false, "Couldn't read that backup: ${e.message ?: e.javaClass.simpleName}", true)
        } finally {
            tmp.delete()
        }
    }

    private fun backupMarkdown(uri: Uri) = viewModelScope.launch(Dispatchers.IO) {
        val c = backupController() ?: return@launch setBackup(false, "Export isn't ready yet. Try again in a moment.", true)
        val storage = graph?.storage ?: return@launch setBackup(false, "Mine is still starting up.", true)
        setBackup(true, "Writing a readable copy…")
        val tmp = File.createTempFile("mine_export", ".md", app.cacheDir)
        try {
            if (c.runExportMarkdown(tmp, storage)) {
                app.contentResolver.openOutputStream(uri, "wt")?.use { out -> tmp.inputStream().use { it.copyTo(out) } }
                setBackup(false, "Readable copy saved.")
            } else setBackup(false, c.state.value.result ?: "Export failed.", true)
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            setBackup(false, "Couldn't save it there: ${e.message ?: e.javaClass.simpleName}", true)
        } finally {
            tmp.delete()
        }
    }

    private fun autoBackup(uri: Uri, passphrase: String) = viewModelScope.launch(Dispatchers.IO) {
        if (passphrase.length < MIN_PASSPHRASE_LENGTH) return@launch setBackup(false, "Use at least $MIN_PASSPHRASE_LENGTH characters.", true)
        runCatching {
            app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        com.anchor.mine.export.BackupPassphraseVault.savePassphrase(app, passphrase)
        engine(UiAction.SetAutoBackupUri(uri.toString()))
        engine(UiAction.SetAutoBackup(true))
        setBackup(false, "Weekly backups will be saved to that folder.")
    }

    // =======================================================================================
    // Helpers
    // =======================================================================================

    companion object {
        private const val OVERLAY_TTL_MS = 5_000L
    }
}

/** What screens receive: a live State (never the raw value) plus dispatch. */
@Stable
class MineHandle(val state: State<MineUiState>, val dispatch: (MineAction) -> Unit)

val LocalMine = staticCompositionLocalOf<MineHandle> { error("MineHandle not provided") }

/**
 * Fine-grained subscription: the caller only recomposes when its selected slice
 * changes (structural equality on data classes), not on every store update.
 */
@Composable
fun <T> mineSelect(selector: (MineUiState) -> T): T {
    val handle = LocalMine.current
    val latest by rememberUpdatedState(selector)
    val derived = remember(handle) { derivedStateOf { latest(handle.state.value) } }
    return derived.value
}

@Composable
fun mineDispatch(): (MineAction) -> Unit = LocalMine.current.dispatch
