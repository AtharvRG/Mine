// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.models

import com.anchor.core.SharedFakeStorage
import com.anchor.core.gateway.Gateway
import com.anchor.core.gateway.ToolCall
import com.anchor.core.resolver.Resolver
import com.anchor.core.time.DateResolver
import com.anchor.core.time.FakeClock
import com.anchor.core.ui.UiState
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReminderTierTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun testReminder_DefaultTierIsAlarm() {
        val reminder = Reminder("r1", "Drink coffee", 1000L)
        assertEquals(AlarmTier.ALARM, reminder.tier)
    }

    @Test
    fun testReminder_ExplicitGentleTier() {
        val reminder = Reminder("r2", "Stretching nudge", 2000L, tier = AlarmTier.GENTLE)
        assertEquals(AlarmTier.GENTLE, reminder.tier)
    }

    @Test
    fun testReminder_SerializationRoundTrip() {
        val original = Reminder("r3", "Gentle reminder", 3000L, nodeId = "n1", tier = AlarmTier.GENTLE)
        val serialized = json.encodeToString(original)
        val deserialized = json.decodeFromString<Reminder>(serialized)

        assertEquals(original.id, deserialized.id)
        assertEquals(original.text, deserialized.text)
        assertEquals(original.fireAtMillis, deserialized.fireAtMillis)
        assertEquals(original.nodeId, deserialized.nodeId)
        assertEquals(AlarmTier.GENTLE, deserialized.tier)
    }

    @Test
    fun testReminder_LegacyJsonWithoutTier_DefaultsToAlarm() {
        val legacyJson = """{"id":"r_legacy","text":"Legacy reminder","fireAtMillis":5000000,"nodeId":"n_leg"}"""
        val deserialized = json.decodeFromString<Reminder>(legacyJson)

        assertEquals("r_legacy", deserialized.id)
        assertEquals("Legacy reminder", deserialized.text)
        assertEquals(5000000L, deserialized.fireAtMillis)
        assertEquals("n_leg", deserialized.nodeId)
        assertEquals(AlarmTier.ALARM, deserialized.tier)
    }

    @Test
    fun testGateway_ScheduleReminderWithGentleTier() = runBlocking {
        val storage = SharedFakeStorage()
        val clock = FakeClock(1000000L)
        val dateResolver = DateResolver(clock) { true }
        val gateway = Gateway(storage, Resolver(), dateResolver, clock)

        val call = ToolCall(
            "schedule_reminder",
            mapOf(
                "target" to JsonPrimitive(""),
                "when" to JsonPrimitive("in 1 hour"),
                "tier" to JsonPrimitive("gentle")
            )
        )

        val result = gateway.executeTool(call)
        assertTrue(result is ToolResult.Committed)

        val pending = storage.getPendingReminders()
        val scheduled = pending.firstOrNull()
        assertNotNull(scheduled)
        assertEquals(AlarmTier.GENTLE, scheduled!!.tier)
    }

    @Test
    fun testUiState_ExposesAlarmDisclosureText() {
        val state = UiState()
        assertTrue("Disclosure text must mention status bar", state.alarmDisclosureText.contains("status bar", ignoreCase = true))
        assertTrue("Disclosure text must mention alarm", state.alarmDisclosureText.contains("alarm", ignoreCase = true))
        assertTrue("Disclosure text must mention gentle", state.alarmDisclosureText.contains("gentle", ignoreCase = true))
    }
}
