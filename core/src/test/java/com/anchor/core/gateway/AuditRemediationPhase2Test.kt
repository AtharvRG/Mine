// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.gateway

import com.anchor.core.SharedFakeStorage
import com.anchor.core.alarms.AlarmScheduler
import com.anchor.core.alarms.ScheduleResult
import com.anchor.core.nudge.CheckinBuilder
import com.anchor.core.context.ContextBuilder
import com.anchor.core.models.AlarmTier
import com.anchor.core.models.CheckinStateRecord
import com.anchor.core.models.DayBlock
import com.anchor.core.models.ExportLogRecord
import com.anchor.core.models.GateResult
import com.anchor.core.models.NudgeCandidate
import com.anchor.core.models.NudgeLogRecord
import com.anchor.core.models.ProactiveState
import com.anchor.core.models.Reminder
import com.anchor.core.models.ToolResult
import com.anchor.core.nudge.NudgeEngine
import com.anchor.core.provider.ChatRequest
import com.anchor.core.provider.HttpTransport
import com.anchor.core.provider.ProviderOrchestrator
import com.anchor.core.provider.RateLimiter
import com.anchor.core.provider.RequestMessage
import com.anchor.core.provider.Target
import com.anchor.core.resolver.Resolver
import com.anchor.core.time.DateResolver
import com.anchor.core.time.FakeClock
import com.anchor.core.ui.ChatController
import com.anchor.core.ui.UiAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AuditRemediationPhase2Test {

    private val now = 1770000000000L

    private class NoTransport : HttpTransport {
        override suspend fun postStream(target: Target, body: String): Flow<String> = flow {
            throw AssertionError("No network call expected")
        }
    }

    private fun createController(storage: SharedFakeStorage, clock: FakeClock): ChatController {
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
    fun testChatRequest_IncludesTemperatureAndMaxTokensFromSettings() {
        val request = ChatRequest(
            model = "gpt-4o-mini",
            stream = true,
            messages = listOf(RequestMessage("user", "Hello")),
            temperature = 0.5,
            max_tokens = 800
        )
        val jsonStr = Json.encodeToString(ChatRequest.serializer(), request)
        assertTrue("JSON must contain temperature", jsonStr.contains(""""temperature":0.5"""))
        assertTrue("JSON must contain max_tokens", jsonStr.contains(""""max_tokens":800"""))

        val decoded = Json.decodeFromString(ChatRequest.serializer(), jsonStr)
        assertEquals(0.5, decoded.temperature)
        assertEquals(800, decoded.max_tokens)
    }

    @Test
    fun testRateLimiter_RespectsCustomDefaultCooldown() = runBlocking {
        var currentTime = now
        val limiter = RateLimiter(clock = { currentTime })

        // Apply cooldown with custom default cooldown of 30 minutes
        limiter.apply429Cooldown("key_abc", retryAfterSeconds = null, defaultCooldownMin = 30)

        // 20 minutes later, still on cooldown
        currentTime += 20 * 60 * 1000L
        val expiry = limiter.getCooldownExpiry("key_abc")
        assertNotNull("Cooldown expiry should be present", expiry)
        assertTrue("Expiry must be greater than current time", expiry!! > currentTime)

        // 31 minutes later, cooldown passed
        currentTime += 11 * 60 * 1000L
        val expired = limiter.getCooldownExpiry("key_abc")
        assertEquals(null, expired)
    }

    @Test
    fun testNudgeEngine_RespectsSettings_MinGapAndDisabled() = runBlocking {
        val clock = FakeClock(now)
        val storage = SharedFakeStorage(clock)
        val engine = NudgeEngine(clock)

        val candidate = NudgeCandidate("overdue", isCritical = false)
        val state = ProactiveState(lastSentAt = now - 30 * 60 * 1000L, backoffStep = 0) // sent 30 min ago

        // With default 60 min gap -> Held("min_gap")
        val gateDefault = engine.evaluate(candidate, state, minGapMinutes = 60L)
        assertTrue("Must be held with min_gap", gateDefault is GateResult.Held && gateDefault.reason == "min_gap")

        // With custom 20 min gap -> Pass (since 30 min > 20 min)
        val gateCustom = engine.evaluate(candidate, state, minGapMinutes = 20L)
        assertTrue("Must pass with 20 min gap", gateCustom is GateResult.Pass)

        // Verify ChatController respects nudge.enabled = 0
        storage.saveSetting("nudge.enabled", "0")
        val controller = createController(storage, clock)
        controller.onAction(UiAction.TickProactive)
        assertEquals("disabled", controller.state.value.nudgeGate)
    }

    @Test
    fun testGateway_UsesReminderDefaultTierSetting() = runBlocking {
        val clock = FakeClock(now)
        val storage = SharedFakeStorage(clock)
        storage.saveSetting("reminder.default_tier", "gentle")
        val resolver = Resolver()
        val dateResolver = DateResolver(clock) { false }
        val gateway = Gateway(storage, resolver, dateResolver, clock)

        // Create a node first in storage
        val node = com.anchor.core.models.Node(id = "n_gentle", type = "task", title = "Gentle task", status = "active")
        storage.saveNode(node)

        // Schedule reminder without explicit "tier" argument
        val schedResult = gateway.executeTool(
            ToolCall("schedule_reminder", mapOf("target" to JsonPrimitive("n_gentle"), "when" to JsonPrimitive("5pm")))
        )
        assertTrue("Schedule reminder must succeed, got $schedResult", schedResult is ToolResult.Committed)

        val rem = storage.getReminderByNodeId("n_gentle")
        assertNotNull("Reminder should be created", rem)
        assertEquals("Reminder tier should be GENTLE from default_tier setting", AlarmTier.GENTLE, rem!!.tier)
    }

    @Test
    fun testStorage_ExportLog_CheckinState_DayBlock_NudgeLog_ReadWrite() = runBlocking {
        val clock = FakeClock(now)
        val storage = SharedFakeStorage(clock)

        // DayBlock
        val block = DayBlock("2026-11-13", 0, "m_start", now, now + 3600_000L, 5, "morning")
        storage.saveDayBlock(block)
        val blocks = storage.getDayBlocks("2026-11-13")
        assertEquals(1, blocks.size)
        assertEquals("m_start", blocks[0].startMsgId)

        // ExportLog
        val exp = ExportLogRecord("exp_1", now, "export", bytes = 1024L, verified = true, note = "test backup")
        storage.saveExportLog(exp)
        val exps = storage.getRecentExportLogs(5)
        assertEquals(1, exps.size)
        assertEquals("exp_1", exps[0].id)
        assertTrue(exps[0].verified)

        // CheckinState
        val checkin = CheckinStateRecord("morning", "2026-11-13", "delivered", "m_chk", now, now)
        storage.saveCheckinState(checkin)
        val loadedCheckin = storage.getCheckinState("morning", "2026-11-13")
        assertNotNull(loadedCheckin)
        assertEquals("delivered", loadedCheckin!!.status)

        // NudgeLog
        val nlog = NudgeLogRecord("nl_1", now, "overdue", decision = "pass", msgId = "n_123")
        storage.saveNudgeLog(nlog)
        val nlogs = storage.getRecentNudgeLogs(5)
        assertEquals(1, nlogs.size)
        assertEquals("nl_1", nlogs[0].id)
        assertEquals("pass", nlogs[0].decision)
    }
}
