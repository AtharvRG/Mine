// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.ui

import com.anchor.core.SharedFakeStorage
import com.anchor.core.context.ContextBuilder
import com.anchor.core.export.ExportController
import com.anchor.core.export.ExportManager
import com.anchor.core.export.ExportStats
import com.anchor.core.gateway.Gateway
import com.anchor.core.models.GateResult
import com.anchor.core.models.NudgeCandidate
import com.anchor.core.models.ProactiveState
import com.anchor.core.models.Reminder
import com.anchor.core.nudge.NudgeEngine
import com.anchor.core.reliability.ReliabilityManager
import com.anchor.core.time.DateResolution
import com.anchor.core.time.DateResolver
import com.anchor.core.time.FakeClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale

@OptIn(ExperimentalCoroutinesApi::class)
class TitaniumBatch2Test {

    /**
     * B2-06: Proactive nudge fuseTripped must reset on day rollover.
     * When 40 nudges have been sent on day 2026-11-10, evaluation trips the fuse.
     * When currentDayKey rolls over to 2026-11-11, sentToday must reset to 0 and evaluate must Pass.
     */
    @Test
    fun testNudgeEngine_DayRollover_ResetsSentToday() {
        val clock = FakeClock(1_000_000L)
        val engine = NudgeEngine(clock)
        val candidate = NudgeCandidate("overdue", isCritical = false)

        val stateDay1 = ProactiveState(
            openThreadMsgId = null,
            threadOpenedAt = null,
            lastSentAt = null,
            sentToday = 40,
            dayKey = "2026-11-10"
        )

        // Same day: fuse is tripped
        val resultSameDay = engine.evaluate(candidate, stateDay1, currentDayKey = "2026-11-10")
        assertEquals(GateResult.Held("fuse_tripped"), resultSameDay)

        // Day rollover: sentToday resets to 0
        val resultNextDay = engine.evaluate(candidate, stateDay1, currentDayKey = "2026-11-11")
        assertEquals(GateResult.Pass, resultNextDay)

        // onNudgeSent on next day increments from 0 to 1
        val updated = engine.onNudgeSent(stateDay1, "n_123", clock.now(), dayKey = "2026-11-11")
        assertEquals(1, updated.sentToday)
        assertEquals("2026-11-11", updated.dayKey)
    }

    /**
     * B2-07: Turkish / Azerbaijani locale must not break relative duration parsing.
     * With Turkish default locale, 'I'/'i' conversions produce dotless 'ı' or dotted 'İ'.
     * Using Locale.ROOT guarantees "in 10 minutes" is normalized reliably.
     */
    @Test
    fun testDateResolver_TurkishLocale_ParsesRelativeDurations() {
        val prevLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale("tr", "TR"))
            val clock = FakeClock(1_000_000L)
            val resolver = DateResolver(clock) { true }

