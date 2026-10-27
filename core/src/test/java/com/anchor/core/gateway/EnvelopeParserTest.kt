// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class EnvelopeParserTest {
    @Test
    fun testMessyOutputs() {
        val messy = """
            Here is what I found:
            ```json
            {
              "reply": "Got it.",
              "calls": [ { "tool": "propose_change", "args": { "intent": "CREATE", "minutes": 2, "nullVal": null } } ]
            }
            ```
            Hope that helps!
        """.trimIndent()
        
        val envelope = EnvelopeParser.parse(messy)
        assertNotNull(envelope)
        assertEquals("Got it.", envelope!!.reply)
        
        val args = EnvelopeParser.resolveArgs(envelope.calls[0])
        assertEquals("CREATE", args["intent"])
        assertEquals("2", args["minutes"]) // Number safely converted to String
        assertEquals("null", args["nullVal"]) // Null safely handled
    }
}
