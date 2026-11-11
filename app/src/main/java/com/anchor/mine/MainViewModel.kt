// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.mine

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.anchor.core.context.ContextBuilder
import com.anchor.core.gateway.Gateway
import com.anchor.core.models.Reminder
import com.anchor.core.provider.ProviderOrchestrator
import com.anchor.core.provider.RateLimiter
import com.anchor.core.reliability.ReliabilityManager
import com.anchor.core.resolver.Resolver
import com.anchor.core.storage.Storage
import com.anchor.core.time.DateResolver
import com.anchor.core.time.SystemClock
import com.anchor.core.ui.ChatController
import com.anchor.core.ui.UiAction
import com.anchor.core.ui.UiState
import com.anchor.mine.alarms.AlarmReceiver
import com.anchor.mine.alarms.AndroidAlarmScheduler
import com.anchor.mine.provider.AndroidHttpTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var controller: ChatController? = null
    private val pendingActions = java.util.concurrent.ConcurrentLinkedQueue<UiAction>()
    private lateinit var reliabilityManager: ReliabilityManager
    var storage: Storage? = null
        private set

    init {
        viewModelScope.launch {
            try {
                val app = application as MineApp
                val loadedStorage = app.sharedStorage.await() // Can throw if keys/DB corrupt
                storage = loadedStorage
                val rateLimiter = com.anchor.core.provider.RateLimiter(
                    clock = { System.currentTimeMillis() },
                    persistence = com.anchor.core.provider.StorageRateLimitPersistence(loadedStorage),
                    monotonicClock = { android.os.SystemClock.elapsedRealtime() }
                )
                val orchestrator = com.anchor.core.provider.ProviderOrchestrator(rateLimiter, com.anchor.mine.provider.AndroidHttpTransport())
                val clock = SystemClock()
                val is24HourFormat = { android.text.format.DateFormat.is24HourFormat(application) }
                val dateResolver = DateResolver(clock, is24HourFormat)
                val embedder = com.anchor.mine.retrieval.LiteRtEmbedder(application)
                val hybridRetriever = com.anchor.core.retrieval.HybridRetriever(loadedStorage, embedder, clock, dateResolver)
                val gateway = Gateway(loadedStorage, Resolver(), dateResolver, clock, hybridRetriever, embedder)
                reliabilityManager = ReliabilityManager(
                    loadedStorage, clock,
                    onAutoExportTriggered = {
                        val uriStr = loadedStorage.getSetting(com.anchor.core.export.AutoExportScheduler.KEY_FOLDER_URI)
                        val passphrase = loadedStorage.getSetting("export.passphrase") ?: loadedStorage.getSetting("backup.passphrase")
                        if (!uriStr.isNullOrBlank() && !passphrase.isNullOrBlank()) {
                            val tempFile = java.io.File(application.cacheDir, "auto_backup_${clock.now()}.minebackup")
                            try {
                                val exportMgr = app.sharedExportManager.await()
                                exportMgr.exportTo(tempFile, passphrase, includeApiKeys = false, includeEmbeddings = false)

                                val treeUri = android.net.Uri.parse(uriStr)
                                val docFolder = androidx.documentfile.provider.DocumentFile.fromTreeUri(application, treeUri)
                                if (docFolder != null && docFolder.canWrite()) {
                                    val fileName = "mine_backup_${clock.now()}.minebackup"
                                    val newDoc = docFolder.createFile("application/octet-stream", fileName)
                                    if (newDoc != null) {
                                        application.contentResolver.openOutputStream(newDoc.uri)?.use { outStream ->
                                            tempFile.inputStream().use { inStream ->
                                                inStream.copyTo(outStream)
                                            }
                                        }
                                        reliabilityManager.autoExportScheduler.recordExportSuccess(clock.now())

                                        // Prune expired backups in user's SAF folder
                                        val existingBackups: List<Pair<androidx.documentfile.provider.DocumentFile, Long>> = docFolder.listFiles()
                                            .filter { it.name?.endsWith(".minebackup") == true && it.isFile }
                                            .mapNotNull { doc ->
                                                val name = doc.name ?: return@mapNotNull null
                                                val ts = name.removePrefix("mine_backup_").removeSuffix(".minebackup").toLongOrNull() ?: doc.lastModified()
                                                Pair(doc, ts)
                                            }
                                        val retentionCount = loadedStorage.getSetting(com.anchor.core.export.AutoExportScheduler.KEY_RETENTION_COUNT)?.toIntOrNull()
                                            ?: com.anchor.core.export.AutoExportScheduler.DEFAULT_RETENTION_COUNT
                                        val toPrune = com.anchor.core.export.AutoExportScheduler.findFilesToPrune(existingBackups, retentionCount)
                                        toPrune.forEach { (doc, _) ->
                                            try { doc.delete() } catch (_: Throwable) {}
                                        }
                                    }
                                }
                            } catch (e: Throwable) {
                                android.util.Log.w("MainViewModel", "Auto-export failed: ${e.message}")
                            } finally {
                                try { tempFile.delete() } catch (_: Throwable) {}
                            }
                        } else {
                            android.util.Log.i("MainViewModel", "Auto-export skipped: missing SAF URI or passphrase")
                        }
                    }
                )

                val checkinBuilder = com.anchor.core.nudge.CheckinBuilder(loadedStorage, clock, dateResolver)
                val contextBuilder = ContextBuilder(loadedStorage, clock, dateResolver, checkinBuilder)
                
                // Blueprint 41 §3.1: the controller decides offline *before* a request, so an
                // airplane-mode capture is instant instead of a ten-second connect timeout.
                val connectivity = com.anchor.mine.provider.ConnectivityOracle(application)
                controller = ChatController(
                    loadedStorage, orchestrator, contextBuilder,
                    gateway, AndroidAlarmScheduler(application),
                    viewModelScope, Dispatchers.Default, clock,
                    dateResolver = dateResolver,
                    networkCheck = { connectivity.isOnline() },
                    embedder = embedder,
                    contextEngine = app.contextEngine
                )
                // Blueprint 50 §3.14: the notification reply needs a controller when one exists.
                app.captureController = controller

                // Drain any actions that arrived before the controller was initialized (e.g. cold-start intents)
                while (true) {
                    val pending = pendingActions.poll() ?: break
                    controller?.onAction(pending)
                }

                reliabilityManager.runSweeper().forEach { missedRem ->
                    val intent = android.content.Intent(getApplication(), com.anchor.mine.alarms.AlarmReceiver::class.java).apply {
                        putExtra("EXTRA_ID", missedRem.id)
                        putExtra("EXTRA_TEXT", missedRem.text)
                        putExtra("EXTRA_TIER", missedRem.tier.name)
                        putExtra("EXTRA_MISSED", true)
                        putExtra("EXTRA_SCHEDULED_FOR", missedRem.fireAtMillis)
                    }
                    getApplication<Application>().sendBroadcast(intent)
                }

                // E-17: a capture stored while the app was dead (or whose turn failed) is still
                // unanswered in the raw log. Send each one now, oldest first. The captured row is
                // the user turn, so nothing is duplicated; an answered capture is not in this list.
                //
                // Every capture is sent, not just the first: a turn's reply is an assistant row
                // that answers *every* capture before it, so sending only the oldest would mark
                // the rest answered without ever showing them to the model. sendText queues each
                // behind the previous one (a turn already streaming is not dropped, it is waited
                // for), so the replies arrive in the order the user wrote them.
                for (captureId in com.anchor.core.capture.QuickCapture.pending(loadedStorage)) {
                    val text = loadedStorage.getRawMessage(captureId)
                    if (!text.isNullOrBlank()) controller!!.sendText(text, captureId)
                }

                // H-11 (R-15): a denied POST_NOTIFICATIONS is not an error in the controller, so
                // the banner is derived here rather than left to the controller's state. It is
                // re-read on every controller emission and on every app resume (refreshNotification
                // Blocked), because the user can change the grant in system settings while this
                // ViewModel is alive.
                _state.update { it.copy(notificationsBlocked = notificationsBlocked()) }
                controller!!.state.collect { s -> _state.value = s.copy(notificationsBlocked = notificationsBlocked()) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                // H-06 (R-11): an Error here used to escape as an uncaught coroutine exception and
                // leave the user on a blank screen with no banner and no reset path.
                // H-11: keep the notifications flag too — the startup-failure screen is exactly
                // where a user may go to settings to fix things, and must come back to a correct
                // banner.
                _state.value = _state.value.copy(
                    globalError = startupFailureMessage(e),
                    notificationsBlocked = notificationsBlocked()
                )
            }
        }
    }

    /**
     * H-11 (R-15): re-read the notification grant. Call from the Activity's `onResume` — the user
     * may have returned from system settings after allowing (or revoking) notifications, and no
     * controller emission happens for that.
     */
    fun refreshNotificationsBlocked() {
        val blocked = notificationsBlocked()
        if (_state.value.notificationsBlocked != blocked) {
            _state.update { it.copy(notificationsBlocked = blocked) }
        }
    }

    /** H-11 (R-15): true when reminders are armed but cannot alert the user. */
    private fun notificationsBlocked(): Boolean =
        !androidx.core.app.NotificationManagerCompat.from(getApplication()).areNotificationsEnabled()

    fun onAction(action: UiAction) {
        if (action is UiAction.PromptResetDatabase) {
            _state.value = _state.value.copy(showResetConfirm = true)
            return
        }
        if (action is UiAction.ConfirmResetDatabase) {
            if (action.confirm) {
                val ctx = getApplication<Application>().applicationContext
                ctx.deleteDatabase("mine_encrypted.db")
                java.io.File(ctx.filesDir, "wrapped_db.key").delete()
                android.os.Process.killProcess(android.os.Process.myPid())
            } else {
                _state.value = _state.value.copy(showResetConfirm = false)
            }
            return
        }
        val c = controller
        if (c != null) {
            c.onAction(action)
        } else {
            pendingActions.add(action)
        }
    }
}
