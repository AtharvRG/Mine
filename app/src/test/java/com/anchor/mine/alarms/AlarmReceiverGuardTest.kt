// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.mine.alarms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmReceiverGuardTest {

    @Test
    fun testShouldSuppressForTerminalOrPausedStatuses() {
        assertTrue(AlarmReceiver.shouldSuppressForNodeStatus("done"))
        assertTrue(AlarmReceiver.shouldSuppressForNodeStatus("cancelled"))
        assertTrue(AlarmReceiver.shouldSuppressForNodeStatus("paused"))
        assertTrue(AlarmReceiver.shouldSuppressForNodeStatus("archived"))

        assertFalse(AlarmReceiver.shouldSuppressForNodeStatus("in_progress"))
        assertFalse(AlarmReceiver.shouldSuppressForNodeStatus("pending"))
        assertFalse(AlarmReceiver.shouldSuppressForNodeStatus(null))
        assertFalse(AlarmReceiver.shouldSuppressForNodeStatus("active"))
    }

    @Test
    fun testTransformTextForInProgress() {
        assertEquals("Check: write report", AlarmReceiver.transformTextForInProgress("start write report"))
        assertEquals("Check: write report", AlarmReceiver.transformTextForInProgress("Start: write report"))
        assertEquals("Check: finish proposal", AlarmReceiver.transformTextForInProgress("START   finish proposal"))
        assertEquals("Check: buy groceries", AlarmReceiver.transformTextForInProgress("buy groceries"))
        assertEquals("Check: review code", AlarmReceiver.transformTextForInProgress("Check: review code"))
    }
}
