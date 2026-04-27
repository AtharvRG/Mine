package com.mine.app.data.remote

import com.mine.app.data.remote.dto.GroqTranscriptionResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders

class GroqApi(private val client: HttpClient) {

    private val baseUrl = "https://api.groq.com/openai/v1/audio/transcriptions"

    suspend fun transcribeAudio(
        apiKey: String,
        audioBytes: ByteArray,
        model: String = "whisper-large-v3-turbo"
    ): String {
        val response: GroqTranscriptionResponse = client.post(baseUrl) {
            header(HttpHeaders.Authorization, "Bearer $apiKey")
            setBody(
                MultiPartFormDataContent(
                    formData {
                        append("model", model)
                        append("response_format", "json")
                        append("language", "en")

                        append("file", audioBytes, Headers.build {
                            append(HttpHeaders.ContentType, "audio/wav")
                            append(HttpHeaders.ContentDisposition, "filename=\"chunk.wav\"")
                        })
                    }
                )
            )
        }.body()

        return response.text
    }
}
