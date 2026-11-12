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

    // Regex 3: One-time passwords and short verification codes (4 to 8 digits, e.g. "code is 849201" or "123-456")
    private val OTP_REGEX = Regex(
        """\b(?:\d{3}[ -]\d{3}|\d{4,8})\b"""
    )

    // Regex 4: Phone numbers with standard international/domestic formatting
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
        result = OTP_REGEX.replace(result) { match ->
            val digitsOnly = match.value.filter { it.isDigit() }
            if (digitsOnly.length in 4..8) "[number]" else match.value
        }

        return result
    }
}
