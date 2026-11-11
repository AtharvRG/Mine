// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test

class EnvelopeParserTest {
    @Test
    fun testMessyOutputs_And_NullDropping() {
        val messy = """
            Here is what I found:
            ```json
            {
              "reply": "Got it.",
              "calls": [ { "tool": "propose_change", "args": { "intent": "CREATE", "minutes": 2, "nullVal": null, "strNull": "null", "empty": "" } } ]
            }
            ```
            Hope that helps!
        """.trimIndent()
        
        val envelope = EnvelopeParser.parse(messy)
        assertNotNull(envelope)
        assertEquals("Got it.", envelope!!.reply)
        
        val args = EnvelopeParser.resolveArgs(envelope.calls[0])
        assertEquals("CREATE", args["intent"])
        assertEquals("2", args["minutes"])
        
        // A1: Nulls, "null", and empty strings must be completely dropped
        assertFalse(args.containsKey("nullVal"))
        assertFalse(args.containsKey("strNull"))
        assertFalse(args.containsKey("empty"))
    }

    @Test
    fun testLenientFallback_ExtractsReplyAndCallsFromTruncatedOrUnescapedJson() {
        // Unescaped quote inside reply string breaking strict JSON decoders
        val brokenJson = """
            {
              "reply": "I have scheduled your reminder for "Doctor Appointment" at 10 AM.",
              "calls": [ { "tool": "propose_change", "args": { "intent": "CREATE", "hint": "Doctor" } } ]
            }
        """.trimIndent()

        val parsed = EnvelopeParser.parse(brokenJson)
        assertNotNull("Lenient parser must recover broken JSON", parsed)
        assertEquals("I have scheduled your reminder for \"Doctor Appointment\" at 10 AM.", parsed!!.reply)
        assertEquals(1, parsed.calls.size)
        assertEquals("propose_change", parsed.calls[0].tool)
    }

    @Test
    fun testTruncatedJson_ExtractsReplyCleanly() {
        val truncated = """{"reply": "Sure, I am on it right now"""
        val parsed = EnvelopeParser.parse(truncated)
        assertNotNull(parsed)
        assertEquals("Sure, I am on it right now", parsed!!.reply)
    }
}
