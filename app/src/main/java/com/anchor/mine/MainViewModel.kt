// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.mine

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.anchor.core.context.ContextBuilder
import com.anchor.core.gateway.Gateway
import com.anchor.core.provider.ProviderOrchestrator
import com.anchor.core.provider.RateLimiter
import com.anchor.core.resolver.Resolver
import com.anchor.core.time.DateResolver
import com.anchor.core.time.SystemClock
import com.anchor.core.ui.ChatController
import com.anchor.core.ui.UiAction
import com.anchor.core.ui.UiState
import com.anchor.mine.alarms.AndroidAlarmScheduler
import com.anchor.mine.provider.AndroidHttpTransport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var controller: ChatController? = null

    init {
        viewModelScope.launch {
            try {
                val app = application as MineApp
                val storage = app.sharedStorage.await() // Can throw if keys/DB corrupt
                val limiter = RateLimiter { System.currentTimeMillis() }
                val orchestrator = ProviderOrchestrator(limiter, AndroidHttpTransport())
                val clock = SystemClock()
                val gateway = Gateway(storage, Resolver(), DateResolver(clock), clock)

                val contextBuilder = ContextBuilder(storage, clock, DateResolver(clock))
                controller = ChatController(
                    storage, orchestrator, contextBuilder,
                    gateway, AndroidAlarmScheduler(application),
                    viewModelScope, kotlinx.coroutines.Dispatchers.Default, clock
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
        controller?.onAction(action)
    }
}
