// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.mine.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
import com.anchor.core.time.FakeClock
import com.anchor.core.ui.ChatController
import com.anchor.core.ui.UiAction
import com.anchor.mine.storage.AndroidStorage
import com.anchor.mine.storage.DatabaseProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Blueprint 52 §3.1/§3.2, audit §6 item 3: **fresh DB → onboarding completion writes the seeded
 * soul facts**, through the real SQLCipher storage rather than a fake.
 *
 * [com.anchor.core.ui.OnboardingSeedTest] proves the controller's logic against an in-memory
 * double. This proves the thing the double cannot: that the rows actually land in `soul_fact` (with
 * `sensitive`/`local_only` as booleans the schema accepts), are readable back through the same
 * `getActiveSoulFacts` the Memory tab and the context builder use, and that `onboarding.done` really
 * survives in `app_meta` so a relaunch does not show the flow again.
 */
@RunWith(AndroidJUnit4::class)
class OnboardingSeedConnectedTest {

    private lateinit var storage: AndroidStorage
    private lateinit var dbHelper: androidx.sqlite.db.SupportSQLiteOpenHelper
    private val clock = FakeClock(1_700_000_000_000L)

    /** Onboarding must never reach the model; a call here is a bug, not a slow test. */
    private class NoTransport : HttpTransport {
        override suspend fun postStream(target: Target, body: String): Flow<String> = flow {
            throw AssertionError("onboarding must not call the model")
        }
    }

    @Before
    fun setup() {
        com.anchor.mine.alarms.AlarmReceiver.lastFiredId = null
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase("mine_onboarding_test.db")
        dbHelper = DatabaseProvider.getHelper(context, ByteArray(32) { 7 }, "mine_onboarding_test.db")
        storage = AndroidStorage(dbHelper)
    }

    @After
    fun teardown() {
        dbHelper.close()
    }

    private fun controller(): ChatController {
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

    @Test
    fun freshDb_FinishingOnboarding_WritesTheSeededFactsToTheRealTables() = runBlocking {
        val controller = controller()
        assertFalse("a fresh database has not been onboarded", controller.state.value.onboardingDone)

        controller.onAction(UiAction.OnboardingAnswer("identity", "I am a night-shift nurse"))
        controller.onAction(UiAction.OnboardingAnswer("communication_style", "Short replies please"))
        controller.onAction(UiAction.OnboardingAnswer("health", "Migraines, dim the tone"))
        controller.onAction(UiAction.OnboardingHealthLocalOnly(true))
        controller.onAction(UiAction.OnboardingAnswer("dislikes", "   "))
        controller.onAction(UiAction.OnboardingFinish)
        // The controller writes on Dispatchers.IO, so the launch has not necessarily finished when
        // onAction returns. Wait for the state it publishes on success rather than reading blind.
        awaitDone(controller)

        assertNull("the seed must not report an error: ${controller.state.value.onboardingError}", controller.state.value.onboardingError)
        assertEquals("the done flag is in app_meta, so a relaunch skips the flow", "1", storage.getSetting(ChatController.ONBOARDING_DONE_KEY))

        // Read back through the production reader, not a raw query: this is what the Memory tab and
        // the context builder actually see.
        val active = storage.getActiveSoulFacts()
        assertEquals("the blank answer wrote nothing", 2, active.size)
        val identity = active.single { it.category == "identity" }
        assertEquals("I am a night-shift nurse", identity.statement)
        assertEquals("onboarding", identity.source)
        assertEquals("approved", identity.origin)
        assertEquals("active", identity.status)
        assertEquals(ChatController.ONBOARDING_FACT_CONFIDENCE, identity.confidence, 0.0)
        assertFalse("a non-health answer is not sensitive", identity.sensitive)
        assertFalse("nor local-only", identity.localOnly)
        assertEquals(clock.now(), identity.createdAt)

        // The health note was marked "keep on this phone only", so the active reader filters it out
        // by design (rule 6). Prove it is stored — and stored with both flags — rather than lost.
        val health = readSoulFactIgnoringLocalOnly(storage, "health")
        assertTrue("the health note is stored as sensitive", health.first)
        assertTrue("and honours the local-only switch", health.second)
    }

    @Test
    fun relaunch_AfterFinishing_DoesNotShowTheFlowAgain() = runBlocking {
        val first = controller()
        first.onAction(UiAction.OnboardingAnswer("identity", "I am a night-shift nurse"))
        first.onAction(UiAction.OnboardingFinish)
        awaitDone(first)

        // A second controller over the same database is what a relaunch looks like. Its first load
        // is async too, so wait for it to paint rather than reading the default UiState.
        val relaunched = controller()
        val deadline = System.currentTimeMillis() + 10_000
        while (!relaunched.state.value.onboardingDone && relaunched.state.value.globalError == null &&
            System.currentTimeMillis() < deadline
        ) {
            kotlinx.coroutines.delay(20)
        }
        assertTrue(
            "a finished install never sees the flow again: ${relaunched.state.value.globalError}",
            relaunched.state.value.onboardingDone
        )
    }

    @Test
    fun confirmDay_WritesTheRule_AndResumesAtTheSoulStep() = runBlocking {
        val controller = controller()
        controller.onAction(UiAction.OnboardingConfirmDay(startMinute = 60, endMinute = 1380))
        val deadline = System.currentTimeMillis() + 10_000
        while (controller.state.value.onboardingStep != ChatController.ONBOARDING_SOUL_STEP &&
            controller.state.value.onboardingError == null && System.currentTimeMillis() < deadline
        ) {
            kotlinx.coroutines.delay(20)
        }

        assertNull(controller.state.value.onboardingError)
        assertEquals(ChatController.ONBOARDING_SOUL_STEP, controller.state.value.onboardingStep)
        val rule = storage.getEffectiveDayRule(clock.now())
        assertEquals(60, rule.startMinute)
        assertEquals(1380, rule.endMinute)
        assertEquals("the step is persisted so a killed app resumes here", "3", storage.getSetting(ChatController.ONBOARDING_STEP_KEY))
    }

    /**
     * Waits for the controller's background write to publish its verdict. The controller writes on
     * Dispatchers.IO, so `onAction` returning does not mean the write finished — reading state
     * immediately is a race, and reading the database immediately is a race against the same job.
     */
    private suspend fun awaitDone(controller: ChatController) {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            val s = controller.state.value
            if (s.onboardingDone || s.onboardingError != null) return
            kotlinx.coroutines.delay(20)
        }
        throw AssertionError("onboarding finish never settled; state=${controller.state.value}")
    }

    /** Reads the stored flags for a category the active reader deliberately hides. */
    private fun readSoulFactIgnoringLocalOnly(storage: AndroidStorage, category: String): Pair<Boolean, Boolean> {
        val db = dbHelper.readableDatabase
        db.query(
            "SELECT sensitive, local_only FROM soul_fact WHERE category = ?",
            arrayOf(category)
        ).use {
            assertTrue("the $category fact is stored", it.moveToFirst())
            return (it.getInt(0) == 1) to (it.getInt(1) == 1)
        }
    }
}
