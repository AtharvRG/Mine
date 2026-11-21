// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.sensing

import com.anchor.core.SharedFakeStorage
import com.anchor.core.alarms.AlarmScheduler
import com.anchor.core.alarms.ScheduleResult
import com.anchor.core.gateway.Gateway
import com.anchor.core.gateway.ToolCall
import com.anchor.core.models.Node
import com.anchor.core.models.Reminder
import com.anchor.core.models.ToolResult
import com.anchor.core.models.TopicNote
import com.anchor.core.resolver.Resolver
import com.anchor.core.time.DateResolver
import com.anchor.core.time.FakeClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OpportunisticErrandsAndWalkTest {

    private lateinit var clock: FakeClock
    private lateinit var storage: SharedFakeStorage
    private lateinit var contextEngine: ContextEngine
    private lateinit var gateway: Gateway
    private val json = Json { ignoreUnknownKeys = true }

    private val homePlace = Place("p_home", null, "home", "Home", 1000L)
    private val homeSignal = PlaceSignal("p_home", wifiJson = """{"ssid":"HomeWiFi","bssids":["aa:bb:cc:dd:ee:ff"]}""")

    @Before
    fun setUp() {
        clock = FakeClock(1700000000000L)
        storage = SharedFakeStorage(clock)
        val dateResolver = DateResolver(clock, { false })
        gateway = Gateway(storage, Resolver(), dateResolver, clock)
        contextEngine = ContextEngine(clock)
    }

    @Test
    fun test5GIndoorWalkGuard_SuppressesAwayTransitionWhenStepsDoNotAdvance() {
        val now = clock.now()
        // 1. Establish at Home on Wi-Fi
        contextEngine.onWifiSignal("HomeWiFi", listOf("aa:bb:cc:dd:ee:ff"), listOf(homePlace to homeSignal), now)
        clock.currentTime += 3 * 60_000L
        contextEngine.checkTimeouts(clock.now())
        assertEquals(PlaceAxis.Home, contextEngine.getState().place)

        // Initial step count: 1200 steps
        contextEngine.onStepCountSignal(1200L, clock.now())

        // 2. Wi-Fi drops (e.g. 5G handover indoors)
        contextEngine.onWifiSignal(null, emptyList(), listOf(homePlace to homeSignal), clock.now())

        // 3. Sensor falsely reports WALKING for 90s, but step counter remains 1200 (delta = 0)
        contextEngine.onMotionSignal(MotionAxis.WALKING, 1.0f, clock.now())
        clock.currentTime += 100_000L
        val triggers = contextEngine.checkTimeouts(clock.now())

        // 5G Indoor Guard must hold at Home because steps did not advance (< 40)
        assertFalse("Must NOT fire CONTEXT_LEFT_HOME without physical steps", triggers.contains(ContextTrigger.CONTEXT_LEFT_HOME))
        assertEquals("Must remain at Home under 5G indoor guard", PlaceAxis.Home, contextEngine.getState().place)
    }

    @Test
    fun test5GIndoorWalkGuard_AllowsAwayTransitionWhenRealStepsTaken() {
        val now = clock.now()
        // 1. Establish at Home on Wi-Fi
        contextEngine.onWifiSignal("HomeWiFi", listOf("aa:bb:cc:dd:ee:ff"), listOf(homePlace to homeSignal), now)
        clock.currentTime += 3 * 60_000L
        contextEngine.checkTimeouts(clock.now())
        assertEquals(PlaceAxis.Home, contextEngine.getState().place)

        contextEngine.onStepCountSignal(1200L, clock.now())

        // 2. Wi-Fi drops
        contextEngine.onWifiSignal(null, emptyList(), listOf(homePlace to homeSignal), clock.now())
        contextEngine.onMotionSignal(MotionAxis.WALKING, 1.0f, clock.now())

        // 3. User actually takes 85 steps out the front door
        clock.currentTime += 95_000L
        val triggers = mutableListOf<ContextTrigger>()
        triggers.addAll(contextEngine.onStepCountSignal(1285L, clock.now()))
        triggers.addAll(contextEngine.checkTimeouts(clock.now()))

        // Verified with real steps -> transitions Away and fires CONTEXT_LEFT_HOME
        assertTrue("Must fire CONTEXT_LEFT_HOME when steps confirm movement", triggers.contains(ContextTrigger.CONTEXT_LEFT_HOME))
        assertEquals(PlaceAxis.Away, contextEngine.getState().place)
    }

    @Test
    fun testOpportunisticErrandsEvaluator_SurfacesErrandsAndChecklistsOnLeaveHome() = runBlocking {
        // Create an errand with context_trigger = "ON_LEAVE_HOME"
        val call = ToolCall(
            tool = "propose_change",
            args = mapOf(
                "intent" to JsonPrimitive("CREATE"),
                "title" to JsonPrimitive("Drop off parcel at post office"),
                "context_trigger" to JsonPrimitive("ON_LEAVE_HOME")
            )
        )
        val res = gateway.executeTool(call)
        assertTrue(res is ToolResult.Committed)
        val errandId = (res as ToolResult.Committed).targetId

        val errandNode = storage.getNode(errandId)
        assertNotNull(errandNode)
        assertEquals("ON_LEAVE_HOME", errandNode!!.contextTrigger)
        assertNotNull("Fallback fire time must be set", errandNode.fallbackFireAt)

        val errandWakeup = storage.getAgentWakeup("aw_errand_$errandId")
        assertNotNull("AgentWakeup for errand fallback must be scheduled", errandWakeup)
        assertEquals(errandNode.fallbackFireAt, errandWakeup!!.fireAt)

        // Create and link a living note checklist for this errand
        val note = TopicNote(
            id = "tn_parcel_1",
            topic = "Parcel Checklist",
            content = "- Return label\n- Tape box\n- Receipt",
            bulletsJson = json.encodeToString(listOf("Return label", "Tape box", "Receipt")),
            pinned = false,
            lastEditedAt = clock.now(),
            createdAt = clock.now()
        )
        storage.saveTopicNote(note)
        gateway.executeTool(
            ToolCall(
                tool = "link_note_reference",
                args = mapOf(
                    "note_id" to JsonPrimitive("tn_parcel_1"),
                    "node_id" to JsonPrimitive(errandId)
                )
            )
        )

        // Evaluate leave home
        val evaluator = OpportunisticErrandsEvaluator(storage, clock)
        val nudges = evaluator.evaluateLeaveHome(clock.now())

        assertEquals(1, nudges.size)
        val nudge = nudges[0]
        assertEquals(errandId, nudge.nodeId)
        assertEquals("Drop off parcel at post office", nudge.title)
        assertEquals(listOf("Return label", "Tape box", "Receipt"), nudge.checklist)
        assertTrue(nudge.message.contains("Return label"))
    }

    @Test
    fun testWalkCompanionEvaluator_WalkStartedAndReturnGreeting() = runBlocking {
        var scheduledWakeupId: String? = null
        var scheduledWakeupTime: Long? = null

        val testScheduler = object : AlarmScheduler {
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

        val evaluator = WalkCompanionEvaluator(storage, clock, testScheduler)

        // 1. Walk started
        val greeting = evaluator.evaluateWalkStarted(clock.now())
        assertTrue(greeting.message.contains("enjoy your stroll"))
        assertNotNull(scheduledWakeupId)
        assertEquals(clock.now() + 35 * 60_000L, scheduledWakeupTime)

        // Verify AgentWakeup persisted in storage
        val wakeup = storage.getAgentWakeup(greeting.scheduledReturnWakeupId)
        assertNotNull(wakeup)
        assertEquals("Walk return check-in", wakeup!!.reason)

        // 2. Return evaluation when home
        val homeState = ContextState(place = PlaceAxis.Home, motion = MotionAxis.STILL)
        val returnGreeting = evaluator.evaluateWalkReturn(wakeup, homeState)
        assertNotNull(returnGreeting)
        assertTrue(returnGreeting!!.contains("Welcome back from your walk"))

        // 3. Return evaluation if still away (suppressed)
        val awayState = ContextState(place = PlaceAxis.Away, motion = MotionAxis.WALKING)
        val awayGreeting = evaluator.evaluateWalkReturn(wakeup, awayState)
        assertEquals(null, awayGreeting)
    }
}
