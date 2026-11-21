// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.mine.search

import com.anchor.core.search.WebSearchEngine
import com.anchor.core.search.WebSearchResult
import com.anchor.core.search.WebSecurityGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder

class SafeWebSearchEngine : WebSearchEngine {

    companion object {
        private const val USER_AGENT = "Mozilla/5.0 (Android 14; Mobile; rv:120.0) Gecko/120.0 Firefox/120.0"
        private const val TIMEOUT_MS = 6000
        private const val MAX_BYTES = 2 * 1024 * 1024 // 2 MB
    }

    override suspend fun search(query: String, maxResults: Int): List<WebSearchResult> = withContext(Dispatchers.IO) {
        val trimmedQuery = query.trim()
        if (trimmedQuery.isBlank()) return@withContext emptyList()

        try {
            val searchUrl = "https://html.duckduckgo.com/html/?q=${URLEncoder.encode(trimmedQuery, "UTF-8")}"
            if (!WebSecurityGuard.isSafeUrl(searchUrl)) return@withContext emptyList()

            val conn = (URL(searchUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "text/html,application/xhtml+xml")
            }

            val html = try {
                if (conn.responseCode !in 200..299) {
                    return@withContext emptyList()
                }
                readBoundedText(conn.inputStream, MAX_BYTES)
            } finally {
                conn.disconnect()
            }

            val doc = Jsoup.parse(html)
            val resultElements = doc.select(".result, .web-result")
            val results = mutableListOf<WebSearchResult>()

            for (elem in resultElements) {
                if (results.size >= maxResults) break
                val titleElem = elem.select(".result__title a, .result__a").firstOrNull() ?: continue
                val snippetElem = elem.select(".result__snippet").firstOrNull()

                var rawUrl = titleElem.attr("href")
                // DuckDuckGo wraps outbound links in /l/?uddg=...
                if (rawUrl.contains("uddg=")) {
                    val uddgParam = rawUrl.substringAfter("uddg=").substringBefore("&")
                    rawUrl = try { URLDecoder.decode(uddgParam, "UTF-8") } catch (_: Exception) { rawUrl }
                }

                if (!rawUrl.startsWith("http")) continue
                if (!WebSecurityGuard.isSafeUrl(rawUrl)) continue

                val title = titleElem.text().trim()
                val snippet = snippetElem?.text()?.trim() ?: ""

                if (title.isNotBlank()) {
                    results.add(
                        WebSearchResult(
                            title = title,
                            url = rawUrl,
                            snippet = snippet
                        )
                    )
                }
            }

            // Fetch top 2-3 result pages in parallel for enriched context
            if (results.isNotEmpty()) {
                coroutineScope {
                    val topCount = minOf(3, results.size)
                    val deferredList = (0 until topCount).map { idx ->
                        async {
                            idx to fetchPage(results[idx].url)
                        }
                    }
                    val contents = deferredList.awaitAll()
                    for ((idx, content) in contents) {
                        if (!content.isNullOrBlank()) {
                            results[idx] = results[idx].copy(pageContent = content)
                        }
                    }
                }
            }

            results
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun fetchPage(url: String): String? = withContext(Dispatchers.IO) {
        var currentUrl = url
        var redirects = 0
        val maxRedirects = 3

        while (redirects <= maxRedirects) {
            if (!WebSecurityGuard.isSafeUrl(currentUrl, resolveDns = true)) return@withContext null

            var conn: HttpURLConnection? = null
            try {
                conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = TIMEOUT_MS
                    readTimeout = TIMEOUT_MS
                    setRequestProperty("User-Agent", USER_AGENT)
                    setRequestProperty("Accept", "text/html,application/xhtml+xml")
                    instanceFollowRedirects = false
                }

                val responseCode = conn.responseCode
                if (responseCode in setOf(301, 302, 303, 307, 308)) {
                    val location = conn.getHeaderField("Location")
                    conn.disconnect()
                    if (location.isNullOrBlank()) return@withContext null

                    val nextUrl = try {
                        URL(URL(currentUrl), location).toString()
                    } catch (_: Exception) {
                        return@withContext null
                    }
                    currentUrl = nextUrl
                    redirects++
                    continue
                }

                if (responseCode !in 200..299) {
                    conn.disconnect()
                    return@withContext null
                }

                val html = readBoundedText(conn.inputStream, MAX_BYTES)
                conn.disconnect()

                val doc = Jsoup.parse(html)
                doc.select("script, style, nav, footer, header, aside, iframe, noscript, svg").remove()

                val mainContent = doc.select("article, main, .content, #content, body").firstOrNull()
                val text = mainContent?.text() ?: doc.body().text()

                return@withContext text.take(2500).trim()
            } catch (_: Exception) {
                conn?.disconnect()
                return@withContext null
            }
        }
        null
    }

    private fun readBoundedText(inputStream: InputStream, maxBytes: Int): String {
        val buffer = ByteArray(8192)
        val baos = ByteArrayOutputStream()
        var totalRead = 0
        inputStream.use { stream ->
            while (true) {
                val read = stream.read(buffer)
                if (read == -1) break
                val toWrite = minOf(read, maxBytes - totalRead)
                if (toWrite > 0) {
                    baos.write(buffer, 0, toWrite)
                    totalRead += toWrite
                }
                if (totalRead >= maxBytes) break
            }
        }
        return baos.toString(Charsets.UTF_8.name())
    }
}
