// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceSpeechSanitizerTest {

    @Test
    fun testCleanText_StripsCodeFencesAndMarkdown() {
        val input = """
            Hello! Here is the plan:
            ```kotlin
            fun doWork() { println("working") }
            ```
            Please run `gradle build` and check [the docs](https://example.com).
            *Important*: don't forget the **deadline**!
            <data>tool result</data>
        """.trimIndent()

        val cleaned = VoiceSpeechSanitizer.cleanText(input)
        assertFalse(cleaned.contains("fun doWork"))
        assertFalse(cleaned.contains("gradle build"))
        assertFalse(cleaned.contains("https://example.com"))
        assertFalse(cleaned.contains("<data>"))
        assertTrue(cleaned.contains("Hello! Here is the plan:"))
        assertTrue(cleaned.contains("the docs"))
        assertTrue(cleaned.contains("Important: don't forget the deadline!"))
    }

    @Test
    fun testChunkSentences_SplitsIntoIndividualSentences() {
        val input = "Here is the first step. Are you ready? Let's begin now!"
        val chunks = VoiceSpeechSanitizer.chunkSentences(input)

        assertEquals(3, chunks.size)
        assertEquals("Here is the first step.", chunks[0])
        assertEquals("Are you ready?", chunks[1])
        assertEquals("Let's begin now!", chunks[2])
    }

    @Test
    fun testChunkSentences_CapsAtMaxCharsAndAppendsNotice() {
        val sentence1 = "This is a normal introductory sentence."
        val longContent = "A".repeat(1500)
        val input = "$sentence1 $longContent"

        val chunks = VoiceSpeechSanitizer.chunkSentences(input, maxChars = 100)
        val combined = chunks.joinToString(" ")

        assertTrue("Truncated text must end with 'The rest is in the chat.'", combined.endsWith("The rest is in the chat."))
        assertTrue("Spoken character count must stay bounded around maxChars", combined.length <= 150)
    }
}
