// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.mine.voice

import android.content.Context
import java.io.File

/**
 * Resolves paths to offline speech models for VAD, STT, and TTS.
 * Supports both internal app storage (production/downloaded) and /data/local/tmp (testing/staging).
 */
data class VoiceModelPaths(
    val vadModel: File,
    val sttEncoder: File,
    val sttDecoder: File,
    val sttJoiner: File,
    val sttTokens: File,
    val ttsModel: File,
    val ttsVoices: File,
    val ttsTokens: File,
    val ttsDataDir: File,
    val ttsLexicon: File,
    val speakerModel: File? = null,
    /** Kokoro multi-lang (v1.0+) word-segmentation dictionary folder; absent for older packs. */
    val ttsDictDir: File? = null,
    /** Kitten TTS (small, Apache-2.0), the alternative engine. */
    val kittenModel: File? = null,
    val kittenVoices: File? = null,
    val kittenTokens: File? = null,
    val kittenDataDir: File? = null,
) {
    val kittenComplete: Boolean
        get() = kittenModel?.exists() == true && kittenVoices?.exists() == true && kittenTokens?.exists() == true && kittenDataDir?.exists() == true

    val vadPresent: Boolean
        get() = vadModel.exists()

    val sttComplete: Boolean
        get() = sttEncoder.exists() &&
                sttDecoder.exists() &&
                sttJoiner.exists() &&
                sttTokens.exists()

    val ttsComplete: Boolean
        get() = ttsModel.exists() &&
                ttsVoices.exists() &&
                ttsTokens.exists() &&
                (ttsLexicon.exists() || ttsDataDir.exists())

    val speakerPresent: Boolean
        get() = speakerModel != null && speakerModel.exists()

    val isComplete: Boolean
        get() = vadPresent && (sttComplete || ttsComplete || kittenComplete)

    /** Any on-device voice installed (Kokoro or Kitten). */
    val anyTtsComplete: Boolean get() = ttsComplete || kittenComplete

    companion object {
        fun resolve(context: Context): VoiceModelPaths? {
            val candidateDirs = mutableListOf(
                context.filesDir.resolve("models"),
                File(context.filesDir, "models")
            )
            if (com.anchor.mine.BuildConfig.DEBUG) {
                candidateDirs.add(File("/data/local/tmp/models"))
            }

            for (base in candidateDirs) {
                val candidate = fromBaseDir(base)
                if (candidate.vadPresent || candidate.sttComplete || candidate.ttsComplete || candidate.kittenComplete || candidate.speakerPresent) {
                    return candidate
                }
            }
            return null
        }

        fun fromBaseDir(baseDir: File): VoiceModelPaths {
            val vadDir = baseDir.resolve("vad")
            val sttDir = baseDir.resolve("stt/zipformer")
            val ttsDir = baseDir.resolve("tts/kokoro")
            val kittenDir = baseDir.resolve("tts/kitten")

            return VoiceModelPaths(
                vadModel = vadDir.resolve("silero_vad.onnx"),
                sttEncoder = sttDir.resolve("encoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx"),
                sttDecoder = sttDir.resolve("decoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx"),
                sttJoiner = sttDir.resolve("joiner-epoch-99-avg-1-chunk-16-left-128.int8.onnx"),
                sttTokens = sttDir.resolve("tokens.txt"),
                ttsModel = ttsDir.resolve("model.int8.onnx"),
                ttsVoices = ttsDir.resolve("voices.bin"),
                ttsTokens = ttsDir.resolve("tokens.txt"),
                ttsDataDir = ttsDir.resolve("espeak-ng-data"),
                ttsLexicon = ttsDir.resolve("lexicon-us-en.txt"),
                speakerModel = listOf(
                    baseDir.resolve("speaker/campplus.onnx"),
                    baseDir.resolve("speaker/3dspeaker.onnx"),
                    baseDir.resolve("speaker/speaker.onnx")
                ).firstOrNull { it.exists() },
                ttsDictDir = ttsDir.resolve("dict").takeIf { it.exists() },
                kittenModel = kittenDir.resolve("model.fp16.onnx"),
                kittenVoices = kittenDir.resolve("voices.bin"),
                kittenTokens = kittenDir.resolve("tokens.txt"),
                kittenDataDir = kittenDir.resolve("espeak-ng-data"),
            )
        }
    }
}
