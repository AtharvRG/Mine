// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.gateway

import com.anchor.core.SharedFakeStorage
import com.anchor.core.alarms.AlarmScheduler
import com.anchor.core.alarms.ScheduleResult
import com.anchor.core.context.ContextBuilder
import com.anchor.core.models.Node
import com.anchor.core.models.RawEvent
import com.anchor.core.models.Reminder
import com.anchor.core.nudge.CheckinBuilder
import com.anchor.core.provider.HttpTransport
import com.anchor.core.provider.ProviderOrchestrator
import com.anchor.core.provider.RateLimiter
import com.anchor.core.provider.StreamEvent
import com.anchor.core.provider.Target
import com.anchor.core.rebuild.RebuildManager
import com.anchor.core.rebuild.RebuildStatus
import com.anchor.core.rebuild.RebuildTarget
import com.anchor.core.resolver.Resolver
import com.anchor.core.retrieval.HybridRetriever
import com.anchor.core.retrieval.PureKotlinEmbedder
import com.anchor.core.retrieval.SemanticProjection
import com.anchor.core.time.DateResolver
import com.anchor.core.time.FakeClock
import com.anchor.core.ui.ChatController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AuditRemediationPhase4Test {

    private lateinit var storage: SharedFakeStorage
    private lateinit var clock: FakeClock

    @Before
    fun setUp() {
        storage = SharedFakeStorage()
        clock = FakeClock(1700000000000L)
    }

    private fun createController(
        storage: SharedFakeStorage,
        clock: FakeClock,
        scope: CoroutineScope,
        dispatcher: kotlinx.coroutines.CoroutineDispatcher
    ): ChatController {
        val dateResolver = DateResolver(clock)
        val transport = object : HttpTransport {
            override suspend fun postStream(target: Target, body: String): Flow<String> = flow {}
        }
        return ChatController(
            storage,
            ProviderOrchestrator(RateLimiter { clock.now() }, transport),
            ContextBuilder(storage, clock, dateResolver, CheckinBuilder(storage, clock, dateResolver)),
            Gateway(storage, Resolver(), dateResolver, clock),
            object : AlarmScheduler {
                override val lastFiredId = MutableStateFlow<String?>(null)
                override fun schedule(reminder: Reminder) = ScheduleResult.Armed
                override fun cancel(reminderId: String) {}
            },
            scope,
            dispatcher,
            clock
        )
    }

    @Test
    fun testSemanticProjection_ExtendedDomains_ProducesHighSimilarity() {
        // Study domain
        val studyVec = SemanticProjection.projectSemanticVector("study for exam lecture")
        val researchVec = SemanticProjection.projectSemanticVector("homework book research university")
        val studySim = HybridRetriever.cosineSimilarity(studyVec, researchVec)
        assertTrue("Study and university research must have positive semantic similarity", studySim > 0.40f)

        // Family domain
        val familyVec = SemanticProjection.projectSemanticVector("family anniversary celebration")
        val kidsVec = SemanticProjection.projectSemanticVector("parents children birthday party")
        val familySim = HybridRetriever.cosineSimilarity(familyVec, kidsVec)
        assertTrue("Family and children birthday must have positive semantic similarity", familySim > 0.40f)

        // Legal domain
        val contractVec = SemanticProjection.projectSemanticVector("sign contract legal agreement")
        val lawyerVec = SemanticProjection.projectSemanticVector("lawyer document policy permit")
        val legalSim = HybridRetriever.cosineSimilarity(contractVec, lawyerVec)
        assertTrue("Contract and lawyer document must have positive semantic similarity", legalSim > 0.40f)
    }

    @Test
    fun testRebuildManager_ExecuteRebuild_FactsTarget() = runTest {
        val manager = RebuildManager(storage, clock)

        // Seed 3 closed study tasks in past week
        for (i in 1..3) {
            val node = Node("n$i", "task", "Study chapter $i", "done", closedAt = clock.now() - 1000L * i)
            storage.createNodeAndCommit(node, RawEvent("e$i", "CREATE", "node", "n$i", tsUtc = clock.now()), null)
        }

        val plan = manager.planRebuild(RebuildTarget.DERIVED_NODES)
        val progress = manager.executeRebuild(plan)
        assertEquals(RebuildStatus.COMPLETED, progress.status)

        // Verify proposed soul fact was generated
        val proposedFacts = storage.getProposedSoulFacts()
        assertTrue("Proposed soul facts must be generated from completed study habits", proposedFacts.isNotEmpty())
        assertTrue("Proposed fact must mention continuous self-learning", proposedFacts.any { it.statement.contains("continuous self-learning") })
    }

    @Test
    fun testChatController_WorkerAiDebounce_ExtractsFactsAfterDelay() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)

        val controller = createController(storage, clock, testScope, testDispatcher)

        // Seed completed habit with streak 5
        val habit = Node("h1", "habit", "Daily Meditation", "done", streakCurrent = 5, closedAt = clock.now() - 500L)
        storage.createNodeAndCommit(habit, RawEvent("e_h1", "CREATE", "node", "h1", tsUtc = clock.now()), null)

        // Trigger debounced Worker AI (30s)
        controller.triggerWorkerAiDebounced(debounceMs = 30_000L)

        // At 10s: still waiting, no proposed facts yet
        testScope.advanceTimeBy(10_000L)
        assertEquals(0, storage.getProposedSoulFacts().size)

        // At 35s: debounce expires and Worker AI executes
        testScope.advanceTimeBy(25_000L)
        val proposed = storage.getProposedSoulFacts()
        assertEquals(1, proposed.size)
        assertTrue("Must propose meditation habit", proposed.first().statement.contains("daily meditation"))
    }
}
