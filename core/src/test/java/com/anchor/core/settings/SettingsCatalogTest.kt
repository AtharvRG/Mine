// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.settings

import com.anchor.core.SharedFakeStorage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsCatalogTest {

    @Test
    fun catalogContainsAllTwelveBlueprintSections() {
        val sections = SettingSection.values()
        assertEquals(12, sections.size)
        for (section in sections) {
            val settings = SettingsCatalog.getSettingsForSection(section)
            assertTrue("Section ${section.title} should have defined settings", settings.isNotEmpty())
        }
    }

    @Test
    fun settingsManager_GetReturnsDefaultWhenUnset() = runBlocking {
        val storage = SharedFakeStorage()
        val manager = SettingsManager(storage)

        assertEquals("companion", manager.get("persona.active"))
        assertEquals("06:00", manager.get("day.start"))
        assertEquals("22:00", manager.get("day.end"))
        assertEquals("1", manager.get("nudge.enabled"))
    }

    @Test
    fun settingsManager_SetValidatesValues() = runBlocking {
        val storage = SharedFakeStorage()
        val manager = SettingsManager(storage)

        // Valid update
        val success = manager.set("day.start", "07:30")
        assertTrue("Valid time format should be accepted", success)
        assertEquals("07:30", manager.get("day.start"))

        // Invalid update (day.start must be HH:MM)
        val fail = manager.set("day.start", "invalid-time")
        assertFalse("Invalid time format should be rejected", fail)
        assertEquals("07:30", manager.get("day.start"))
    }

    @Test
    fun resetSectionToDefaults_AllowedOnlyForSectionsFourToEight() = runBlocking {
        val storage = SharedFakeStorage()
        val manager = SettingsManager(storage)

        // Mutate section 2 (AI and providers)
        manager.set("provider.rpm_default", "35")
        // Mutate section 5 (Nudges)
        manager.set("nudge.min_gap_minutes", "120")

        // Attempt reset on Section 2 (Disallowed per Blueprint 52 §3.4)
        val resetSec2 = manager.resetSectionToDefaults(SettingSection.AI_AND_PROVIDERS)
        assertFalse("Section 2 reset to defaults must be forbidden", resetSec2)
        assertEquals("35", manager.get("provider.rpm_default"))

        // Attempt reset on Section 5 (Allowed per Blueprint 52 §3.4)
        val resetSec5 = manager.resetSectionToDefaults(SettingSection.NUDGES)
        assertTrue("Section 5 reset to defaults must succeed", resetSec5)
        assertEquals("60", manager.get("nudge.min_gap_minutes"))
    }

    @Test
    fun findSetting_FindsExistingKeys() {
        val def = SettingsCatalog.find("persona.active")
        assertNotNull(def)
        assertEquals(SettingScope.SYNCED, def?.scope)
        assertEquals("companion", def?.defaultValue)
    }
}
