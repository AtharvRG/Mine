// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine Author
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.resolver

import com.anchor.core.models.IntentType
import com.anchor.core.models.Node
import com.anchor.core.models.Resolution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WhichDinnerTest {

    private val resolver = Resolver()

    // Fake times
    private val timeTomorrow = 1000L * 60 * 60 * 24 * 1 // 1 day from epoch
    private val timeThursday = 1000L * 60 * 60 * 24 * 5 // 5 days from epoch

    private val dinnerTomorrow = Node(
        id = "n1", type = "event", title = "Dinner with Raj", status = "active", dueAt = timeTomorrow
    )
    private val dinnerThursday = Node(
        id = "n2", type = "event", title = "Dinner with Sam", status = "active", dueAt = timeThursday
    )

    private val memoryGraph = listOf(dinnerTomorrow, dinnerThursday)

    @Test
    fun testVagueCancel_ReturnsAmbiguous() {
        // "Cancel the dinner" -> LLM extracts intent=CANCEL, hint="the dinner", no specific time
        val resolution = resolver.resolve(
            intent = IntentType.CANCEL,
            hint = "the dinner",
            targetTimeMillis = null,
            candidates = memoryGraph
        )

        assertTrue("Should be ambiguous because both match text equally", resolution is Resolution.Ambiguous)

        val options = (resolution as Resolution.Ambiguous).options
        assertEquals(2, options.size)
        assertEquals("n1", options[0].id)
        assertEquals("n2", options[1].id)
    }

    @Test
    fun testSpecificCancel_ReturnsClearWinner() {
        // "Cancel tomorrow's dinner" -> Code parses "tomorrow" into timeTomorrow
        val resolution = resolver.resolve(
            intent = IntentType.CANCEL,
            hint = "tomorrow's dinner",
            targetTimeMillis = timeTomorrow,
            candidates = memoryGraph
        )

        assertTrue("Should be a clear winner due to exact time match", resolution is Resolution.ClearWinner)
        assertEquals("n1", (resolution as Resolution.ClearWinner).node.id)
    }
}