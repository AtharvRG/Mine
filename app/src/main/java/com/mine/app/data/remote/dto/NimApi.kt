package com.mine.app.data.remote

import android.util.Log
import com.mine.app.data.remote.dto.NimChatRequest
import com.mine.app.data.remote.dto.NimChatResponse
import com.mine.app.data.remote.dto.NimEmbeddingRequest
import com.mine.app.data.remote.dto.NimEmbeddingResponse
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json

class NimApi(private val client: HttpClient, private val rateLimiter: com.mine.app.util.RateLimiter) {

    private val baseUrl = "https://integrate.api.nvidia.com/v1/chat/completions"
    private val jsonParser = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun getChatCompletion(apiKey: String, request: NimChatRequest): NimChatResponse {
        rateLimiter.waitAndConsume(apiKey)
        val response = client.post(baseUrl) {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer $apiKey")
            setBody(request)
        }

        val rawJson = response.bodyAsText()
        if (!response.status.isSuccess()) {
            Log.e("NIM_API_DEBUG", "API Error: ${response.status.value} - $rawJson")
            throw Exception("NVIDIA API Error: ${response.status.value}")
        }
        Log.e("NIM_API_DEBUG", "Raw Response from NVIDIA: $rawJson")

        return jsonParser.decodeFromString(rawJson)
    }

    suspend fun getEmbeddings(apiKey: String, request: NimEmbeddingRequest): NimEmbeddingResponse {
        rateLimiter.waitAndConsume(apiKey)
        val response = client.post("https://integrate.api.nvidia.com/v1/embeddings") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer $apiKey")
            setBody(request)
        }

        val rawJson = response.bodyAsText()
        if (!response.status.isSuccess()) {
            Log.e("NIM_API_DEBUG", "Embedding API Error: ${response.status.value} - $rawJson")
            throw Exception("NVIDIA Embedding Error: ${response.status.value}")
        }
        return jsonParser.decodeFromString(rawJson)
    }

}
