// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.mine.capture

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import com.anchor.core.capture.QuickCapture
import com.anchor.mine.MineApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Blueprint 50 §3.14 / E-17: the inline reply on a reminder notification.
 *
 * Order is the whole point of this class. The text is **stored first**, then the notification is
 * told "Sending…", and only then is anything asked of the network. A process killed between any
 * two of those steps leaves a stored row that the next start retries — never a lost sentence.
 */
class QuickCaptureReceiver : BroadcastReceiver() {

    companion object {
        const val EXTRA_NOTIFICATION_ID = "com.anchor.mine.capture.EXTRA_NOTIFICATION_ID"

        /** The last id this receiver stored. Read by the instrumented test; never by production. */
        @androidx.annotation.VisibleForTesting
        @Volatile
        var lastStoredId: String? = null
    }

    override fun onReceive(context: Context, intent: Intent) {
        val text = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(CaptureNotifications.RESULT_KEY)
            ?.toString()
            ?: return
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0)
        val appContext = context.applicationContext

        val pendingResult = try { goAsync() } catch (_: Exception) { null }
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // R-55: an unchecked cast threw inside this coroutine when the process was not
                // started by MineApp. The reply is simply dropped in that case; the notification
                // stays as it was, so the user can try again.
                val app = appContext as? MineApp ?: return@launch
                val storage = app.sharedStorage.await()

                val id = QuickCapture.store(storage, text) ?: return@launch
                lastStoredId = id

                // Stored. Everything after this point is best-effort presentation.
                CaptureNotifications.showSending(appContext, notificationId)

                val controller = app.captureController
                if (controller == null) {
                    // The chat controller is built by the ViewModel when an activity is up. With
                    // the app dead there is none, so the row waits for the next start (E-17).
                    CaptureNotifications.showFailed(appContext, notificationId)
                    return@launch
                }
                app.captureNotificationIds[id] = notificationId
                controller.sendText(text, id)
            } catch (_: Throwable) {
                // Storage failed before the row was written. Nothing was captured, so the
                // notification must not claim otherwise.
                CaptureNotifications.showFailed(appContext, notificationId)
            } finally {
                pendingResult?.finish()
            }
        }
    }
}