            val res = resolver.resolve("in 10 minutes")
            assertTrue("Turkish locale must resolve 'in 10 minutes' successfully", res is DateResolution.Exact)
            val resolved = res as DateResolution.Exact
            assertEquals(1_000_000L + 10 * 60 * 1000L, resolved.timeMillis)
        } finally {
            Locale.setDefault(prevLocale)
        }
    }

    /**
     * B2-08: extractReplyFromJsonStream must permit optional whitespace around colon.
     */
    @Test
    fun testStreamParser_ToleratesWhitespaceAroundColon() {
        // Standard compact JSON
        assertEquals("Hello world", ChatController.extractReplyFromJsonStream("""{"reply":"Hello world","calls":[]}"""))

        // Whitespace after colon
        assertEquals("Hello world", ChatController.extractReplyFromJsonStream("""{"reply": "Hello world","calls":[]}"""))

        // Whitespace before and after colon
        assertEquals("Hello world", ChatController.extractReplyFromJsonStream("""{"reply" : "Hello world", "calls": []}"""))

        // Multiple spaces and newlines
        assertEquals("Hello world", ChatController.extractReplyFromJsonStream("""{"reply"  :  "Hello world"}"""))
    }

    /**
     * B2-10: ReliabilityManager latency integer overflow:
     * Overdue reminder overdue by 35 days (> Int.MAX_VALUE ms) must be clamped and not overflow to negative.
     */
    @Test
    fun testReliabilityManager_ExtremeOverdue_DoesNotOverflow() = runBlocking {
        val now = 5_000_000_000L
        val thirtyFiveDaysMs = 35L * 24 * 3600 * 1000L // ~3.024 billion ms (> Int.MAX_VALUE = 2.147 billion)
        val fireAt = now - thirtyFiveDaysMs

        val clock = FakeClock(now)
        val storage = SharedFakeStorage(clock)
        val reminder = Reminder("r_overdue", "Very old task", fireAt)
        storage.saveReminder(reminder)

        val reliabilityManager = ReliabilityManager(storage, clock)
        val missed = reliabilityManager.runSweeper()

        assertEquals(1, missed.size)
        val fire = storage.getReminderFire("r_overdue", "missed")
        assertNotNull("Recorded reminder_fire must exist", fire)
        assertTrue("B2-10: latencyMs must be non-negative and clamped to Int.MAX_VALUE", fire!!.latencyMs > 0)
        assertEquals(Int.MAX_VALUE, fire.latencyMs)
    }

    /**
     * B2-11: Swallowed CancellationException in ExportController:
     * When the job is cancelled, CancellationException must propagate cleanly.
     */
    @Test
    fun testExportController_Cancellation_Propagates() = runTest {
        val started = CompletableDeferred<Unit>()
        val fakeManager = object : ExportManager {
            override suspend fun exportTo(file: File, passphrase: String, includeApiKeys: Boolean, includeEmbeddings: Boolean): ExportStats {
                started.complete(Unit)
                delay(10_000L) // Wait until cancelled
                return ExportStats(0, 0, 0, false)
            }
            override suspend fun verifyOrImport(file: File, passphrase: String, isImport: Boolean): ExportStats =
                ExportStats(0, 0, 0, false)
        }
        val job = Job()
        val scope = CoroutineScope(UnconfinedTestDispatcher() + job)
        val controller = ExportController(fakeManager, scope)

        controller.export(File("test.mine"), "validPass123456", includeApiKeys = false)
        started.await()

        assertTrue("Controller must be busy while export is running", controller.state.value.isBusy)

        // Cancel the job
        job.cancel(CancellationException("Test cancellation"))

        // After cancellation, it must not write an application error
        val state = controller.state.value
        assertTrue("Cancellation should not record resultIsError=true as a UI failure", !state.resultIsError || state.result == null)
    }

    /**
     * B2-09: Stream concurrency & outbox drain race:
     * When sendText and drainQueuedTurns are launched concurrently, turn execution is serialized
     * through sendMutex so streaming responses and bot IDs cannot collide.
     */
    @Test
    fun testConcurrentSendAndDrain_SerializedSafely() = runBlocking {
        val clock = FakeClock(1_000_000L)
        val storage = SharedFakeStorage(clock)
        storage.settings["key"] = "test-key"

        val transport = object : com.anchor.core.provider.HttpTransport {
            override suspend fun postStream(target: com.anchor.core.provider.Target, body: String) = kotlinx.coroutines.flow.flow {
                emit("""data: {"choices":[{"delta":{"content":"{\"reply\":\"Turn complete.\",\"calls\":[]}"}}]}""")
                emit("data: [DONE]")
            }
        }
        val orchestrator = com.anchor.core.provider.ProviderOrchestrator(com.anchor.core.provider.RateLimiter { clock.now() }, transport)
        val dateResolver = DateResolver(clock)
        val checkinBuilder = com.anchor.core.nudge.CheckinBuilder(storage, clock, dateResolver)
        val contextBuilder = ContextBuilder(storage, clock, dateResolver, checkinBuilder)
        val scope = CoroutineScope(UnconfinedTestDispatcher())
        val controller = ChatController(
            storage = storage,
            orchestrator = orchestrator,
            contextBuilder = contextBuilder,
            gateway = Gateway(storage, com.anchor.core.resolver.Resolver(), dateResolver, clock),
            alarmScheduler = object : com.anchor.core.alarms.AlarmScheduler {
                override val lastFiredId = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
                override fun schedule(reminder: Reminder) = com.anchor.core.alarms.ScheduleResult.Armed
                override fun cancel(reminderId: String) {}
            },
            coroutineScope = scope,
            defaultDispatcher = UnconfinedTestDispatcher(),
            clock = clock,
            dateResolver = dateResolver,
            networkCheck = { true }
        )

        // Queue a job in outbox
        storage.enqueueOutbox(
            com.anchor.core.models.OutboxJob(
                id = "ob_queued_turn",
                jobType = com.anchor.core.models.OutboxJob.JOB_CHAT_TURN,
                priority = 0,
                payloadJson = """{"msg_id":"q_1"}""",
                idemKey = "q_1",
                nextTryAt = clock.now() - 1000L,
                createdAt = clock.now() - 2000L,
                updatedAt = clock.now() - 2000L
            )
        )
        storage.insertRawMessage("q_1", "user", "queued question")

        // Launch concurrent sendText and drainQueuedTurns
        controller.sendText("live question")
        controller.onAction(UiAction.DrainQueuedTurns)

        while (controller.state.value.isChatLoading) {
            delay(10)
        }

        // Both turns completed safely and the assistant answered
        assertTrue("Messages must contain assistant replies", storage.messages.any { it.role == "assistant" })
    }
}
