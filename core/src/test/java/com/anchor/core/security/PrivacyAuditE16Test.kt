// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.security

import com.anchor.core.SharedFakeStorage
import com.anchor.core.alarms.AlarmScheduler
import com.anchor.core.alarms.ScheduleResult
import com.anchor.core.context.ContextBuilder
import com.anchor.core.gateway.Gateway
import com.anchor.core.models.Node
import com.anchor.core.models.Reminder
import com.anchor.core.models.SoulFact
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivacyAuditE16Test {

    private val dummyScheduler = object : AlarmScheduler {
        override val lastFiredId = MutableStateFlow<String?>(null)
        override fun schedule(reminder: Reminder) = ScheduleResult.Armed
        override fun cancel(reminderId: String) {}
    }

    private val secretApiKey = "sk-live-secret-test-key-9876543210-xyz"
    private val rawDatabaseNodeId = "node_db_guid_998877665544"
    private val rawDatabaseSoulId = "soul_db_guid_112233445566"
    private val localOnlySecretNodeTitle = "TopSecret Passcode 44321"
    private val localOnlySecretFactStatement = "Confidential Medical Condition Omega"

    @Test
    fun testFullDayPrivacyAudit_NoKeyLeaks_NoLocalOnlyLeaks_NoRawDbIdLeaks() = runBlocking {
        val clock = FakeClock(1000000L)
        val storage = SharedFakeStorage().apply {
            settings["key"] = secretApiKey

            // Nodes: 1 public with raw DB ID, 1 local_only
            activeNodes = mutableListOf(
                Node(rawDatabaseNodeId, "task", "Public Project Plan", "active", localOnly = false),
                Node("node_local_123", "task", localOnlySecretNodeTitle, "active", localOnly = true)
            )

            // Soul facts: 1 public with raw DB ID, 1 local_only
            soulFacts = mutableListOf(
                SoulFact(rawDatabaseSoulId, "work", "Prefers morning meetings", 0.9, "chat", "approved", "active", 1000L, localOnly = false),
                SoulFact("soul_local_456", "medical", localOnlySecretFactStatement, 0.9, "chat", "approved", "active", 1000L, localOnly = true, sensitive = true)
            )
        }

        val dateResolver = DateResolver(clock)
        val checkinBuilder = CheckinBuilder(storage, clock, dateResolver)
        val contextBuilder = ContextBuilder(storage, clock, dateResolver, checkinBuilder)

        val capturedBodies = mutableListOf<String>()
        var turnCounter = 0

        val capturingProxyTransport = object : HttpTransport {
            override suspend fun postStream(target: Target, body: String): Flow<String> = flow {
                capturedBodies.add(body)
                turnCounter++
                if (turnCounter == 1) {
                    // Turn 1: Model invokes search_memory
                    emit("""data: {"choices":[{"delta":{"content":"{\"reply\":\"Searching memory...\",\"calls\":[{\"tool\":\"search_memory\",\"args\":{\"query\":\"Plan\"}}]}"}}]}""")
                } else if (turnCounter == 2) {
                    // Turn 2: Model completes after receiving search results
                    emit("""data: {"choices":[{"delta":{"content":"{\"reply\":\"Found your Public Project Plan.\",\"calls\":[]}"}}]}""")
                } else {
                    emit("""data: {"choices":[{"delta":{"content":"{\"reply\":\"Ready.\",\"calls\":[]}"}}]}""")
                }
                emit("data: [DONE]")
            }
        }

        val gateway = Gateway(storage, Resolver(), dateResolver, clock)

        val controller = ChatController(
            storage,
            ProviderOrchestrator(RateLimiter { clock.now() }, capturingProxyTransport),
            contextBuilder,
            gateway,
            dummyScheduler,
            TestScope(UnconfinedTestDispatcher()),
            UnconfinedTestDispatcher(),
            clock
        )

        // Session Turn 1: User says hello and asks about their plan
        controller.onAction(UiAction.UpdateDraft("What is my current project plan?"))
        controller.onAction(UiAction.SendMessage)

        assertEquals("Multi-turn interaction should generate exactly 2 HTTP requests", 2, capturedBodies.size)

        for ((index, body) in capturedBodies.withIndex()) {
            val reqNumber = index + 1

            // Audit Guarantee 1: Secret API key must NEVER be embedded in the JSON payload body
            assertFalse(
                "Request #$reqNumber body leaked API key: $body",
                body.contains(secretApiKey)
            )

            // Audit Guarantee 2: local_only node title must NEVER appear in provider requests
            assertFalse(
                "Request #$reqNumber body leaked local_only node title: $body",
                body.contains(localOnlySecretNodeTitle)
            )

            // Audit Guarantee 3: local_only soul fact statement must NEVER appear in provider requests
            assertFalse(
                "Request #$reqNumber body leaked local_only soul fact: $body",
                body.contains(localOnlySecretFactStatement)
            )

            // Audit Guarantee 4: Raw database UUIDs must NEVER leak to provider requests
            assertFalse(
                "Request #$reqNumber body leaked raw database node ID: $body",
                body.contains(rawDatabaseNodeId)
            )
            assertFalse(
                "Request #$reqNumber body leaked raw database soul ID: $body",
                body.contains(rawDatabaseSoulId)
            )
        }

        // Audit Guarantee 4: In Turn 2 (tool result feedback), delimiters hold and aliasing is applied
        val turn2Body = capturedBodies[1]
        val secondRequestJson = Json.parseToJsonElement(turn2Body).jsonObject
        val turn2Messages = secondRequestJson["messages"]?.jsonArray
        val toolFeedbackMsg = turn2Messages
            ?.map { it.jsonObject["content"]?.jsonPrimitive?.content ?: "" }
            ?.firstOrNull { it.contains("Tool Result:") } ?: ""

        assertTrue("Turn 2 must contain tool feedback", toolFeedbackMsg.contains("Tool Result:"))
        assertTrue("Tool feedback must be enclosed in <data> tags", toolFeedbackMsg.contains("<data>"))
        assertTrue("Tool feedback must be closed with </data> tags", toolFeedbackMsg.contains("</data>"))

        // An alias reference (n_...) must appear for the public node
        assertTrue(
            "Tool result feedback should contain aliased node reference: $toolFeedbackMsg",
            toolFeedbackMsg.contains("n_")
        )
    }
}
