// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.voice

/**
 * Every downloadable on-device model (blueprint 60 §3.4): what it is, where it comes from, how
 * big it is, its licence, and where it lives under `files/models/`. Weights are never bundled
 * (D-078); the user downloads them after seeing size and terms.
 *
 * Integrity: for Hugging Face sources the expected SHA-256 of every large file is read at
 * download time from the repository's tree API (Git LFS object id) and checked after the
 * download; small text files are checked by size. [Source.Direct] files carry a pinned SHA-256.
 */
object ModelCatalog {

    sealed interface Source {
        /** Files from a Hugging Face model repo, selected by [include] (prefix or exact path). */
        data class HuggingFace(val repo: String, val revision: String = "main", val include: List<String>, val rename: Map<String, String> = emptyMap()) : Source
        /** One file at a fixed URL with a pinned checksum. */
        data class Direct(val url: String, val sha256: String, val sizeBytes: Long, val fileName: String) : Source
    }

    enum class Pooling { CLS, MEAN }
    enum class TokenizerKind { WORDPIECE, BYTE_BPE }

    data class EmbedSpec(
        val onnxFile: String,
        val dim: Int,
        val pooling: Pooling,
        val tokenizer: TokenizerKind,
        val queryPrefix: String = "",
        val documentPrefix: String = "",
        val maxTokens: Int = 512,
    )

    data class Entry(
        val id: String,
        val kind: ModelAssetKind,
        val name: String,
        val summary: String,
        val approxBytes: Long,
        val license: String,
        val licenseUrl: String,
        /** Folder under `files/models/`. Must match what the loader expects (see VoiceModelPaths). */
        val installDir: String,
        val source: Source,
        /** Files that must exist (relative to [installDir]) for the model to count as installed. */
        val requiredFiles: List<String>,
        val embed: EmbedSpec? = null,
        /** Shown in the app wherever this model's output is used, when its licence asks for it. */
        val attribution: String? = null,
        /** False when Mine can't run this model on Android yet: listed so the user knows why. */
        val runnable: Boolean = true,
        val note: String? = null,
        val recommended: Boolean = false,
    )

    // ---- speech --------------------------------------------------------------------------------

    val SILERO_VAD = Entry(
        id = "vad-silero", kind = ModelAssetKind.VAD, name = "Silero VAD",
        summary = "Finds where speech starts and stops. Needed for clean listening and voice enrolment.",
        approxBytes = 643_854, license = "MIT", licenseUrl = "https://github.com/snakers4/silero-vad/blob/master/LICENSE",
        installDir = "vad",
        source = Source.Direct(
            url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx",
            sha256 = "9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6",
            sizeBytes = 643_854, fileName = "silero_vad.onnx",
        ),
        requiredFiles = listOf("silero_vad.onnx"),
        recommended = true,
    )

    val SPEAKER_CAMPPLUS = Entry(
        id = "speaker-campplus-en", kind = ModelAssetKind.SPEAKER, name = "CAM++ speaker model (English)",
        summary = "Turns a voice into an x-vector so Mine can tell you from everyone else.",
        approxBytes = 29_600_000, license = "Apache-2.0", licenseUrl = "https://github.com/modelscope/3D-Speaker/blob/main/LICENSE",
        installDir = "speaker",
        source = Source.HuggingFace(
            "csukuangfj/speaker-embedding-models",
            include = listOf("3dspeaker_speech_campplus_sv_en_voxceleb_16k.onnx"),
            rename = mapOf("3dspeaker_speech_campplus_sv_en_voxceleb_16k.onnx" to "campplus.onnx"),
        ),
        requiredFiles = listOf("campplus.onnx"),
        recommended = true,
    )

    val STT_ZIPFORMER_EN = Entry(
        id = "stt-zipformer-en", kind = ModelAssetKind.STT, name = "Zipformer speech-to-text (English)",
        summary = "Private, offline transcription for voice chat and listening.",
        approxBytes = 72_660_000, license = "Apache-2.0", licenseUrl = "https://github.com/k2-fsa/icefall/blob/master/LICENSE",
        installDir = "stt/zipformer",
        source = Source.HuggingFace(
            "csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-06-26",
            include = listOf(
                "encoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx",
                "decoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx",
                "joiner-epoch-99-avg-1-chunk-16-left-128.int8.onnx",
                "tokens.txt",
            ),
        ),
        requiredFiles = listOf(
            "encoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx",
            "decoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx",
            "joiner-epoch-99-avg-1-chunk-16-left-128.int8.onnx",
            "tokens.txt",
        ),
        recommended = true,
    )

