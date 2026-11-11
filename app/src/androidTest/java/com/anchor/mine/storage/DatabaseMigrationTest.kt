// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.mine.storage

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.anchor.core.models.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DatabaseMigrationTest {

    @Test
    fun testFreshV5SmokeTest_RunsEveryStorageMethod() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase("mine_smoke_test.db")
        val key = ByteArray(32) { 2 }

        val helper = DatabaseProvider.getHelper(context, key, "mine_smoke_test.db")
        assertEquals(MineDatabaseCallback.DATABASE_VERSION, helper.readableDatabase.version)
        val storage = AndroidStorage(helper)

        // 1. Immutable Messages
        storage.insertRawMessage("msg_s1", "user", "Hello")
        assertEquals("Hello", storage.getRawMessage("msg_s1"))
        assertEquals(1, storage.getRecentMessages(10).size)
        storage.redactMessage("msg_s1")
        assertNull(storage.getRawMessage("msg_s1"))

        // 2. Proposals with v5 payload
        val p = Proposal("p_fresh", "ambiguous", IntentType.RESCHEDULE, listOf(ProposalOption("n1", "Choice 1")), payload = "{\"targetAtMillis\":1700000000000}")
        storage.saveProposal(p)
        val savedProp = storage.getProposal("p_fresh")
        assertNotNull(savedProp)
        assertEquals("{\"targetAtMillis\":1700000000000}", savedProp!!.payload)
        assertEquals(1, storage.getOpenProposals().size)
        storage.consumeProposal("p_fresh")
        assertEquals("consumed", storage.getProposal("p_fresh")?.status)
        assertEquals(0, storage.getOpenProposals().size)
        storage.updateProposalStatus("p_fresh", "open")
        assertEquals("open", storage.getProposal("p_fresh")?.status)
        assertEquals(1, storage.getOpenProposals().size)
        storage.consumeProposalCas("p_fresh")
        assertEquals("consumed", storage.getProposal("p_fresh")?.status)
        assertEquals(0, storage.getOpenProposals().size)
        val reopened = storage.reopenProposal("p_fresh")
        assertTrue(reopened)
        assertEquals("open", storage.getProposal("p_fresh")?.status)
        assertEquals(1, storage.getOpenProposals().size)
        val reopenAgain = storage.reopenProposal("p_fresh")
        org.junit.Assert.assertFalse(reopenAgain)

        // 3. Settings
        storage.saveSetting("set1", "val1")
        assertEquals("val1", storage.getSetting("set1"))

        // 4. Nodes and Events
        val n = Node("n_s", "task", "Smoke Task", "active", dueAt = 1700000000000L)
        val e = RawEvent("e_s", "CREATE", "node", "n_s")
        storage.createNodeAndCommit(n, e, null)
        assertNotNull(storage.getNode("n_s"))
        assertEquals(1, storage.getActiveNodes(limit = 100).size)
        assertEquals(1, storage.getActiveNodeCounts(1700000000000L).totalOpen)
        assertEquals(0, storage.getStaleNodes(1700000000000L, 100000L).size)
        assertEquals(1, storage.getUrgentNodes().size)

        storage.updateNodeAndCommit(n.copy(status="done"), RawEvent("e_s2", "UPDATE", "node", "n_s"), null)
        assertEquals("done", storage.getNode("n_s")?.status)
        assertNotNull(storage.getEvent("e_s2"))

        // 5. Reminders
        val rem = Reminder("rem_1", "Smoke Reminder", 1700000000000L, "n_s", AlarmTier.ALARM)
        storage.saveReminder(rem)
        assertEquals(rem.id, storage.getReminderByNodeId("n_s")?.id)
        assertEquals(1, storage.getPendingReminders().size)
        storage.deleteReminder("rem_1")
        assertNull(storage.getReminderByNodeId("n_s"))

        val stats = storage.getDebugStats()
        assertTrue(stats.containsKey("Rows in raw_message"))

        // 6. FTS Sync Verification
        val nFts = Node("n_fts", "task", "Bananas", "active")
        val eFts = RawEvent("e_fts", "CREATE", "node", "n_fts")
        storage.createNodeAndCommit(nFts, eFts, null)
        storage.createSoulFactAndCommit(SoulFact("s_1", "diet", "Allergic to Apples", 1.0, "chat", "approved", "active", 1000L), RawEvent("e_soul", "CREATE", "soul", "s_1"))

        val searchTasks = storage.searchMemoryFts("Bananas", 5)
        assertTrue("Node FTS Trigger must fire and populate index", searchTasks.any { it.title == "Bananas" })

        val searchFacts = storage.searchMemoryFts("Apples", 5)
        assertTrue("Soul FTS Trigger must fire and populate index", searchFacts.any { it.details == "Allergic to Apples" })

        storage.insertRawMessage("msg_smoke_chat", "user", "Remember to call Dr Watson")
        val searchChat = storage.searchMemoryFts("Watson", 5)
        assertTrue("Msg Chunk FTS Trigger must fire and populate index", searchChat.any { it.kind == "chat" && it.details.contains("Watson") })
        storage.redactMessage("msg_smoke_chat")
        val searchChatAfterRedact = storage.searchMemoryFts("Watson", 5)
        assertTrue("Msg Chunk FTS Trigger must delete entry upon redaction", searchChatAfterRedact.none { it.details.contains("Watson") })

        // 7. Edges
        val edge = Edge("e1", "n_s", "n_fts", EdgeTypes.FOLLOW_UP)
        storage.saveEdge(edge)
        assertNotNull(storage.getEdge("e1"))
        assertEquals(1, storage.getEdgesForSource("n_s").size)
        assertEquals(1, storage.getEdgesForDestination("n_fts").size)
        storage.deleteEdge("e1")
        assertNull(storage.getEdge("e1"))

        // 8. Quotes
        val quote = Quote(QuoteSlot.MORNING, "2026-11-03", "A fresh morning.", 1000L, source = QuoteSource.PRESET)
        storage.saveQuoteSlot(quote)
        assertEquals("A fresh morning.", storage.getQuoteSlot(QuoteSlot.MORNING, "2026-11-03")?.text)

        // 9. Reliability State & Alarm Registry
        val rel = ReliabilityState(score = "at_risk", issuesJson = "{\"error\":\"test\"}", lastCheckAt = 12345L)
        storage.saveReliabilityState(rel)
        val loadedRel = storage.getReliabilityState()
        assertEquals("at_risk", loadedRel.score)
        assertEquals("{\"error\":\"test\"}", loadedRel.issuesJson)
        assertEquals(12345L, loadedRel.lastCheckAt)

        storage.saveRegisteredAlarm("rem_reg_1", 1001, 1700000000000L)
        val registered = storage.getRegisteredAlarms()
        assertTrue(registered.any { it.first == "rem_reg_1" && it.second == 1700000000000L })
        storage.deleteRegisteredAlarm("rem_reg_1")
        assertTrue(storage.getRegisteredAlarms().none { it.first == "rem_reg_1" })

        // 10. Outbox (B3-15 expansion)
        val job = OutboxJob(
            id = "ob_smoke_1",
            jobType = OutboxJob.JOB_CHAT_TURN,
            priority = 0,
            payloadJson = "{\"msg_id\":\"msg_s1\"}",
            idemKey = "msg_s1",
            status = OutboxJob.STATUS_PENDING,
            attempts = 0,
            nextTryAt = 1700000000000L,
            deadlineAt = null,
            createdAt = 1700000000000L,
            updatedAt = 1700000000000L
        )
        storage.enqueueOutbox(job)
        val pendingOutbox = storage.getPendingOutbox(OutboxJob.JOB_CHAT_TURN)
        assertEquals(1, pendingOutbox.size)
        assertEquals("ob_smoke_1", pendingOutbox.first().id)
        assertNull("B1-01 null preservation", pendingOutbox.first().deadlineAt)

        storage.markOutboxDone("ob_smoke_1", 1700000001000L)
        assertEquals(0, storage.getPendingOutbox(OutboxJob.JOB_CHAT_TURN).size)

        // 11. Signals (B3-15 expansion)
        val sig = SignalLog(
            id = "sig_smoke_1",
            tsUtc = 1700000000000L,
            kind = "nudge_reply",
            subject = "overdue",
            value = 1.0,
            attrs = "{}"
        )
        storage.recordSignal(sig)
        val signals = storage.getSignals("nudge_reply", 0L, 10)
        assertEquals(1, signals.size)
        assertEquals("sig_smoke_1", signals.first().id)

        // 12. DayRule (B3-15 expansion)
        val rule = DayRule(
            id = "rule_smoke_1",
            effectiveFrom = 1700000000000L,
            startMinute = 360,
            endMinute = 1320,
            tzId = "UTC",
            createdAt = 1700000000000L
        )
        storage.saveDayRule(rule)
        val effectiveRule = storage.getEffectiveDayRule(1700000000000L)
        assertEquals(360, effectiveRule.startMinute)
        assertEquals(1320, effectiveRule.endMinute)

        helper.close()
    }

    /**
     * T19: an upgrade must repair triggers and indexes, not just add columns.
     *
     * Every trigger in the schema was declared `CREATE TRIGGER IF NOT EXISTS`, so a device that
     * had already created a trigger kept the old body forever - editing the trigger SQL never
     * reached an upgraded install. Indexes were worse: they existed only in `onCreate`, so a
     * device upgraded from v1 never got `idx_reminder_fire`.
     *
     * The test corrupts a trigger body and drops the index on a live DB, then runs `onUpgrade`
     * and asserts the schema is back to what a fresh install has.
     */
    @Test
    fun testOnUpgrade_RestoresTriggersAndIndexes() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dbName = "mine_upgrade_test.db"
        context.deleteDatabase(dbName)
        val key = ByteArray(32) { 3 }

        val helper = DatabaseProvider.getHelper(context, key, dbName)
        val db = helper.writableDatabase

        // Simulate a stale install: a trigger with an old body that no longer enforces anything,
        // and a missing index.
        db.execSQL("DROP TRIGGER IF EXISTS trg_raw_msg_no_delete")
        db.execSQL(
            "CREATE TRIGGER trg_raw_msg_no_delete BEFORE DELETE ON raw_message " +
                "BEGIN SELECT RAISE(ABORT, 'stale body that does nothing'); END;"
        )
        db.execSQL("DROP INDEX IF EXISTS idx_reminder_fire")

        MineDatabaseCallback().onUpgrade(db, 1, MineDatabaseCallback.DATABASE_VERSION)

        // The index must exist again.
        var indexFound = false
        db.query("SELECT name FROM sqlite_master WHERE type='index' AND name='idx_reminder_fire'").use { c ->
            indexFound = c.moveToFirst()
        }
        assertTrue("T19: onUpgrade must recreate idx_reminder_fire", indexFound)

        // The trigger body must be the current one, not the stale one.
        var triggerSql = ""
        db.query("SELECT sql FROM sqlite_master WHERE type='trigger' AND name='trg_raw_msg_no_delete'").use { c ->
            if (c.moveToFirst()) triggerSql = c.getString(0)
        }
        assertTrue(
            "T19: onUpgrade must replace the stale trigger body (got: $triggerSql)",
            triggerSql.contains("raw_message is append-only") && !triggerSql.contains("stale body")
        )

        // And it must actually enforce: a delete is refused.
        db.execSQL(
            "INSERT INTO raw_message (id, role, content) VALUES ('t19_msg', 'user', 'x')"
        )
        var deleteRefused = false
        try {
            db.execSQL("DELETE FROM raw_message WHERE id = 't19_msg'")
        } catch (e: Exception) {
            deleteRefused = true
        }
        assertTrue("T19: the restored trigger must refuse a raw_message delete", deleteRefused)

        helper.close()
        context.deleteDatabase(dbName)
    }

    /**
     * B3-14: V5 -> V6 migration test.
     * Drops outbox table and index to simulate v5 database, then runs onUpgrade(db, 5, 6)
     * and asserts outbox table and indexes are created properly and storage operations work.
     */
    @Test
    fun testDatabaseMigration_V5toV6_CreatesOutboxTableAndIndex() {
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val dbName = "mine_v5_v6_test.db"
            context.deleteDatabase(dbName)
            val key = ByteArray(32) { 4 }

            val helper = DatabaseProvider.getHelper(context, key, dbName)
            val db = helper.writableDatabase

            // Simulate a v5 state by dropping outbox table and outbox index
            db.execSQL("DROP INDEX IF EXISTS idx_outbox_due")
            db.execSQL("DROP TABLE IF EXISTS outbox")

            // Run onUpgrade from 5 to 6
            MineDatabaseCallback().onUpgrade(db, 5, 6)

            // Verify outbox table exists in sqlite_master
            var outboxTableFound = false
            db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='outbox'").use { c ->
                outboxTableFound = c.moveToFirst()
            }
            assertTrue("B3-14: onUpgrade from 5 to 6 must create outbox table", outboxTableFound)

            // Verify idx_outbox_due index exists
            var outboxIndexFound = false
            db.query("SELECT name FROM sqlite_master WHERE type='index' AND name='idx_outbox_due'").use { c ->
                outboxIndexFound = c.moveToFirst()
            }
            assertTrue("B3-14: onUpgrade from 5 to 6 must create idx_outbox_due index", outboxIndexFound)

            // Verify outbox CRUD operations succeed through AndroidStorage
            val storage = AndroidStorage(helper)
            val testJob = OutboxJob(
                id = "ob_v6_migrated",
                jobType = OutboxJob.JOB_CHAT_TURN,
                priority = OutboxJob.PRIORITY_P0,
                payloadJson = "{\"msg\":\"v6 test\"}",
                idemKey = "idem_v6_migrated",
                status = OutboxJob.STATUS_PENDING,
                attempts = 0,
                nextTryAt = 100_000L,
                deadlineAt = null,
                createdAt = 100_000L,
                updatedAt = 100_000L
            )
            storage.enqueueOutbox(testJob)
            val pending = storage.getPendingOutbox(OutboxJob.JOB_CHAT_TURN)
            assertEquals("Enqueued outbox job must be retrievable on upgraded v6 db", 1, pending.size)
            assertEquals("ob_v6_migrated", pending.first().id)
            assertNull("B1-01 null deadline preserved on upgraded v6 db", pending.first().deadlineAt)

            helper.close()
            context.deleteDatabase(dbName)
        }
    }

    /**
     * v1.1 Item 3: V6 -> V7 migration test.
     * Drops recurrence and streak columns to simulate v6 database state, then runs onUpgrade(db, 6, 7)
     * and asserts columns and indexes are created properly and habit streak operations work.
     */
    @Test
    fun testDatabaseMigration_V6toV7_AddsRecurrenceAndStreakColumns() {
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val dbName = "mine_v6_v7_test.db"
            context.deleteDatabase(dbName)
            val key = ByteArray(32) { 5 }

            val helper = DatabaseProvider.getHelper(context, key, dbName)
            val db = helper.writableDatabase

            // Simulate a v6 state: drop index and recreate node and reminder tables as V6 schemas
            db.execSQL("DROP INDEX IF EXISTS idx_node_habit")
            db.execSQL("DROP TABLE IF EXISTS node_fts")
            db.execSQL("DROP TABLE IF EXISTS reminder")
            db.execSQL("DROP TABLE IF EXISTS node")

            // V6 DDL without recurrence/streak columns
            db.execSQL("CREATE TABLE node (id TEXT PRIMARY KEY, device_id TEXT, type TEXT, title TEXT, body TEXT, status TEXT, priority INTEGER, severity INTEGER, start_at INTEGER, due_at INTEGER, time_precision TEXT, flexible INTEGER, lane TEXT, expires_at INTEGER, source TEXT, origin TEXT, source_msg_ids TEXT, extractor_version TEXT, attrs TEXT, local_only INTEGER, hlc TEXT, created_at INTEGER, updated_at INTEGER, closed_at INTEGER, deleted_at INTEGER)")
            db.execSQL("CREATE TABLE reminder (id TEXT PRIMARY KEY, node_id TEXT, text TEXT NOT NULL, fire_at INTEGER NOT NULL, tier TEXT NOT NULL DEFAULT 'alarm')")

            // Insert existing V6 rows
            db.execSQL("INSERT INTO node (id, type, title, status, due_at, created_at, updated_at) VALUES ('n_v6', 'habit', 'Morning Walk', 'active', 1700000000000, 1700000000000, 1700000000000)")
            db.execSQL("INSERT INTO reminder (id, node_id, text, fire_at, tier) VALUES ('rem_v6', 'n_v6', 'Morning Walk', 1700000000000, 'alarm')")

            // Run onUpgrade from 6 to 7
            MineDatabaseCallback().onUpgrade(db, 6, 7)

            // Verify node table has new columns
            val nodeCols = mutableSetOf<String>()
            db.query("PRAGMA table_info(node)").use { c ->
                val nameIdx = c.getColumnIndexOrThrow("name")
                while (c.moveToNext()) {
                    nodeCols.add(c.getString(nameIdx).lowercase())
                }
            }
            assertTrue("V7 migration must add recurrence_rule to node", nodeCols.contains("recurrence_rule"))
            assertTrue("V7 migration must add streak_current to node", nodeCols.contains("streak_current"))
            assertTrue("V7 migration must add streak_best to node", nodeCols.contains("streak_best"))
            assertTrue("V7 migration must add last_completed_at to node", nodeCols.contains("last_completed_at"))

            // Verify reminder table has new columns
            val remCols = mutableSetOf<String>()
            db.query("PRAGMA table_info(reminder)").use { c ->
                val nameIdx = c.getColumnIndexOrThrow("name")
                while (c.moveToNext()) {
                    remCols.add(c.getString(nameIdx).lowercase())
                }
            }
            assertTrue("V7 migration must add rrule to reminder", remCols.contains("rrule"))
            assertTrue("V7 migration must add iteration to reminder", remCols.contains("iteration"))

            // Verify idx_node_habit index exists
            var habitIndexFound = false
            db.query("SELECT name FROM sqlite_master WHERE type='index' AND name='idx_node_habit'").use { c ->
                habitIndexFound = c.moveToFirst()
            }
            assertTrue("V7 migration must create idx_node_habit index", habitIndexFound)

            // Verify AndroidStorage can read and write migrated habit node with default streak 0
            val storage = AndroidStorage(helper)
            val migratedNode = storage.getNode("n_v6")
            assertNotNull("Migrated node must be readable", migratedNode)
            assertEquals("Default streak_current on migrated v6 row must be 0", 0, migratedNode!!.streakCurrent)
            assertEquals("Default streak_best on migrated v6 row must be 0", 0, migratedNode.streakBest)
            assertNull("Default recurrence_rule on migrated v6 row must be null", migratedNode.recurrenceRule)

            // Verify streak update persists through updateNodeAndCommit
            val updatedNode = migratedNode.copy(
                streakCurrent = 1,
                streakBest = 1,
                recurrenceRule = "FREQ=DAILY",
                lastCompletedAt = 1700000001000L,
                updatedAt = 1700000001000L
            )
            storage.updateNodeAndCommit(
                node = updatedNode,
                event = RawEvent("e_streak_1", "UPDATE", "node", "n_v6", tsUtc = 1700000001000L),
                deleteReminderId = null,
                upsertReminder = null
            )

            val reloadedNode = storage.getNode("n_v6")
            assertNotNull(reloadedNode)
            assertEquals(1, reloadedNode!!.streakCurrent)
            assertEquals(1, reloadedNode.streakBest)
            assertEquals("FREQ=DAILY", reloadedNode.recurrenceRule)
            assertEquals(1700000001000L, reloadedNode.lastCompletedAt)

            helper.close()
            context.deleteDatabase(dbName)
        }
    }

    /**
     * v1.1 Item 11 & 12: V7 -> V8 migration test.
     * Simulates a v7 database without the embedding table, executes onUpgrade(db, 7, 8)
     * and asserts embedding table and index exist, and AndroidStorage can persist and retrieve QuantVectors.
     */
    @Test
    fun testDatabaseMigration_V7toV8_CreatesEmbeddingTableAndPerformsVectorOps() {
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val dbName = "mine_v7_v8_test.db"
            context.deleteDatabase(dbName)
            val key = ByteArray(32) { 7 }

            val helper = DatabaseProvider.getHelper(context, key, dbName)
            val db = helper.writableDatabase

            // Simulate V7 by dropping embedding table and index
            db.execSQL("DROP INDEX IF EXISTS idx_embedding_owner")
            db.execSQL("DROP TABLE IF EXISTS embedding")

            // Run migration from 7 to 8
            MineDatabaseCallback().onUpgrade(db, 7, 8)

            // Verify embedding table exists
            var embeddingTableFound = false
            db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='embedding'").use { c ->
                embeddingTableFound = c.moveToFirst()
            }
            assertTrue("V8 migration must create embedding table", embeddingTableFound)

            // Verify idx_embedding_owner index exists
            var embeddingIndexFound = false
            db.query("SELECT name FROM sqlite_master WHERE type='index' AND name='idx_embedding_owner'").use { c ->
                embeddingIndexFound = c.moveToFirst()
            }
            assertTrue("V8 migration must create idx_embedding_owner index", embeddingIndexFound)

            // Verify storage operations for embeddings
            val storage = AndroidStorage(helper)
            val testVec = com.anchor.core.retrieval.QuantVector(ByteArray(256) { 42 }, 0.05f)
            storage.saveEmbedding("node", "n_embed_1", "test-model-v1", testVec)

            val loaded = storage.getEmbeddings("node", "test-model-v1")
            assertEquals(1, loaded.size)
            assertEquals("n_embed_1", loaded[0].first)
            assertEquals(256, loaded[0].second.dim)
            assertEquals(0.05f, loaded[0].second.scale, 0.0001f)
            assertEquals(42.toByte(), loaded[0].second.bytes[0])

            storage.deleteEmbedding("node", "n_embed_1")
            val emptyList = storage.getEmbeddings("node", "test-model-v1")
            assertTrue("Deleted embedding must not be returned", emptyList.isEmpty())

            helper.close()
            context.deleteDatabase(dbName)
        }
    }

    /**
     * v2 Item 1: V8 -> V9 migration test (Blueprint 43 §4).
     * Simulates a v8 database without place, place_signal, and sensing_event tables,
     * executes onUpgrade(db, 8, 9), asserts tables and indexes exist, and verifies
     * AndroidStorage methods for places and sensing events work cleanly.
     */
    @Test
    fun testDatabaseMigration_V8toV9_CreatesPlaceAndSensingTables() {
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val dbName = "mine_v8_v9_test.db"
            context.deleteDatabase(dbName)
            val key = ByteArray(32) { 8 }

            val helper = DatabaseProvider.getHelper(context, key, dbName)
            val db = helper.writableDatabase

            // Simulate V8 by dropping V9 tables and indexes
            db.execSQL("DROP INDEX IF EXISTS idx_sensing_event_ts")
            db.execSQL("DROP INDEX IF EXISTS idx_place_kind")
            db.execSQL("DROP TABLE IF EXISTS sensing_event")
            db.execSQL("DROP TABLE IF EXISTS place_signal")
            db.execSQL("DROP TABLE IF EXISTS place")

            // Run migration from 8 to 9
            MineDatabaseCallback().onUpgrade(db, 8, 9)

            // Verify place, place_signal, sensing_event tables exist
            var placeFound = false
            var placeSignalFound = false
            var sensingEventFound = false
            db.query("SELECT name FROM sqlite_master WHERE type='table'").use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(0)
                    if (name.equals("place", ignoreCase = true)) placeFound = true
                    if (name.equals("place_signal", ignoreCase = true)) placeSignalFound = true
                    if (name.equals("sensing_event", ignoreCase = true)) sensingEventFound = true
                }
            }
            assertTrue("V9 migration must create place table", placeFound)
            assertTrue("V9 migration must create place_signal table", placeSignalFound)
            assertTrue("V9 migration must create sensing_event table", sensingEventFound)

            // Verify indexes
            var sensingIndexFound = false
            var placeKindIndexFound = false
            db.query("SELECT name FROM sqlite_master WHERE type='index'").use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(0)
                    if (name.equals("idx_sensing_event_ts", ignoreCase = true)) sensingIndexFound = true
                    if (name.equals("idx_place_kind", ignoreCase = true)) placeKindIndexFound = true
                }
            }
            assertTrue("V9 migration must create idx_sensing_event_ts", sensingIndexFound)
            assertTrue("V9 migration must create idx_place_kind", placeKindIndexFound)

            // Verify AndroidStorage operations for Place, Signal, and SensingEvent
            val storage = AndroidStorage(helper)
            val homePlace = com.anchor.core.sensing.Place(
                id = "p_home_1",
                nodeId = null,
                kind = "home",
                label = "Home",
                updatedAt = 1700000000000L
            )
            val homeSignal = com.anchor.core.sensing.PlaceSignal(
                placeId = "p_home_1",
                wifiJson = """{"ssid":"HomeWifi","bssids":["aa:bb:cc:dd:ee:ff"]}""",
                geoLat = 37.422,
                geoLon = -122.084,
                geoRadiusM = 150
            )

            storage.savePlace(homePlace, homeSignal)

            val places = storage.getPlaces()
            assertEquals(1, places.size)
            assertEquals("p_home_1", places[0].id)
            assertEquals("Home", places[0].label)

            val signals = storage.getPlaceSignals()
            assertEquals(1, signals.size)
            assertEquals("p_home_1", signals[0].first.id)
            assertEquals(150, signals[0].second.geoRadiusM)
            assertTrue(signals[0].second.wifiJson!!.contains("HomeWifi"))

            // Sensing event recording and pruning
            val eventOld = com.anchor.core.sensing.SensingEvent(
                id = "se_old",
                tsUtc = 1000L,
                axis = "place",
                fromState = "Unknown",
                toState = "Home",
                source = "wifi"
            )
            val eventNew = com.anchor.core.sensing.SensingEvent(
                id = "se_new",
                tsUtc = 5000L,
                axis = "motion",
                fromState = "STILL",
                toState = "WALKING",
                source = "sensor"
            )
            storage.recordSensingEvent(eventOld)
            storage.recordSensingEvent(eventNew)

            val events = storage.getRecentSensingEvents(10)
            assertEquals(2, events.size)
            assertEquals("se_new", events[0].id) // DESC by tsUtc

            // Prune events older than 3000L -> eventOld removed
            val pruned = storage.pruneSensingEvents(3000L)
            assertEquals(1, pruned)
            val remaining = storage.getRecentSensingEvents(10)
            assertEquals(1, remaining.size)
            assertEquals("se_new", remaining[0].id)

            helper.close()
            context.deleteDatabase(dbName)
        }
    }

    /**
     * v2 Item 2: V9 -> V10 migration test (Blueprint 44 §4).
     * Simulates a v9 database without the call_event table,
     * executes onUpgrade(db, 9, 10), asserts call_event table and index exist,
     * and verifies AndroidStorage methods for call events work cleanly.
     */
    @Test
    fun testDatabaseMigration_V9toV10_CreatesCallEventTableAndIndexes() {
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val dbName = "mine_v9_v10_test.db"
            context.deleteDatabase(dbName)
            val key = ByteArray(32) { 9 }

            val helper = DatabaseProvider.getHelper(context, key, dbName)
            val db = helper.writableDatabase

            // Simulate V9 by dropping V10 table and index
            db.execSQL("DROP INDEX IF EXISTS idx_call_event_ended")
            db.execSQL("DROP TABLE IF EXISTS call_event")

            // Run migration from 9 to 10
            MineDatabaseCallback().onUpgrade(db, 9, 10)

            // Verify call_event table exists
            var callEventFound = false
            db.query("SELECT name FROM sqlite_master WHERE type='table'").use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(0)
                    if (name.equals("call_event", ignoreCase = true)) callEventFound = true
                }
            }
            assertTrue("V10 migration must create call_event table", callEventFound)

            // Verify index idx_call_event_ended exists
            var callIndexFound = false
            db.query("SELECT name FROM sqlite_master WHERE type='index'").use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(0)
                    if (name.equals("idx_call_event_ended", ignoreCase = true)) callIndexFound = true
                }
            }
            assertTrue("V10 migration must create idx_call_event_ended", callIndexFound)

            // Verify AndroidStorage operations for CallEvent
            val storage = AndroidStorage(helper)
            val event1 = com.anchor.core.nudge.CallEvent(
                id = "call_1",
                startedAt = 1000L,
                endedAt = 70_000L,
                durationS = 69,
                direction = "in",
                contactRef = null,
                nudgeId = null,
                state = com.anchor.core.nudge.CallNudgeState.PENDING,
                createdAt = 70_000L
            )
            val event2 = com.anchor.core.nudge.CallEvent(
                id = "call_2",
                startedAt = 80_000L,
                endedAt = 150_000L,
                durationS = 70,
                direction = "out",
                contactRef = "Alice",
                nudgeId = "nudge_2",
                state = com.anchor.core.nudge.CallNudgeState.ANSWERED,
                createdAt = 150_000L
            )

            storage.saveCallEvent(event1)
            storage.saveCallEvent(event2)

            val fetched = storage.getCallEvent("call_1")
            assertNotNull(fetched)
            assertEquals(69, fetched?.durationS)
            assertEquals(com.anchor.core.nudge.CallNudgeState.PENDING, fetched?.state)

            val recent = storage.getRecentCallEvents(10)
            assertEquals(2, recent.size)
            assertEquals("call_2", recent[0].id) // DESC by endedAt

            val between = storage.getCallEventsBetween(50_000L, 100_000L)
            assertEquals(1, between.size)
            assertEquals("call_1", between[0].id)

            // Update state
            storage.updateCallEventState("call_1", com.anchor.core.nudge.CallNudgeState.SKIPPED, "nudge_1")
            val updated = storage.getCallEvent("call_1")
            assertEquals(com.anchor.core.nudge.CallNudgeState.SKIPPED, updated?.state)
            assertEquals("nudge_1", updated?.nudgeId)

            // Pruning
            val pruned = storage.pruneCallEvents(100_000L)
            assertEquals(1, pruned) // call_1 pruned
            val remaining = storage.getRecentCallEvents(10)
            assertEquals(1, remaining.size)
            assertEquals("call_2", remaining[0].id)

            helper.close()
            context.deleteDatabase(dbName)
        }
    }
}
