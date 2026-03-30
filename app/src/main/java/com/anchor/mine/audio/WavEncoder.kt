package com.anchor.mine.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder

object WavEncoder {
    fun encodeToWav(floatArray: FloatArray, sampleRate: Int = 16000): ByteArray {
        val channels = 1
        val bitDepth = 16
        val byteRate = sampleRate * channels * bitDepth / 8
        val audioDataLength = floatArray.size * 2 // 16-bit = 2 bytes per sample
        val totalDataLength = audioDataLength + 36

        val buffer = ByteBuffer.allocate(44 + audioDataLength)
        buffer.order(ByteOrder.LITTLE_ENDIAN)

        // RIFF Header
        buffer.put("RIFF".toByteArray())
        buffer.putInt(totalDataLength)
        buffer.put("WAVE".toByteArray())

        // fmt subchunk
        buffer.put("fmt ".toByteArray())
        buffer.putInt(16) // Subchunk1Size (16 for PCM)
        buffer.putShort(1.toShort()) // AudioFormat (1 = PCM)
        buffer.putShort(channels.toShort())
        buffer.putInt(sampleRate)
        buffer.putInt(byteRate)
        buffer.putShort((channels * bitDepth / 8).toShort()) // BlockAlign
        buffer.putShort(bitDepth.toShort())

        // data subchunk
        buffer.put("data".toByteArray())
        buffer.putInt(audioDataLength)

        // Audio data (Convert Float [-1.0, 1.0] back to 16-bit PCM Short)
        for (f in floatArray) {
            val clamped = f.coerceIn(-1.0f, 1.0f)
            val shortVal = (clamped * 32767.0f).toInt().toShort()
            buffer.putShort(shortVal)
        }

        return buffer.array()
    }
}