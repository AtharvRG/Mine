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
                
                // 1. Reconcile alarms: Cancel orphans from registry, and re-arm future reminders
                // AND reminders within the 60-second grace period.
                // Registry is a hint; force-stop / reboot clears OS alarms.
                val now = System.currentTimeMillis()
                val reminders = storage.getPendingReminders()
                val registered = storage.getRegisteredAlarms()
                val pendingIds = reminders.map { it.id }.toSet()

                registered.forEach { (id, _) ->
                    if (id !in pendingIds) {
                        scheduler.cancel(id)
                        storage.deleteRegisteredAlarm(id)
                    }
                }

                reminders.filter { it.fireAtMillis >= now - 60000L }.forEach {
                    scheduler.schedule(it)
                    storage.saveRegisteredAlarm(it.id, it.id.hashCode(), it.fireAtMillis)
                }

                // 2. Run Sweeper for alarms missed while the device was off (older than 60s)
                val clock = com.anchor.core.time.SystemClock()
                val reliabilityManager = com.anchor.core.reliability.ReliabilityManager(storage, clock)
                
                reliabilityManager.runSweeper().forEach { missedRem ->
                    val missedIntent = Intent(context, AlarmReceiver::class.java).apply {
                        putExtra("EXTRA_ID", missedRem.id)
                        putExtra("EXTRA_TEXT", "Missed: ${missedRem.text}")
                    }
                    context.sendBroadcast(missedIntent)
                }
            } finally {
                pendingResult?.finish()
            }
        }
    }
}