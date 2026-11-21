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
import com.anchor.core.nudge.CallNudgeConfig
import com.anchor.core.nudge.CallNudgeDecision
import com.anchor.core.nudge.CallNudgeEngine
import com.anchor.core.nudge.NudgeEngine
import com.anchor.core.time.SystemClock
import com.anchor.mine.MineApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Handles user interactions (Reply and Skip) from the post-call notification,
 * and handles delayed post-call evaluation alarms without blocking broadcast threads.
 * Blueprint 44 §2 / §3.4.
 *
 * Invariants:
 * - Text stored immediately.
 * - Call event transitioned to ANSWERED or SKIPPED.
 * - Skip produces no guilt and does not count as ignored.
 * - Uses real durable ProactiveState from storage during delayed candidate evaluation.
 */
class CallNudgeActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val callEventId = intent.getStringExtra(CallNudgeNotifications.EXTRA_CALL_EVENT_ID) ?: return
        val notificationId = intent.getIntExtra(CallNudgeNotifications.EXTRA_NOTIFICATION_ID, callEventId.hashCode())
        val appContext = context.applicationContext

        // Dismiss notification immediately only for user dismiss/reply actions
        if (action == CallNudgeNotifications.ACTION_REPLY || action == CallNudgeNotifications.ACTION_SKIP) {
            CallNudgeNotifications.dismiss(context, notificationId)
        }

        val pendingResult = try { goAsync() } catch (_: Exception) { null }
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val app = appContext as? MineApp ?: return@launch
                val storage = app.sharedStorage.await()

                when (action) {
                    ACTION_EVALUATE_CALL_NUDGE -> {
                        val clock = SystemClock()
                        val minDuration = storage.getSetting("callnudge.min_duration_s")?.toIntOrNull() ?: 45
                        val delayS = storage.getSetting("callnudge.delay_s")?.toIntOrNull() ?: 20
                        val coalesceMin = storage.getSetting("callnudge.coalesce_min")?.toIntOrNull() ?: 10
                        val expireH = storage.getSetting("callnudge.expire_h")?.toIntOrNull() ?: 3
                        val callConfig = CallNudgeConfig(minDurationS = minDuration, delayS = delayS, coalesceMin = coalesceMin, expireH = expireH)
                        val callEngine = CallNudgeEngine(
                            storage = storage,
                            clock = clock,
                            nudgeEngine = NudgeEngine(clock),
                            configProvider = { callConfig }
                        )
                        // Use real durable ProactiveState
                        val proactiveState = storage.getProactiveState()
                        val eval = callEngine.evaluateCandidate(callEventId, proactiveState)
                        if (eval is CallNudgeDecision.ReadyToNudge) {
                            CallNudgeNotifications.show(appContext, callEventId)
                            // Also a chat message, so the question is waiting in the conversation.
                            com.anchor.mine.notifications.ProactiveMessages.post(
                                appContext, storage,
                                "How did that call go? Anything you promised or need to remember from it?",
                                source = "call", notify = false,
                            )
                            storage.updateCallEventState(callEventId, CallNudgeState.NUDGED)
                        }
                    }
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
            } catch (t: Throwable) {
                android.util.Log.e("CallNudgeActionReceiver", "Failed handling call nudge action: ${t.message}", t)
            } finally {
                pendingResult?.finish()
            }
        }
    }

    companion object {
        const val ACTION_EVALUATE_CALL_NUDGE = "com.anchor.mine.nudge.ACTION_EVALUATE_CALL_NUDGE"
    }
}
