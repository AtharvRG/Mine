// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.mine

import android.app.Application
import com.anchor.core.provider.ProviderOrchestrator
import com.anchor.core.provider.RateLimiter
import com.anchor.core.storage.Storage
import com.anchor.mine.provider.AndroidHttpTransport
import com.anchor.mine.storage.AndroidStorage
import com.anchor.mine.storage.DatabaseProvider
import com.anchor.mine.storage.DbKeyProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MineApp : Application() {
    @Volatile
    var sharedStorage = CompletableDeferred<Storage>()
    val sharedExportManager = CompletableDeferred<com.anchor.core.export.ExportManager>()

    val rateLimiter: RateLimiter = RateLimiter { System.currentTimeMillis() }
    val orchestrator: ProviderOrchestrator by lazy {
        ProviderOrchestrator(rateLimiter, AndroidHttpTransport())
    }

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
        CoroutineScope(Dispatchers.IO).launch {
            // H-06 (R-11): catches Throwable, not Exception — an Error here used to leave both
            // deferreds unsettled, so every await() hung forever.
            val result = runSettlingStartup {
                val key = DbKeyProvider(this@MineApp).getOrGenerateKey()
                val db = DatabaseProvider.getHelper(this@MineApp, key, "mine_encrypted.db")
                sharedStorage.complete(AndroidStorage(db))
                // B5: without a scheduler the post-import reconcile is skipped, and an import
                // would swap the database while the OLD alarms stayed armed — reminders that
                // fire for rows the user no longer has.
                sharedExportManager.complete(
                    com.anchor.mine.export.AndroidExportManager(
                        this@MineApp, db, key,
                        scheduler = com.anchor.mine.alarms.AndroidAlarmScheduler(this@MineApp)
                    )
                )
                com.anchor.mine.quotes.QuoteAlarmScheduler.scheduleNext(this@MineApp)
                // H-02 (R-02): the sweeper used to run only at boot and app open. Arm the
                // periodic trigger here too, so a device that neither reboots nor updates still
                // reports a miss — and so a dropped registration is restored on the next start.
                com.anchor.mine.alarms.BootReceiver.schedulePeriodicSweep(this@MineApp)
            }
            result.exceptionOrNull()?.let { e ->
                // completeExceptionally is a no-op once completed; the storage may already be
                // open when only the export manager or the quote schedule failed.
                sharedStorage.completeExceptionally(e)
                sharedExportManager.completeExceptionally(e)
            }
        }
    }
}
