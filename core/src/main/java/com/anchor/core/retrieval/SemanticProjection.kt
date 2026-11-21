// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.retrieval

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Pure Kotlin semantic vector projection and quantization engine.
 * Employs Matryoshka-truncatable 256-d projection using concept clusters,
 * subword character n-grams, and L2 normalization with symmetric int8 quantization.
 * Kept in `:core` to allow pure-JVM unit testing and deterministic embedding.
 */
object SemanticProjection {

    private val SEMANTIC_CLUSTERS = mapOf(
        setOf("doctor", "physician", "medic", "clinic", "hospital", "health", "medicine", "nurse", "patient", "appointment", "therapy", "dentist", "prescription") to 0,
        setOf("tax", "taxes", "accountant", "accounting", "audit", "irs", "revenue", "financial", "finance", "money", "bank", "billing", "invoice", "salary") to 16,
        setOf("meeting", "sync", "call", "catchup", "discussion", "conference", "zoom", "chat", "interview", "standup", "presentation") to 32,
        setOf("grocery", "groceries", "milk", "eggs", "supermarket", "store", "buy", "purchase", "shopping", "food", "dinner", "lunch", "breakfast", "cook", "recipe") to 48,
        setOf("gym", "workout", "exercise", "running", "fitness", "train", "training", "sports", "swim", "yoga", "cycling", "marathon") to 64,
        setOf("flight", "airport", "ticket", "hotel", "travel", "trip", "vacation", "train", "bus", "passport", "luggage", "booking") to 80,
        setOf("car", "vehicle", "drive", "driving", "mechanic", "oil", "tire", "repair", "gas", "parking") to 96,
        setOf("code", "programming", "software", "bug", "deploy", "build", "git", "commit", "server", "database", "test", "release") to 112,
        setOf("home", "house", "apartment", "clean", "cleaning", "chore", "laundry", "dishes", "furniture", "garden", "lawn") to 128,
        setOf("study", "exam", "lecture", "course", "class", "school", "homework", "read", "book", "research", "university") to 144,
        setOf("family", "parents", "mom", "dad", "kid", "child", "children", "baby", "spouse", "husband", "wife", "friend", "birthday", "anniversary") to 160,
        setOf("project", "deadline", "report", "client", "boss", "manager", "task", "proposal", "career", "job", "resume") to 176,
        setOf("movie", "concert", "music", "game", "gaming", "party", "show", "hobby", "relaxing", "weekend") to 192,
        setOf("contract", "agreement", "legal", "lawyer", "permit", "license", "notary", "signature", "policy", "document") to 208,
        setOf("reminder", "schedule", "plan", "agenda", "calendar", "alarm", "alert", "event", "urgent", "timeline") to 224,
        setOf("email", "message", "letter", "contact", "reply", "feedback", "notify", "invite", "outreach", "inbox") to 240
    )

    fun quantizeFloats(floats: FloatArray, targetDim: Int = 256): QuantVector {
        val truncated = if (floats.size >= targetDim) {
            floats.copyOf(targetDim)
        } else {
            FloatArray(targetDim) { i -> if (i < floats.size) floats[i] else 0f }
        }

        var maxAbs = 0f
        for (f in truncated) {
            val a = abs(f)
            if (a > maxAbs) maxAbs = a
        }

        val scale = if (maxAbs > 0f) maxAbs / 127f else 1f
        val bytes = ByteArray(targetDim) { i ->
            (truncated[i] / scale).roundToInt().coerceIn(-128, 127).toByte()
        }
        return QuantVector(bytes, scale)
    }

    fun projectSemanticVector(text: String, targetDim: Int = 256): QuantVector {
        val floats = FloatArray(targetDim)
        val isQuery = text.startsWith("task: search query | ")
        val isDoc = text.startsWith("task: search result | ")
        var clean = text.lowercase(java.util.Locale.ROOT)
        clean = clean.removePrefix("task: search result | ").removePrefix("task: search query | ")
        val rawTokens = clean.split(Regex("""[^\p{L}\p{M}\p{N}]+""")).filter { it.isNotBlank() }
        if (rawTokens.isEmpty() && !isQuery && !isDoc) return QuantVector(ByteArray(targetDim), 1.0f)

        val tokens = if (isQuery) rawTokens + "task_query" else if (isDoc) rawTokens + "task_result" else rawTokens

        for (token in tokens) {
            // Concept cluster alignment (require minimum length 4 for prefix matching to prevent 1-letter pollution)
            if (token != "task_query" && token != "task_result") {
                for ((clusterWords, baseDim) in SEMANTIC_CLUSTERS) {
                    if (clusterWords.contains(token) || (token.length >= 4 && clusterWords.any { (it.length >= 4 && token.startsWith(it)) || (token.length >= 4 && it.startsWith(token)) })) {
                        for (d in 0 until 16) {
                            val dimIdx = (baseDim + d) % targetDim
                            floats[dimIdx] += 1.5f
                        }
                    }
                }
            }

            // Subword character n-grams (3-grams, 4-grams)
            val padded = "^$token$"
            for (n in 3..4) {
                if (padded.length >= n) {
                    for (i in 0..padded.length - n) {
                        val ngram = padded.substring(i, i + n)
                        val hash = ngram.hashCode()
                        val dimIdx = Math.floorMod(hash, targetDim)
                        val sign = if ((hash and 1) == 0) 1f else -1f
                        floats[dimIdx] += sign * 0.4f
                    }
                }
            }
        }

        // L2 normalize
        var sumSq = 0.0
        for (f in floats) sumSq += (f * f)
        val norm = sqrt(sumSq).toFloat()
        if (norm > 0f) {
            for (i in floats.indices) {
                floats[i] = floats[i] / norm
            }
        }

        return quantizeFloats(floats, targetDim)
    }
}

/**
 * Pure Kotlin embedder for unit tests and deterministic semantic vector generation.
 */
class PureKotlinEmbedder(
    override val version: String = "semantic-projection-256-int8-v1",
    override val dim: Int = 256
) : Embedder {
    override suspend fun embed(texts: List<String>, kind: Embedder.Kind): List<QuantVector> {
        val prefix = when (kind) {
            Embedder.Kind.DOCUMENT -> "task: search result | "
            Embedder.Kind.QUERY -> "task: search query | "
        }
        return texts.map { text ->
            SemanticProjection.projectSemanticVector("$prefix${text.trim()}", dim)
        }
    }
}
