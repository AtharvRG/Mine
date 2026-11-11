// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.ui

/**
 * Version update notification card catalog (Blueprint 90 §3.3 / Roadmap P1.1 Item 10).
 */
object WhatsNewCatalog {

    const val CURRENT_VERSION = "1.1.0"
    const val SETTING_VERSION_NOTIFIED = "app.version_notified"

    fun shouldShowWhatsNew(lastNotifiedVersion: String?): Boolean {
        if (lastNotifiedVersion == null) return true
        return lastNotifiedVersion != CURRENT_VERSION
    }

    fun buildWhatsNewCard(): String {
        return """
✨ **Welcome to Mine v1.1!**

Here is what's new in this release:
• ⚡ **Offline Quick Add**: Instantly schedule tasks and alarms offline without waiting for connectivity.
• 🔄 **Habits & Streaks**: Recurring reminders with daily streak calculation and grace windows.
• 🔍 **Hybrid Vector Search**: On-device EmbeddingGemma 2 vectors fused with SQLite FTS5 BM25.
• 🛡️ **Direct-Boot Safety Net**: Alarms fire reliably even after a device restart before phone unlock.
• 📄 **Readable Markdown Export**: Clean, plain-text human-readable backup archive.
• 🌐 **Multi-Target Fallback**: Automatic, resilient failover across multiple AI providers.
        """.trimIndent()
    }
}
