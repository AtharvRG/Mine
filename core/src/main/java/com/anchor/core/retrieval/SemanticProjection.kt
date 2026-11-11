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
        setOf("doctor", "physician", "medic", "clinic", "hospital", "health", "medicine", "nurse", "patient", "appointment") to 0,
        setOf("tax", "taxes", "accountant", "accounting", "audit", "irs", "revenue", "financial", "finance", "money") to 16,
        setOf("meeting", "sync", "call", "catchup", "discussion", "conference", "zoom", "chat") to 32,
        setOf("grocery", "groceries", "milk", "eggs", "supermarket", "store", "buy", "purchase", "shopping") to 48,
        setOf("gym", "workout", "exercise", "running", "fitness", "train", "training") to 64,
        setOf("flight", "airport", "ticket", "hotel", "travel", "trip", "vacation") to 80,
        setOf("car", "vehicle", "drive", "driving", "mechanic", "oil", "tire") to 96,
        setOf("code", "programming", "software", "bug", "deploy", "build", "git", "commit") to 112,
        setOf("home", "house", "apartment", "clean", "cleaning", "chore", "laundry", "dishes") to 128
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
        val clean = text.lowercase(java.util.Locale.ROOT)
        val tokens = clean.split(Regex("""[^a-z0-9]+""")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return QuantVector(ByteArray(targetDim), 1.0f)

        for (token in tokens) {
            // Concept cluster alignment
            for ((clusterWords, baseDim) in SEMANTIC_CLUSTERS) {
                if (clusterWords.contains(token) || clusterWords.any { token.startsWith(it) || it.startsWith(token) }) {
                    for (d in 0 until 16) {
                        val dimIdx = (baseDim + d) % targetDim
                        floats[dimIdx] += 1.5f
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
