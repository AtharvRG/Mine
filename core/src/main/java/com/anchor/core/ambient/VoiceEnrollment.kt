// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.ambient

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Teaching Mine your voice (blueprint 61 §2): the user reads a few sentences, each clip becomes
 * a speaker embedding (x-vector), and this decides whether the result is good enough to tell
 * them apart from other people — and says plainly what to fix when it isn't.
 *
 * Pure maths, no Android: the app records, runs VAD and the embedding model, and hands the
 * numbers here.
 */
object VoiceEnrollment {

    const val MIN_CLIPS = 3
    const val RECOMMENDED_CLIPS = 5

    /** Sentences that cover a wide spread of English sounds and are easy to read aloud. */
    val PROMPTS = listOf(
        "The quick brown fox jumps over the lazy dog near the riverbank.",
        "Remind me to call my sister about Saturday's dinner plans at seven.",
        "I usually start my mornings with a strong coffee and a short walk.",
        "Please move the dentist appointment to Thursday afternoon at three thirty.",
        "Bright yellow umbrellas opened quickly when the thunderstorm finally arrived.",
        "My favourite place to think is a quiet table by the window.",
        "Could you add milk, bread, and two dozen eggs to the shopping list?",
    )

    enum class Issue(val message: String) {
        TOO_SHORT("Too short. Read the whole sentence."),
        TOO_QUIET("Too quiet. Hold the phone closer and speak up a little."),
        CLIPPING("Too loud — the mic distorted. Hold the phone a bit further away."),
        NOISY("Too much background noise. Find a quieter spot."),
        NOT_LIKE_OTHERS("Didn't sound like your other clips. Use your normal speaking voice."),
    }

    enum class Verdict(val title: String) {
        GOOD("Great — Mine knows your voice"),
        OKAY("Usable, but could be better"),
        POOR("Not good enough yet"),
    }

    /** Loudness and noise for one recorded clip. */
    data class ClipMetrics(
        val durationMs: Long,
        /** Milliseconds the VAD judged to be speech (all of it when no VAD ran). */
        val speechMs: Long,
        /** Loudness of the speech, dBFS (0 is the loudest the mic can record). */
        val speechDb: Float,
        /** The quiet floor between words, dBFS. */
        val noiseDb: Float,
        /** Share of samples at the mic's limit. */
        val clippedRatio: Float,
    ) {
        val snrDb: Float get() = speechDb - noiseDb
    }

    data class ClipResult(val index: Int, val metrics: ClipMetrics, val issues: List<Issue>, val similarity: Float?) {
        val ok: Boolean get() = issues.isEmpty()
    }

    data class Result(
        val verdict: Verdict,
        val clips: List<ClipResult>,
        /** Mean similarity between your clips (1.0 = identical). */
        val consistency: Float,
        /** The voiceprint to store: the normalised average of the good clips. Null when POOR. */
        val voiceprint: FloatArray?,
        /** Thresholds tuned to how consistent this enrolment was (`ambient.t_lo`, `ambient.t_hi`). */
        val tLo: Float,
        val tHi: Float,
        /** Plain advice for the user, most important first. */
        val advice: List<String>,
    )

    // ---------------------------------------------------------------------------------------

    /** Measures a clip. [speechMask] marks speech samples from the VAD; null treats all of it as speech. */
    fun measure(samples: FloatArray, sampleRate: Int = 16_000, speechMs: Long? = null): ClipMetrics {
        val frame = (sampleRate * 0.02).toInt().coerceAtLeast(1) // 20 ms frames
        val levels = ArrayList<Float>()
        var clipped = 0
        var i = 0
        while (i + frame <= samples.size) {
            var sum = 0.0
            for (j in i until i + frame) {
                val v = samples[j]
                sum += v * v
                if (v >= 0.985f || v <= -0.985f) clipped++
            }
            levels += sqrt(sum / frame).toFloat()
            i += frame
        }
        if (levels.isEmpty()) return ClipMetrics(0, 0, -120f, -120f, 0f)
        val sorted = levels.sorted()
        val noise = sorted[(sorted.size * 0.10).toInt().coerceIn(0, sorted.lastIndex)]
        val speech = sorted[(sorted.size * 0.90).toInt().coerceIn(0, sorted.lastIndex)]
        val durationMs = samples.size * 1000L / sampleRate
        return ClipMetrics(
            durationMs = durationMs,
            speechMs = speechMs ?: durationMs,
            speechDb = db(speech),
            noiseDb = db(noise),
            clippedRatio = clipped.toFloat() / samples.size.coerceAtLeast(1),
        )
    }

    /** Checks one clip on its own, before its embedding is compared with the others. */
    fun issuesOf(m: ClipMetrics): List<Issue> = buildList {
        if (m.speechMs < 2_000) add(Issue.TOO_SHORT)
        if (m.speechDb < -38f) add(Issue.TOO_QUIET)
        if (m.clippedRatio > 0.01f) add(Issue.CLIPPING)
        if (m.snrDb < 15f && m.speechDb >= -38f) add(Issue.NOISY)
    }

