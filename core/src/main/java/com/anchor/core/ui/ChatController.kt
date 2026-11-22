// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.ui

import com.anchor.core.alarms.AlarmReconciler
import com.anchor.core.alarms.AlarmScheduler
import com.anchor.core.alarms.ReliabilityIssue
import com.anchor.core.alarms.ScheduleResult
import com.anchor.core.capture.QuickCapture
import com.anchor.core.context.ContextBuilder
import com.anchor.core.gateway.EnvelopeParser
import com.anchor.core.gateway.Gateway
import com.anchor.core.models.*
import com.anchor.core.nudge.NudgeEngine
import com.anchor.core.provider.AllProvidersFailedException
import com.anchor.core.provider.Http429Exception
import com.anchor.core.provider.HttpStatusException
import com.anchor.core.provider.OfflineException
import com.anchor.core.provider.ProviderConfigParser
import com.anchor.core.provider.ProviderOrchestrator
import com.anchor.core.provider.ProviderTimeoutException
import com.anchor.core.provider.RateLimiter
import com.anchor.core.provider.StreamEvent
import com.anchor.core.provider.Target
import com.anchor.core.storage.Storage
import com.anchor.core.time.Clock
import com.anchor.core.time.DateResolver
import com.anchor.core.time.DayRollover
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import com.anchor.core.provider.KeyPool
import java.util.UUID

