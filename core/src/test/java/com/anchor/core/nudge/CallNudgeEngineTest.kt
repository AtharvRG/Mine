// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.nudge

import com.anchor.core.SharedFakeStorage
import com.anchor.core.models.ProactiveState
import com.anchor.core.time.FakeClock
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CallNudgeEngineTest {

    private lateinit var clock: FakeClock
    private lateinit var storage: SharedFakeStorage
    private lateinit var nudgeEngine: NudgeEngine
    private lateinit var engine: CallNudgeEngine

    private fun FakeClock.advance(ms: Long) {
        currentTime += ms
    }

    @Before
    fun setUp() {
        clock = FakeClock(1_000_000L)
        storage = SharedFakeStorage(clock)
        nudgeEngine = NudgeEngine(clock)
        engine = CallNudgeEngine(
            storage = storage,
            clock = clock,
            nudgeEngine = nudgeEngine,
            configProvider = { CallNudgeConfig(minDurationS = 45, delayS = 20, coalesceMin = 10, expireH = 3) }
        )
    }

    @Test
    fun testShortCalls_IgnoredWithoutNudge() = runBlocking {
        // Call of 30 seconds (< 45s min duration)
        val started = clock.now()
        val ended = started + 30_000L
        clock.advance(30_000L)

        val decision = engine.onCallEnded(startedAt = started, endedAt = ended, direction = "in")
        assertTrue("Short call must be dropped", decision is CallNudgeDecision.DropShortCall)

        val events = storage.getRecentCallEvents(10)
        assertEquals(1, events.size)
        assertEquals(CallNudgeState.IGNORED, events[0].state)
        assertEquals(30, events[0].durationS)
    }

    @Test
    fun testValidCall_SchedulesAfterDelay() = runBlocking {
        // Call of 120 seconds (>= 45s min duration)
        val started = clock.now()
        val ended = started + 120_000L
        clock.advance(120_000L)

        val decision = engine.onCallEnded(startedAt = started, endedAt = ended, direction = "out")
        assertTrue("Valid call must schedule after delay", decision is CallNudgeDecision.ScheduleAfterDelay)

        val sched = decision as CallNudgeDecision.ScheduleAfterDelay
        assertEquals(ended + 20_000L, sched.fireAt)

        val event = storage.getCallEvent(sched.eventId)
        assertNotNull(event)
        assertEquals(CallNudgeState.PENDING, event?.state)
    }

    @Test
    fun testCoalescing_RapidCallsMergeIntoOneCandidate() = runBlocking {
        // Call 1 ends at T
        val start1 = clock.now()
        val end1 = start1 + 60_000L
        clock.advance(60_000L)
        val d1 = engine.onCallEnded(startedAt = start1, endedAt = end1, direction = "in")
        assertTrue(d1 is CallNudgeDecision.ScheduleAfterDelay)
        val event1Id = (d1 as CallNudgeDecision.ScheduleAfterDelay).eventId

        // Call 2 starts 3 minutes later (within 10-minute coalesce window)
        clock.advance(3 * 60_000L)
        val start2 = clock.now()
        val end2 = start2 + 90_000L
        clock.advance(90_000L)
        val d2 = engine.onCallEnded(startedAt = start2, endedAt = end2, direction = "out")

        assertTrue("Subsequent call within coalesce window must merge", d2 is CallNudgeDecision.CoalesceWithExisting)
        val coalesce = d2 as CallNudgeDecision.CoalesceWithExisting
        assertEquals(event1Id, coalesce.existingId)
        assertEquals(end2 + 20_000L, coalesce.newFireAt)
    }

    @Test
    fun testExpiry_DropsStaleCandidateAfterExpireHours() = runBlocking {
        val started = clock.now()
        val ended = started + 180_000L
        clock.advance(180_000L)

        val decision = engine.onCallEnded(startedAt = started, endedAt = ended)
        val eventId = (decision as CallNudgeDecision.ScheduleAfterDelay).eventId

        // Advance 3 hours and 1 minute
        clock.advance((3 * 3600 + 60) * 1000L)

        val eval = engine.evaluateCandidate(eventId, ProactiveState())
        assertTrue("Candidate past expire_h must be dropped as expired", eval is CallNudgeDecision.DropExpired)

        val event = storage.getCallEvent(eventId)
        assertEquals(CallNudgeState.EXPIRED, event?.state)
    }

    @Test
    fun testVehicleHold_HoldsCallNudgeUntilDrivingEnds() = runBlocking {
        val started = clock.now()
        val ended = started + 60_000L
        clock.advance(60_000L)

        val decision = engine.onCallEnded(startedAt = started, endedAt = ended)
        val eventId = (decision as CallNudgeDecision.ScheduleAfterDelay).eventId

        // Delay passes, but user is driving
        clock.advance(20_000L)
        val evalHeld = engine.evaluateCandidate(eventId, ProactiveState(), isVehicle = true)
        assertTrue(evalHeld is CallNudgeDecision.Held)
        assertEquals("vehicle_hold", (evalHeld as CallNudgeDecision.Held).reason)

        // Still pending
        assertEquals(CallNudgeState.PENDING, storage.getCallEvent(eventId)?.state)

        // Driving ends
        val evalPass = engine.evaluateCandidate(eventId, ProactiveState(), isVehicle = false)
        assertTrue(evalPass is CallNudgeDecision.ReadyToNudge)
    }

    @Test
    fun testLearningStop_ThreeConsecutiveIgnoresTriggersPreferencePrompt() = runBlocking {
        assertFalse(engine.shouldPromptPreference())

        // 1st ignore
        clock.advance(10_000L)
        engine.recordCallTerminalOutcome("call1", CallNudgeState.IGNORED)
        assertFalse(engine.shouldPromptPreference())

        // 2nd ignore
        clock.advance(10_000L)
        engine.recordCallTerminalOutcome("call2", CallNudgeState.IGNORED)
        assertFalse(engine.shouldPromptPreference())

        // 3rd ignore in a row
        clock.advance(10_000L)
        engine.recordCallTerminalOutcome("call3", CallNudgeState.IGNORED)
        assertTrue("3 consecutive ignored call nudges must trigger preference prompt", engine.shouldPromptPreference())

        // Answered call resets streak
        clock.advance(10_000L)
        engine.recordCallTerminalOutcome("call4", CallNudgeState.ANSWERED)
        assertFalse("Answered call must reset ignore streak", engine.shouldPromptPreference())

        // Skipping does not count as ignored (no guilt)
        clock.advance(10_000L)
        engine.recordCallTerminalOutcome("call5", CallNudgeState.IGNORED)
        clock.advance(10_000L)
        engine.recordCallTerminalOutcome("call6", CallNudgeState.IGNORED)
        clock.advance(10_000L)
        engine.recordCallTerminalOutcome("call7", CallNudgeState.SKIPPED)
        assertFalse("Skipped call must not count as ignore", engine.shouldPromptPreference())
    }

    @Test
    fun testEveningRecap_CountsCallsNotHeardAbout() = runBlocking {
        val dayStart = clock.now()
        val dayEnd = dayStart + 24 * 3600 * 1000L

        // Call 1: answered
        storage.saveCallEvent(CallEvent("c1", dayStart + 1000, dayStart + 60_000, 59, state = CallNudgeState.ANSWERED))
        // Call 2: expired/ignored
        storage.saveCallEvent(CallEvent("c2", dayStart + 70_000, dayStart + 150_000, 80, state = CallNudgeState.EXPIRED))
        // Call 3: skipped
        storage.saveCallEvent(CallEvent("c3", dayStart + 200_000, dayStart + 300_000, 100, state = CallNudgeState.SKIPPED))
        // Call 4: short call (< 45s) -> should not count
        storage.saveCallEvent(CallEvent("c4", dayStart + 400_000, dayStart + 420_000, 20, state = CallNudgeState.IGNORED))

        val unanswered = engine.getUnansweredCallsCount(dayStart, dayEnd)
        // c2 (expired) and c3 (skipped) are >= 45s and were NOT answered
        assertEquals(2, unanswered)
    }
}
