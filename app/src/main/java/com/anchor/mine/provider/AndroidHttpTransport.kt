// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.mine.provider

import com.anchor.core.provider.Http429Exception
import com.anchor.core.provider.HttpStatusException
import com.anchor.core.provider.HttpTransport
import com.anchor.core.provider.OfflineException
import com.anchor.core.provider.ProviderTimeoutException
import com.anchor.core.provider.Target
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

class AndroidHttpTransport : HttpTransport {

    companion object {
        internal fun parseRetryAfter(v: String?): Int? = v?.trim()?.let { s ->
            s.toIntOrNull() ?: runCatching {
                val at = java.time.ZonedDateTime.parse(s, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
                ((at - System.currentTimeMillis()) / 1000L).toInt().coerceAtLeast(1)
            }.getOrNull()
        }

        internal fun readCapped(s: java.io.InputStream?, max: Int): String {
            if (s == null) return ""
            val buf = ByteArray(max)
            var n = 0
            s.use {
                while (n < max) {
                    val r = it.read(buf, n, max - n)
                    if (r < 0) break
                    n += r
                }
            }
            return String(buf, 0, n, Charsets.UTF_8)
        }
    }

    override suspend fun postStream(target: Target, body: String): Flow<String> = callbackFlow {
        val conn = (URL(target.id).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "text/event-stream")
            setRequestProperty("Authorization", "Bearer ${target.key}")
            connectTimeout = 15_000
            readTimeout = 120_000 // Reasoning models think > 60s before token 1
        }
        val io = launch(Dispatchers.IO) {
            try {
                conn.outputStream.use { os -> os.write(body.toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                if (code == 429) {
                    throw Http429Exception(parseRetryAfter(conn.getHeaderField("Retry-After")))
                }
                if (code !in 200..299) {
                    throw HttpStatusException(code, readCapped(conn.errorStream, 2048))
                }
                conn.inputStream.bufferedReader().use { r ->
                    while (isActive) {
                        val line = r.readLine() ?: break
                        if (line.isNotBlank()) send(line)
                    }
                }
                close()
            } catch (e: CancellationException) {
                throw e
            } catch (e: SocketTimeoutException) {
                close(ProviderTimeoutException("The provider did not respond in time", e))
            } catch (e: UnknownHostException) {
                close(OfflineException(e.message ?: "DNS lookup failed", e))
            } catch (e: ConnectException) {
                close(OfflineException(e.message ?: "Connection refused", e))
            } catch (e: Throwable) {
                close(e)
            }
        }
        awaitClose {
            runCatching { conn.disconnect() }
            io.cancel()
        }
    }.buffer(Channel.UNLIMITED)
}
