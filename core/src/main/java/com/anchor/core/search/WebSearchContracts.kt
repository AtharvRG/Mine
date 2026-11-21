// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.search

import kotlinx.serialization.Serializable

@Serializable
data class WebSearchResult(
    val title: String,
    val url: String,
    val snippet: String,
    val pageContent: String? = null
)

interface WebSearchEngine {
    suspend fun search(query: String, maxResults: Int = 3): List<WebSearchResult>
    suspend fun fetchPage(url: String): String?
}
