// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.mine.alarms

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.anchor.core.alarms.AlarmReconciler
import com.anchor.core.alarms.ReliabilityIssue
import com.anchor.core.time.SystemClock
import com.anchor.mine.MineApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    companion object {
        /** H-03 (R-03): sent when storage failed at boot and the retry slot comes due. */
        const val ACTION_STORAGE_RETRY = "com.anchor.mine.alarms.ACTION_STORAGE_RETRY"
        const val REQUEST_CODE_STORAGE_RETRY = 9107

        /**
         * H-03 (R-03): after a storage failure the next attempt is at most this far away. Inexact
         * on purpose — the OS may defer an inexact alarm, and a late retry is harmless while a
         * security exception from an exact alarm would lose the retry entirely.
         */
        const val STORAGE_RETRY_DELAY_MS = 15L * 60 * 1000

        /**
         * H-03 (R-03): how many times a retry has actually been armed in this process. An inexact
         * alarm is invisible to the app (`getNextAlarmClock()` only reports `setAlarmClock`) and
         * `dumpsys alarm` needs the signature-level DUMP permission, so this counter is the only
         * channel a test has to observe the retry. Test-only in practice; never read by app code.
         */
        @Volatile
        var retriesArmed = 0
            private set

        /**
         * H-02 (R-02): sent by the periodic trigger. The sweeper used to run only at boot and at
         * app open, so a reminder missed while the app stayed closed went unreported until the
         * user next opened it.
         */
        const val ACTION_SWEEP = "com.anchor.mine.alarms.ACTION_SWEEP"
        const val REQUEST_CODE_SWEEP = 9109

        /**
         * H-02: how often the sweeper may run unattended. Inexact on purpose — the OS may defer
         * it, and a late sweep reports the same miss a minute later; an exact alarm would need a
         * wakeup we do not need for a report.
         */
        const val SWEEP_INTERVAL_MS = 15L * 60 * 1000

        /** H-02: same observation channel as [retriesArmed], same reason. Test-only in practice. */
        @Volatile
        var sweepsArmed = 0
            private set

        /**
         * H-02: arms the periodic sweep. Idempotent by design — `setInexactRepeating` with the
         * same PendingIntent replaces the previous registration rather than stacking a second
         * one, so calling it on every app start and every boot is safe.
         */
        fun schedulePeriodicSweep(context: Context, intervalMs: Long = SWEEP_INTERVAL_MS) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                REQUEST_CODE_SWEEP,
                Intent(context, BootReceiver::class.java).setAction(ACTION_SWEEP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            try {
                alarmManager.setInexactRepeating(
                    AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() + intervalMs,
                    intervalMs,
                    pendingIntent
                )
            } catch (e: SecurityException) {
                android.util.Log.w("BootReceiver", "Periodic sweep not armed: ${e.message}")
                return
            }
            sweepsArmed++
        }

        fun scheduleStorageRetry(context: Context, delayMs: Long = STORAGE_RETRY_DELAY_MS) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                REQUEST_CODE_STORAGE_RETRY,
                Intent(context, BootReceiver::class.java).setAction(ACTION_STORAGE_RETRY),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.set(
                AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + delayMs,
                pendingIntent
            )
            retriesArmed++
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val validActions = listOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            ACTION_STORAGE_RETRY,
            ACTION_SWEEP
        )
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
                        putExtra("EXTRA_SCHEDULED_FOR", missedRem.fireAtMillis)
                    }
                    context.sendBroadcast(missedIntent)
                }

                // 3. Reschedule quote preparation alarm
                com.anchor.mine.quotes.QuoteAlarmScheduler.scheduleNext(context)

                // 4. H-02: keep the periodic sweep registered. Re-arming on every boot and every
                // app start is what makes the trigger survive a reboot, a package update and an
                // OEM that drops repeating alarms.
                schedulePeriodicSweep(context)
            } catch (e: Exception) {
                // H-03 (R-03): without this catch a Keystore failure skipped reconcile, the sweeper
                // and quote rescheduling for the whole boot, and nothing ever retried — every
                // reminder stayed disarmed until the user opened the app. Record the failure so the
                // app can show it, and arm a bounded retry on this same path.
                recordStorageFailure(context, e)
                scheduleStorageRetry(context)
            } finally {
                pendingResult?.finish()
            }
        }
    }

    private suspend fun recordStorageFailure(context: Context, e: Exception) {
        // Re-opening is the only way to record this: app.sharedStorage is a deferred that has
        // already completed (or failed), so awaiting it again either returns the same failure or
        // the same instance — never a fresh open. A transient failure (I/O, lock) can succeed on
        // this second attempt; a KEYSTORE_LOST one cannot, and then the retry alarm is the only
        // remaining signal.
        try {
            val key = com.anchor.mine.storage.DbKeyProvider(context).getOrGenerateKey()
            val db = com.anchor.mine.storage.DatabaseProvider.getHelper(context, key, "mine_encrypted.db")
            val storage = com.anchor.mine.storage.AndroidStorage(db)
            val current = storage.getReliabilityState()
            val existing = AlarmReconciler.parseIssues(current.issuesJson)
            val reason = "Storage unavailable at boot: ${e.message}"
            val updated = (existing.filter { it.error != reason } + ReliabilityIssue(reason, System.currentTimeMillis()))
                .sortedByDescending { it.time }
                .take(5)
            storage.saveReliabilityState(
                current.copy(score = "at_risk", issuesJson = AlarmReconciler.encodeIssues(updated))
            )
        } catch (_: Exception) {
        }
    }
}