    /**
     * Combines the clips. [neuralModel] is false when no speaker model is installed and the
     * embeddings come from the signal-processing fallback: those can't separate voices well, so
     * the verdict is capped at OKAY and the advice says to install the model.
     */
    fun assess(metrics: List<ClipMetrics>, embeddings: List<FloatArray?>, neuralModel: Boolean): Result {
        require(metrics.size == embeddings.size)
        val normalized = embeddings.map { e -> e?.let { l2(it) } }
        val firstPass = metrics.mapIndexed { i, m -> i to issuesOf(m) }

        // Clips with good audio form the reference; each is then compared with the others
        // (leave-one-out, so a clip never vouches for itself).
        val goodIdx = firstPass.filter { (i, iss) -> iss.isEmpty() && normalized[i] != null }.map { it.first }
        val sims = HashMap<Int, Float>()
        for (i in normalized.indices) {
            val e = normalized[i] ?: continue
            val others = goodIdx.filter { it != i }.mapNotNull { normalized[it] }
            if (others.isNotEmpty()) sims[i] = dot(e, l2(mean(others)))
        }
        val goodSims = goodIdx.mapNotNull { sims[it] }
        val median = goodSims.sorted().let { if (it.isEmpty()) 0f else it[it.size / 2] }
        val outlierCut = if (neuralModel) max(0.45f, median - 0.15f) else median - 0.25f

        val clips = metrics.indices.map { i ->
            val iss = firstPass[i].second.toMutableList()
            val s = sims[i]
            if (iss.isEmpty() && s != null && goodIdx.size >= 3 && s < outlierCut) iss += Issue.NOT_LIKE_OTHERS
            ClipResult(i, metrics[i], iss, s)
        }

        val usable = clips.filter { it.ok }.mapNotNull { normalized[it.index] }
        val consistency = if (usable.size < 2) 0f else {
            var total = 0f; var n = 0
            for (a in usable.indices) for (b in a + 1 until usable.size) { total += dot(usable[a], usable[b]); n++ }
            total / n
        }

        var verdict = when {
            usable.size >= MIN_CLIPS && consistency >= (if (neuralModel) 0.62f else 0.85f) -> Verdict.GOOD
            usable.size >= 2 && consistency >= (if (neuralModel) 0.50f else 0.75f) -> Verdict.OKAY
            else -> Verdict.POOR
        }
        if (!neuralModel && verdict == Verdict.GOOD) verdict = Verdict.OKAY

        val print = if (verdict == Verdict.POOR || usable.isEmpty()) null else l2(mean(usable))
        val selfSims = clips.filter { it.ok }.mapNotNull { it.similarity }
        val minSelf = selfSims.minOrNull() ?: 0.6f
        val meanSelf = if (selfSims.isEmpty()) 0.75f else selfSims.average().toFloat()
        val tLo = (minSelf - 0.15f).coerceIn(0.45f, 0.70f)
        val tHi = (meanSelf - 0.05f).coerceIn(tLo + 0.10f, 0.90f)

        val advice = buildList {
            val counts = clips.flatMap { it.issues }.groupingBy { it }.eachCount()
            counts.entries.sortedByDescending { it.value }.forEach { (issue, n) ->
                add(if (n > 1) "${issue.message} ($n clips)" else issue.message)
            }
            if (!neuralModel) add("Install the speaker model (Settings → Models) so Mine can really tell voices apart.")
            if (usable.size < MIN_CLIPS) add("Record at least $MIN_CLIPS clean clips.")
            if (verdict == Verdict.OKAY && neuralModel && usable.size < RECOMMENDED_CLIPS) add("A couple more clips in a quiet room will make recognition steadier.")
        }
        return Result(verdict, clips, consistency, print, tLo, tHi, advice)
    }

    // ---- small vector helpers ---------------------------------------------------------------

    private fun db(rms: Float): Float = if (rms <= 1e-6f) -120f else (20.0 * log10(rms.toDouble())).toFloat()

    fun l2(v: FloatArray): FloatArray {
        var s = 0.0
        for (x in v) s += x * x
        val n = sqrt(s).toFloat()
        return if (n < 1e-9f) v.copyOf() else FloatArray(v.size) { v[it] / n }
    }

    private fun dot(a: FloatArray, b: FloatArray): Float {
        val n = minOf(a.size, b.size)
        var s = 0f
        for (i in 0 until n) s += a[i] * b[i]
        return s
    }

    private fun mean(vs: List<FloatArray>): FloatArray {
        val n = vs.minOf { it.size }
        val out = FloatArray(n)
        for (v in vs) for (i in 0 until n) out[i] += v[i]
        for (i in 0 until n) out[i] /= vs.size
        return out
    }
}
