// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.nudge

import com.anchor.core.SharedFakeStorage
import com.anchor.core.alarms.AlarmScheduler
import com.anchor.core.alarms.ScheduleResult
import com.anchor.core.context.ContextBuilder
import com.anchor.core.gateway.Gateway
import com.anchor.core.models.Node
import com.anchor.core.models.Reminder
import com.anchor.core.models.TopicNote
import com.anchor.core.provider.HttpTransport
import com.anchor.core.provider.ProviderOrchestrator
import com.anchor.core.provider.RateLimiter
import com.anchor.core.provider.Target
import com.anchor.core.resolver.Resolver
import com.anchor.core.time.DateResolver
import com.anchor.core.time.FakeClock
import com.anchor.core.ui.ChatController
import com.anchor.core.ui.UiAction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ProcrastinationAndCasualNudgeTest {

    private lateinit var clock: FakeClock
    private lateinit var storage: SharedFakeStorage
    private lateinit var dateResolver: DateResolver
    private lateinit var checkinBuilder: CheckinBuilder
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        clock = FakeClock(1700000000000L)
        storage = SharedFakeStorage(clock)
        storage.settings["key"] = "test-key"
        dateResolver = DateResolver(clock, { false })
        checkinBuilder = CheckinBuilder(storage, clock, dateResolver)
    }

    @Test
    fun testProcrastinationDetector_GentleMode_InjectsGentleGuidance() = runBlocking {
        storage.saveSetting("procrastination.mode", "gentle")
        storage.saveSetting("procrastination.slip_threshold", "2")

        val task = Node(
            id = "n_slipped_1",
            title = "Submit tax documentation",
            type = "task",
            status = "active",
            createdAt = clock.now() - 5 * 86400_000L,
            updatedAt = clock.now(),
            dueAt = clock.now() - 3600_000L,
            originalDueAt = clock.now() - 5 * 86400_000L,
            slipCount = 2
        )
        storage.saveNode(task)

        val detector = ProcrastinationDetector(storage, clock, dateResolver)
        val analysis = detector.analyze(clock.now())

        assertEquals("gentle", analysis.mode)
        assertEquals(1, analysis.slippedNodes.size)
        assertNotNull(analysis.promptInstruction)
        assertTrue(analysis.promptInstruction!!.contains("Procrastination Guidance (GENTLE)"))
        assertTrue(analysis.promptInstruction!!.contains("slipped 2 times"))
    }

    @Test
    fun testProcrastinationDetector_FirmMode_InjectsHardGate() = runBlocking {
        storage.saveSetting("procrastination.mode", "firm")
        storage.saveSetting("procrastination.slip_threshold", "2")

        val task = Node(
            id = "n_slipped_2",
            title = "Finalize quarterly budget",
            type = "task",
            status = "active",
            createdAt = clock.now() - 7 * 86400_000L,
            updatedAt = clock.now(),
            dueAt = clock.now() - 72 * 3600_000L,
            originalDueAt = clock.now() - 7 * 86400_000L,
            slipCount = 3
        )
        storage.saveNode(task)

        val detector = ProcrastinationDetector(storage, clock, dateResolver)
        val analysis = detector.analyze(clock.now())

        assertEquals("firm", analysis.mode)
        assertEquals(1, analysis.slippedNodes.size)
        assertNotNull(analysis.promptInstruction)
        assertTrue(analysis.promptInstruction!!.contains("Procrastination Gate (FIRM)"))
        assertTrue(analysis.promptInstruction!!.contains("Do NOT encourage scheduling or adding new low-priority tasks"))
        assertTrue(analysis.promptInstruction!!.contains("5-minute micro-action"))
    }

    @Test
    fun testProcrastinationDetector_ExpiredBreakTimer_FirmMode_InjectsBreakNotice() = runBlocking {
        storage.saveSetting("procrastination.mode", "firm")
        val now = clock.now()
        val breakEnded = now - 45 * 60_000L // 45 minutes ago
        storage.saveSetting("break.until_ms", breakEnded.toString())

        // Active task due today
        val task = Node(
            id = "n_today_task",
            title = "Prepare demo script",
            type = "task",
            status = "active",
            createdAt = now - 3600_000L,
            updatedAt = now - 3600_000L,
            dueAt = now + 2 * 3600_000L,
            originalDueAt = now + 2 * 3600_000L,
            slipCount = 0
        )
        storage.saveNode(task)

        val detector = ProcrastinationDetector(storage, clock, dateResolver)
        val analysis = detector.analyze(now)

        assertEquals("firm", analysis.mode)
        assertTrue(analysis.breakExpired)
        assertNotNull(analysis.promptInstruction)
        assertTrue(analysis.promptInstruction!!.contains("The user's scheduled break ended"))
        assertTrue(analysis.promptInstruction!!.contains("Save any off-topic idea or question from the user to a Topic Note Card via `upsert_topic_note`"))
        assertTrue(analysis.promptInstruction!!.contains("firmly redirect them to complete 5-25 minutes on the slipped task first"))
    }

    @Test
    fun testChatController_SetProcrastinationMode_UpdatesStateAndStorage() = runBlocking {
        val testDispatcher = UnconfinedTestDispatcher()
        val testScope = TestScope(testDispatcher)

        val fakeScheduler = object : AlarmScheduler {
            override val lastFiredId = MutableStateFlow<String?>(null)
            override fun schedule(reminder: Reminder) = ScheduleResult.Armed
            override fun cancel(reminderId: String) {}
        }
        val fakeTransport = object : HttpTransport {
            override suspend fun postStream(target: Target, body: String) = kotlinx.coroutines.flow.emptyFlow<String>()
        }
        val rateLimiter = RateLimiter(clock = { clock.now() })
        val orchestrator = ProviderOrchestrator(rateLimiter, fakeTransport)
        val contextBuilder = ContextBuilder(storage, clock, dateResolver, checkinBuilder)
        val gateway = Gateway(storage, Resolver(), dateResolver, clock, alarmScheduler = fakeScheduler)

        val controller = ChatController(
            storage = storage,
            orchestrator = orchestrator,
            contextBuilder = contextBuilder,
            gateway = gateway,
            alarmScheduler = fakeScheduler,
            coroutineScope = testScope,
            defaultDispatcher = testDispatcher,
            clock = clock,
            dateResolver = dateResolver,
            networkCheck = { true },
            checkinBuilder = checkinBuilder
        )

        assertEquals("gentle", controller.state.value.procrastinationMode)

        // Switch to firm
        controller.onAction(UiAction.SetProcrastinationMode("firm"))

        assertEquals("firm", controller.state.value.procrastinationMode)
        assertEquals("firm", storage.getSetting("procrastination.mode"))

        // Switch to off
        controller.onAction(UiAction.SetProcrastinationMode("off"))

        assertEquals("off", controller.state.value.procrastinationMode)
        assertEquals("off", storage.getSetting("procrastination.mode"))
    }

    @Test
    fun testGroundedCasualCheckin_UsesActualNotes() = runBlocking {
        val note = TopicNote(
            id = "tn_dentist",
            topic = "Dentist prep",
            content = "- Bring insurance card\n- Ask about whitening",
            bulletsJson = json.encodeToString(listOf("Bring insurance card", "Ask about whitening")),
            pinned = true,
            lastEditedAt = clock.now(),
            createdAt = clock.now()
        )
        storage.saveTopicNote(note)

        val checkin = checkinBuilder.buildGroundedCasualCheckin(clock.now())
        assertNotNull(checkin)
        assertTrue("Checkin should reference topic: $checkin", checkin!!.contains("Dentist prep"))
        assertTrue("Checkin should reference first bullet: $checkin", checkin.contains("Bring insurance card"))
    }

    @Test
    fun testTalkLater_SchedulesAgentWakeupAndGoesQuiet() = runBlocking {
        val testDispatcher = UnconfinedTestDispatcher()
        val testScope = TestScope(testDispatcher)

        var scheduledWakeupId: String? = null
        var scheduledWakeupTime: Long? = null

        val fakeScheduler = object : AlarmScheduler {
            override val lastFiredId = MutableStateFlow<String?>(null)
            override fun schedule(reminder: Reminder) = ScheduleResult.Armed
            override fun cancel(reminderId: String) {}
            override fun scheduleAgentWakeup(wakeupId: String, fireAtMillis: Long): ScheduleResult {
                scheduledWakeupId = wakeupId
                scheduledWakeupTime = fireAtMillis
                return ScheduleResult.Armed
            }
            override fun cancelAgentWakeup(wakeupId: String) {}
        }

        val fakeTransport = object : HttpTransport {
            override suspend fun postStream(target: Target, body: String): Flow<String> = flow {
                emit("""data: {"choices":[{"delta":{"content":"{\"reply\":\"ok\",\"calls\":[]}"}}]}""")
            }
        }

        val rateLimiter = RateLimiter(clock = { clock.now() })
        val orchestrator = ProviderOrchestrator(rateLimiter, fakeTransport)
        val contextBuilder = ContextBuilder(storage, clock, dateResolver, checkinBuilder)
        val gateway = Gateway(storage, Resolver(), dateResolver, clock, alarmScheduler = fakeScheduler)

        val controller = ChatController(
            storage = storage,
            orchestrator = orchestrator,
            contextBuilder = contextBuilder,
            gateway = gateway,
            alarmScheduler = fakeScheduler,
            coroutineScope = testScope,
            defaultDispatcher = testDispatcher,
            clock = clock,
            dateResolver = dateResolver,
            networkCheck = { true }
        )

        // User responds with "talk later"
        controller.onAction(UiAction.UpdateDraft("talk later"))
        controller.onAction(UiAction.SendMessage)

        // Verify scheduled agent wakeup 2 hours later
        assertNotNull(scheduledWakeupId)
        assertEquals(clock.now() + 2 * 3600_000L, scheduledWakeupTime)

        // Verify stored deferred wakeup
        val wakeup = storage.getAgentWakeup(scheduledWakeupId!!)
        assertNotNull(wakeup)
        assertEquals("Checking back in—are you free to chat now?", wakeup!!.reason)

        // Verify assistant acknowledged gracefully
        val lastMsg = controller.state.value.chatMessages.lastOrNull()
        assertNotNull(lastMsg)
        assertEquals("assistant", lastMsg!!.role)
        assertTrue(lastMsg.content!!.contains("couple of hours"))
    }

    @Test
    fun testBreakRegex_SchedulesAgentWakeupAndSetsBreakSetting() = runBlocking {
        // 1. Verify Regex matches across expected phrasings
        assertTrue(ChatController.BREAK_REGEX.containsMatchIn("taking a 15 min break"))
        assertTrue(ChatController.BREAK_REGEX.containsMatchIn("starting a 20 minute break"))
        assertTrue(ChatController.BREAK_REGEX.containsMatchIn("on a 30 mins break"))
        assertTrue(ChatController.BREAK_REGEX.containsMatchIn("start a 5 minutes break"))
        assertTrue(ChatController.BREAK_REGEX.containsMatchIn("I'm taking a 25 min break"))

        val testDispatcher = UnconfinedTestDispatcher()
        val testScope = TestScope(testDispatcher)

        var scheduledWakeupId: String? = null
        var scheduledWakeupTime: Long? = null

        val fakeScheduler = object : AlarmScheduler {
            override val lastFiredId = MutableStateFlow<String?>(null)
            override fun schedule(reminder: Reminder) = ScheduleResult.Armed
            override fun cancel(reminderId: String) {}
            override fun scheduleAgentWakeup(wakeupId: String, fireAtMillis: Long): ScheduleResult {
                scheduledWakeupId = wakeupId
                scheduledWakeupTime = fireAtMillis
                return ScheduleResult.Armed
            }
            override fun cancelAgentWakeup(wakeupId: String) {}
        }

        val fakeTransport = object : HttpTransport {
            override suspend fun postStream(target: Target, body: String): Flow<String> = flow {
                emit("""data: {"choices":[{"delta":{"content":"{\"reply\":\"ok\",\"calls\":[]}"}}]}""")
            }
        }

        val rateLimiter = RateLimiter(clock = { clock.now() })
        val orchestrator = ProviderOrchestrator(rateLimiter, fakeTransport)
        val contextBuilder = ContextBuilder(storage, clock, dateResolver, checkinBuilder)
        val gateway = Gateway(storage, Resolver(), dateResolver, clock, alarmScheduler = fakeScheduler)

        val controller = ChatController(
            storage = storage,
            orchestrator = orchestrator,
            contextBuilder = contextBuilder,
            gateway = gateway,
            alarmScheduler = fakeScheduler,
            coroutineScope = testScope,
            defaultDispatcher = testDispatcher,
            clock = clock,
            dateResolver = dateResolver,
            networkCheck = { true }
        )

        // User says they are taking a 25-minute break
        controller.onAction(UiAction.UpdateDraft("taking a 25 min break"))
        controller.onAction(UiAction.SendMessage)

        // Verify break.until_ms setting saved
        val expectedUntilMs = clock.now() + 25 * 60_000L
        assertEquals(expectedUntilMs.toString(), storage.getSetting("break.until_ms"))

        // Verify scheduled agent wakeup
        assertNotNull(scheduledWakeupId)
        assertTrue(scheduledWakeupId!!.startsWith("aw_break_"))
        assertEquals(expectedUntilMs, scheduledWakeupTime)

        // Verify stored wakeup record
        val wakeup = storage.getAgentWakeup(scheduledWakeupId!!)
        assertNotNull(wakeup)
        assertEquals("Your 25-minute break is up! Ready to jump back in?", wakeup!!.reason)
        assertEquals("pending", wakeup.state)

        // Verify assistant acknowledgment
        val lastMsg = controller.state.value.chatMessages.lastOrNull()
        assertNotNull(lastMsg)
        assertEquals("assistant", lastMsg!!.role)
        assertTrue(lastMsg.content!!.contains("Enjoy your 25-minute break!"))

        // Test SetContextSensing action wiring
        var sensingCallbackInvoked = false
        controller.onContextSensingChanged = { enabled ->
            sensingCallbackInvoked = enabled
        }
        controller.onAction(UiAction.SetContextSensing(true))
        assertTrue(controller.state.value.contextSensingEnabled)
        assertEquals("1", storage.getSetting("flag.sensing"))
        assertEquals("1", storage.getSetting("sensing.enabled"))
        assertTrue(sensingCallbackInvoked)

        controller.onAction(UiAction.SetContextSensing(false))
        org.junit.Assert.assertFalse(controller.state.value.contextSensingEnabled)
        assertEquals("0", storage.getSetting("flag.sensing"))
        assertEquals("0", storage.getSetting("sensing.enabled"))
        org.junit.Assert.assertFalse(sensingCallbackInvoked)
    }

    @Test
    fun testTickProactive_WhenNoOverdueItems_DeliversGroundedCasualCheckin() = runBlocking {
        val testDispatcher = UnconfinedTestDispatcher()
        val testScope = TestScope(testDispatcher)

        val fakeScheduler = object : AlarmScheduler {
            override val lastFiredId = MutableStateFlow<String?>(null)
            override fun schedule(reminder: Reminder) = ScheduleResult.Armed
            override fun cancel(reminderId: String) {}
        }
        val fakeTransport = object : HttpTransport {
            override suspend fun postStream(target: Target, body: String): Flow<String> = flow {
                emit("""data: {"choices":[{"delta":{"content":"{\"reply\":\"ok\",\"calls\":[]}"}}]}""")
            }
        }
        val rateLimiter = RateLimiter(clock = { clock.now() })
        val orchestrator = ProviderOrchestrator(rateLimiter, fakeTransport)
        val contextBuilder = ContextBuilder(storage, clock, dateResolver, checkinBuilder)
        val gateway = Gateway(storage, Resolver(), dateResolver, clock, alarmScheduler = fakeScheduler)

        // Save DayRule covering the full 24h
        storage.saveDayRule(
            com.anchor.core.models.DayRule(
                id = "r_test_full",
                effectiveFrom = 0L,
                startMinute = 0,
                endMinute = 1440,
                tzId = "UTC",
                createdAt = 0L
            )
        )

        // Seed a pinned topic note
        val note = TopicNote(
            id = "tn_japan",
            topic = "Japan Trip 2027",
            content = "- Book bullet train passes\n- Reserve ryokan in Kyoto",
            bulletsJson = json.encodeToString(listOf("Book bullet train passes", "Reserve ryokan in Kyoto")),
            pinned = true,
            lastEditedAt = clock.now(),
            createdAt = clock.now()
        )
        storage.saveTopicNote(note)

        // Ensure no active overdue tasks exist
        storage.activeNodes.clear()

        // Set last contact to 4 hours ago so threshold is low (9.0 <= 22.0)
        storage.saveProactiveState(
            storage.getProactiveState().copy(
                lastContactAt = clock.now() - 4 * 3600_000L,
                backoffStep = 0
            )
        )

        val controller = ChatController(
            storage = storage,
            orchestrator = orchestrator,
            contextBuilder = contextBuilder,
            gateway = gateway,
            alarmScheduler = fakeScheduler,
            coroutineScope = testScope,
            defaultDispatcher = testDispatcher,
            clock = clock,
            dateResolver = dateResolver,
            networkCheck = { true },
            checkinBuilder = checkinBuilder
        )

        // Fire TickProactive
        controller.onAction(UiAction.TickProactive)

        // Verify casual check-in was delivered into messages
        val messages = storage.getRecentMessages(10)
        val casualMsg = messages.lastOrNull { it.role == "assistant" }
        assertNotNull("Assistant casual checkin must be saved to messages", casualMsg)
        assertTrue(casualMsg!!.content!!.contains("Japan Trip 2027"))
        assertTrue(casualMsg.content!!.contains("Book bullet train passes"))
        assertEquals("sent", controller.state.value.nudgeGate)
    }
}
