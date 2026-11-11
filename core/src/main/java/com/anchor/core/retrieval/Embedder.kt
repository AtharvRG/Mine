// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.retrieval

/**
 * Quantized vector representation for on-device embeddings (Blueprint 21 §3.1).
 * Matryoshka-truncated to [dim] (default 256) and quantized to int8 with a scale factor.
 */
class QuantVector(
    val bytes: ByteArray,
    val scale: Float
) {
    val dim: Int get() = bytes.size

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is QuantVector) return false
        if (!bytes.contentEquals(other.bytes)) return false
        if (scale != other.scale) return false
        return true
    }

    override fun hashCode(): Int {
        var result = bytes.contentHashCode()
        result = 31 * result + scale.hashCode()
        return result
    }
}

/**
 * Clean core interface contract for on-device embedding generation (Blueprint 21 §3.1).
 * Kept isolated in `:core` without any platform/android dependencies.
 */
interface Embedder {
    val version: String // e.g. "egemma2-text-256-int8-v1"
    val dim: Int        // default 256

    suspend fun embed(texts: List<String>, kind: Kind): List<QuantVector>

    enum class Kind {
        QUERY,
        DOCUMENT
    }
}
