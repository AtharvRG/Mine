// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.voice

/**
 * Pure Kotlin Turn-Taking State Machine for Hands-Free Conversation.
 * Blueprint 60 §9, Parking Lot P-018.
 *
 * Implements strict half-duplex turn alternation to eliminate acoustic echo
 * feedback loops, detects verbal stop phrases, and enforces battery safety timeouts.
 */
class HandsFreeStateMachine(
    initialState: HandsFreeState = HandsFreeState.IDLE
) {

    var state: HandsFreeState = initialState
        private set

    companion object {
        private val STOP_PHRASES = setOf(
            "stop", "exit", "quit", "cancel", "bye", "goodbye", "good bye",
            "that's all", "thats all", "that is all",
            "thank you that's all", "thank you thats all", "thanks that's all",
            "done", "finished", "close"
        )

        fun isStopWord(text: String): Boolean {
            val normalized = text.lowercase()
                .replace(Regex("[^a-z0-9'\\s]"), "")
                .trim()

            if (normalized in STOP_PHRASES) return true
            for (phrase in STOP_PHRASES) {
                if (normalized.startsWith("$phrase ") || normalized.endsWith(" $phrase")) {
                    return true
                }
            }
            return false
        }
    }

    /**
     * Processes an incoming event, transitions internal state, and returns side effects to execute.
     */
    fun transition(event: HandsFreeEvent): List<HandsFreeEffect> {
        val effects = mutableListOf<HandsFreeEffect>()

        when (state) {
            HandsFreeState.IDLE -> {
                when (event) {
                    is HandsFreeEvent.Start -> {
                        state = HandsFreeState.LISTENING
                        effects.add(HandsFreeEffect.StartListening)
                    }
                    else -> {} // Ignore other events when idle
                }
            }

            HandsFreeState.LISTENING -> {
                when (event) {
                    is HandsFreeEvent.Stop -> {
                        state = HandsFreeState.IDLE
                        effects.add(HandsFreeEffect.StopListening)
                        effects.add(HandsFreeEffect.SessionEnded(event.reason))
                    }
                    is HandsFreeEvent.SilenceTimeout -> {
                        state = HandsFreeState.IDLE
                        effects.add(HandsFreeEffect.StopListening)
                        effects.add(HandsFreeEffect.SessionEnded("Session ended due to silence."))
                    }
                    is HandsFreeEvent.UserUtterance -> {
                        val text = event.text.trim()
                        if (isStopWord(text)) {
                            state = HandsFreeState.IDLE
                            effects.add(HandsFreeEffect.StopListening)
                            effects.add(HandsFreeEffect.SessionEnded("Session completed: $text"))
                        } else if (text.isNotBlank()) {
                            state = HandsFreeState.PROCESSING
                            effects.add(HandsFreeEffect.StopListening)
                            effects.add(HandsFreeEffect.DispatchUserMessage(text))
                        }
                    }
                    else -> {}
                }
            }

            HandsFreeState.PROCESSING -> {
                when (event) {
                    is HandsFreeEvent.Stop -> {
                        state = HandsFreeState.IDLE
                        effects.add(HandsFreeEffect.SessionEnded(event.reason))
                    }
                    is HandsFreeEvent.TurnComplete -> {
                        if (event.replyText.isNotBlank()) {
                            state = HandsFreeState.SPEAKING
                            effects.add(HandsFreeEffect.SpeakReply(event.replyText))
                        } else {
                            state = HandsFreeState.LISTENING
                            effects.add(HandsFreeEffect.StartListening)
                        }
                    }
                    else -> {}
                }
            }

            HandsFreeState.SPEAKING -> {
                when (event) {
                    is HandsFreeEvent.Stop -> {
                        state = HandsFreeState.IDLE
                        effects.add(HandsFreeEffect.SessionEnded(event.reason))
                    }
                    is HandsFreeEvent.PlaybackComplete -> {
                        // Crucial loop-back: After assistant finishes speaking, reopen mic for next turn
                        state = HandsFreeState.LISTENING
                        effects.add(HandsFreeEffect.StartListening)
                    }
                    else -> {}
                }
            }
        }

        return effects
    }
}
