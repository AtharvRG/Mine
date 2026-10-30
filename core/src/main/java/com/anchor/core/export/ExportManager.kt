// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.export

import java.io.File

data class ExportStats(
    val messages: Int,
    val nodes: Int,
    val events: Int,
    val verified: Boolean
)

interface ExportManager {
    suspend fun exportTo(file: File, passphrase: String, includeApiKeys: Boolean, includeEmbeddings: Boolean): ExportStats
    suspend fun verifyOrImport(file: File, passphrase: String, isImport: Boolean): ExportStats
}
