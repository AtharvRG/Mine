// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.persona

import kotlinx.serialization.Serializable

/**
 * Persona tones matching Blueprint 24 §3.3.
 */
enum class PersonaTone {
    WARM,
    DIRECT_SARCASTIC,
    STRUCTURED,
    CASUAL,
    MINIMAL
}

enum class ReplyLength {
    SHORT,
    MEDIUM,
    DETAILED
}

/**
 * Model representing an AI Persona (Blueprint 24 §3 & §4).
 * Presets ship with the app; custom personas can be created by the user.
 */
@Serializable
data class Persona(
    val id: String,
    val name: String,
    val kind: String = KIND_PRESET,
    val prompt: String,
    val tone: PersonaTone = PersonaTone.WARM,
    val humor: Int = 1,
    val strictness: Int = 1,
    val replyLength: ReplyLength = ReplyLength.SHORT,
    val emoji: Boolean = false
) {
    companion object {
        const val KIND_PRESET = "preset"
        const val KIND_CUSTOM = "custom"
        /** Max character limit for persona prompts (~400 tokens per Blueprint 24 §3.4). */
        const val MAX_PROMPT_CHARS = 1600
    }
}

data class PersonaValidationResult(
    val isValid: Boolean,
    val error: String? = null
)
