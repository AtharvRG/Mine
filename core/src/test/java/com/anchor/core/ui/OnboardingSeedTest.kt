// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.ui

import com.anchor.core.SharedFakeStorage
import com.anchor.core.alarms.AlarmScheduler
import com.anchor.core.alarms.ScheduleResult
import com.anchor.core.context.ContextBuilder
import com.anchor.core.gateway.Gateway
import com.anchor.core.models.Reminder
import com.anchor.core.nudge.CheckinBuilder
import com.anchor.core.provider.HttpTransport
import com.anchor.core.provider.ProviderOrchestrator
import com.anchor.core.provider.RateLimiter
import com.anchor.core.provider.Target
import com.anchor.core.resolver.Resolver
import com.anchor.core.time.DateResolver
import com.anchor.core.time.DayRollover
import com.anchor.core.time.FakeClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Blueprint 52 §3.1/§3.2, the audit's §6 item 3: onboarding completion writes the seeded Soul facts.
 *
 * What these pin, in order of how badly it would hurt to get wrong: a fresh install is shown the
 * flow and a finished one is not; the answers land in `soul_fact` as the user's own words (active,
 * `source = onboarding`) with the health note's local-only switch honoured; and a failure part-way
 * leaves the flow open rather than marking a half-seeded Soul as done.
 */
class OnboardingSeedTest {

    private class NoTransport : HttpTransport {
        override suspend fun postStream(target: Target, body: String): Flow<String> = flow {
            throw AssertionError("onboarding must not call the model")
        }
    }

    private fun controller(storage: SharedFakeStorage, clock: FakeClock): ChatController {
        val dateResolver = DateResolver(clock)
        return ChatController(
            storage,
            ProviderOrchestrator(RateLimiter { clock.now() }, NoTransport()),
            ContextBuilder(storage, clock, dateResolver, CheckinBuilder(storage, clock, dateResolver)),
            Gateway(storage, Resolver(), dateResolver, clock),
            object : AlarmScheduler {
                override val lastFiredId = MutableStateFlow<String?>(null)
                override fun schedule(reminder: Reminder) = ScheduleResult.Armed
                override fun cancel(reminderId: String) {}
            },
            CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined, clock
        )
    }

    private fun seeded(storage: SharedFakeStorage, clock: FakeClock): ChatController {
        val controller = controller(storage, clock)
        controller.onAction(UiAction.OnboardingAnswer("identity", "I am a nurse on night shifts"))
        controller.onAction(UiAction.OnboardingAnswer("communication_style", "Short replies please"))
        controller.onAction(UiAction.OnboardingAnswer("health", "Migraines, please dim the tone"))
        controller.onAction(UiAction.OnboardingAnswer("dislikes", "   "))
        return controller
    }

    @Test
    fun freshInstall_ShowsTheFlow_AndAFinishedOneDoesNot() {
        val storage = SharedFakeStorage()
        val clock = FakeClock(1000L)

        assertFalse("a fresh install is never done", controller(storage, clock).state.value.onboardingDone)

        storage.settings[ChatController.ONBOARDING_DONE_KEY] = "1"
        assertTrue("a finished install never sees the flow again", controller(storage, clock).state.value.onboardingDone)
    }

    @Test
    fun finish_WritesTheSeededFactsAsTheUsersOwnWords() {
        val storage = SharedFakeStorage()
        val clock = FakeClock(5000L)
        val controller = seeded(storage, clock)

        controller.onAction(UiAction.OnboardingFinish)

        val state = controller.state.value
        assertTrue("finishing marks the flow done", state.onboardingDone)
        assertNull(state.onboardingError)
        assertEquals("the done flag is persisted, not just in memory", "1", storage.settings[ChatController.ONBOARDING_DONE_KEY])

        val facts = storage.soulFacts
        assertEquals("a blank answer writes nothing", 3, facts.size)
        assertEquals(
            "one fact per answered category, in the order they were asked",
            listOf("identity", "communication_style", "health"),
            facts.map { it.category }
        )
        assertEquals("I am a nurse on night shifts", facts[0].statement)
        for (fact in facts) {
            assertEquals("onboarding", fact.source)
            assertEquals("approved", fact.origin)
            assertEquals("active", fact.status)
            assertEquals(ChatController.ONBOARDING_FACT_CONFIDENCE, fact.confidence, 0.0)
            assertEquals(5000L, fact.createdAt)
        }
        assertEquals("every seed is audited", 3, storage.loggedEvents.count { it.targetType == "soul_fact" })
    }

    @Test
    fun healthAnswer_IsSensitive_AndLocalOnlyOnlyWhenTheSwitchIsOn() {
        val storage = SharedFakeStorage()
        val clock = FakeClock(5000L)
        val controller = seeded(storage, clock)

        controller.onAction(UiAction.OnboardingFinish)

        val health = storage.soulFacts.single { it.category == "health" }
        assertTrue("the health note is always sensitive", health.sensitive)
        assertFalse("the switch defaults off", health.localOnly)
        assertFalse("a non-health answer is not sensitive", storage.soulFacts.single { it.category == "identity" }.sensitive)

        val localStorage = SharedFakeStorage()
        val localController = seeded(localStorage, clock)
        localController.onAction(UiAction.OnboardingHealthLocalOnly(true))
        localController.onAction(UiAction.OnboardingFinish)

        assertTrue(
            "with the switch on, the health note never leaves the phone",
            localStorage.soulFacts.single { it.category == "health" }.localOnly
        )
    }

