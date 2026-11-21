// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.mine.retrieval

import com.anchor.core.retrieval.Embedder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiteRtEmbedderTest {

    @Test
    fun testEmbed_Produces256DimensionQuantVector() = runBlocking {
        val embedder = LiteRtEmbedder(
            modelPath = "models/embeddinggemma-2-text-270m.litertlm",
            isNativeAvailableOverride = false
        )

        assertEquals(LiteRtEmbedder.HASH_VERSION, embedder.version)
        assertEquals(256, embedder.dim)

        val queryVectors = embedder.embed(listOf("meeting with doctor"), Embedder.Kind.QUERY)
        assertEquals(1, queryVectors.size)
        assertEquals(256, queryVectors[0].dim)
        assertEquals(256, queryVectors[0].bytes.size)
        assertTrue(queryVectors[0].scale > 0f)

        val docVectors = embedder.embed(listOf("meeting with doctor"), Embedder.Kind.DOCUMENT)
        assertEquals(1, docVectors.size)
        assertEquals(256, docVectors[0].dim)

        // Query and Document must differ due to asymmetric Gemma task instruction prefixes
        assertNotEquals(queryVectors[0], docVectors[0])
    }

    @Test
    fun testQuantizeFloats_TruncatesTo256DimensionsAndClampsInt8() {
        // Create 768-dim float vector (e.g. raw Gemma full dimension)
        val full768 = FloatArray(768) { i ->
            when (i) {
                0 -> 10.0f
                1 -> -10.0f
                else -> 0.01f * i
            }
        }

        val quant = LiteRtEmbedder.quantizeFloats(full768, targetDim = 256)
        assertEquals(256, quant.dim)
        assertEquals(256, quant.bytes.size)
        // Max abs is 10.0f, scale = 10.0f / 127f
        assertEquals(10.0f / 127f, quant.scale, 0.001f)
        assertEquals(127.toByte(), quant.bytes[0])
        assertEquals((-127).toByte(), quant.bytes[1])
    }

    @Test
    fun testDeterministicVector_IsStableAndPure() {
        val v1 = LiteRtEmbedder.generateDeterministicVector("apple", 256)
        val v2 = LiteRtEmbedder.generateDeterministicVector("apple", 256)
        val v3 = LiteRtEmbedder.generateDeterministicVector("orange", 256)

        assertEquals(v1, v2)
        assertNotEquals(v1, v3)
    }
}
