// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.settings

import com.anchor.core.storage.Storage

/**
 * Settings manager providing typed get, validated set, and section-level reset to defaults
 * (Blueprint 52 §3.4).
 */
class SettingsManager(
    private val storage: Storage
) {

    /**
     * Reads a setting value from storage, falling back to the catalog default if unset.
     */
    suspend fun get(key: String): String {
        val stored = storage.getSetting(key)
        if (stored != null) return stored
        return SettingsCatalog.find(key)?.defaultValue ?: ""
    }

    /**
     * Validates and saves a setting value. Returns true if successful, false if rejected by validator.
     */
    suspend fun set(key: String, value: String): Boolean {
        val def = SettingsCatalog.find(key)
        if (def != null && !def.validator(value)) {
            return false
        }
        storage.saveSetting(key, value)
        return true
    }

    /**
     * Resets a single setting to its catalog default.
     */
    suspend fun resetToDefault(key: String): Boolean {
        val def = SettingsCatalog.find(key) ?: return false
        storage.saveSetting(key, def.defaultValue)
        return true
    }

    /**
     * Section-level reset to defaults (Blueprint 52 §3.4).
     * Strictly restricted to sections 4 to 8 only.
     * Account-like data (providers, encryption, Soul facts) is never reset.
     */
    suspend fun resetSectionToDefaults(section: SettingSection): Boolean {
        if (section.id !in 4..8) {
            return false
        }
        val settings = SettingsCatalog.getSettingsForSection(section)
        for (def in settings) {
            storage.saveSetting(def.key, def.defaultValue)
        }
        return true
    }
}
