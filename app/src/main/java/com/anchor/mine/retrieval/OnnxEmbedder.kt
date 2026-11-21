// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.mine.retrieval

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.anchor.core.retrieval.Embedder
import com.anchor.core.retrieval.HfTokenizer
import com.anchor.core.retrieval.QuantVector
import com.anchor.core.retrieval.SemanticProjection
import com.anchor.core.voice.ModelCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.nio.LongBuffer
import kotlin.math.sqrt

/**
 * A downloaded sentence-embedding model (IBM Granite R2, MiniLM, BGE…) run with ONNX Runtime.
 * Tokenizes with the model's own `tokenizer.json` ([HfTokenizer]), pools the last hidden state
 * (CLS or mean, per the model card), L2-normalises, and stores int8 at the model's native size.
 */
class OnnxEmbedder(private val entry: ModelCatalog.Entry, dir: File) : Embedder, Closeable {
    private val spec = entry.embed ?: error("${entry.id} is not an embedding model")
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val tokenizer: HfTokenizer = HfTokenizer.fromJson(File(dir, "tokenizer.json").readText())
    private val lock = Any()

    init {
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(2)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        // From a path, so ONNX Runtime finds the external weights file (.onnx_data) beside it.
        session = env.createSession(File(dir, spec.onnxFile).absolutePath, opts)
    }

    override val version: String = ModelCatalog.embedVersion(entry)
    override val dim: Int = spec.dim

    override suspend fun embed(texts: List<String>, kind: Embedder.Kind): List<QuantVector> = withContext(Dispatchers.Default) {
        texts.map { raw ->
            val prefix = if (kind == Embedder.Kind.QUERY) spec.queryPrefix else spec.documentPrefix
            SemanticProjection.quantizeFloats(embedOne(prefix + raw.trim()), spec.dim)
        }
    }

    private fun embedOne(text: String): FloatArray = synchronized(lock) {
        val enc = tokenizer.encode(text, spec.maxTokens)
        val n = enc.size.toLong()
        val inputs = HashMap<String, OnnxTensor>()
        try {
            for (name in session.inputNames) {
                val data = when (name) {
                    "input_ids" -> enc.ids.map { it.toLong() }
                    "attention_mask" -> enc.attentionMask.map { it.toLong() }
                    "token_type_ids" -> List(enc.size) { 0L }
                    "position_ids" -> List(enc.size) { it.toLong() }
                    else -> continue
                }
                inputs[name] = OnnxTensor.createTensor(env, LongBuffer.wrap(data.toLongArray()), longArrayOf(1, n))
            }
            session.run(inputs).use { result ->
                // Prefer a ready-made sentence embedding when the export has one.
                val named = session.outputNames.firstOrNull { it == "sentence_embedding" }
                val out = (if (named != null) result.get(named).orElse(null) else null) ?: result.get(0)
                @Suppress("UNCHECKED_CAST")
                val v: FloatArray = when (val value = out.value) {
                    is Array<*> -> when (val first = value[0]) {
                        is FloatArray -> first // [1, dim]
                        is Array<*> -> pool(first as Array<FloatArray>, enc.attentionMask) // [1, seq, dim]
                        else -> error("Unexpected output shape")
                    }
                    else -> error("Unexpected output type")
                }
                return@synchronized l2(v)
            }
        } finally {
            inputs.values.forEach { runCatching { it.close() } }
        }
    }

    private fun pool(hidden: Array<FloatArray>, mask: IntArray): FloatArray = when (spec.pooling) {
        ModelCatalog.Pooling.CLS -> hidden[0].copyOf()
        ModelCatalog.Pooling.MEAN -> {
            val d = hidden[0].size
            val sum = FloatArray(d)
            var count = 0
            for (t in hidden.indices) if (mask.getOrElse(t) { 0 } == 1) { for (i in 0 until d) sum[i] += hidden[t][i]; count++ }
            if (count > 0) for (i in 0 until d) sum[i] /= count
            sum
        }
    }

    private fun l2(v: FloatArray): FloatArray {
        var s = 0.0
        for (x in v) s += x * x
        val norm = sqrt(s).toFloat().coerceAtLeast(1e-12f)
        return FloatArray(v.size) { v[it] / norm }
    }

    override fun close() {
        runCatching { session.close() }
    }
}

/**
 * The embedder the app hands to retrieval: whichever model the user picked in Settings →
 * Models, or the built-in hash projection when none is installed. Switching changes [version],
 * so old vectors are simply ignored by search until the re-index writes new ones.
 */
class SwitchableEmbedder(private val fallback: Embedder) : Embedder {
    @Volatile private var active: Embedder = fallback

    @Volatile private var activeEntryId: String? = null
    /** Catalog id of the model in use, or null for the built-in fallback. */
    val activeId: String? get() = activeEntryId

    override val version: String get() = active.version
    override val dim: Int get() = active.dim

    override suspend fun embed(texts: List<String>, kind: Embedder.Kind): List<QuantVector> =
        try {
            active.embed(texts, kind)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Throwable) {
            // A broken model must never break search: fall back for this call and the rest.
            use(null, null)
            fallback.embed(texts, kind)
        }

    /** Activates [entry] from [dir], or the fallback when either is null or the model won't load. Returns true on success. */
    fun use(entry: ModelCatalog.Entry?, dir: File?): Boolean {
        val old = active
        val next: Embedder = if (entry == null || dir == null) fallback else try {
            OnnxEmbedder(entry, dir)
        } catch (_: Throwable) {
            fallback
        }
        active = next
        activeEntryId = if (next === fallback) null else entry?.id
        if (old !== next && old is Closeable) runCatching { old.close() }
        return next !== fallback || entry == null
    }
}
