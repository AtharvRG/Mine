// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.mine.ambient

import com.anchor.core.ambient.AmbientState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

/**
 * Process-wide view of the ambient listening service.
 *
 * The service is the only writer. The UI reads [state] to show what is really happening (not
 * what the toggle says), and the notification reader reads it to decide whether it may speak.
 *
 * [markSpeaking] is the half-duplex guard: while Mine itself is talking (reading a notification
 * or a reply aloud), audio slices the service records are discarded, so Mine never transcribes
 * its own voice as conversation.
 */
object AmbientStatusBus {
    private val _state = MutableStateFlow<AmbientState>(AmbientState.Off)
    val state: StateFlow<AmbientState> = _state.asStateFlow()

    private val speakingUntil = AtomicLong(0L)

    internal fun publish(s: AmbientState) {
        _state.value = s
    }

    /** True while the service is up and actually capturing (or extracting) audio. */
    val isActive: Boolean
        get() = when (_state.value) {
            is AmbientState.Listening, is AmbientState.Processing -> true
            else -> false
        }

    /** True while the service is running at all, even if it is temporarily asleep. */
    val isRunning: Boolean get() = _state.value !is AmbientState.Off

    /** Mine is about to speak for roughly [durationMs]; drop captured audio until then. */
    fun markSpeaking(durationMs: Long) {
        val until = System.currentTimeMillis() + durationMs.coerceIn(0L, 60_000L) + TAIL_MS
        speakingUntil.updateAndGet { maxOf(it, until) }
    }

    /** Speech finished early (or was interrupted): keep only the short tail. */
    fun clearSpeaking() {
        speakingUntil.set(System.currentTimeMillis() + TAIL_MS)
    }

    /** True when a slice spanning [startMs, endMs] overlaps Mine's own speech. */
    fun overlapsOwnSpeech(startMs: Long, endMs: Long): Boolean = startMs <= speakingUntil.get() && endMs >= 0L

    /** The room echoes for a moment after speech ends. */
    private const val TAIL_MS = 600L
}
