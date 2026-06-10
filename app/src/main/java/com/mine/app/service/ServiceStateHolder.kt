package com.mine.app.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ServiceStateHolder @Inject constructor() {
    private val _isAmbientRunning = MutableStateFlow(false)
    val isAmbientRunning: StateFlow<Boolean> = _isAmbientRunning

    fun setRunning(isRunning: Boolean) {
        _isAmbientRunning.value = isRunning
    }
}
