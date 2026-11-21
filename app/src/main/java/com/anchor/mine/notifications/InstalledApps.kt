// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.mine.notifications

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/**
 * Every app with a launcher icon, for the notification-sources screen, so the user can choose
 * what Mine may read before an app ever sends anything. Needs the MAIN/LAUNCHER `<queries>`
 * entry in the manifest (package visibility, Android 11+). Labels only; nothing is stored.
 */
object InstalledApps {
    fun launchable(context: Context): Map<String, String> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val infos = pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        return infos
            .filter { it.activityInfo.packageName != context.packageName }
            .associate { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .toList().sortedBy { it.second.lowercase() }.toMap()
    }
}
