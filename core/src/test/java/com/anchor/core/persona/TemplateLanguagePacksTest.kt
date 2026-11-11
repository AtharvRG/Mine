// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.persona

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TemplateLanguagePacksTest {

    private val engine = TemplateEngine()

    @Test
    fun testEnglishPack_RendersWarmStart() {
        val result = engine.render(
            trigger = TriggerType.REMINDER_START,
            tone = PersonaTone.WARM,
            slots = mapOf("title" to "Submit taxes"),
            languageCode = "en"
        )
        assertTrue("Must contain title", result.contains("Submit taxes"))
    }

    @Test
    fun testSpanishPack_RendersWarmStart() {
        val result = engine.render(
            trigger = TriggerType.REMINDER_START,
            tone = PersonaTone.WARM,
            slots = mapOf("title" to "Pagar impuestos"),
            languageCode = "es"
        )
        assertTrue("Must render in Spanish", result.contains("Pagar impuestos") && (result.contains("empez", ignoreCase = true) || result.contains("listo", ignoreCase = true)))
    }

    @Test
    fun testSpanishPack_RendersCheckinMorning() {
        val result = engine.render(
            trigger = TriggerType.CHECKIN_MORNING,
            tone = PersonaTone.WARM,
            slots = mapOf("name" to "Carlos"),
            languageCode = "es"
        )
        assertTrue("Must render morning greeting in Spanish", result.contains("Buenos días") || result.contains("mañana"))
    }

    @Test
    fun testFallbackToEnglish_WhenLanguageUnknown() {
        val result = engine.render(
            trigger = TriggerType.REMINDER_START,
            tone = PersonaTone.WARM,
            slots = mapOf("title" to "Water plants"),
            languageCode = "unknown_lang"
        )
        assertTrue("Must fall back to English", result.contains("Water plants"))
    }

    @Test
    fun testRegistry_ContainsEnglishAndSpanish() {
        val en = TemplatePackRegistry.getPack("en")
        val es = TemplatePackRegistry.getPack("es")
        assertNotNull("English pack must exist", en)
        assertNotNull("Spanish pack must exist", es)
        assertEquals("Español", es!!.displayName)
    }
}
