// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
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
    private val timeTomorrow = 1000L * 60 * 60 * 24 * 1
    private val timeThursday = 1000L * 60 * 60 * 24 * 5

    @Test
    fun testVagueCancel_TwoDinners_ReturnsAmbiguous() {
        val candidates = listOf(
            Node("n1", "event", "Dinner with Raj", "active", timeTomorrow),
            Node("n2", "event", "Dinner with Sam", "active", timeThursday)
        )
        val res = resolver.resolve(IntentType.CANCEL, "dinner", null, candidates)
        assertTrue(res is Resolution.Ambiguous)
    }

    @Test
    fun testCancel_OneDinner_ReturnsClearWinner() {
        val candidates = listOf(Node("n1", "event", "Dinner with Raj", "active", timeTomorrow))
        val res = resolver.resolve(IntentType.CANCEL, "cancel dinner", null, candidates)
        assertTrue(res is Resolution.ClearWinner)
        assertEquals("n1", (res as Resolution.ClearWinner).node.id)
    }

    @Test
    fun testTwoDifferentEvents_ReturnsClearWinner() {
        val candidates = listOf(
            Node("n1", "event", "Dinner with Shyam", "active", timeTomorrow),
            Node("n2", "event", "Cafe meet with Deux", "active", timeTomorrow)
        )
        val res = resolver.resolve(IntentType.CANCEL, "dinner with shyam", null, candidates)
        assertTrue(res is Resolution.ClearWinner)
        assertEquals("n1", (res as Resolution.ClearWinner).node.id)
    }

    @Test
    fun testUnrelatedHint_NotFound() {
        val candidates = listOf(Node("n1", "event", "Dinner with Raj", "active", timeTomorrow))
        assertTrue(resolver.resolve(IntentType.CANCEL, "cancel gym", null, candidates) is Resolution.NotFound)
    }

    @Test
    fun testStopwordsFiltered_NotFound() {
        val candidates = listOf(Node("n1", "event", "Dinner with Raj", "active", timeTomorrow))
        assertTrue(resolver.resolve(IntentType.CANCEL, "cancel the with", null, candidates) is Resolution.NotFound)
    }

    @Test
    fun testSubstringNotMatched_NotFound() {
        // "tea" should not match inside "steam"
        val candidates = listOf(Node("n3", "task", "Blow off steam", "active"))
        assertTrue(resolver.resolve(IntentType.CANCEL, "tea", null, candidates) is Resolution.NotFound)
    }

    @Test
    fun testHindiUnicodeResolution() {
        val candidates = listOf(
            Node("n1", "event", "श्याम के साथ बैठक", "active", timeTomorrow),
            Node("n2", "event", "राम के साथ बैठक", "active", timeThursday)
        )

        // Vague word "बैठक" (Meeting) -> Ambiguous
        val ambiguous = resolver.resolve(IntentType.CANCEL, "बैठक", null, candidates)
        assertTrue(ambiguous is Resolution.Ambiguous)

        // Highly specific distinguishing word "श्याम" -> ClearWinner n1
        val winner = resolver.resolve(IntentType.CANCEL, "श्याम", null, candidates)
        assertTrue("Expected ClearWinner, but got $winner", winner is Resolution.ClearWinner)
        assertEquals("n1", (winner as Resolution.ClearWinner).node.id)
    }
}