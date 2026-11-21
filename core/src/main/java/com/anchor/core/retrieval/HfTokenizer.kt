// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.retrieval

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.text.Normalizer

/**
 * Reads a Hugging Face `tokenizer.json` and turns text into model input ids, for the two
 * families the on-device embedders use:
 *
 *  - **WordPiece** (BERT, MiniLM, BGE): lowercasing and accent stripping when the normalizer
 *    asks, split on whitespace and punctuation, greedy longest-match with `##` continuations.
 *  - **Byte-level BPE** (GPT-2 style; ModernBERT, which IBM Granite Embedding R2 uses): the GPT-2
 *    split pattern, bytes mapped to printable characters, merges applied by rank.
 *
 * Special tokens around the sequence come from the post-processor (`[CLS] … [SEP]`).
 * Pure Kotlin; the JSON is parsed once when the embedder loads.
 */
class HfTokenizer private constructor(
    private val model: Model,
    private val lowercase: Boolean,
    private val stripAccents: Boolean,
    private val nfc: Boolean,
    private val prefixIds: IntArray,
    private val suffixIds: IntArray,
    val padId: Int,
) {
    data class Encoding(val ids: IntArray, val attentionMask: IntArray) {
        val size: Int get() = ids.size
    }

    private sealed interface Model {
        fun tokenize(text: String): List<Int>
    }

    fun encode(text: String, maxTokens: Int = 512): Encoding {
        var t = text
        if (nfc) t = Normalizer.normalize(t, Normalizer.Form.NFC)
        if (lowercase) t = t.lowercase()
        if (stripAccents) t = Normalizer.normalize(t, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
        val body = model.tokenize(t)
        val room = (maxTokens - prefixIds.size - suffixIds.size).coerceAtLeast(0)
        val ids = prefixIds + body.take(room).toIntArray() + suffixIds
        return Encoding(ids, IntArray(ids.size) { 1 })
    }

    // ---- WordPiece -------------------------------------------------------------------------------

    private class WordPiece(
        private val vocab: Map<String, Int>,
        private val unkId: Int,
        private val prefix: String,
        private val maxChars: Int,
    ) : Model {
        override fun tokenize(text: String): List<Int> {
            val out = ArrayList<Int>()
            for (word in basicSplit(text)) {
                if (word.length > maxChars) { out += unkId; continue }
                var start = 0
                val pieces = ArrayList<Int>()
                var bad = false
                while (start < word.length) {
                    var end = word.length
                    var found = -1
                    while (start < end) {
                        val sub = (if (start > 0) prefix else "") + word.substring(start, end)
                        val id = vocab[sub]
                        if (id != null) { found = id; break }
                        end--
                    }
                    if (found < 0) { bad = true; break }
                    pieces += found
                    start = end
                }
                if (bad) out += unkId else out += pieces
            }
            return out
        }

        /** BERT pre-tokenizer: whitespace splits, every punctuation character is its own word. */
        private fun basicSplit(text: String): List<String> {
            val words = ArrayList<String>()
            val cur = StringBuilder()
            fun flush() { if (cur.isNotEmpty()) { words += cur.toString(); cur.clear() } }
            for (ch in text) {
                when {
                    ch.isWhitespace() -> flush()
                    isPunct(ch) || isCjk(ch) -> { flush(); words += ch.toString() }
                    ch.code == 0 || ch.code == 0xFFFD || Character.isISOControl(ch) -> Unit
                    else -> cur.append(ch)
                }
            }
            flush()
            return words
        }

        private fun isPunct(c: Char): Boolean {
            val cp = c.code
            if (cp in 33..47 || cp in 58..64 || cp in 91..96 || cp in 123..126) return true
            return when (Character.getType(c).toByte()) {
                Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION, Character.START_PUNCTUATION,
                Character.END_PUNCTUATION, Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION,
                Character.OTHER_PUNCTUATION -> true
                else -> false
            }
        }

        private fun isCjk(c: Char): Boolean = c.code in 0x4E00..0x9FFF || c.code in 0x3400..0x4DBF || c.code in 0xF900..0xFAFF
    }

    // ---- Byte-level BPE ----------------------------------------------------------------------------

    private class ByteBpe(
        private val vocab: Map<String, Int>,
        private val ranks: Map<Pair<String, String>, Int>,
        private val unkId: Int?,
        private val addPrefixSpace: Boolean,
    ) : Model {
        private val cache = HashMap<String, List<Int>>()

        override fun tokenize(text: String): List<Int> {
            val t = if (addPrefixSpace && text.isNotEmpty() && !text[0].isWhitespace()) " $text" else text
            val out = ArrayList<Int>()
            for (m in GPT2_SPLIT.findAll(t)) {
                val piece = m.value
                val mapped = buildString { for (b in piece.toByteArray(Charsets.UTF_8)) append(BYTE_TO_CHAR[b.toInt() and 0xFF]) }
                out += cache.getOrPut(mapped) { bpe(mapped) }
                if (cache.size > 20_000) cache.clear()
            }
            return out
        }

        private fun bpe(word: String): List<Int> {
            vocab[word]?.let { return listOf(it) }
            var parts = word.map { it.toString() }.toMutableList()
            while (parts.size > 1) {
                var best = -1
                var bestRank = Int.MAX_VALUE
                for (i in 0 until parts.size - 1) {
                    val r = ranks[parts[i] to parts[i + 1]] ?: continue
                    if (r < bestRank) { bestRank = r; best = i }
                }
                if (best < 0) break
                val a = parts[best]
                val b = parts[best + 1]
                val merged = ArrayList<String>(parts.size)
                var i = 0
                while (i < parts.size) {
                    if (i < parts.size - 1 && parts[i] == a && parts[i + 1] == b) { merged += a + b; i += 2 }
                    else { merged += parts[i]; i++ }
                }
                parts = merged
            }
            return parts.mapNotNull { vocab[it] ?: unkId }
        }
    }

    companion object {
        /** GPT-2's pre-tokenizer split, as used by `ByteLevel(use_regex=true)`. */
        internal val GPT2_SPLIT = Regex("""'s|'t|'re|'ve|'m|'ll|'d| ?\p{L}+| ?\p{N}+| ?[^\s\p{L}\p{N}]+|\s+(?!\S)|\s+""")

        /** GPT-2 `bytes_to_unicode`: every byte gets a printable character. */
        internal val BYTE_TO_CHAR: CharArray = run {
            val bs = ArrayList<Int>()
            bs += ('!'.code..'~'.code); bs += ('¡'.code..'¬'.code); bs += ('®'.code..'ÿ'.code)
            val cs = bs.toMutableList()
            var n = 0
            for (b in 0..255) if (b !in bs) { bs += b; cs += 256 + n; n++ }
            val table = CharArray(256)
            for (i in bs.indices) table[bs[i]] = cs[i].toChar()
            table
        }

        private val json = Json { ignoreUnknownKeys = true }

        fun fromJson(text: String): HfTokenizer = fromElement(json.parseToJsonElement(text).jsonObject)

        private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull
        private fun JsonObject.bool(k: String): Boolean? = (this[k] as? JsonPrimitive)?.booleanOrNull
        private fun JsonElement?.objOrNull(): JsonObject? = if (this == null || this is JsonNull) null else this as? JsonObject

        private fun fromElement(root: JsonObject): HfTokenizer {
            val modelObj = root["model"].objOrNull() ?: error("tokenizer.json has no model")
            val vocab = HashMap<String, Int>()
            modelObj["vocab"].objOrNull()?.forEach { (k, v) -> v.jsonPrimitive.intOrNull?.let { vocab[k] = it } }
            val special = HashMap<String, Int>()
            (root["added_tokens"] as? JsonArray)?.forEach { t ->
                val o = t.jsonObject
                val content = o.str("content") ?: return@forEach
                val id = (o["id"] as? JsonPrimitive)?.intOrNull ?: return@forEach
                special[content] = id
                vocab.putIfAbsent(content, id)
            }

            val type = modelObj.str("type") ?: if (modelObj.containsKey("merges")) "BPE" else "WordPiece"
            val model: Model = when (type) {
                "WordPiece" -> {
                    val unk = modelObj.str("unk_token") ?: "[UNK]"
                    WordPiece(vocab, vocab[unk] ?: 0, modelObj.str("continuing_subword_prefix") ?: "##", (modelObj["max_input_chars_per_word"] as? JsonPrimitive)?.intOrNull ?: 100)
                }
                "BPE" -> {
                    val ranks = HashMap<Pair<String, String>, Int>()
                    (modelObj["merges"] as? JsonArray)?.forEachIndexed { i, m ->
                        val pair = when (m) {
                            is JsonPrimitive -> m.content.split(' ', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] }
                            is JsonArray -> if (m.size == 2) m[0].jsonPrimitive.content to m[1].jsonPrimitive.content else null
                            else -> null
                        }
                        if (pair != null) ranks.putIfAbsent(pair, i)
                    }
                    val pre = root["pre_tokenizer"].objOrNull()
                    val byteLevel = findByType(pre, "ByteLevel")
                    ByteBpe(vocab, ranks, modelObj.str("unk_token")?.let { vocab[it] }, byteLevel?.bool("add_prefix_space") ?: false)
                }
                else -> error("Unsupported tokenizer model: $type")
            }

            val norm = root["normalizer"].objOrNull()
            val bert = findByType(norm, "BertNormalizer")
            val lowercase = bert?.bool("lowercase") ?: (findByType(norm, "Lowercase") != null)
            val strip = bert?.let { it.bool("strip_accents") ?: lowercase } ?: (findByType(norm, "StripAccents") != null)
            val nfc = findByType(norm, "NFC") != null

            val (prefix, suffix) = specialFrame(root["post_processor"].objOrNull(), special)
            val padId = special["[PAD]"] ?: special["<pad>"] ?: vocab["[PAD]"] ?: 0
            return HfTokenizer(model, lowercase, strip, nfc, prefix, suffix, padId)
        }

        /** Normalizers and pre-tokenizers may be a single object or a Sequence of them. */
        private fun findByType(o: JsonObject?, type: String): JsonObject? {
            if (o == null) return null
            if (o.str("type") == type) return o
            val list = (o["normalizers"] ?: o["pretokenizers"]) as? JsonArray ?: return null
            return list.mapNotNull { it.objOrNull() }.firstOrNull { it.str("type") == type }
        }

        /** Ids added before and after the sequence: TemplateProcessing, BertProcessing, RobertaProcessing. */
        private fun specialFrame(pp: JsonObject?, special: Map<String, Int>): Pair<IntArray, IntArray> {
            if (pp == null) return IntArray(0) to IntArray(0)
            fun idOf(tok: JsonElement?): Int? = when (tok) {
                is JsonArray -> tok.getOrNull(1)?.jsonPrimitive?.intOrNull ?: tok.getOrNull(0)?.jsonPrimitive?.contentOrNull?.let { special[it] }
                is JsonPrimitive -> special[tok.content]
                else -> null
            }
            return when (pp.str("type")) {
                "TemplateProcessing" -> {
                    val items = (pp["single"] as? JsonArray).orEmpty()
                    val tokens = pp["special_tokens"].objOrNull()
                    val before = ArrayList<Int>(); val after = ArrayList<Int>()
                    var seenSeq = false
                    for (it in items) {
                        val o = it.jsonObject
                        if (o.containsKey("Sequence")) { seenSeq = true; continue }
                        val name = o["SpecialToken"].objOrNull()?.str("id") ?: continue
                        val ids = tokens?.get(name).objOrNull()?.get("ids")?.jsonArray?.mapNotNull { e -> e.jsonPrimitive.intOrNull }
                            ?: listOfNotNull(special[name])
                        if (seenSeq) after += ids else before += ids
                    }
                    before.toIntArray() to after.toIntArray()
                }
                "BertProcessing", "RobertaProcessing" -> {
                    val cls = idOf(pp["cls"]); val sep = idOf(pp["sep"])
                    listOfNotNull(cls).toIntArray() to listOfNotNull(sep).toIntArray()
                }
                else -> IntArray(0) to IntArray(0)
            }
        }
    }
}
