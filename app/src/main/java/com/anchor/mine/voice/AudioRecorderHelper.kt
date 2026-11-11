// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.mine.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.sqrt

/**
 * In-memory 16 kHz audio recorder for voice transcription.
 * Blueprint 60 §3.2.
 *
 * Invariants:
 * - 16 kHz mono.
 * - Entirely in-memory buffer: ZERO audio saved to SQLite or storage.
 * - Foreground only; stopped immediately on cancel or finish.
 */
class AudioRecorderHelper(
    private val sampleRate: Int = 16000
) {

    @Volatile
    var isRecording: Boolean = false
        private set

    @Volatile
    var currentRms: Float = 0f
        private set

    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private val sampleBuffer = mutableListOf<Float>()
    private val lock = Any()

    @SuppressLint("MissingPermission")
    fun startRecording(coroutineScope: CoroutineScope): Boolean {
        synchronized(lock) {
            if (isRecording) return true
            sampleBuffer.clear()

            val minBufferSize = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBufferSize <= 0) return false

            val record = try {
                AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBufferSize * 2
                )
            } catch (_: Exception) {
                return false
            }

            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                return false
            }

            try {
                record.startRecording()
            } catch (_: Exception) {
                record.release()
                return false
            }

            audioRecord = record
            isRecording = true

            recordingJob = coroutineScope.launch(Dispatchers.IO) {
                val shortBuffer = ShortArray(minBufferSize)
                while (isActive && isRecording) {
                    val read = record.read(shortBuffer, 0, shortBuffer.size)
                    if (read > 0) {
                        var sumSq = 0.0
                        val floats = FloatArray(read)
                        for (i in 0 until read) {
                            val f = shortBuffer[i] / 32768.0f
                            floats[i] = f
                            sumSq += (f * f)
                        }
                        currentRms = sqrt(sumSq / read).toFloat()
                        synchronized(lock) {
                            for (i in 0 until read) {
                                sampleBuffer.add(floats[i])
                            }
                        }
                    }
                }
            }
            return true
        }
    }

    fun stopRecording(): FloatArray {
        synchronized(lock) {
            isRecording = false
            recordingJob?.cancel()
            recordingJob = null

            try {
                audioRecord?.stop()
                audioRecord?.release()
            } catch (_: Exception) {
            } finally {
                audioRecord = null
            }

            val result = sampleBuffer.toFloatArray()
            sampleBuffer.clear()
            currentRms = 0f
            return result
        }
    }

    fun cancelRecording() {
        synchronized(lock) {
            isRecording = false
            recordingJob?.cancel()
            recordingJob = null
            try {
                audioRecord?.stop()
                audioRecord?.release()
            } catch (_: Exception) {
            } finally {
                audioRecord = null
            }
            sampleBuffer.clear()
            currentRms = 0f
        }
    }
}
