// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.retrieval

import com.anchor.core.voice.ModelCatalog
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HfTokenizerTest {

    private val wordPieceJson = """
    {
      "added_tokens": [
        {"id": 0, "content": "[PAD]", "special": true},
        {"id": 1, "content": "[UNK]", "special": true},
        {"id": 2, "content": "[CLS]", "special": true},
        {"id": 3, "content": "[SEP]", "special": true}
      ],
      "normalizer": {"type": "BertNormalizer", "lowercase": true, "strip_accents": null, "clean_text": true},
      "pre_tokenizer": {"type": "BertPreTokenizer"},
      "post_processor": {
        "type": "TemplateProcessing",
        "single": [{"SpecialToken": {"id": "[CLS]", "type_id": 0}}, {"Sequence": {"id": "A", "type_id": 0}}, {"SpecialToken": {"id": "[SEP]", "type_id": 0}}],
        "special_tokens": {"[CLS]": {"id": "[CLS]", "ids": [2], "tokens": ["[CLS]"]}, "[SEP]": {"id": "[SEP]", "ids": [3], "tokens": ["[SEP]"]}}
      },
      "model": {
        "type": "WordPiece", "unk_token": "[UNK]", "continuing_subword_prefix": "##", "max_input_chars_per_word": 100,
        "vocab": {"[PAD]": 0, "[UNK]": 1, "[CLS]": 2, "[SEP]": 3, "call": 4, "mom": 5, "at": 6, "5": 7, "pm": 8, "!": 9, "re": 10, "##mind": 11, "##er": 12, "cafe": 13}
      }
    }
    """.trimIndent()

    @Test fun wordPieceLowercasesSplitsPunctuationAndUsesContinuations() {
        val t = HfTokenizer.fromJson(wordPieceJson)
        val e = t.encode("Call MOM at 5 PM! Reminder")
        assertArrayEquals(intArrayOf(2, 4, 5, 6, 7, 8, 9, 10, 11, 12, 3), e.ids)
        assertTrue(e.attentionMask.all { it == 1 })
        // Accents are stripped for uncased models; unknown words become [UNK].
        assertArrayEquals(intArrayOf(2, 13, 1, 3), t.encode("Café zzz").ids)
    }

    @Test fun truncationKeepsTheFrame() {
        val t = HfTokenizer.fromJson(wordPieceJson)
        val e = t.encode("call mom call mom call mom", maxTokens = 5)
        assertArrayEquals(intArrayOf(2, 4, 5, 4, 3), e.ids)
    }

    /** A tiny byte-level BPE in the ModernBERT shape (merges as "a b" strings). */
    private fun bpeJson(mergesAsArrays: Boolean): String {
        val g = HfTokenizer.BYTE_TO_CHAR[' '.code] // 'Ġ'
        val merges = listOf("c a", "ca ll", "${g} m", "${g}m om")
        val mergesJson = if (mergesAsArrays) merges.joinToString(",") { m -> m.split(" ").let { "[\"${it[0]}\",\"${it[1]}\"]" } }
        else merges.joinToString(",") { "\"$it\"" }
        return """
        {
          "added_tokens": [{"id": 50, "content": "[CLS]", "special": true}, {"id": 51, "content": "[SEP]", "special": true}, {"id": 52, "content": "[PAD]", "special": true}],
          "normalizer": {"type": "NFC"},
          "pre_tokenizer": {"type": "ByteLevel", "add_prefix_space": false, "use_regex": true},
          "post_processor": {"type": "TemplateProcessing",
            "single": [{"SpecialToken": {"id": "[CLS]", "type_id": 0}}, {"Sequence": {"id": "A", "type_id": 0}}, {"SpecialToken": {"id": "[SEP]", "type_id": 0}}],
            "special_tokens": {"[CLS]": {"id": "[CLS]", "ids": [50], "tokens": ["[CLS]"]}, "[SEP]": {"id": "[SEP]", "ids": [51], "tokens": ["[SEP]"]}}},
          "model": {"type": "BPE", "merges": [$mergesJson],
            "vocab": {"c": 1, "a": 2, "l": 3, "ca": 4, "call": 5, "${g}": 6, "m": 7, "o": 8, "${g}m": 9, "${g}mom": 10, "!": 11}}
        }
        """.trimIndent()
    }

    @Test fun byteLevelBpeMergesByRankAndMarksSpaces() {
        for (arrays in listOf(false, true)) {
            val t = HfTokenizer.fromJson(bpeJson(arrays))
            assertArrayEquals("merges as ${if (arrays) "arrays" else "strings"}", intArrayOf(50, 5, 10, 11, 51), t.encode("call mom!").ids)
            assertEquals(52, t.padId)
        }
    }

    @Test fun gpt2SplitAndByteTableMatchTheReference() {
        assertEquals(listOf("Hello", " world", "!!", " I", "'m", " 42"), HfTokenizer.GPT2_SPLIT.findAll("Hello world!! I'm 42").map { it.value }.toList())
        assertEquals('Ġ', HfTokenizer.BYTE_TO_CHAR[' '.code])
        assertEquals('Ċ', HfTokenizer.BYTE_TO_CHAR['\n'.code])
        assertEquals('A', HfTokenizer.BYTE_TO_CHAR['A'.code])
        assertEquals(256, HfTokenizer.BYTE_TO_CHAR.toSet().size)
    }

    @Test fun catalogSelectsAndRenamesFiles() {
        val src = ModelCatalog.EMBED_GRANITE_SMALL_R2.source as ModelCatalog.Source.HuggingFace
        assertTrue(ModelCatalog.includes(src.include, "onnx/model_quantized.onnx"))
        assertTrue(!ModelCatalog.includes(src.include, "onnx/model_fp16.onnx"))
        assertEquals("model.onnx", ModelCatalog.localName(src, "onnx/model_quantized.onnx"))
        // External weights keep their name: the .onnx file refers to them by it.
        assertEquals("model_quantized.onnx_data", ModelCatalog.localName(src, "onnx/model_quantized.onnx_data"))
        val kokoro = ModelCatalog.TTS_KOKORO.source as ModelCatalog.Source.HuggingFace
        assertTrue(ModelCatalog.includes(kokoro.include, "espeak-ng-data/en_dict"))
        assertEquals(ModelCatalog.ALL.size, ModelCatalog.ALL.map { it.id }.toSet().size)
        assertTrue(ModelCatalog.ALL.filter { it.embed != null }.all { e -> e.requiredFiles.contains(e.embed!!.onnxFile) })
    }
}
