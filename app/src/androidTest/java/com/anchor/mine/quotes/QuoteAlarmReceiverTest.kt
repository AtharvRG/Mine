// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.mine.quotes

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QuoteAlarmReceiverTest {

    @Test
    fun testScheduleNext_executesWithoutCrash() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        QuoteAlarmScheduler.scheduleNext(context)
        val next = QuoteAlarmScheduler.getNextPrepareTimeMillis()
        assertTrue(next > System.currentTimeMillis())
    }

    @Test
    fun testQuoteAlarmReceiver_onReceive_executesCleanly() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val receiver = QuoteAlarmReceiver()
        val intent = Intent(QuoteAlarmScheduler.ACTION_PREPARE_QUOTE)
        receiver.onReceive(context, intent)
        // Give background coroutine time to complete
        kotlinx.coroutines.delay(1000)
    }
}
