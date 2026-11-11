// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.learning

import com.anchor.core.SharedFakeStorage
import com.anchor.core.time.FakeClock
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LearningEngineTest {

    private lateinit var storage: SharedFakeStorage
    private lateinit var clock: FakeClock
    private lateinit var engine: LearningEngine

    @Before
    fun setUp() {
        storage = SharedFakeStorage()
        clock = FakeClock(1700000000000L)
        engine = LearningEngine(storage = storage, clock = clock)
    }

    @Test
    fun testRecordSignal_PersistsSignalLog() = runBlocking {
        val signal = engine.recordSignal(
            kind = LearningEngine.SIGNAL_NUDGE_REPLY,
            subject = "deadline",
            value = 1.0,
            attrs = "{\"source\":\"notification\"}"
        )

        assertNotNull(signal.id)
        assertTrue(signal.id.startsWith("sig_"))
        assertEquals(clock.currentTime, signal.tsUtc)
        assertEquals("deadline", signal.subject)

        val retrieved = storage.getSignals(kind = LearningEngine.SIGNAL_NUDGE_REPLY)
        assertEquals(1, retrieved.size)
        assertEquals(signal.id, retrieved[0].id)
        assertEquals(signal.attrs, retrieved[0].attrs)
    }

    @Test
    fun testUnderMinSamples_ReturnsDefaultReplyRate() = runBlocking {
        // Record only 4 reply signals (< 8 min samples)
        repeat(4) {
            engine.recordSignal(
                kind = LearningEngine.SIGNAL_NUDGE_REPLY,
                subject = "urgent"
            )
        }

        engine.recomputePreferences()

        val pref = storage.getLearnedPref(LearningEngine.KEY_NUDGE_REPLY_RATE, "trigger=urgent")
        assertNotNull(pref)
        assertEquals(4, pref!!.samples)
        assertEquals(0.0, pref.confidence, 0.001) // Confidence is 0.0 below min samples

        val rate = engine.getReplyRate("urgent", defaultRate = 0.5)
        assertEquals(0.5, rate, 0.001) // Fallback to default
    }

    @Test
    fun testOverMinSamples_ComputesBetaDistributionReplyRate() = runBlocking {
        // Record 9 replies and 1 ignore (total 10 >= 8 min samples)
        repeat(9) {
            engine.recordSignal(
                kind = LearningEngine.SIGNAL_NUDGE_REPLY,
                subject = "health"
            )
        }
        engine.recordSignal(
            kind = LearningEngine.SIGNAL_NUDGE_IGNORED,
            subject = "health"
        )

        engine.recomputePreferences()

        val pref = storage.getLearnedPref(LearningEngine.KEY_NUDGE_REPLY_RATE, "trigger=health")
        assertNotNull(pref)
        assertEquals(10, pref!!.samples)
        assertTrue(pref.confidence > 0.0)

        val rate = engine.getReplyRate("health", defaultRate = 0.5)
        // With uniform prior (alpha=1, beta=1) + 9 replies + 1 ignore => alpha=10, beta=2 => 10/12 ≈ 0.833
        assertTrue("Rate should be high: $rate", rate > 0.75)

        val explanation = engine.explainPreference(pref)
        assertTrue(explanation.contains("You usually respond"))
        assertTrue(explanation.contains("health"))
    }

    @Test
    fun testAllIgnored_ComputesLowEngagementRate() = runBlocking {
        repeat(10) {
            engine.recordSignal(
                kind = LearningEngine.SIGNAL_NUDGE_IGNORED,
                subject = "trivia"
            )
        }

        engine.recomputePreferences()

        val rate = engine.getReplyRate("trivia", defaultRate = 0.5)
        // Prior: alpha=1, beta=1. 10 ignores => alpha=1, beta=11 => 1/12 ≈ 0.083
        assertTrue("Rate should be low: $rate", rate < 0.20)

        val pref = storage.getLearnedPref(LearningEngine.KEY_NUDGE_REPLY_RATE, "trigger=trivia")!!
        val explanation = engine.explainPreference(pref)
        assertTrue(explanation.contains("You tend to ignore"))
    }

    @Test
    fun testDecayWeight_ReducesInfluenceOfOldSignals() {
        val halfLife = 60L * 24 * 3600 * 1000L
        val weightNow = LearningEngine.computeDecayWeight(0L, halfLife)
        assertEquals(1.0, weightNow, 0.0001)

        val weightOneHalfLife = LearningEngine.computeDecayWeight(halfLife, halfLife)
        assertEquals(0.5, weightOneHalfLife, 0.0001)

        val weightTwoHalfLives = LearningEngine.computeDecayWeight(2 * halfLife, halfLife)
        assertEquals(0.25, weightTwoHalfLives, 0.0001)
    }

    @Test
    fun testLockedPreference_IsNotOverwrittenByRecompute() = runBlocking {
        repeat(10) {
            engine.recordSignal(
                kind = LearningEngine.SIGNAL_NUDGE_REPLY,
                subject = "work"
            )
        }
        engine.recomputePreferences()

        val locked = engine.lockPreference(LearningEngine.KEY_NUDGE_REPLY_RATE, "trigger=work", locked = true)
        assertTrue(locked)

        val originalPref = storage.getLearnedPref(LearningEngine.KEY_NUDGE_REPLY_RATE, "trigger=work")!!
        assertTrue(originalPref.locked)

        // Now record 20 ignores that would normally tank the rate
        repeat(20) {
            engine.recordSignal(
                kind = LearningEngine.SIGNAL_NUDGE_IGNORED,
                subject = "work"
            )
        }

        engine.recomputePreferences()

        val currentPref = storage.getLearnedPref(LearningEngine.KEY_NUDGE_REPLY_RATE, "trigger=work")!!
        assertEquals(originalPref.valueJson, currentPref.valueJson)
        assertEquals(originalPref.samples, currentPref.samples)
    }

    @Test
    fun testResetLearning_ClearsUnlockedAndPreservesLocked() = runBlocking {
        repeat(10) {
            engine.recordSignal(LearningEngine.SIGNAL_NUDGE_REPLY, subject = "unlocked_topic")
            engine.recordSignal(LearningEngine.SIGNAL_NUDGE_REPLY, subject = "locked_topic")
        }
        engine.recomputePreferences()

        engine.lockPreference(LearningEngine.KEY_NUDGE_REPLY_RATE, "trigger=locked_topic", locked = true)

        engine.resetLearning(preserveLocked = true)

        assertNull(storage.getLearnedPref(LearningEngine.KEY_NUDGE_REPLY_RATE, "trigger=unlocked_topic"))
        assertNotNull(storage.getLearnedPref(LearningEngine.KEY_NUDGE_REPLY_RATE, "trigger=locked_topic"))

        // Reset completely
        engine.resetLearning(preserveLocked = false)
        assertNull(storage.getLearnedPref(LearningEngine.KEY_NUDGE_REPLY_RATE, "trigger=locked_topic"))
    }

    @Test
    fun testShouldExplore_HonorsExplorationRate() {
        assertTrue(engine.shouldExplore(customRate = 0.10, randomDouble = 0.05))
        assertFalse(engine.shouldExplore(customRate = 0.10, randomDouble = 0.15))
        assertTrue(engine.shouldExplore(customRate = 1.0, randomDouble = 0.99))
        assertFalse(engine.shouldExplore(customRate = 0.0, randomDouble = 0.01))
    }

    @Test
    fun testDurationEma_LearnsSnoozeHabit() = runBlocking {
        // Record 8 snooze durations of 10 minutes (600,000 ms)
        repeat(8) {
            engine.recordSignal(
                kind = LearningEngine.SIGNAL_SNOOZE,
                value = 600000.0
            )
        }

        engine.recomputePreferences()

        val pref = storage.getLearnedPref(LearningEngine.KEY_SNOOZE_HABIT, "global")
        assertNotNull(pref)
        assertEquals(8, pref!!.samples)

        val explanation = engine.explainPreference(pref)
        assertTrue(explanation.contains("typical snooze duration is about 10 minutes"))
    }

    @Test
    fun testRecordSignal_NullValue_PreservedAsNull() = runBlocking {
        val signal = engine.recordSignal(
            kind = LearningEngine.SIGNAL_NUDGE_IGNORED,
            subject = "chores",
            value = null
        )
        assertNull(signal.value)

        val retrieved = storage.getSignals(kind = LearningEngine.SIGNAL_NUDGE_IGNORED)
        assertEquals(1, retrieved.size)
        assertNull(retrieved[0].value)
    }

    @Test
    fun testResetLearning_PreventsImmediateResurrection() = runBlocking {
        // Record 10 signals and compute
        repeat(10) {
            engine.recordSignal(LearningEngine.SIGNAL_NUDGE_REPLY, subject = "topic_a")
        }
        engine.recomputePreferences()
        assertNotNull(storage.getLearnedPref(LearningEngine.KEY_NUDGE_REPLY_RATE, "trigger=topic_a"))

        // Reset learning
        clock.currentTime += 1000L
        engine.resetLearning(preserveLocked = false)
        assertNull(storage.getLearnedPref(LearningEngine.KEY_NUDGE_REPLY_RATE, "trigger=topic_a"))

        // Recompute without fresh signals -> must NOT resurrect old signals!
        engine.recomputePreferences()
        assertNull(storage.getLearnedPref(LearningEngine.KEY_NUDGE_REPLY_RATE, "trigger=topic_a"))

        // Record fresh signal after reset -> computes cleanly
        clock.currentTime += 1000L
        repeat(8) {
            engine.recordSignal(LearningEngine.SIGNAL_NUDGE_REPLY, subject = "topic_b")
        }
        engine.recomputePreferences()
        assertNotNull(storage.getLearnedPref(LearningEngine.KEY_NUDGE_REPLY_RATE, "trigger=topic_b"))
    }

    @Test
    fun testClockSkew_FutureTimestamp_ClampedToOne() {
        val halfLife = 60L * 24 * 3600 * 1000L
        // Future timestamp: deltaMs is negative (-100,000ms), which clamped to 0 yields weight 1.0
        val weightFuture = LearningEngine.computeDecayWeight(deltaMs = -100000L.coerceAtLeast(0L), halfLifeMs = halfLife)
        assertEquals(1.0, weightFuture, 0.0001)
    }

    @Test
    fun testExplainWhy_FormatsExplanation() = runBlocking {
        repeat(9) {
            engine.recordSignal(LearningEngine.SIGNAL_NUDGE_REPLY, subject = "workout")
        }
        engine.recordSignal(LearningEngine.SIGNAL_NUDGE_IGNORED, subject = "workout")
        engine.recomputePreferences()

        val pref = storage.getLearnedPref(LearningEngine.KEY_NUDGE_REPLY_RATE, "trigger=workout")!!
        val explanation = engine.explainWhy("workout", "workout is scheduled for 6pm", pref)

        assertTrue(explanation.startsWith("I asked because: workout is scheduled for 6pm."))
        assertTrue(explanation.contains("You usually respond to nudges about workout"))
    }
}
