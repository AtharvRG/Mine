// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.models

import kotlinx.serialization.Serializable

@Serializable
enum class QuoteSlot {
    MORNING,
    AFTERNOON,
    EVENING,
    NIGHT
}

@Serializable
enum class QuoteSource {
    PRESET,
    LIVE
}

@Serializable
data class Quote(
    val slot: QuoteSlot,
    val dayKey: String,
    val text: String,
    val generatedAt: Long,
    val stateHash: String? = null,
    val source: QuoteSource = QuoteSource.PRESET,
    val regenCount: Int = 0
)
