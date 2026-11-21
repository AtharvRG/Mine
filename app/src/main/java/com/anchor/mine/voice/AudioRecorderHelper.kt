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
    private val sampleRate: Int = 16000,
    private val maxDurationSeconds: Int = 180,
    private val isForegroundUserMic: Boolean = true
) {

    companion object {
        @Volatile
        var isForegroundMicActive: Boolean = false
    }

    @Volatile
    var isRecording: Boolean = false
        private set

    @Volatile
    var currentRms: Float = 0f
        private set

    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private val chunkList = ArrayList<FloatArray>()
    private var totalSampleCount = 0
    private val lock = Any()

    @SuppressLint("MissingPermission")
    fun startRecording(coroutineScope: CoroutineScope): Boolean {
        synchronized(lock) {
            if (isRecording) return true
            chunkList.clear()
            totalSampleCount = 0

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
            if (isForegroundUserMic) isForegroundMicActive = true

            recordingJob = coroutineScope.launch(Dispatchers.IO) {
                val shortBuffer = ShortArray(minBufferSize)
                val maxSamples = sampleRate * maxDurationSeconds
                try {
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
                            val shouldStop = synchronized(lock) {
                                chunkList.add(floats)
                                totalSampleCount += read
                                if (totalSampleCount >= maxSamples) {
                                    if (isForegroundUserMic) {
                                        isRecording = false
                                        true
                                    } else {
                                        // Ambient ring buffer: drop oldest audio chunks to remain bounded
                                        while (totalSampleCount > maxSamples / 2 && chunkList.isNotEmpty()) {
                                            totalSampleCount -= chunkList.removeAt(0).size
                                        }
                                        false
                                    }
                                } else {
                                    false
                                }
                            }
                            if (shouldStop) {
                                break
                            }
                        }
                    }
                } finally {
                    if (isForegroundUserMic) isForegroundMicActive = false
                    try {
                        record.stop()
                    } catch (_: Throwable) {}
                    try {
                        record.release()
                    } catch (_: Throwable) {}
                    synchronized(lock) {
                        if (audioRecord === record) {
                            audioRecord = null
                            isRecording = false
                        }
                    }
                }
            }
            return true
        }
    }

    fun snapshotAndClear(): FloatArray {
        synchronized(lock) {
            val result = FloatArray(totalSampleCount)
            var offset = 0
            for (chunk in chunkList) {
                System.arraycopy(chunk, 0, result, offset, chunk.size)
                offset += chunk.size
            }
            chunkList.clear()
            totalSampleCount = 0
            currentRms = 0f
            return result
        }
    }

    fun stopRecording(): FloatArray {
        synchronized(lock) {
            isRecording = false
            if (isForegroundUserMic) isForegroundMicActive = false
            recordingJob?.cancel()
            recordingJob = null

            try {
                audioRecord?.stop()
                audioRecord?.release()
            } catch (_: Exception) {
            } finally {
                audioRecord = null
            }

            val result = FloatArray(totalSampleCount)
            var offset = 0
            for (chunk in chunkList) {
                System.arraycopy(chunk, 0, result, offset, chunk.size)
                offset += chunk.size
            }
            chunkList.clear()
            totalSampleCount = 0
            currentRms = 0f
            return result
        }
    }

    fun cancelRecording() {
        synchronized(lock) {
            isRecording = false
            if (isForegroundUserMic) isForegroundMicActive = false
            recordingJob?.cancel()
            recordingJob = null
            try {
                audioRecord?.stop()
                audioRecord?.release()
            } catch (_: Exception) {
            } finally {
                audioRecord = null
            }
            chunkList.clear()
            totalSampleCount = 0
            currentRms = 0f
        }
    }
}
