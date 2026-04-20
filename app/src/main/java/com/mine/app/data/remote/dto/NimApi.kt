package com.mine.app.data.remote

import com.mine.app.data.remote.dto.NimChatRequest
import com.mine.app.data.remote.dto.NimChatResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType

class NimApi(private val client: HttpClient) {

    // The base URL for NVIDIA NIM
    private val baseUrl = "https://integrate.api.nvidia.com/v1/chat/completions"

    suspend fun getChatCompletion(apiKey: String, request: NimChatRequest): NimChatResponse {
        return client.post(baseUrl) {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer $apiKey")
            setBody(request)
        }.body()
    }
}
