// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.nudge

import com.anchor.core.models.GateResult
import com.anchor.core.models.NudgeCandidate
import com.anchor.core.models.ProactiveState
import com.anchor.core.time.FakeClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NudgeEngineTest {

    // E-07: Nudges that ignore, back off and break through
    @Test
    fun testNudgesIgnoreBackoffAndBreakThrough() {
        var state = ProactiveState()
        val engine = NudgeEngine(FakeClock(1000))
        
        // 1. Thread opens
        state = engine.onNudgeSent(state, "msg1", 1000)
        assertEquals("msg1", state.openThreadMsgId)
        
        // 2. 3 hours pass, ignored
        state = engine.processIgnore(state, 1000 + (3 * 3600 * 1000L))
        assertNull(state.openThreadMsgId)
        assertEquals(1, state.backoffStep)
        
        // 3. Next gap is 60 * 2^1 = 120 mins
        assertEquals(120L, engine.calculateGapMinutes(state))
        
        // 4. Ignored again -> step 2 -> 240 mins
        state = engine.onNudgeSent(state, "msg2", 20000000)
        state = engine.processIgnore(state, 20000000 + (3 * 3600 * 1000L))
        assertEquals(2, state.backoffStep)
        assertEquals(240L, engine.calculateGapMinutes(state))
        
        // 5. Critical item breaks through even if open thread or min gap holds
        val crit = NudgeCandidate("deadline", isCritical = true)
        val heldState = state.copy(openThreadMsgId = "msg3", lastSentAt = 20000000 + (3 * 3600 * 1000L))
        val res = engine.evaluate(crit, heldState, isUserPresent = false, isInDayWindow = true)
        assertTrue("Critical item must bypass open thread and backoff", res is GateResult.Pass)
        
        // 6. But NOT fuse
        val trippedState = heldState.copy(sentToday = 40)
        val resFuse = engine.evaluate(crit, trippedState, isUserPresent = false, isInDayWindow = true)
        assertTrue("Fuse must block even critical items", resFuse is GateResult.Held)
        assertEquals("fuse_tripped", (resFuse as GateResult.Held).reason)
        
        // 7. Reply resets backoff
        state = engine.processReply(state, 90000000)
        assertEquals(0, state.backoffStep)
    }
}
