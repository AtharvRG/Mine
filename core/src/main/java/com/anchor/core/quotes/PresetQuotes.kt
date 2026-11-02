// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.quotes

import com.anchor.core.models.QuoteSlot
import kotlin.math.abs

object PresetQuotes {

    private val morningQuotes = listOf(
        "Begin today with calm intention.",
        "Small steps forward build great momentum.",
        "Focus on the essential; let the rest wait.",
        "Clear your mind, take a breath, and take the first step.",
        "Today is an unwritten page. Start gently.",
        "Energy follows clarity. Decide what matters most.",
        "Start early with quiet resolve."
    )

    private val afternoonQuotes = listOf(
        "Maintain steady rhythm through the middle of the day.",
        "Pause, breathe, and recalibrate your focus.",
        "Progress is measured by patience, not haste.",
        "Finish what matters most before the sun sets.",
        "One task at a time, with complete presence.",
        "Protect your attention from midday noise.",
        "Action dispels doubt. Keep building."
    )

    private val eveningQuotes = listOf(
        "Review what was accomplished with gratitude.",
        "Allow the work of the day to rest.",
        "Unwind your thoughts as twilight settles.",
        "Evening brings closure; let go of unfinished striving.",
        "Transition from doing to simply being.",
        "The day is done. Acknowledge your efforts.",
        "Quiet the mind and make space for stillness."
    )

    private val nightQuotes = listOf(
        "Rest deeply so tomorrow meets a restored spirit.",
        "The night is for quiet healing and stillness.",
        "Sleep restores clarity that striving cannot force.",
        "Let the darkness quiet your ambitions for tonight.",
        "Peace comes with accepting that today is complete.",
        "Rest is not surrender; it is renewal.",
        "Sleep soundly. Everything can wait until dawn."
    )

    fun getPreset(slot: QuoteSlot, dayKey: String): String {
        val pool = when (slot) {
            QuoteSlot.MORNING -> morningQuotes
            QuoteSlot.AFTERNOON -> afternoonQuotes
            QuoteSlot.EVENING -> eveningQuotes
            QuoteSlot.NIGHT -> nightQuotes
        }
        val hash = abs("$slot:$dayKey".hashCode())
        val index = hash % pool.size
        return pool[index]
    }
}
