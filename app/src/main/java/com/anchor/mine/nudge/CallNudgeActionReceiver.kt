// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.mine.nudge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import com.anchor.core.capture.QuickCapture
import com.anchor.core.nudge.CallNudgeState
import com.anchor.mine.MineApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Handles user interactions (Reply and Skip) from the post-call notification.
 * Blueprint 44 §2 / §3.4.
 *
 * Invariants:
 * - Text stored immediately.
 * - Call event transitioned to ANSWERED or SKIPPED.
 * - Skip produces no guilt and does not count as ignored.
 */
class CallNudgeActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val callEventId = intent.getStringExtra(CallNudgeNotifications.EXTRA_CALL_EVENT_ID) ?: return
        val notificationId = intent.getIntExtra(CallNudgeNotifications.EXTRA_NOTIFICATION_ID, callEventId.hashCode())
        val appContext = context.applicationContext

        // Dismiss notification immediately
        CallNudgeNotifications.dismiss(context, notificationId)

        val pendingResult = try { goAsync() } catch (_: Exception) { null }
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val app = appContext as? MineApp ?: return@launch
                val storage = app.sharedStorage.await()

                when (action) {
                    CallNudgeNotifications.ACTION_REPLY -> {
                        val replyText = RemoteInput.getResultsFromIntent(intent)
                            ?.getCharSequence(CallNudgeNotifications.RESULT_KEY)
                            ?.toString()
                            ?.trim()

                        if (!replyText.isNullOrEmpty()) {
                            val msgId = QuickCapture.store(storage, replyText)
                            storage.updateCallEventState(callEventId, CallNudgeState.ANSWERED, nudgeId = msgId)
                            if (msgId != null) {
                                app.captureController?.sendText(replyText, msgId)
                            }
                        }
                    }
                    CallNudgeNotifications.ACTION_SKIP -> {
                        storage.updateCallEventState(callEventId, CallNudgeState.SKIPPED)
                    }
                }
            } finally {
                pendingResult?.finish()
            }
        }
    }
}
