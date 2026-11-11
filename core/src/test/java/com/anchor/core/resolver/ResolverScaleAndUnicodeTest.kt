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

class ResolverScaleAndUnicodeTest {

    private val resolver = Resolver()
    private val dayMillis = 1000L * 60 * 60 * 24

    @Test
    fun testRecencyBoost_BreaksTiesDecisively() {
        val candidates = listOf(
            Node("n1", "task", "Team Sync", "active", 1000L),
            Node("n2", "task", "Team Retrospective", "active", 1000L)
        )

        // Without recency: both match "team" with 1.0 text score, 0 margin -> Ambiguous
        val resNoRecency = resolver.resolve(IntentType.UPDATE, "team", null, candidates)
        assertTrue("Without recency, both should be ambiguous: $resNoRecency", resNoRecency is Resolution.Ambiguous)

        // With recency: n1 was discussed in recent turns -> ClearWinner
        val resWithRecency = resolver.resolve(
            IntentType.UPDATE,
            "team",
            null,
            candidates,
            recentlyDiscussedNodeIds = listOf("n1")
        )
        assertTrue("With recency, n1 should win: $resWithRecency", resWithRecency is Resolution.ClearWinner)
        assertEquals("n1", (resWithRecency as Resolution.ClearWinner).node.id)
    }

    @Test
    fun testCommandWordsFiltered_NotFoundWhenNoContentTokens() {
        val candidates = listOf(
            Node("n1", "task", "Buy groceries", "active")
        )
        // Hint contains only conversational filler and command words
        val res = resolver.resolve(IntentType.CANCEL, "please can you cancel it", null, candidates)
        assertTrue("Hint with only command words/stopwords should return NotFound", res is Resolution.NotFound)
    }

    @Test
    fun testExactMatch_OnActionNamedItem() {
        // A task literally named "Update" or "Please"
        val candidates = listOf(
            Node("n1", "task", "Update", "active"),
            Node("n2", "task", "Other Task", "active")
        )
        val res = resolver.resolve(IntentType.UPDATE, "update", null, candidates)
        assertTrue("Exact match should resolve node named after command word", res is Resolution.ClearWinner)
        assertEquals("n1", (res as Resolution.ClearWinner).node.id)
    }

    @Test
    fun testUnicodeCurlyQuotesAndAccents() {
        val candidates = listOf(
            Node("n1", "event", "Mother’s Day Dinner", "active", 2 * dayMillis),
            Node("n2", "event", "Frühstück mit Anna", "active", 3 * dayMillis),
            Node("n3", "event", "Reunión de Negocios", "active", 4 * dayMillis)
        )

        // 1. Curly quote match with straight quote
        val res1 = resolver.resolve(IntentType.CANCEL, "mother's day dinner", null, candidates)
        assertTrue("Curly quote should match straight quote: $res1", res1 is Resolution.ClearWinner)
        assertEquals("n1", (res1 as Resolution.ClearWinner).node.id)

        // 2. German umlauts
        val res2 = resolver.resolve(IntentType.CANCEL, "frühstück", null, candidates)
        assertTrue("Umlauts should resolve cleanly: $res2", res2 is Resolution.ClearWinner)
        assertEquals("n2", (res2 as Resolution.ClearWinner).node.id)

        // 3. Spanish accents
        val res3 = resolver.resolve(IntentType.CANCEL, "reunión", null, candidates)
        assertTrue("Accented characters should resolve cleanly: $res3", res3 is Resolution.ClearWinner)
        assertEquals("n3", (res3 as Resolution.ClearWinner).node.id)
    }

    @Test
    fun testClosingIntentStatusFit_PrefersOpenNode() {
        val candidates = listOf(
            Node("n_closed", "task", "Prepare tax return", "completed"),
            Node("n_open", "task", "Prepare quarterly tax", "active")
        )
        // Hint "tax" matches both, but intent is CANCEL (closing intent).
        // The completed task has status fit 0.0, the open task has status fit 1.0.
        // Therefore, n_open has a decisive total margin.
        val res = resolver.resolve(IntentType.CANCEL, "tax", null, candidates)
        assertTrue("Closing intent should favor the active task over completed: $res", res is Resolution.ClearWinner)
        assertEquals("n_open", (res as Resolution.ClearWinner).node.id)
    }
}
