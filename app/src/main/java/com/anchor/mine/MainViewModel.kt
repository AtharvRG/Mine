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
    private lateinit var reliabilityManager: ReliabilityManager
    private var storage: Storage? = null

    init {
        viewModelScope.launch {
            try {
                val app = application as MineApp
                val loadedStorage = app.sharedStorage.await() // Can throw if keys/DB corrupt
                storage = loadedStorage
                val orchestrator = app.orchestrator
                val clock = SystemClock()
                val is24HourFormat = { android.text.format.DateFormat.is24HourFormat(application) }
                val dateResolver = DateResolver(clock, is24HourFormat)
                val gateway = Gateway(loadedStorage, Resolver(), dateResolver, clock)
                reliabilityManager = ReliabilityManager(loadedStorage, clock)

                val checkinBuilder = com.anchor.core.nudge.CheckinBuilder(loadedStorage, clock, dateResolver)
                val contextBuilder = ContextBuilder(loadedStorage, clock, dateResolver, checkinBuilder)
                
                controller = ChatController(
                    loadedStorage, orchestrator, contextBuilder,
                    gateway, AndroidAlarmScheduler(application),
                    viewModelScope, Dispatchers.Default, clock,
                    dateResolver = dateResolver
                )

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
        controller?.onAction(action)
    }
}
