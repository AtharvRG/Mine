// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.mine.alarms

import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ManifestPermissionTest {

    @Test
    fun testManifest_DoesNotDeclareForbiddenPermissions() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val packageInfo = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        }

        val requestedPermissions = packageInfo.requestedPermissions?.toSet() ?: emptySet()

        assertFalse(
            "Manifest must NOT declare SCHEDULE_EXACT_ALARM (Decision D-102)",
            requestedPermissions.contains("android.permission.SCHEDULE_EXACT_ALARM")
        )
        org.junit.Assert.assertTrue(
            "Manifest must declare USE_EXACT_ALARM for setAlarmClock (Decision D-102b: setAlarmClock throws SecurityException on API 34 without it)",
            requestedPermissions.contains("android.permission.USE_EXACT_ALARM")
        )
        assertFalse(
            "Manifest must NOT declare VIBRATE (Zero Vibration Protocol)",
            requestedPermissions.contains("android.permission.VIBRATE")
        )
        assertFalse(
            "Manifest must NOT declare WAKE_LOCK (Decision D-102)",
            requestedPermissions.contains("android.permission.WAKE_LOCK")
        )
    }
}
