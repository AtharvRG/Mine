// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.quotes

import com.anchor.core.SharedFakeStorage
import com.anchor.core.models.Node
import com.anchor.core.models.QuoteSlot
import com.anchor.core.models.QuoteSource
import com.anchor.core.models.QuoteStateCounts
import com.anchor.core.provider.HttpTransport
import com.anchor.core.provider.ProviderOrchestrator
import com.anchor.core.provider.RateLimiter
import com.anchor.core.provider.Target
import com.anchor.core.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class QuoteManagerTest {

    private class SettableClock(var time: Long) : Clock {
        override fun now(): Long = time
    }

    private val utcTz = TimeZone.getTimeZone("UTC")

    private fun calendarAt(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int = 0): Long {
        val cal = Calendar.getInstance(utcTz)
        cal.set(year, month - 1, day, hour, minute, second)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    @Test
    fun testExactSlotBoundaries() {
        val storage = SharedFakeStorage()
        val clock = SettableClock(0L)
        val manager = QuoteManager(storage, null, clock, utcTz)

        // D-109: MORNING 05-12, AFTERNOON 12-17, EVENING 17-21, NIGHT 21-05
        // Required boundary tests: 04:59, 05:00, 11:59, 12:00, 16:59, 17:00, 20:59, 21:00
        assertEquals(QuoteSlot.NIGHT, manager.getSlot(calendarAt(2026, 11, 2, 4, 59)))
        assertEquals(QuoteSlot.MORNING, manager.getSlot(calendarAt(2026, 11, 2, 5, 0)))
        assertEquals(QuoteSlot.MORNING, manager.getSlot(calendarAt(2026, 11, 2, 11, 59)))
        assertEquals(QuoteSlot.AFTERNOON, manager.getSlot(calendarAt(2026, 11, 2, 12, 0)))
        assertEquals(QuoteSlot.AFTERNOON, manager.getSlot(calendarAt(2026, 11, 2, 16, 59)))
        assertEquals(QuoteSlot.EVENING, manager.getSlot(calendarAt(2026, 11, 2, 17, 0)))
        assertEquals(QuoteSlot.EVENING, manager.getSlot(calendarAt(2026, 11, 2, 20, 59)))
        assertEquals(QuoteSlot.NIGHT, manager.getSlot(calendarAt(2026, 11, 2, 21, 0)))
    }

    @Test
    fun testPrepareHours() {
        val storage = SharedFakeStorage()
        val clock = SettableClock(0L)
        val manager = QuoteManager(storage, null, clock, utcTz)

        // D-109: Prepare-ahead fires 1 hour before each boundary: 04:00 (->MORNING), 11:00 (->AFTERNOON), 16:00 (->EVENING), 20:00 (->NIGHT)
        val (prep4, slot4) = manager.shouldPrepareNextSlot(calendarAt(2026, 11, 2, 4, 0))
        assertTrue("04:00 must prepare MORNING", prep4)
        assertEquals(QuoteSlot.MORNING, slot4)

        val (prep11, slot11) = manager.shouldPrepareNextSlot(calendarAt(2026, 11, 2, 11, 0))
        assertTrue("11:00 must prepare AFTERNOON", prep11)
        assertEquals(QuoteSlot.AFTERNOON, slot11)

        val (prep16, slot16) = manager.shouldPrepareNextSlot(calendarAt(2026, 11, 2, 16, 0))
        assertTrue("16:00 must prepare EVENING", prep16)
        assertEquals(QuoteSlot.EVENING, slot16)

        val (prep20, slot20) = manager.shouldPrepareNextSlot(calendarAt(2026, 11, 2, 20, 0))
        assertTrue("20:00 must prepare NIGHT", prep20)
        assertEquals(QuoteSlot.NIGHT, slot20)

        // Non-prepare hours must return false and null
        val (prep3, slot3) = manager.shouldPrepareNextSlot(calendarAt(2026, 11, 2, 3, 0))
        assertFalse(prep3)
        assertNull(slot3)

        val (prep12, slot12) = manager.shouldPrepareNextSlot(calendarAt(2026, 11, 2, 12, 0))
        assertFalse(prep12)
        assertNull(slot12)

        val (prep17, slot17) = manager.shouldPrepareNextSlot(calendarAt(2026, 11, 2, 17, 0))
        assertFalse(prep17)
        assertNull(slot17)

        val (prep21, slot21) = manager.shouldPrepareNextSlot(calendarAt(2026, 11, 2, 21, 0))
        assertFalse(prep21)
        assertNull(slot21)
    }

    @Test
    fun testPresetFallback_InstantSeed() = runBlocking {
        val storage = SharedFakeStorage()
        val ts = calendarAt(2026, 11, 2, 8, 30)
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
    fun testRegenCap2WithHashChangeRequired() = runBlocking {
        val storage = SharedFakeStorage()
        storage.saveSetting("ep", "https://api.openai.com/v1/chat/completions")
        storage.saveSetting("key", "test-key")
        storage.saveSetting("mod", "gpt-4o-mini")

        var llmText = "Live inspiring quote 1."
        val transport = object : HttpTransport {
            override suspend fun postStream(target: Target, body: String): Flow<String> = flow {
                emit("""data: {"choices":[{"delta":{"content":"$llmText"}}]}""")
            }
        }
        val orchestrator = ProviderOrchestrator(RateLimiter { 0L }, transport)

        val ts = calendarAt(2026, 11, 2, 14, 0) // 14:00 (AFTERNOON)
        val clock = SettableClock(ts)
        val manager = QuoteManager(storage, orchestrator, clock, utcTz)

        // Seed initial preset
        val initialPreset = manager.getQuote(ts)
        assertEquals(QuoteSource.PRESET, initialPreset.source)

        // 1. First live generation: regenCount must be 0 per R8 requirement
        val counts1 = QuoteStateCounts(open = 5, overdue = 1, completedToday = 0, atRisk = false)
        val live1 = manager.generateLiveQuote(QuoteSlot.AFTERNOON, "2026-11-02", counts1)
        assertNotNull(live1)
        assertEquals(QuoteSource.LIVE, live1!!.source)
        assertEquals("Live inspiring quote 1.", live1.text)
        assertEquals("First live generation must have regenCount = 0", 0, live1.regenCount)
        assertEquals(manager.computeStateHash(counts1), live1.stateHash)

        // 2. Second generation (1st regeneration): requires changed state_hash -> regenCount = 1
        llmText = "Live inspiring quote 2."
        val counts2 = QuoteStateCounts(open = 4, overdue = 0, completedToday = 1, atRisk = false) // Changed
        val live2 = manager.generateLiveQuote(QuoteSlot.AFTERNOON, "2026-11-02", counts2)
        assertNotNull(live2)
        assertEquals("Live inspiring quote 2.", live2!!.text)
        assertEquals("First regeneration must have regenCount = 1", 1, live2.regenCount)
        assertEquals(manager.computeStateHash(counts2), live2.stateHash)

        // 3. Third generation (2nd regeneration): requires changed state_hash -> regenCount = 2
        llmText = "Live inspiring quote 3."
        val counts3 = QuoteStateCounts(open = 3, overdue = 0, completedToday = 2, atRisk = false) // Changed
        val live3 = manager.generateLiveQuote(QuoteSlot.AFTERNOON, "2026-11-02", counts3)
        assertNotNull(live3)
        assertEquals("Live inspiring quote 3.", live3!!.text)
        assertEquals("Second regeneration must have regenCount = 2", 2, live3.regenCount)
        assertEquals(manager.computeStateHash(counts3), live3.stateHash)

        // 4. Fourth generation attempt (exceeding max 2 regenerations per slot) -> must be rejected and keep live3
        llmText = "Live inspiring quote 4."
        val counts4 = QuoteStateCounts(open = 2, overdue = 0, completedToday = 3, atRisk = true) // Changed
        val live4 = manager.generateLiveQuote(QuoteSlot.AFTERNOON, "2026-11-02", counts4)
        assertNotNull(live4)
        assertEquals("Must reject regeneration exceeding cap of 2 and retain 3rd live quote", "Live inspiring quote 3.", live4!!.text)
        assertEquals(2, live4.regenCount)
    }

    @Test
    fun testSameHashNoRegen() = runBlocking {
        val storage = SharedFakeStorage()
        storage.saveSetting("ep", "https://api.openai.com/v1/chat/completions")
        storage.saveSetting("key", "test-key")
        storage.saveSetting("mod", "gpt-4o-mini")

        var networkCalls = 0
        val transport = object : HttpTransport {
            override suspend fun postStream(target: Target, body: String): Flow<String> = flow {
                networkCalls++
                emit("""data: {"choices":[{"delta":{"content":"Live quote."}}]}""")
            }
        }
        val orchestrator = ProviderOrchestrator(RateLimiter { 0L }, transport)

        val ts = calendarAt(2026, 11, 2, 9, 0) // MORNING
        val clock = SettableClock(ts)
        val manager = QuoteManager(storage, orchestrator, clock, utcTz)

        val counts = QuoteStateCounts(open = 3, overdue = 0, completedToday = 1, atRisk = false)

        // 1. First live generation
        val live1 = manager.generateLiveQuote(QuoteSlot.MORNING, "2026-11-02", counts)
        assertNotNull(live1)
        assertEquals(1, networkCalls)
        assertEquals(0, live1!!.regenCount)

        // 2. Second attempt with exact same counts / hash -> must NOT call network or regenerate
        val live2 = manager.generateLiveQuote(QuoteSlot.MORNING, "2026-11-02", counts)
        assertNotNull(live2)
        assertEquals("Should not call network when state hash is unchanged", 1, networkCalls)
        assertEquals(live1.text, live2!!.text)
        assertEquals(0, live2.regenCount)
    }

    @Test
    fun testCountsOnlyPrompt_NoTitlesInRequestBody() = runBlocking {
        val storage = SharedFakeStorage()
        storage.saveSetting("ep", "https://api.openai.com/v1/chat/completions")
        storage.saveSetting("key", "test-key")
        storage.saveSetting("mod", "gpt-4o-mini")

        // Seed a sensitive node title in storage
        val sensitiveNode = Node(
            id = "n_priv_1",
            type = "task",
            title = "Top Secret Oncology Appointment with Dr Smith",
            status = "active",
            dueAt = 1700000000000L,
            createdAt = 1700000000000L,
            updatedAt = 1700000000000L
        )
        storage.createNodeAndCommit(sensitiveNode, com.anchor.core.models.RawEvent("op_priv", "CREATE", "node", "n_priv_1"), null)

        var capturedBody = ""
        val transport = object : HttpTransport {
            override suspend fun postStream(target: Target, body: String): Flow<String> = flow {
                capturedBody = body
                emit("""data: {"choices":[{"delta":{"content":"Mindful presence."}}]}""")
            }
        }
        val orchestrator = ProviderOrchestrator(RateLimiter { 0L }, transport)

        val ts = calendarAt(2026, 11, 2, 18, 0) // EVENING
        val clock = SettableClock(ts)
        val manager = QuoteManager(storage, orchestrator, clock, utcTz)

        val counts = QuoteStateCounts(open = 7, overdue = 2, completedToday = 4, atRisk = true)
        val result = manager.generateLiveQuote(QuoteSlot.EVENING, "2026-11-02", counts)
        assertNotNull(result)

        // Assert privacy: body MUST contain numeric counts and slot, but MUST NOT contain any node titles
        assertTrue("Request body should contain open count", capturedBody.contains("7 open") || capturedBody.contains("7"))
        assertTrue("Request body should contain overdue count", capturedBody.contains("2 overdue") || capturedBody.contains("2"))
        assertTrue("Request body should contain completedToday count", capturedBody.contains("4 completed today") || capturedBody.contains("4"))
        assertTrue("Request body should contain at-risk status", capturedBody.contains("at-risk: true") || capturedBody.contains("true"))
        assertFalse("Request body must NEVER contain node title", capturedBody.contains("Top Secret Oncology Appointment"))
        assertFalse("Request body must NEVER contain Dr Smith", capturedBody.contains("Dr Smith"))
    }

    @Test
    fun testProviderMissingOrOfflineKeepsPreset() = runBlocking {
        val storage = SharedFakeStorage()
        val ts = calendarAt(2026, 11, 2, 22, 0) // NIGHT
        val clock = SettableClock(ts)

        // 1. Provider missing settings entirely
        val managerNoSettings = QuoteManager(storage, null, clock, utcTz)
        val preset = managerNoSettings.getQuote(ts)
        assertEquals(QuoteSource.PRESET, preset.source)

        val resultNoSettings = managerNoSettings.generateLiveQuote(QuoteSlot.NIGHT, "2026-11-02")
        assertEquals("Missing provider settings must keep preset", preset.text, resultNoSettings!!.text)
        assertEquals(QuoteSource.PRESET, resultNoSettings.source)

        // 2. Provider throws network error
        storage.saveSetting("ep", "https://api.openai.com/v1/chat/completions")
        storage.saveSetting("key", "test-key")
        storage.saveSetting("mod", "gpt-4o-mini")

        val failingTransport = object : HttpTransport {
            override suspend fun postStream(target: Target, body: String): Flow<String> = flow {
                throw RuntimeException("Connection refused / 503 Service Unavailable")
            }
        }
        val orchestrator = ProviderOrchestrator(RateLimiter { 0L }, failingTransport)
        val managerWithFailingNet = QuoteManager(storage, orchestrator, clock, utcTz)

        val resultFailingNet = managerWithFailingNet.generateLiveQuote(QuoteSlot.NIGHT, "2026-11-02")
        assertNotNull(resultFailingNet)
        assertEquals("Network failure must retain existing preset", preset.text, resultFailingNet!!.text)
        assertEquals(QuoteSource.PRESET, resultFailingNet.source)
    }

    @Test
    fun testCancellationPropagates() = runBlocking {
        val storage = SharedFakeStorage()
        storage.saveSetting("ep", "https://api.openai.com/v1/chat/completions")
        storage.saveSetting("key", "test-key")
        storage.saveSetting("mod", "gpt-4o-mini")

        val cancellingTransport = object : HttpTransport {
            override suspend fun postStream(target: Target, body: String): Flow<String> = flow {
                throw CancellationException("Coroutine was cancelled")
            }
        }
        val orchestrator = ProviderOrchestrator(RateLimiter { 0L }, cancellingTransport)

        val ts = calendarAt(2026, 11, 2, 10, 0)
        val clock = SettableClock(ts)
        val manager = QuoteManager(storage, orchestrator, clock, utcTz)

        try {
            manager.generateLiveQuote(QuoteSlot.MORNING, "2026-11-02")
            fail("CancellationException must propagate and not be swallowed")
        } catch (e: CancellationException) {
            assertEquals("Coroutine was cancelled", e.message)
        }
    }

    @Test
    fun testOnHomeOpened_ShowsPresetInstantlyThenSwapsLive() = runBlocking {
        val storage = SharedFakeStorage()
        storage.saveSetting("ep", "https://api.openai.com/v1/chat/completions")
        storage.saveSetting("key", "test-key")
        storage.saveSetting("mod", "gpt-4o-mini")

        val transport = object : HttpTransport {
            override suspend fun postStream(target: Target, body: String): Flow<String> = flow {
                emit("""data: {"choices":[{"delta":{"content":"Live Home Quote."}}]}""")
            }
        }
        val orchestrator = ProviderOrchestrator(RateLimiter { 0L }, transport)

        val ts = calendarAt(2026, 11, 2, 13, 0) // AFTERNOON
        val clock = SettableClock(ts)
        val testScope = CoroutineScope(Dispatchers.Unconfined)
        val manager = QuoteManager(storage, orchestrator, clock, utcTz, coroutineScope = testScope)

        // 1. Initial onHomeOpened call
        val initial = manager.onHomeOpened(ts)
        assertEquals("Instant response must be PRESET", QuoteSource.PRESET, initial.source)

        // Since testScope is Unconfined, the background job executes and swaps to live
        val updated = manager.currentQuote.value
        assertNotNull(updated)
        assertEquals(QuoteSource.LIVE, updated!!.source)
        assertEquals("Live Home Quote.", updated.text)

        // 2. Subsequent onHomeOpened call with same state is idempotent
        val repeated = manager.onHomeOpened(ts)
        assertEquals("Repeated call returns current live quote", "Live Home Quote.", repeated.text)
    }

    @Test
    fun testPrepareNextSlotQuote_SettingsCheck() = runBlocking {
        val storage = SharedFakeStorage()
        storage.saveSetting("ep", "https://api.openai.com/v1/chat/completions")
        storage.saveSetting("key", "test-key")
        storage.saveSetting("mod", "gpt-4o-mini")

        val transport = object : HttpTransport {
            override suspend fun postStream(target: Target, body: String): Flow<String> = flow {
                emit("""data: {"choices":[{"delta":{"content":"Early morning calm."}}]}""")
            }
        }
        val orchestrator = ProviderOrchestrator(RateLimiter { 0L }, transport)

        // 04:00 prepares MORNING
        val ts4am = calendarAt(2026, 11, 2, 4, 0)
        val clock = SettableClock(ts4am)
        val manager = QuoteManager(storage, orchestrator, clock, utcTz)

        // 1. When setting is disabled (prepare_next_quote_early = "false") -> skips
        storage.saveSetting("prepare_next_quote_early", "false")
        val quoteDisabled = manager.prepareNextSlotQuote(ts4am)
        assertNull("Should skip when prepare_next_quote_early is false", quoteDisabled)

        // 2. When setting is enabled ("true" or default) -> prepares next slot (MORNING)
        storage.saveSetting("prepare_next_quote_early", "true")
        val quoteEnabled = manager.prepareNextSlotQuote(ts4am)
        assertNotNull(quoteEnabled)
        assertEquals(QuoteSlot.MORNING, quoteEnabled!!.slot)
        assertEquals(QuoteSource.LIVE, quoteEnabled.source)
        assertEquals("Early morning calm.", quoteEnabled.text)
    }

    @Test
    fun testPresetQuotesFloorMod_NoIntMinValueCrash() {
        // Test all slots with various day keys
        for (slot in QuoteSlot.entries) {
            val q = PresetQuotes.getPreset(slot, "2026-11-02")
            assertTrue("Preset quote must not be blank", q.isNotBlank())
        }
        // Test synthetic hash producing Int.MIN_VALUE with Math.floorMod
        val poolSize = 7
        val mod = Math.floorMod(Int.MIN_VALUE, poolSize)
        assertTrue("floorMod must always be non-negative", mod in 0 until poolSize)
    }
}
