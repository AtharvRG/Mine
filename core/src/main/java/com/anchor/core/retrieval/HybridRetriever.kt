// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.retrieval

import com.anchor.core.models.SearchResult
import com.anchor.core.storage.Storage
import com.anchor.core.time.Clock
import com.anchor.core.time.DateResolver
import kotlin.math.sqrt

/**
 * Hybrid retrieval engine combining SQLite FTS5 (BM25) and on-device quantized embeddings
 * fused via Reciprocal Rank Fusion (RRF, k=60) per Blueprint 21 §3.5.
 */
class HybridRetriever(
    private val storage: Storage,
    private val embedder: Embedder?,
    private val clock: Clock,
    private val dateResolver: DateResolver
) {

    suspend fun search(query: String, limit: Int = 20): List<SearchResult> {
        if (query.isBlank()) return emptyList()

        // 1. Keyword search (FTS5 BM25)
        val ftsHits = storage.searchMemoryFts(query, CANDIDATE_LIMIT)

        // 2. Vector search (int8 cosine similarity)
        val vectorHits = mutableListOf<SearchResult>()
        if (embedder != null) {
            val queryVectors = embedder.embed(listOf(query), Embedder.Kind.QUERY)
            val queryVec = queryVectors.firstOrNull()
            if (queryVec != null) {
                val nodeEmbeddings = storage.getEmbeddings("node", embedder.version)
                val factEmbeddings = storage.getEmbeddings("soul_fact", embedder.version)
                val allCandidateEmbeddings = nodeEmbeddings.map { "node" to it } + factEmbeddings.map { "soul_fact" to it }

                val scored = allCandidateEmbeddings.mapNotNull { (ownerType, pair) ->
                    val (ownerId, vec) = pair
                    val sim = cosineSimilarity(queryVec, vec)
                    if (sim > 0.1f) Triple(ownerType, ownerId, sim) else null
                }.sortedByDescending { it.third }.take(CANDIDATE_LIMIT)

                val activeSoulFactsMap = if (scored.any { it.first == "soul_fact" }) {
                    storage.getActiveSoulFacts().associateBy { it.id }
                } else emptyMap()

                val activeNodesMap = if (scored.any { it.first == "node" }) {
                    storage.getActiveNodes(100).associateBy { it.id }
                } else emptyMap()

                for ((ownerType, ownerId, _) in scored) {
                    when (ownerType) {
                        "node" -> {
                            val node = activeNodesMap[ownerId] ?: storage.getNode(ownerId)
                            if (node != null && node.status != "archived" && node.status != "cancelled" && node.status != "done" && !node.localOnly) {
                                val dueText = if (node.dueAt != null) "Due: ${node.dueAt}" else ""
                                val details = "Status: ${node.status} $dueText".trim()
                                vectorHits.add(SearchResult(node.id, node.type, node.title, details, node.createdAt))
                            }
                        }
                        "soul_fact" -> {
                            val fact = activeSoulFactsMap[ownerId]
                            if (fact != null && !fact.localOnly) {
                                vectorHits.add(SearchResult(fact.id, "soul_fact", fact.category, fact.statement, fact.createdAt))
                            }
                        }
                    }
                }
            }
        }

        // 3. Reciprocal Rank Fusion (RRF with k = 60)
        val rrfScores = mutableMapOf<String, Float>()
        val itemLookup = mutableMapOf<String, SearchResult>()

        ftsHits.forEachIndexed { index, hit ->
            val rank = index + 1
            val rrf = 1.0f / (RRF_K + rank)
            rrfScores[hit.id] = (rrfScores[hit.id] ?: 0f) + rrf
            itemLookup[hit.id] = hit
        }

        vectorHits.forEachIndexed { index, hit ->
            val rank = index + 1
            val rrf = 1.0f / (RRF_K + rank)
            rrfScores[hit.id] = (rrfScores[hit.id] ?: 0f) + rrf
            if (!itemLookup.containsKey(hit.id)) {
                itemLookup[hit.id] = hit
            }
        }

        if (rrfScores.isEmpty()) return emptyList()

        // 4. Multipliers: Status and Temporal relevance
        val parsedTime = when (val res = runCatching { dateResolver.resolve(query) }.getOrNull()) {
            is com.anchor.core.time.DateResolution.Exact -> res.timeMillis
            else -> null
        }

        val finalScored = rrfScores.mapNotNull { (id, baseScore) ->
            val item = itemLookup[id] ?: return@mapNotNull null
            var multiplier = 1.0f

            val node = if (id.startsWith("n_")) storage.getNode(id) else null
            if (node != null) {
                when (node.status) {
                    "active", "in_progress" -> multiplier *= 1.0f
                    "done", "closed" -> multiplier *= 0.8f
                    "paused" -> multiplier *= 0.9f
                    "archived", "cancelled" -> multiplier *= 0.1f
                }

                if (parsedTime != null) {
                    val windowStart = parsedTime - 12 * 3600_000L
                    val windowEnd = parsedTime + 12 * 3600_000L
                    val nodeTime = node.dueAt ?: node.createdAt
                    if (nodeTime in windowStart..windowEnd) {
                        multiplier *= 1.5f
                    }
                }
            }

            item to (baseScore * multiplier)
        }

        return finalScored.sortedByDescending { it.second }
            .take(limit.coerceAtLeast(0))
            .map { it.first }
    }

    companion object {
        const val RRF_K = 60f
        const val CANDIDATE_LIMIT = 40

        /**
         * sub-microsecond int8 cosine similarity.
         * Floating-point scale factors cancel out:
         * sim = sum(b1 * b2) / (sqrt(sum(b1^2)) * sqrt(sum(b2^2)))
         */
        fun cosineSimilarity(v1: QuantVector, v2: QuantVector): Float {
            if (v1.dim != v2.dim || v1.dim == 0) return 0f

            var dot = 0L
            var norm1 = 0L
            var norm2 = 0L

            val b1 = v1.bytes
            val b2 = v2.bytes
            val len = b1.size

            for (i in 0 until len) {
                val val1 = b1[i].toInt()
                val val2 = b2[i].toInt()
                dot += (val1 * val2)
                norm1 += (val1 * val1)
                norm2 += (val2 * val2)
            }

            if (norm1 == 0L || norm2 == 0L) return 0f

            val denom = sqrt(norm1.toDouble()) * sqrt(norm2.toDouble())
            val sim = (dot / denom).toFloat()
            return sim.coerceIn(-1.0f, 1.0f)
        }
    }
}
