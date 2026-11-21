// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.mine.lifecycle

import android.app.Activity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AppVisibilityTrackerTest {

    private class TestActivity : Activity()

    @Before
    fun setUp() {
        AppVisibilityTracker.resetForTesting(isBackground = true)
    }

    @Test
    fun testInitialState_IsBackground() {
        assertTrue(AppVisibilityTracker.isAppInBackground)
        assertFalse(AppVisibilityTracker.isAppInForeground)
    }

    @Test
    fun testActivityStarted_TransitionsToForeground() {
        val act = TestActivity()
        AppVisibilityTracker.onActivityStarted(act)

        assertFalse(AppVisibilityTracker.isAppInBackground)
        assertTrue(AppVisibilityTracker.isAppInForeground)
    }

    @Test
    fun testMultipleActivities_MaintainsForegroundUntilAllStop() {
        val act1 = TestActivity()
        val act2 = TestActivity()

        AppVisibilityTracker.onActivityStarted(act1)
        AppVisibilityTracker.onActivityStarted(act2)
        assertTrue(AppVisibilityTracker.isAppInForeground)

        AppVisibilityTracker.onActivityStopped(act1)
        assertTrue("Should still be foreground while act2 is started", AppVisibilityTracker.isAppInForeground)

        AppVisibilityTracker.onActivityStopped(act2)
        assertTrue("Should transition to background when all activities stop", AppVisibilityTracker.isAppInBackground)
        assertFalse(AppVisibilityTracker.isAppInForeground)
    }

    @Test
    fun testUnderflowGuard_StaysAtZero() {
        val act1 = TestActivity()
        AppVisibilityTracker.onActivityStopped(act1) // Stopped without started
        assertTrue(AppVisibilityTracker.isAppInBackground)

        AppVisibilityTracker.onActivityStarted(act1)
        assertTrue(AppVisibilityTracker.isAppInForeground)
    }
}
