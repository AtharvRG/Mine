// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.mine.provider

import android.content.Context
import com.anchor.core.provider.OnDeviceLlmEngine
import com.anchor.core.provider.RuleBasedLocalLlmEngine
import com.anchor.core.provider.StreamEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * Android implementation of OnDeviceLlmEngine (v4 / P-010).
 * Checks for LiteRT-LM model weights on device storage; if absent or on inference failure,
 * gracefully falls back to RuleBasedLocalLlmEngine so the app never hangs or fails offline.
 */
class AndroidOnDeviceLlmEngine(
    private val context: Context,
    private val customModelPath: String? = null,
    private val ruleFallback: RuleBasedLocalLlmEngine = RuleBasedLocalLlmEngine()
) : OnDeviceLlmEngine {

    private val defaultModelPath = File(context.filesDir, "models/gemma-2b-it.litertlm").absolutePath
    private val jsonParser = Json { ignoreUnknownKeys = true; isLenient = true }

    private val isNativeAvailable: Boolean by lazy {
        try {
            Class.forName("com.google.ai.edge.litertlm.LiteRtLmJni")
            true
        } catch (_: Throwable) {
            false
        }
    }

    override suspend fun generateStream(prompt: String): Flow<StreamEvent> = flow {
        currentCoroutineContext().ensureActive()
        val path = customModelPath ?: defaultModelPath
        val file = File(path)

        if (isNativeAvailable && file.exists() && file.length() > 0) {
            var emittedAny = false
            try {
                val formattedPrompt = formatGemmaPrompt(prompt)
                sessionMutex.withLock {
                    val engine = getOrCreateEngine(path)
                    val session = engine.createSession()
                    val streamFlow = callbackFlow<StreamEvent> {
                        val input = listOf(com.google.ai.edge.litertlm.InputData.Text(formattedPrompt))
                        val initialBuffer = StringBuilder()
                        var detected = false
                        var isDirectJson = false

                        session.generateContentStream(input, object : com.google.ai.edge.litertlm.ResponseCallback {
                            override fun onNext(chunk: String) {
                                if (!detected) {
                                    initialBuffer.append(chunk)
                                    val trimmed = initialBuffer.toString().trimStart()
                                    if (trimmed.isEmpty()) return
                                    if (trimmed.startsWith("```json")) {
                                        val afterFence = trimmed.removePrefix("```json").trimStart()
                                        if (afterFence.startsWith("{")) {
                                            detected = true
                                            isDirectJson = true
                                            trySend(StreamEvent.TextDelta(afterFence))
                                            return
                                        } else if (afterFence.isNotEmpty()) {
                                            detected = true
                                            isDirectJson = false
                                            trySend(StreamEvent.TextDelta("{\"reply\": \""))
                                            val escaped = escapeJson(initialBuffer.toString())
                                            trySend(StreamEvent.TextDelta(escaped))
                                            return
                                        }
                                        return
                                    } else if (trimmed.startsWith("```")) {
                                        val afterFence = trimmed.removePrefix("```").trimStart()
                                        if (afterFence.startsWith("{")) {
                                            detected = true
                                            isDirectJson = true
                                            trySend(StreamEvent.TextDelta(afterFence))
                                            return
                                        } else if (afterFence.isNotEmpty() && !afterFence.startsWith("j") && !afterFence.startsWith("json")) {
                                            detected = true
                                            isDirectJson = false
                                            trySend(StreamEvent.TextDelta("{\"reply\": \""))
                                            val escaped = escapeJson(initialBuffer.toString())
                                            trySend(StreamEvent.TextDelta(escaped))
                                            return
                                        }
                                        return
                                    } else if (trimmed.startsWith("{")) {
                                        detected = true
                                        isDirectJson = true
                                        trySend(StreamEvent.TextDelta(trimmed))
                                        return
                                    } else if (trimmed.length >= 12 || trimmed.contains("\n")) {
                                        detected = true
                                        isDirectJson = false
                                        trySend(StreamEvent.TextDelta("{\"reply\": \""))
                                        val escaped = escapeJson(initialBuffer.toString())
                                        trySend(StreamEvent.TextDelta(escaped))
                                        return
                                    } else {
                                        return
                                    }
                                }

                                if (isDirectJson) {
                                    val cleanChunk = if (chunk.endsWith("```")) chunk.removeSuffix("```") else chunk
                                    trySend(StreamEvent.TextDelta(cleanChunk))
                                } else {
                                    val escaped = escapeJson(chunk)
                                    trySend(StreamEvent.TextDelta(escaped))
                                }
                            }

                            override fun onDone() {
                                if (!detected) {
                                    val trimmed = initialBuffer.toString().trim()
                                    if (trimmed.startsWith("{")) {
                                        trySend(StreamEvent.TextDelta(trimmed))
                                    } else {
                                        val escaped = escapeJson(trimmed)
                                        trySend(StreamEvent.TextDelta("{\"reply\": \"$escaped\", \"calls\": []}"))
                                    }
                                } else if (!isDirectJson) {
                                    trySend(StreamEvent.TextDelta("\", \"calls\": []}"))
                                }
                                trySend(StreamEvent.Finish("stop"))
                                close()
                            }

                            override fun onError(throwable: Throwable) {
                                close(throwable)
                            }
                        })
                        awaitClose {
                            try { session.cancelProcess() } catch (_: Throwable) {}
                            try { session.close() } catch (_: Throwable) {}
                        }
                    }

                    streamFlow.collect { event ->
                        emittedAny = true
                        emit(event)
                    }
                }
                scheduleIdleUnload()
                if (emittedAny) return@flow
            } catch (e: Throwable) {
                if (emittedAny) {
                    emit(StreamEvent.Clear)
                }
                scheduleIdleUnload()
                android.util.Log.w("AndroidOnDeviceLlm", "Native LLM generation failed (${e.message}), falling back to rule engine")
            }
        }

        // Rule-based deterministic offline fallback
        ruleFallback.generateStream(prompt).collect { event ->
            currentCoroutineContext().ensureActive()
            emit(event)
        }
    }.flowOn(Dispatchers.Default)

    /**
     * Parses OpenAI wire JSON and formats as Gemma chat template:
     * <start_of_turn>user\n...<end_of_turn>\n<start_of_turn>model\n
     */
    private fun formatGemmaPrompt(rawPrompt: String): String {
        return try {
            val trimmed = rawPrompt.trim()
            if (!trimmed.startsWith("{")) return rawPrompt

            val root = jsonParser.parseToJsonElement(trimmed).jsonObject
            val messages = root["messages"]?.jsonArray
            if (messages.isNullOrEmpty()) return rawPrompt

            buildString {
                for (elem in messages) {
                    val obj = elem.jsonObject
                    val role = obj["role"]?.jsonPrimitive?.content ?: "user"
                    val content = obj["content"]?.jsonPrimitive?.content ?: ""
                    val gemmaRole = when (role) {
                        "assistant", "model" -> "model"
                        else -> "user"
                    }
                    append("<start_of_turn>$gemmaRole\n$content<end_of_turn>\n")
                }
                append("<start_of_turn>model\n")
            }
        } catch (_: Throwable) {
            rawPrompt
        }
    }

    companion object {
        private val sessionMutex = Mutex()
        @Volatile
        private var cachedEngine: com.google.ai.edge.litertlm.Engine? = null
        @Volatile
        private var cachedPath: String? = null
        private val engineLock = Any()
        private var idleUnloadJob: kotlinx.coroutines.Job? = null
        private val unloadScope = kotlinx.coroutines.CoroutineScope(Dispatchers.Default + kotlinx.coroutines.SupervisorJob())

        fun getOrCreateEngine(path: String): com.google.ai.edge.litertlm.Engine {
            idleUnloadJob?.cancel()
            return cachedEngine?.takeIf { cachedPath == path } ?: synchronized(engineLock) {
                cachedEngine?.takeIf { cachedPath == path } ?: run {
                    try { cachedEngine?.close() } catch (_: Throwable) {}
                    // Try GPU backend first, fall back to CPU if unavailable or unsupported
                    val engine = try {
                        val gpuConfig = com.google.ai.edge.litertlm.EngineConfig(
                            modelPath = path,
                            backend = com.google.ai.edge.litertlm.Backend.GPU()
                        )
                        val eng = com.google.ai.edge.litertlm.Engine(gpuConfig)
                        eng.initialize()
                        eng
                    } catch (_: Throwable) {
                        val cpuConfig = com.google.ai.edge.litertlm.EngineConfig(
                            modelPath = path,
                            backend = com.google.ai.edge.litertlm.Backend.CPU()
                        )
                        val eng = com.google.ai.edge.litertlm.Engine(cpuConfig)
                        eng.initialize()
                        eng
                    }
                    cachedEngine = engine
                    cachedPath = path
                    engine
                }
            }
        }

        fun scheduleIdleUnload() {
            synchronized(engineLock) {
                idleUnloadJob?.cancel()
                idleUnloadJob = unloadScope.launch {
                    kotlinx.coroutines.delay(120_000L) // Unload after 2 minutes of idle time
                    closeCachedEngine()
                }
            }
        }

        fun onTrimMemory(level: Int) {
            if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
                closeCachedEngine()
            }
        }

        fun closeCachedEngine() {
            synchronized(engineLock) {
                idleUnloadJob?.cancel()
                try { cachedEngine?.close() } catch (_: Throwable) {}
                cachedEngine = null
                cachedPath = null
            }
        }

        fun escapeJson(text: String): String {
            return text.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n")
                .replace("\t", "\\t")
                .replace("\b", "\\b")
                .replace("\u000c", "\\f")
        }
    }
}
