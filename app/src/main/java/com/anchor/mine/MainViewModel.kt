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

                controller = ChatController(
                    storage, orchestrator, ContextBuilder(storage),
                    gateway, AndroidAlarmScheduler(application),
                    viewModelScope, kotlinx.coroutines.Dispatchers.Default, SystemClock()
                )

                controller!!.state.collect { _state.value = it }
            } catch (e: Exception) {
                // UI will intercept this and display a fatal error instead of hanging/crashing.
                _state.value = _state.value.copy(globalError = "Storage failed: ${e.message}")
            }
        }
    }

    fun onAction(action: UiAction) {
        controller?.onAction(action)
    }
}
