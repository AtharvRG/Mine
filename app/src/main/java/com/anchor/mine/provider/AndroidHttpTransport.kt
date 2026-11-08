// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.mine.provider

import com.anchor.core.provider.Http429Exception
import com.anchor.core.provider.HttpTransport
import com.anchor.core.provider.OfflineException
import com.anchor.core.provider.Target
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runInterruptible
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

class AndroidHttpTransport : HttpTransport {
    override suspend fun postStream(target: Target, body: String): Flow<String> = flow {
        val connection = URL(target.id).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("Authorization", "Bearer ${target.key}")
        connection.doOutput = true
        connection.connectTimeout = 10000
        connection.readTimeout = 60000

        try {
            connection.outputStream.use { os -> os.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode

            if (code == 429) {
                val retryAfter = connection.getHeaderField("Retry-After")?.toIntOrNull()
                throw Http429Exception(retryAfter)
            }
            if (code !in 200..299) {
                // B13: Cap the error body to 500 characters so Cloudflare/Nginx HTML pages don't blow up the UI State
                val err = connection.errorStream?.bufferedReader()?.use { it.readText().take(500) } ?: "HTTP $code"
                throw Exception("Provider error: $err")
            }

            connection.inputStream.bufferedReader().use { reader ->
                while (currentCoroutineContext().isActive) {
                    val line = runInterruptible { reader.readLine() } ?: break
                    if (line.isNotBlank()) emit(line)
                }
            }
        } catch (e: IOException) {
            // Blueprint 41 §2 no_network: the socket-level failures that mean "this device has no
            // usable connection". They used to escape as raw IOException and reach the controller as
            // "Error: Unable to resolve host …", which is neither actionable nor queueable.
            // Cancellation is not an IOException, so a cancelled turn still propagates untouched.
            throw when (e) {
                is UnknownHostException, is ConnectException, is SocketTimeoutException ->
                    OfflineException(e.message ?: "No network connection", e)
                else -> e
            }
        } finally {
            // Disconnect in finally guarantees the socket is severed instantly on coroutine cancellation,
            // unblocking readLine() immediately.
            connection.disconnect()
        }
    }.flowOn(Dispatchers.IO) // Entire flow runs on IO, making thread-safety clean
}