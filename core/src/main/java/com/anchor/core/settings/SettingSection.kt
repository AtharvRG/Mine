// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.settings

/**
 * 12 Settings sections defined in Blueprint 52 §3.4 & §3.5.
 */
enum class SettingSection(val id: Int, val title: String) {
    PROFILE_AND_DAY(1, "Profile and day"),
    AI_AND_PROVIDERS(2, "AI and providers"),
    PERSONA(3, "Persona"),
    MEMORY_AND_SOUL(4, "Memory and Soul"),
    NUDGES(5, "Nudges"),
    REMINDERS_AND_ALARMS(6, "Reminders and alarms"),
    CHECKINS(7, "Check-ins"),
    VOICE(8, "Voice"),
    PRIVACY(9, "Privacy"),
    RELIABILITY(10, "Reliability"),
    STORAGE_AND_SYNC(11, "Storage and sync"),
    ABOUT_AND_DEBUG(12, "About and debug")
}

/**
 * Scope of a setting (Blueprint 52 §3.4):
 * - SYNCED ('S'): Synced across devices
 * - DEVICE_LOCAL ('D'): Local to this phone only
 */
enum class SettingScope {
    SYNCED,
    DEVICE_LOCAL
}
