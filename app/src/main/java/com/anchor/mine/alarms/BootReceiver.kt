// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine Author
// Part of Mine. See LICENSE in the repository root.

package com.anchor.mine.alarms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.i("MineBootReceiver", "Triggered by $action. Re-arming alarms...")
        // In Step 6, we'll fetch pending Reminders from Storage and call AndroidAlarmScheduler.
    }
}