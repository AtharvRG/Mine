// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.retrieval

import com.anchor.core.SharedFakeStorage
import com.anchor.core.models.Node
import com.anchor.core.models.SearchResult
import com.anchor.core.models.SoulFact
import com.anchor.core.time.DateResolver
import com.anchor.core.time.FakeClock
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HybridRetrieverTest {

    @Test
    fun testCosineSimilarity_IdenticalAndOrthogonalVectors() {
        val bytes1 = ByteArray(256) { 10 }
        val bytes2 = ByteArray(256) { 10 }
        val v1 = QuantVector(bytes1, 0.1f)
        val v2 = QuantVector(bytes2, 0.1f)

        val simIdentical = HybridRetriever.cosineSimilarity(v1, v2)
        assertEquals(1.0f, simIdentical, 0.001f)

        val bytesOpposite = ByteArray(256) { -10 }
        val vOpposite = QuantVector(bytesOpposite, 0.1f)
        val simOpposite = HybridRetriever.cosineSimilarity(v1, vOpposite)
        assertEquals(-1.0f, simOpposite, 0.001f)
    }

    @Test
    fun testRrfFusion_FusesKeywordAndVectorHitsWithoutDroppingEither() = runBlocking {
        val clock = FakeClock(100_000L)
        val storage = SharedFakeStorage(clock)
        val embedder = PureKotlinEmbedder()
        val retriever = HybridRetriever(storage, embedder, clock, DateResolver(clock))

        // Node 1: Exact keyword match for "doctor", no vector stored
        val n1 = Node("n1", "task", "doctor appointment scheduled", "active", createdAt = 90_000L, updatedAt = 90_000L)
        // Node 2: No keyword match for "doctor", but semantic match via physician vector
        val n2 = Node("n2", "task", "physician annual review", "active", createdAt = 95_000L, updatedAt = 95_000L)
        storage.saveNode(n1)
        storage.saveNode(n2)

        // Seed vector only for n2 with physician embedding
        val n2Vec = embedder.embed(listOf(n2.title), Embedder.Kind.DOCUMENT).first()
        storage.saveEmbedding("node", n2.id, embedder.version, n2Vec)

        // Query "doctor": FTS returns n1, Vector search returns n2
        val query = "doctor"
        val results = retriever.search(query, limit = 10)

        assertTrue("Fused search must return hits", results.isNotEmpty())
        val returnedIds = results.map { it.id }
        assertTrue("Exact keyword hit must be in results", returnedIds.contains("n1"))
        assertTrue("Semantic vector hit must be in results", returnedIds.contains("n2"))
    }

    @Test
    fun testEmbedderNull_FallsBackToFtsGracefully() = runBlocking {
        val clock = FakeClock(100_000L)
        val storage = SharedFakeStorage(clock)
        val retriever = HybridRetriever(storage, embedder = null, clock = clock, dateResolver = DateResolver(clock))

        val n1 = Node("n1", "task", "Buy organic apples", "active", createdAt = 90_000L, updatedAt = 90_000L)
        storage.saveNode(n1)

        val results = retriever.search("apples", limit = 5)
        assertEquals(1, results.size)
        assertEquals("n1", results.first().id)
    }

    @Test
    fun testStatusMultiplier_ActiveRanksHigherThanClosed() = runBlocking {
        val clock = FakeClock(100_000L)
        val storage = SharedFakeStorage(clock)
        val retriever = HybridRetriever(storage, embedder = null, clock = clock, dateResolver = DateResolver(clock))

        // Both match keyword "tax", but one is paused (0.9 multiplier), one is active (1.0 multiplier)
        val paused = Node("n_paused", "task", "Prepare 2025 tax audit", "paused", createdAt = 50_000L, updatedAt = 50_000L)
        val active = Node("n_active", "task", "Prepare 2026 tax documents", "active", createdAt = 50_000L, updatedAt = 50_000L)
        storage.saveNode(paused)
        storage.saveNode(active)

        val results = retriever.search("tax", limit = 5)
        assertEquals(2, results.size)
        assertEquals("Active node must rank higher than paused node due to status multiplier", "n_active", results[0].id)
        assertEquals("n_paused", results[1].id)
    }

    @Test
    fun testZeroVector_CosineSimilarityIsZero() {
        val zeroBytes = ByteArray(256) { 0 }
        val nonZeroBytes = ByteArray(256) { 5 }
        val vZero = QuantVector(zeroBytes, 1.0f)
        val vNonZero = QuantVector(nonZeroBytes, 1.0f)

        assertEquals(0.0f, HybridRetriever.cosineSimilarity(vZero, vNonZero), 0.0001f)
        assertEquals(0.0f, HybridRetriever.cosineSimilarity(vZero, vZero), 0.0001f)
    }

    @Test
    fun testLocalOnlyAndDoneNodesExcludedFromVectorHits() = runBlocking {
        val clock = FakeClock(100_000L)
        val storage = SharedFakeStorage(clock)
        val embedder = PureKotlinEmbedder()
        val retriever = HybridRetriever(storage, embedder, clock, DateResolver(clock))

        // Node with secret title marked localOnly
        val secretNode = Node("n_secret", "task", "Secret bank vault code", "active", localOnly = true)
        val doneNode = Node("n_done", "task", "Completed tax submission", "done")
        storage.saveNode(secretNode)
        storage.saveNode(doneNode)

        val vecSecret = embedder.embed(listOf(secretNode.title), Embedder.Kind.DOCUMENT).first()
        val vecDone = embedder.embed(listOf(doneNode.title), Embedder.Kind.DOCUMENT).first()
        storage.saveEmbedding("node", secretNode.id, embedder.version, vecSecret)
        storage.saveEmbedding("node", doneNode.id, embedder.version, vecDone)

        val results = retriever.search("bank vault", limit = 10)
        assertTrue("LocalOnly node must never be returned in hybrid search", results.none { it.id == "n_secret" })

        val doneResults = retriever.search("tax submission", limit = 10)
        assertTrue("Done node must not be returned in hybrid search", doneResults.none { it.id == "n_done" })
    }
}
