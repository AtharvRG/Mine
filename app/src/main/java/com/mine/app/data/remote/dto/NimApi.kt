package com.mine.app.data.remote

import android.util.Log
import com.mine.app.data.remote.dto.NimChatRequest
import com.mine.app.data.remote.dto.NimChatResponse
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.serialization.json.Json

class NimApi(private val client: HttpClient) {

    private val baseUrl = "https://integrate.api.nvidia.com/v1/chat/completions"
    private val jsonParser = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun getChatCompletion(apiKey: String, request: NimChatRequest): NimChatResponse {
        val response = client.post(baseUrl) {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer $apiKey")
            setBody(request)
        }

        val rawJson = response.bodyAsText()
        Log.e("NIM_API_DEBUG", "Raw Response from NVIDIA: $rawJson")

        return jsonParser.decodeFromString(rawJson)
    }
}
