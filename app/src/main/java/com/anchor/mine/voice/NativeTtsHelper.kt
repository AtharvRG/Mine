// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.mine.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * Built-in Android TextToSpeech helper.
 * Provides resilient, zero-model fallback speech output when Sherpa-ONNX is not installed or unavailable.
 */
object NativeTtsHelper {
    private var tts: TextToSpeech? = null
    private var isInitialized: Boolean = false
    private val pendingUtterances = mutableListOf<String>()
    private val lock = Any()

    fun init(context: Context) {
        synchronized(lock) {
            if (tts == null) {
                val appContext = context.applicationContext
                tts = TextToSpeech(appContext) { status ->
                    synchronized(lock) {
                        if (status == TextToSpeech.SUCCESS) {
                            tts?.language = Locale.getDefault()
                            isInitialized = true
                            for (text in pendingUtterances) {
                                tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "tts_${text.hashCode()}")
                            }
                            pendingUtterances.clear()
                        }
                    }
                }
            }
        }
    }

    fun speak(context: Context, text: String) {
        if (text.isBlank()) return
        synchronized(lock) {
            if (tts == null) {
                init(context)
            }
            if (isInitialized) {
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "tts_${text.hashCode()}")
            } else {
                pendingUtterances.add(text)
            }
        }
    }

    fun stop() {
        synchronized(lock) {
            tts?.stop()
        }
    }

    fun shutdown() {
        synchronized(lock) {
            tts?.stop()
            tts?.shutdown()
            tts = null
            isInitialized = false
            pendingUtterances.clear()
        }
    }
}
