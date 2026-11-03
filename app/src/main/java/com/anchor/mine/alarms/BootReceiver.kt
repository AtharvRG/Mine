// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.mine.alarms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.anchor.core.alarms.AlarmReconciler
import com.anchor.core.time.SystemClock
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
                val clock = SystemClock()

                // 1. Reconcile alarms using unified core reconciler (force = true on boot / time change)
                val reconciler = AlarmReconciler(storage, scheduler, clock)
                reconciler.reconcile(force = true)

                // 2. Run Sweeper for alarms missed while the device was off (older than 60s)
                val reliabilityManager = com.anchor.core.reliability.ReliabilityManager(storage, clock)

                reliabilityManager.runSweeper().forEach { missedRem ->
                    val missedIntent = Intent(context, AlarmReceiver::class.java).apply {
                        putExtra("EXTRA_ID", missedRem.id)
                        putExtra("EXTRA_TEXT", missedRem.text)
                        putExtra("EXTRA_TIER", missedRem.tier.name)
                        putExtra("EXTRA_MISSED", true)
                    }
                    context.sendBroadcast(missedIntent)
                }
            } finally {
                pendingResult?.finish()
            }
        }
    }
}