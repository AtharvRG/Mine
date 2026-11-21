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
                // P1-01 (Patch C): Collect from singleton AppGraph process graph
                val graph = app.ensureAppGraphInitialized().await()
                storage = graph.storage
                controller = graph.chatController
                reliabilityManager = graph.reliabilityManager

                // Drain any actions that arrived before the controller was initialized
                while (true) {
                    val pending = pendingActions.poll() ?: break
                    graph.chatController.onAction(pending)
                }

                // Run sweeper on app open
                graph.reliabilityManager.runSweeper().forEach { missedRem ->
                    val intent = android.content.Intent(getApplication(), com.anchor.mine.alarms.AlarmReceiver::class.java).apply {
                        putExtra("EXTRA_ID", missedRem.id)
                        putExtra("EXTRA_TEXT", missedRem.text)
                        putExtra("EXTRA_TIER", missedRem.tier.name)
                        putExtra("EXTRA_MISSED", true)
                        putExtra("EXTRA_SCHEDULED_FOR", missedRem.fireAtMillis)
                    }
                    getApplication<Application>().sendBroadcast(intent)
                }

                // E-17: a capture stored while the app was dead is sent now, oldest first
                for (captureId in com.anchor.core.capture.QuickCapture.pending(graph.storage)) {
                    val text = graph.storage.getRawMessage(captureId)
                    if (!text.isNullOrBlank()) graph.chatController.sendText(text, captureId)
                }

                _state.update { it.copy(notificationsBlocked = notificationsBlocked()) }
                graph.chatController.state.collect { s -> _state.value = s.copy(notificationsBlocked = notificationsBlocked()) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
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
                viewModelScope.launch(Dispatchers.IO) {
                    val app = getApplication<Application>() as? MineApp
                    app?.resetDatabase()
                }
            } else {
                _state.value = _state.value.copy(showResetConfirm = false)
            }
            return
        }
        if (action is UiAction.SetContextSensing) {
            val c = controller
            if (c != null) {
                c.onAction(action)
            } else {
                pendingActions.add(action)
            }
            (getApplication<Application>() as? MineApp)?.contextSensingManager?.checkAndApplySettings()
            return
        }
        if (action is UiAction.SetCurrentWifiAsHome) {
            val app = getApplication<Application>() as? MineApp
            val enrolled = app?.contextSensingManager?.enrollCurrentWifiAsHome() ?: false
            if (enrolled) {
                controller?.addTransientReceipt(com.anchor.core.ui.ReceiptState(java.util.UUID.randomUUID().toString(), "Current Wi-Fi enrolled as Home place", null, isError = false, timestamp = System.currentTimeMillis()))
            } else {
                controller?.addTransientReceipt(com.anchor.core.ui.ReceiptState(java.util.UUID.randomUUID().toString(), "Could not read current Wi-Fi network (ensure Wi-Fi is connected)", null, isError = true, timestamp = System.currentTimeMillis()))
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
