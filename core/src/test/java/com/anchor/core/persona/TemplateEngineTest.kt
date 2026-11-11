// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.persona

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TemplateEngineTest {

    private val engine = TemplateEngine()

    @Test
    fun slotInterpolation_ReplacesKnownSlotsCorrectly() {
        val rendered = engine.render(
            trigger = TriggerType.NUDGE_OVERDUE,
            persona = PersonaPresets.COMPANION,
            slots = mapOf("title" to "Grocery shopping", "count" to "3"),
            seed = 42L
        )
        assertTrue("Must contain interpolated title", rendered.contains("Grocery shopping"))
        assertFalse("Must not leave raw slot tags", rendered.contains("{title}"))
    }

    @Test
    fun slotInterpolation_HandlesMissingSlotsGracefully() {
        val rendered = engine.render(
            trigger = TriggerType.NUDGE_OVERDUE,
            persona = PersonaPresets.COMPANION,
            slots = emptyMap(),
            seed = 42L
        )
        assertFalse("Must not throw or keep unreplaced {title}", rendered.contains("{title}"))
        assertTrue("Must have non-empty text", rendered.isNotBlank())
    }

    @Test
    fun personaToneAffectsPhrasing_CofounderVsCompanion() {
        val companionText = engine.render(
            trigger = TriggerType.NUDGE_OVERDUE,
            persona = PersonaPresets.COMPANION,
            slots = mapOf("title" to "Tax filing"),
            seed = 100L
        )
        val cofounderText = engine.render(
            trigger = TriggerType.NUDGE_OVERDUE,
            persona = PersonaPresets.COFOUNDER,
            slots = mapOf("title" to "Tax filing"),
            seed = 100L
        )

        assertFalse("Different personas should produce distinct phrasings", companionText == cofounderText)
        assertTrue(companionText.contains("Tax filing"))
        assertTrue(cofounderText.contains("Tax filing"))
    }

    @Test
    fun renderIsDeterministicForSameSeed() {
        val t1 = engine.render(TriggerType.REMINDER_DEADLINE, PersonaPresets.COACH, mapOf("title" to "Workout"), seed = 12345L)
        val t2 = engine.render(TriggerType.REMINDER_DEADLINE, PersonaPresets.COACH, mapOf("title" to "Workout"), seed = 12345L)
        assertEquals("Same seed must produce identical template variant", t1, t2)
    }

    @Test
    fun renderNeverThrowsOnExtremeSeedValues() {
        val seeds = listOf(Long.MIN_VALUE, Long.MAX_VALUE, 0L, -1L, Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())
        for (seed in seeds) {
            val text = engine.render(TriggerType.REMINDER_DEADLINE, PersonaPresets.COACH, mapOf("title" to "Workout"), seed = seed)
            assertTrue("Must successfully render without index out of bounds", text.isNotBlank())
        }
    }

    @Test
    fun allTriggerTypesRenderValidTemplatesAcrossAllPresets() {
        for (trigger in TriggerType.values()) {
            for (persona in PersonaPresets.all) {
                val text = engine.render(
                    trigger = trigger,
                    persona = persona,
                    slots = mapOf("title" to "Test Task", "count" to "5", "due" to "5:00 PM"),
                    seed = 999L
                )
                assertTrue("Template for $trigger and ${persona.name} must not be blank", text.isNotBlank())
            }
        }
    }
}
