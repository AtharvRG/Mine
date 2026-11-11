// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.learning

import com.anchor.core.models.LearnedPref
import com.anchor.core.models.SignalLog
import com.anchor.core.storage.Storage
import com.anchor.core.time.Clock
import com.anchor.core.time.SystemClock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.pow

@Serializable
data class BetaDistributionPref(
    val alpha: Double,
    val beta: Double,
    val mean: Double,
    val rawSamples: Int
)

@Serializable
data class DurationEmaPref(
    val meanValue: Double,
    val rawSamples: Int
)

/**
 * Core behavioral learning engine implementing Blueprint 25.
 *
 * Guarantees:
 * 1. Visible, explainable, bounded and resettable (Blueprint 25 §1).
 * 2. Plain statistics in code, zero LLM calls (Blueprint 25 §1 & §3.2).
 * 3. Exponential decay with 60-day half-life (Blueprint 25 §3.2).
 * 4. Beta distributions for binary outcomes (reply vs ignore) with minimum samples (default 8).
 * 5. Exploration rate (default 10%) to prevent echo chambers and local minima.
 * 6. Locked preferences survive recompute and reset (Blueprint 25 §2).
 */
class LearningEngine(
    private val storage: Storage,
    private val clock: Clock = SystemClock(),
    private val minSamples: Int = DEFAULT_MIN_SAMPLES,
    private val halfLifeMs: Long = DEFAULT_HALF_LIFE_MS,
    private val explorationRate: Double = DEFAULT_EXPLORATION_RATE,
    private val json: Json = Json { ignoreUnknownKeys = true }
) {

    suspend fun recordSignal(
        kind: String,
        subject: String? = null,
        value: Double? = null,
        attrs: String? = null,
        now: Long = clock.now()
    ): SignalLog {
        val signal = SignalLog(
            id = "sig_${UUID.randomUUID().toString().take(12)}",
            tsUtc = now,
            kind = kind,
            subject = subject,
            value = value,
            attrs = attrs
        )
        storage.recordSignal(signal)
        return signal
    }

    suspend fun recomputePreferences(now: Long = clock.now()) {
        val resetEpoch = storage.getSetting(SETTING_RESET_EPOCH)?.toLongOrNull() ?: 0L
        val signals = storage.getSignals(sinceUtc = resetEpoch, limit = 5000)
        if (signals.isEmpty()) return

        // 1. Group nudge responses (nudge_reply vs nudge_ignored)
        val nudgeSignals = signals.filter { it.kind == SIGNAL_NUDGE_REPLY || it.kind == SIGNAL_NUDGE_IGNORED }
        val bySubject = nudgeSignals.groupBy { it.subject ?: "default" }

        for ((subject, group) in bySubject) {
            val scope = "trigger=$subject"
            val existing = storage.getLearnedPref(KEY_NUDGE_REPLY_RATE, scope)
            if (existing?.locked == true) continue

            var alpha = 1.0 // prior
            var beta = 1.0  // prior
            var rawSamples = 0
            var effectiveWeightSum = 0.0

            for (sig in group) {
                rawSamples++
                val deltaMs = (now - sig.tsUtc).coerceAtLeast(0L)
                val weight = computeDecayWeight(deltaMs, halfLifeMs)
                effectiveWeightSum += weight
                if (sig.kind == SIGNAL_NUDGE_REPLY) {
                    alpha += weight
                } else {
                    beta += weight
                }
            }

            val mean = alpha / (alpha + beta)
            val confidence = if (rawSamples < minSamples) {
                0.0
            } else {
                min(1.0, (effectiveWeightSum / 20.0).coerceIn(0.0, 1.0))
            }

            val valueObj = BetaDistributionPref(
                alpha = alpha,
                beta = beta,
                mean = mean,
                rawSamples = rawSamples
            )

            val pref = LearnedPref(
                key = KEY_NUDGE_REPLY_RATE,
                scope = scope,
                valueJson = json.encodeToString(valueObj),
                samples = rawSamples,
                confidence = confidence,
                locked = false,
                updatedAt = now
            )
            storage.saveLearnedPref(pref)
        }

        // 2. Group duration signals (snooze, latency)
        val durationSignals = signals.filter { (it.kind == SIGNAL_SNOOZE || it.kind == SIGNAL_LATENCY) && it.value != null }
        val durationByKind = durationSignals.groupBy { it.kind }

        for ((kind, group) in durationByKind) {
            val key = if (kind == SIGNAL_SNOOZE) KEY_SNOOZE_HABIT else KEY_RESPONSE_LATENCY
            val scope = "global"
            val existing = storage.getLearnedPref(key, scope)
            if (existing?.locked == true) continue

            var weightedSum = 0.0
            var weightSum = 0.0
            var rawSamples = 0

            for (sig in group) {
                val v = sig.value ?: continue
                rawSamples++
                val deltaMs = (now - sig.tsUtc).coerceAtLeast(0L)
                val weight = computeDecayWeight(deltaMs, halfLifeMs)
                weightedSum += weight * v
                weightSum += weight
            }

            if (weightSum > 0.0) {
                val meanVal = weightedSum / weightSum
                val confidence = if (rawSamples < minSamples) 0.0 else min(1.0, weightSum / 20.0)
                val valueObj = DurationEmaPref(meanValue = meanVal, rawSamples = rawSamples)
                val pref = LearnedPref(
                    key = key,
                    scope = scope,
                    valueJson = json.encodeToString(valueObj),
                    samples = rawSamples,
                    confidence = confidence,
                    locked = false,
                    updatedAt = now
                )
                storage.saveLearnedPref(pref)
            }
        }
    }

    suspend fun getReplyRate(triggerType: String, defaultRate: Double = 0.5): Double {
        val pref = storage.getLearnedPref(KEY_NUDGE_REPLY_RATE, "trigger=$triggerType") ?: return defaultRate
        if (pref.samples < minSamples || pref.confidence < 0.1) return defaultRate
        return try {
            val dist = json.decodeFromString<BetaDistributionPref>(pref.valueJson)
            dist.mean
        } catch (_: Exception) {
            defaultRate
        }
    }

    suspend fun lockPreference(key: String, scope: String, locked: Boolean = true): Boolean {
        val existing = storage.getLearnedPref(key, scope) ?: return false
        val updated = existing.copy(locked = locked, updatedAt = clock.now())
        storage.saveLearnedPref(updated)
        return true
    }

    suspend fun resetLearning(preserveLocked: Boolean = true, now: Long = clock.now()) {
        storage.saveSetting(SETTING_RESET_EPOCH, now.toString())
        storage.resetLearning(preserveLocked = preserveLocked)
    }

    fun shouldExplore(customRate: Double = explorationRate, randomDouble: Double = Math.random()): Boolean {
        return randomDouble < customRate
    }

    fun explainWhy(
        triggerType: String,
        reason: String,
        replyRatePref: LearnedPref? = null
    ): String {
        val baseExplanation = "I asked because: $reason."
        if (replyRatePref == null || replyRatePref.samples < minSamples) {
            return baseExplanation
        }
        return try {
            val dist = json.decodeFromString<BetaDistributionPref>(replyRatePref.valueJson)
            val ratePercent = (dist.mean * 100).toInt()
            if (dist.mean >= 0.6) {
                "$baseExplanation You usually respond to nudges about $triggerType (~$ratePercent% response rate)."
            } else if (dist.mean <= 0.4) {
                "$baseExplanation You tend to ignore nudges about $triggerType (~$ratePercent% response rate), but this item is high priority."
            } else {
                baseExplanation
            }
        } catch (_: Exception) {
            baseExplanation
        }
    }

    fun explainPreference(pref: LearnedPref): String {
        val confPercent = (pref.confidence * 100).toInt()
        return when (pref.key) {
            KEY_NUDGE_REPLY_RATE -> {
                val subject = pref.scope.removePrefix("trigger=")
                try {
                    val dist = json.decodeFromString<BetaDistributionPref>(pref.valueJson)
                    val ratePercent = (dist.mean * 100).toInt()
                    if (pref.samples < minSamples) {
                        "Not enough data yet for '$subject' ($pref.samples/$minSamples observations)."
                    } else if (dist.mean >= 0.6) {
                        "You usually respond to nudges about $subject (~$ratePercent% response rate, confidence $confPercent%)."
                    } else if (dist.mean <= 0.4) {
                        "You tend to ignore nudges about $subject (~$ratePercent% response rate, confidence $confPercent%)."
                    } else {
                        "Mixed engagement with $subject nudges (~$ratePercent% response rate, confidence $confPercent%)."
                    }
                } catch (_: Exception) {
                    "Learned preference for $subject (confidence $confPercent%)."
                }
            }
            KEY_SNOOZE_HABIT -> {
                try {
                    val dur = json.decodeFromString<DurationEmaPref>(pref.valueJson)
                    val mins = (dur.meanValue / 60000.0).toInt()
                    "Your typical snooze duration is about $mins minutes."
                } catch (_: Exception) {
                    "Learned snooze habit."
                }
            }
            KEY_RESPONSE_LATENCY -> {
                try {
                    val dur = json.decodeFromString<DurationEmaPref>(pref.valueJson)
                    val mins = (dur.meanValue / 60000.0).toInt()
                    "Your typical response latency is about $mins minutes."
                } catch (_: Exception) {
                    "Learned response latency."
                }
            }
            else -> "Learned pattern for ${pref.key} [${pref.scope}] (confidence $confPercent%)."
        }
    }

    companion object {
        const val KEY_NUDGE_REPLY_RATE = "nudge.reply_rate"
        const val KEY_SNOOZE_HABIT = "snooze.duration"
        const val KEY_RESPONSE_LATENCY = "response.latency"
        const val SETTING_RESET_EPOCH = "learning_reset_epoch"

        const val SIGNAL_NUDGE_REPLY = "nudge_reply"
        const val SIGNAL_NUDGE_IGNORED = "nudge_ignored"
        const val SIGNAL_SNOOZE = "snooze"
        const val SIGNAL_LATENCY = "latency"
        const val SIGNAL_UNDO_AFTER_COMMIT = "undo_after_commit"

        const val DEFAULT_MIN_SAMPLES = 8
        const val DEFAULT_HALF_LIFE_MS = 60L * 24 * 3600 * 1000L // 60 days
        const val DEFAULT_EXPLORATION_RATE = 0.10

        fun computeDecayWeight(deltaMs: Long, halfLifeMs: Long): Double {
            if (halfLifeMs <= 0L) return 1.0
            val periods = deltaMs.toDouble() / halfLifeMs.toDouble()
            return 2.0.pow(-periods)
        }
    }
}
