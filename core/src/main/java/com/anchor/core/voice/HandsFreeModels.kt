// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.voice

import kotlinx.serialization.Serializable

/**
 * Hands-free conversation turn-taking states.
 * Blueprint 60 §9, Parking Lot P-018.
 */
@Serializable
enum class HandsFreeState {
    /** Inactive: hands-free mode is off. */
    IDLE,
    /** Microphone is actively capturing user speech. */
    LISTENING,
    /** Transcribing speech or executing LLM turn. */
    PROCESSING,
    /** Reading assistant reply aloud via TTS. Mic is strictly CLOSED. */
    SPEAKING
}

/**
 * Events driving the Hands-Free State Machine.
 */
sealed interface HandsFreeEvent {
    data object Start : HandsFreeEvent
    data class Stop(val reason: String = "User requested") : HandsFreeEvent
    data class UserUtterance(val text: String) : HandsFreeEvent
    data class TurnComplete(val replyText: String) : HandsFreeEvent
    data object PlaybackComplete : HandsFreeEvent
    data object SilenceTimeout : HandsFreeEvent
}

/**
 * Side-effects emitted by the state machine to be executed by the platform/UI.
 */
sealed interface HandsFreeEffect {
    data object StartListening : HandsFreeEffect
    data object StopListening : HandsFreeEffect
    data class SpeakReply(val text: String) : HandsFreeEffect
    data class DispatchUserMessage(val text: String) : HandsFreeEffect
    data class SessionEnded(val reason: String) : HandsFreeEffect
}
