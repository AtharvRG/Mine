package com.mine.app.data.remote

import android.util.Log
import com.mine.app.data.remote.dto.NimChatRequest
import com.mine.app.data.remote.dto.NimChatResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
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

    // We use a local JSON parser just for debugging the raw text
    private val jsonParser = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun getChatCompletion(apiKey: String, request: NimChatRequest): NimChatResponse {
        val response = client.post(baseUrl) {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer $apiKey")
            setBody(request)
        }

        // Grab the raw JSON string from NVIDIA
        val rawJson = response.bodyAsText()

        // Print it to Android Studio Logcat so we can see exactly what NVIDIA is complaining about
        Log.e("NIM_API_DEBUG", "Raw Response from NVIDIA: $rawJson")

        // Now parse it into our Kotlin object
        return jsonParser.decodeFromString(rawJson)
    }
}
