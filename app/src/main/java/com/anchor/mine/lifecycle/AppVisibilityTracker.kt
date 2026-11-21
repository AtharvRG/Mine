// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.mine.lifecycle

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tracks whether any activity belonging to Mine is currently started (visible to the user).
 * Used by D-206 to determine whether background reply notifications should be posted.
 */
object AppVisibilityTracker : Application.ActivityLifecycleCallbacks {
    private val startedActivityCount = AtomicInteger(0)

    val isAppInBackground: Boolean
        get() = startedActivityCount.get() <= 0

    val isAppInForeground: Boolean
        get() = startedActivityCount.get() > 0

    override fun onActivityStarted(activity: Activity) {
        startedActivityCount.incrementAndGet()
    }

    override fun onActivityStopped(activity: Activity) {
        startedActivityCount.updateAndGet { count -> if (count > 0) count - 1 else 0 }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityResumed(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}

    fun resetForTesting(isBackground: Boolean = true) {
        startedActivityCount.set(if (isBackground) 0 else 1)
    }
}
