// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.gateway

import com.anchor.core.SharedFakeStorage
import com.anchor.core.models.*
import com.anchor.core.provider.HttpTransport
import com.anchor.core.provider.ProviderOrchestrator
import com.anchor.core.provider.RateLimiter
import com.anchor.core.provider.StreamEvent
import com.anchor.core.resolver.Resolver
import com.anchor.core.time.DateResolver
import com.anchor.core.time.FakeClock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class AuditRemediationPhase1Test {

    private lateinit var storage: SharedFakeStorage
    private lateinit var clock: FakeClock
    private lateinit var resolver: Resolver
    private lateinit var dateResolver: DateResolver
    private lateinit var gateway: Gateway

    @Before
    fun setUp() {
        clock = FakeClock(1700000000000L)
        storage = SharedFakeStorage(clock)
        resolver = Resolver()
        dateResolver = DateResolver(clock, { false })
        gateway = Gateway(storage, resolver, dateResolver, clock)
    }

    @Test
    fun testProposeChange_WithTargetRefOrHintAlias_ResolvesAndUpdatesNode() = runBlocking {
        // Create an initial node in storage
        val node = Node(id = "real-uuid-123", title = "File tax documents", type = "task", status = "active", dueAt = clock.now() + 3600_000L)
        storage.saveNode(node)

        // Register alias n_1 -> real-uuid-123
        val alias = gateway.aliasRegistry.aliasFor("n", "real-uuid-123")
        assertEquals("n_1", alias)

        // 1. propose_change with hint = "n_1" and intent = COMPLETE
        val res1 = gateway.executeTool(
            ToolCall("propose_change", mapOf("intent" to JsonPrimitive("COMPLETE"), "hint" to JsonPrimitive("n_1")))
        )
        assertTrue("Expected committed result for hint=n_1, got $res1", res1 is ToolResult.Committed)
        assertEquals("done", storage.getNode("real-uuid-123")?.status)

        // Reset to active
        storage.saveNode(node.copy(status = "active"))

        // 2. propose_change with target_ref = "n_1" and intent = CANCEL
        val res2 = gateway.executeTool(
            ToolCall("propose_change", mapOf("intent" to JsonPrimitive("CANCEL"), "target_ref" to JsonPrimitive("n_1")))
        )
        assertTrue("Expected committed result for target_ref=n_1, got $res2", res2 is ToolResult.Committed)
        assertEquals("cancelled", storage.getNode("real-uuid-123")?.status)
    }

    @Test
    fun testConfirmChange_WithConversationalReplies_TheFirstOneAndYes_Succeeds() = runBlocking {
        val node1 = Node(id = "node-first-1", title = "Meeting with Alice", type = "task", status = "active")
        val node2 = Node(id = "node-second-2", title = "Meeting with Bob", type = "task", status = "active")
        storage.saveNode(node1)
        storage.saveNode(node2)

        val a1 = gateway.aliasRegistry.aliasFor("n", "node-first-1")
        val a2 = gateway.aliasRegistry.aliasFor("n", "node-second-2")
        assertEquals("n_1", a1)
        assertEquals("n_2", a2)

        val pId = "p_test_disambig"
        val proposal = Proposal(
            id = pId,
            kind = "disambiguation",
            intent = IntentType.COMPLETE,
            options = listOf(
                ProposalOption("node-first-1", "Meeting with Alice"),
                ProposalOption("node-second-2", "Meeting with Bob")
            )
        )
        storage.saveProposal(proposal)

        // User typed "the first one"
        val res1 = gateway.executeTool(
            ToolCall("confirm_change", mapOf("proposal" to JsonPrimitive(pId), "choice" to JsonPrimitive("n_1"))),
            userMessage = "the first one"
        )
        assertTrue("Expected Committed for 'the first one', got $res1", res1 is ToolResult.Committed)
        assertEquals("done", storage.getNode("node-first-1")?.status)

        // Create another proposal and test "yes"
        storage.saveNode(node2.copy(status = "active"))
        val pId2 = "p_test_confirm_yes"
        val proposal2 = Proposal(
            id = pId2,
            kind = "confirm",
            intent = IntentType.COMPLETE,
            options = listOf(
                ProposalOption("node-second-2", "Meeting with Bob")
            )
        )
        storage.saveProposal(proposal2)

        val res2 = gateway.executeTool(
            ToolCall("confirm_change", mapOf("proposal" to JsonPrimitive(pId2), "choice" to JsonPrimitive("n_2"))),
            userMessage = "yes, do it"
        )
        assertTrue("Expected Committed for 'yes, do it', got $res2", res2 is ToolResult.Committed)
        assertEquals("done", storage.getNode("node-second-2")?.status)
    }

    @Test
    fun testConfirmChange_WithExplicitNegation_IsRejected() = runBlocking {
        val node1 = Node(id = "node-first-1", title = "Meeting with Alice", type = "task", status = "active")
        storage.saveNode(node1)
        gateway.aliasRegistry.aliasFor("n", "node-first-1")

        val pId = "p_test_negated"
        val proposal = Proposal(
            id = pId,
            kind = "confirm",
            intent = IntentType.COMPLETE,
            options = listOf(ProposalOption("node-first-1", "Meeting with Alice"))
        )
        storage.saveProposal(proposal)

        val res = gateway.executeTool(
            ToolCall("confirm_change", mapOf("proposal" to JsonPrimitive(pId), "choice" to JsonPrimitive("n_1"))),
            userMessage = "no, don't do that"
        )
        assertTrue("Expected Error for negated message, got $res", res is ToolResult.Error)
        assertEquals("active", storage.getNode("node-first-1")?.status)
    }

    @Test
    fun testConfirmChange_OrdinalAndLexicalMismatch_RejectedAccurately() = runBlocking {
        val node1 = Node(id = "node-raj-1", title = "Dinner with Raj", type = "task", status = "active")
        val node2 = Node(id = "node-sam-2", title = "Dinner with Sam", type = "task", status = "active")
        storage.saveNode(node1)
        storage.saveNode(node2)

        val a1 = gateway.aliasRegistry.aliasFor("n", "node-raj-1")
        val a2 = gateway.aliasRegistry.aliasFor("n", "node-sam-2")
        assertEquals("n_1", a1)
        assertEquals("n_2", a2)

        val pId = "p_test_disambig_ordinal"
        val proposal = Proposal(
            id = pId,
            kind = "disambiguation",
            intent = IntentType.COMPLETE,
            options = listOf(
                ProposalOption("node-raj-1", "Dinner with Raj"),
                ProposalOption("node-sam-2", "Dinner with Sam")
            )
        )
        storage.saveProposal(proposal)

        // 1. User typed "the second one", but LLM sent choice = "n_1" (index 0) -> MUST BE REJECTED
        val resMismatchOrdinal = gateway.executeTool(
            ToolCall("confirm_change", mapOf("proposal" to JsonPrimitive(pId), "choice" to JsonPrimitive("n_1"))),
            userMessage = "the second one"
        )
        assertTrue("Typing 'the second one' with choice=n_1 must be rejected, got $resMismatchOrdinal", resMismatchOrdinal is ToolResult.Error)
        assertEquals("active", storage.getNode("node-raj-1")?.status)
        assertEquals("active", storage.getNode("node-sam-2")?.status)

        // 2. User typed "Dinner with Sam", but LLM sent choice = "n_1" ("Dinner with Raj") -> MUST BE REJECTED
        val resMismatchLexical = gateway.executeTool(
            ToolCall("confirm_change", mapOf("proposal" to JsonPrimitive(pId), "choice" to JsonPrimitive("n_1"))),
            userMessage = "Dinner with Sam"
        )
        assertTrue("Typing 'Dinner with Sam' with choice=n_1 must be rejected, got $resMismatchLexical", resMismatchLexical is ToolResult.Error)
        assertEquals("active", storage.getNode("node-raj-1")?.status)
        assertEquals("active", storage.getNode("node-sam-2")?.status)

        // 3. User typed "the second one" and LLM sent choice = "n_2" (index 1) -> MUST COMMIT node2
        val resMatchSecond = gateway.executeTool(
            ToolCall("confirm_change", mapOf("proposal" to JsonPrimitive(pId), "choice" to JsonPrimitive("n_2"))),
            userMessage = "the second one"
        )
        assertTrue("Typing 'the second one' with choice=n_2 must commit, got $resMatchSecond", resMatchSecond is ToolResult.Committed)
        assertEquals("active", storage.getNode("node-raj-1")?.status)
        assertEquals("done", storage.getNode("node-sam-2")?.status)
    }

    @Test
    fun testLocalOnlyItems_AreVisibleInLocalUiQueries() = runBlocking {
        val localNode = Node(
            id = "private-node-1",
            title = "Personal health checkup",
            type = "task",
            status = "active",
            localOnly = true,
            dueAt = clock.now() + 1000L
        )
        storage.saveNode(localNode)

        val urgent = storage.getUrgentNodes(includeLocalOnly = true)
        assertTrue("Urgent nodes must include local_only items for local UI", urgent.any { it.id == "private-node-1" })

        val homeList = storage.getHomePriorityList(clock.now(), 10)
        assertTrue("Home priority list must include local_only items for local UI", homeList.any { it.id == "private-node-1" })

        val dueBetween = storage.getNodesDueBetween(clock.now(), clock.now() + 5000L, 10, includeLocalOnly = true)
        assertTrue("Due between query must include local_only items for local UI", dueBetween.any { it.id == "private-node-1" })
    }

    @Test
    fun testProviderOrchestrator_EmitsClearOnMidStreamFailover() = runBlocking {
        val target1 = com.anchor.core.provider.Target("ep1", "key1", 60)
        val target2 = com.anchor.core.provider.Target("ep2", "key2", 60)
        val chain = listOf(target1, target2)

        val transport = object : HttpTransport {
            override suspend fun postStream(target: com.anchor.core.provider.Target, body: String): Flow<String> = flow {
                if (target.id == "ep1") {
                    emit("""data: {"choices":[{"delta":{"content":"{\"reply\": \"Incom"}}]}""")
                    throw RuntimeException("Network drop mid-stream")
                } else {
                    emit("""data: {"choices":[{"delta":{"content":"{\"reply\": \"Hello from ep2\", \"calls\": []}"}}]}""")
                }
            }
        }

        val orchestrator = ProviderOrchestrator(
            limiter = RateLimiter({ clock.now() }),
            transport = transport
        )

        val events = orchestrator.chat(chain, "Test prompt", envelopeMode = true).toList()
        assertTrue("Events must contain StreamEvent.Clear when mid-stream failover happens", events.any { it is StreamEvent.Clear })
    }
}