    @Test
    fun finish_WhenTheWriteFails_LeavesTheFlowOpen() {
        val storage = object : SharedFakeStorage() {
            override suspend fun createSoulFactAndCommit(fact: com.anchor.core.models.SoulFact, event: com.anchor.core.models.RawEvent) {
                throw IllegalStateException("disk full")
            }
        }
        val clock = FakeClock(5000L)
        val controller = seeded(storage, clock)

        controller.onAction(UiAction.OnboardingFinish)

        val state = controller.state.value
        assertFalse("a failed seed must not mark onboarding done", state.onboardingDone)
        assertTrue("the failure is reported", state.onboardingError!!.contains("disk full"))
        assertNull("and the done flag was never written", storage.settings[ChatController.ONBOARDING_DONE_KEY])
        assertTrue("nothing was half-seeded either", storage.soulFacts.isEmpty())
    }

    @Test
    fun finish_WritesTheDoneFlag_AsTheVeryLastWrite() {
        // The failure test above only proves the flag is absent after a failed run — which would also
        // hold if the flag were never written at all. This one pins that the flag is written, and
        // written after the facts, so a crash mid-seed can never leave a done flow over a partial Soul.
        val storage = object : SharedFakeStorage() {
            override suspend fun saveSetting(key: String, value: String) {
                if (key == ChatController.ONBOARDING_DONE_KEY) {
                    assertEquals("every fact is committed before the flow is marked done", 3, soulFacts.size)
                }
                super.saveSetting(key, value)
            }
        }
        val controller = seeded(storage, FakeClock(5000L))

        controller.onAction(UiAction.OnboardingFinish)

        assertEquals("1", storage.settings[ChatController.ONBOARDING_DONE_KEY])
        assertTrue(controller.state.value.onboardingDone)
    }

    @Test
    fun nextAndBack_PersistTheStep_SoAClosedAppResumesWhereItStopped() {
        val storage = SharedFakeStorage()
        val clock = FakeClock(1000L)
        val controller = controller(storage, clock)

        controller.onAction(UiAction.OnboardingNext)
        controller.onAction(UiAction.OnboardingNext)
        assertEquals(2, controller.state.value.onboardingStep)
        assertEquals("2", storage.settings[ChatController.ONBOARDING_STEP_KEY])

        controller.onAction(UiAction.OnboardingBack)
        assertEquals(1, controller.state.value.onboardingStep)
        assertEquals("1", storage.settings[ChatController.ONBOARDING_STEP_KEY])

        controller.onAction(UiAction.OnboardingBack)
        controller.onAction(UiAction.OnboardingBack)
        assertEquals("the flow does not run off the front", 0, controller.state.value.onboardingStep)

        repeat(10) { controller.onAction(UiAction.OnboardingNext) }
        assertEquals("nor off the end", ChatController.ONBOARDING_LAST_STEP, controller.state.value.onboardingStep)
    }

    @Test
    fun confirmDay_WritesTheFirstDayRule_AndMovesToTheSoulStep() {
        val storage = SharedFakeStorage()
        val clock = FakeClock(1000L)
        val controller = controller(storage, clock)

        controller.onAction(UiAction.OnboardingConfirmDay(startMinute = 60, endMinute = 1380))

        val rule = storage.dayRules.single()
        assertEquals(60, rule.startMinute)
        assertEquals(1380, rule.endMinute)
        assertEquals(ChatController.ONBOARDING_SOUL_STEP, controller.state.value.onboardingStep)
        assertNull(controller.state.value.onboardingError)
    }

    @Test
    fun confirmDay_ZeroHourWindow_IsRefused_AndWritesNothing() {
        val storage = SharedFakeStorage()
        val controller = controller(storage, FakeClock(1000L))

        controller.onAction(UiAction.OnboardingConfirmDay(startMinute = 240, endMinute = 240))

        assertTrue(storage.dayRules.isEmpty())
        assertEquals(0, controller.state.value.onboardingStep)
        assertTrue(controller.state.value.onboardingError!!.contains("zero hours"))
    }

    @Test
    fun confirmDay_DefaultsAreTheDayRolloverDefaults() {
        val storage = SharedFakeStorage()
        val controller = controller(storage, FakeClock(1000L))

        controller.onAction(
            UiAction.OnboardingConfirmDay(
                DayRollover.DEFAULT_START_MINUTE,
                DayRollover.DEFAULT_END_MINUTE
            )
        )

        val rule = storage.dayRules.single()
        assertEquals("04:00", "%02d:%02d".format(rule.startMinute / 60, rule.startMinute % 60))
        assertEquals("22:00", "%02d:%02d".format(rule.endMinute / 60, rule.endMinute % 60))
    }
}
