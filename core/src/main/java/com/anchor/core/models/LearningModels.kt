// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.models

import kotlinx.serialization.Serializable

/**
 * Raw-ish signal log row used for behavioral learning statistics.
 * Corresponds to Blueprint 25 §4 `signal_log`.
 */
@Serializable
data class SignalLog(
    val id: String,
    val tsUtc: Long,
    val kind: String,
    val subject: String? = null,
    val value: Double? = null,
    val attrs: String? = null
)

/**
 * Derived behavioral preference row.
 * Corresponds to Blueprint 25 §4 `learned_pref`.
 */
@Serializable
data class LearnedPref(
    val key: String,
    val scope: String,
    val valueJson: String,
    val samples: Int,
    val confidence: Double,
    val locked: Boolean = false,
    val updatedAt: Long
)
