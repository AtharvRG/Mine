package com.anchor.mine.network

import android.content.Context
import android.util.Log
import com.anchor.mine.security.KeyManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import okio.BufferedSink
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

// Data class to hold the stream state as it arrives
data class StreamUpdate(
    val thinkingDelta: String = "",
    val contentDelta: String = "",
    val isFinished: Boolean = false
)

class NimClient(context: Context) {

    private val keyManager = KeyManager(context)
    private val nimApiKey get() = keyManager.getNimKey() ?: ""

    // The models from your Python script
    private val thinkingModel = "moonshotai/kimi-k2-thinking"
    private val fastModel = "moonshotai/kimi-k2-instruct-0905"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS) // 5 FULL MINUTES
        .writeTimeout(300, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS) // Keeps the connection alive while Kimi thinks!
        .build()

    // The 415 Byte Array Trick
    private fun createJsonRequestBody(jsonString: String): RequestBody {
        return object : RequestBody() {
            override fun contentType() = "application/json".toMediaType()
            override fun writeTo(sink: BufferedSink) {
                sink.writeUtf8(jsonString)
            }
        }
    }

    suspend fun cleanAndSummarize(rawTranscripts: List<String>): String = withContext(Dispatchers.IO) {
        if (rawTranscripts.isEmpty()) return@withContext ""

        val combinedText = rawTranscripts.joinToString(" ")
        val systemPrompt = """
            You are the backend memory processor for an AI wearable. 
            The user just finished an ambient listening session. The following text is raw, messy audio transcription.
            Your job is to:
            1. Remove filler words and transcription errors.
            2. Summarize the main points concisely.
            3. Extract any clear Action Items or Tasks.
            Format your response cleanly. Do not chat with the user, just output the summary and action items.
        """.trimIndent()

        val jsonPayload = JSONObject().apply {
            put("model", fastModel) // Use the fast model for backend tasks
            put("temperature", 0.2)
            put("max_tokens", 500)

            val messagesArray = JSONArray()
            messagesArray.put(JSONObject().apply { put("role", "system"); put("content", systemPrompt) })
            messagesArray.put(JSONObject().apply { put("role", "user"); put("content", combinedText) })
            put("messages", messagesArray)
        }

        val request = Request.Builder()
            .url("https://integrate.api.nvidia.com/v1/chat/completions")
            .header("Authorization", "Bearer $nimApiKey")
            .post(createJsonRequestBody(jsonPayload.toString()))
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext "Failed to process memory."
                val jsonResponse = JSONObject(response.body?.string() ?: "")
                val choices = jsonResponse.getJSONArray("choices")
                return@withContext choices.getJSONObject(0).getJSONObject("message").getString("content").trim()
            }
        } catch (e: Exception) {
            return@withContext "Network error during memory processing."
        }
    }

    suspend fun getEmbedding(text: String, inputType: String = "passage"): String = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext "[]"

        val jsonPayload = JSONObject().apply {
            put("input", JSONArray().put(text))
            put("model", "nvidia/nv-embed-v1")
            put("encoding_format", "float")
            put("input_type", inputType)
            put("truncate", "NONE")
        }

        val request = Request.Builder()
            .url("https://integrate.api.nvidia.com/v1/embeddings")
            .header("Authorization", "Bearer $nimApiKey")
            .post(createJsonRequestBody(jsonPayload.toString()))
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext "[]"
                val jsonResponse = JSONObject(response.body?.string() ?: "")
                val dataArray = jsonResponse.getJSONArray("data")
                return@withContext dataArray.getJSONObject(0).getJSONArray("embedding").toString()
            }
        } catch (e: Exception) {
            return@withContext "[]"
        }
    }

    suspend fun generateNudgeInsight(ambientText: String): String = withContext(Dispatchers.IO) {
        if (ambientText.split(" ").size < 4) return@withContext "IGNORE"

        val systemPrompt = """
            You are an elite, silent Co-Founder observing the user's ambient thoughts. 
            The user just said: "$ambientText"
            
            Evaluate this statement. 
            If it is a problem, a forgotten task, a brilliant idea, or something that requires insight, reply with a SINGLE, punchy, helpful sentence.
            If it is just casual conversation, a rant, or background noise, reply with the exact word: IGNORE
        """.trimIndent()

        val jsonPayload = JSONObject().apply {
            put("model", fastModel) // Fast model for nudges
            put("temperature", 0.3)
            put("max_tokens", 50)

            val messagesArray = JSONArray()
            messagesArray.put(JSONObject().apply { put("role", "system"); put("content", systemPrompt) })
            put("messages", messagesArray)
        }

        val request = Request.Builder()
            .url("https://integrate.api.nvidia.com/v1/chat/completions")
            .header("Authorization", "Bearer $nimApiKey")
            .post(createJsonRequestBody(jsonPayload.toString()))
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext "IGNORE"
                val jsonResponse = JSONObject(response.body?.string() ?: "")
                val choices = jsonResponse.getJSONArray("choices")
                val reply = choices.getJSONObject(0).getJSONObject("message").getString("content").trim()
                return@withContext reply.replace("\"", "")
            }
        } catch (e: Exception) {
            return@withContext "IGNORE"
        }
    }

    // --- NEW: FAST CHAIN OF THOUGHT ENGINE ---
    fun streamChatBot(userMessage: String, memories: List<String>, useThinkingModel: Boolean): Flow<StreamUpdate> = callbackFlow {
        val memoryContext = if (memories.isEmpty()) {
            "The user has no recorded memories yet."
        } else {
            "Here are the user's recent ambient memories:\n" + memories.joinToString("\n- ")
        }

        // If Thinking is ON, we do a lightning-fast brainstorm first!
        var internalThoughts = ""
        if (useThinkingModel) {
            val brainstormPrompt = """
                You are the internal logic engine for an AI assistant. 
                The user asked: "$userMessage"
                Relevant memories: $memoryContext
                
                Briefly brainstorm the logical steps to answer this. Use bullet points. Do not write the final answer. Keep it under 50 words.
            """.trimIndent()

            val brainstormPayload = JSONObject().apply {
                put("model", fastModel)
                put("temperature", 0.3)
                put("max_tokens", 100)
                val msgArray = JSONArray().put(JSONObject().apply { put("role", "user"); put("content", brainstormPrompt) })
                put("messages", msgArray)
            }

            val brainstormRequest = Request.Builder()
                .url("https://integrate.api.nvidia.com/v1/chat/completions")
                .header("Authorization", "Bearer $nimApiKey")
                .post(createJsonRequestBody(brainstormPayload.toString()))
                .build()

            try {
                client.newCall(brainstormRequest).execute().use { response ->
                    if (response.isSuccessful) {
                        val jsonResponse = JSONObject(response.body?.string() ?: "")
                        internalThoughts = jsonResponse.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content").trim()
                        // Instantly send the thoughts to the UI dropdown!
                        trySend(StreamUpdate(thinkingDelta = internalThoughts, contentDelta = ""))
                    }
                }
            } catch (e: Exception) {
                Log.e("NimStream", "Brainstorm failed: ${e.message}")
            }
        }

        // --- Now, stream the actual answer! ---
        val systemPrompt = """
            You are MineAI, a highly intelligent, direct, and concise personal assistant.
            Answer the user's question based ONLY on their memories. If you don't know, say so.
            
            $memoryContext
            
            ${if (internalThoughts.isNotEmpty()) "Your internal brainstorm for this answer was: $internalThoughts" else ""}
        """.trimIndent()

        val jsonPayload = JSONObject().apply {
            put("model", fastModel) // Always use the fast model!
            put("temperature", 0.5)
            put("max_tokens", 1000)
            put("stream", true)

            val messagesArray = JSONArray()
            messagesArray.put(JSONObject().apply { put("role", "system"); put("content", systemPrompt) })
            messagesArray.put(JSONObject().apply { put("role", "user"); put("content", userMessage) })
            put("messages", messagesArray)
        }

        val request = Request.Builder()
            .url("https://integrate.api.nvidia.com/v1/chat/completions")
            .header("Authorization", "Bearer $nimApiKey")
            .header("Accept", "text/event-stream")
            .post(createJsonRequestBody(jsonPayload.toString()))
            .build()

        val eventSourceListener = object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                if (data == "[DONE]") {
                    trySend(StreamUpdate(isFinished = true))
                    eventSource.cancel()
                    close()
                    return
                }
                try {
                    val jsonResponse = JSONObject(data)
                    val choices = jsonResponse.optJSONArray("choices") ?: return
                    if (choices.length() == 0) return
                    val delta = choices.getJSONObject(0).optJSONObject("delta") ?: return
                    val content = delta.optString("content", "")

                    if (content.isNotEmpty()) {
                        trySend(StreamUpdate(contentDelta = content))
                    }
                } catch (e: Exception) {
                    Log.e("NimStream", "Failed to parse chunk: $data")
                }
            }
            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                trySend(StreamUpdate(contentDelta = "\n[Network Timeout]", isFinished = true))
                eventSource.cancel()
                close()
            }
        }

        val eventSourceFactory = EventSources.createFactory(client)
        eventSourceFactory.newEventSource(request, eventSourceListener)

        awaitClose { }
    }
    // --- AUTO-TITLING ENGINE (OpenAI Method) ---
    suspend fun generateChatTitle(firstUserMessage: String): String = withContext(Dispatchers.IO) {
        val systemPrompt = """
            You are a titling engine. Read the user's message and generate a short, punchy, 3-4 word title for the conversation.
            DO NOT use quotes, punctuation, or conversational filler. Just the title.
            Example input: "Can you help me write a Python script for an API?"
            Example output: Python API Script
        """.trimIndent()

        val jsonPayload = JSONObject().apply {
            put("model", fastModel) // Use the fast instruct model, no thinking!
            put("temperature", 0.3)
            put("max_tokens", 10)

            val messagesArray = JSONArray()
            messagesArray.put(JSONObject().apply { put("role", "system"); put("content", systemPrompt) })
            messagesArray.put(JSONObject().apply { put("role", "user"); put("content", firstUserMessage) })
            put("messages", messagesArray)
        }

        val request = Request.Builder()
            .url("https://integrate.api.nvidia.com/v1/chat/completions")
            .header("Authorization", "Bearer $nimApiKey")
            .post(createJsonRequestBody(jsonPayload.toString()))
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext "New Chat"
                val jsonResponse = JSONObject(response.body?.string() ?: "")
                val choices = jsonResponse.getJSONArray("choices")
                return@withContext choices.getJSONObject(0).getJSONObject("message").getString("content").trim().replace("\"", "")
            }
        } catch (e: Exception) {
            return@withContext "New Chat"
        }
    }

}