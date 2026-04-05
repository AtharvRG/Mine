package com.anchor.mine.network

import android.content.Context
import android.util.Log
import com.anchor.mine.security.KeyManager // <-- ADD THIS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class GroqClient(context: Context) { // <-- Pass Context here

    private val keyManager = KeyManager(context)
    private val apiKey get() = keyManager.getGroqKey() ?: "" // <-- Dynamically pull key

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun transcribeAudio(wavBytes: ByteArray): String = withContext(Dispatchers.IO) {
        try {
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("model", "whisper-large-v3")
                .addFormDataPart(
                    "file",
                    "audio.wav",
                    wavBytes.toRequestBody("audio/wav".toMediaType())
                )
                // Optional: Force English to make it even faster
                .addFormDataPart("language", "en")
                .build()

            val request = Request.Builder()
                .url("https://api.groq.com/openai/v1/audio/transcriptions")
                .header("Authorization", "Bearer $apiKey")
                .post(requestBody)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e("GroqClient", "API Error: ${response.code} - ${response.body?.string()}")
                    return@withContext ""
                }

                val responseBody = response.body?.string() ?: return@withContext ""
                val json = JSONObject(responseBody)
                return@withContext json.getString("text").trim()
            }
        } catch (e: Exception) {
            Log.e("GroqClient", "Network Error: ${e.message}")
            return@withContext ""
        }
    }
}