    // ---- text to speech --------------------------------------------------------------------------

    val TTS_KOKORO = Entry(
        id = "tts-kokoro-int8", kind = ModelAssetKind.TTS, name = "Kokoro (int8)",
        summary = "Natural voices, including British ones. About 185 MB.",
        approxBytes = 185_000_000, license = "Apache-2.0", licenseUrl = "https://huggingface.co/hexgrad/Kokoro-82M",
        installDir = "tts/kokoro",
        source = Source.HuggingFace(
            "csukuangfj/kokoro-int8-multi-lang-v1_1",
            include = listOf("model.int8.onnx", "voices.bin", "tokens.txt", "lexicon-us-en.txt", "lexicon-gb-en.txt", "espeak-ng-data/", "dict/"),
        ),
        requiredFiles = listOf("model.int8.onnx", "voices.bin", "tokens.txt", "espeak-ng-data"),
    )

    val TTS_KITTEN = Entry(
        id = "tts-kitten-nano", kind = ModelAssetKind.TTS, name = "Kitten TTS nano",
        summary = "Tiny and quick: about 34 MB with its pronunciation data.",
        approxBytes = 34_000_000, license = "Apache-2.0", licenseUrl = "https://huggingface.co/KittenML",
        installDir = "tts/kitten",
        source = Source.HuggingFace(
            "csukuangfj/kitten-nano-en-v0_2-fp16",
            include = listOf("model.fp16.onnx", "voices.bin", "tokens.txt", "espeak-ng-data/"),
        ),
        requiredFiles = listOf("model.fp16.onnx", "voices.bin", "tokens.txt", "espeak-ng-data"),
        recommended = true,
    )

    val TTS_UNDERDOG_WOOF = Entry(
        id = "tts-underdog-woof", kind = ModelAssetKind.TTS, name = "Underdog Woof",
        summary = "40.6 MB English voice model (Juno and Mira).",
        approxBytes = 40_595_426, license = "Underdog Non-Commercial 1.0",
        licenseUrl = "https://huggingface.co/ConwayResearch/Underdog-Woof",
        installDir = "tts/woof",
        source = Source.HuggingFace("ConwayResearch/Underdog-Woof", include = listOf("woof_int8.npz", "LICENSE", "NOTICE")),
        requiredFiles = listOf("woof_int8.npz"),
        attribution = "Voice by Underdog Woof",
        runnable = false,
        note = "Woof ships as NumPy weights with its own Python runtime. It isn't an ONNX model, so sherpa-onnx can't run it, " +
            "and there is no Android runtime for it yet. Free for personal use with the credit \"Voice by Underdog Woof\"; " +
            "commercial use needs a licence from Underdog.",
    )

    // ---- embeddings (search memory) ----------------------------------------------------------------

    val EMBED_GRANITE_SMALL_R2 = Entry(
        id = "embed-granite-small-r2", kind = ModelAssetKind.EMBED, name = "IBM Granite Embedding small R2",
        summary = "47M parameters, 384 dimensions, English. Strong retrieval for its size. ~53 MB (int8).",
        approxBytes = 54_610_000, license = "Apache-2.0", licenseUrl = "https://huggingface.co/ibm-granite/granite-embedding-small-english-r2",
        installDir = "embed/granite-small-r2",
        source = Source.HuggingFace(
            "onnx-community/granite-embedding-small-english-r2-ONNX",
            include = listOf("onnx/model_quantized.onnx", "onnx/model_quantized.onnx_data", "tokenizer.json"),
            rename = mapOf("onnx/model_quantized.onnx" to "model.onnx", "onnx/model_quantized.onnx_data" to "model_quantized.onnx_data"),
        ),
        requiredFiles = listOf("model.onnx", "model_quantized.onnx_data", "tokenizer.json"),
        embed = EmbedSpec("model.onnx", dim = 384, pooling = Pooling.CLS, tokenizer = TokenizerKind.BYTE_BPE),
        recommended = true,
    )

