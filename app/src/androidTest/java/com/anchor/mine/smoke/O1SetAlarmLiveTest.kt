// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.mine.smoke

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.anchor.core.context.ContextBuilder
import com.anchor.core.gateway.Gateway
import com.anchor.core.provider.ProviderOrchestrator
import com.anchor.core.provider.RateLimiter
import com.anchor.core.resolver.Resolver
import com.anchor.core.time.DateResolver
import com.anchor.core.time.SystemClock
import com.anchor.core.ui.ChatController
import com.anchor.core.ui.UiAction
import com.anchor.mine.BuildConfig
import com.anchor.mine.alarms.AndroidAlarmScheduler
import com.anchor.mine.provider.AndroidHttpTransport
import com.anchor.mine.storage.AndroidStorage
import com.anchor.mine.storage.DatabaseProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * O-1 against a real model. The unit test in SystemPromptTest proves the prompt *text*; this
 * proves the model, reading that prompt, actually issues propose_change for the exact phrasings
 * that failed on the R10g release build ("set alarm for drink water in 3 minutes" produced the
 * prose "Setting an alarm to drink water." and stored nothing).
 *
 * Each utterance gets a fresh database so a node created by turn 1 cannot satisfy turn 2.
 */
@LiveTest
@RunWith(AndroidJUnit4::class)
class O1SetAlarmLiveTest {

    private val apiKey = BuildConfig.LIVE_API_KEY
    private val endpoint = BuildConfig.LIVE_ENDPOINT
    private val model = BuildConfig.LIVE_MODEL

    @Test
    fun setAlarmForSubjectPhrasings_CreateTheReminder() = runBlocking {
        assumeTrue(
            "LIVE_API_KEY is empty — set API_KEY in local.properties to run live tests",
            apiKey.isNotBlank()
        )
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        // (utterance, expected title fragment, expected fire delay in ms)
        val cases = listOf(
            Triple("set alarm for drink water in 3 minutes", "drink water", 3 * 60_000L),
            Triple("set alarm to buy milk in 3 minutes", "buy milk", 3 * 60_000L)
        )

        for ((idx, case) in cases.withIndex()) {
            val (utterance, expectedTitle, expectedDelay) = case
            val dbName = "o1_live_$idx.db"
            context.deleteDatabase(dbName)

            val key = ByteArray(32) { (7 + idx).toByte() }
            val dbHelper = DatabaseProvider.getHelper(context, key, dbName)
            val storage = AndroidStorage(dbHelper)
            storage.saveSetting("ep", endpoint)
            storage.saveSetting("key", apiKey)
            storage.saveSetting("mod", model)

            val clock = SystemClock()
            val dateResolver = DateResolver(clock, is24HourFormat = { false })
            val checkinBuilder = com.anchor.core.nudge.CheckinBuilder(storage, clock, dateResolver)
            val controller = ChatController(
                storage = storage,
                orchestrator = ProviderOrchestrator(RateLimiter { clock.now() }, AndroidHttpTransport()),
                contextBuilder = ContextBuilder(storage, clock, dateResolver, checkinBuilder),
                gateway = Gateway(storage, Resolver(), dateResolver, clock),
                alarmScheduler = AndroidAlarmScheduler(context),
                coroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
                defaultDispatcher = Dispatchers.Default,
                clock = clock,
                dateResolver = dateResolver
            )

            withTimeout(5_000L) {
                while (controller.state.value.providerKey.isBlank()) delay(50L)
            }

            val sentAt = clock.now()
            controller.onAction(UiAction.UpdateDraft(utterance))
            controller.onAction(UiAction.SendMessage)
            withTimeout(45_000L) {
                var attempts = 0
                while (!controller.state.value.isChatLoading && attempts < 20) {
                    delay(50L); attempts++
                }
                while (controller.state.value.isChatLoading) delay(100L)
            }
            delay(500L)

            val nodes = storage.getActiveNodes(50)
            val reminders = storage.getActiveReminders(System.currentTimeMillis())
            val reply = controller.state.value.chatMessages.lastOrNull { it.role == "assistant" }?.content
            val receipts = controller.state.value.receipts.joinToString("; ") { it.message }
            val detail = "input=\"$utterance\" reply=\"$reply\" receipts=[$receipts] " +
                "nodes=${nodes.map { it.title }} reminders=${reminders.map { it.text to it.fireAtMillis }}"

            assertEquals("Exactly one node must be created. $detail", 1, nodes.size)
            assertTrue(
                "Node title must carry the subject. $detail",
                nodes[0].title.contains(expectedTitle, ignoreCase = true)
            )
            assertEquals("Exactly one reminder must be armed. $detail", 1, reminders.size)
            // "in 3 minutes" is a relative duration: DateResolver returns now + 180s exactly.
            assertTrue(
                "Reminder must fire ~3 minutes after the turn. $detail",
                reminders[0].fireAtMillis in (sentAt + expectedDelay - 5_000L)..(sentAt + expectedDelay + 5_000L)
            )

        }
    }
}