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

class ResolverSingleLowMatchChipTest {

    private val resolver = Resolver()
    private val tomorrow = 1000L * 60 * 60 * 24

    @Test
    fun testLowOverlapSoleCandidate_AsksInsteadOfNotFound() {
        // H-14 (R-19): the 0.30 text floor ran before the single-candidate branch, so a hint
        // that overlapped the only visible item below 30% came back as NotFound — the user was
        // told the item does not exist while looking at it. With exactly one candidate there is
        // nothing to disambiguate against, so the resolver must ask (chip), not deny.
        val candidates = listOf(Node("n1", "task", "Report", "active", tomorrow))
        // "quarterly report draft" -> 1 of 3 hint tokens matched = 0.33 ... use 4 tokens to land
        // under the floor: "quarterly report draft review" -> 1/4 = 0.25.
        val res = resolver.resolve(IntentType.UPDATE, "quarterly report draft review", null, candidates)

        assertTrue("Sole low-overlap candidate must ask, not say not-found: $res", res is Resolution.Ambiguous)
        val amb = res as Resolution.Ambiguous
        assertEquals(1, amb.options.size)
        assertEquals("n1", amb.options[0].id)
    }

    @Test
    fun testUnrelatedSoleCandidate_StillNotFound() {
        // Pins that the floor survives for genuinely unrelated hints: a sole candidate that
        // shares no token at all must not be offered as a chip — that would turn every typo
        // into "did you mean the only thing you have?".
        val candidates = listOf(Node("n1", "task", "Report", "active", tomorrow))
        val res = resolver.resolve(IntentType.UPDATE, "quarterly draft review", null, candidates)
        assertTrue("A zero-overlap sole candidate must stay NotFound, but was $res", res is Resolution.NotFound)
    }
}
