// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.mine.alarms

import org.junit.Assert.assertEquals
import org.junit.Test

class AgentWakeupAlarmTest {

    @Test
    fun testActionConstant_MatchesExpected() {
        assertEquals("com.anchor.mine.ACTION_AGENT_WAKEUP", AlarmReceiver.ACTION_AGENT_WAKEUP)
    }
}
