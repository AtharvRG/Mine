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
                val limiter = RateLimiter { System.currentTimeMillis() }
                val orchestrator = ProviderOrchestrator(limiter, AndroidHttpTransport())
                val clock = SystemClock()
                val gateway = Gateway(loadedStorage, Resolver(), DateResolver(clock), clock)
                reliabilityManager = ReliabilityManager(loadedStorage, clock)

                val contextBuilder = ContextBuilder(loadedStorage, clock, DateResolver(clock))
                controller = ChatController(
                    loadedStorage, orchestrator, contextBuilder,
                    gateway, AndroidAlarmScheduler(application),
                    viewModelScope, Dispatchers.Default, clock
                )

                controller!!.state.collect { _state.value = it }
            } catch (e: Exception) {
                val msg = if (e.message == "KEYSTORE_LOST") {
                    "Keystore key lost. The database cannot be decrypted. You must reset the app."
                } else {
                    "Storage failed: ${e.message}"
                }
                _state.value = _state.value.copy(globalError = msg)
            }
        }
    }

    fun onAction(action: UiAction) {
        if (action is UiAction.ResetDatabase) {
            val ctx = getApplication<Application>().applicationContext
            ctx.deleteDatabase("mine_encrypted.db")
            java.io.File(ctx.filesDir, "wrapped_db.key").delete()
            // Force crash to restart fresh (or you can elegantly reload the activity)
            android.os.Process.killProcess(android.os.Process.myPid())
            return
        }
        if (action is UiAction.RunSelfTest) {
            viewModelScope.launch(Dispatchers.Default) {
                val st = storage ?: return@launch
                _state.update { it.copy(selfTestStatus = "Armed for 10 seconds...") }
                val id = "test_${System.currentTimeMillis()}"
                val fireAt = System.currentTimeMillis() + 10000L
                val reminder = Reminder(id, "Self Test Fired!", fireAt)
                st.saveReminder(reminder)
                controller?.state?.value?.let { /* Trigger recompose */ }
                
                // Fire sweeper manually for testing purposes
                val missed = reliabilityManager.runSweeper()
                missed.forEach { missedRem ->
                    val intent = Intent(getApplication(), AlarmReceiver::class.java).apply {
                        putExtra("EXTRA_ID", missedRem.id)
                        putExtra("EXTRA_TEXT", "Missed: ${missedRem.text}")
                    }
                    getApplication<Application>().sendBroadcast(intent)
                }
            }
            return
        }
        controller?.onAction(action)
    }
}
