// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.settings

/**
 * Definition of a single configurable setting in the catalog (Blueprint 52 §3.4 & §3.5).
 */
data class SettingDef(
    val key: String,
    val section: SettingSection,
    val defaultValue: String,
    val scope: SettingScope,
    val isAdvanced: Boolean = false,
    val description: String = "",
    val validator: (String) -> Boolean = { true }
)
