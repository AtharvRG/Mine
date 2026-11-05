// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.context

object PromptSanitizer {
    /**
     * Sanitises titles and statements before they enter any prompt:
     * - Collapses newlines (\r\n, \r, \n) and consecutive whitespace into a single space
     * - Replaces pipe delimiter '|' with '-' to prevent table delimiter spoofing
     * - Hard caps string length to at most [maxLength] characters (defaults to 80)
     */
    fun sanitize(text: String, maxLength: Int = 80): String {
        return text
            .replace("\r\n", " ")
            .replace("\r", " ")
            .replace("\n", " ")
            .replace(Regex("\\s+"), " ")
            .replace("|", "-")
            .trim()
            .take(maxLength)
    }
}
