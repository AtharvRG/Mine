package com.mine.app.util

import java.nio.ByteBuffer
import kotlin.math.sqrt

object VectorUtils {

    // Converts a List of Floats from NVIDIA into a ByteArray for SQLite BLOB storage
    fun floatArrayToByteArray(floats: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(floats.size * 4) // 4 bytes per float
        for (f in floats) {
            buffer.putFloat(f)
        }
        return buffer.array()
    }

    // Converts the SQLite BLOB back into a FloatArray for math
    fun byteArrayToFloatArray(bytes: ByteArray): FloatArray {
        val buffer = ByteBuffer.wrap(bytes)
        val floats = FloatArray(bytes.size / 4)
        for (i in floats.indices) {
            floats[i] = buffer.float
        }
        return floats
    }

    // The magic formula: Returns 1.0 for identical meaning, 0.0 for completely unrelated
    fun cosineSimilarity(v1: FloatArray, v2: FloatArray): Float {
        if (v1.size != v2.size) return 0.0f

        var dotProduct = 0.0f
        var normA = 0.0f
        var normB = 0.0f

        for (i in v1.indices) {
            dotProduct += v1[i] * v2[i]
            normA += v1[i] * v1[i]
            normB += v2[i] * v2[i]
        }

        return if (normA == 0.0f || normB == 0.0f) 0.0f else (dotProduct / (sqrt(normA) * sqrt(normB)))
    }
}
