// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.mine.alarms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.anchor.mine.MineApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val validActions = listOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED)
        if (action !in validActions) return

        // Fix: goAsync() throws IllegalStateException in manual instrumented tests
        val pendingResult = try { goAsync() } catch (e: Exception) { null }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val storage = (context.applicationContext as MineApp).sharedStorage.await()
                val scheduler = AndroidAlarmScheduler(context)

                val now = System.currentTimeMillis()
                val reminders = storage.getPendingReminders()

                reminders.forEach {
                    if (it.fireAtMillis <= now) {
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
                pendingResult?.finish()
            }
        }
    }
}