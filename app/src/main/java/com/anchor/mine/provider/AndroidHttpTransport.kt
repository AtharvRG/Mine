// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine Author
// Part of Mine. See LICENSE in the repository root.

package com.anchor.mine.provider

import com.anchor.core.provider.Http429Exception
import com.anchor.core.provider.HttpTransport
import com.anchor.core.provider.Target
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

class AndroidHttpTransport : HttpTransport {
    override suspend fun postStream(target: Target, body: String): Flow<String> = flow {
        // For our architecture, target.id holds the Base URL
        val connection = URL(target.id).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("Authorization", "Bearer ${target.key}")
        connection.doOutput = true
        connection.connectTimeout = 10000
        connection.readTimeout = 60000

        connection.outputStream.use { os ->
            os.write(body.toByteArray(Charsets.UTF_8))
        }

        val code = connection.responseCode
        if (code == 429) {
            val retryAfter = connection.getHeaderField("Retry-After")?.toIntOrNull()
            throw Http429Exception(retryAfter)
        }
        if (code !in 200..299) {
            val err = connection.errorStream?.bufferedReader()?.readText() ?: "HTTP $code"
            throw Exception("Provider error: $err")
        }

        connection.inputStream.bufferedReader().use { reader ->
            var line = reader.readLine()
            while (line != null) {
                if (line.isNotBlank()) emit(line)
                line = reader.readLine()
            }
        }
    }.flowOn(Dispatchers.IO)
}