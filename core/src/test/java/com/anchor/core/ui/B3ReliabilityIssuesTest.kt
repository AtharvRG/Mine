// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.ui

import com.anchor.core.SharedFakeStorage
import com.anchor.core.alarms.AlarmReconciler
import com.anchor.core.alarms.AlarmScheduler
import com.anchor.core.alarms.ReliabilityIssue
import com.anchor.core.alarms.ScheduleResult
import com.anchor.core.context.ContextBuilder
import com.anchor.core.gateway.Gateway
import com.anchor.core.models.Reminder
import com.anchor.core.models.ReliabilityState
import com.anchor.core.nudge.CheckinBuilder
import com.anchor.core.provider.HttpTransport
import com.anchor.core.provider.ProviderOrchestrator
import com.anchor.core.provider.RateLimiter
import com.anchor.core.provider.Target
import com.anchor.core.resolver.Resolver
import com.anchor.core.time.DateResolver
import com.anchor.core.time.FakeClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class B3ReliabilityIssuesTest {

    private val healthReason = "SecurityException: exact alarm permission missing"
    private val scheduleReason = "Schedule engine offline"

    private suspend fun waitUntil(timeoutMs: Long = 1000L, condition: suspend () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) {
                org.junit.Assert.fail("Timed out waiting for condition after ${timeoutMs}ms")
            }
            kotlinx.coroutines.yield()
            kotlinx.coroutines.delay(5)
        }
    }

    private class ConfigurableScheduler(
        private val healthReason: String? = null,
        private val scheduleReason: String? = null
    ) : AlarmScheduler {
        override val lastFiredId = MutableStateFlow<String?>(null)
        override fun schedule(reminder: Reminder): ScheduleResult =
            if (scheduleReason != null) ScheduleResult.Failed(scheduleReason) else ScheduleResult.Armed
        override fun cancel(reminderId: String) {}
        override fun checkHealth(): ScheduleResult =
            if (healthReason != null) ScheduleResult.Failed(healthReason) else ScheduleResult.Armed
    }

    private fun doneTransport(): HttpTransport = object : HttpTransport {
        override suspend fun postStream(target: Target, body: String): Flow<String> = flow {
            emit("data: [DONE]")
        }
    }

    private fun createController(
        storage: SharedFakeStorage,
        clock: FakeClock,
        dateResolver: DateResolver,
        scheduler: AlarmScheduler
    ): ChatController {
        val orchestrator = ProviderOrchestrator(RateLimiter { clock.now() }, doneTransport())
        val checkinBuilder = CheckinBuilder(storage, clock, dateResolver)
        val contextBuilder = ContextBuilder(storage, clock, dateResolver, checkinBuilder)
        val gateway = Gateway(storage, Resolver(), dateResolver, clock)
        return ChatController(
            storage, orchestrator, contextBuilder, gateway,
            scheduler, CoroutineScope(Dispatchers.Unconfined),
            Dispatchers.Unconfined, clock,
            dateResolver = dateResolver
        )
    }

    private fun decodeIssues(issuesJson: String): List<ReliabilityIssue> =
        Json.decodeFromString(ListSerializer(ReliabilityIssue.serializer()), issuesJson)

    @Test
    fun testHealthFailure_WritesStructuredIssueJson_AndHumanReadableBanner() = runBlocking {
        val clock = FakeClock(1700000000000L)
        val storage = SharedFakeStorage(clock)
        val dateResolver = DateResolver(clock) { true }
        val controller = createController(storage, clock, dateResolver, ConfigurableScheduler(healthReason = healthReason))

        waitUntil { storage.getReliabilityState().issuesJson != null }
        val issuesJson = storage.getReliabilityState().issuesJson!!

        // B3: the controller-written issuesJson must be the SAME structured format
        // AlarmReconciler writes: a JSON array of {error, time} objects.
        val issues = decodeIssues(issuesJson)
        assertTrue("Controller must record the health failure as a structured issue", issues.any { it.error == healthReason })

        // B3: the user-facing schedulingError banner must be human-readable text
        // decoded from the issues, never raw JSON. providerEndpoint guard ensures
        // we read the final init state update, not an intermediate one.
        waitUntil { controller.state.value.providerEndpoint.isNotBlank() && controller.state.value.schedulingError != null }
        val banner = controller.state.value.schedulingError!!
        assertTrue("Banner must contain the failure reason", banner.contains(healthReason))
        assertFalse("Banner must not leak raw JSON array syntax", banner.contains("[\""))
        assertFalse("Banner must not leak raw JSON object syntax", banner.contains("{\"error\""))
    }

    @Test
    fun testReconcilerPreservesControllerWrittenIssue() = runBlocking {
        val clock = FakeClock(1700000000000L)
        val storage = SharedFakeStorage(clock)
        val dateResolver = DateResolver(clock) { true }
        createController(storage, clock, dateResolver, ConfigurableScheduler(healthReason = healthReason))
        waitUntil { storage.getReliabilityState().issuesJson?.contains(healthReason) == true }

        // A later reconcile with a schedule() failure must PRESERVE the
        // controller-written issue (same format), not silently drop it.
        storage.saveReminder(Reminder("r_new", "New Task", clock.now() + 5000L))
        val reconciler = AlarmReconciler(storage, ConfigurableScheduler(scheduleReason = scheduleReason), clock)
        reconciler.reconcile(force = true)

        val issues = decodeIssues(storage.getReliabilityState().issuesJson!!)
        assertTrue("Reconciler must preserve the controller-written health issue", issues.any { it.error == healthReason })
        assertTrue("Reconciler must record its own scheduling failure", issues.any { it.error == scheduleReason })
    }

    @Test
    fun testBothWritersAppend_IssuesBoundedToFiveStructuredObjects() = runBlocking {
        val clock = FakeClock(1700000000000L)
        val storage = SharedFakeStorage(clock)
        val dateResolver = DateResolver(clock) { true }

        // Pre-seed 4 valid issues (as a previous AlarmReconciler pass would write)
        val seeded = (1..4).map { ReliabilityIssue("Pre-seeded issue $it", clock.now() - it * 1000L) }
        storage.reliabilityState = ReliabilityState(
            score = "at_risk",
            issuesJson = Json.encodeToString(ListSerializer(ReliabilityIssue.serializer()), seeded),
            lastCheckAt = clock.now()
        )
        storage.saveReminder(Reminder("r1", "Task", clock.now() + 5000L))

        // Controller init: reconciler schedule() fails (+1), health fails (+1) => 6 candidates
        createController(storage, clock, dateResolver, ConfigurableScheduler(healthReason = healthReason, scheduleReason = scheduleReason))
        waitUntil { storage.getReliabilityState().issuesJson?.contains(healthReason) == true }

        val issues = decodeIssues(storage.getReliabilityState().issuesJson!!)
        assertTrue("Issues must be bounded to 5, got ${issues.size}", issues.size <= 5)
        assertTrue("Health issue must survive bounding", issues.any { it.error == healthReason })
        assertTrue("Reconciler issue must survive bounding", issues.any { it.error == scheduleReason })
    }
}
