// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.gateway

import com.anchor.core.SharedFakeStorage
import com.anchor.core.models.AlarmTier
import com.anchor.core.models.DayRule
import com.anchor.core.models.IntentType
import com.anchor.core.models.Node
import com.anchor.core.models.ToolResult
import com.anchor.core.resolver.Resolver
import com.anchor.core.time.DateResolution
import com.anchor.core.time.DateResolver
import com.anchor.core.time.FakeClock
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class P1B15ReminderPolicyTest {

    // Base time: Oct 25, 2026 12:00:00 UTC
    private val baseCal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        set(2026, Calendar.OCTOBER, 25, 12, 0, 0)
        set(Calendar.MILLISECOND, 0)
    }
    private val clock = FakeClock(baseCal.timeInMillis)
    private val dateResolver = DateResolver(clock, { true }, TimeZone.getTimeZone("UTC"))

    @Test
    fun testVagueDayParts_MorningWindowAndSeverity() {
        val utc = TimeZone.getTimeZone("UTC")
        val resolver = DateResolver(clock) { true }

        // Morning window: 07:00 to 11:00 (240 min) on Oct 26 (tomorrow, since 12:00 is past morning)
        // sev 3: 10% = 24m -> 07:24
        val resSev3 = resolver.resolve("morning", rolloverMinuteOfDay = 240, timeZone = utc, severity = 3)
        assertTrue(resSev3 is DateResolution.Exact)
        val cal3 = Calendar.getInstance(utc).apply { timeInMillis = (resSev3 as DateResolution.Exact).timeMillis }
        assertEquals(7, cal3.get(Calendar.HOUR_OF_DAY))
        assertEquals(24, cal3.get(Calendar.MINUTE))

        // sev 2: 35% = 84m -> 08:24
        val resSev2 = resolver.resolve("morning", rolloverMinuteOfDay = 240, timeZone = utc, severity = 2)
        assertTrue(resSev2 is DateResolution.Exact)
        val cal2 = Calendar.getInstance(utc).apply { timeInMillis = (resSev2 as DateResolution.Exact).timeMillis }
        assertEquals(8, cal2.get(Calendar.HOUR_OF_DAY))
        assertEquals(24, cal2.get(Calendar.MINUTE))

        // sev 1: 60% = 144m -> 09:24
        val resSev1 = resolver.resolve("morning", rolloverMinuteOfDay = 240, timeZone = utc, severity = 1)
        assertTrue(resSev1 is DateResolution.Exact)
        val cal1 = Calendar.getInstance(utc).apply { timeInMillis = (resSev1 as DateResolution.Exact).timeMillis }
        assertEquals(9, cal1.get(Calendar.HOUR_OF_DAY))
        assertEquals(24, cal1.get(Calendar.MINUTE))

        // sev 0: 75% = 180m -> 10:00
        val resSev0 = resolver.resolve("morning", rolloverMinuteOfDay = 240, timeZone = utc, severity = 0)
        assertTrue(resSev0 is DateResolution.Exact)
        val cal0 = Calendar.getInstance(utc).apply { timeInMillis = (resSev0 as DateResolution.Exact).timeMillis }
        assertEquals(10, cal0.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, cal0.get(Calendar.MINUTE))

        // No severity: default midpoint/base 09:00
        val resDefault = resolver.resolve("morning", rolloverMinuteOfDay = 240, timeZone = utc, severity = null)
        assertTrue(resDefault is DateResolution.Exact)
        val calDef = Calendar.getInstance(utc).apply { timeInMillis = (resDefault as DateResolution.Exact).timeMillis }
        assertEquals(9, calDef.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, calDef.get(Calendar.MINUTE))
    }

    @Test
    fun testVagueDayParts_EveningWindowAndSeverity() {
        val utc = TimeZone.getTimeZone("UTC")
        val resolver = DateResolver(clock) { true }

        // Evening window: 17:00 to 20:00 (180 min) today (12:00 is before 17:00)
        // sev 3: 10% = 18m -> 17:18
        val resSev3 = resolver.resolve("evening", rolloverMinuteOfDay = 240, timeZone = utc, severity = 3)
        assertTrue(resSev3 is DateResolution.Exact)
        val cal3 = Calendar.getInstance(utc).apply { timeInMillis = (resSev3 as DateResolution.Exact).timeMillis }
        assertEquals(17, cal3.get(Calendar.HOUR_OF_DAY))
        assertEquals(18, cal3.get(Calendar.MINUTE))

        // sev 2: 35% = 63m -> 18:03
        val resSev2 = resolver.resolve("evening", rolloverMinuteOfDay = 240, timeZone = utc, severity = 2)
        assertTrue(resSev2 is DateResolution.Exact)
        val cal2 = Calendar.getInstance(utc).apply { timeInMillis = (resSev2 as DateResolution.Exact).timeMillis }
        assertEquals(18, cal2.get(Calendar.HOUR_OF_DAY))
        assertEquals(3, cal2.get(Calendar.MINUTE))

        // sev 1: 60% = 108m -> 18:48
        val resSev1 = resolver.resolve("evening", rolloverMinuteOfDay = 240, timeZone = utc, severity = 1)
        assertTrue(resSev1 is DateResolution.Exact)
        val cal1 = Calendar.getInstance(utc).apply { timeInMillis = (resSev1 as DateResolution.Exact).timeMillis }
        assertEquals(18, cal1.get(Calendar.HOUR_OF_DAY))
        assertEquals(48, cal1.get(Calendar.MINUTE))

        // sev 0: 75% = 135m -> 19:15
        val resSev0 = resolver.resolve("this evening", rolloverMinuteOfDay = 240, timeZone = utc, severity = 0)
        assertTrue(resSev0 is DateResolution.Exact)
        val cal0 = Calendar.getInstance(utc).apply { timeInMillis = (resSev0 as DateResolution.Exact).timeMillis }
        assertEquals(19, cal0.get(Calendar.HOUR_OF_DAY))
        assertEquals(15, cal0.get(Calendar.MINUTE))
    }

    @Test
    fun testVagueDayParts_SoonAndLater() {
        val utc = TimeZone.getTimeZone("UTC")
        val resolver = DateResolver(clock) { true }

        // "soon" = now + 30 min
        val resSoon = resolver.resolve("soon", rolloverMinuteOfDay = 240, timeZone = utc)
        assertTrue(resSoon is DateResolution.Exact)
        assertEquals(clock.now() + 30 * 60 * 1000L, (resSoon as DateResolution.Exact).timeMillis)

        // "later" = now + 2 hours
        val resLater = resolver.resolve("later", rolloverMinuteOfDay = 240, timeZone = utc)
        assertTrue(resLater is DateResolution.Exact)
        assertEquals(clock.now() + 2 * 60 * 60 * 1000L, (resLater as DateResolution.Exact).timeMillis)
    }

    @Test
    fun testScheduleReminder_RelativeLeadTime_AnchoredToNodeDueAt() = runBlocking {
        val storage = SharedFakeStorage(clock)
        val dayRule = DayRule("dr1", 0L, startMinute = 240, endMinute = 1320, tzId = "UTC", createdAt = clock.now())
        storage.saveDayRule(dayRule)
        val gateway = Gateway(storage, Resolver(), dateResolver, clock)

        // Create node with dueAt = 17:00 (5pm today)
        val dueAt5pm = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            set(2026, Calendar.OCTOBER, 25, 17, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val node = Node("n1", "task", "Submit project", "active", dueAt = dueAt5pm, createdAt = clock.now(), updatedAt = clock.now())
        storage.activeNodes.add(node)

        // Schedule reminder 30 minutes before due date
        val call = ToolCall(
            "schedule_reminder",
            mapOf(
                "target_ref" to JsonPrimitive("n1"),
                "offset_text" to JsonPrimitive("30 minutes before")
            )
        )
        val result = gateway.executeTool(call)
        assertTrue("Expected Committed result: $result", result is ToolResult.Committed)

        val rems = storage.getPendingReminders()
        assertEquals(1, rems.size)
        // Expected fire time: 16:30 (4:30pm)
        val expectedFireAt = dueAt5pm - 30 * 60 * 1000L
        assertEquals(expectedFireAt, rems[0].fireAtMillis)
    }

    @Test
    fun testScheduleReminder_DuplicateWithin30Minutes_ReturnsNeedsConfirm() = runBlocking {
        val storage = SharedFakeStorage(clock)
        val dayRule = DayRule("dr1", 0L, startMinute = 240, endMinute = 1320, tzId = "UTC", createdAt = clock.now())
        storage.saveDayRule(dayRule)
        val gateway = Gateway(storage, Resolver(), dateResolver, clock)

        val dueAt5pm = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            set(2026, Calendar.OCTOBER, 25, 17, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val node = Node("n1", "task", "Doctor appointment", "active", dueAt = dueAt5pm, createdAt = clock.now(), updatedAt = clock.now())
        storage.activeNodes.add(node)

        // 1. Initial reminder set at 5pm
        val call1 = ToolCall(
            "schedule_reminder",
            mapOf(
                "target_ref" to JsonPrimitive("n1"),
                "when_text" to JsonPrimitive("today at 5pm")
            )
        )
        val res1 = gateway.executeTool(call1)
        assertTrue(res1 is ToolResult.Committed)
        assertEquals(1, storage.getPendingReminders().size)

        // 2. Second reminder scheduled within 30 min (at 5:15pm = 15m difference)
        val call2 = ToolCall(
            "schedule_reminder",
            mapOf(
                "target_ref" to JsonPrimitive("n1"),
                "when_text" to JsonPrimitive("today at 5:15pm")
            )
        )
        val res2 = gateway.executeTool(call2)
        assertTrue("Expected NeedsConfirm for duplicate within 30 min: $res2", res2 is ToolResult.NeedsConfirm)
        val needsConfirm = res2 as ToolResult.NeedsConfirm
        assertTrue(needsConfirm.message.contains("already set"))
        assertTrue(needsConfirm.options.contains("Update existing"))
        assertTrue(needsConfirm.options.contains("Cancel"))

        // Confirm with "update": updates existing reminder
        val confirmCall = ToolCall(
            "confirm_change",
            mapOf(
                "proposal" to JsonPrimitive(needsConfirm.proposalId),
                "choice" to JsonPrimitive("update")
            )
        )
        val confirmRes = gateway.executeTool(confirmCall)
        assertTrue("Expected Committed after confirm: $confirmRes", confirmRes is ToolResult.Committed)

        // Still exactly 1 reminder, updated to 5:15pm
        val rems = storage.getPendingReminders()
        assertEquals(1, rems.size)
        val expected515pm = dueAt5pm + 15 * 60 * 1000L
        assertEquals(expected515pm, rems[0].fireAtMillis)

        // 3. Third reminder scheduled > 30 minutes away (at 6:30pm = 75m difference) -> commits directly
        val call3 = ToolCall(
            "schedule_reminder",
            mapOf(
                "target_ref" to JsonPrimitive("n1"),
                "when_text" to JsonPrimitive("today at 6:30pm")
            )
        )
        val res3 = gateway.executeTool(call3)
        assertTrue("Expected Committed for distinct time: $res3", res3 is ToolResult.Committed)
    }

    @Test
    fun testScheduleReminder_QuietHoursAdjustment_AiInferredOnly() = runBlocking {
        val storage = SharedFakeStorage(clock)
        val gateway = Gateway(storage, Resolver(), dateResolver, clock)

        // DayRule: startMinute = 240 (04:00 AM), endMinute = 1320 (22:00 / 10:00 PM)
        val dayRule = DayRule("dr1", 0L, startMinute = 240, endMinute = 1320, tzId = "UTC", createdAt = clock.now())
        storage.saveDayRule(dayRule)

        // 1. Explicit user reminder for 02:00 AM (inside quiet hours 22:00..04:00)
        // Explicit reminders are requested-class: they ring even outside the day window (D-035, s3.1, s3.3)
        val callExplicit = ToolCall(
            "schedule_reminder",
            mapOf(
                "when_text" to JsonPrimitive("tomorrow at 2am")
            )
        )
        val resExplicit = gateway.executeTool(callExplicit)
        assertTrue(resExplicit is ToolResult.Committed)
        val explicitRem = storage.getPendingReminders().first { it.id == (resExplicit as ToolResult.Committed).targetId }
        val calExplicit = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = explicitRem.fireAtMillis }
        assertEquals(2, calExplicit.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, calExplicit.get(Calendar.MINUTE))

        // 2. AI-inferred reminder for 02:00 AM
        // AI-suggested reminders are moved to day_start + 30 min = 04:30 AM (s3.3, D-035)
        storage.saveSetting("reminder.ai_suggest", "auto") // auto mode commits directly with adjusted time
        val callInferred = ToolCall(
            "schedule_reminder",
            mapOf(
                "when_text" to JsonPrimitive("tomorrow at 2am"),
                "inferred" to JsonPrimitive("true")
            )
        )
        val resInferred = gateway.executeTool(callInferred)
        assertTrue(resInferred is ToolResult.Committed)
        val inferredRem = storage.getPendingReminders().first { it.id == (resInferred as ToolResult.Committed).targetId }
        val calInferred = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = inferredRem.fireAtMillis }
        assertEquals(4, calInferred.get(Calendar.HOUR_OF_DAY))
        assertEquals(30, calInferred.get(Calendar.MINUTE))
    }

    @Test
    fun testScheduleReminder_AiSuggestSetting_AskVsAuto() = runBlocking {
        val storage = SharedFakeStorage(clock)
        val dayRule = DayRule("dr1", 0L, startMinute = 240, endMinute = 1320, tzId = "UTC", createdAt = clock.now())
        storage.saveDayRule(dayRule)
        val gateway = Gateway(storage, Resolver(), dateResolver, clock)

        // Default setting is "ask" (Blueprint 52 line 174, Blueprint 30 s3.3)
        // In "ask" mode, AI-inferred reminder returns NeedsConfirm proposal with chips: Set time, Change, No
        val callAsk = ToolCall(
            "schedule_reminder",
            mapOf(
                "when_text" to JsonPrimitive("tomorrow at 5pm"),
                "inferred" to JsonPrimitive("true")
            )
        )
        val resAsk = gateway.executeTool(callAsk)
        assertTrue("Expected NeedsConfirm in ask mode: $resAsk", resAsk is ToolResult.NeedsConfirm)
        val needsConfirm = resAsk as ToolResult.NeedsConfirm
        assertTrue(needsConfirm.options.any { it.contains("Set") || it.contains("Set 17:00") })
        assertTrue(needsConfirm.options.contains("Cancel") || needsConfirm.options.contains("No"))

        // When setting is "auto": commits immediately
        storage.saveSetting("reminder.ai_suggest", "auto")
        val callAuto = ToolCall(
            "schedule_reminder",
            mapOf(
                "when_text" to JsonPrimitive("tomorrow at 5pm"),
                "inferred" to JsonPrimitive("true")
            )
        )
        val resAuto = gateway.executeTool(callAuto)
        assertTrue("Expected Committed in auto mode: $resAuto", resAuto is ToolResult.Committed)
    }
}
