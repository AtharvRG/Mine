// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.persona

import com.anchor.core.persona.AboutYouInterviewer.Turn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AboutYouInterviewerTest {

    @Test fun envelopeGivesReplyAndOnlyWellFormedFacts() {
        val raw = """```json
            {"reply":"Night shifts sound rough. How do you sleep after?",
             "facts":[{"statement":"Works night shifts as a nurse.","category":"identity","confidence":0.95},
                      {"statement":"x","category":"identity"},
                      {"statement":"Drinks coffee at 2am.","category":"made_up","confidence":3}]}
            ```"""
        val r = AboutYouInterviewer.parse(raw)
        assertEquals("Night shifts sound rough. How do you sleep after?", r.text)
        assertEquals(2, r.facts.size)
        assertEquals("other", r.facts[1].category)          // unknown category → other
        assertEquals(1.0, r.facts[1].confidence, 0.0)        // clamped
    }

    @Test fun plainTextIsAReplyWithNoFacts() {
        val r = AboutYouInterviewer.parse("What do you do for fun?")
        assertEquals("What do you do for fun?", r.text)
        assertTrue(r.facts.isEmpty())
    }

    @Test fun shortAnswersMakeItStaleAndTheTopicMoves() {
        val h = listOf(Turn("assistant", "What's your work like?"), Turn("user", "fine"), Turn("assistant", "Busy lately?"), Turn("user", "kinda"))
        assertTrue(AboutYouInterviewer.isStale(h))
        assertFalse(AboutYouInterviewer.isStale(h.dropLast(1) + Turn("user", "Yes, we're shipping a big release on Friday and it's chaos")))
        val q = AboutYouInterviewer.localQuestion(h)
        assertTrue(q.isNotBlank())
    }

    @Test fun promptListsKnownFactsSoTheyAreNotAskedAgain() {
        val body = AboutYouInterviewer.requestBody("Asha", listOf("Lives in Pune."), listOf(Turn("user", "hi")), cookTopic = "a perfect weekend")
        assertTrue(body.contains("Lives in Pune."))
        assertTrue(body.contains("a perfect weekend"))
        assertTrue(body.contains("\"stream\":true"))
    }

    @Test fun historyRoundTripsAndIsCapped() {
        val h = (1..40).map { Turn(if (it % 2 == 0) "user" else "assistant", "t$it") }
        val back = AboutYouInterviewer.decodeHistory(AboutYouInterviewer.encodeHistory(h))
        assertEquals(30, back.size)
        assertEquals("t40", back.last().text)
        assertTrue(AboutYouInterviewer.decodeHistory("not json").isEmpty())
    }
}
