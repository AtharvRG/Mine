// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.mine.quotes

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Calendar

@RunWith(AndroidJUnit4::class)
class QuoteAlarmReceiverTest {

    @Test
    fun testScheduleNext_executesWithoutCrash() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        QuoteAlarmScheduler.scheduleNext(context)
        val next = QuoteAlarmScheduler.getNextPrepareTimeMillis()
        assertTrue("Next prepare time must be in future", next > System.currentTimeMillis())
        val cal = Calendar.getInstance().apply { timeInMillis = next }
        assertTrue("Prepare hour must be 4, 11, 16, or 20", QuoteAlarmScheduler.PREPARE_HOURS.contains(cal.get(Calendar.HOUR_OF_DAY)))
        assertEquals("Minute must be 0", 0, cal.get(Calendar.MINUTE))
        assertEquals("Second must be 0", 0, cal.get(Calendar.SECOND))
    }

    @Test
    fun testQuoteAlarmReceiver_onReceive_executesCleanly() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val receiver = QuoteAlarmReceiver()
        val intent = Intent(QuoteAlarmScheduler.ACTION_PREPARE_QUOTE)
        receiver.onReceive(context, intent)
        // Give background coroutine time to complete
        kotlinx.coroutines.delay(1000)
        val next = QuoteAlarmScheduler.getNextPrepareTimeMillis()
        assertTrue("Receiver must schedule next prepare time in future", next > System.currentTimeMillis())
    }
}
