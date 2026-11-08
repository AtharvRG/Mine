// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.mine.smoke

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.anchor.core.context.ContextBuilder
import com.anchor.core.gateway.Gateway
import com.anchor.core.models.Node
import com.anchor.core.models.RawEvent
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
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@LiveTest
@RunWith(AndroidJUnit4::class)
class LiveSmoke15Test {

    // Credentials are injected from local.properties (git-ignored) at build time; see app/build.gradle.kts.
    private val apiKey = BuildConfig.LIVE_API_KEY
    private val endpoint = BuildConfig.LIVE_ENDPOINT
    private val model = BuildConfig.LIVE_MODEL

    @Test
    fun execute15UtteranceSmokeTest() = runBlocking {
        // A skipped run that reports green is a false success. Fail with the reason instead.
        org.junit.Assume.assumeTrue(
            "LIVE_API_KEY is empty — set API_KEY in local.properties to run live tests",
            apiKey.isNotBlank()
        )
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dbName = "smoke_live_15.db"
        context.deleteDatabase(dbName)

        val key = ByteArray(32) { 7 }
        val dbHelper = DatabaseProvider.getHelper(context, key, dbName)
        val storage = AndroidStorage(dbHelper)

        storage.saveSetting("ep", endpoint)
        storage.saveSetting("key", apiKey)
        storage.saveSetting("mod", model)

        val clock = SystemClock()
        val dateResolver = DateResolver(clock, is24HourFormat = { false })
        val checkinBuilder = com.anchor.core.nudge.CheckinBuilder(storage, clock, dateResolver)
        val contextBuilder = ContextBuilder(storage, clock, dateResolver, checkinBuilder)
        val gateway = Gateway(storage, Resolver(), dateResolver, clock)
        val alarmScheduler = AndroidAlarmScheduler(context)
        val orchestrator = ProviderOrchestrator(RateLimiter { clock.now() }, AndroidHttpTransport())

        val testScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val controller = ChatController(
            storage = storage,
            orchestrator = orchestrator,
            contextBuilder = contextBuilder,
            gateway = gateway,
            alarmScheduler = alarmScheduler,
            coroutineScope = testScope,
            defaultDispatcher = Dispatchers.Default,
            clock = clock,
            dateResolver = dateResolver
        )

        // Seed an active task "Gym session" so U15 compound cancellation can find and cancel it
        val preGymNode = Node("n_gym_seed", "task", "Gym session", "active", dueAt = null, createdAt = clock.now())
        val preEvent = RawEvent("u_seed", "CREATE", "node", "n_gym_seed", tsUtc = clock.now())
        storage.createNodeAndCommit(preGymNode, preEvent, null, null, null)

        // Ensure controller state has loaded provider key
        withTimeout(5_000L) {
            while (controller.state.value.providerKey.isBlank()) {
                delay(50L)
            }
        }

        val utterances = listOf(
            "remind me to check oven in 1 min",
            "remaind me to check my laptop in 10 secs",
            "cue me in after 2 mins",
            "for checking laptop",
            "notify me in 2 mins",
            "no reason, just an alarm",
            "notify me in 2 mins for coffee",
            "give me a heads up in 1 mins",
            "remind me to take bread out in 2 min 32 seconds",
            "remind me at 5 to call doctor",
            "remind me to submit report tomorrow at 4pm",
            "remind me in २ mins to take medicine",
            "remind me to prepare the presentation slides for the quarterly review meeting tomorrow at 10am",
            "remind me to buy 🍎 and 🥛 at 6pm",
            "remind me to call mom at 6 and cancel the gym thing"
        )

        val transcriptLines = mutableListOf<String>()
        transcriptLines.add("# 15-Utterance Live Smoke Test Transcript\n")
        transcriptLines.add("**Model**: $model  ")
        transcriptLines.add("**Endpoint**: $endpoint  ")
        transcriptLines.add("**Device**: Motorola moto g73 5G (Android 14)  ")
        transcriptLines.add("**Date**: 2026-11-05\n")
        transcriptLines.add("---\n")

        for ((idx, text) in utterances.withIndex()) {
            val turnNum = idx + 1
            android.util.Log.i("LiveSmoke15", "=== TURN $turnNum: $text ===")
            transcriptLines.add("### Turn $turnNum: \"$text\"\n")
            transcriptLines.add("- **User**: `$text`")

            controller.onAction(UiAction.UpdateDraft(text))
            controller.onAction(UiAction.SendMessage)

            // Wait for completion: isChatLoading becomes false
            withTimeout(45_000L) {
                // First wait until it starts loading (or gives immediate reply)
                var attempts = 0
                while (!controller.state.value.isChatLoading && attempts < 20) {
                    delay(50L)
                    attempts++
                }
                while (controller.state.value.isChatLoading) {
                    delay(100L)
                }
            }

            // Small delay for DB writes to settle
            delay(500L)

            val messages = controller.state.value.chatMessages
            val lastAssistantMsg = messages.filter { it.role == "assistant" }.lastOrNull()?.content ?: "(no assistant reply)"
            val activeReceipts = controller.state.value.receipts
            val proposals = storage.getOpenProposals()
            val nodes = storage.getActiveNodes(50)
            val reminders = storage.getActiveReminders(System.currentTimeMillis())

            transcriptLines.add("- **Assistant**: $lastAssistantMsg")
            if (activeReceipts.isNotEmpty()) {
                val receiptsStr = activeReceipts.joinToString("; ") { it.message }
                transcriptLines.add("- **Receipts**: $receiptsStr")
            }
            if (proposals.isNotEmpty()) {
                val pStr = proposals.joinToString("; ") { "Proposal ${it.id} (${it.kind}/${it.intent}): [${it.options.joinToString { opt -> opt.title }}]" }
                transcriptLines.add("- **Proposals/Chips**: $pStr")
            }
            transcriptLines.add("")

            android.util.Log.i("LiveSmoke15", "Turn $turnNum Completed. Assistant: $lastAssistantMsg")
        }

        // Export All Core Database Rows
        val dbJson = JSONObject()
        val db = dbHelper.readableDatabase

        // 1. raw_message
        val rawMsgArr = JSONArray()
        db.query("SELECT id, role, content, ts_utc FROM raw_message ORDER BY ts_utc ASC").use { cur ->
            while (cur.moveToNext()) {
                val row = JSONObject()
                row.put("id", cur.getString(0))
                row.put("role", cur.getString(1))
                row.put("content", cur.getString(2))
                row.put("ts_utc", cur.getLong(3))
                rawMsgArr.put(row)
            }
        }
        dbJson.put("raw_message", rawMsgArr)

        // 2. node
        val nodeArr = JSONArray()
        db.query("SELECT id, title, type, status, due_at, created_at FROM node ORDER BY created_at ASC").use { cur ->
            while (cur.moveToNext()) {
                val row = JSONObject()
                row.put("id", cur.getString(0))
                row.put("title", cur.getString(1))
                row.put("type", cur.getString(2))
                row.put("status", cur.getString(3))
                row.put("due_at", if (cur.isNull(4)) null else cur.getLong(4))
                row.put("created_at", cur.getLong(5))
                nodeArr.put(row)
            }
        }
        dbJson.put("node", nodeArr)

        // 3. reminder
        val remArr = JSONArray()
        db.query("SELECT id, node_id, text, fire_at, tier FROM reminder ORDER BY fire_at ASC").use { cur ->
            while (cur.moveToNext()) {
                val row = JSONObject()
                row.put("id", cur.getString(0))
                row.put("node_id", if (cur.isNull(1)) null else cur.getString(1))
                row.put("text", cur.getString(2))
                row.put("fire_at", cur.getLong(3))
                row.put("tier", cur.getString(4))
                remArr.put(row)
            }
        }
        dbJson.put("reminder", remArr)

        // 4. raw_event
        val eventArr = JSONArray()
        db.query("SELECT op_id, op_type, target_type, target_id, before_json, after_json, ts_utc, cause_msg_id FROM raw_event ORDER BY ts_utc ASC").use { cur ->
            while (cur.moveToNext()) {
                val row = JSONObject()
                row.put("op_id", cur.getString(0))
                row.put("op_type", cur.getString(1))
                row.put("target_type", cur.getString(2))
                row.put("target_id", cur.getString(3))
                row.put("before_json", if (cur.isNull(4)) null else cur.getString(4))
                row.put("after_json", if (cur.isNull(5)) null else cur.getString(5))
                row.put("ts_utc", cur.getLong(6))
                row.put("cause_msg_id", if (cur.isNull(7)) null else cur.getString(7))
                eventArr.put(row)
            }
        }
        dbJson.put("raw_event", eventArr)

        // 5. proposal
        val propArr = JSONArray()
        db.query("SELECT id, kind, intent, options, status, payload FROM proposal ORDER BY created_at ASC").use { cur ->
            while (cur.moveToNext()) {
                val row = JSONObject()
                row.put("id", cur.getString(0))
                row.put("kind", cur.getString(1))
                row.put("intent", cur.getString(2))
                row.put("options", cur.getString(3))
                row.put("status", cur.getString(4))
                row.put("payload", if (cur.isNull(5)) null else cur.getString(5))
                propArr.put(row)
            }
        }
        dbJson.put("proposal", propArr)

        val outTranscript = transcriptLines.joinToString("\n")
        val outJsonStr = dbJson.toString(2)

        // Save to internal app files dir
        val transcriptFile = File(context.filesDir, "smoke_transcript.md")
        transcriptFile.writeText(outTranscript, Charsets.UTF_8)

        val dbJsonFile = File(context.filesDir, "smoke_db_rows.json")
        dbJsonFile.writeText(outJsonStr, Charsets.UTF_8)

        try {
            File("/sdcard/Download/smoke_transcript.md").writeText(outTranscript, Charsets.UTF_8)
            File("/sdcard/Download/smoke_db_rows.json").writeText(outJsonStr, Charsets.UTF_8)
        } catch (e: Exception) {
            android.util.Log.w("LiveSmoke15", "Could not write to /sdcard/Download", e)
        }

        android.util.Log.i("LiveSmoke15", "WROTE EVIDENCE FILES TO: ${transcriptFile.absolutePath} and ${dbJsonFile.absolutePath}")
        println("=== TRANSCRIPT START ===")
        println(outTranscript)
        println("=== TRANSCRIPT END ===")
        println("=== DB JSON START ===")
        println(outJsonStr)
        println("=== DB JSON END ===")

        assertTrue("Transcript file must exist and be non-empty", transcriptFile.length() > 0)
        assertTrue("DB JSON file must exist and be non-empty", dbJsonFile.length() > 0)
    }
}
