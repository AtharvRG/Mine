// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.settings

/**
 * Authoritative settings catalog matching Blueprint 52 §3.5.
 */
object SettingsCatalog {

    private val TIME_REGEX = Regex("""^([01]?[0-9]|2[0-3]):[0-5][0-9]$""")
    private val INT_REGEX = Regex("""^-?\d+$""")
    private val FLOAT_REGEX = Regex("""^-?\d+(\.\d+)?$""")
    private val BOOL_REGEX = Regex("""^[01]$""")

    val ALL: List<SettingDef> = listOf(
        // Section 1: Profile and day
        SettingDef("profile.name", SettingSection.PROFILE_AND_DAY, "", SettingScope.SYNCED, description = "User's preferred display name"),
        SettingDef("profile.languages", SettingSection.PROFILE_AND_DAY, "en", SettingScope.SYNCED, description = "Supported conversation languages"),
        SettingDef("day.timezone", SettingSection.PROFILE_AND_DAY, "auto", SettingScope.DEVICE_LOCAL, description = "Timezone setting"),
        SettingDef("day.start", SettingSection.PROFILE_AND_DAY, "06:00", SettingScope.SYNCED, description = "Start of user's active day", validator = { it.matches(TIME_REGEX) }),
        SettingDef("day.end", SettingSection.PROFILE_AND_DAY, "22:00", SettingScope.SYNCED, description = "End of user's active day", validator = { it.matches(TIME_REGEX) }),
        SettingDef("day.week_start", SettingSection.PROFILE_AND_DAY, "Monday", SettingScope.SYNCED, description = "First day of the week"),
        SettingDef("ui.time_format", SettingSection.PROFILE_AND_DAY, "system", SettingScope.DEVICE_LOCAL, description = "12 or 24 hour display format"),

        // Section 2: AI and providers
        SettingDef("slot.worker.same_as_chat", SettingSection.AI_AND_PROVIDERS, "1", SettingScope.DEVICE_LOCAL, validator = { it.matches(BOOL_REGEX) }),
        SettingDef("ai.temperature.chat", SettingSection.AI_AND_PROVIDERS, "0.7", SettingScope.DEVICE_LOCAL, isAdvanced = true, validator = { it.matches(FLOAT_REGEX) }),
        SettingDef("ai.temperature.worker", SettingSection.AI_AND_PROVIDERS, "0.2", SettingScope.DEVICE_LOCAL, isAdvanced = true, validator = { it.matches(FLOAT_REGEX) }),
        SettingDef("ai.max_tokens", SettingSection.AI_AND_PROVIDERS, "1200", SettingScope.DEVICE_LOCAL, isAdvanced = true, validator = { it.matches(INT_REGEX) }),
        SettingDef("provider.rpm_default", SettingSection.AI_AND_PROVIDERS, "20", SettingScope.DEVICE_LOCAL, validator = { it.matches(INT_REGEX) && it.toInt() in 1..50 }),
        SettingDef("provider.cooldown_min", SettingSection.AI_AND_PROVIDERS, "15", SettingScope.DEVICE_LOCAL, validator = { it.matches(INT_REGEX) }),
        SettingDef("provider.fallback.endpoint", SettingSection.AI_AND_PROVIDERS, "", SettingScope.DEVICE_LOCAL, description = "Fallback provider base URL"),
        SettingDef("provider.fallback.key", SettingSection.AI_AND_PROVIDERS, "", SettingScope.DEVICE_LOCAL, description = "Fallback provider API key"),
        SettingDef("provider.fallback.rpm", SettingSection.AI_AND_PROVIDERS, "20", SettingScope.DEVICE_LOCAL, validator = { it.matches(INT_REGEX) && it.toInt() in 1..50 }),
        SettingDef("ai.mobile_data", SettingSection.AI_AND_PROVIDERS, "1", SettingScope.DEVICE_LOCAL, validator = { it.matches(BOOL_REGEX) }),
        SettingDef("ai.compact_mode", SettingSection.AI_AND_PROVIDERS, "0", SettingScope.DEVICE_LOCAL, isAdvanced = true, description = "Low token mode for smaller models", validator = { it.matches(BOOL_REGEX) }),

        // Section 3: Persona
        SettingDef("persona.active", SettingSection.PERSONA, "companion", SettingScope.SYNCED, description = "Selected AI persona id"),
        SettingDef("persona.intensity", SettingSection.PERSONA, "medium", SettingScope.SYNCED, description = "Persona intensity (low, medium, high)"),
        SettingDef("persona.use_user_name", SettingSection.PERSONA, "1", SettingScope.SYNCED, validator = { it.matches(BOOL_REGEX) }),

        // Section 4: Memory and Soul
        SettingDef("memory.auto_extract", SettingSection.MEMORY_AND_SOUL, "1", SettingScope.SYNCED, validator = { it.matches(BOOL_REGEX) }),
        SettingDef("memory.min_confidence", SettingSection.MEMORY_AND_SOUL, "0.7", SettingScope.SYNCED, validator = { it.matches(FLOAT_REGEX) }),
        SettingDef("soul.mode", SettingSection.MEMORY_AND_SOUL, "ask", SettingScope.SYNCED, description = "Soul auto-accept mode: ask or auto", validator = { it in setOf("ask", "auto") }),

        // Section 5: Nudges
        SettingDef("nudge.enabled", SettingSection.NUDGES, "1", SettingScope.SYNCED, validator = { it.matches(BOOL_REGEX) }),
        SettingDef("nudge.min_gap_minutes", SettingSection.NUDGES, "60", SettingScope.SYNCED, validator = { it.matches(INT_REGEX) }),
        SettingDef("nudge.ignore_window_hours", SettingSection.NUDGES, "3", SettingScope.SYNCED, validator = { it.matches(INT_REGEX) }),
        SettingDef("nudge.max_per_day", SettingSection.NUDGES, "40", SettingScope.SYNCED, isAdvanced = true, validator = { it.matches(INT_REGEX) }),
        // Blueprint 43 §5: Context Sensing
        SettingDef("flag.sensing", SettingSection.NUDGES, "0", SettingScope.DEVICE_LOCAL, validator = { it.matches(BOOL_REGEX) }),
        SettingDef("sensing.enabled", SettingSection.NUDGES, "0", SettingScope.DEVICE_LOCAL, validator = { it.matches(BOOL_REGEX) }),
        SettingDef("sensing.use_wifi", SettingSection.NUDGES, "1", SettingScope.DEVICE_LOCAL, validator = { it.matches(BOOL_REGEX) }),
        SettingDef("sensing.use_geofence", SettingSection.NUDGES, "0", SettingScope.DEVICE_LOCAL, validator = { it.matches(BOOL_REGEX) }),
        SettingDef("sensing.away_confirm_min", SettingSection.NUDGES, "4", SettingScope.SYNCED, validator = { it.matches(INT_REGEX) }),
        SettingDef("sensing.home_confirm_min", SettingSection.NUDGES, "2", SettingScope.SYNCED, validator = { it.matches(INT_REGEX) }),
        SettingDef("sensing.motion_min_s", SettingSection.NUDGES, "45", SettingScope.SYNCED, validator = { it.matches(INT_REGEX) }),
        SettingDef("sensing.context_nudges", SettingSection.NUDGES, "1", SettingScope.SYNCED, validator = { it.matches(BOOL_REGEX) }),
        SettingDef("sensing.hold_while_vehicle", SettingSection.NUDGES, "1", SettingScope.SYNCED, validator = { it.matches(BOOL_REGEX) }),
        SettingDef("sensing.show_chip", SettingSection.NUDGES, "0", SettingScope.SYNCED, validator = { it.matches(BOOL_REGEX) }),
        SettingDef("sensing.keep_events_days", SettingSection.NUDGES, "30", SettingScope.SYNCED, validator = { it.matches(INT_REGEX) }),

        // Section 6: Reminders and alarms
        SettingDef("reminder.default_tier", SettingSection.REMINDERS_AND_ALARMS, "alarm", SettingScope.SYNCED, description = "Default reminder tier: alarm or gentle"),
        SettingDef("reminder.lead_time_minutes", SettingSection.REMINDERS_AND_ALARMS, "0", SettingScope.SYNCED, validator = { it.matches(INT_REGEX) }),

        // Section 7: Check-ins
        SettingDef("checkin.morning_enabled", SettingSection.CHECKINS, "1", SettingScope.SYNCED, validator = { it.matches(BOOL_REGEX) }),
        SettingDef("checkin.evening_enabled", SettingSection.CHECKINS, "1", SettingScope.SYNCED, validator = { it.matches(BOOL_REGEX) }),
        SettingDef("checkin.weekly_enabled", SettingSection.CHECKINS, "1", SettingScope.SYNCED, validator = { it.matches(BOOL_REGEX) }),

        // Section 8: Voice
        SettingDef("voice.stt_enabled", SettingSection.VOICE, "0", SettingScope.DEVICE_LOCAL, validator = { it.matches(BOOL_REGEX) }),
        SettingDef("voice.tts_enabled", SettingSection.VOICE, "0", SettingScope.DEVICE_LOCAL, validator = { it.matches(BOOL_REGEX) }),

        // Section 9: Privacy
        SettingDef("privacy.app_lock", SettingSection.PRIVACY, "0", SettingScope.DEVICE_LOCAL, validator = { it.matches(BOOL_REGEX) }),
        SettingDef("privacy.hide_notification_content", SettingSection.PRIVACY, "0", SettingScope.DEVICE_LOCAL, validator = { it.matches(BOOL_REGEX) }),
        SettingDef("privacy.block_screenshots", SettingSection.PRIVACY, "0", SettingScope.DEVICE_LOCAL, validator = { it.matches(BOOL_REGEX) }),

        // Section 10: Reliability
        SettingDef("reliability.periodic_sweep", SettingSection.RELIABILITY, "1", SettingScope.DEVICE_LOCAL, validator = { it.matches(BOOL_REGEX) }),

        // Section 11: Storage and sync
        SettingDef("storage.export_reminder_days", SettingSection.STORAGE_AND_SYNC, "30", SettingScope.DEVICE_LOCAL, validator = { it.matches(INT_REGEX) }),

        // Section 12: About and debug
        SettingDef("debug.logging", SettingSection.ABOUT_AND_DEBUG, "0", SettingScope.DEVICE_LOCAL, isAdvanced = true, validator = { it.matches(BOOL_REGEX) })
    )

    private val KEY_MAP = ALL.associateBy { it.key }

    fun find(key: String): SettingDef? = KEY_MAP[key]

    fun getSettingsForSection(section: SettingSection): List<SettingDef> =
        ALL.filter { it.section == section }
}
