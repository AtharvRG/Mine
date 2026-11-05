// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.mine.quotes

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.anchor.core.quotes.QuoteManager
import com.anchor.core.time.SystemClock
import com.anchor.mine.MineApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Locale

class QuoteAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = try { goAsync() } catch (_: Exception) { null }
        val app = context.applicationContext as? MineApp ?: run {
            pendingResult?.finish()
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val storage = app.sharedStorage.await()
                val prepareSetting = storage.getSetting("prepare_next_quote_early") ?: "true"
                if (prepareSetting.lowercase(Locale.US) == "true") {
                    val ep = storage.getSetting("ep") ?: ""
                    val key = storage.getSetting("key") ?: ""
                    val mod = storage.getSetting("mod") ?: ""
                    if (ep.isNotBlank() && key.isNotBlank() && mod.isNotBlank()) {
                        val quoteManager = QuoteManager(
                            storage = storage,
                            orchestrator = app.orchestrator,
                            clock = SystemClock()
                        )
                        quoteManager.prepareNextSlotQuote()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Ignore transient network or database errors during background prep
            } finally {
                QuoteAlarmScheduler.scheduleNext(context)
                pendingResult?.finish()
            }
        }
    }
}
