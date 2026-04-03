package com.anchor.mine.brain

import org.json.JSONArray
import kotlin.math.sqrt

object VectorMath {

    // Converts the JSON string from Room back into a FloatArray
    fun parseVector(jsonString: String): FloatArray {
        val jsonArray = JSONArray(jsonString)
        val floatArray = FloatArray(jsonArray.length())
        for (i in 0 until jsonArray.length()) {
            floatArray[i] = jsonArray.getDouble(i).toFloat()
        }
        return floatArray
    }

    // Calculates the "Cosine Similarity" between two vectors.
    // 1.0 means they mean the exact same thing. 0.0 means they are unrelated.
    fun cosineSimilarity(vectorA: FloatArray, vectorB: FloatArray): Float {
        if (vectorA.size != vectorB.size) return 0.0f

        var dotProduct = 0.0f
        var normA = 0.0f
        var normB = 0.0f

        for (i in vectorA.indices) {
            dotProduct += vectorA[i] * vectorB[i]
            normA += vectorA[i] * vectorA[i]
            normB += vectorB[i] * vectorB[i]
        }

        if (normA == 0.0f || normB == 0.0f) return 0.0f
        return dotProduct / (sqrt(normA) * sqrt(normB))
    }
}