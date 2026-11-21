// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.ambient

/**
 * Scrubs sensitive numeric sequences (credit cards, OTPs, SSNs, phone numbers, ID numbers)
 * into `[number]` before ambient transcripts are passed to LLM extraction or storage.
 * Blueprint 61 §3.5.
 */
object NumberScrubber {

    // Regex 1: Credit cards (13 to 19 digits, possibly space or hyphen delimited)
    private val CREDIT_CARD_REGEX = Regex(
        """\b(?:\d[ -]*?){13,19}\b"""
    )

    // Regex 2: Formatted SSN / Tax IDs (e.g. 123-45-6789 or 9 digits)
    private val SSN_REGEX = Regex(
        """\b\d{3}-\d{2}-\d{4}\b"""
    )

    // Regex 3: Standalone one-time passwords / auth codes (6 to 8 digits, or 3-3 format e.g. "123-456" or "849201")
    private val STANDALONE_OTP_REGEX = Regex(
        """\b(?:\d{3}[ -]\d{3}|\d{6,8})\b"""
    )

    // Regex 4: Keyword-preceded verification/PIN codes (4 to 8 digits, e.g. "code is 1234", "pin 5678")
    private val KEYWORD_OTP_REGEX = Regex(
        """(?i)\b(?:code|otp|pin|passcode|password|verification|secret)\s*(?:is|:|=)?\s*(\d{4,8})\b"""
    )

    // Regex 5: Phone numbers with standard international/domestic formatting
    private val PHONE_REGEX = Regex(
        """\b(?:\+?\d{1,3}[-.\s]?)?\(?\d{3}\)?[-.\s]?\d{3}[-.\s]?\d{4}\b"""
    )

    /**
     * Scrubs sensitive numbers from text, replacing them with [number].
     */
    fun scrub(input: String): String {
        if (input.isBlank()) return input

        var result = CREDIT_CARD_REGEX.replace(input) { match ->
            val digitsOnly = match.value.filter { it.isDigit() }
            if (digitsOnly.length in 13..19) "[number]" else match.value
        }

        result = SSN_REGEX.replace(result, "[number]")
        result = PHONE_REGEX.replace(result, "[number]")

        // Replace keyword-preceded 4-8 digit codes (e.g. "code: 1234" -> "code: [number]")
        result = KEYWORD_OTP_REGEX.replace(result) { match ->
            val full = match.value
            val digits = match.groups[1]?.value ?: ""
            if (digits.isNotEmpty()) {
                full.replace(digits, "[number]")
            } else {
                full
            }
        }

        // Replace standalone 6-8 digit or 3-3 format OTPs
        result = STANDALONE_OTP_REGEX.replace(result) { match ->
            val digitsOnly = match.value.filter { it.isDigit() }
            if (digitsOnly.length in 6..8) "[number]" else match.value
        }

        return result
    }
}