    val EMBED_GRANITE_R2 = Entry(
        id = "embed-granite-r2", kind = ModelAssetKind.EMBED, name = "IBM Granite Embedding R2",
        summary = "149M parameters, 768 dimensions, English. Best quality here, slower. ~160 MB (int8).",
        approxBytes = 160_850_000, license = "Apache-2.0", licenseUrl = "https://huggingface.co/ibm-granite/granite-embedding-english-r2",
        installDir = "embed/granite-r2",
        source = Source.HuggingFace(
            "onnx-community/granite-embedding-english-r2-ONNX",
            include = listOf("onnx/model_quantized.onnx", "onnx/model_quantized.onnx_data", "tokenizer.json"),
            rename = mapOf("onnx/model_quantized.onnx" to "model.onnx", "onnx/model_quantized.onnx_data" to "model_quantized.onnx_data"),
        ),
        requiredFiles = listOf("model.onnx", "model_quantized.onnx_data", "tokenizer.json"),
        embed = EmbedSpec("model.onnx", dim = 768, pooling = Pooling.CLS, tokenizer = TokenizerKind.BYTE_BPE),
    )

    val EMBED_MINILM = Entry(
        id = "embed-minilm-l6", kind = ModelAssetKind.EMBED, name = "all-MiniLM-L6-v2",
        summary = "The classic small embedder: 22M parameters, 384 dimensions, very fast. ~23 MB.",
        approxBytes = 23_500_000, license = "Apache-2.0", licenseUrl = "https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2",
        installDir = "embed/minilm-l6",
        source = Source.HuggingFace(
            "sentence-transformers/all-MiniLM-L6-v2",
            include = listOf("onnx/model_qint8_arm64.onnx", "tokenizer.json"),
            rename = mapOf("onnx/model_qint8_arm64.onnx" to "model.onnx"),
        ),
        requiredFiles = listOf("model.onnx", "tokenizer.json"),
        embed = EmbedSpec("model.onnx", dim = 384, pooling = Pooling.MEAN, tokenizer = TokenizerKind.WORDPIECE, maxTokens = 256),
    )

    val EMBED_BGE_SMALL = Entry(
        id = "embed-bge-small-en", kind = ModelAssetKind.EMBED, name = "BGE small English v1.5",
        summary = "33M parameters, 384 dimensions, good general retrieval. ~34 MB (int8).",
        approxBytes = 34_500_000, license = "MIT", licenseUrl = "https://huggingface.co/BAAI/bge-small-en-v1.5",
        installDir = "embed/bge-small-en",
        source = Source.HuggingFace(
            "Xenova/bge-small-en-v1.5",
            include = listOf("onnx/model_quantized.onnx", "tokenizer.json"),
            rename = mapOf("onnx/model_quantized.onnx" to "model.onnx"),
        ),
        requiredFiles = listOf("model.onnx", "tokenizer.json"),
        embed = EmbedSpec(
            "model.onnx", dim = 384, pooling = Pooling.CLS, tokenizer = TokenizerKind.WORDPIECE,
            queryPrefix = "Represent this sentence for searching relevant passages: ",
        ),
    )

    val ALL: List<Entry> = listOf(
        SILERO_VAD, SPEAKER_CAMPPLUS, STT_ZIPFORMER_EN,
        TTS_KITTEN, TTS_KOKORO, TTS_UNDERDOG_WOOF,
        EMBED_GRANITE_SMALL_R2, EMBED_GRANITE_R2, EMBED_MINILM, EMBED_BGE_SMALL,
    )

    fun byId(id: String): Entry? = ALL.firstOrNull { it.id == id }

    /** Settings: which embedder and which TTS engine are active. */
    const val KEY_ACTIVE_EMBEDDER = "ui.models.embedder"
    const val KEY_ACTIVE_TTS = "voice.tts.engine"
    /** "1" = downloads may use mobile data (default: Wi-Fi only, blueprint 60 §5). */
    const val KEY_ALLOW_MOBILE_DATA = "voice.models.mobile_data"

    /** True when [path] (repo-relative) is selected by [include] entries (exact file or "dir/" prefix). */
    fun includes(include: List<String>, path: String): Boolean =
        include.any { inc -> if (inc.endsWith("/")) path.startsWith(inc) else path == inc }

    /** Where a repo file lands inside the model's folder. */
    fun localName(src: Source.HuggingFace, path: String): String = src.rename[path] ?: path

    /** Embedding vectors from different models must never be compared: each model has its own version tag. */
    fun embedVersion(entry: Entry): String = "${entry.id}-${entry.embed?.dim ?: 0}-v1"
}
