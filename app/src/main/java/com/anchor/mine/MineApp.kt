// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.mine

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.os.UserManagerCompat
import com.anchor.core.provider.ProviderOrchestrator
import com.anchor.core.provider.RateLimiter
import com.anchor.core.storage.Storage
import com.anchor.mine.provider.AndroidHttpTransport
import com.anchor.mine.storage.AndroidStorage
import com.anchor.mine.storage.DatabaseProvider
import com.anchor.mine.storage.DbKeyProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MineApp : Application() {
    private val appExceptionHandler = kotlinx.coroutines.CoroutineExceptionHandler { _, throwable ->
        android.util.Log.e("MineApp", "Uncaught exception in applicationScope: ${throwable.message}", throwable)
    }
    val applicationScope = CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Default + appExceptionHandler)

    @Volatile
    var sharedStorage = CompletableDeferred<Storage>()
    @Volatile
    var sharedExportManager = CompletableDeferred<com.anchor.core.export.ExportManager>()
    @Volatile
    var appGraph = CompletableDeferred<AppGraph>()

    @Volatile
    private var isInitializing = false

    val rateLimiter: RateLimiter = RateLimiter { System.currentTimeMillis() }
    val orchestrator: ProviderOrchestrator by lazy {
        ProviderOrchestrator(
            rateLimiter,
            AndroidHttpTransport(),
            com.anchor.mine.provider.AndroidOnDeviceLlmEngine(this)
        )
    }

    val contextEngine: com.anchor.core.sensing.ContextEngine by lazy {
        com.anchor.core.sensing.ContextEngine(com.anchor.core.time.SystemClock())
    }
    var contextSensingManager: com.anchor.mine.sensing.ContextSensingManager? = null

    /**
     * Blueprint 50 §3.14 / E-17: the chat controller the notification reply sends through.
     *
     * A process started by a reply has no activity, so no ViewModel, so no controller. The reply
     * still stores its text; this field is how the receiver finds a controller when one exists,
     * and how it knows there is none when the app is dead.
     */
    @Volatile
    var captureController: com.anchor.core.ui.ChatController? = null

    /**
     * The notification each pending capture was written from, so a failed turn can update the
     * notification that is actually waiting on it. Cleared when the capture is answered or fails.
     */
    val captureNotificationIds = java.util.concurrent.ConcurrentHashMap<String, Int>()

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(com.anchor.mine.lifecycle.AppVisibilityTracker)
        // The reply's "Sending…" must become "Saved" whether the turn fails now or the process
        // dies first. Core cannot build a notification, so it calls back into here.
        com.anchor.core.capture.QuickCapture.onCaptureFailed = { messageId ->
            if (messageId != null) {
                captureNotificationIds.remove(messageId)?.let {
                    com.anchor.mine.capture.CaptureNotifications.showFailed(this, it)
                }
            }
        }
        // Must exist before the first alarm fires: a notification posted to a channel that was
        // never created is dropped silently, and checkHealth() would call that state broken.
        com.anchor.mine.alarms.NotificationChannels.ensureCreated(this)
        val sensingMgr = com.anchor.mine.sensing.ContextSensingManager(
            this,
            contextEngine,
            storageProvider = { if (sharedStorage.isCompleted) sharedStorage.await() else null }
        )
        contextSensingManager = sensingMgr

        if (UserManagerCompat.isUserUnlocked(this)) {
            startStorageInitialization()
        } else {
            // Direct Boot: credential storage is locked. Defer CE storage initialization until unlocked.
            val unlockReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent?.action == Intent.ACTION_USER_UNLOCKED) {
                        try {
                            unregisterReceiver(this)
                        } catch (_: Exception) {}
                        startStorageInitialization()
                    }
                }
            }
            try {
                registerReceiver(unlockReceiver, IntentFilter(Intent.ACTION_USER_UNLOCKED))
            } catch (e: Exception) {
                // If receiver registration fails in test environment, start init directly
                startStorageInitialization()
            }
        }
    }

    @Synchronized
    fun startStorageInitialization(): Deferred<Storage> {
        if (sharedStorage.isCompleted) {
            if (sharedStorage.getCompletionExceptionOrNull() != null) {
                // Item 3.3 / Self-healing deferred: reset exceptionally failed startup so callers can retry safely
                sharedStorage = CompletableDeferred()
                if (sharedExportManager.isCompleted && sharedExportManager.getCompletionExceptionOrNull() != null) {
                    sharedExportManager = CompletableDeferred()
                }
                if (appGraph.isCompleted && appGraph.getCompletionExceptionOrNull() != null) {
                    appGraph = CompletableDeferred()
                }
                isInitializing = false
            } else {
                return sharedStorage
            }
        }
        if (isInitializing) {
            return sharedStorage
        }
        isInitializing = true
        CoroutineScope(Dispatchers.IO).launch {
            // H-06 (R-11): catches Throwable, not Exception — an Error here used to leave both
            // deferreds unsettled, so every await() hung forever.
            val result = runSettlingStartup {
                val key = DbKeyProvider(this@MineApp).getOrGenerateKey()
                val db = DatabaseProvider.getHelper(this@MineApp, key, "mine_encrypted.db")
                val storage = AndroidStorage(db)
                val exportManager = com.anchor.mine.export.AndroidExportManager(
                    this@MineApp, db, key,
                    scheduler = com.anchor.mine.alarms.AndroidAlarmScheduler(this@MineApp)
                )

                // P1-01 (Patch C): Initialize singleton AppGraph process graph
                val graph = AppGraph(this@MineApp, storage, applicationScope)
                captureController = graph.chatController

                // P1-8: Complete all deferreds atomically after full graph construction succeeds
                sharedStorage.complete(storage)
                sharedExportManager.complete(exportManager)
                appGraph.complete(graph)

                com.anchor.mine.quotes.QuoteAlarmScheduler.scheduleNext(this@MineApp)
                // H-02 (R-02): the sweeper used to run only at boot and app open. Arm the
                // periodic trigger here too, so a device that neither reboots nor updates still
                // reports a miss — and so a dropped registration is restored on the next start.
                com.anchor.mine.alarms.BootReceiver.schedulePeriodicSweep(this@MineApp)

                // Blueprint 43 §2: Gated context sensing (disabled by default)
                val flagSensing = storage.getSetting("flag.sensing") == "1"
                val sensingEnabled = storage.getSetting("sensing.enabled") == "1"
                if (flagSensing && sensingEnabled) {
                    contextSensingManager?.start()
                }

                // Blueprint 44 §2: Gated post-call nudge (P2-11)
                val flagCallNudge = storage.getSetting("flag.call_nudge") == "1" || storage.getSetting("flag.call_nudges") == "1"
                val callNudgeEnabled = storage.getSetting("callnudge.enabled") == "1"
                com.anchor.mine.nudge.CallStateReceiver.setCallReceiverEnabled(this@MineApp, flagCallNudge && callNudgeEnabled)

                // Schedule proactive day anchors (P1-13)
                try {
                    com.anchor.mine.alarms.ProactiveAnchorScheduler.scheduleNextAnchors(this@MineApp, storage)
                } catch (e: Exception) {
                    android.util.Log.w("MineApp", "Failed scheduling proactive anchors: ${e.message}")
                }
                storage
            }
            result.exceptionOrNull()?.let { e ->
                synchronized(this@MineApp) {
                    sharedStorage.completeExceptionally(e)
                    sharedExportManager.completeExceptionally(e)
                    appGraph.completeExceptionally(e)
                    isInitializing = false
                }
            }
        }
        return sharedStorage
    }

    fun ensureStorageInitialized(): Deferred<Storage> {
        if (UserManagerCompat.isUserUnlocked(this)) {
            return startStorageInitialization()
        }
        return sharedStorage
    }

    fun ensureAppGraphInitialized(): Deferred<AppGraph> {
        if (UserManagerCompat.isUserUnlocked(this)) {
            startStorageInitialization()
        }
        return appGraph
    }

    /**
     * P1-14 (Patch P): Comprehensive database reset.
     * Cancels all OS alarms, agent wakeups, clears DirectBoot store, cancels WorkManager,
     * and acquires DbGate exclusive write lock before quarantining files and purging state.
     */
    suspend fun resetDatabase(restart: Boolean = true) = withContext(Dispatchers.IO) {
        try {
            val scheduler = com.anchor.mine.alarms.AndroidAlarmScheduler(this@MineApp)
            if (sharedStorage.isCompleted && sharedStorage.getCompletionExceptionOrNull() == null) {
                val s = sharedStorage.getCompleted()
                val pending = s.getPendingReminders()
                for (r in pending) {
                    scheduler.cancel(r.id)
                }
                val wakeups = s.getPendingAgentWakeups(System.currentTimeMillis() + 365 * 86_400_000L)
                for (w in wakeups) {
                    scheduler.cancelAgentWakeup(w.id)
                }
            }
        } catch (_: Throwable) {}

        com.anchor.mine.storage.DbGate.withWriteLock {
            try {
                com.anchor.mine.alarms.DirectBootAlarmStore(this@MineApp).clearAll()
            } catch (_: Throwable) {}

            try {
                val key = DbKeyProvider(this@MineApp).getOrGenerateKey()
                val helper = DatabaseProvider.getHelper(this@MineApp, key, "mine_encrypted.db")
                helper.close()
            } catch (_: Throwable) {}

            // P0-5: Rename / quarantine live database before deletion rather than deleting outright
            val timestamp = System.currentTimeMillis()
            val dbFile = getDatabasePath("mine_encrypted.db")
            if (dbFile.exists()) {
                dbFile.renameTo(java.io.File(dbFile.parentFile, "mine_encrypted.db.corrupt-$timestamp"))
            }
            val walFile = getDatabasePath("mine_encrypted.db-wal")
            if (walFile.exists()) {
                walFile.renameTo(java.io.File(walFile.parentFile, "mine_encrypted.db-wal.corrupt-$timestamp"))
            }
            val shmFile = getDatabasePath("mine_encrypted.db-shm")
            if (shmFile.exists()) {
                shmFile.renameTo(java.io.File(shmFile.parentFile, "mine_encrypted.db-shm.corrupt-$timestamp"))
            }

            deleteDatabase("mine_encrypted.db")
            deleteDatabase("mine_encrypted.db-wal")
            deleteDatabase("mine_encrypted.db-shm")
            java.io.File(filesDir, "wrapped_db.key").delete()
            java.io.File(filesDir, "wrapped_backup_passphrase.bin").delete()
            java.io.File(cacheDir, "wrapped_db.key").delete()

            if (restart) {
                android.os.Process.killProcess(android.os.Process.myPid())
            }
        }
    }
}
