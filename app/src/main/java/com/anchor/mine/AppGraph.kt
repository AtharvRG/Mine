// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.mine

import android.app.Application
import com.anchor.core.context.ContextBuilder
import com.anchor.core.gateway.Gateway
import com.anchor.core.nudge.CheckinBuilder
import com.anchor.core.provider.ProviderOrchestrator
import com.anchor.core.provider.RateLimiter
import com.anchor.core.provider.StorageRateLimitPersistence
import com.anchor.core.reliability.ReliabilityManager
import com.anchor.core.resolver.Resolver
import com.anchor.core.retrieval.HybridRetriever
import com.anchor.core.storage.Storage
import com.anchor.core.time.DateResolver
import com.anchor.core.time.SystemClock
import com.anchor.core.ui.ChatController
import com.anchor.mine.alarms.AndroidAlarmScheduler
import com.anchor.mine.provider.AndroidHttpTransport
import com.anchor.mine.provider.AndroidOnDeviceLlmEngine
import com.anchor.mine.provider.ConnectivityOracle
import com.anchor.mine.retrieval.LiteRtEmbedder
import com.anchor.mine.search.SafeWebSearchEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * P1-01 (Patch C): Singleton Application Process Graph.
 *
 * Guarantees exactly one ChatController, Gateway, Orchestrator, and Retriever per process.
 * Survives Activity rotations and recreations without rebuilding state or duplicating controllers.
 */
class AppGraph(
    val application: Application,
    val storage: Storage,
    val appScope: CoroutineScope
) {
    val clock = SystemClock()
    val is24HourFormat = { android.text.format.DateFormat.is24HourFormat(application) }
    val dateResolver = DateResolver(clock, is24HourFormat)

    val rateLimiter = RateLimiter(
        clock = { System.currentTimeMillis() },
        persistence = StorageRateLimitPersistence(storage),
        monotonicClock = { android.os.SystemClock.elapsedRealtime() }
    )

    val onDeviceEngine = AndroidOnDeviceLlmEngine(application)
    val orchestrator = ProviderOrchestrator(
        limiter = rateLimiter,
        transport = AndroidHttpTransport(),
        onDeviceEngine = onDeviceEngine
    )

    val embedder = LiteRtEmbedder(application)
    val hybridRetriever = HybridRetriever(storage, embedder, clock, dateResolver)
    val scheduler = AndroidAlarmScheduler(application)
    val webSearchEngine = SafeWebSearchEngine()
    val gateway = Gateway(
        storage = storage,
        resolver = Resolver(),
        dateResolver = dateResolver,
        clock = clock,
        hybridRetriever = hybridRetriever,
        embedder = embedder,
        alarmScheduler = scheduler,
        webSearchEngine = webSearchEngine
    )

    val checkinBuilder = CheckinBuilder(storage, clock, dateResolver)
    val contextBuilder = ContextBuilder(storage, clock, dateResolver, checkinBuilder)
    val connectivity = ConnectivityOracle(application)

    val chatController: ChatController = ChatController(
        storage = storage,
        orchestrator = orchestrator,
        contextBuilder = contextBuilder,
        gateway = gateway,
        alarmScheduler = scheduler,
        coroutineScope = appScope,
        defaultDispatcher = Dispatchers.Default,
        clock = clock,
        rateLimiter = rateLimiter,
        dateResolver = dateResolver,
        networkCheck = { connectivity.isOnline() },
        embedder = embedder,
        contextEngine = (application as? MineApp)?.contextEngine
    ).apply {
        onBackgroundReplyCompleted = { reply ->
            if (com.anchor.mine.lifecycle.AppVisibilityTracker.isAppInBackground) {
                com.anchor.mine.notifications.ChatReplyNotifier.notify(application, reply)
            }
        }
        onContextSensingChanged = {
            (application as? MineApp)?.contextSensingManager?.checkAndApplySettings()
        }
        onPostCallNudgesChanged = { enabled ->
            com.anchor.mine.nudge.CallStateReceiver.setCallReceiverEnabled(application, enabled)
        }
    }

    val reliabilityManager: ReliabilityManager = ReliabilityManager(
        storage = storage,
        clock = clock,
        onAutoExportTriggered = {
            val app = application as? MineApp
            val uriStr = storage.getSetting(com.anchor.core.export.AutoExportScheduler.KEY_FOLDER_URI) ?: storage.getSetting("backup.auto_uri")
            val passphrase = com.anchor.mine.export.BackupPassphraseVault.getPassphrase(application)
                ?: storage.getSetting("export.passphrase")
                ?: storage.getSetting("backup.passphrase")
            if (!uriStr.isNullOrBlank() && !passphrase.isNullOrBlank() && app != null) {
                app.applicationScope.launch(Dispatchers.IO) {
                    val tempFile = java.io.File(application.cacheDir, "auto_backup_${clock.now()}.mnex")
                    try {
                        val exportMgr = app.sharedExportManager.await()
                        exportMgr.exportTo(tempFile, passphrase, includeApiKeys = false, includeEmbeddings = false)
                        val treeUri = android.net.Uri.parse(uriStr)
                        val docFolder = androidx.documentfile.provider.DocumentFile.fromTreeUri(application, treeUri)
                        if (docFolder != null && docFolder.canWrite()) {
                            val fileName = "mine_backup_${clock.now()}.mnex"
                            val newDoc = docFolder.createFile("application/octet-stream", fileName)
                            if (newDoc != null) {
                                application.contentResolver.openOutputStream(newDoc.uri)?.use { outStream ->
                                    tempFile.inputStream().use { inStream -> inStream.copyTo(outStream) }
                                }
                                reliabilityManager.autoExportScheduler.recordExportSuccess(clock.now())

                                // P1-9: Prune excess backups in SAF folder to retention limit (5 files)
                                val existingFiles = docFolder.listFiles()
                                    .filter { it.name?.endsWith(".mnex") == true }
                                    .map { Pair(it, it.lastModified()) }
                                val toPrune = com.anchor.core.export.AutoExportScheduler.findFilesToPrune(existingFiles, retentionCount = 5)
                                for ((fileToPrune, _) in toPrune) {
                                    try {
                                        fileToPrune.delete()
                                    } catch (_: Exception) {}
                                }
                            }
                        }
                    } catch (t: Throwable) {
                        android.util.Log.w("AppGraph", "Auto-export failed: ${t.message}", t)
                    } finally {
                        tempFile.delete()
                    }
                }
            }
        }
    )

    init {
        // P1-16: Drain offline turn queue immediately upon network reconnection
        try {
            val cm = application.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
            if (cm != null) {
                var reconnectJob: kotlinx.coroutines.Job? = null
                cm.registerDefaultNetworkCallback(object : android.net.ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: android.net.Network) {
                        reconnectJob?.cancel()
                        reconnectJob = appScope.launch {
                            kotlinx.coroutines.delay(2000L) // 2s debounce
                            chatController.onAction(com.anchor.core.ui.UiAction.RetryNow)
                        }
                    }
                })
            }
        } catch (t: Throwable) {
            android.util.Log.w("AppGraph", "Failed to register network callback: ${t.message}")
        }
    }
}
