// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.mine.ambient

import com.anchor.core.ambient.RivaAsrProto
import com.anchor.core.provider.KeyPool
import com.anchor.core.provider.RateLimiter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Transcribes a speech batch with NVIDIA NIM's hosted Whisper (Riva `Recognize` over gRPC on
 * NVCF), using the same key pool as the chat provider: least-used key first, a key that returns
 * RESOURCE_EXHAUSTED / 429 sits out a minute and the next key is tried at once.
 *
 * gRPC is spoken directly over HTTP/2 (OkHttp) with a hand-written protobuf codec
 * ([RivaAsrProto]), so the app needs no grpc-java, protobuf runtime or code generation.
 *
 * Only audio of windows that passed the presence gate ever reaches this class.
 */
class NimWhisperClient(
    private val limiter: RateLimiter,
    private val functionId: String = DEFAULT_FUNCTION_ID,
    private val host: String = DEFAULT_HOST,
    private val http: OkHttpClient = sharedClient,
) {
    sealed interface Result {
        data class Text(val transcript: String, val keyUsed: String) : Result
        /** Every key is rate-limited right now. */
        data object AllKeysBusy : Result
        /** NVIDIA refused the keys (wrong, revoked, or no access to this function). */
        data class AuthFailed(val message: String) : Result
        data class Failed(val message: String) : Result
    }

    suspend fun transcribe(
        samples: FloatArray,
        keys: List<String>,
        languageCode: String = "en",
        rpmPerKey: Int = 40,
    ): Result = withContext(Dispatchers.IO) {
        val ordered = KeyPool.order(keys, limiter, rpmPerKey, LIMITER_PREFIX)
        if (ordered.isEmpty()) return@withContext Result.Failed("No NVIDIA key")
        val body = RivaAsrProto.frame(
            RivaAsrProto.encodeRecognizeRequest(RivaAsrProto.Config(languageCode = languageCode), RivaAsrProto.floatToPcm16(samples))
        )
        var lastError: Result = Result.AllKeysBusy
        for (key in ordered) {
            // Shares the chat limiter bucket: NVIDIA counts every request a key makes.
            if (!limiter.canRequest(key, rpmPerKey)) continue
            limiter.recordRequest(key)
            val r = try {
                call(key, body, languageCode)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                Result.Failed("Network: ${e.javaClass.simpleName}")
            }
            when (r) {
                is Result.Text -> { limiter.recordSuccess(key); return@withContext r }
                Result.AllKeysBusy -> { limiter.apply429Cooldown(key, KeyPool.BENCH_SECONDS_ON_429); lastError = r }
                is Result.AuthFailed -> { limiter.apply429Cooldown(key, AUTH_BENCH_SECONDS); lastError = r }
                is Result.Failed -> return@withContext r // a server error is not the key's fault: don't burn the others
            }
        }
        lastError
    }

    private fun call(key: String, framed: ByteArray, languageCode: String): Result {
        val request = Request.Builder()
            .url("https://$host${RivaAsrProto.METHOD_PATH}")
            .header("authorization", "Bearer $key")
            .header("function-id", functionId)
            .header("te", "trailers")
            .header("grpc-accept-encoding", "identity")
            .header("user-agent", "mine-android grpc-over-okhttp")
            .post(framed.toRequestBody(GRPC))
            .build()
        http.newCall(request).execute().use { resp ->
            val bytes = resp.body?.bytes() ?: ByteArray(0)
            // Trailers-only responses (errors) carry grpc-status in the headers.
            val trailers = runCatching { resp.trailers() }.getOrNull()
            val status = (trailers?.get("grpc-status") ?: resp.header("grpc-status"))?.toIntOrNull()
            val message = trailers?.get("grpc-message") ?: resp.header("grpc-message") ?: resp.message
            if (RivaAsrProto.isRateLimited(status, resp.code)) return Result.AllKeysBusy
            if (resp.code == 401 || resp.code == 403 || status == 16 || status == 7) return Result.AuthFailed(message.take(160))
            if (resp.code != 200 || (status != null && status != 0)) return Result.Failed("HTTP ${resp.code}, gRPC ${status ?: "?"}: ${message.take(160)}")
            val messages = RivaAsrProto.unframe(bytes)
            val text = messages.flatMap { RivaAsrProto.decodeTranscripts(it) }.joinToString(" ").trim()
            return Result.Text(text, key)
        }
    }

    companion object {
        /** Whisper large-v3 on NVIDIA's cloud functions (from build.nvidia.com). Overridable in settings. */
        const val DEFAULT_FUNCTION_ID = "b702f636-f60c-4a3d-a6f4-f3568c13bd7d"
        const val DEFAULT_HOST = "grpc.nvcf.nvidia.com"
        const val LIMITER_PREFIX = ""
        private const val AUTH_BENCH_SECONDS = 15 * 60
        private val GRPC = "application/grpc".toMediaType()

        private val sharedClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .callTimeout(45, TimeUnit.SECONDS)
                .build()
        }
    }
}
