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
import com.anchor.core.ui.ChatController
import com.anchor.core.ui.UiAction
import com.anchor.core.ui.UiState
import com.anchor.mine.alarms.AndroidAlarmScheduler
import com.anchor.mine.provider.AndroidHttpTransport
import com.anchor.mine.storage.AndroidStorage
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
            val app = application as MineApp
            app.storage.collect { storage ->
                if (storage != null) {
                    val limiter = RateLimiter { System.currentTimeMillis() }
                    val orchestrator = ProviderOrchestrator(limiter, AndroidHttpTransport())
                    val gateway = Gateway(storage, Resolver())

                    controller = ChatController(
                        storage, orchestrator, ContextBuilder(storage),
                        gateway, AndroidAlarmScheduler(application), viewModelScope
                    )

                    controller!!.state.collect { _state.value = it }
                }
            }
        }
    }

    fun onAction(action: UiAction) {
        controller?.onAction(action)
    }
}