class ChatController(
    private val storage: Storage,
    private val orchestrator: ProviderOrchestrator,
    private val contextBuilder: ContextBuilder,
    private val gateway: Gateway,
    private val alarmScheduler: AlarmScheduler,
    private val coroutineScope: CoroutineScope,
    private val defaultDispatcher: CoroutineDispatcher,
    private val clock: Clock,
    private val rateLimiter: RateLimiter? = null,
    private val reconciler: AlarmReconciler = AlarmReconciler(storage, alarmScheduler, clock),
    private val dateResolver: DateResolver = DateResolver(clock, is24HourFormat = { false }),
    private val quoteManager: com.anchor.core.quotes.QuoteManager = com.anchor.core.quotes.QuoteManager(storage, orchestrator, clock, coroutineScope = coroutineScope, limiter = rateLimiter),
    // Blueprint 31: the proactive pipeline's gatekeeper. This is the production construction the
    // audit found missing — the engine existed and was tested, but nothing ever built or called it.
    private val nudgeEngine: NudgeEngine = NudgeEngine(clock),
    private val learningEngine: com.anchor.core.learning.LearningEngine = com.anchor.core.learning.LearningEngine(storage, clock),
    // Blueprint 41 §3.1: "network validated and the provider answers". Null means no oracle was
    // installed (core unit tests), and every send is attempted — a wrong "online" costs one failed
    // request, while a wrong "offline" would queue a message that could have been answered now.
    private val networkCheck: (() -> Boolean)? = null,
    private val embedder: com.anchor.core.retrieval.Embedder? = null,
    private val contextEngine: com.anchor.core.sensing.ContextEngine? = null,
    private val checkinBuilder: com.anchor.core.nudge.CheckinBuilder = com.anchor.core.nudge.CheckinBuilder(storage, clock, dateResolver)
) {
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** Replies waiting for their turn to start: user message id → the message it quotes. */
    private val pendingReplyContext = java.util.concurrent.ConcurrentHashMap<String, ReplyRef>()

    /** Looks up the quoted message (any day) for its preview, role and day. Null when it is gone. */
    private suspend fun replyRefFor(messageId: String): ReplyRef? = try {
        val text = storage.getRawMessage(messageId)
        val known = (_state.value.chatMessages + _state.value.selectedDayMessages).firstOrNull { it.id == messageId }
        if (text == null && known == null) null
        else ReplyRef(
            id = messageId,
            preview = (text ?: known?.content).orEmpty().replace("\n", " ").take(MessageAttrs.PREVIEW_CHARS),
            role = known?.role ?: "assistant",
            dayKey = known?.dayKey,
        )
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        null
    }

    /** The main provider as targets: one per key, ordered by [KeyPool] (least-loaded first). */
    private suspend fun providerTargets(rpm: Int, limiterPrefix: String, model: String? = null): List<Target> {
        val st = _state.value
        val keys = listOf(st.providerKey) + st.providerExtraKeys
        val m = model ?: st.providerModel
        val limiter = rateLimiter
        val pooled = if (limiter == null) {
            KeyPool.sanitize(keys).map { Target(st.providerEndpoint, it, rpm, model = m, limiterKey = limiterPrefix + it) }
        } else {
            KeyPool.targets(st.providerEndpoint, keys, m, rpm, limiter, limiterPrefix)
        }
        // No usable key (not loaded yet, or none saved): keep the single-target chain exactly as
        // before the key pool, so the provider answers with its own clear auth error.
        return pooled.ifEmpty {
            listOf(Target(st.providerEndpoint, st.providerKey, rpm, model = m, limiterKey = limiterPrefix + st.providerKey))
        }
    }
    private var currentStreamJob: Job? = null
    @Volatile private var currentBotId: String? = null
    private val json = Json { ignoreUnknownKeys = true }
    private val receiptFormatter = ReceiptFormatter(dateResolver)
    private val transientReceipts = mutableListOf<ReceiptState>()
    private val templateEngine = com.anchor.core.persona.TemplateEngine()
    private val personaEngine = com.anchor.core.persona.PersonaEngine()
    private val ruleBasedQuickAdd = com.anchor.core.capture.RuleBasedQuickAdd(dateResolver)
    private val companionActions = CompanionActions(storage, gateway, alarmScheduler, clock, ruleBasedQuickAdd, dateResolver)
    private val actionSeq = java.util.concurrent.atomic.AtomicLong(0)
    private val sendMutex = kotlinx.coroutines.sync.Mutex()
    private val isDraining = java.util.concurrent.atomic.AtomicBoolean(false)
    private val workerScope = CoroutineScope(defaultDispatcher + SupervisorJob())
    private var workerAiJob: Job? = null

    /**
     * D-206: Background Chat Reply Notification callback.
     * Invoked when an assistant turn completes while the app is in the background.
     */
    var onBackgroundReplyCompleted: ((replySnippet: String) -> Unit)? = null

    /**
     * Invoked when context sensing is enabled or disabled.
     */
    var onContextSensingChanged: ((Boolean) -> Unit)? = null

    /**
     * P2-11 (Patch T): Invoked when post-call nudges are enabled or disabled.
     */
    var onPostCallNudgesChanged: ((Boolean) -> Unit)? = null

    fun addTransientReceipt(receipt: ReceiptState) {
        val dayMs = 24L * 3600 * 1000L
        synchronized(transientReceipts) {
            transientReceipts.removeAll { clock.now() - it.timestamp >= dayMs }
            transientReceipts.add(receipt)
            if (transientReceipts.size > 20) {
                transientReceipts.subList(0, transientReceipts.size - 20).clear()
            }
        }
    }

    private fun currentTransientReceipts(): List<ReceiptState> {        val dayMs = 24L * 3600 * 1000L
        synchronized(transientReceipts) {
            transientReceipts.removeAll { clock.now() - it.timestamp >= dayMs }
            return transientReceipts.toList()
        }
    }

    /**
     * Blueprint 52 §3.1: progress is saved after every step. A failed write reports itself and
     * leaves the step where it was, so the user sees the same screen again rather than a flow that
     * silently forgot where it was.
     */
    private suspend fun persistOnboardingStep(step: Int) {
        try {
            storage.saveSetting(ONBOARDING_STEP_KEY, step.toString())
            _state.update { it.copy(onboardingStep = step, onboardingError = null) }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            _state.update { it.copy(onboardingError = "Could not save your progress: ${e.message ?: e.javaClass.simpleName}") }
        }
    }

    private suspend fun loadReceipts(): List<ReceiptState> {
        val events = storage.getRecentEvents(30)
        val now = clock.now()
        val dayMs = 24L * 3600 * 1000L
        val undoneOpIds = storage.getUndoneOpIds(now - dayMs)
        return events.map { event ->
            val undoValid = (now - event.tsUtc < dayMs) && !undoneOpIds.contains(event.opId)
            val undoToken = if (undoValid) event.opId else null
            val message = receiptFormatter.format(event)
            ReceiptState(
                id = event.opId,
                message = message,
                undoToken = undoToken,
                isError = false,
                causeMsgId = event.causeMsgId,
                timestamp = event.tsUtc
            )
        }
    }

    init {
        contextBuilder.aliasRegistry = gateway.aliasRegistry
        coroutineScope.launch(defaultDispatcher) {
            // H-07 (R-08): everything below is the only thing that ever paints the first frame.
            // Uncaught, a storage failure killed this coroutine and left the default UiState on
            // screen forever — an empty chat with no banner, indistinguishable from a slow load,
            // and nothing retried. Report it through globalError, which MainActivity renders as a
            // full-screen message, and rethrow CancellationException untouched.
            try {
                loadInitialState()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                _state.update {
                    it.copy(
                        globalError = "Could not load your data: ${e.message ?: e.javaClass.simpleName}",
                        isChatLoading = false
                    )
                }
            }
            // Blueprint 41 §3.4: "an immediate attempt … whenever the user opens the app". This is
            // that attempt for a queue left behind by an offline session. It runs after the first
            // frame is painted, so a slow or failing drain never delays the user's data appearing.
            drainQueuedTurns()
        }
        coroutineScope.launch(defaultDispatcher) {
            alarmScheduler.lastFiredId.collect { firedId ->
                if (firedId != null) {
                    refreshUI()
                }
            }
        }
        coroutineScope.launch(defaultDispatcher) {
            quoteManager.currentQuote.collect { quote ->
                if (quote != null) {
                    _state.update { it.copy(homeQuote = quote) }
                }
            }
        }
        coroutineScope.launch(defaultDispatcher) {
            val q = quoteManager.onHomeOpened()
            _state.update { it.copy(homeQuote = q) }
        }
    }

    private suspend fun loadInitialState() {
        reconcileAlarms(force = true)

        val proposals = storage.getOpenProposals()
        val soulFacts = storage.getProposedSoulFacts()
        val stale = storage.getStaleNodes(clock.now())
        val relState = storage.getReliabilityState()
        // B3: derive a human-readable banner from the structured issues,
        // never surface the raw issuesJson string to the user.
        val schedError = if (relState.score == "at_risk") {
            val issues = AlarmReconciler.parseIssues(relState.issuesJson)
            if (issues.isEmpty()) "Alarms or notifications at risk"
            else issues.joinToString("; ") { it.error }
        } else null
        val recentReceipts = loadReceipts()
        val priorityList = storage.getHomePriorityList(clock.now())
        val closedNodes = storage.getRecentlyClosed(clock.now())
        val dayMs = 24L * 3600 * 1000L
        val nowMs = clock.now()
        val recentlyClosed = closedNodes.map { node ->
            val event = storage.getLatestEventForTarget("node", node.id)
            val undoValid = event != null && (nowMs - event.tsUtc < dayMs) && !storage.hasUndo(event.opId)
            RecentlyClosed(node, undoValid, if (undoValid) event.opId else null)
    }
    val proactive = storage.getProactiveState()
    val activeThread = proactive.openThreadMsgId?.let { ActiveThread(it, proactive.threadOpenedAt ?: 0L) }
    val initialKey = storage.getSetting("key") ?: ""
    val extraKeys = KeyPool.decode(storage.getSetting(KEY_EXTRA_KEYS)).filter { it != initialKey }
    // With a key pool, the provider is only "cooling down" when every key is.
    val initialCooldownExpiry = if (initialKey.isBlank()) null else {
        val expiries = (listOf(initialKey) + extraKeys).map { rateLimiter?.getCooldownExpiry(it) }
        if (expiries.any { it == null }) null else expiries.filterNotNull().minOrNull()
    }

    _state.update { it.copy(
        receipts = recentReceipts + currentTransientReceipts(),
        activeReminders = storage.getActiveReminders(clock.now()),
        chatMessages = if (it.isChatLoading) it.chatMessages else storage.getRecentMessages(30),
        providerEndpoint = storage.getSetting("ep") ?: "https://api.openai.com/v1/chat/completions",
        providerKey = initialKey,
        providerExtraKeys = extraKeys,
        providerRpm = storage.getSetting("provider.rpm_default")?.toIntOrNull() ?: 20,
        providerModel = storage.getSetting("mod") ?: "gpt-4o-mini",
        debugStats = storage.getDebugStats().entries.joinToString("\n") { e -> "${e.key}: ${e.value}" },
        openProposals = proposals,
        proposedSoulFacts = soulFacts,
        staleItems = stale,
        inboxCount = proposals.size + soulFacts.size + stale.size,
        urgentItems = storage.getUrgentNodes(),
        schedulingError = schedError ?: it.schedulingError,
        atRisk = relState.score == "at_risk",
        activeThread = activeThread,
        homePriorityList = priorityList,
        recentlyClosed = recentlyClosed,
        // Blueprint 52 §3.1: a fresh install shows the flow, a finished one never does. Read here
        // rather than in the init block so the first painted frame already knows.
        onboardingDone = storage.getSetting(ONBOARDING_DONE_KEY) == "1",
        queuedChatIds = queuedChatIds(),
        activePersonaId = com.anchor.core.persona.PersonaPresets.findById(
            storage.getSetting("persona.active") ?: "companion",
            (storage.getSetting("persona.active") ?: "").let { if (it.startsWith("custom_")) storage.getSetting("persona.custom.$it") else null }
        ).id,
        activePersonaName = com.anchor.core.persona.PersonaPresets.findById(
            storage.getSetting("persona.active") ?: "companion",
            (storage.getSetting("persona.active") ?: "").let { if (it.startsWith("custom_")) storage.getSetting("persona.custom.$it") else null }
        ).name,
        // Blueprint 16 §3.2 & Blueprint 52 §3.4: App lock and privacy UI gating
        appLockEnabled = storage.getSetting("privacy.app_lock") == "1",
        isAppLocked = (storage.getSetting("privacy.app_lock") == "1") && it.isAppLocked.let { locked -> if (it.appLockEnabled) locked else true },
        relockTimeoutSec = storage.getSetting("privacy.relock_timeout_sec")?.toIntOrNull() ?: 60,
        blockScreenshots = storage.getSetting("privacy.block_screenshots") == "1",
        compactMode = storage.getSetting("ai.compact_mode") == "1",
        ambientEnabled = storage.getSetting("ambient.enabled") == "1",
        voiceprintEnrolled = storage.getVoiceprint() != null,
        latestAmbientReport = storage.getLatestAmbientReport(),
        ambientReportItems = storage.getLatestAmbientReport()?.let { r -> storage.getAmbientReportItems(r.id) } ?: emptyList(),
        procrastinationMode = storage.getSetting("procrastination.mode") ?: "gentle",
        contextSensingEnabled = (storage.getSetting("flag.sensing") == "1") && (storage.getSetting("sensing.enabled") == "1"),
        postCallNudgesEnabled = (storage.getSetting("flag.call_nudge") == "1" || storage.getSetting("flag.call_nudges") == "1") && storage.getSetting("callnudge.enabled") != "0",
        onDeviceFallbackEnabled = storage.getSetting("provider.ondevice.enabled") == "1" || storage.getSetting("flag.ondevice_llm") == "1",
        moodTrackingEnabled = storage.getSetting("flag.mood_tracking") == "1",
        hideNotificationContent = storage.getSetting("privacy.hide_notification_content") == "1" || storage.getSetting("flag.hide_notification_content") == "1",
        soulAutoAcceptMode = storage.getSetting("soul.mode") == "auto" || storage.getSetting("soul.auto_accept") == "1",
        fallbackEndpoint = storage.getSetting("provider.fallback.endpoint") ?: storage.getSetting("ep.fallback") ?: storage.getSetting("ep_fallback") ?: "",
        fallbackKey = storage.getSetting("provider.fallback.key") ?: storage.getSetting("key.fallback") ?: storage.getSetting("key_fallback") ?: "",
        fallbackModel = storage.getSetting("provider.fallback.model") ?: storage.getSetting("model.fallback") ?: storage.getSetting("model_fallback") ?: "",
        providerCooldownExpiry = initialCooldownExpiry,
        autoBackupEnabled = storage.getSetting("export.auto.enabled")?.let { it == "1" || it.toBooleanStrictOrNull() == true } ?: (storage.getSetting("backup.auto_enabled") == "1"),
        autoBackupUri = storage.getSetting("export.auto.folder_uri") ?: storage.getSetting("backup.auto_uri") ?: "",
        webSearchEnabled = (storage.getSetting("web_search.enabled")?.let { it == "1" } ?: (it.webSearchEnabled || gateway.webSearchEnabled)).also { gateway.webSearchEnabled = it }
    )}
    _state.update { it.copy(chatItems = ChatTimelineBuilder.build(it.chatMessages, it.receipts, it.openProposals)) }
    loadCompanionExtras()
    _state.update { it.copy(initialLoadComplete = true) }

        val onboardingDone = storage.getSetting(ONBOARDING_DONE_KEY) == "1" || storage.getSetting("onboarding.done") == "true"
        val lastNotified = storage.getSetting(WhatsNewCatalog.SETTING_VERSION_NOTIFIED)
        if (onboardingDone && WhatsNewCatalog.shouldShowWhatsNew(lastNotified)) {
            val whatsNewText = WhatsNewCatalog.buildWhatsNewCard()
            storage.insertRawMessage("sys_${UUID.randomUUID()}", "assistant", whatsNewText)
            storage.saveSetting(WhatsNewCatalog.SETTING_VERSION_NOTIFIED, WhatsNewCatalog.CURRENT_VERSION)
            refreshUI()
        }
    }

    fun onAction(action: UiAction) {
        when (action) {
            is UiAction.OpenHome -> coroutineScope.launch(defaultDispatcher) {
                val q = quoteManager.onHomeOpened()
                _state.update { it.copy(homeQuote = q) }
            }
            is UiAction.UpdateDraft -> _state.update { it.copy(composerDraft = action.text) }
            is UiAction.SendMessage -> {
                val text = _state.value.composerDraft.trim()
                if (text.isBlank()) return
                _state.update { it.copy(composerDraft = "") }
                sendText(text)
            }
            is UiAction.SendText -> {
                val text = action.text.trim()
                val replyTo = action.replyToId
                if (text.isEmpty()) return
                if (replyTo == null) sendText(text)
                else coroutineScope.launch(defaultDispatcher) {
                    // A reply is stored first, with the quote in attrs, then runs the normal turn
                    // (the same path as Retry, which also sends an already-stored message).
                    val id = "u_${UUID.randomUUID()}"
                    val ref = replyRefFor(replyTo)
                    try {
                        storage.insertRawMessageWithAttrs(id, "user", text, null, ref?.let { MessageAttrs.reply(it) })
                        if (ref != null) pendingReplyContext[id] = ref
                        refreshUI()
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        sendText(text)
                        return@launch
                    }
                    sendText(text, id)
                }
            }
            is UiAction.ExternalMessagesChanged -> coroutineScope.launch(defaultDispatcher) {
                try { refreshUI() } catch (e: Exception) { if (e is CancellationException) throw e }
            }
            is UiAction.RetryLastMessage -> coroutineScope.launch(defaultDispatcher) {
                val id = _state.value.lastFailedMsgId ?: return@launch
                val text = try { storage.getRawMessage(id) } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    null
                }
                _state.update { it.copy(lastFailedMsgId = null, connectionError = null, connectionErrorRetryable = false, connectionErrorNeedsSettings = false) }
                if (!text.isNullOrBlank()) sendText(text, id)
            }
            is UiAction.DismissConnectionError -> _state.update {
                it.copy(connectionError = null, connectionErrorRetryable = false, connectionErrorNeedsSettings = false)
            }
            is UiAction.TestProvider -> coroutineScope.launch(defaultDispatcher) {
                val ep = action.endpoint.trim()
                val key = action.key.trim()
                val model = action.model.trim()
                val draft = if (ep.isBlank() || (key.isBlank() && !ep.startsWith("local://"))) {
                    ActionResultDraft("Add the address and API key first.", isError = true)
                } else try {
                    val startedNs = System.nanoTime()
                    val payload = kotlinx.serialization.json.Json.encodeToString(
                        com.anchor.core.provider.ChatRequest(
                            model,
                            true,
                            listOf(com.anchor.core.provider.RequestMessage("user", "Reply with the single word: ok")),
                            temperature = 0.0,
                            max_tokens = 8
                        )
                    )
                    var answered = false
                    kotlinx.coroutines.withTimeout(PROVIDER_TEST_TIMEOUT_MS) {
                        orchestrator.chat(listOf(Target(ep, key, 60, model = model.ifBlank { null })), payload, envelopeMode = false).collect { ev ->
                            if (ev is StreamEvent.TextDelta && ev.text.isNotBlank()) answered = true
                        }
                    }
                    val ms = (System.nanoTime() - startedNs) / 1_000_000
                    if (answered) ActionResultDraft("Connected · ${ms} ms") else ActionResultDraft("The provider answered, but with an empty reply. Check the model name.", isError = true)
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.TimeoutCancellationException) {
                        ActionResultDraft("No answer within ${PROVIDER_TEST_TIMEOUT_MS / 1000} seconds. Check the address.", isError = true)
                    } else {
                        if (e is CancellationException) throw e
                        ActionResultDraft(ChatResilience.describe(e).message, isError = true)
                    }
                }
                publishActionResult(draft, action.tag)
            }
            is UiAction.Direct -> coroutineScope.launch(defaultDispatcher) {
                val draft = companionActions.handle(action)
                publishActionResult(draft, action.tag)
                try {
                    refreshUI()
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                }
            }
            is UiAction.Stop -> {
                // B10: Cancel job, but do NOT set isChatLoading = false here.
                // The finally block of handleSendMessage will do it, preventing job overlap.
                currentStreamJob?.cancel()
            }
            is UiAction.SaveProvider -> coroutineScope.launch(defaultDispatcher) {
                // ISSUE 1: Keep the endpoint intact for users pointing at specific proxies
                val ep = action.endpoint.trim()
                val key = action.key.trim()
                val mod = action.model.trim()
                // One unit: a half-written config (new endpoint + old key) fails every request.
                // On failure the UI must keep showing the config that is actually persisted.
                try {
                    val settings = mutableMapOf("ep" to ep, "key" to key, "mod" to mod)
                    // Extra keys for the same provider (KeyPool). The primary key stays in "key" so
                    // everything that reads one key keeps working.
                    val extra = action.extraKeys?.let { k -> KeyPool.sanitize(k).filter { it != key }.take(KeyPool.MAX_KEYS - 1) }
                    if (extra != null) settings[KEY_EXTRA_KEYS] = KeyPool.encode(extra)
                    action.rpm?.let { settings["provider.rpm_default"] = it.coerceIn(1, RateLimiter.HARD_RPM_CAP).toString() }
                    storage.saveSettings(settings)
                    _state.update {
                        it.copy(
                            providerEndpoint = ep, providerKey = key, providerModel = mod,
                            providerExtraKeys = extra ?: it.providerExtraKeys,
                            providerRpm = action.rpm?.coerceIn(1, RateLimiter.HARD_RPM_CAP) ?: it.providerRpm,
                        )
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    addTransientReceipt(ReceiptState(UUID.randomUUID().toString(), "Could not save provider settings: ${e.message ?: e.javaClass.simpleName}", null, isError = true, timestamp = clock.now()))
                    refreshUI()
                }
            }
            is UiAction.ImportProviderConfig -> coroutineScope.launch(defaultDispatcher) {
                val parsed = ProviderConfigParser.parse(action.payload).getOrElse { e ->
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    addTransientReceipt(
                        ReceiptState(
                            UUID.randomUUID().toString(),
                            "Could not import provider setup: ${e.message ?: e.javaClass.simpleName}",
                            null,
                            isError = true,
                            timestamp = clock.now()
                        )
                    )
                    refreshUI()
                    return@launch
                }
                _state.update { it.copy(pendingProviderImport = parsed) }
            }
            is UiAction.RejectProviderImport -> {
                _state.update { it.copy(pendingProviderImport = null) }
            }
            is UiAction.ConfirmProviderImport -> coroutineScope.launch(defaultDispatcher) {
                val p = _state.value.pendingProviderImport ?: return@launch
                val oldHost = runCatching { java.net.URI(_state.value.providerEndpoint).host }.getOrNull()
                val newHost = runCatching { java.net.URI(p.endpoint).host }.getOrNull()
                val sameHost = oldHost != null && oldHost.equals(newHost, ignoreCase = true)
                val settings = mutableMapOf("ep" to p.endpoint, "mod" to p.model, "provider.endpoint" to p.endpoint, "provider.model" to p.model)
                when {
                    p.key != null -> {
                        settings["key"] = p.key
                        settings["provider.key"] = p.key
                    }
                    !sameHost -> {
                        settings["key"] = "" // Never send the old key to a different host
                        settings["provider.key"] = ""
                    }
                }
                if (p.fallbackEndpoint != null) {
                    settings["provider.fallback.endpoint"] = p.fallbackEndpoint
                    settings["ep.fallback"] = p.fallbackEndpoint
                }
                if (p.fallbackModel != null) {
                    settings["provider.fallback.model"] = p.fallbackModel
                    settings["model.fallback"] = p.fallbackModel
                }
                if (p.fallbackKey != null) {
                    settings["provider.fallback.key"] = p.fallbackKey
                    settings["key.fallback"] = p.fallbackKey
                }
                storage.saveSettings(settings)
                _state.update {
                    it.copy(
                        pendingProviderImport = null,
                        providerEndpoint = p.endpoint,
                        providerModel = p.model,
                        providerKey = settings["key"] ?: it.providerKey,
                        fallbackEndpoint = p.fallbackEndpoint ?: it.fallbackEndpoint,
                        fallbackModel = p.fallbackModel ?: it.fallbackModel,
                        fallbackKey = p.fallbackKey ?: it.fallbackKey
                    )
                }
                addTransientReceipt(
                    ReceiptState(
                        UUID.randomUUID().toString(),
                        "Imported provider setup (${p.model})" + if (p.key == null && sameHost) " [key unchanged]" else if (!sameHost && p.key == null) " [key cleared]" else "",
                        null,
                        isError = false,
                        timestamp = clock.now()
                    )
                )
                refreshUI()
            }
            is UiAction.CancelReminder -> coroutineScope.launch(defaultDispatcher) {
                val rem = storage.getPendingReminders().find { it.id == action.id }
                // H-08 (R-12): cancel the OS alarm BEFORE deleting the row. Row-first left an
                // armed alarm with no row when cancel() threw, and nothing could ever find it
                // again: reconcile only cancels registrations whose row is gone if the
                // registration itself is still recorded, and a failed cancel must not look like
                // a success. Cancelling first is safe to repeat — both calls are idempotent for
                // an unknown id, and a stale chip must not leave a live alarm behind.
                //
                // A throwing cancel() aborts here on purpose: the row and its registration stay
                // in place so the alarm is still reachable, and the next reconcile retries the
                // orphan. The error is surfaced rather than swallowed, because the user's chip
                // is still on screen and they need to know why.
                try {
                    alarmScheduler.cancel(action.id)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    _state.update {
                        it.copy(schedulingError = "Could not cancel alarm: ${e.message ?: e.javaClass.simpleName}")
                    }
                    return@launch
                }
                storage.deleteRegisteredAlarm(action.id)
                if (rem != null) {
                    val uId = "u_${UUID.randomUUID()}"
                    val event = RawEvent(uId, "DELETE", "reminder", action.id, beforeJson = json.encodeToString(rem), afterJson = null, tsUtc = clock.now())
                    storage.deleteReminderAndCommit(action.id, event)
                    refreshUI()
                }
            }
            is UiAction.RunSelfTest -> coroutineScope.launch(defaultDispatcher) {
                // checkHealth() is a pre-check, not the verdict: it can only see notification
                // settings, and a false failure would deny the user the one test that proves
                // alarms work. Attempt the arm and report what actually happened; a real
                // notification problem is surfaced as a separate warning below.
                val health = alarmScheduler.checkHealth()
                val healthWarning = (health as? ScheduleResult.Failed)?.reason
                val id = "test_${clock.now()}"
                val fireAt = clock.now() + 5000L
                val reminder = Reminder(id, "Self Test Fired!", fireAt)
                // Schedule BEFORE persisting: a failed arm must not leave a reminder row that
                // claims to be scheduled while no OS alarm exists (phantom row).
                val schedResult = alarmScheduler.schedule(reminder)
                if (schedResult is com.anchor.core.alarms.ScheduleResult.Armed) {
                    storage.saveReminder(reminder)
                    storage.saveRegisteredAlarm(id, id.hashCode(), fireAt)
                    val status = if (healthWarning != null) "Armed for 5 seconds... (warning: $healthWarning)" else "Armed for 5 seconds..."
                    _state.update {
                        it.copy(
                            selfTestStatus = status,
                            schedulingError = healthWarning ?: it.schedulingError,
                            schedulingFailedCount = if (healthWarning != null) it.schedulingFailedCount + 1 else it.schedulingFailedCount,
                            activeReminders = storage.getActiveReminders(clock.now())
                        )
                    }
                } else if (schedResult is com.anchor.core.alarms.ScheduleResult.Failed) {
                    alarmScheduler.cancel(id)
                    _state.update { it.copy(selfTestStatus = "Failed: ${schedResult.reason}", schedulingError = schedResult.reason, schedulingFailedCount = it.schedulingFailedCount + 1) }
                }
            }
            is UiAction.AcceptSoulFact -> coroutineScope.launch(defaultDispatcher) {
                try {
                    val fact = storage.getProposedSoulFacts().find { it.id == action.factId }
                    val uId = "u_${UUID.randomUUID()}"
                    val beforeJson = fact?.let { json.encodeToString(it) }
                    val updatedFact = fact?.copy(status = "active")
                    val afterJson = updatedFact?.let { json.encodeToString(it) }
                    val event = RawEvent(uId, "ACCEPT", "soul_fact", action.factId, beforeJson = beforeJson, afterJson = afterJson, tsUtc = clock.now())
                    storage.updateSoulFactStatusAndCommit(action.factId, "active", event)
                    if (embedder != null && updatedFact != null && !updatedFact.localOnly) {
                        val vec = embedder.embed(listOf(updatedFact.statement), com.anchor.core.retrieval.Embedder.Kind.DOCUMENT).firstOrNull()
                        if (vec != null) {
                            storage.saveEmbedding("soul_fact", updatedFact.id, embedder.version, vec)
                        }
                    }
                    refreshUI()
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    addTransientReceipt(ReceiptState(UUID.randomUUID().toString(), "Could not accept fact: ${e.message ?: e.javaClass.simpleName}", null, isError = true, timestamp = clock.now()))
                    refreshUI()
                }
            }
            is UiAction.RejectSoulFact -> coroutineScope.launch(defaultDispatcher) {
                try {
                    val fact = storage.getProposedSoulFacts().find { it.id == action.factId }
                    val uId = "u_${UUID.randomUUID()}"
                    val beforeJson = fact?.let { json.encodeToString(it) }
                    val updatedFact = fact?.copy(status = "rejected")
                    val afterJson = updatedFact?.let { json.encodeToString(it) }
                    val event = RawEvent(uId, "REJECT", "soul_fact", action.factId, beforeJson = beforeJson, afterJson = afterJson, tsUtc = clock.now())
                    storage.updateSoulFactStatusAndCommit(action.factId, "rejected", event)
                    refreshUI()
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    addTransientReceipt(ReceiptState(UUID.randomUUID().toString(), "Could not reject fact: ${e.message ?: e.javaClass.simpleName}", null, isError = true, timestamp = clock.now()))
                    refreshUI()
                }
            }
            is UiAction.RefreshStats -> coroutineScope.launch(defaultDispatcher) {
                try {
                    refreshUI()
                    _state.update { it.copy(debugStats = storage.getDebugStats().entries.joinToString("\n") { e -> "${e.key}: ${e.value}" }) }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    addTransientReceipt(ReceiptState(UUID.randomUUID().toString(), "Could not refresh stats: ${e.message ?: e.javaClass.simpleName}", null, isError = true, timestamp = clock.now()))
                    refreshUI()
                }
            }
            is UiAction.ReloadStorage -> coroutineScope.launch(defaultDispatcher) {
                try {
                    loadInitialState()
                    refreshUI()
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    _state.update { it.copy(globalError = "Could not reload data: ${e.message ?: e.javaClass.simpleName}") }
                }
            }
            is UiAction.Undo -> coroutineScope.launch(defaultDispatcher) {
                try {
                    // D-033: Chip taps call gateway directly. Instant, no LLM.
                    val call = com.anchor.core.gateway.ToolCall("undo", mapOf("token" to kotlinx.serialization.json.JsonPrimitive(action.token)))
                    val result = gateway.executeTool(call)
                    when (result) {
                        is ToolResult.Committed -> {
                            addTransientReceipt(ReceiptState(UUID.randomUUID().toString(), result.message, null, isError = false, timestamp = clock.now()))
                        }
                        is ToolResult.Error -> {
                            addTransientReceipt(ReceiptState(UUID.randomUUID().toString(), result.reason, null, isError = true, timestamp = clock.now()))
                        }
                        else -> {}
                    }
                    if (_state.value.memoryLoaded) {
                        val activeNodes = storage.getActiveNodes(limit = 200)
                        val activeFacts = storage.getActiveSoulFacts(includeLocalOnly = true)
                        _state.update { it.copy(memoryNodes = activeNodes, memorySoulFacts = activeFacts) }
                    }
                    refreshUI()
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    addTransientReceipt(ReceiptState(UUID.randomUUID().toString(), "Could not undo: ${e.message ?: e.javaClass.simpleName}", null, isError = true, timestamp = clock.now()))
                    refreshUI()
                }
            }
            is UiAction.ConfirmProposal -> coroutineScope.launch(defaultDispatcher) {
                try {
                    val call = com.anchor.core.gateway.ToolCall("confirm_change", mapOf("proposal" to kotlinx.serialization.json.JsonPrimitive(action.proposalId), "choice" to kotlinx.serialization.json.JsonPrimitive(action.choiceId)))
                    val result = gateway.executeTool(call)
                    when (result) {
                        is ToolResult.Error -> {
                            // User-friendly loser / error UX:
                            // If already resolved (e.g. concurrent winner or double-tap loser), do not display a jarring error banner.
                            // The winning tap already displayed its success receipt and consumed the proposal.
                            if (result.reason != "Proposal already resolved") {
                                val friendlyReason = when (result.reason) {
                                    "Node not found" -> "Item was not found or was deleted"
                                    "Node is no longer active" -> "Item is no longer active"
                                    "Invalid choice" -> "Invalid option selected"
                                    "Invalid time choice" -> "Invalid time selected"
                                    else -> result.reason
                                }
                                addTransientReceipt(ReceiptState(UUID.randomUUID().toString(), friendlyReason, null, isError = true, timestamp = clock.now()))
                            }
                        }
                        is ToolResult.NotFound -> {
                            addTransientReceipt(ReceiptState(UUID.randomUUID().toString(), "Item not found: ${result.hint}", null, isError = true, timestamp = clock.now()))
                        }
                        is ToolResult.Committed -> {
                            // H-05 (R-09): a cancel has no raw_event, so nothing rebuilds its receipt on
                            // refresh. Without this branch the confirmation vanished from the timeline
                            // after the next refresh, and the empty token it used to carry would have
                            // rendered an Undo button whose only outcome was "Undo token not found".
                            // Every other choice writes a raw_event that loadReceipts() already turns
                            // into a receipt (with a validity-checked token), so adding one here would
                            // show the same action twice.
                            if (result.op == "CANCEL" && result.targetId == action.proposalId) {
                                addTransientReceipt(ReceiptState(
                                    UUID.randomUUID().toString(),
                                    result.message,
                                    result.undoToken.ifBlank { null },
                                    isError = false,
                                    timestamp = clock.now()
                                ))
                            }
                        }
                        else -> {}
                    }
                    if (_state.value.memoryLoaded) {
                        val activeNodes = storage.getActiveNodes(limit = 200)
                        val activeFacts = storage.getActiveSoulFacts(includeLocalOnly = true)
                        _state.update { it.copy(memoryNodes = activeNodes, memorySoulFacts = activeFacts) }
                    }
                    refreshUI()
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    addTransientReceipt(ReceiptState(UUID.randomUUID().toString(), "Could not confirm change: ${e.message ?: e.javaClass.simpleName}", null, isError = true, timestamp = clock.now()))
                    refreshUI()
                }
            }
            is UiAction.AddFollowUp -> coroutineScope.launch(defaultDispatcher) {
                try {
                    when (val result = gateway.handleAddFollowUp(action.srcNodeId, action.dstNodeId)) {
                        is ToolResult.Committed -> {
                            addTransientReceipt(ReceiptState(UUID.randomUUID().toString(), result.message, result.undoToken, isError = false, timestamp = clock.now()))
                        }
                        is ToolResult.Error -> {
                            addTransientReceipt(ReceiptState(UUID.randomUUID().toString(), result.reason, null, isError = true, timestamp = clock.now()))
                        }
                        else -> {}
                    }
                    if (_state.value.memoryLoaded) {
                        val activeNodes = storage.getActiveNodes(limit = 200)
                        val activeFacts = storage.getActiveSoulFacts(includeLocalOnly = true)
                        _state.update { it.copy(memoryNodes = activeNodes, memorySoulFacts = activeFacts) }
                    }
                    refreshUI()
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    addTransientReceipt(ReceiptState(UUID.randomUUID().toString(), "Could not add follow up: ${e.message ?: e.javaClass.simpleName}", null, isError = true, timestamp = clock.now()))
                    refreshUI()
                }
            }
            is UiAction.RemoveFollowUp -> coroutineScope.launch(defaultDispatcher) {
                try {
                    when (val result = gateway.handleRemoveFollowUp(action.edgeId)) {
                        is ToolResult.Committed -> {
                            addTransientReceipt(ReceiptState(UUID.randomUUID().toString(), result.message, result.undoToken, isError = false, timestamp = clock.now()))
                        }
                        is ToolResult.Error -> {
                            addTransientReceipt(ReceiptState(UUID.randomUUID().toString(), result.reason, null, isError = true, timestamp = clock.now()))
                        }
                        else -> {}
                    }
                    if (_state.value.memoryLoaded) {
                        val activeNodes = storage.getActiveNodes(limit = 200)
                        val activeFacts = storage.getActiveSoulFacts(includeLocalOnly = true)
                        _state.update { it.copy(memoryNodes = activeNodes, memorySoulFacts = activeFacts) }
                    }
                    refreshUI()
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    addTransientReceipt(ReceiptState(UUID.randomUUID().toString(), "Could not remove follow up: ${e.message ?: e.javaClass.simpleName}", null, isError = true, timestamp = clock.now()))
                    refreshUI()
                }
            }
            is UiAction.LoadDay -> coroutineScope.launch(defaultDispatcher) {
                try {
                    val msgs = storage.getMessagesForDay(action.dayKey)
                    _state.update { it.copy(selectedDayKey = action.dayKey, selectedDayMessages = msgs) }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    addTransientReceipt(ReceiptState(UUID.randomUUID().toString(), "Could not load messages for day: ${e.message ?: e.javaClass.simpleName}", null, isError = true, timestamp = clock.now()))
                    refreshUI()
                }
            }
            is UiAction.ShowDayReminders -> coroutineScope.launch(defaultDispatcher) {
                try {
                    val rems = storage.getRemindersForDay(action.startOfDayMs, action.endOfDayMs)
                    _state.update { it.copy(selectedDayReminders = rems) }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    addTransientReceipt(ReceiptState(UUID.randomUUID().toString(), "Could not load reminders for day: ${e.message ?: e.javaClass.simpleName}", null, isError = true, timestamp = clock.now()))
                    refreshUI()
                }
            }
            is UiAction.ClearDaySelection -> {
                _state.update {
                    it.copy(
                        selectedDayKey = null,
                        selectedDayMessages = emptyList(),
                        selectedDayReminders = emptyList()
                    )
                }
            }
            is UiAction.KeepStale -> coroutineScope.launch(defaultDispatcher) {
                try {
                    val result = gateway.handleKeepStale(action.nodeId)
                    when (result) {
                        is ToolResult.Error -> {
                            addTransientReceipt(ReceiptState(UUID.randomUUID().toString(), result.reason, null, isError = true, timestamp = clock.now()))
                        }
                        else -> {}
                    }
                    if (_state.value.memoryLoaded) {
                        val activeNodes = storage.getActiveNodes(limit = 200)
                        val activeFacts = storage.getActiveSoulFacts(includeLocalOnly = true)
                        _state.update { it.copy(memoryNodes = activeNodes, memorySoulFacts = activeFacts) }
                    }
                    refreshUI()
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    addTransientReceipt(ReceiptState(UUID.randomUUID().toString(), "Could not keep stale item: ${e.message ?: e.javaClass.simpleName}", null, isError = true, timestamp = clock.now()))
                    refreshUI()
                }
            }
            is UiAction.CancelStale -> coroutineScope.launch(defaultDispatcher) {
                try {
                    val result = gateway.handleCancelStale(action.nodeId)
                    when (result) {
                        is ToolResult.Error -> {
                            addTransientReceipt(ReceiptState(UUID.randomUUID().toString(), result.reason, null, isError = true, timestamp = clock.now()))
                        }
                        else -> {}
                    }
                    if (_state.value.memoryLoaded) {
                        val activeNodes = storage.getActiveNodes(limit = 200)
                        val activeFacts = storage.getActiveSoulFacts(includeLocalOnly = true)
                        _state.update { it.copy(memoryNodes = activeNodes, memorySoulFacts = activeFacts) }
                    }
                    refreshUI()
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    addTransientReceipt(ReceiptState(UUID.randomUUID().toString(), "Could not cancel stale item: ${e.message ?: e.javaClass.simpleName}", null, isError = true, timestamp = clock.now()))
                    refreshUI()
                }
            }
            // Row 20, read-only slice: the viewer renders the real tables (blueprint 51 §3.1 rule 1).
            // No model call, no gateway, no derived state — a plain read. A storage throw leaves the
            // lists untouched and reports it; memoryLoaded stays false so the next open retries.
            is UiAction.LoadMemory -> coroutineScope.launch(defaultDispatcher) {
                try {
                    val nodes = storage.getActiveNodes(MEMORY_VIEWER_PAGE)
                    val facts = storage.getActiveSoulFacts(includeLocalOnly = true)
                    val trash = storage.getTrashNodes(clock.now())
                    _state.update { it.copy(memoryNodes = nodes, memorySoulFacts = facts, trashNodes = trash, memoryLoaded = true) }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    addTransientReceipt(ReceiptState(UUID.randomUUID().toString(), "Could not load memory: ${e.message ?: e.javaClass.simpleName}", null, isError = true, timestamp = clock.now()))
                    refreshUI()
                }
            }
            is UiAction.ArchiveNode -> coroutineScope.launch(defaultDispatcher) {
                try {
                    val result = gateway.handleArchiveNode(action.nodeId)
                    when (result) {
                        is ToolResult.Error -> {
                            addTransientReceipt(ReceiptState(UUID.randomUUID().toString(), result.reason, null, isError = true, timestamp = clock.now()))
                        }
                        else -> {}
                    }
                    val nodes = storage.getActiveNodes(MEMORY_VIEWER_PAGE)
                    val facts = storage.getActiveSoulFacts(includeLocalOnly = true)
                    val trash = storage.getTrashNodes(clock.now())
                    _state.update { it.copy(memoryNodes = nodes, memorySoulFacts = facts, trashNodes = trash) }
                    refreshUI()
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    addTransientReceipt(ReceiptState(UUID.randomUUID().toString(), "Could not archive item: ${e.message ?: e.javaClass.simpleName}", null, isError = true, timestamp = clock.now()))
                    refreshUI()
                }
            }
            is UiAction.DeleteNode -> coroutineScope.launch(defaultDispatcher) {
                try {
                    val result = gateway.handleDeleteNode(action.nodeId)
                    when (result) {
                        is ToolResult.Error -> {
                            addTransientReceipt(ReceiptState(UUID.randomUUID().toString(), result.reason, null, isError = true, timestamp = clock.now()))
                        }
                        else -> {}
                    }
                    val nodes = storage.getActiveNodes(MEMORY_VIEWER_PAGE)
                    val facts = storage.getActiveSoulFacts(includeLocalOnly = true)
                    val trash = storage.getTrashNodes(clock.now())
                    _state.update { it.copy(memoryNodes = nodes, memorySoulFacts = facts, trashNodes = trash) }
                    refreshUI()
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    addTransientReceipt(ReceiptState(UUID.randomUUID().toString(), "Could not delete item: ${e.message ?: e.javaClass.simpleName}", null, isError = true, timestamp = clock.now()))
                    refreshUI()
                }
            }
            is UiAction.RestoreNode -> coroutineScope.launch(defaultDispatcher) {
                try {
                    val result = gateway.handleRestoreNode(action.nodeId)
                    when (result) {
                        is ToolResult.Error -> {
                            addTransientReceipt(ReceiptState(UUID.randomUUID().toString(), result.reason, null, isError = true, timestamp = clock.now()))
                        }
                        else -> {}
                    }
                    val nodes = storage.getActiveNodes(MEMORY_VIEWER_PAGE)
                    val facts = storage.getActiveSoulFacts(includeLocalOnly = true)
                    val trash = storage.getTrashNodes(clock.now())
                    _state.update { it.copy(memoryNodes = nodes, memorySoulFacts = facts, trashNodes = trash) }
                    refreshUI()
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    addTransientReceipt(ReceiptState(UUID.randomUUID().toString(), "Could not restore item: ${e.message ?: e.javaClass.simpleName}", null, isError = true, timestamp = clock.now()))
                    refreshUI()
                }
            }
            is UiAction.EraseNode -> coroutineScope.launch(defaultDispatcher) {
                try {
                    val rem = storage.getReminderByNodeId(action.nodeId) ?: storage.getReminderById("r_${action.nodeId}")
                    if (rem != null) {
                        alarmScheduler.cancel(rem.id)
                    }
                    val result = gateway.handleEraseNode(action.nodeId)
                    when (result) {
                        is ToolResult.Committed -> {
                            addTransientReceipt(ReceiptState(UUID.randomUUID().toString(), result.message, null, isError = false, timestamp = clock.now()))
                        }
                        is ToolResult.Error -> {
                            addTransientReceipt(ReceiptState(UUID.randomUUID().toString(), result.reason, null, isError = true, timestamp = clock.now()))
                        }
                        else -> {}
                    }
                    val nodes = storage.getActiveNodes(MEMORY_VIEWER_PAGE)
                    val facts = storage.getActiveSoulFacts(includeLocalOnly = true)
                    val trash = storage.getTrashNodes(clock.now())
                    _state.update { it.copy(memoryNodes = nodes, memorySoulFacts = facts, trashNodes = trash) }
                    refreshUI()
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    addTransientReceipt(ReceiptState(UUID.randomUUID().toString(), "Could not erase item: ${e.message ?: e.javaClass.simpleName}", null, isError = true, timestamp = clock.now()))
                    refreshUI()
                }
            }
            is UiAction.EraseSoulFact -> coroutineScope.launch(defaultDispatcher) {
                try {
                    val result = gateway.handleEraseSoulFact(action.factId)
                    when (result) {
                        is ToolResult.Committed -> {
                            addTransientReceipt(ReceiptState(UUID.randomUUID().toString(), result.message, null, isError = false, timestamp = clock.now()))
                        }
                        is ToolResult.Error -> {
                            addTransientReceipt(ReceiptState(UUID.randomUUID().toString(), result.reason, null, isError = true, timestamp = clock.now()))
                        }
                        else -> {}
                    }
                    val nodes = storage.getActiveNodes(MEMORY_VIEWER_PAGE)
                    val facts = storage.getActiveSoulFacts(includeLocalOnly = true)
                    val trash = storage.getTrashNodes(clock.now())
                    _state.update { it.copy(memoryNodes = nodes, memorySoulFacts = facts, trashNodes = trash) }
                    refreshUI()
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    addTransientReceipt(ReceiptState(UUID.randomUUID().toString(), "Could not erase fact: ${e.message ?: e.javaClass.simpleName}", null, isError = true, timestamp = clock.now()))
                    refreshUI()
                }
            }
            is UiAction.UpdateNodeStatus -> coroutineScope.launch(defaultDispatcher) {
                try {
                    val result = gateway.handleUpdateNodeStatus(action.nodeId, action.newStatus)
                    when (result) {
                        is ToolResult.Error -> addTransientReceipt(ReceiptState(UUID.randomUUID().toString(), result.reason, null, isError = true, timestamp = clock.now()))
                        else -> {}
                    }
                    if (_state.value.memoryLoaded) {
                        val activeNodes = storage.getActiveNodes(limit = 200)
                        val activeFacts = storage.getActiveSoulFacts(includeLocalOnly = true)
                        _state.update { it.copy(memoryNodes = activeNodes, memorySoulFacts = activeFacts) }
                    }
                    refreshUI()
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    addTransientReceipt(ReceiptState(UUID.randomUUID().toString(), "Could not update node status: ${e.message ?: e.javaClass.simpleName}", null, isError = true, timestamp = clock.now()))
                    refreshUI()
                }
            }
            // Blueprint 52 §3.1: step navigation. Progress is persisted after every step so a
            // mid-way close resumes where it stopped; `done` is written only by OnboardingFinish,
            // so a killed app never lands on the chat screen with an unseeded Soul.
            is UiAction.OnboardingNext -> coroutineScope.launch(defaultDispatcher) {
                val next = (_state.value.onboardingStep + 1).coerceAtMost(ONBOARDING_LAST_STEP)
                persistOnboardingStep(next)
            }
            is UiAction.OnboardingBack -> coroutineScope.launch(defaultDispatcher) {
                persistOnboardingStep((_state.value.onboardingStep - 1).coerceAtLeast(0))
            }
            is UiAction.OnboardingAnswer -> _state.update {
                it.copy(onboardingAnswers = it.onboardingAnswers + (action.category to action.text))
            }
            is UiAction.OnboardingHealthLocalOnly -> _state.update {
                it.copy(onboardingHealthLocalOnly = action.localOnly)
            }
            // Blueprint 52 §3.1 step 4: confirming the day is mandatory and writes the first
            // `day_rule`. The window may cross midnight; `DayRollover.computeDayKey` already
            // handles that, so the only check here is that the two ends are not the same minute.
            is UiAction.OnboardingConfirmDay -> coroutineScope.launch(defaultDispatcher) {
                if (action.startMinute == action.endMinute) {
                    _state.update { it.copy(onboardingError = "Pick a day that is not zero hours long.") }
                    return@launch
                }
                try {
                    storage.saveDayRule(
                        DayRollover.defaultRule(clock.now()).copy(
                            startMinute = action.startMinute,
                            endMinute = action.endMinute
                        )
                    )
                    persistOnboardingStep(ONBOARDING_SOUL_STEP)
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    _state.update { it.copy(onboardingError = "Could not save your day: ${e.message ?: e.javaClass.simpleName}") }
                }
            }
            // Blueprint 52 §3.2: the answers become Soul facts with `source = onboarding`,
            // `origin = approved`, status `active` — the user typed them, so there is no approval
            // step (D-137). Skipped questions write nothing. `done` is written last, so a failure
            // part-way leaves the flow open and retryable rather than half-seeded and finished.
            is UiAction.OnboardingFinish -> coroutineScope.launch(defaultDispatcher) {
                try {
                    val now = clock.now()
                    for ((category, text) in _state.value.onboardingAnswers) {
                        if (text.isBlank()) continue
                        val localOnly = category == "health" && _state.value.onboardingHealthLocalOnly
                        val fact = SoulFact(
                            id = "s_${UUID.randomUUID()}",
                            category = category,
                            statement = text.trim(),
                            confidence = ONBOARDING_FACT_CONFIDENCE,
                            source = "onboarding",
                            origin = "approved",
                            status = "active",
                            createdAt = now,
                            localOnly = localOnly,
                            sensitive = category == "health"
                        )
                        storage.createSoulFactAndCommit(
                            fact,
                            RawEvent(
                                opId = "u_${UUID.randomUUID()}",
                                opType = "CREATE",
                                targetType = "soul_fact",
                                targetId = fact.id,
                                afterJson = json.encodeToString(fact),
                                tsUtc = now
                            )
                        )
                    }
                    // Personal details from the About-you step become identity facts.
                    val profileValues = ProfileFields.ALL.associate { f -> f.key to storage.getSetting(f.key).orEmpty() }
                    val profileLocal = storage.getSetting(ProfileFields.KEY_LOCAL_ONLY) == "1"
                    val zoneId = runCatching {
                        val tz = DayRollover.safeTimeZone(storage.getEffectiveDayRule(now).tzId)
                        runCatching { tz.toZoneId() }.getOrElse {
                            runCatching { java.time.ZoneId.of(tz.id, java.time.ZoneId.SHORT_IDS) }
                                .getOrElse { java.time.ZoneId.systemDefault() }
                        }
                    }.getOrElse { java.time.ZoneId.systemDefault() }
                    val today = runCatching { java.time.Instant.ofEpochMilli(now).atZone(zoneId).toLocalDate() }
                        .getOrElse { java.time.LocalDate.now() }
                    val already = storage.getActiveSoulFacts(includeLocalOnly = true).map { it.statement.trim().lowercase() }.toSet()
                    for ((field, statement) in ProfileFields.soulStatements(profileValues, today)) {
                        if (statement.trim().lowercase() in already) continue
                        val fact = SoulFact(
                            id = "s_${UUID.randomUUID()}",
                            category = if (field == ProfileFields.ABOUT) "other" else "identity",
                            statement = statement,
                            confidence = ONBOARDING_FACT_CONFIDENCE,
                            source = "onboarding",
                            origin = "approved",
                            status = "active",
                            createdAt = now,
                            localOnly = profileLocal,
                            sensitive = field.sensitive
                        )
                        storage.createSoulFactAndCommit(
                            fact,
                            RawEvent(
                                opId = "u_${UUID.randomUUID()}",
                                opType = "CREATE",
                                targetType = "soul_fact",
                                targetId = fact.id,
                                afterJson = json.encodeToString(fact),
                                tsUtc = now
                            )
                        )
                    }
                    storage.saveSetting(ONBOARDING_DONE_KEY, "1")
                    _state.update { it.copy(onboardingDone = true, onboardingError = null) }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    _state.update { it.copy(onboardingError = "Could not save your answers: ${e.message ?: e.javaClass.simpleName}") }
                }
            }
            // Blueprint 31: one proactive pass. The engine decides; this only supplies the
            // candidate, the persisted state and the delivery. Nothing here calls the model — the
            // v1 slice delivers template text, which is what blueprint 31 §3.7 falls back to when
            // a model turn is skipped, timed out or offline.
            is UiAction.TickProactive -> coroutineScope.launch(defaultDispatcher) {
                try {
                    val now = clock.now()
                    storage.dropStaleOutbox(now)

                    if (storage.getSetting("nudge.enabled") == "0") {
                        _state.update { it.copy(nudgeGate = "disabled") }
                        return@launch
                    }
                    val pausedUntil = storage.getSetting(CompanionActions.KEY_NUDGES_PAUSED)?.toLongOrNull()
                    if (pausedUntil != null && pausedUntil > now) {
                        _state.update { it.copy(nudgeGate = "paused") }
                        return@launch
                    }

                    val dayRule = storage.getEffectiveDayRule(now)
                    val currentDayKey = com.anchor.core.time.DayRollover.computeDayKey(now, dayRule)
                    val isInDayWindow = com.anchor.core.time.DayRollover.isInDayWindow(now, dayRule)

                    // Overnight / evening memory consolidation: persist DayDigest if outside day window or late
                    if (!isInDayWindow && storage.getDayDigest(currentDayKey) == null) {
                        try {
                            val closed = storage.getRecentlyClosed(now, limit = 50)
                            val open = storage.getActiveNodes(limit = 50)
                            val digest = com.anchor.core.consolidation.ConsolidationEngine().consolidateDay(
                                dayKey = currentDayKey,
                                completedNodes = closed,
                                openNodes = open,
                                nowMs = now
                            )
                            storage.saveDayDigest(digest)
                        } catch (_: Exception) {}
                    }

                    val minGapMinutes = storage.getSetting("nudge.min_gap_minutes")?.toLongOrNull() ?: 60L
                    val ignoreWindowHours = storage.getSetting("nudge.ignore_window_hours")?.toLongOrNull() ?: 3L
                    val maxPerDay = storage.getSetting("nudge.max_per_day")?.toIntOrNull() ?: 40

                    val state = storage.getProactiveState()
                    // An ignored thread is closed by the engine after its window; running
                    // that first is what lets the next nudge out once the window has passed.
                    val afterIgnore = nudgeEngine.processIgnore(state, now, ignoreWindowHours)
                    if (afterIgnore != state) {
                        storage.saveProactiveState(afterIgnore)
                        if (afterIgnore.backoffStep > state.backoffStep) {
                            learningEngine.recordSignal(
                                kind = com.anchor.core.learning.LearningEngine.SIGNAL_NUDGE_IGNORED,
                                subject = "overdue",
                                now = now
                            )
                            learningEngine.recomputePreferences(now)
                        }
                    }

                    val picked = pickNudgeCandidate(afterIgnore)
                    if (picked == null) {
                        _state.update { it.copy(nudgeGate = "no_candidate") }
                        return@launch
                    }
                    val candidate = picked.candidate
                    val isVehicle = contextEngine?.isVehicleHoldActive() ?: false
                    when (val gate = nudgeEngine.evaluate(
                        candidate,
                        afterIgnore,
                        isInDayWindow = isInDayWindow,
                        currentDayKey = currentDayKey,
                        isVehicle = isVehicle,
                        minGapMinutes = minGapMinutes,
                        maxPerDay = maxPerDay
                    )) {
                        is GateResult.Held -> {
                            _state.update { it.copy(nudgeGate = gate.reason) }
                            try {
                                storage.saveNudgeLog(
                                    com.anchor.core.models.NudgeLogRecord(
                                        id = "nl_${UUID.randomUUID()}",
                                        tsUtc = now,
                                        triggerType = candidate.triggerType,
                                        decision = "held",
                                        reasonJson = gate.reason
                                    )
                                )
                            } catch (_: Exception) {}
                        }
                        is GateResult.Pass -> {
                            val msgId = "n_${UUID.randomUUID()}"
                            val text = picked.customText ?: nudgeText(picked.node!!)
                            // The message row is written before the state is advanced: a crash in
                            // between leaves a nudge the user saw but the engine will re-send, which
                            // is recoverable; the reverse loses it silently.
                            storage.insertRawMessageWithAttrs(msgId, "assistant", text, null, MessageAttrs.proactive("nudge"))
                            storage.saveProactiveState(nudgeEngine.onNudgeSent(afterIgnore, msgId, now, dayKey = currentDayKey))
                            try {
                                storage.saveNudgeLog(
                                    com.anchor.core.models.NudgeLogRecord(
                                        id = "nl_${UUID.randomUUID()}",
                                        tsUtc = now,
                                        triggerType = candidate.triggerType,
                                        decision = "pass",
                                        msgId = msgId
                                    )
                                )
                            } catch (_: Exception) {}
                            onBackgroundReplyCompleted?.invoke(text)
                            _state.update { it.copy(nudgeGate = "sent") }
                            refreshUI()
                        }
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    _state.update { it.copy(nudgeGate = "error") }
                }
            }
            // Blueprint 41 §3.4: the "network available" event and the retry timer both land here.
            // drainQueuedTurns is a no-op when nothing is due, so a spurious trigger costs a read.
            is UiAction.DrainQueuedTurns -> coroutineScope.launch(defaultDispatcher) {
                try {
                    drainQueuedTurns()
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    addTransientReceipt(ReceiptState(UUID.randomUUID().toString(), "Could not send saved messages: ${e.message ?: e.javaClass.simpleName}", null, isError = true, timestamp = clock.now()))
                    refreshUI()
                }
            }
            is UiAction.WhyNudge -> coroutineScope.launch(defaultDispatcher) {
                try {
                    val pref = storage.getLearnedPref(com.anchor.core.learning.LearningEngine.KEY_NUDGE_REPLY_RATE, "trigger=overdue")
                    val explanation = learningEngine.explainWhy("overdue", "an item is overdue and needs your attention", pref)
                    _state.update { it.copy(whyNudgeText = explanation) }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                }
            }
            is UiAction.DismissWhyNudge -> {
                _state.update { it.copy(whyNudgeText = null) }
            }
            is UiAction.LockLearnedPref -> coroutineScope.launch(defaultDispatcher) {
                try {
                    learningEngine.lockPreference(action.key, action.scope, action.locked)
                    refreshUI()
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    addTransientReceipt(ReceiptState(UUID.randomUUID().toString(), "Could not lock preference: ${e.message ?: e.javaClass.simpleName}", null, isError = true, timestamp = clock.now()))
                    refreshUI()
                }
            }
            is UiAction.ResetLearning -> coroutineScope.launch(defaultDispatcher) {
                try {
                    learningEngine.resetLearning(action.preserveLocked)
                    refreshUI()
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    addTransientReceipt(ReceiptState(UUID.randomUUID().toString(), "Could not reset learning: ${e.message ?: e.javaClass.simpleName}", null, isError = true, timestamp = clock.now()))
                    refreshUI()
                }
            }
            is UiAction.SetPersona -> coroutineScope.launch(defaultDispatcher) {
                try {
                    storage.saveSetting("persona.active", action.personaId)
                    val persona = com.anchor.core.persona.PersonaPresets.findById(action.personaId)
                    _state.update { it.copy(activePersonaId = persona.id, activePersonaName = persona.name) }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    addTransientReceipt(ReceiptState(UUID.randomUUID().toString(), "Could not set persona: ${e.message ?: e.javaClass.simpleName}", null, isError = true, timestamp = clock.now()))
                    refreshUI()
                }
            }
            is UiAction.UnlockApp -> {
                _state.update { it.copy(isAppLocked = false) }
            }
            is UiAction.LockApp -> {
                if (_state.value.appLockEnabled) {
                    _state.update { it.copy(isAppLocked = true) }
                }
            }
            is UiAction.SetAppLock -> coroutineScope.launch(defaultDispatcher) {
                try {
                    storage.saveSetting("privacy.app_lock", if (action.enabled) "1" else "0")
                    _state.update { it.copy(appLockEnabled = action.enabled, isAppLocked = false) }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    addTransientReceipt(ReceiptState(UUID.randomUUID().toString(), "Could not update app lock: ${e.message ?: e.javaClass.simpleName}", null, isError = true, timestamp = clock.now()))
                    refreshUI()
                }
            }
            is UiAction.SetRelockTimeout -> coroutineScope.launch(defaultDispatcher) {
                try {
                    storage.saveSetting("privacy.relock_timeout_sec", action.seconds.toString())
                    _state.update { it.copy(relockTimeoutSec = action.seconds) }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    addTransientReceipt(ReceiptState(UUID.randomUUID().toString(), "Could not update relock timeout: ${e.message ?: e.javaClass.simpleName}", null, isError = true, timestamp = clock.now()))
                    refreshUI()
                }
            }
            is UiAction.SetBlockScreenshots -> coroutineScope.launch(defaultDispatcher) {
                try {
                    storage.saveSetting("privacy.block_screenshots", if (action.enabled) "1" else "0")
                    _state.update { it.copy(blockScreenshots = action.enabled) }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    addTransientReceipt(ReceiptState(UUID.randomUUID().toString(), "Could not update screenshot setting: ${e.message ?: e.javaClass.simpleName}", null, isError = true, timestamp = clock.now()))
                    refreshUI()
                }
            }
            is UiAction.SetCompactMode -> coroutineScope.launch(defaultDispatcher) {
                try {
                    storage.saveSetting("ai.compact_mode", if (action.enabled) "1" else "0")
                    _state.update { it.copy(compactMode = action.enabled) }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    addTransientReceipt(ReceiptState(UUID.randomUUID().toString(), "Could not update compact mode: ${e.message ?: e.javaClass.simpleName}", null, isError = true, timestamp = clock.now()))
                    refreshUI()
                }
            }
            is UiAction.SetProcrastinationMode -> coroutineScope.launch(defaultDispatcher) {
                try {
                    storage.saveSetting("procrastination.mode", action.mode)
                    _state.update { it.copy(procrastinationMode = action.mode) }
                    refreshUI()
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                }
            }
            is UiAction.SetContextSensing -> coroutineScope.launch(defaultDispatcher) {
                try {
                    storage.saveSetting("flag.sensing", if (action.enabled) "1" else "0")
                    storage.saveSetting("sensing.enabled", if (action.enabled) "1" else "0")
                    _state.update { it.copy(contextSensingEnabled = action.enabled) }
                    onContextSensingChanged?.invoke(action.enabled)
                    refreshUI()
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                }
            }
            is UiAction.AcceptAmbientItem -> coroutineScope.launch(defaultDispatcher) {
                try {
                    val report = storage.getLatestAmbientReport() ?: return@launch
                    val items = storage.getAmbientReportItems(report.id)
                    val target = items.find { it.id == action.itemId } ?: return@launch

                    storage.updateAmbientReportItemState(target.id, "accepted", clock.now())
                    var scheduledNote = ""
                    if (target.nodeId != null) {
                        gateway.handleActivateNode(target.nodeId)
                        // A suggestion that names a time ("Call Riya at 6pm") becomes a real reminder
                        // on accept, through the same reschedule path a chat turn would use.
                        val node = storage.getNode(target.nodeId)
                        val whenText = node?.takeIf { it.dueAt == null }?.let { com.anchor.core.notifications.NotificationSuggestions.timeSpan(it.title) }
                        if (whenText != null) {
                            val r = gateway.executeTool(
                                com.anchor.core.gateway.ToolCall(
                                    "propose_change",
                                    mapOf(
                                        "intent" to kotlinx.serialization.json.JsonPrimitive("RESCHEDULE"),
                                        "target_ref" to kotlinx.serialization.json.JsonPrimitive(target.nodeId),
                                        "due_text" to kotlinx.serialization.json.JsonPrimitive(whenText)
                                    )
                                ),
                                executedCalls = mutableSetOf()
                            )
                            if (r is ToolResult.Committed) scheduledNote = " · reminder set"
                        }
                    }
                    val updatedItems = storage.getAmbientReportItems(report.id)
                    _state.update { it.copy(ambientReportItems = updatedItems) }
                    addTransientReceipt(ReceiptState(UUID.randomUUID().toString(), "Accepted: ${target.summary}$scheduledNote", null, isError = false, timestamp = clock.now()))
                    refreshUI()
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    addTransientReceipt(ReceiptState(UUID.randomUUID().toString(), "Could not accept ambient item: ${e.message}", null, isError = true, timestamp = clock.now()))
                    refreshUI()
                }
            }
            is UiAction.RejectAmbientItem -> coroutineScope.launch(defaultDispatcher) {
                try {
                    val report = storage.getLatestAmbientReport() ?: return@launch
                    val items = storage.getAmbientReportItems(report.id)
                    val target = items.find { it.id == action.itemId } ?: return@launch

                    storage.updateAmbientReportItemState(target.id, "rejected", clock.now())
                    if (target.nodeId != null) {
                        gateway.handleDeleteNode(target.nodeId)
                    }
                    val updatedItems = storage.getAmbientReportItems(report.id)
                    _state.update { it.copy(ambientReportItems = updatedItems) }
                    addTransientReceipt(ReceiptState(UUID.randomUUID().toString(), "Rejected: ${target.summary}", null, isError = false, timestamp = clock.now()))
                    refreshUI()
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    addTransientReceipt(ReceiptState(UUID.randomUUID().toString(), "Could not reject ambient item: ${e.message}", null, isError = true, timestamp = clock.now()))
                    refreshUI()
                }
            }
            is UiAction.AcceptAllAmbientItems -> coroutineScope.launch(defaultDispatcher) {
                try {
                    val items = storage.getAmbientReportItems(action.reportId)
                    var count = 0
                    for (item in items) {
                        val flags = item.flags ?: ""
                        if (!flags.contains("attribution_uncertain") && !flags.contains("possible_duplicate") && item.state == "pending") {
                            storage.updateAmbientReportItemState(item.id, "accepted", clock.now())
                            if (item.nodeId != null) {
                                gateway.handleActivateNode(item.nodeId)
                            }
                            count++
                        }
                    }
                    val updatedItems = storage.getAmbientReportItems(action.reportId)
                    _state.update { it.copy(ambientReportItems = updatedItems) }
                    addTransientReceipt(ReceiptState(UUID.randomUUID().toString(), "Accepted $count unflagged items", null, isError = false, timestamp = clock.now()))
                    refreshUI()
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    addTransientReceipt(ReceiptState(UUID.randomUUID().toString(), "Could not accept items: ${e.message}", null, isError = true, timestamp = clock.now()))
                    refreshUI()
                }
            }
            is UiAction.DeleteAllAmbientData -> coroutineScope.launch(defaultDispatcher) {
                try {
                    val count = storage.deleteAllAmbientData()
                    _state.update { it.copy(latestAmbientReport = null, ambientReportItems = emptyList(), voiceprintEnrolled = false) }
                    addTransientReceipt(ReceiptState(UUID.randomUUID().toString(), "Deleted all ambient data ($count records)", null, isError = false, timestamp = clock.now()))
                    refreshUI()
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    addTransientReceipt(ReceiptState(UUID.randomUUID().toString(), "Could not delete ambient data: ${e.message}", null, isError = true, timestamp = clock.now()))
                    refreshUI()
                }
            }
            is UiAction.BookmarkAmbientLast2Min -> coroutineScope.launch(defaultDispatcher) {
                try {
                    addTransientReceipt(ReceiptState(UUID.randomUUID().toString(), "Remembering last 2 minutes...", null, isError = false, timestamp = clock.now()))
                    refreshUI()
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    addTransientReceipt(ReceiptState(UUID.randomUUID().toString(), "Could not trigger ambient bookmark: ${e.message}", null, isError = true, timestamp = clock.now()))
                    refreshUI()
                }
            }
            is UiAction.LoadInsights -> coroutineScope.launch(defaultDispatcher) {
                try {
                    val now = clock.now()
                    val spanMs = action.days.toLong() * 86_400_000L
                    val startMs = now - spanMs
                    val moodEnabled = storage.getSetting("flag.mood_tracking") == "1"
                    val report = storage.getFullInsightsReport(startMs, now, now, moodEnabled)
                    _state.update { it.copy(latestInsightsReport = report) }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    addTransientReceipt(ReceiptState(UUID.randomUUID().toString(), "Could not load insights: ${e.message}", null, isError = true, timestamp = clock.now()))
                }
            }
            is UiAction.SetHandsFree -> {
                val nextState = if (action.enabled) com.anchor.core.voice.HandsFreeState.LISTENING else com.anchor.core.voice.HandsFreeState.IDLE
                _state.update { it.copy(handsFreeState = nextState) }
                if (!action.enabled) {
                    addTransientReceipt(ReceiptState(UUID.randomUUID().toString(), "Hands-free mode stopped.", null, isError = false, timestamp = clock.now()))
                }
            }
            is UiAction.UpdateHandsFreeState -> {
                _state.update { it.copy(handsFreeState = action.state) }
            }
            is UiAction.ToggleWebSearch -> {
                val next = !_state.value.webSearchEnabled
                _state.update { it.copy(webSearchEnabled = next) }
                gateway.webSearchEnabled = next
                coroutineScope.launch(defaultDispatcher) {
                    storage.saveSetting("web_search.enabled", if (next) "1" else "0")
                }
            }
            is UiAction.SetWebSearch -> {
                _state.update { it.copy(webSearchEnabled = action.enabled) }
                gateway.webSearchEnabled = action.enabled
                coroutineScope.launch(defaultDispatcher) {
                    storage.saveSetting("web_search.enabled", if (action.enabled) "1" else "0")
                }
            }
            is UiAction.CreateTopicNote -> coroutineScope.launch(defaultDispatcher) {
                val now = clock.now()
                val id = "tn_${UUID.randomUUID().toString().take(8)}"
                val cleanedBullets = action.bullets.map { it.trim().removePrefix("-").removePrefix("*").trim() }.filter { it.isNotBlank() }
                val newNote = TopicNote(
                    id = id,
                    topic = action.topic.trim(),
                    content = cleanedBullets.joinToString("\n") { "- $it" },
                    bulletsJson = json.encodeToString(cleanedBullets),
                    pinned = action.pinned,
                    lastEditedAt = now,
                    createdAt = now
                )
                storage.saveTopicNote(newNote)
                val uId = "u_${UUID.randomUUID()}"
                val event = RawEvent(
                    opId = uId,
                    opType = "CREATE",
                    targetType = "topic_note",
                    targetId = id,
                    beforeJson = null,
                    afterJson = json.encodeToString(newNote),
                    tsUtc = now
                )
                storage.insertRawEvent(event)
                addTransientReceipt(ReceiptState(uId, "Created note: ${newNote.topic}", uId, isError = false, timestamp = now))
                refreshUI()
            }
            is UiAction.AddTopicNoteBullet -> coroutineScope.launch(defaultDispatcher) {
                val note = storage.getTopicNote(action.id) ?: storage.getAllTopicNotes().find { it.id == action.id }
                if (note != null) {
                    val now = clock.now()
                    val existingBullets = try {
                        json.decodeFromString<List<String>>(note.bulletsJson)
                    } catch (_: Exception) {
                        note.content.lines().map { it.removePrefix("-").trim() }.filter { it.isNotBlank() }
                    }
                    val bulletClean = action.bullet.trim().removePrefix("-").removePrefix("*").trim()
                    if (bulletClean.isNotBlank()) {
                        val updatedBullets = existingBullets + bulletClean
                        val updatedNote = note.copy(
                            content = updatedBullets.joinToString("\n") { "- $it" },
                            bulletsJson = json.encodeToString(updatedBullets),
                            lastEditedAt = now
                        )
                        storage.saveTopicNote(updatedNote)
                        val uId = "u_${UUID.randomUUID()}"
                        val event = RawEvent(
                            opId = uId,
                            opType = "UPDATE",
                            targetType = "topic_note",
                            targetId = note.id,
                            beforeJson = json.encodeToString(note),
                            afterJson = json.encodeToString(updatedNote),
                            tsUtc = now
                        )
                        storage.insertRawEvent(event)
                        addTransientReceipt(ReceiptState(uId, "Updated note: ${note.topic}", uId, isError = false, timestamp = now))
                        refreshUI()
                    }
                }
            }
            is UiAction.TogglePinTopicNote -> coroutineScope.launch(defaultDispatcher) {
                val note = storage.getTopicNote(action.id) ?: storage.getAllTopicNotes().find { it.id == action.id }
                if (note != null) {
                    val now = clock.now()
                    val updatedNote = note.copy(
                        pinned = !note.pinned,
                        lastEditedAt = now
                    )
                    storage.saveTopicNote(updatedNote)
                    val uId = "u_${UUID.randomUUID()}"
                    val event = RawEvent(
                        opId = uId,
                        opType = "UPDATE",
                        targetType = "topic_note",
                        targetId = note.id,
                        beforeJson = json.encodeToString(note),
                        afterJson = json.encodeToString(updatedNote),
                        tsUtc = now
                    )
                    storage.insertRawEvent(event)
                    val msg = if (updatedNote.pinned) "Pinned note: ${note.topic}" else "Unpinned note: ${note.topic}"
                    addTransientReceipt(ReceiptState(uId, msg, uId, isError = false, timestamp = now))
                    refreshUI()
                }
            }
            is UiAction.DeleteTopicNote -> coroutineScope.launch(defaultDispatcher) {
                val note = storage.getTopicNote(action.id) ?: storage.getAllTopicNotes().find { it.id == action.id }
                if (note != null) {
                    val now = clock.now()
                    storage.deleteTopicNote(action.id)
                    val uId = "u_${UUID.randomUUID()}"
                    val event = RawEvent(
                        opId = uId,
                        opType = "DELETE",
                        targetType = "topic_note",
                        targetId = note.id,
                        beforeJson = json.encodeToString(note),
                        afterJson = null,
                        tsUtc = now
                    )
                    storage.insertRawEvent(event)
                    addTransientReceipt(ReceiptState(uId, "Deleted note: ${note.topic}", uId, isError = false, timestamp = now))
                    refreshUI()
                }
            }
            is UiAction.SetCurrentWifiAsHome -> coroutineScope.launch(defaultDispatcher) {
                val now = clock.now()
                val place = com.anchor.core.sensing.Place(
                    id = "place_home",
                    kind = "home",
                    label = "Home",
                    updatedAt = now
                )
                val signal = com.anchor.core.sensing.PlaceSignal(
                    placeId = "place_home",
                    wifiJson = """{"ssid":"home_wifi","bssids":[]}"""
                )
                storage.savePlace(place, signal)
                addTransientReceipt(ReceiptState(UUID.randomUUID().toString(), "Enrolled Home Wi-Fi", null, isError = false, timestamp = now))
                refreshUI()
            }
            is UiAction.SetPostCallNudges -> coroutineScope.launch(defaultDispatcher) {
                val v = if (action.enabled) "1" else "0"
                storage.saveSetting("flag.call_nudge", v)
                storage.saveSetting("callnudge.enabled", v)
                storage.saveSetting("flag.call_nudges", v)
                _state.update { it.copy(postCallNudgesEnabled = action.enabled) }
                onPostCallNudgesChanged?.invoke(action.enabled)
                refreshUI()
            }
            is UiAction.SetOnDeviceFallback -> coroutineScope.launch(defaultDispatcher) {
                val v = if (action.enabled) "1" else "0"
                storage.saveSetting("provider.ondevice.enabled", v)
                storage.saveSetting("flag.ondevice_llm", v)
                _state.update { it.copy(onDeviceFallbackEnabled = action.enabled) }
                refreshUI()
            }
            is UiAction.SetMoodTracking -> coroutineScope.launch(defaultDispatcher) {
                storage.saveSetting("flag.mood_tracking", if (action.enabled) "1" else "0")
                _state.update { it.copy(moodTrackingEnabled = action.enabled) }
                refreshUI()
            }
            is UiAction.SetHideNotificationContent -> coroutineScope.launch(defaultDispatcher) {
                val v = if (action.enabled) "1" else "0"
                storage.saveSetting("privacy.hide_notification_content", v)
                storage.saveSetting("flag.hide_notification_content", v)
                _state.update { it.copy(hideNotificationContent = action.enabled) }
                refreshUI()
            }
            is UiAction.SetSoulAutoAcceptMode -> coroutineScope.launch(defaultDispatcher) {
                val mode = if (action.enabled) "auto" else "ask"
                storage.saveSetting("soul.mode", mode)
                storage.saveSetting("soul.auto_accept", if (action.enabled) "1" else "0")
                _state.update { it.copy(soulAutoAcceptMode = action.enabled) }
                refreshUI()
            }
            is UiAction.SaveFallbackProvider -> coroutineScope.launch(defaultDispatcher) {
                val ep = action.endpoint.trim()
                val k = action.key.trim()
                val m = action.model.trim()
                storage.saveSetting("provider.fallback.endpoint", ep)
                storage.saveSetting("provider.fallback.key", k)
                storage.saveSetting("provider.fallback.model", m)
                storage.saveSetting("ep.fallback", ep)
                storage.saveSetting("key.fallback", k)
                storage.saveSetting("model.fallback", m)
                _state.update { it.copy(fallbackEndpoint = ep, fallbackKey = k, fallbackModel = m) }
                refreshUI()
            }
            is UiAction.SetFallbackModel -> coroutineScope.launch(defaultDispatcher) {
                val m = action.model.trim()
                storage.saveSetting("provider.fallback.model", m)
                storage.saveSetting("model.fallback", m)
                _state.update { it.copy(fallbackModel = m) }
                refreshUI()
            }
            is UiAction.RetryNow -> coroutineScope.launch(defaultDispatcher) {
                val primaryKey = _state.value.providerKey
                val fallbackKey = _state.value.fallbackKey
                if (primaryKey.isNotBlank()) rateLimiter?.clearCooldown(primaryKey)
                if (fallbackKey.isNotBlank()) rateLimiter?.clearCooldown(fallbackKey)
                _state.update { it.copy(connectionError = null, providerCooldownExpiry = null) }
                drainQueuedTurns()
                refreshUI()
            }
            is UiAction.SetAutoBackup -> coroutineScope.launch(defaultDispatcher) {
                val boolStr = if (action.enabled) "true" else "false"
                val digitStr = if (action.enabled) "1" else "0"
                storage.saveSetting("export.auto.enabled", boolStr)
                storage.saveSetting("backup.auto_enabled", digitStr)
                _state.update { it.copy(autoBackupEnabled = action.enabled) }
                refreshUI()
            }
            is UiAction.SetAutoBackupUri -> coroutineScope.launch(defaultDispatcher) {
                storage.saveSetting("export.auto.folder_uri", action.uri)
                storage.saveSetting("backup.auto_uri", action.uri)
                _state.update { it.copy(autoBackupUri = action.uri) }
                refreshUI()
            }
            else -> {}
        }
    }

    private data class PickedNudge(
        val candidate: NudgeCandidate,
        val node: Node? = null,
        val customText: String? = null
    )

    /**
     * Blueprint 31 §3.2: the v1 slice has overdue item triggers and grounded casual check-in triggers.
     * Local-only items are skipped: they are invisible to the model and to chat (H2).
     */
    private suspend fun pickNudgeCandidate(state: ProactiveState): PickedNudge? {
        val now = clock.now()
        val overdue = storage.getActiveNodes(NUDGE_SCAN_PAGE)
            .filter { !it.localOnly && it.dueAt != null && it.dueAt < now }
            .minByOrNull { it.dueAt!! }

        if (overdue != null) {
            val baseValue = 20.0 + (overdue.priority * 5.0) + (overdue.severity * 5.0)
            val candidate = NudgeCandidate("overdue_item", isCritical = false, baseValue = baseValue)
            if (candidate.baseValue >= nudgeEngine.calculateThreshold(state)) {
                return PickedNudge(candidate, overdue, null)
            }
        }

        // Blueprint 31 casual checkin: when no overdue item or below threshold, check if threshold is cool (<= 22.0)
        if (nudgeEngine.calculateThreshold(state) <= 22.0) {
            val casualMsg = checkinBuilder.buildGroundedCasualCheckin(now)
            if (!casualMsg.isNullOrBlank()) {
                val candidate = NudgeCandidate("casual_checkin", isCritical = false, baseValue = 25.0)
                return PickedNudge(candidate, null, customText = casualMsg)
            }
        }

        return null
    }

    /** Blueprint 31 §3.7 template text: no model call, styled by active persona. */
    private fun nudgeText(node: Node): String {
        val persona = com.anchor.core.persona.PersonaPresets.findById(_state.value.activePersonaId)
        return templateEngine.render(
            com.anchor.core.persona.TriggerType.NUDGE_OVERDUE,
            persona,
            mapOf("title" to node.title.trim()),
            seed = node.createdAt
        )
    }

    /**
     * Blueprint 50 §3.14 / E-17: sends [text] on behalf of a path that has no composer — the
     * notification reply and the launcher shortcut. [userMsgId] is the row the caller already
     * stored, so the capture that the user wrote is the user turn of this exchange and is never
     * duplicated; when null a fresh `u_` row is written, exactly as the composer does.
     *
     * A blank message is dropped. A message that arrives while a turn is already streaming waits
     * for that turn to finish instead of being dropped: the startup drain sends every pending
     * capture, and a dropped one would be marked answered by the previous turn's reply without
     * ever being sent. The caller keeps the stored row either way, so a send that never runs is
     * retried on the next start instead of lost.
     *
     * The provider config is read from storage on every call: a capture can be the first thing
     * that runs after a cold start, before any state emission has hydrated the key, and a turn
     * started with a blank key would fail on the wire instead of saying "connect a provider".
     */
    fun sendText(text: String, userMsgId: String? = null) {
        if (text.isBlank()) return
        coroutineScope.launch(defaultDispatcher) {
            sendMutex.withLock {
                currentStreamJob = currentCoroutineContext()[Job]
                workerAiJob?.cancel()
                try {
                    val before = storage.getProactiveState()
                    if (before.openThreadMsgId != null) {
                        val now = clock.now()
                        storage.saveProactiveState(nudgeEngine.processReply(before, now))
                        learningEngine.recordSignal(
                            kind = com.anchor.core.learning.LearningEngine.SIGNAL_NUDGE_REPLY,
                            subject = "overdue",
                            now = now
                        )
                        learningEngine.recomputePreferences(now)
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                }

                try {
                    _state.update { it.copy(isChatLoading = true, connectionError = null) }
                    val key = storage.getSetting("key") ?: ""
                    val onDeviceOnly = key.isBlank() &&
                        (storage.getSetting("provider.ondevice.enabled") == "1" || storage.getSetting("flag.ondevice_llm") == "1")
                    if (key.isBlank() && !onDeviceOnly) {
                        val activeUserMsgId = userMsgId ?: "u_${UUID.randomUUID()}"
                        val quickAdd = ruleBasedQuickAdd.parse(text)
                        if (quickAdd != null) {
                            _state.update { it.copy(isChatLoading = false) }
                            handleOfflineQuickAdd(quickAdd, text, if (userMsgId == null) null else activeUserMsgId)
                            return@withLock
                        }
                        val talk = ChatResilience.classifySmallTalk(text)
                        if (talk != null) {
                            replyLocally(text, userMsgId, talk, connected = false)
                            return@withLock
                        }
                        enqueueChatTurn(text, userMsgId)
                        _state.update {
                            it.copy(
                                isChatLoading = false,
                                connectionError = "Connect an AI provider in Settings and I'll answer this. Your message is saved.",
                                connectionErrorRetryable = false,
                                connectionErrorNeedsSettings = true
                            )
                        }
                        refreshUI()
                        return@withLock
                    }
                    _state.update {
                        it.copy(
                            providerKey = key,
                            providerEndpoint = storage.getSetting("ep") ?: "https://api.openai.com/v1/chat/completions",
                            providerModel = storage.getSetting("mod") ?: "gpt-4o-mini"
                        )
                    }

                    if (!networkAvailable() && !onDeviceOnly) {
                        _state.update { it.copy(isChatLoading = false) }
                        val quickAdd = ruleBasedQuickAdd.parse(text)
                        if (quickAdd != null) {
                            handleOfflineQuickAdd(quickAdd, text, userMsgId)
                            return@withLock
                        }
                        val talk = ChatResilience.classifySmallTalk(text)
                        if (talk != null) {
                            replyLocally(text, userMsgId, talk, connected = true)
                            return@withLock
                        }
                        enqueueChatTurn(text, userMsgId)
                        return@withLock
                    }

                    executeChatTurn(text, userMsgId, queuedBatch = false, batchIds = emptyList())
                } finally {
                    isDraining.set(false)
                    if (currentStreamJob == currentCoroutineContext()[Job]) {
                        currentStreamJob = null
                    }
                }
            }
        }
    }

    /**
     * Answers a greeting / thanks / "how are you" on the device, so social messages are never
     * met with silence when no model can be reached. Stores the user row (unless the caller
     * already did) and the reply, like any other turn.
     */
    private suspend fun replyLocally(
        text: String,
        userMsgId: String?,
        kind: ChatResilience.SmallTalkKind,
        connected: Boolean,
        alreadyStored: Boolean = false
    ) {
        val id = userMsgId ?: "u_${UUID.randomUUID()}"
        if (userMsgId == null && !alreadyStored) storage.insertRawMessage(id, "user", text)
        val now = clock.now()
        val hour = try {
            val rule = storage.getEffectiveDayRule(now)
            java.util.Calendar.getInstance(DayRollover.safeTimeZone(rule.tzId)).apply { timeInMillis = now }.get(java.util.Calendar.HOUR_OF_DAY)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            12
        }
        val name = storage.getSetting(CompanionActions.KEY_USER_NAME)
        val reply = ChatResilience.smallTalkReply(kind, name, connected, hour)
        val botId = "m_${UUID.randomUUID()}"
        storage.insertRawMessage(botId, "assistant", reply, causeMsgId = id)
        _state.update { it.copy(isChatLoading = false) }
        refreshUI()
        onBackgroundReplyCompleted?.invoke(reply)
    }

    /**
     * Blueprint 41 §3.1 `no_network`: "Network validated and the provider answers". Null means the
     * app layer has no connectivity oracle (core unit tests, and any caller that never installed
     * one), and the send is attempted — a wrong "online" costs one failed request, while a wrong
     * "offline" would queue a message that could have been answered now.
     */
    private fun networkAvailable(): Boolean = networkCheck?.invoke() ?: true

    /**
     * Blueprint 41 §3.3: records one `chat_turn` job for a user message. The payload holds the
     * message id, never a copy of the text (blueprint 41 §4: "ids and parameters, never copies of
     * private text beyond what the raw log holds"), and the idem key is the message id, so a
     * capture queued twice — the receiver, then the startup drain — is one job.
     */
    private suspend fun enqueueChatTurn(text: String, userMsgId: String?) {
        val id = userMsgId ?: "u_${UUID.randomUUID()}"
        // sendText's contract: the caller stored the row. A composer-style send with no row is
        // stored here, so a queued message is always readable back by its id at drain time.
        if (userMsgId == null) storage.insertRawMessage(id, "user", text)
        val now = clock.now()
        try {
            storage.enqueueOutbox(
                OutboxJob(
                    // Deterministic from the message id: the drain claims and completes the row by
                    // this same id, and a second enqueue of the same capture is the same row.
                    id = chatTurnJobId(id),
                    jobType = OutboxJob.JOB_CHAT_TURN,
                    priority = OUTBOX_PRIORITY_CHAT_TURN,
                    payloadJson = """{"msg_id":"$id"}""",
                    idemKey = id,
                    nextTryAt = now,
                    createdAt = now,
                    updatedAt = now
                )
            )
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            // The message is stored either way; saying it is queued when the queue write failed
            // would be a claim the next drain cannot keep.
            _state.update { it.copy(connectionError = "Could not queue your message: ${e.message ?: e.javaClass.simpleName}") }
            QuickCapture.notifyCaptureFailed(userMsgId)
            return
        }
        refreshUI()
    }

    /**
     * Rule-Based Offline Quick Add (v1.1 Item 6 / Blueprint 41 §3.6):
     * Immediately creates and schedules the task/reminder locally without an LLM when offline.
     */
    private suspend fun handleOfflineQuickAdd(
        quickAdd: com.anchor.core.capture.QuickAddResult,
        text: String,
        userMsgId: String?
    ) {
        val id = userMsgId ?: "u_${UUID.randomUUID()}"
        if (userMsgId == null) storage.insertRawMessage(id, "user", text)

        val nodeId = "n_${UUID.randomUUID()}"
        val now = clock.now()
        val node = com.anchor.core.models.Node(
            id = nodeId,
            type = "task",
            title = quickAdd.title,
            status = "active",
            createdAt = now,
            updatedAt = now,
            dueAt = quickAdd.dueAt
        )

        val reminder = quickAdd.dueAt?.let {
            Reminder(
                id = "r_$nodeId",
                text = quickAdd.title,
                fireAtMillis = it,
                nodeId = nodeId,
                tier = quickAdd.tier
            )
        }

        val afterJson = json.encodeToString(com.anchor.core.models.NodeStateSnapshot(node, reminder))
        val undoId = "u_${UUID.randomUUID()}"
        val event = com.anchor.core.models.RawEvent(
            opId = undoId,
            opType = com.anchor.core.models.IntentType.CREATE.name,
            targetType = "node",
            targetId = nodeId,
            afterJson = afterJson,
            tsUtc = now,
            causeMsgId = id
        )

        storage.createNodeAndCommit(node, event, reminder)

        if (reminder != null) {
            alarmScheduler.schedule(reminder)
        }

        val replyText = if (quickAdd.dueAt != null) {
            "Reminder scheduled: \"${quickAdd.title}\" for ${quickAdd.dueText ?: "scheduled time"}."
        } else {
            "Task added: \"${quickAdd.title}\"."
        }
        val botMsgId = "m_${UUID.randomUUID()}"
        storage.insertRawMessage(botMsgId, "assistant", replyText)

        val receipt = ReceiptState(
            id = UUID.randomUUID().toString(),
            message = if (quickAdd.dueAt != null) "Created: ${quickAdd.title} (${quickAdd.dueText})" else "Created: ${quickAdd.title}",
            undoToken = undoId,
            isError = false,
            timestamp = now
        )
        addTransientReceipt(receipt)
        _state.update { it.copy(isChatLoading = false) }
        refreshUI()
        onBackgroundReplyCompleted?.invoke(replyText)
    }

    /**
     * Blueprint 41 §3.5: answers everything that was queued while offline in **one** turn, then
     * clears the queue. Runs on start, on app open, and on the retry timer; there is no live
     * network callback (see D-138).
     *
     * The queued messages are stored rows, so the turn's pack already carries them in the order
     * the user wrote them; the instruction adds the thing the pack cannot say — that they were
     * written earlier, and when. Claiming a job re-enqueues it a minute out rather than marking it
     * `running`: the queued mark then survives a crash mid-turn, and a second drain in that minute
     * finds nothing due instead of sending the same messages twice.
     */
    suspend fun drainQueuedTurns() {
        if (!isDraining.compareAndSet(false, true)) return
        try {
            if (_state.value.isChatLoading || sendMutex.isLocked) {
                isDraining.set(false)
                return
            }
            if (!networkAvailable()) {
                isDraining.set(false)
                return
            }
            val key = storage.getSetting("key") ?: ""
            if (key.isBlank()) {
                isDraining.set(false)
                return
            }

            val now = clock.now()
            storage.dropStaleOutbox(now)
            val due = storage.getDueOutbox(OutboxJob.JOB_CHAT_TURN, now)
            if (due.isEmpty()) {
                isDraining.set(false)
                return
            }
            val validDue = mutableListOf<Pair<OutboxJob, String>>()
            for (job in due) {
                val msgId = queuedMsgId(job)
                if (msgId == null || !storage.hasRawMessage(msgId)) {
                    // Poison pill / deleted message defense: retire immediately so it never loops stuck
                    storage.markOutboxDone(job.id, now)
                } else {
                    validDue.add(job to msgId)
                }
            }
            if (validDue.isEmpty()) {
                isDraining.set(false)
                return
            }
            val ids = validDue.map { it.second }
            for ((job, _) in validDue) {
                storage.claimOutbox(job.id, now + QUEUE_CLAIM_MS, now)
            }

            // Set isChatLoading = true so the composer blocks concurrent sends and refreshUI preserves in-flight stream
            _state.update {
                it.copy(
                    connectionError = null,
                    providerKey = key,
                    providerEndpoint = storage.getSetting("ep") ?: "https://api.openai.com/v1/chat/completions",
                    providerModel = storage.getSetting("mod") ?: "gpt-4o-mini",
                    isChatLoading = true
                )
            }
            // The turn is asynchronous (handleSendMessage launches it), so this call cannot see its
            // outcome. It does not need to: the batch is marked done only once the assistant reply is
            // stored, which is the one moment "answered" is a fact (see markBatchAnswered).
            handleSendMessage(offlineBatchInstruction(ids, now), existingUserMsgId = null, queuedBatch = true, batchIds = ids)
        } catch (e: Exception) {
            isDraining.set(false)
            if (e is kotlinx.coroutines.CancellationException) throw e
            addTransientReceipt(ReceiptState(UUID.randomUUID().toString(), "Could not send saved messages: ${e.message ?: e.javaClass.simpleName}", null, isError = true, timestamp = clock.now()))
            refreshUI()
        }
    }

    /**
     * Blueprint 41 §3.3/§3.5: the queued messages have been answered — clear their jobs. Called
     * after the assistant reply for a queued batch is stored, never before: a turn that fails or
     * is cancelled leaves every job pending, so the next drain sends the words again rather than
     * dropping them.
     */
    private suspend fun markBatchAnswered(messageIds: List<String>) {
        val doneAt = clock.now()
        messageIds.forEach { storage.markOutboxDone(chatTurnJobId(it), doneAt) }
    }

    /**
     * Blueprint 41 §3.5.2/§3.5.3: the text that turns the stored offline messages into one turn.
     * The messages themselves are already in the pack (they are rows); this names them with their
     * original times and asks for one reply, and says how stale the oldest is so a 12-hour backlog
     * is acknowledged rather than answered as if it were fresh.
     */
    private suspend fun offlineBatchInstruction(ids: List<String>, now: Long): String {
        // One read for the whole batch: the rows the turn answers were written in the past, so a
        // "recent" window is the wrong way to find them — the oldest queued message of a long
        // offline stretch is exactly the one that falls out of a short tail.
        val history = storage.getRecentMessages(200)
        val times = ids.mapNotNull { id ->
            history.firstOrNull { it.id == id }?.tsUtc?.takeIf { it > 0 }
        }
        val stamps = times.joinToString(", ") { dateResolver.formatTimeOfDay(it) }
        val oldest = times.minOrNull()
        val staleHours = if (oldest != null) (now - oldest) / (3600L * 1000L) else 0L
        val stale = if (staleHours >= OFFLINE_STALE_HOURS) {
            " The oldest of these was written $staleHours hours ago, so begin by briefly acknowledging the delay."
        } else ""
        return "The user was offline and wrote ${ids.size} message(s) that could not be sent: sent offline, $stamps. " +
            "Answer them together in one reply, in the order they were written.$stale"
    }

    private fun chatTurnJobId(messageId: String): String = "ob_$messageId"

    /** The stored message id inside a `chat_turn` payload, or null when the row is unreadable. */
    private fun queuedMsgId(job: OutboxJob): String? = try {
        json.parseToJsonElement(job.payloadJson).let {
            (it as? kotlinx.serialization.json.JsonObject)?.get("msg_id")?.let { v -> (v as? kotlinx.serialization.json.JsonPrimitive)?.content }
        }
    } catch (e: Exception) {
        null
    }

    /**
     * @param queuedBatch true when [text] is the instruction that answers messages already queued
     *   in the outbox. The turn then stores no user row of its own (the queued rows are the user's
     *   words), and on success it clears exactly those jobs.
     */
    private fun handleSendMessage(
        text: String,
        existingUserMsgId: String? = null,
        queuedBatch: Boolean = false,
        batchIds: List<String> = emptyList()
    ) {
        coroutineScope.launch(defaultDispatcher) {
            sendMutex.withLock {
                currentStreamJob = currentCoroutineContext()[Job]
                workerAiJob?.cancel()
                try {
                    _state.update { it.copy(isChatLoading = true, connectionError = null) }
                    executeChatTurn(text, existingUserMsgId, queuedBatch, batchIds)
                } finally {
                    isDraining.set(false)
                    if (currentStreamJob == currentCoroutineContext()[Job]) {
                        currentStreamJob = null
                    }
                }
            }
        }
    }

    private fun isTransientProviderError(e: Throwable): Boolean = when (val root = (e as? AllProvidersFailedException)?.cause ?: e) {
        is HttpStatusException -> root.isTransient
        is Http429Exception, is ProviderTimeoutException -> true
        is java.io.IOException -> true
        else -> false
    }

    private suspend fun executeChatTurn(
        text: String,
        existingUserMsgId: String? = null,
        queuedBatch: Boolean = false,
        batchIds: List<String> = emptyList()
    ) {
        var activeUserMsgId: String? = existingUserMsgId
        var activeBatchIds: List<String> = emptyList()
        var anyLoopCommitted = false
        var turnTainted = false
        try {
            currentBotId = null
            // Read before the turn starts: a capture queued while this turn is streaming must
            // not be marked answered by a reply that was written without it.
            activeBatchIds = if (queuedBatch) {
                if (batchIds.isNotEmpty()) batchIds else storage.getPendingOutbox(OutboxJob.JOB_CHAT_TURN).mapNotNull { queuedMsgId(it) }
            } else emptyList()
            val userMsgId = existingUserMsgId ?: "u_${UUID.randomUUID()}"
            activeUserMsgId = userMsgId

            val cleanText = text.trim()
            if (storage.getOpenProposals().isEmpty() && TALK_LATER_REGEX.matches(cleanText)) {
                if (existingUserMsgId == null && !queuedBatch) {
                    storage.insertRawMessage(userMsgId, "user", text)
                }
                val recent6 = storage.getRecentMessages(6)
                val previousTurns = recent6.filter { it.id != userMsgId && !it.content.isNullOrBlank() }
                val lastTopic = previousTurns.lastOrNull()?.content?.take(100)?.replace("\n", " ")?.trim()

                val wakeupTime = clock.now() + 2 * 3600_000L
                val wakeupId = "aw_defer_${UUID.randomUUID().toString().take(6)}"
                val checkinReason = if (!lastTopic.isNullOrBlank()) {
                    "Earlier we were talking about: \"$lastTopic\" — you were busy then. Ready to pick that back up?"
                } else {
                    "Checking back in—are you free to chat now?"
                }
                val encodedTopic = kotlinx.serialization.json.Json.encodeToString(kotlinx.serialization.serializer(), lastTopic ?: "")
                val wakeup = com.anchor.core.models.AgentWakeup(
                    id = wakeupId,
                    fireAt = wakeupTime,
                    reason = checkinReason,
                    payloadJson = """{"type":"DEFERRED_CHECKIN","topic":$encodedTopic}""",
                    state = "pending",
                    createdAt = clock.now()
                )
                storage.saveAgentWakeup(wakeup)
                alarmScheduler.scheduleAgentWakeup(wakeupId, wakeupTime)

                val replyText = "Understood! I'll check back in with you in a couple of hours."
                val botMsgId = "m_${UUID.randomUUID()}"
                storage.insertRawMessage(botMsgId, "assistant", replyText)
                _state.update { it.copy(isChatLoading = false) }
                refreshUI()
                onBackgroundReplyCompleted?.invoke(replyText)
                return
            }

            val breakMatch = BREAK_REGEX.find(cleanText)
            if (breakMatch != null) {
                if (existingUserMsgId == null && !queuedBatch) {
                    storage.insertRawMessage(userMsgId, "user", text)
                }
                val mins = breakMatch.groupValues[1].toLongOrNull() ?: 15L
                val untilMs = clock.now() + mins * 60_000L
                storage.saveSetting("break.until_ms", untilMs.toString())

                val wakeupId = "aw_break_${UUID.randomUUID().toString().take(6)}"
                val reason = "Your $mins-minute break is up! Ready to jump back in?"
                val wakeup = com.anchor.core.models.AgentWakeup(
                    id = wakeupId,
                    fireAt = untilMs,
                    reason = reason,
                    payloadJson = """{"type":"BREAK_TIMER","mins":$mins}""",
                    state = "pending",
                    createdAt = clock.now()
                )
                storage.saveAgentWakeup(wakeup)
                alarmScheduler.scheduleAgentWakeup(wakeupId, untilMs)

                val replyText = "Enjoy your $mins-minute break! I'll ping you when it's time to dive back in."
                val botMsgId = "m_${UUID.randomUUID()}"
                storage.insertRawMessage(botMsgId, "assistant", replyText)
                _state.update { it.copy(isChatLoading = false) }
                refreshUI()
                onBackgroundReplyCompleted?.invoke(replyText)
                return
            }

            val onDeviceEnabled = storage.getSetting("provider.ondevice.enabled") == "1" || storage.getSetting("flag.ondevice_llm") == "1"
            if (!networkAvailable() && !queuedBatch) {
                val quickAdd = ruleBasedQuickAdd.parse(text)
                if (quickAdd != null) {
                    handleOfflineQuickAdd(quickAdd, text, existingUserMsgId)
                    return
                }
                if (!onDeviceEnabled) {
                    enqueueChatTurn(text, existingUserMsgId)
                    refreshUI()
                    return
                }
            }

                if (existingUserMsgId == null && !queuedBatch) {
                    storage.insertRawMessage(userMsgId, "user", text)
                }
                if (!queuedBatch) {
                    _state.update {
                        val msgs = if (it.chatMessages.none { m -> m.id == userMsgId }) {
                            it.chatMessages + ChatMessage(userMsgId, "user", text)
                        } else it.chatMessages
                        it.copy(chatMessages = msgs, chatItems = ChatTimelineBuilder.build(msgs, it.receipts, it.openProposals))
                    }
                    refreshUI()
                }

                gateway.aliasRegistry.clear()
                val contextLine = contextEngine?.formatNowContext()
                var pack = contextBuilder.buildPack(contextLine = contextLine)
                // Blueprint 41 §3.5: the queued messages are already rows in the pack. The
                // instruction that names them is this turn's own text, which the pack was built
                // before and so cannot carry; it goes last, after the newest history, as the turn.
                // Storing it as a row would put a user-style line in the raw log that they never
                // typed, and the next pack would read it back as something they said.
                if (queuedBatch) {
                    pack = pack + ChatMessage("u_${UUID.randomUUID()}", "user", text)
                }
                // WhatsApp-style reply: the quoted message goes in as context right before the turn.
                pendingReplyContext.remove(userMsgId)?.let { ref ->
                    val full = try { storage.getRawMessage(ref.id) } catch (e: Exception) { if (e is CancellationException) throw e; null }
                    val todayKey = DayRollover.computeDayKey(clock.now(), storage.getEffectiveDayRule(clock.now()))
                    val line = ChatMessage("ctx_reply_${ref.id}", "system", MessageAttrs.replyContext(ref, full, todayKey))
                    val at = pack.indexOfLast { it.id == userMsgId }
                    pack = if (at >= 0) pack.take(at) + line + pack.drop(at) else pack.dropLast(1) + line + pack.takeLast(1)
                }
                var turnComplete = false
                var loops = 0
                val executedCalls = mutableSetOf<String>()
                var emptyRetries = 0
                var trimmedForOverflow = false
                var lastSubstantiveReply: String? = null

                while (!turnComplete && loops < 4) {
                    loops++
                    val payload = buildJsonPayload(pack)
                    val rpm = storage.getSetting("provider.rpm_default")?.toIntOrNull() ?: 20
                    val chain = mutableListOf<Target>()
                    if (networkAvailable() && _state.value.providerKey.isNotBlank()) {
                        // One key, or a pool of up to ten rotated by load (KeyPool): each key is its
                        // own target, so a 429 on one moves the turn straight to the next.
                        chain.addAll(providerTargets(rpm, limiterPrefix = ""))
                    }
                    val fallbackEndpoint = storage.getSetting("provider.fallback.endpoint") ?: storage.getSetting("ep.fallback") ?: storage.getSetting("ep_fallback")
                    val fallbackKey = storage.getSetting("provider.fallback.key") ?: storage.getSetting("key.fallback") ?: storage.getSetting("key_fallback")
                    val fallbackRpm = storage.getSetting("provider.fallback.rpm")?.toIntOrNull() ?: rpm
                    val fallbackModel = storage.getSetting("provider.fallback.model") ?: storage.getSetting("model.fallback") ?: storage.getSetting("model_fallback")
                    if (networkAvailable() && !fallbackEndpoint.isNullOrBlank() && !fallbackKey.isNullOrBlank()) {
                        chain.add(Target(fallbackEndpoint, fallbackKey, fallbackRpm, model = fallbackModel))
                    }
                    val onDeviceEnabled = storage.getSetting("provider.ondevice.enabled") == "1" || storage.getSetting("flag.ondevice_llm") == "1"
                    if (onDeviceEnabled || chain.isEmpty()) {
                        chain.add(Target("local://ondevice", "local", 120))
                    }

                    val botId = "a_${UUID.randomUUID()}"
                    currentBotId = botId
                    val botResponse = java.lang.StringBuilder()
                    var lastUpdate = 0L

                    _state.update {
                        val msgs = it.chatMessages + ChatMessage(botId, "assistant", "")
                        it.copy(chatMessages = msgs, chatItems = ChatTimelineBuilder.build(msgs, it.receipts, it.openProposals))
                    }

                    try {
                        orchestrator.chat(chain, payload, envelopeMode = true).collect { event ->
                            if (event is StreamEvent.Clear) {
                                botResponse.clear()
                            } else if (event is StreamEvent.TextDelta) {
                                botResponse.append(event.text)
                                if (clock.now() - lastUpdate > 100) {
                                    // B7: Incremental streaming of JSON 'reply' field natively
                                    val currentText = botResponse.toString()
                                    val display = extractReplyFromJsonStream(currentText)
                                    if (display != null) {
                                        updateLastMessage(botId, display)
                                    }
                                    lastUpdate = clock.now()
                                }
                            }
                        }
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        // A prompt that outgrew the model's context is fixed by a shorter one, not by
                        // failing the turn: retry once with the newest history only.
                        if (!trimmedForOverflow && ChatResilience.isContextOverflow(e)) {
                            trimmedForOverflow = true
                            dropPlaceholder(botId)
                            pack = trimPackForOverflow(pack)
                            loops--
                            continue
                        }
                        throw e
                    }

                    // Flush any remaining characters immediately so nothing is dropped before tool execution
                    val finalStreamDisplay = extractReplyFromJsonStream(botResponse.toString())
                    if (finalStreamDisplay != null) {
                        updateLastMessage(botId, finalStreamDisplay)
                    }

                    val rawBot = botResponse.toString()
                    val envelope = EnvelopeParser.parse(rawBot)

                    if (envelope != null) {
                        var sysContext = ""
                        var hasCommitted = false
                        val turnReceipts = mutableListOf<ReceiptState>()
                        val turnErrors = mutableListOf<String>()
                        val toolResults = mutableListOf<ToolResult>()

                        envelope.calls.forEach { call ->
                            if (call.tool == "web_search") {
                                val query = (call.args["query"] ?: call.args["q"])?.toString()?.trim('"', ' ')
                                if (!query.isNullOrBlank()) {
                                    _state.update { it.copy(lastWebSearchQuery = query) }
                                }
                            }
                            val effectiveUserMessage = if (queuedBatch) {
                                if (activeBatchIds.isNotEmpty()) {
                                    storage.getRecentMessages(20)
                                        .filter { it.id in activeBatchIds }
                                        .mapNotNull { it.content }
                                        .joinToString(" ")
                                        .ifBlank { null }
                                } else null
                            } else text
                            val effectiveCauseMsgId = if (queuedBatch) activeBatchIds.lastOrNull() ?: userMsgId else userMsgId
                            val result = gateway.executeTool(
                                call,
                                executedCalls,
                                userMessage = effectiveUserMessage,
                                causeMsgId = effectiveCauseMsgId,
                                untrustedContentInTurn = turnTainted
                            )
                            toolResults.add(result)
                            val rawText = when(result) {
                                is ToolResult.Committed -> {
                                    hasCommitted = true
                                    anyLoopCommitted = true
                                    "Committed. ${result.message}"
                                }
                                is ToolResult.Ambiguous -> {
                                    turnComplete = true
                                    "Ambiguous. UI will ask user. ID: ${result.proposalId}"
                                }
                                is ToolResult.NeedsConfirm -> {
                                    turnComplete = true
                                    "Needs confirmation. UI will ask user. ID: ${result.proposalId}"
                                }
                                is ToolResult.NeedsTime -> {
                                    turnComplete = true
                                    "Ambiguous time. UI will ask user with candidate chips. ID: ${result.proposalId}"
                                }
                                is ToolResult.NotFound -> {
                                    turnReceipts.add(ReceiptState(UUID.randomUUID().toString(), "Not found: ${result.hint}", null, isError = true, causeMsgId = userMsgId))
                                    "Not Found."
                                }
                                is ToolResult.Data -> "Search Results:\n${result.text}"
                                is ToolResult.Error -> {
                                    val friendlyReason = when (result.reason) {
                                        "Proposal already resolved" -> "This choice has already been confirmed."
                                        "Node not found" -> "Item was not found or was deleted."
                                        "Node is no longer active" -> "Item is no longer active."
                                        "Invalid choice" -> "Invalid option selected."
                                        "Invalid time choice" -> "Invalid time selected."
                                        else -> result.reason
                                    }
                                    turnErrors.add(friendlyReason)
                                    turnReceipts.add(ReceiptState(UUID.randomUUID().toString(), friendlyReason, null, isError = true, causeMsgId = userMsgId))
                                    "Error: ${result.reason}"
                                }
                            }
                            // Strip/escape </data> to prevent premature delimiter breakout
                            val safeText = rawText.replace(Regex("(?i)</data>"), "")
                            sysContext += "Tool Result:\n<data>\n$safeText\n</data>\n"
                        }

                        turnReceipts.forEach { addTransientReceipt(it.copy(timestamp = clock.now())) }
                        refreshUI()

                        if (envelope.calls.any { it.tool == "web_search" } && toolResults.any { it is ToolResult.Data }) {
                            turnTainted = true
                        }

                        val hasData = toolResults.any { it is ToolResult.Data }
                        val hasQuestion = envelope.reply.contains('?')
                        if (envelope.calls.isEmpty() || hasCommitted || turnErrors.isNotEmpty()) {
                            if (!(hasData && !hasQuestion && turnErrors.isEmpty() && loops < 3)) {
                                turnComplete = true
                            }
                        }

                        // Stop storing model "Done!" prose when receipts already show the change
                        val rawReply = envelope.reply.trim()
                        val isGenericDone = rawReply.lowercase().removeSuffix(".").removeSuffix("!") in setOf("done", "saved", "added", "acknowledged", "ok")
                        val baseText = if (isGenericDone && (hasCommitted || anyLoopCommitted) && turnErrors.isEmpty()) {
                            "" // Suppress generic "Done!" when commit receipts exist
                        } else {
                            rawReply
                        }

                        val composedAssistantText = if (turnErrors.isNotEmpty()) {
                            if ((hasCommitted || anyLoopCommitted) && baseText.isNotBlank()) "$baseText\n\n(${turnErrors.joinToString("; ")})"
                            else turnErrors.joinToString("\n")
                        } else baseText

                        if (!ChatResilience.isProgressOnly(composedAssistantText)) lastSubstantiveReply = composedAssistantText

                        // A turn that did nothing and said nothing is a model hiccup, not an answer:
                        // ask once more, then fall back to a plain apology instead of silence.
                        val nothingHappened = composedAssistantText.isBlank() && !hasCommitted && !anyLoopCommitted &&
                            turnReceipts.isEmpty() && toolResults.none {
                                it is ToolResult.Ambiguous || it is ToolResult.NeedsConfirm || it is ToolResult.NeedsTime || it is ToolResult.Data
                            }
                        if (turnComplete && nothingHappened && emptyRetries == 0 && loops < 4) {
                            emptyRetries++
                            turnComplete = false
                            dropPlaceholder(botId)
                            pack = pack + ChatMessage(botId, "assistant", rawBot.trim().ifBlank { """{"reply":"","calls":[]}""" }) +
                                ChatMessage("sys_${UUID.randomUUID()}", "user", ChatResilience.EMPTY_REPLY_FEEDBACK)
                            continue
                        }
                        val finalAssistantText = if (turnComplete && nothingHappened) {
                            ChatResilience.classifySmallTalk(text)?.let { kind ->
                                ChatResilience.smallTalkReply(kind, storage.getSetting(CompanionActions.KEY_USER_NAME), true, 12)
                            } ?: ChatResilience.EMPTY_REPLY
                        } else composedAssistantText

                        if (turnComplete) {
                            // Avoid inserting empty placeholder messages if we purely generated receipts
                            if (finalAssistantText.isNotBlank()) {
                                updateLastMessage(botId, finalAssistantText)
                                val causeId = if (queuedBatch) activeBatchIds.lastOrNull() else userMsgId
                                storage.insertRawMessage(botId, "assistant", finalAssistantText, causeMsgId = causeId)
                                currentBotId = null
                                pack = pack + ChatMessage(botId, "assistant", finalAssistantText)
                                if (activeBatchIds.isNotEmpty()) markBatchAnswered(activeBatchIds)
                            } else {
                                _state.update {
                                    val msgs = it.chatMessages.dropLast(1)
                                    it.copy(chatMessages = msgs, chatItems = ChatTimelineBuilder.build(msgs, it.receipts, it.openProposals))
                                }
                                currentBotId = null
                                if ((hasCommitted || anyLoopCommitted) && activeBatchIds.isNotEmpty()) markBatchAnswered(activeBatchIds)
                            }
                            val replySnippet = when {
                                finalAssistantText.isNotBlank() -> finalAssistantText
                                turnReceipts.isNotEmpty() -> turnReceipts.joinToString("; ") { it.message }
                                else -> "Action completed"
                            }
                            onBackgroundReplyCompleted?.invoke(replySnippet)
                            triggerWorkerAiDebounced()
                        } else {
                            // Multi-step tool loop: intermediate turn continuing to next loop.
                            // Do not insert intermediate assistant bubble into storage raw_message.
                            // Always append an assistant message to pack so the subsequent system feedback
                            // ("user" role) never causes two consecutive user messages in strict APIs.
                            val intermediateAssistantText = if (finalAssistantText.isNotBlank()) {
                                updateLastMessage(botId, finalAssistantText)
                                currentBotId = null
                                finalAssistantText
                            } else {
                                _state.update {
                                    val msgs = it.chatMessages.dropLast(1)
                                    it.copy(chatMessages = msgs, chatItems = ChatTimelineBuilder.build(msgs, it.receipts, it.openProposals))
                                }
                                currentBotId = null
                                rawBot.trim().ifBlank { """{"reply":"","calls":[]}""" }
                            }
                            pack = pack + ChatMessage(botId, "assistant", intermediateAssistantText)
                        }

                        if (sysContext.isNotBlank()) {
                            pack = pack + ChatMessage("sys_${UUID.randomUUID()}", "user", "System feedback: $sysContext")
                        }

                        // SEC 6: Use unique ID to prevent PK violations when loop limit is hit without completing
                        if (!turnComplete && loops >= 4) {
                            val limitMsg = lastSubstantiveReply?.takeIf { it.isNotBlank() } ?: ChatResilience.LOOP_LIMIT_REPLY
                            val limitId = "a_${UUID.randomUUID()}"
                            _state.update {
                                val msgs = it.chatMessages + ChatMessage(limitId, "assistant", limitMsg)
                                it.copy(chatMessages = msgs, chatItems = ChatTimelineBuilder.build(msgs, it.receipts, it.openProposals))
                            }
                            val causeId = if (queuedBatch) activeBatchIds.lastOrNull() else userMsgId
                            storage.insertRawMessage(limitId, "assistant", limitMsg, causeMsgId = causeId)
                            currentBotId = null
                            if (activeBatchIds.isNotEmpty()) markBatchAnswered(activeBatchIds)
                            onBackgroundReplyCompleted?.invoke(limitMsg)
                            turnComplete = true
                        }
                    } else {
                        // O-3: a reply with no envelope performed no action. If it reads like a
                        // confirmation ("Setting an alarm…", "Reminder set.") the user is told a
                        // reminder exists that does not — show what actually happened instead.
                        val claimsAction = ACTION_CLAIM_RE.containsMatchIn(rawBot)
                        if (claimsAction && loops < 2) {
                            // Silent 1-turn auto-retry: instruct model to emit the valid ToolEnvelope
                            pack = pack + ChatMessage("sys_${UUID.randomUUID()}", "user", "System feedback: You stated you would set an alarm or reminder, but you emitted no tool call. Output the valid JSON ToolEnvelope with a propose_change or create call now.")
                            continue
                        }

                        if (rawBot.isBlank() && emptyRetries == 0 && loops < 4) {
                            emptyRetries++
                            dropPlaceholder(botId)
                            pack = pack + ChatMessage("sys_${UUID.randomUUID()}", "user", ChatResilience.EMPTY_REPLY_FEEDBACK)
                            continue
                        }
                        val extractedProse = if (rawBot.trim().startsWith("{")) extractReplyFromJsonStream(rawBot) else null
                        val coverUp = if (claimsAction && !queuedBatch) ruleBasedQuickAdd.parse(text) else null

                        if (claimsAction && coverUp != null) {
                            // Cover up: rule-based parser creates and schedules the task cleanly (Blueprint 41 / Mandate M1)
                            val nodeId = "n_${UUID.randomUUID()}"
                            val now = clock.now()
                            val node = com.anchor.core.models.Node(
                                id = nodeId,
                                type = "task",
                                title = coverUp.title,
                                status = "active",
                                createdAt = now,
                                updatedAt = now,
                                dueAt = coverUp.dueAt
                            )
                            val reminder = coverUp.dueAt?.let {
                                Reminder(
                                    id = "r_$nodeId",
                                    text = coverUp.title,
                                    fireAtMillis = it,
                                    nodeId = nodeId,
                                    tier = coverUp.tier
                                )
                            }
                            val afterJson = json.encodeToString(com.anchor.core.models.NodeStateSnapshot(node, reminder))
                            val undoId = "u_${UUID.randomUUID()}"
                            val event = com.anchor.core.models.RawEvent(
                                opId = undoId,
                                opType = com.anchor.core.models.IntentType.CREATE.name,
                                targetType = "node",
                                targetId = nodeId,
                                afterJson = afterJson,
                                tsUtc = now,
                                causeMsgId = userMsgId
                            )
                            storage.createNodeAndCommit(node, event, reminder)
                            if (reminder != null) {
                                alarmScheduler.schedule(reminder)
                            }
                            addTransientReceipt(
                                ReceiptState(
                                    id = undoId,
                                    message = if (coverUp.dueAt != null) "Created: ${coverUp.title} (${coverUp.dueText})" else "Created: ${coverUp.title}",
                                    undoToken = undoId,
                                    isError = false,
                                    causeMsgId = userMsgId,
                                    timestamp = now
                                )
                            )
                            val formattedDue = coverUp.dueAt?.let { coverUp.dueText ?: dateResolver.format(it) }
                            val finalTxt = if (formattedDue != null) {
                                "Created: ${coverUp.title} — $formattedDue (offline parser)"
                            } else {
                                "Created: ${coverUp.title} (offline parser)"
                            }
                            updateLastMessage(botId, finalTxt)
                            val causeId = if (queuedBatch) activeBatchIds.lastOrNull() else userMsgId
                            storage.insertRawMessage(botId, "assistant", finalTxt, causeMsgId = causeId)
                            currentBotId = null
                            if (activeBatchIds.isNotEmpty()) markBatchAnswered(activeBatchIds)
                            onBackgroundReplyCompleted?.invoke(finalTxt)
                            turnComplete = true
                        } else {
                            val finalTxt = when {
                                claimsAction -> "I couldn't turn that into a reminder, so nothing was saved. Please try again."
                                !extractedProse.isNullOrBlank() -> extractedProse
                                rawBot.isBlank() -> ChatResilience.classifySmallTalk(text)?.let { kind ->
                                    ChatResilience.smallTalkReply(kind, storage.getSetting(CompanionActions.KEY_USER_NAME), true, 12)
                                } ?: ChatResilience.EMPTY_REPLY
                                else -> rawBot
                            }
                            if (claimsAction) {
                                addTransientReceipt(
                                    ReceiptState(
                                        UUID.randomUUID().toString(),
                                        "No reminder was created — the assistant did not issue an action.",
                                        null,
                                        isError = true,
                                        causeMsgId = userMsgId,
                                        timestamp = clock.now()
                                    )
                                )
                            }
                            updateLastMessage(botId, finalTxt)
                            val causeId = if (queuedBatch) activeBatchIds.lastOrNull() else userMsgId
                            storage.insertRawMessage(botId, "assistant", finalTxt, causeMsgId = causeId)
                            currentBotId = null
                            if (activeBatchIds.isNotEmpty()) {
                                if (!claimsAction) {
                                    markBatchAnswered(activeBatchIds)
                                } else {
                                    val now = clock.now()
                                    val pendingJobs = storage.getPendingOutbox(OutboxJob.JOB_CHAT_TURN)
                                    activeBatchIds.forEach { msgId ->
                                        val jobId = chatTurnJobId(msgId)
                                        val job = pendingJobs.find { it.id == jobId }
                                        val attempts = (job?.attempts ?: 0) + 1
                                        val backoffMs = when (attempts) {
                                            1 -> 60_000L
                                            2 -> 120_000L
                                            3 -> 300_000L
                                            4 -> 600_000L
                                            else -> 1_800_000L
                                        }
                                        storage.markOutboxFailed(jobId, now, now + backoffMs, "Model claimed action without envelope")
                                    }
                                }
                            }
                            onBackgroundReplyCompleted?.invoke(finalTxt)
                            turnComplete = true
                        }
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) {
                    // A9 & A-01: Must use NonCancellable to persist partial message while coroutine is tearing down
                    withContext(NonCancellable) {
                        try {
                            val activeBotId = currentBotId
                            currentBotId = null
                            if (activeBotId != null) {
                                val msgs = _state.value.chatMessages
                                val target = msgs.find { it.id == activeBotId }
                                val content = target?.content
                                val alreadyInStorage = storage.hasRawMessage(activeBotId)
                                if (!alreadyInStorage && !content.isNullOrBlank() && content.trim() != "Thinking..." && !content.contains("[Interrupted]")) {
                                    val intContent = content + " [Interrupted]"
                                    updateLastMessage(activeBotId, intContent)
                                    val causeId = if (queuedBatch) activeBatchIds.lastOrNull() else activeUserMsgId
                                    storage.insertRawMessage(activeBotId, "assistant", intContent, causeMsgId = causeId)
                                } else if (!alreadyInStorage) {
                                    _state.update { state ->
                                        state.copy(chatMessages = state.chatMessages.filter { it.id != activeBotId })
                                    }
                                }
                            }
                        } catch (ignored: Throwable) {
                            // Storage or UI update failure during teardown must not suppress CancellationException
                        }
                    }
                    throw e
                }
                if (anyLoopCommitted) {
                    val partialReply = "Done — your changes are saved. I couldn't finish replying, though: ${ChatResilience.describe(e).message}"
                    val botMsgId = currentBotId ?: "m_${UUID.randomUUID()}"
                    currentBotId = null
                    updateLastMessage(botMsgId, partialReply)
                    val causeId = if (queuedBatch) activeBatchIds.lastOrNull() else activeUserMsgId
                    storage.insertRawMessage(botMsgId, "assistant", partialReply, causeMsgId = causeId)
                    if (queuedBatch && activeBatchIds.isNotEmpty()) {
                        markBatchAnswered(activeBatchIds)
                    }
                    _state.update { it.copy(connectionError = null) }
                    return
                }
                // Blueprint 41 §2: an offline state is not an unhandled error — messages are either
                // handled immediately via RuleBasedQuickAdd or queued safely in outbox.
                if (e is OfflineException) {
                    _state.update { it.copy(connectionError = null) }
                    if (!queuedBatch && activeUserMsgId != null) {
                        val quickAdd = ruleBasedQuickAdd.parse(text)
                        if (quickAdd != null) {
                            handleOfflineQuickAdd(quickAdd, text, activeUserMsgId)
                        } else {
                            enqueueChatTurn(text, activeUserMsgId)
                        }
                    }
                    return
                }
                // A10: Provider error refreshes UI properly below in finally
                val friendly = ChatResilience.describe(e)
                val willQueue = !queuedBatch && activeUserMsgId != null && isTransientProviderError(e)
                _state.update {
                    it.copy(
                        connectionError = friendly.message,
                        connectionErrorRetryable = friendly.retryable && !willQueue,
                        connectionErrorNeedsSettings = friendly.needsSettings,
                        lastFailedMsgId = if (!queuedBatch && !willQueue) activeUserMsgId else it.lastFailedMsgId
                    )
                }
                // Small talk still gets a human reply when the provider is down; the banner above
                // explains the outage. Real questions keep their place so Retry can answer them.
                if (!queuedBatch && !willQueue && activeUserMsgId != null) {
                    ChatResilience.classifySmallTalk(text)?.let { kind ->
                        try {
                            val botIdToDrop = currentBotId
                            if (botIdToDrop != null) dropPlaceholder(botIdToDrop)
                            currentBotId = null
                            replyLocally(text, activeUserMsgId, kind, connected = true, alreadyStored = true)
                            _state.update { st -> st.copy(lastFailedMsgId = null, connectionErrorRetryable = false) }
                        } catch (inner: Exception) {
                            if (inner is CancellationException) throw inner
                        }
                    }
                }
                if (queuedBatch && activeBatchIds.isNotEmpty() && e !is OfflineException) {
                    val now = clock.now()
                    val errText = e.message ?: e.javaClass.simpleName
                    val pendingJobs = storage.getPendingOutbox(OutboxJob.JOB_CHAT_TURN)
                    val isTransient = isTransientProviderError(e)
                    activeBatchIds.forEach { msgId ->
                        val jobId = chatTurnJobId(msgId)
                        val job = pendingJobs.find { it.id == jobId }
                        val attempts = (job?.attempts ?: 0) + 1
                        if (attempts >= 5 || !isTransient) {
                            storage.markOutboxFailed(jobId, now, now + 86400000L, errText, status = OutboxJob.STATUS_FAILED)
                        } else {
                            val backoffMs = when (attempts) {
                                1 -> 60_000L
                                2 -> 120_000L
                                3 -> 300_000L
                                4 -> 600_000L
                                else -> 1_800_000L
                            }
                            storage.markOutboxFailed(jobId, now, now + backoffMs, errText, status = OutboxJob.STATUS_PENDING)
                        }
                    }
                } else if (!queuedBatch && activeUserMsgId != null && e !is OfflineException) {
                    // Fallback to outbox retry on transient network/5xx/429 errors only, NOT on 400/401/403 configuration errors
                    if (isTransientProviderError(e)) {
                        enqueueChatTurn(text, activeUserMsgId)
                    }
                }
                // E-17: a capture whose turn never ran stays unanswered in the raw log, so the
                // next start retries it. Tell the notification that showed "Sending..." now.
                if (existingUserMsgId != null && !(e is OfflineException)) {
                    QuickCapture.notifyCaptureFailed(existingUserMsgId)
                }
                val activeBotId = currentBotId
                currentBotId = null
                if (activeBotId != null) {
                    _state.update { state ->
                        state.copy(chatMessages = state.chatMessages.filter { it.id != activeBotId })
                    }
                }
            } finally {
                currentBotId = null
                _state.update { it.copy(isChatLoading = false) }
                refreshUI()
            }
    }

    /** Removes the streaming placeholder bubble [botId] from the visible timeline. */
    private fun dropPlaceholder(botId: String) {
        _state.update { st ->
            val msgs = st.chatMessages.filter { it.id != botId }
            st.copy(chatMessages = msgs, chatItems = ChatTimelineBuilder.build(msgs, st.receipts, st.openProposals))
        }
        if (currentBotId == botId) currentBotId = null
    }

    /**
     * The pack after a context-overflow refusal: the system prompt capped, and only the newest
     * few messages (each capped) kept. The live user turn is always the last message.
     */
    private fun trimPackForOverflow(pack: List<ChatMessage>): List<ChatMessage> {
        val system = pack.firstOrNull { it.role == "system" }
        val rest = pack.filter { it.role != "system" && !it.content.isNullOrBlank() }.takeLast(OVERFLOW_KEEP_MESSAGES)
        val cappedSystem = system?.let { it.copy(content = ChatResilience.capForModel(it.content.orEmpty(), OVERFLOW_SYSTEM_CHARS)) }
        return listOfNotNull(cappedSystem) + rest.map { it.copy(content = ChatResilience.capForModel(it.content.orEmpty(), OVERFLOW_MESSAGE_CHARS)) }
    }

    private suspend fun buildJsonPayload(pack: List<ChatMessage>, temperatureOverride: Double? = null): String {
        // TB-5: Tool instructions and rules are unified into ContextBuilder's single system message.
        // Exactly one system message is emitted per turn.
        // Coalesce consecutive same-role non-system messages to satisfy strict LLM alternating role requirements.
        val rawMessages = pack.filter { !it.content.isNullOrBlank() }.map {
            val cap = if (it.role == "system") MAX_SYSTEM_CHARS else MAX_MESSAGE_CHARS
            com.anchor.core.provider.RequestMessage(it.role, ChatResilience.capForModel(it.content!!, cap))
        }
        val messages = mutableListOf<com.anchor.core.provider.RequestMessage>()
        for (msg in rawMessages) {
            if (messages.isNotEmpty() && msg.role != "system" && messages.last().role == msg.role) {
                val previous = messages.removeAt(messages.lastIndex)
                messages.add(com.anchor.core.provider.RequestMessage(msg.role, "${previous.content}\n\n${msg.content}"))
            } else {
                messages.add(msg)
            }
        }
        val temp = temperatureOverride ?: (storage.getSetting("ai.temperature.chat")?.toDoubleOrNull() ?: 0.7)
        val maxTokens = storage.getSetting("ai.max_tokens")?.toIntOrNull() ?: 1200
        return kotlinx.serialization.json.Json.encodeToString(
            com.anchor.core.provider.ChatRequest(
                _state.value.providerModel,
                true,
                messages,
                temperature = temp,
                max_tokens = maxTokens
            )
        )
    }

    private fun updateLastMessage(id: String, content: String) {
        // B9: Use the thread-safe StateFlow.update {} transaction block to prevent
        // concurrent typing inputs from being overwritten by incoming LLM network chunks.
        _state.update { curr ->
            val msgs = curr.chatMessages.toMutableList()
            val idx = msgs.indexOfLast { it.id == id }
            if (idx != -1) msgs[idx] = ChatMessage(id, "assistant", content)
            curr.copy(
                chatMessages = msgs,
                chatItems = ChatTimelineBuilder.build(msgs, curr.receipts, curr.openProposals)
            )
        }
    }

    private suspend fun reconcileAlarms(force: Boolean = false) {
        val health = alarmScheduler.checkHealth()
        val results = reconciler.reconcile(force)
        val failures = results.filterIsInstance<ScheduleResult.Failed>()
        val failure = failures.firstOrNull() ?: (health as? ScheduleResult.Failed)
        val failCount = failures.size + if (health is ScheduleResult.Failed) 1 else 0
        if (health is ScheduleResult.Failed) {
            val currentState = storage.getReliabilityState()
            // B3: write the SAME structured {error, time} format AlarmReconciler uses
            val existing = AlarmReconciler.parseIssues(currentState.issuesJson)
            if (existing.none { it.error == health.reason }) {
                val updated = (existing + ReliabilityIssue(health.reason, clock.now()))
                    .sortedByDescending { it.time }
                    .take(5)
                storage.saveReliabilityState(currentState.copy(score = "at_risk", issuesJson = AlarmReconciler.encodeIssues(updated)))
            }
        }
        _state.update {
            it.copy(
                schedulingError = failure?.reason,
                schedulingFailedCount = failCount,
                // H-01 (R-01): a miss is reported, not armed. Reconcile dedupes on the fire instance,
                // so this list is rebuilt from the current pass rather than accumulated.
                missedReminders = results.filterIsInstance<ScheduleResult.Overdue>()
            )
        }
    }

    private fun publishActionResult(d: ActionResultDraft, tag: String?) {
        val r = ActionResult(
            seq = actionSeq.incrementAndGet(),
            tag = tag,
            message = d.message,
            undoToken = d.undoToken,
            isError = d.isError,
            needsChoice = d.needsChoice,
            targetId = d.targetId
        )
        _state.update { it.copy(lastActionResult = r) }
    }

    /**
     * The companion UI's extra reads: open items, living notes and their links, armed wakeups,
     * the user's profile and notification-reading settings. Isolated so one failing read can
     * never stop the chat state from refreshing.
     */
    private suspend fun loadCompanionExtras() {
        try {
            val now = clock.now()
            val nodes = storage.getActiveNodes(COMPANION_NODE_LIMIT)
            val notes = storage.getAllTopicNotes()
            val refs = notes.flatMap { n ->
                try { storage.getReferencesForNote(n.id) } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    emptyList()
                }
            }
            val wakeups = storage.getPendingAgentWakeups(Long.MAX_VALUE)
            val rule = storage.getEffectiveDayRule(now)
            val prefKeys = PROFILE_PREF_KEYS
            val prefs = buildMap {
                for (k in prefKeys) storage.getSetting(k)?.let { put(k, it) }
                for (n in notes) storage.getSetting("note.mode.${n.id}")?.let { put("note.mode.${n.id}", it) }
                // How many NVIDIA keys Whisper can use, never the keys themselves.
                put("ambient.stt.nim_key_count", KeyPool.decode(storage.getSetting("ambient.stt.nim_keys")).size.toString())
            }
            val profile = UserProfile(
                name = storage.getSetting(CompanionActions.KEY_USER_NAME).orEmpty(),
                languages = storage.getSetting(CompanionActions.KEY_LANGUAGES)?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.ifEmpty { null } ?: listOf("English"),
                dayStartMinute = rule.startMinute,
                dayEndMinute = rule.endMinute,
                breakUntilMs = storage.getSetting(CompanionActions.KEY_BREAK_UNTIL)?.toLongOrNull()?.takeIf { it > now },
                nudgesPausedUntilMs = storage.getSetting(CompanionActions.KEY_NUDGES_PAUSED)?.toLongOrNull()?.takeIf { it > now },
                prefs = prefs
            )
            val dayCounts = try {
                storage.getHeatMapCounts(
                    DayRollover.computeDayKey(now - 60L * 86_400_000L, rule),
                    DayRollover.computeDayKey(now, rule)
                )
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                emptyMap()
            }
            val readMap = buildMap {
                for (k in com.anchor.core.notifications.NotificationReadSettings.ALL_KEYS) storage.getSetting(k)?.let { put(k, it) }
            }
            val reading = com.anchor.core.notifications.NotificationReadSettings.fromSettings { readMap[it] }
            _state.update {
                it.copy(
                    activeNodes = nodes,
                    topicNotes = notes,
                    noteReferences = refs,
                    pendingWakeups = wakeups,
                    profile = profile,
                    notificationReading = reading,
                    chatDayCounts = dayCounts
                )
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
        }
    }

    private suspend fun refreshUI() {
        reconcileAlarms(force = false)
        val currentSize = _state.value.chatMessages.size
        val fetchCount = kotlin.math.max(30, currentSize)

        val proposals = storage.getOpenProposals()
        val soulFacts = storage.getProposedSoulFacts()
        val stale = storage.getStaleNodes(now = clock.now())
        val recentReceipts = loadReceipts()
        val nowMs = clock.now()
        val priorityList = storage.getHomePriorityList(nowMs)
        val closedNodes = storage.getRecentlyClosed(nowMs)
        val dayMs = 24L * 3600 * 1000L
        val undoneOpIds = storage.getUndoneOpIds(nowMs - dayMs)
        val recentlyClosed = closedNodes.map { node ->
            val event = storage.getLatestEventForTarget("node", node.id)
            val undoValid = event != null && (nowMs - event.tsUtc < dayMs) && !undoneOpIds.contains(event.opId)
            RecentlyClosed(node, undoValid, if (undoValid) event.opId else null)
        }
        val proactive = storage.getProactiveState()
        val activeThread = proactive.openThreadMsgId?.let { ActiveThread(it, proactive.threadOpenedAt ?: 0L) }
        val relState = storage.getReliabilityState()
        val allPrefs = storage.getAllLearnedPrefs()
        val latestReport = storage.getLatestAmbientReport()
        val reportItems = latestReport?.let { r -> storage.getAmbientReportItems(r.id) } ?: emptyList()
        val ambEnabled = storage.getSetting("ambient.enabled") == "1"
        val vpEnrolled = storage.getVoiceprint() != null

        val currentBot = currentBotId?.let { bId -> _state.value.chatMessages.find { m -> m.id == bId } }
        val recentMessages = storage.getRecentMessages(fetchCount)
        val resolvedMessages = if (_state.value.isChatLoading) {
            if (currentBot != null && recentMessages.none { m -> m.id == currentBot.id }) {
                recentMessages + currentBot
            } else if (recentMessages.isNotEmpty()) {
                recentMessages
            } else {
                _state.value.chatMessages
            }
        } else {
            recentMessages
        }

        val pKey = _state.value.providerKey
        val cooldownExp = if (pKey.isNotBlank()) rateLimiter?.getCooldownExpiry(pKey) else null

        _state.update { it.copy(
            receipts = (recentReceipts + currentTransientReceipts()).distinctBy { it.undoToken ?: it.id },
            chatMessages = resolvedMessages,
            activeReminders = storage.getActiveReminders(clock.now()),
            openProposals = proposals,
            proposedSoulFacts = soulFacts,
            staleItems = stale,
            inboxCount = proposals.size + soulFacts.size + stale.size,
            urgentItems = storage.getUrgentNodes(),
            atRisk = relState.score == "at_risk",
            activeThread = activeThread,
            homePriorityList = priorityList,
            recentlyClosed = recentlyClosed,
            queuedChatIds = queuedChatIds(),
            learnedPrefs = allPrefs,
            latestAmbientReport = latestReport,
            ambientReportItems = reportItems,
            ambientEnabled = ambEnabled,
            voiceprintEnrolled = vpEnrolled,
            postCallNudgesEnabled = (storage.getSetting("flag.call_nudge") == "1" || storage.getSetting("flag.call_nudges") == "1") && storage.getSetting("callnudge.enabled") != "0",
            onDeviceFallbackEnabled = storage.getSetting("provider.ondevice.enabled") == "1" || storage.getSetting("flag.ondevice_llm") == "1",
            moodTrackingEnabled = storage.getSetting("flag.mood_tracking") == "1",
            hideNotificationContent = storage.getSetting("privacy.hide_notification_content") == "1" || storage.getSetting("flag.hide_notification_content") == "1",
            soulAutoAcceptMode = storage.getSetting("soul.mode") == "auto" || storage.getSetting("soul.auto_accept") == "1",
            fallbackEndpoint = storage.getSetting("provider.fallback.endpoint") ?: storage.getSetting("ep.fallback") ?: storage.getSetting("ep_fallback") ?: "",
            fallbackKey = storage.getSetting("provider.fallback.key") ?: storage.getSetting("key.fallback") ?: storage.getSetting("key_fallback") ?: "",
            fallbackModel = storage.getSetting("provider.fallback.model") ?: storage.getSetting("model.fallback") ?: storage.getSetting("model_fallback") ?: "",
            providerCooldownExpiry = cooldownExp,
            autoBackupEnabled = storage.getSetting("export.auto.enabled")?.let { it == "1" || it.toBooleanStrictOrNull() == true } ?: (storage.getSetting("backup.auto_enabled") == "1"),
            autoBackupUri = storage.getSetting("export.auto.folder_uri") ?: storage.getSetting("backup.auto_uri") ?: "",
            contextSensingEnabled = (storage.getSetting("flag.sensing") == "1") && (storage.getSetting("sensing.enabled") == "1"),
            webSearchEnabled = (storage.getSetting("web_search.enabled")?.let { it == "1" } ?: (it.webSearchEnabled || gateway.webSearchEnabled)).also { gateway.webSearchEnabled = it }
        ) }
        _state.update { it.copy(chatItems = ChatTimelineBuilder.build(it.chatMessages, it.receipts, it.openProposals)) }
        loadCompanionExtras()
    }

    /**
     * Blueprint 41 §2/§3.3: which stored user messages are still waiting in the `outbox`. Blueprint
     * 11:83 forbids a delivery column on `raw_message`, so the queued mark is derived from the
     * pending `chat_turn` rows on every refresh — the queue is the only truth.
     */
    private suspend fun queuedChatIds(): List<String> = try {
        storage.getPendingOutbox(OutboxJob.JOB_CHAT_TURN).mapNotNull { queuedMsgId(it) }
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        emptyList()
    }

    /**
     * Blueprint 02 & Blueprint 20 §3.8: Debounced 30-second Worker AI background pipeline.
     * Extracts implicit tasks, reflected Soul facts, and pattern observations in the background
     * without blocking user interaction or conversational turns.
     */
    internal fun triggerWorkerAiDebounced(debounceMs: Long = 30_000L) {
        workerAiJob?.cancel()
        workerAiJob = workerScope.launch {
            try {
                delay(debounceMs)
                runWorkerAiExtraction()
            } catch (_: CancellationException) {
                // Expected on cancellation or new user input
            } catch (_: Throwable) {
                // Background worker errors must never affect chat operations
            }
        }
    }

    suspend fun runWorkerAiExtraction() {
        try {
            val autoExtract = storage.getSetting("memory.auto_extract") != "0"
            if (!autoExtract) return

            val sameAsChat = (storage.getSetting("worker.same_as_chat") ?: storage.getSetting("slot.worker.same_as_chat")) != "0"
            val workerTemp = storage.getSetting("ai.temperature.worker")?.toDoubleOrNull() ?: 0.3
            val lastExtractedId = storage.getSetting("worker.last_extracted_msg_id") ?: ""
            val lastExtractedTs = storage.getSetting("worker.last_extracted_ts_utc")?.toLongOrNull() ?: 0L
            var maxExtractedTs = lastExtractedTs
            val recentEvents = storage.getRecentEvents(100)
            val causeMsgIds = recentEvents.mapNotNull { it.causeMsgId }.toSet()

            // Reflect Soul facts from closed tasks and habits (combine active + proposed to avoid duplicate proposed spam)
            val closedNodes = storage.getNodesClosedBetween(
                startMs = clock.now() - 7 * 86400 * 1000L,
                endMs = clock.now()
            )
            val existingFacts = storage.getActiveSoulFacts(includeLocalOnly = true) + storage.getProposedSoulFacts()
            val consolidation = com.anchor.core.consolidation.ConsolidationEngine()
            val candidates = consolidation.reflectSoulPatterns(closedNodes, existingFacts, clock.now())

            var factsAdded = false
            for (cand in candidates) {
                if (storage.isSoulFactSuppressed(cand.statement)) continue
                val fact = consolidation.toProposedSoulFact(cand, clock.now())
                val uId = "u_${UUID.randomUUID()}"
                val event = RawEvent(
                    opId = uId,
                    opType = "PROPOSE",
                    targetType = "soul_fact",
                    targetId = fact.id,
                    afterJson = null,
                    tsUtc = clock.now()
                )
                storage.createSoulFactAndCommit(fact, event)
                factsAdded = true
            }

            if (factsAdded) {
                refreshUI()
            }

            // Companion Brain / Living Notes extraction:
            val recentMsgs = storage.getRecentMessages(10)
            for (msg in recentMsgs) {
                if (msg.role != "user" || msg.content.isNullOrBlank()) continue
                if (msg.id == lastExtractedId || (msg.tsUtc > 0 && msg.tsUtc <= lastExtractedTs) || msg.id in causeMsgIds) continue
                val text = msg.content
                val lines = text.lines().map { it.trim() }.filter { it.isNotBlank() }
                val bulletLines = lines.filter { it.startsWith("-") || it.startsWith("*") || it.matches(Regex("""^\d+[\.)]\s+.*""")) }
                if (bulletLines.size >= 2) {
                    val firstLine = lines.first()
                    val topic = if (!firstLine.startsWith("-") && !firstLine.startsWith("*") && !firstLine.matches(Regex("""^\d+[\.)]\s+.*"""))) {
                        firstLine.removeSuffix(":").take(50).trim()
                    } else {
                        "General Notes"
                    }
                    val bullets = bulletLines.map {
                        it.removePrefix("-").removePrefix("*").replace(Regex("""^\d+[\.)]\s*"""), "").trim()
                    }.filter { it.isNotBlank() }

                    if (topic.isNotBlank() && bullets.isNotEmpty()) {
                        val existing = storage.getTopicNoteByTopic(topic)
                        val merged = if (existing != null) {
                            val prev = try {
                                json.decodeFromString<List<String>>(existing.bulletsJson)
                            } catch (_: Exception) {
                                existing.content.lines().map { it.removePrefix("-").trim() }
                            }
                            (prev + bullets).distinct()
                        } else bullets.distinct()

                        val note = TopicNote(
                            id = existing?.id ?: "tn_${UUID.randomUUID().toString().take(8)}",
                            topic = topic,
                            content = merged.joinToString("\n") { "- $it" },
                            bulletsJson = json.encodeToString(merged),
                            pinned = existing?.pinned ?: false,
                            lastEditedAt = clock.now(),
                            createdAt = existing?.createdAt ?: clock.now()
                        )
                        storage.saveTopicNote(note)
                        if (msg.tsUtc > maxExtractedTs) maxExtractedTs = msg.tsUtc
                        storage.saveSetting("worker.last_extracted_msg_id", msg.id)
                        storage.saveSetting("worker.last_extracted_ts_utc", maxExtractedTs.toString())

                        // Check if an active task matches this topic and link reference
                        val activeNodes = storage.getActiveNodes(50)
                        val topicTokens = topic.lowercase().split(Regex("""\s+""")).filter { it.length > 3 }
                        val matchingNode = activeNodes.find { node ->
                            val nodeTokens = node.title.lowercase().split(Regex("""\s+""")).toSet()
                            topicTokens.any { it in nodeTokens } ||
                                node.title.contains(topic, ignoreCase = true) ||
                                topic.contains(node.title, ignoreCase = true)
                        }
                        if (matchingNode != null) {
                            val existingRefs = storage.getReferencesForNode(matchingNode.id)
                            if (existingRefs.none { it.noteId == note.id }) {
                                val ref = NoteReference(
                                    id = "nr_${UUID.randomUUID().toString().take(8)}",
                                    noteId = note.id,
                                    nodeId = matchingNode.id,
                                    createdAt = clock.now()
                                )
                                storage.linkNoteReference(ref)
                            }
                        }
                    }
                }
            }

            // Worker AI LLM Natural Language Reflection
            val workerEp = if (sameAsChat) _state.value.providerEndpoint else (storage.getSetting("ep.worker") ?: storage.getSetting("provider.worker.endpoint") ?: _state.value.providerEndpoint)
            val workerKey = if (sameAsChat) _state.value.providerKey else (storage.getSetting("key.worker") ?: storage.getSetting("provider.worker.key") ?: _state.value.providerKey)

            if ((networkCheck?.invoke() != false) && workerKey.isNotBlank()) {
                if (rateLimiter?.getCooldownExpiry(workerKey) != null) {
                    return
                }
                val recent6 = storage.getRecentMessages(6)
                val latestUserMsg = recent6.lastOrNull { it.role == "user" }
                val isSubstantive = (latestUserMsg?.content?.length ?: 0) >= 25 &&
                    latestUserMsg?.id != lastExtractedId &&
                    (latestUserMsg?.tsUtc ?: 0L) > lastExtractedTs &&
                    latestUserMsg?.id !in causeMsgIds
                if (isSubstantive) {
                    val systemPrompt = """
You are Mine's background Worker AI. Analyze the recent conversation between user and assistant.
Extract any worthy structured knowledge from natural language:
- Topic Note bullets (`upsert_topic_note` with topic, bullets, pinned) for checklists, plans, meeting takeaways, or study lists.
- Note-to-Task links (`link_note_reference` with note_id, node_id, mode="read_write").
- Opportunistic Errands (`propose_change` with intent="CREATE", title="...", context_trigger="ON_LEAVE_HOME").
- Long-term Soul facts (`soul_propose` with statement, category).
If the exchange contains only transient chit-chat, return empty calls.
Respond ONLY in JSON envelope: {"reply":"","calls":[...]}
                    """.trimIndent()

                    val workerMessages = mutableListOf<ChatMessage>()
                    workerMessages.add(ChatMessage("sys_worker", "system", systemPrompt))
                    recent6.forEach {
                        workerMessages.add(ChatMessage(it.id, it.role, it.content ?: ""))
                    }

                    val workerPayload = buildJsonPayload(workerMessages, temperatureOverride = workerTemp)
                    val rpm = storage.getSetting("provider.rpm_default")?.toIntOrNull() ?: 20
                    val workerModel = storage.getSetting("provider.worker.model") ?: storage.getSetting("model.worker") ?: _state.value.providerModel
                    val chain = if (workerKey == _state.value.providerKey && workerEp == _state.value.providerEndpoint) {
                        providerTargets(rpm, limiterPrefix = "bg:", model = workerModel)
                    } else {
                        listOf(Target(workerEp, workerKey, rpm, model = workerModel, limiterKey = "bg:$workerKey"))
                    }

                    val responseBuffer = StringBuilder()
                    orchestrator.chat(chain, workerPayload, envelopeMode = true).collect { event ->
                        if (event is com.anchor.core.provider.StreamEvent.TextDelta) {
                            responseBuffer.append(event.text)
                        }
                    }

                    val rawEnvelope = responseBuffer.toString()
                    val envelope = EnvelopeParser.parse(rawEnvelope)
                    if (envelope != null && envelope.calls.isNotEmpty()) {
                        val executedCalls = mutableSetOf<String>()
                        for (call in envelope.calls) {
                            val intentContent = (call.args["intent"]?.jsonPrimitive?.contentOrNull ?: call.args["intent"]?.toString()?.trim('"', ' '))
                            val isAllowed = call.tool in setOf("upsert_topic_note", "link_note_reference", "soul_propose") ||
                                (call.tool == "propose_change" && intentContent?.equals("CREATE", ignoreCase = true) == true)
                            if (isAllowed) {
                                gateway.executeTool(call, executedCalls)
                            }
                        }
                        refreshUI()
                    }
                    if (latestUserMsg != null) {
                        if (latestUserMsg.tsUtc > maxExtractedTs) maxExtractedTs = latestUserMsg.tsUtc
                        storage.saveSetting("worker.last_extracted_msg_id", latestUserMsg.id)
                        storage.saveSetting("worker.last_extracted_ts_utc", maxExtractedTs.toString())
                    }
                }
            }
        } catch (_: Throwable) {}
    }

    companion object {
        /**
         * Row 20: how many Items the read-only viewer loads. The list is a browse surface, not a
         * report — a page this size is far past what one person scrolls, and the same cap the
         * paged Home helper uses per page.
         */
        const val MEMORY_VIEWER_PAGE = 200
        /** Per-message caps so one giant paste or note can never make the request itself fail. */
        const val MAX_MESSAGE_CHARS = 24_000
        const val MAX_SYSTEM_CHARS = 48_000
        const val OVERFLOW_KEEP_MESSAGES = 6
        const val PROVIDER_TEST_TIMEOUT_MS = 20_000L
        const val OVERFLOW_MESSAGE_CHARS = 6_000
        const val OVERFLOW_SYSTEM_CHARS = 14_000
        /** Open items surfaced to the companion UI (Home, Memory, Board). */
        const val COMPANION_NODE_LIMIT = 300
        /** Settings exposed to the UI as preferences. */
        val PROFILE_PREF_KEYS = listOf(
            "ui.reduced_motion", "ui.dev_mode", "chat.reasoning", "voice.replies",
            "consent.accepted", "ui.onboarding_fast_path", "ambient.enabled", "notif_read.onboarded",
            // Listening: speech-to-text route, AI extraction, tuned thresholds, enrolment quality.
            "ambient.stt.mode", "ambient.extract_with_ai", "ambient.t_lo", "ambient.t_hi", "ambient.voiceprint_quality",
            "ambient.gate_user_presence", "ambient.stt.language",
            // About you (onboarding): the details the user chose to share.
            *ProfileFields.ALL_KEYS.toTypedArray()
        )

        /**
         * Blueprint 31: how many open items one proactive pass scans for an overdue candidate.
         * Bounded so a tick is a fixed cost regardless of how much the user has stored; the scan
         * is ordered by due time, so the oldest overdue item is inside the first page.
         */
        const val NUDGE_SCAN_PAGE = 200

        // Blueprint 52 §3.1: onboarding progress lives in `setting` (blueprint 14 §3.8), not a new
        // table — the flow is four steps and a flag, and a table would need a schema bump for it.
        const val ONBOARDING_STEP_KEY = "onboarding.step"
        const val ONBOARDING_DONE_KEY = "onboarding.done"
        const val ONBOARDING_LAST_STEP = 3
        const val ONBOARDING_SOUL_STEP = 3

        /**
         * Blueprint 52 §3.2: the user typed these answers in setup, which is stronger evidence than
         * anything the AI infers later, so they start above the model's default confidence.
         */
        const val ONBOARDING_FACT_CONFIDENCE = 0.9

        // Blueprint 41 §3.3: `chat_turn` is P0 — the user's own words outrank every background job.
        const val OUTBOX_PRIORITY_CHAT_TURN = 0

        /**
         * How far out a claimed job is re-enqueued while its turn is in flight. Claiming this way
         * rather than flipping a `running` status keeps the queued mark true if the process dies
         * mid-turn, and makes a second drain inside the window find nothing due instead of sending
         * the same messages twice.
         */
        const val QUEUE_CLAIM_MS = 60_000L
        /** Extra API keys for the main provider, one per line (see KeyPool). */
        const val KEY_EXTRA_KEYS = "provider.extra_keys"

        /** Blueprint 41 §3.5.3: at or past this age a backlog is acknowledged before it is answered. */
        const val OFFLINE_STALE_HOURS = 12L

        // O-3: prose that reads as a completed action. A reply matching this with no envelope
        // performed nothing, so it must not be shown as a confirmation. Deliberately excludes
        // questions ("For what?") and offers ("Shall I set…"), which are honest non-actions.
        internal val ACTION_CLAIM_RE = Regex(
            """(?i)(""" +
                """\bsetting (an? )?(alarm|reminder|notification)\b|""" +
                """\b(alarm|reminder|notification) (is )?(now )?set\b|""" +
                """\b(i'?ve|i have|i'm|i am)\s+(set|scheduled|created|added|saved)\b|""" +
                """\bi set (an? )?(alarm|reminder|task)\b|""" +
                """\b(all\s+set|all\s+done)[.!]?\s*$|""" +
                """^(done|saved|created|scheduled)[.!]?\s*$""" +
                """)"""
        )

        private val REPLY_KEY_REGEX = Regex(""""reply"\s*:\s*"""")

        internal val TALK_LATER_REGEX = Regex("""(?i)^\s*(talk\s+later|not\s+now|busy(\s+right\s+now)?|another\s+time|ping\s+me\s+later|check\s+in\s+later)\s*[.!]?\s*$""")

        internal val BREAK_REGEX = Regex("""(?i)\b(?:i(?:'m|\s+am)?\s+)?(?:taking|start(?:ing)?|on)\s+a\s+(\d{1,3})\s*(?:min|minute|mins|minutes)\s+break\b""")

        internal fun extractReplyFromJsonStream(jsonStream: String): String? {
            // Find the LAST occurrence of "reply": in case a failed failover stream was prepended
            val match = REPLY_KEY_REGEX.findAll(jsonStream).lastOrNull()
            if (match == null) {
                val trimmed = jsonStream.trim()
                if (trimmed.startsWith("{") || trimmed.isEmpty()) {
                    return null
                }
                return jsonStream
            }

            val textStart = match.range.last + 1
            var end = jsonStream.indexOf("\"", textStart)
            while (end != -1 && jsonStream[end - 1] == '\\') {
                end = jsonStream.indexOf("\"", end + 1)
            }

            val text = if (end == -1) {
                jsonStream.substring(textStart).replace("\\\"", "\"").replace("\\n", "\n")
            } else {
                jsonStream.substring(textStart, end).replace("\\\"", "\"").replace("\\n", "\n")
            }
            return if (text.isEmpty()) null else text
        }
    }
}
