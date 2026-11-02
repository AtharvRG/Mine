// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.quotes

import com.anchor.core.SharedFakeStorage
import com.anchor.core.models.QuoteSlot
import com.anchor.core.models.QuoteSource
import com.anchor.core.provider.HttpTransport
import com.anchor.core.provider.ProviderOrchestrator
import com.anchor.core.provider.RateLimiter
import com.anchor.core.provider.Target
import com.anchor.core.time.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class QuoteManagerTest {

    private class SettableClock(var time: Long) : Clock {
        override fun now(): Long = time
    }

    private val utcTz = TimeZone.getTimeZone("UTC")

    private fun calendarAt(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long {
        val cal = Calendar.getInstance(utcTz)
        cal.set(year, month - 1, day, hour, minute, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    @Test
    fun testSlotMappingByHour() {
        val storage = SharedFakeStorage()
        val clock = SettableClock(0L)
        val manager = QuoteManager(storage, null, clock, utcTz)

        assertEquals(QuoteSlot.MORNING, manager.getSlotForHour(6))
        assertEquals(QuoteSlot.MORNING, manager.getSlotForHour(9))
        assertEquals(QuoteSlot.MORNING, manager.getSlotForHour(11))

        assertEquals(QuoteSlot.AFTERNOON, manager.getSlotForHour(12))
        assertEquals(QuoteSlot.AFTERNOON, manager.getSlotForHour(14))
        assertEquals(QuoteSlot.AFTERNOON, manager.getSlotForHour(16))

        assertEquals(QuoteSlot.EVENING, manager.getSlotForHour(17))
        assertEquals(QuoteSlot.EVENING, manager.getSlotForHour(19))
        assertEquals(QuoteSlot.EVENING, manager.getSlotForHour(21))

        assertEquals(QuoteSlot.NIGHT, manager.getSlotForHour(22))
        assertEquals(QuoteSlot.NIGHT, manager.getSlotForHour(23))
        assertEquals(QuoteSlot.NIGHT, manager.getSlotForHour(0))
        assertEquals(QuoteSlot.NIGHT, manager.getSlotForHour(3))
        assertEquals(QuoteSlot.NIGHT, manager.getSlotForHour(5))
    }

    @Test
    fun testPresetFallback_InstantSeed() = runBlocking {
        val storage = SharedFakeStorage()
        val ts = calendarAt(2026, 11, 2, 8, 30) // 8:30 AM UTC
        val clock = SettableClock(ts)
        val manager = QuoteManager(storage, null, clock, utcTz)

        // 1. Initial lookup seeds preset
        val quote = manager.getQuote(ts)
        assertEquals(QuoteSlot.MORNING, quote.slot)
        assertEquals("2026-11-02", quote.dayKey)
        assertEquals(QuoteSource.PRESET, quote.source)
        assertEquals(0, quote.regenCount)
        assertTrue(quote.text.isNotBlank())

        // 2. Persisted into storage
        val persisted = storage.getQuoteSlot(QuoteSlot.MORNING, "2026-11-02")
        assertNotNull(persisted)
        assertEquals(quote.text, persisted!!.text)

        // 3. Repeated lookup returns exact same quote
        val quoteAgain = manager.getQuote(ts + 60000L)
        assertEquals(quote.text, quoteAgain.text)
    }

    @Test
    fun testAdvancePreparationWindow() {
        val storage = SharedFakeStorage()
        val clock = SettableClock(0L)
        val manager = QuoteManager(storage, null, clock, utcTz)

        // 5:30 AM -> Prepares MORNING
        val (prepMorning, morningSlot) = manager.shouldPrepareNextSlot(calendarAt(2026, 11, 2, 5, 30))
        assertTrue(prepMorning)
        assertEquals(QuoteSlot.MORNING, morningSlot)

        // 11:15 AM -> Prepares AFTERNOON
        val (prepAfternoon, afternoonSlot) = manager.shouldPrepareNextSlot(calendarAt(2026, 11, 2, 11, 15))
        assertTrue(prepAfternoon)
        assertEquals(QuoteSlot.AFTERNOON, afternoonSlot)

        // 16:45 -> Prepares EVENING
        val (prepEvening, eveningSlot) = manager.shouldPrepareNextSlot(calendarAt(2026, 11, 2, 16, 45))
        assertTrue(prepEvening)
        assertEquals(QuoteSlot.EVENING, eveningSlot)

        // 21:05 -> Prepares NIGHT
        val (prepNight, nightSlot) = manager.shouldPrepareNextSlot(calendarAt(2026, 11, 2, 21, 5))
        assertTrue(prepNight)
        assertEquals(QuoteSlot.NIGHT, nightSlot)

        // Normal middle hour -> False
        val (prepNoon, _) = manager.shouldPrepareNextSlot(calendarAt(2026, 11, 2, 13, 0))
        assertFalse(prepNoon)
    }

    @Test
    fun testLiveQuoteGeneration_SwapsPreset_AndEnforcesMaxTwoRegens() = runBlocking {
        val storage = SharedFakeStorage()
        storage.saveSetting("ep", "https://api.openai.com/v1/chat/completions")
        storage.saveSetting("key", "test-key")
        storage.saveSetting("mod", "gpt-4o-mini")

        var llmText = "Live inspiring quote from AI."
        val transport = object : HttpTransport {
            override suspend fun postStream(target: Target, body: String): Flow<String> = flow {
                emit("""data: {"choices":[{"delta":{"content":"$llmText"}}]}""")
            }
        }
        val orchestrator = ProviderOrchestrator(RateLimiter { 0L }, transport)

        val ts = calendarAt(2026, 11, 2, 14, 0) // 14:00 (AFTERNOON)
        val clock = SettableClock(ts)
        val manager = QuoteManager(storage, orchestrator, clock, utcTz)

        // 1. Initial preset
        val initial = manager.getQuote(ts)
        assertEquals(QuoteSource.PRESET, initial.source)

        // 2. Generate live quote (1st time)
        val live1 = manager.generateLiveQuote(QuoteSlot.AFTERNOON, "2026-11-02", "Busy afternoon", forceRegen = false)
        assertNotNull(live1)
        assertEquals(QuoteSource.LIVE, live1!!.source)
        assertEquals("Live inspiring quote from AI.", live1.text)
        assertEquals(1, live1.regenCount)

        // 3. Second regeneration (forceRegen = true) -> regenCount = 2
        llmText = "Second AI perspective."
        val live2 = manager.generateLiveQuote(QuoteSlot.AFTERNOON, "2026-11-02", "New tasks added", forceRegen = true)
        assertNotNull(live2)
        assertEquals("Second AI perspective.", live2!!.text)
        assertEquals(2, live2.regenCount)

        // 4. Third regeneration attempt (exceeding cap of 2 per D-108) -> Rejected, retains 2nd
        llmText = "Third AI attempt."
        val live3 = manager.generateLiveQuote(QuoteSlot.AFTERNOON, "2026-11-02", "Another update", forceRegen = true)
        assertNotNull(live3)
        assertEquals("Second AI perspective.", live3!!.text)
        assertEquals(2, live3.regenCount)
    }

    @Test
    fun testLiveQuoteGeneration_FailureRetainsExistingPreset() = runBlocking {
        val storage = SharedFakeStorage()
        storage.saveSetting("ep", "https://api.openai.com/v1/chat/completions")
        storage.saveSetting("key", "test-key")
        storage.saveSetting("mod", "gpt-4o-mini")

        val transport = object : HttpTransport {
            override suspend fun postStream(target: Target, body: String): Flow<String> = flow {
                throw RuntimeException("Network disconnected")
            }
        }
        val orchestrator = ProviderOrchestrator(RateLimiter { 0L }, transport)

        val ts = calendarAt(2026, 11, 2, 20, 0) // 20:00 (EVENING)
        val clock = SettableClock(ts)
        val manager = QuoteManager(storage, orchestrator, clock, utcTz)

        // 1. Seed preset
        val preset = manager.getQuote(ts)
        assertEquals(QuoteSource.PRESET, preset.source)

        // 2. Try live generation which fails
        val result = manager.generateLiveQuote(QuoteSlot.EVENING, "2026-11-02", "Evening rest")
        assertNotNull(result)
        assertEquals(preset.text, result!!.text)
        assertEquals(QuoteSource.PRESET, result.source)
    }
}
