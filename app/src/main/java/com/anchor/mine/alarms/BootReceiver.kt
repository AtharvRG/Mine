// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.mine.alarms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.anchor.mine.storage.AndroidStorage
import com.anchor.mine.storage.DatabaseProvider
import com.anchor.mine.storage.DbKeyProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return

        val validActions = listOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED, // FIXED: Matches "android.intent.action.TIME_SET"
            Intent.ACTION_TIMEZONE_CHANGED
        )
        if (action !in validActions) return

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val key = DbKeyProvider(context).getOrGenerateKey()
                val db = DatabaseProvider.getHelper(context, key)
                val storage = AndroidStorage(db)
                val scheduler = AndroidAlarmScheduler(context)

                val now = System.currentTimeMillis()
                val reminders = storage.getActiveReminders()

                reminders.forEach {
                    if (it.fireAtMillis <= now) {
                        // Missed while offline/off. Fire immediately.
                        val missedIntent = Intent(context, AlarmReceiver::class.java).apply {
                            putExtra("EXTRA_ID", it.id)
                            putExtra("EXTRA_TEXT", "Missed: ${it.text}")
                        }
                        context.sendBroadcast(missedIntent)
                    } else {
                        scheduler.schedule(it)
                    }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}