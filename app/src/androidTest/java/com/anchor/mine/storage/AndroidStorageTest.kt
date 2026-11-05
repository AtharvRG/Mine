// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.mine.storage

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.anchor.core.models.*
import com.anchor.core.storage.ProposalAlreadyResolvedException
import com.anchor.mine.alarms.BootReceiver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.time.Duration.Companion.milliseconds

@RunWith(AndroidJUnit4::class)
class AndroidStorageTest {

    private lateinit var storage: AndroidStorage
    private lateinit var dbHelper: androidx.sqlite.db.SupportSQLiteOpenHelper

    @Before
    fun setup() {
        // Clear global state leaked from AlarmSelfTest
        com.anchor.mine.alarms.AlarmReceiver.lastFiredId = null

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase("mine_test.db") // Isolated Test DB
        val key = ByteArray(32) { 1 } // Stable key for test
        dbHelper = DatabaseProvider.getHelper(context, key, "mine_test.db")
        storage = AndroidStorage(dbHelper)
    }

    @After
    fun teardown() {
        dbHelper.close()
    }

    @Test
    fun testSaveAndReadProposal() = runBlocking {
        val p = Proposal("p1", "ambiguous", IntentType.CANCEL, listOf(ProposalOption("n1", "Option 1"), ProposalOption("n2", "Option 2")))
        storage.saveProposal(p)
        
        val readProposal = storage.getProposal("p1")
        assertNotNull(readProposal)
        assertEquals(2, readProposal!!.options.size)
        assertEquals("n1", readProposal.options[0].id)
        assertEquals("Option 1", readProposal.options[0].title)
    }

    @Test
    fun testMessages_RolesAndNewest30() = runBlocking {
        for (i in 1..40) {
            storage.insertRawMessage("msg_$i", if (i % 2 == 0) "assistant" else "user", "Content $i")
            Thread.sleep(2) // Ensure distinct timestamps
        }
        
        val recent = storage.getRecentMessages(30)
        assertEquals(30, recent.size)
        // Must be chronological: oldest of the 30 first
        assertEquals("msg_11", recent.first().id)
        assertEquals("user", recent.first().role)
        assertEquals("msg_40", recent.last().id)
        assertEquals("assistant", recent.last().role)
    }

    @Test
    fun testCommitChange_UpdateNodeAndEvent() = runBlocking {
        // Initial insert
        val n = Node("n1", "task", "Test Task", "active")
        val e1 = RawEvent("e1", "CREATE", "node", "n1")
        val r = Reminder("r1", "Test", System.currentTimeMillis() + 10000)
        storage.createNodeAndCommit(n, e1, r)

        // Update
        val updatedNode = n.copy(status = "done", closedAt = 12345L)
        val e2 = RawEvent("e2", "COMPLETE", "node", "n1", beforeJson = "{}", afterJson = "{}")
        storage.updateNodeAndCommit(updatedNode, e2, "r1")

        // Assertions
        val fetchedNode = storage.getNode("n1")
        assertEquals("done", fetchedNode?.status)
        assertEquals(12345L, fetchedNode?.closedAt)

        val fetchedEvent = storage.getEvent("e2")
        assertEquals("COMPLETE", fetchedEvent?.opType)
        assertEquals("{}", fetchedEvent?.beforeJson)

        assertTrue(storage.getActiveReminders().isEmpty())
    }

    @Test
    fun testBootReceiver_FiresMissedReminders() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        // Fix: Use the REAL app storage for this test, because BootReceiver and AlarmReceiver
        // explicitly connect to the app's real database.
        val appStorage = (context.applicationContext as com.anchor.mine.MineApp).sharedStorage.await()
        appStorage.deleteReminder("test_alarm_1")
        appStorage.deleteReminder("test_gentle_1")

        // Push the reminder 70 seconds into the past so it formally qualifies as "missed" by the Sweeper
        val rId = "r_missed_${System.currentTimeMillis()}"
        val r = com.anchor.core.models.Reminder(rId, "Missed Task", System.currentTimeMillis() - 70000L)
        appStorage.saveReminder(r)

        com.anchor.mine.alarms.AlarmReceiver.lastFiredId = null

        val receiver = com.anchor.mine.alarms.BootReceiver()
        val intent = android.content.Intent(android.content.Intent.ACTION_BOOT_COMPLETED)

        receiver.onReceive(context, intent)

        var elapsed = 0
        while (com.anchor.mine.alarms.AlarmReceiver.lastFiredId != rId && elapsed < 5000) {
            kotlinx.coroutines.delay(250.milliseconds)
            elapsed += 250
        }

        // Cleanup
        appStorage.deleteReminder(rId)

        org.junit.Assert.assertEquals("BootReceiver must broadcast the missed alarm to AlarmReceiver", rId, com.anchor.mine.alarms.AlarmReceiver.lastFiredId)
    }

    @Test
    fun testStrictRedactionTrigger() = runBlocking {
        storage.insertRawMessage("msg_redact", "user", "Secret info")
        
        // 1. Normal UPDATE aborts
        var threw = false
        try {
            dbHelper.writableDatabase.execSQL("UPDATE raw_message SET content = 'Changed' WHERE id = 'msg_redact'")
        } catch (_: Exception) { threw = true }
        assertTrue("Normal update must abort", threw)

        // 2. Valid Redaction passes
        storage.redactMessage("msg_redact")
        val content = storage.getRawMessage("msg_redact")
        assertNull("BP11 requires content to be NULL after redaction", content)

        // 3. Double redaction aborts
        threw = false
        try {
            storage.redactMessage("msg_redact")
        } catch (_: Exception) { threw = true }
        assertTrue("Double redaction must abort", threw)
    }

    @Test
    fun testMonotonicDayKey_PreventsTimeTravel() = kotlinx.coroutines.runBlocking {
        val db = dbHelper.writableDatabase

        // 1. Manually insert a message with a rogue runaway "future" day_key (simulating clock was corrupted/far ahead)
        db.execSQL(
            "INSERT INTO raw_message (id, device_id, ts_utc, tz_offset_min, day_key, role, content) VALUES (?, ?, ?, ?, ?, ?, ?)",
            arrayOf<Any>("msg_future", "test_dev", System.currentTimeMillis() - 10000, 0, "2099-12-31", "user", "Future")
        )

        // 2. Insert a normal message via AndroidStorage (clock is now back to reality)
        storage.insertRawMessage("msg_present", "user", "Present")

        // 3. Verify the runaway defense prevented inheriting 2099-12-31, bounding to current day key
        dbHelper.readableDatabase.query("SELECT day_key FROM raw_message WHERE id = 'msg_present'").use {
            org.junit.Assert.assertTrue(it.moveToFirst())
            val presentDayKey = it.getString(0)
            org.junit.Assert.assertNotEquals("Runaway future day_key must not be inherited", "2099-12-31", presentDayKey)
            org.junit.Assert.assertTrue("Day key must be bounded by current date/maxDayKey", presentDayKey < "2099-12-31")
        }
    }

    @Test
    fun testD104_ActiveNodesPaginationAndCountsOnRealDb() = runBlocking {
        val now = 1_700_000_000_000L
        val staleCutoff = 30L * 24 * 3600 * 1000L
        val thirtyFiveDaysAgo = now - 35L * 24 * 3600 * 1000L

        // Insert 20 nodes directly via SQLite
        // 2 stale nodes (created 35 days ago, updated 35 days ago)
        // 3 overdue nodes (due in past, updated recently)
        // 5 future due nodes
        // 10 undated nodes
        val db = dbHelper.writableDatabase

        for (i in 1..2) {
            db.execSQL(
                "INSERT INTO node (id, type, title, status, due_at, created_at, updated_at) VALUES (?, 'task', ?, 'active', NULL, ?, ?)",
                arrayOf<Any>("stale_$i", "Stale $i", thirtyFiveDaysAgo, thirtyFiveDaysAgo)
            )
        }

        for (i in 1..3) {
            val due = now - (i * 3600_000L)
            db.execSQL(
                "INSERT INTO node (id, type, title, status, due_at, created_at, updated_at) VALUES (?, 'task', ?, 'active', ?, ?, ?)",
                arrayOf<Any>("overdue_$i", "Overdue $i", due, now - 1000, now - 1000)
            )
        }

        for (i in 1..5) {
            val due = now + (i * 3600_000L)
            db.execSQL(
                "INSERT INTO node (id, type, title, status, due_at, created_at, updated_at) VALUES (?, 'task', ?, 'active', ?, ?, ?)",
                arrayOf<Any>("future_$i", "Future $i", due, now - 1000, now - 1000)
            )
        }

        for (i in 1..10) {
            db.execSQL(
                "INSERT INTO node (id, type, title, status, due_at, created_at, updated_at) VALUES (?, 'task', ?, 'active', NULL, ?, ?)",
                arrayOf<Any>("undated_$i", "Undated $i", now - 1000, now - 1000)
            )
        }

        // 1. Verify getActiveNodeCounts:
        // total open (non-stale) = 3 overdue + 5 future + 10 undated = 18.
        // overdue = 3.
        val counts = storage.getActiveNodeCounts(now, staleCutoff)
        assertEquals(18, counts.totalOpen)
        assertEquals(3, counts.overdue)

        // 2. Verify getStaleNodes:
        val stale = storage.getStaleNodes(now, staleCutoff)
        assertEquals(2, stale.size)
        assertTrue(stale.any { it.id == "stale_1" })
        assertTrue(stale.any { it.id == "stale_2" })

        // 3. Verify Keyset Cursor Pagination over all 20 active nodes
        val pageSize = 6
        val visitedIds = mutableListOf<String>()
        var cursor: String? = null

        while (true) {
            val page = storage.getActiveNodes(limit = pageSize, cursor = cursor)
            if (page.isEmpty()) break
            page.forEach { visitedIds.add(it.id) }
            val last = page.last()
            cursor = "${last.dueAt ?: "null"}:${last.id}"
        }

        assertEquals("Must visit all 20 nodes across pages", 20, visitedIds.size)
        assertEquals("Zero duplicates across pages", 20, visitedIds.toSet().size)

        // Ensure order: dated items come before undated items (null due_at)
        val firstUndatedIdx = visitedIds.indexOfFirst { it.startsWith("undated_") || it.startsWith("stale_") }
        val lastDatedIdx = visitedIds.indexOfLast { it.startsWith("overdue_") || it.startsWith("future_") }
        assertTrue("Dated items must precede undated items", lastDatedIdx < firstUndatedIdx)
    }

    @Test
    fun testR2_StorageContract_AndroidStorage(): Unit = runBlocking {
        val now = 1_700_000_000_000L
        val staleCutoff = 30L * 24 * 3600 * 1000L
        val db = dbHelper.writableDatabase

        db.execSQL("INSERT INTO node (id, type, title, status, due_at, created_at, updated_at) VALUES ('n_walk', 'task', 'Walk the dog', 'active', ?, ?, ?)", arrayOf(now + 10_000L, now - 1000L, now - 1000L))
        db.execSQL("INSERT INTO node (id, type, title, status, due_at, created_at, updated_at) VALUES ('n_groceries', 'task', 'Buy groceries', 'active', ?, ?, ?)", arrayOf(now + 7200_000L, now - 1000L, now - 1000L))
        db.execSQL("INSERT INTO node (id, type, title, status, due_at, created_at, updated_at) VALUES ('n_overdue_fresh', 'task', 'Pay electric bill', 'active', ?, ?, ?)", arrayOf(now - 3600_000L, now - 1000L, now - 1000L))
        db.execSQL("INSERT INTO node (id, type, title, status, due_at, created_at, updated_at) VALUES ('n_overdue_stale', 'task', 'Old parking ticket', 'active', ?, ?, ?)", arrayOf(now - (40L * 86400000L), now - (40L * 86400000L), now - (35L * 86400000L)))
        db.execSQL("INSERT INTO node (id, type, title, status, due_at, created_at, updated_at, attrs) VALUES ('n_pinned', 'task', 'Keep kitchen clean', 'active', NULL, ?, ?, '{\"pinned\":true}')", arrayOf(now - 1000L, now - 1000L))
        db.execSQL("INSERT INTO node (id, type, title, status, due_at, created_at, updated_at, severity) VALUES ('n_urgent', 'task', 'Fix water leak', 'active', NULL, ?, ?, 3)", arrayOf(now - 1000L, now - 1000L))
        db.execSQL("INSERT INTO node (id, type, title, status, due_at, created_at, updated_at) VALUES ('n_done', 'task', 'Walk the dog', 'done', ?, ?, ?)", arrayOf(now - 10_000L, now - 2000L, now - 1000L))
        db.execSQL("INSERT INTO node (id, type, title, status, due_at, created_at, updated_at) VALUES ('n_deleted', 'task', 'Walk the dog', 'deleted', ?, ?, ?)", arrayOf(now - 10_000L, now - 2000L, now - 1000L))

        // 1. findActiveNodeByTitle & findActiveNodesByTitle
        val foundWalk = storage.findActiveNodeByTitle("  walk the DOG  ")
        assertNotNull(foundWalk)
        assertEquals("n_walk", foundWalk?.id)

        val foundWalks = storage.findActiveNodesByTitle("  walk the DOG  ")
        assertEquals(listOf("n_walk"), foundWalks.map { it.id })

        val foundGroceries = storage.findActiveNodeByTitle("buy groceries")
        assertNotNull(foundGroceries)
        assertEquals("n_groceries", foundGroceries?.id)

        val foundMissing = storage.findActiveNodeByTitle("does not exist")
        assertEquals(null, foundMissing)
        assertTrue(storage.findActiveNodesByTitle("does not exist").isEmpty())

        // 2. getNodesDueBetween
        val dueSoon = storage.getNodesDueBetween(now, now + 86400000L, limit = 10)
        assertEquals(listOf("n_walk", "n_groceries"), dueSoon.map { it.id })

        // 3. getOverdueNodes
        val overdue = storage.getOverdueNodes(now, limit = 10)
        assertEquals(listOf("n_overdue_stale", "n_overdue_fresh"), overdue.map { it.id })

        // 4. getRankedCandidates: must exclude stale overdue, done, deleted; include fresh overdue, due soon, pinned, high severity
        val ranked = storage.getRankedCandidates(now, staleCutoff, perTierLimit = 10)
        val rankedIds = ranked.map { it.id }.toSet()
        assertTrue("Must include fresh overdue", rankedIds.contains("n_overdue_fresh"))
        assertFalse("Must NOT include stale overdue", rankedIds.contains("n_overdue_stale"))
        assertTrue("Must include due soon", rankedIds.contains("n_walk") && rankedIds.contains("n_groceries"))
        assertTrue("Must include pinned", rankedIds.contains("n_pinned"))
        assertTrue("Must include high severity", rankedIds.contains("n_urgent"))
        assertFalse("Must NOT include done or deleted", rankedIds.contains("n_done") || rankedIds.contains("n_deleted"))
    }

    @Test
    fun testReminderTier_SaveAndReadAlarmAndGentle(): Unit = runBlocking {
        val rAlarm = Reminder("r_alarm", "Alarm task", System.currentTimeMillis() + 100000L, "n_a", AlarmTier.ALARM)
        val rGentle = Reminder("r_gentle", "Gentle task", System.currentTimeMillis() + 200000L, "n_g", AlarmTier.GENTLE)

        storage.saveReminder(rAlarm)
        storage.saveReminder(rGentle)

        val pending = storage.getPendingReminders()
        val fetchedAlarm = pending.find { it.id == "r_alarm" }
        val fetchedGentle = pending.find { it.id == "r_gentle" }

        assertNotNull(fetchedAlarm)
        assertEquals(AlarmTier.ALARM, fetchedAlarm!!.tier)

        assertNotNull(fetchedGentle)
        assertEquals(AlarmTier.GENTLE, fetchedGentle!!.tier)
    }

    @Test
    fun testDatabaseMigration_V1toV3_PreservesRemindersAndDefaultsToAlarm(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val migrationDbName = "mine_migration_test.db"
        context.deleteDatabase(migrationDbName)
        val key = ByteArray(32) { 2 }

        // 1. Create DB with V1 schema (no 'tier' column on reminder)
        val v1Callback = object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(1) {
            override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE app_meta (\"key\" TEXT PRIMARY KEY, value TEXT NOT NULL)")
                db.execSQL("CREATE TABLE raw_message (id TEXT PRIMARY KEY, device_id TEXT, ts_utc INTEGER, tz_offset_min INTEGER, day_key TEXT, role TEXT, content TEXT, redacted_at INTEGER, reasoning TEXT, tags TEXT, persona_id TEXT, model_ref TEXT, tokens_in INTEGER, tokens_out INTEGER, client_msg_id TEXT, attrs TEXT)")
                db.execSQL("CREATE TABLE raw_event (op_id TEXT PRIMARY KEY, ts_utc INTEGER, device_id TEXT, actor TEXT, op_type TEXT, target_type TEXT, target_id TEXT, before_json TEXT, after_json TEXT, cause_msg_id TEXT, undo_of TEXT, attrs TEXT)")
                db.execSQL("CREATE TABLE node (id TEXT PRIMARY KEY, device_id TEXT, type TEXT, title TEXT, body TEXT, status TEXT, priority INTEGER, severity INTEGER, start_at INTEGER, due_at INTEGER, time_precision TEXT, flexible INTEGER, lane TEXT, expires_at INTEGER, source TEXT, origin TEXT, source_msg_ids TEXT, extractor_version TEXT, attrs TEXT, local_only INTEGER, hlc TEXT, created_at INTEGER, updated_at INTEGER, closed_at INTEGER, deleted_at INTEGER)")
                db.execSQL("CREATE TABLE edge (id TEXT PRIMARY KEY, device_id TEXT, src_id TEXT, dst_id TEXT, dst_kind TEXT, type TEXT, weight REAL, source TEXT, origin TEXT, source_msg_ids TEXT, attrs TEXT, hlc TEXT, created_at INTEGER, updated_at INTEGER, deleted_at INTEGER)")
                db.execSQL("CREATE TABLE soul_fact (id TEXT PRIMARY KEY, device_id TEXT NOT NULL, category TEXT NOT NULL, statement TEXT NOT NULL, attrs TEXT, confidence REAL NOT NULL DEFAULT 0.7, source TEXT NOT NULL, origin TEXT NOT NULL, status TEXT NOT NULL, pinned_core INTEGER NOT NULL DEFAULT 0, sensitive INTEGER NOT NULL DEFAULT 0, local_only INTEGER NOT NULL DEFAULT 0, hlc TEXT NOT NULL, valid_from INTEGER, valid_to INTEGER, supersedes TEXT, source_msg_ids TEXT, extractor_version TEXT, last_confirmed_at INTEGER, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, deleted_at INTEGER)")
                db.execSQL("CREATE TABLE msg_chunk (id TEXT PRIMARY KEY, day_key TEXT NOT NULL, ts_start INTEGER NOT NULL, ts_end INTEGER NOT NULL, msg_ids TEXT NOT NULL, text TEXT NOT NULL, extractor_version TEXT NOT NULL)")
                db.execSQL("CREATE TABLE reminder (id TEXT PRIMARY KEY, node_id TEXT, text TEXT NOT NULL, fire_at INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE proposal (id TEXT PRIMARY KEY, kind TEXT NOT NULL, intent TEXT NOT NULL, options TEXT NOT NULL, status TEXT NOT NULL DEFAULT 'open', created_at INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE day_rule (id TEXT PRIMARY KEY, effective_from INTEGER NOT NULL, start_minute INTEGER NOT NULL, end_minute INTEGER NOT NULL, tz_id TEXT NOT NULL, created_at INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE day_block (day_key TEXT NOT NULL, idx INTEGER NOT NULL, start_msg_id TEXT NOT NULL, start_ts INTEGER NOT NULL, end_ts INTEGER NOT NULL, msg_count INTEGER NOT NULL, part_of_day TEXT, PRIMARY KEY (day_key, idx))")
                db.execSQL("CREATE TABLE export_log (id TEXT PRIMARY KEY, ts INTEGER NOT NULL, kind TEXT NOT NULL, options_json TEXT, counts_json TEXT, bytes INTEGER, verified INTEGER, note TEXT)")
                db.execSQL("CREATE TABLE signal_log (id TEXT PRIMARY KEY, ts_utc INTEGER NOT NULL, kind TEXT NOT NULL, subject TEXT, value REAL, attrs TEXT)")
                db.execSQL("CREATE TABLE learned_pref (key TEXT NOT NULL, scope TEXT NOT NULL, value_json TEXT NOT NULL, samples INTEGER NOT NULL, confidence REAL NOT NULL, locked INTEGER NOT NULL DEFAULT 0, updated_at INTEGER NOT NULL, PRIMARY KEY (key, scope))")
                db.execSQL("CREATE TABLE reliability_state (id INTEGER PRIMARY KEY CHECK (id = 1), last_check_at INTEGER, score TEXT, issues_json TEXT, sweeper_heartbeat INTEGER, last_force_stop_detected INTEGER, p95_latency_ms INTEGER)")
                db.execSQL("CREATE TABLE diag_log (ts INTEGER NOT NULL, level TEXT NOT NULL, tag TEXT NOT NULL, msg TEXT NOT NULL)")
                db.execSQL("CREATE TABLE reminder_fire (id TEXT PRIMARY KEY, reminder_id TEXT NOT NULL, fired_at INTEGER NOT NULL, scheduled_for INTEGER NOT NULL, latency_ms INTEGER NOT NULL, outcome TEXT NOT NULL, delivery TEXT, reason_json TEXT, opener_source TEXT)")
                db.execSQL("CREATE TABLE alarm_registry (reminder_id TEXT PRIMARY KEY, request_code INTEGER NOT NULL, api TEXT NOT NULL, registered_for INTEGER NOT NULL, registered_at INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE proactive_state (id INTEGER PRIMARY KEY CHECK (id = 1), open_thread_msg_id TEXT, thread_opened_at INTEGER, backoff_step INTEGER NOT NULL DEFAULT 0, last_sent_at INTEGER, last_contact_at INTEGER, paused_until INTEGER, silent_days INTEGER NOT NULL DEFAULT 0, sent_today INTEGER NOT NULL DEFAULT 0, fuse_tripped_at INTEGER, day_key TEXT)")
                db.execSQL("CREATE TABLE nudge_log (id TEXT PRIMARY KEY, ts_utc INTEGER NOT NULL, trigger_type TEXT NOT NULL, subject_refs TEXT, score REAL, threshold REAL, gates_json TEXT, decision TEXT NOT NULL, delivery TEXT, msg_id TEXT, text_source TEXT, outcome TEXT, latency_ms INTEGER, reason_json TEXT)")
                db.execSQL("CREATE TABLE nudge_pool (id TEXT PRIMARY KEY, trigger_type TEXT NOT NULL, subject_refs TEXT, score REAL NOT NULL, created_at INTEGER NOT NULL, expires_at INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE nudge_hold (node_id TEXT NOT NULL, trigger_type TEXT NOT NULL, until_ts INTEGER NOT NULL, PRIMARY KEY (node_id, trigger_type))")
                db.execSQL("CREATE TABLE checkin_state (kind TEXT NOT NULL, day_key TEXT NOT NULL, status TEXT NOT NULL, msg_id TEXT, scheduled_for INTEGER, delivered_at INTEGER, PRIMARY KEY (kind, day_key))")

                db.execSQL("CREATE INDEX idx_node_status_due ON node(status, due_at)")
                db.execSQL("CREATE INDEX idx_raw_event_target ON raw_event(target_type, target_id)")
                db.execSQL("CREATE INDEX idx_raw_message_ts ON raw_message(ts_utc)")
                db.execSQL("CREATE INDEX idx_raw_message_day ON raw_message(day_key, ts_utc)")
                db.execSQL("CREATE INDEX idx_reminder_fire ON reminder(fire_at)")

                db.execSQL("CREATE VIRTUAL TABLE node_fts USING fts5(title, body, content='node', content_rowid='rowid')")
                db.execSQL("CREATE VIRTUAL TABLE soul_fts USING fts5(statement, content='soul_fact', content_rowid='rowid')")
                db.execSQL("CREATE VIRTUAL TABLE msg_chunk_fts USING fts5(text, content='msg_chunk', content_rowid='rowid')")

                MineDatabaseCallback.createTriggers(db)
            }
            override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
        }
        val v1Config = androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(migrationDbName)
            .callback(v1Callback)
            .build()
        val v1Helper = net.zetetic.database.sqlcipher.SupportOpenHelperFactory(key).create(v1Config)
        val v1Db = v1Helper.writableDatabase
        v1Db.execSQL("INSERT INTO reminder (id, node_id, text, fire_at) VALUES ('r_v1', 'n_v1', 'Legacy Task', 1234567890)")
        v1Helper.close()

        // 2. Open DB with MineDatabaseCallback (version 5)
        val v3Helper = DatabaseProvider.getHelper(context, key, migrationDbName)
        val v3Storage = AndroidStorage(v3Helper)

        // Verify version bumped to 5
        assertEquals(5, v3Helper.readableDatabase.version)

        // Verify column exists
        var hasTierColumn = false
        v3Helper.readableDatabase.query("PRAGMA table_info(reminder)").use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            while (cursor.moveToNext()) {
                if (nameIndex != -1 && cursor.getString(nameIndex).equals("tier", ignoreCase = true)) {
                    hasTierColumn = true
                    break
                }
            }
        }
        assertTrue("Migrated table must contain 'tier' column", hasTierColumn)

        // 3. Read legacy reminder; should default to AlarmTier.ALARM
        val legacy = v3Storage.getReminderByNodeId("n_v1")
        assertNotNull(legacy)
        assertEquals("r_v1", legacy!!.id)
        assertEquals(AlarmTier.ALARM, legacy.tier)

        // 4. Save a gentle reminder into migrated DB and verify it persists
        val gentleRem = Reminder("r_gentle", "Gentle Reminder", System.currentTimeMillis() + 50000L, "n_gentle", AlarmTier.GENTLE)
        v3Storage.saveReminder(gentleRem)
        val readGentle = v3Storage.getReminderByNodeId("n_gentle")
        assertNotNull(readGentle)
        assertEquals(AlarmTier.GENTLE, readGentle!!.tier)

        v3Helper.close()
        context.deleteDatabase(migrationDbName)
    }

    @Test
    fun testDatabaseMigration_V2toV3_CreatesEdgeIndexes(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val migrationDbName = "mine_migration_v2_v3.db"
        context.deleteDatabase(migrationDbName)
        val key = ByteArray(32) { 3 }

        // 1. Create DB with V2 schema (has 'tier' on reminder, but lacks edge indices)
        val v2Callback = object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(2) {
            override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE app_meta (\"key\" TEXT PRIMARY KEY, value TEXT NOT NULL)")
                db.execSQL("CREATE TABLE raw_message (id TEXT PRIMARY KEY, device_id TEXT, ts_utc INTEGER, tz_offset_min INTEGER, day_key TEXT, role TEXT, content TEXT, redacted_at INTEGER, reasoning TEXT, tags TEXT, persona_id TEXT, model_ref TEXT, tokens_in INTEGER, tokens_out INTEGER, client_msg_id TEXT, attrs TEXT)")
                db.execSQL("CREATE TABLE raw_event (op_id TEXT PRIMARY KEY, ts_utc INTEGER, device_id TEXT, actor TEXT, op_type TEXT, target_type TEXT, target_id TEXT, before_json TEXT, after_json TEXT, cause_msg_id TEXT, undo_of TEXT, attrs TEXT)")
                db.execSQL("CREATE TABLE node (id TEXT PRIMARY KEY, device_id TEXT, type TEXT, title TEXT, body TEXT, status TEXT, priority INTEGER, severity INTEGER, start_at INTEGER, due_at INTEGER, time_precision TEXT, flexible INTEGER, lane TEXT, expires_at INTEGER, source TEXT, origin TEXT, source_msg_ids TEXT, extractor_version TEXT, attrs TEXT, local_only INTEGER, hlc TEXT, created_at INTEGER, updated_at INTEGER, closed_at INTEGER, deleted_at INTEGER)")
                db.execSQL("CREATE TABLE edge (id TEXT PRIMARY KEY, device_id TEXT, src_id TEXT, dst_id TEXT, dst_kind TEXT, type TEXT, weight REAL, source TEXT, origin TEXT, source_msg_ids TEXT, attrs TEXT, hlc TEXT, created_at INTEGER, updated_at INTEGER, deleted_at INTEGER)")
                db.execSQL("CREATE TABLE soul_fact (id TEXT PRIMARY KEY, device_id TEXT NOT NULL, category TEXT NOT NULL, statement TEXT NOT NULL, attrs TEXT, confidence REAL NOT NULL DEFAULT 0.7, source TEXT NOT NULL, origin TEXT NOT NULL, status TEXT NOT NULL, pinned_core INTEGER NOT NULL DEFAULT 0, sensitive INTEGER NOT NULL DEFAULT 0, local_only INTEGER NOT NULL DEFAULT 0, hlc TEXT NOT NULL, valid_from INTEGER, valid_to INTEGER, supersedes TEXT, source_msg_ids TEXT, extractor_version TEXT, last_confirmed_at INTEGER, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, deleted_at INTEGER)")
                db.execSQL("CREATE TABLE msg_chunk (id TEXT PRIMARY KEY, day_key TEXT NOT NULL, ts_start INTEGER NOT NULL, ts_end INTEGER NOT NULL, msg_ids TEXT NOT NULL, text TEXT NOT NULL, extractor_version TEXT NOT NULL)")
                db.execSQL("CREATE TABLE reminder (id TEXT PRIMARY KEY, node_id TEXT, text TEXT NOT NULL, fire_at INTEGER NOT NULL, tier TEXT NOT NULL DEFAULT 'alarm')")
                db.execSQL("CREATE TABLE proposal (id TEXT PRIMARY KEY, kind TEXT NOT NULL, intent TEXT NOT NULL, options TEXT NOT NULL, status TEXT NOT NULL DEFAULT 'open', created_at INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE day_rule (id TEXT PRIMARY KEY, effective_from INTEGER NOT NULL, start_minute INTEGER NOT NULL, end_minute INTEGER NOT NULL, tz_id TEXT NOT NULL, created_at INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE day_block (day_key TEXT NOT NULL, idx INTEGER NOT NULL, start_msg_id TEXT NOT NULL, start_ts INTEGER NOT NULL, end_ts INTEGER NOT NULL, msg_count INTEGER NOT NULL, part_of_day TEXT, PRIMARY KEY (day_key, idx))")
                db.execSQL("CREATE TABLE export_log (id TEXT PRIMARY KEY, ts INTEGER NOT NULL, kind TEXT NOT NULL, options_json TEXT, counts_json TEXT, bytes INTEGER, verified INTEGER, note TEXT)")
                db.execSQL("CREATE TABLE signal_log (id TEXT PRIMARY KEY, ts_utc INTEGER NOT NULL, kind TEXT NOT NULL, subject TEXT, value REAL, attrs TEXT)")
                db.execSQL("CREATE TABLE learned_pref (key TEXT NOT NULL, scope TEXT NOT NULL, value_json TEXT NOT NULL, samples INTEGER NOT NULL, confidence REAL NOT NULL, locked INTEGER NOT NULL DEFAULT 0, updated_at INTEGER NOT NULL, PRIMARY KEY (key, scope))")
                db.execSQL("CREATE TABLE reliability_state (id INTEGER PRIMARY KEY CHECK (id = 1), last_check_at INTEGER, score TEXT, issues_json TEXT, sweeper_heartbeat INTEGER, last_force_stop_detected INTEGER, p95_latency_ms INTEGER)")
                db.execSQL("CREATE TABLE diag_log (ts INTEGER NOT NULL, level TEXT NOT NULL, tag TEXT NOT NULL, msg TEXT NOT NULL)")
                db.execSQL("CREATE TABLE reminder_fire (id TEXT PRIMARY KEY, reminder_id TEXT NOT NULL, fired_at INTEGER NOT NULL, scheduled_for INTEGER NOT NULL, latency_ms INTEGER NOT NULL, outcome TEXT NOT NULL, delivery TEXT, reason_json TEXT, opener_source TEXT)")
                db.execSQL("CREATE TABLE alarm_registry (reminder_id TEXT PRIMARY KEY, request_code INTEGER NOT NULL, api TEXT NOT NULL, registered_for INTEGER NOT NULL, registered_at INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE proactive_state (id INTEGER PRIMARY KEY CHECK (id = 1), open_thread_msg_id TEXT, thread_opened_at INTEGER, backoff_step INTEGER NOT NULL DEFAULT 0, last_sent_at INTEGER, last_contact_at INTEGER, paused_until INTEGER, silent_days INTEGER NOT NULL DEFAULT 0, sent_today INTEGER NOT NULL DEFAULT 0, fuse_tripped_at INTEGER, day_key TEXT)")
                db.execSQL("CREATE TABLE nudge_log (id TEXT PRIMARY KEY, ts_utc INTEGER NOT NULL, trigger_type TEXT NOT NULL, subject_refs TEXT, score REAL, threshold REAL, gates_json TEXT, decision TEXT NOT NULL, delivery TEXT, msg_id TEXT, text_source TEXT, outcome TEXT, latency_ms INTEGER, reason_json TEXT)")
                db.execSQL("CREATE TABLE nudge_pool (id TEXT PRIMARY KEY, trigger_type TEXT NOT NULL, subject_refs TEXT, score REAL NOT NULL, created_at INTEGER NOT NULL, expires_at INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE nudge_hold (node_id TEXT NOT NULL, trigger_type TEXT NOT NULL, until_ts INTEGER NOT NULL, PRIMARY KEY (node_id, trigger_type))")
                db.execSQL("CREATE TABLE checkin_state (kind TEXT NOT NULL, day_key TEXT NOT NULL, status TEXT NOT NULL, msg_id TEXT, scheduled_for INTEGER, delivered_at INTEGER, PRIMARY KEY (kind, day_key))")

                db.execSQL("CREATE INDEX idx_node_status_due ON node(status, due_at)")
                db.execSQL("CREATE INDEX idx_raw_event_target ON raw_event(target_type, target_id)")
                db.execSQL("CREATE INDEX idx_raw_message_ts ON raw_message(ts_utc)")
                db.execSQL("CREATE INDEX idx_raw_message_day ON raw_message(day_key, ts_utc)")
                db.execSQL("CREATE INDEX idx_reminder_fire ON reminder(fire_at)")

                db.execSQL("CREATE VIRTUAL TABLE node_fts USING fts5(title, body, content='node', content_rowid='rowid')")
                db.execSQL("CREATE VIRTUAL TABLE soul_fts USING fts5(statement, content='soul_fact', content_rowid='rowid')")
                db.execSQL("CREATE VIRTUAL TABLE msg_chunk_fts USING fts5(text, content='msg_chunk', content_rowid='rowid')")

                MineDatabaseCallback.createTriggers(db)
            }
            override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
        }
        val v2Config = androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(migrationDbName)
            .callback(v2Callback)
            .build()
        val v2Helper = net.zetetic.database.sqlcipher.SupportOpenHelperFactory(key).create(v2Config)
        val v2Db = v2Helper.writableDatabase
        v2Db.execSQL("INSERT INTO edge (id, src_id, dst_id, type) VALUES ('e_legacy', 'n1', 'n2', 'follow_up')")
        v2Helper.close()

        // 2. Open DB with MineDatabaseCallback (version 5)
        val v3Helper = DatabaseProvider.getHelper(context, key, migrationDbName)
        assertEquals(5, v3Helper.readableDatabase.version)

        // 3. Verify indexes exist
        val foundIndexes = mutableListOf<String>()
        v3Helper.readableDatabase.query("SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'edge'").use { cursor ->
            while (cursor.moveToNext()) {
                foundIndexes.add(cursor.getString(0))
            }
        }
        assertTrue("Index idx_edge_src_type must exist after migration", foundIndexes.contains("idx_edge_src_type"))
        assertTrue("Index idx_edge_dst_type must exist after migration", foundIndexes.contains("idx_edge_dst_type"))

        v3Helper.close()
        context.deleteDatabase(migrationDbName)
    }

    @Test
    fun testSaveAndQueryEdges_FollowUp(): Unit = runBlocking {
        val edge1 = Edge(
            id = "e_1",
            srcId = "n_1",
            dstId = "n_2",
            type = EdgeTypes.FOLLOW_UP,
            createdAt = 1000L,
            updatedAt = 1000L
        )
        val edge2 = Edge(
            id = "e_2",
            srcId = "n_1",
            dstId = "n_3",
            type = EdgeTypes.FOLLOW_UP,
            createdAt = 2000L,
            updatedAt = 2000L
        )
        val edge3 = Edge(
            id = "e_3",
            srcId = "n_4",
            dstId = "n_1",
            type = EdgeTypes.FOLLOW_UP,
            createdAt = 3000L,
            updatedAt = 3000L
        )

        storage.saveEdge(edge1)
        storage.saveEdge(edge2)
        storage.saveEdge(edge3)

        val retrieved1 = storage.getEdge("e_1")
        assertNotNull(retrieved1)
        assertEquals("n_1", retrieved1!!.srcId)
        assertEquals("n_2", retrieved1.dstId)
        assertEquals(EdgeTypes.FOLLOW_UP, retrieved1.type)

        val outEdges = storage.getEdgesForSource("n_1", EdgeTypes.FOLLOW_UP)
        assertEquals(2, outEdges.size)
        assertEquals("n_2", outEdges[0].dstId)
        assertEquals("n_3", outEdges[1].dstId)

        val inEdges = storage.getEdgesForDestination("n_1", EdgeTypes.FOLLOW_UP)
        assertEquals(1, inEdges.size)
        assertEquals("n_4", inEdges[0].srcId)

        // Soft delete edge1
        storage.deleteEdge("e_1")
        assertNull(storage.getEdge("e_1"))

        val outAfterDelete = storage.getEdgesForSource("n_1", EdgeTypes.FOLLOW_UP)
        assertEquals(1, outAfterDelete.size)
        assertEquals("n_3", outAfterDelete[0].dstId)
    }

    @Test
    fun testCreateEdgeAndCommit_And_DeleteEdgeAndCommit(): Unit = runBlocking {
        val edge = Edge(
            id = "e_commit",
            srcId = "n_src",
            dstId = "n_dst",
            type = EdgeTypes.FOLLOW_UP,
            createdAt = 5000L,
            updatedAt = 5000L
        )
        val createEvent = RawEvent(
            opId = "op_link_1",
            opType = "LINK",
            targetType = "edge",
            targetId = "e_commit",
            tsUtc = 5000L
        )

        storage.createEdgeAndCommit(edge, createEvent)

        val fetched = storage.getEdge("e_commit")
        assertNotNull(fetched)
        assertEquals("n_src", fetched!!.srcId)

        val event = storage.getEvent("op_link_1")
        assertNotNull(event)
        assertEquals("LINK", event!!.opType)
        assertEquals("edge", event.targetType)

        val deleteEvent = RawEvent(
            opId = "op_unlink_1",
            opType = "UNLINK",
            targetType = "edge",
            targetId = "e_commit",
            undoOf = "op_link_1",
            tsUtc = 6000L
        )

        storage.deleteEdgeAndCommit("e_commit", deleteEvent)

        assertNull(storage.getEdge("e_commit"))
        val unlinkedEvent = storage.getEvent("op_unlink_1")
        assertNotNull(unlinkedEvent)
        assertEquals("op_link_1", unlinkedEvent!!.undoOf)
    }

    @Test
    fun testCalendarQueries_AndroidStorage(): Unit = runBlocking {
        val db = dbHelper.writableDatabase

        // 1. Insert messages across days
        db.execSQL("INSERT INTO raw_message (id, device_id, ts_utc, tz_offset_min, day_key, role, content) VALUES ('m1', 'dev1', 1000, 0, '2026-11-01', 'user', 'Hello Day 1')")
        db.execSQL("INSERT INTO raw_message (id, device_id, ts_utc, tz_offset_min, day_key, role, content) VALUES ('m2', 'dev1', 2000, 0, '2026-11-01', 'assistant', 'Hi there!')")

        db.execSQL("INSERT INTO raw_message (id, device_id, ts_utc, tz_offset_min, day_key, role, content) VALUES ('m3', 'dev1', 10000, 0, '2026-11-02', 'user', 'Morning Day 2')")
        db.execSQL("INSERT INTO raw_message (id, device_id, ts_utc, tz_offset_min, day_key, role, content) VALUES ('m4', 'dev1', 11000, 0, '2026-11-02', 'assistant', 'Good morning')")
        db.execSQL("INSERT INTO raw_message (id, device_id, ts_utc, tz_offset_min, day_key, role, content) VALUES ('m5', 'dev1', 12000, 0, '2026-11-02', 'user', 'Afternoon task')")
        db.execSQL("INSERT INTO raw_message (id, device_id, ts_utc, tz_offset_min, day_key, role, content) VALUES ('m6', 'dev1', 13000, 0, '2026-11-02', 'assistant', 'Got it')")
        db.execSQL("INSERT INTO raw_message (id, device_id, ts_utc, tz_offset_min, day_key, role, content) VALUES ('m7', 'dev1', 14000, 0, '2026-11-02', 'user', 'Evening wrap-up')")

        db.execSQL("INSERT INTO raw_message (id, device_id, ts_utc, tz_offset_min, day_key, role, content) VALUES ('m8', 'dev1', 20000, 0, '2026-11-03', 'user', 'Sole message on Day 3')")

        // 2. Insert reminders
        val r1 = Reminder("r1", "Doctor appointment", 10500L, "n1", AlarmTier.ALARM)
        val r2 = Reminder("r2", "Call electrician", 13500L, "n2", AlarmTier.GENTLE)
        val r3 = Reminder("r3", "Dentist next week", 30000L, "n3", AlarmTier.ALARM)
        storage.saveReminder(r1)
        storage.saveReminder(r2)
        storage.saveReminder(r3)

        // 3. Verify getDaysWithMessages
        val activeDays = storage.getDaysWithMessages("2026-11-01", "2026-11-03")
        assertEquals(setOf("2026-11-01", "2026-11-02", "2026-11-03"), activeDays)

        val emptyDays = storage.getDaysWithMessages("2026-11-04", "2026-11-10")
        assertTrue(emptyDays.isEmpty())

        // 4. Verify getHeatMapCounts
        val heatMap = storage.getHeatMapCounts("2026-11-01", "2026-11-03")
        assertEquals(3, heatMap.size)
        assertEquals(2, heatMap["2026-11-01"])
        assertEquals(5, heatMap["2026-11-02"])
        assertEquals(1, heatMap["2026-11-03"])

        // 5. Verify getMessagesForDay (chronological ASC)
        val day2Msgs = storage.getMessagesForDay("2026-11-02")
        assertEquals(5, day2Msgs.size)
        assertEquals("m3", day2Msgs[0].id)
        assertEquals("2026-11-02", day2Msgs[0].dayKey)
        assertEquals(10000L, day2Msgs[0].tsUtc)
        assertEquals("m7", day2Msgs[4].id)

        // 6. Verify getRemindersForDay
        val day2Reminders = storage.getRemindersForDay(10000L, 15000L)
        assertEquals(2, day2Reminders.size)
        assertEquals("r1", day2Reminders[0].id)
        assertEquals(AlarmTier.ALARM, day2Reminders[0].tier)
        assertEquals("r2", day2Reminders[1].id)
        assertEquals(AlarmTier.GENTLE, day2Reminders[1].tier)
    }

    @Test
    fun testDatabaseMigration_V3toV4_CreatesQuoteSlotTable(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val migrationDbName = "mine_migration_v3_v4.db"
        context.deleteDatabase(migrationDbName)
        val key = ByteArray(32) { 4 }

        // 1. Create DB with V3 schema (has edge indexes, but lacks quote_slot)
        val v3Callback = object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(3) {
            override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE app_meta (\"key\" TEXT PRIMARY KEY, value TEXT NOT NULL)")
                db.execSQL("CREATE TABLE raw_message (id TEXT PRIMARY KEY, device_id TEXT, ts_utc INTEGER, tz_offset_min INTEGER, day_key TEXT, role TEXT, content TEXT, redacted_at INTEGER, reasoning TEXT, tags TEXT, persona_id TEXT, model_ref TEXT, tokens_in INTEGER, tokens_out INTEGER, client_msg_id TEXT, attrs TEXT)")
                db.execSQL("CREATE TABLE raw_event (op_id TEXT PRIMARY KEY, ts_utc INTEGER, device_id TEXT, actor TEXT, op_type TEXT, target_type TEXT, target_id TEXT, before_json TEXT, after_json TEXT, cause_msg_id TEXT, undo_of TEXT, attrs TEXT)")
                db.execSQL("CREATE TABLE node (id TEXT PRIMARY KEY, device_id TEXT, type TEXT, title TEXT, body TEXT, status TEXT, priority INTEGER, severity INTEGER, start_at INTEGER, due_at INTEGER, time_precision TEXT, flexible INTEGER, lane TEXT, expires_at INTEGER, source TEXT, origin TEXT, source_msg_ids TEXT, extractor_version TEXT, attrs TEXT, local_only INTEGER, hlc TEXT, created_at INTEGER, updated_at INTEGER, closed_at INTEGER, deleted_at INTEGER)")
                db.execSQL("CREATE TABLE edge (id TEXT PRIMARY KEY, device_id TEXT, src_id TEXT, dst_id TEXT, dst_kind TEXT, type TEXT, weight REAL, source TEXT, origin TEXT, source_msg_ids TEXT, attrs TEXT, hlc TEXT, created_at INTEGER, updated_at INTEGER, deleted_at INTEGER)")
                db.execSQL("CREATE TABLE soul_fact (id TEXT PRIMARY KEY, device_id TEXT NOT NULL, category TEXT NOT NULL, statement TEXT NOT NULL, attrs TEXT, confidence REAL NOT NULL DEFAULT 0.7, source TEXT NOT NULL, origin TEXT NOT NULL, status TEXT NOT NULL, pinned_core INTEGER NOT NULL DEFAULT 0, sensitive INTEGER NOT NULL DEFAULT 0, local_only INTEGER NOT NULL DEFAULT 0, hlc TEXT NOT NULL, valid_from INTEGER, valid_to INTEGER, supersedes TEXT, source_msg_ids TEXT, extractor_version TEXT, last_confirmed_at INTEGER, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, deleted_at INTEGER)")
                db.execSQL("CREATE TABLE msg_chunk (id TEXT PRIMARY KEY, day_key TEXT NOT NULL, ts_start INTEGER NOT NULL, ts_end INTEGER NOT NULL, msg_ids TEXT NOT NULL, text TEXT NOT NULL, extractor_version TEXT NOT NULL)")
                db.execSQL("CREATE TABLE reminder (id TEXT PRIMARY KEY, node_id TEXT, text TEXT NOT NULL, fire_at INTEGER NOT NULL, tier TEXT NOT NULL DEFAULT 'alarm')")
                db.execSQL("CREATE TABLE proposal (id TEXT PRIMARY KEY, kind TEXT NOT NULL, intent TEXT NOT NULL, options TEXT NOT NULL, status TEXT NOT NULL DEFAULT 'open', created_at INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE day_rule (id TEXT PRIMARY KEY, effective_from INTEGER NOT NULL, start_minute INTEGER NOT NULL, end_minute INTEGER NOT NULL, tz_id TEXT NOT NULL, created_at INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE day_block (day_key TEXT NOT NULL, idx INTEGER NOT NULL, start_msg_id TEXT NOT NULL, start_ts INTEGER NOT NULL, end_ts INTEGER NOT NULL, msg_count INTEGER NOT NULL, part_of_day TEXT, PRIMARY KEY (day_key, idx))")
                db.execSQL("CREATE TABLE export_log (id TEXT PRIMARY KEY, ts INTEGER NOT NULL, kind TEXT NOT NULL, options_json TEXT, counts_json TEXT, bytes INTEGER, verified INTEGER, note TEXT)")
                db.execSQL("CREATE TABLE signal_log (id TEXT PRIMARY KEY, ts_utc INTEGER NOT NULL, kind TEXT NOT NULL, subject TEXT, value REAL, attrs TEXT)")
                db.execSQL("CREATE TABLE learned_pref (key TEXT NOT NULL, scope TEXT NOT NULL, value_json TEXT NOT NULL, samples INTEGER NOT NULL, confidence REAL NOT NULL, locked INTEGER NOT NULL DEFAULT 0, updated_at INTEGER NOT NULL, PRIMARY KEY (key, scope))")
                db.execSQL("CREATE TABLE reliability_state (id INTEGER PRIMARY KEY CHECK (id = 1), last_check_at INTEGER, score TEXT, issues_json TEXT, sweeper_heartbeat INTEGER, last_force_stop_detected INTEGER, p95_latency_ms INTEGER)")
                db.execSQL("CREATE TABLE diag_log (ts INTEGER NOT NULL, level TEXT NOT NULL, tag TEXT NOT NULL, msg TEXT NOT NULL)")
                db.execSQL("CREATE TABLE reminder_fire (id TEXT PRIMARY KEY, reminder_id TEXT NOT NULL, fired_at INTEGER NOT NULL, scheduled_for INTEGER NOT NULL, latency_ms INTEGER NOT NULL, outcome TEXT NOT NULL, delivery TEXT, reason_json TEXT, opener_source TEXT)")
                db.execSQL("CREATE TABLE alarm_registry (reminder_id TEXT PRIMARY KEY, request_code INTEGER NOT NULL, api TEXT NOT NULL, registered_for INTEGER NOT NULL, registered_at INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE proactive_state (id INTEGER PRIMARY KEY CHECK (id = 1), open_thread_msg_id TEXT, thread_opened_at INTEGER, backoff_step INTEGER NOT NULL DEFAULT 0, last_sent_at INTEGER, last_contact_at INTEGER, paused_until INTEGER, silent_days INTEGER NOT NULL DEFAULT 0, sent_today INTEGER NOT NULL DEFAULT 0, fuse_tripped_at INTEGER, day_key TEXT)")
                db.execSQL("CREATE TABLE nudge_log (id TEXT PRIMARY KEY, ts_utc INTEGER NOT NULL, trigger_type TEXT NOT NULL, subject_refs TEXT, score REAL, threshold REAL, gates_json TEXT, decision TEXT NOT NULL, delivery TEXT, msg_id TEXT, text_source TEXT, outcome TEXT, latency_ms INTEGER, reason_json TEXT)")
                db.execSQL("CREATE TABLE nudge_pool (id TEXT PRIMARY KEY, trigger_type TEXT NOT NULL, subject_refs TEXT, score REAL NOT NULL, created_at INTEGER NOT NULL, expires_at INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE nudge_hold (node_id TEXT NOT NULL, trigger_type TEXT NOT NULL, until_ts INTEGER NOT NULL, PRIMARY KEY (node_id, trigger_type))")
                db.execSQL("CREATE TABLE checkin_state (kind TEXT NOT NULL, day_key TEXT NOT NULL, status TEXT NOT NULL, msg_id TEXT, scheduled_for INTEGER, delivered_at INTEGER, PRIMARY KEY (kind, day_key))")

                db.execSQL("CREATE INDEX idx_node_status_due ON node(status, due_at)")
                db.execSQL("CREATE INDEX idx_raw_event_target ON raw_event(target_type, target_id)")
                db.execSQL("CREATE INDEX idx_raw_message_ts ON raw_message(ts_utc)")
                db.execSQL("CREATE INDEX idx_raw_message_day ON raw_message(day_key, ts_utc)")
                db.execSQL("CREATE INDEX idx_reminder_fire ON reminder(fire_at)")
                db.execSQL("CREATE INDEX idx_edge_src_type ON edge(src_id, type)")
                db.execSQL("CREATE INDEX idx_edge_dst_type ON edge(dst_id, type)")

                db.execSQL("CREATE VIRTUAL TABLE node_fts USING fts5(title, body, content='node', content_rowid='rowid')")
                db.execSQL("CREATE VIRTUAL TABLE soul_fts USING fts5(statement, content='soul_fact', content_rowid='rowid')")
                db.execSQL("CREATE VIRTUAL TABLE msg_chunk_fts USING fts5(text, content='msg_chunk', content_rowid='rowid')")

                MineDatabaseCallback.createTriggers(db)
            }
            override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
        }

        val v3Config = androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(migrationDbName)
            .callback(v3Callback)
            .build()
        val v3Helper = net.zetetic.database.sqlcipher.SupportOpenHelperFactory(key).create(v3Config)
        val v3Db = v3Helper.writableDatabase
        v3Db.execSQL("INSERT INTO reminder (id, text, fire_at) VALUES ('r_v3', 'Task in V3', 50000)")
        v3Helper.close()

        // 2. Open DB with MineDatabaseCallback (version 5)
        val v4Helper = DatabaseProvider.getHelper(context, key, migrationDbName)
        assertEquals(5, v4Helper.readableDatabase.version)

        // 3. Verify quote_slot table exists
        var quoteSlotExists = false
        v4Helper.readableDatabase.query("SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'quote_slot'").use { cursor ->
            if (cursor.moveToFirst()) {
                quoteSlotExists = true
            }
        }
        assertTrue("Table quote_slot must exist after migration", quoteSlotExists)

        // 4. Test storing quote in migrated DB
        val v4Storage = AndroidStorage(v4Helper)
        val quote = Quote(QuoteSlot.EVENING, "2026-11-02", "Evening rest and reflection.", 60000L, null, QuoteSource.PRESET, 0)
        v4Storage.saveQuoteSlot(quote)
        val fetched = v4Storage.getQuoteSlot(QuoteSlot.EVENING, "2026-11-02")
        assertNotNull(fetched)
        assertEquals("Evening rest and reflection.", fetched!!.text)
        assertEquals(QuoteSlot.EVENING, fetched.slot)
        assertEquals(QuoteSource.PRESET, fetched.source)

        v4Helper.close()
        context.deleteDatabase(migrationDbName)
    }

    @Test
    fun testDatabaseMigration_V4toV5_AddsProposalPayloadColumn(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val migrationDbName = "mine_migration_v4_v5.db"
        context.deleteDatabase(migrationDbName)
        val key = ByteArray(32) { 5 }

        // 1. Create DB with V4 schema (proposal lacks payload column)
        val v4Callback = object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(4) {
            override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE app_meta (\"key\" TEXT PRIMARY KEY, value TEXT NOT NULL)")
                db.execSQL("CREATE TABLE raw_message (id TEXT PRIMARY KEY, device_id TEXT, ts_utc INTEGER, tz_offset_min INTEGER, day_key TEXT, role TEXT, content TEXT, redacted_at INTEGER, reasoning TEXT, tags TEXT, persona_id TEXT, model_ref TEXT, tokens_in INTEGER, tokens_out INTEGER, client_msg_id TEXT, attrs TEXT)")
                db.execSQL("CREATE TABLE raw_event (op_id TEXT PRIMARY KEY, ts_utc INTEGER, device_id TEXT, actor TEXT, op_type TEXT, target_type TEXT, target_id TEXT, before_json TEXT, after_json TEXT, cause_msg_id TEXT, undo_of TEXT, attrs TEXT)")
                db.execSQL("CREATE TABLE node (id TEXT PRIMARY KEY, device_id TEXT, type TEXT, title TEXT, body TEXT, status TEXT, priority INTEGER, severity INTEGER, start_at INTEGER, due_at INTEGER, time_precision TEXT, flexible INTEGER, lane TEXT, expires_at INTEGER, source TEXT, origin TEXT, source_msg_ids TEXT, extractor_version TEXT, attrs TEXT, local_only INTEGER, hlc TEXT, created_at INTEGER, updated_at INTEGER, closed_at INTEGER, deleted_at INTEGER)")
                db.execSQL("CREATE TABLE edge (id TEXT PRIMARY KEY, device_id TEXT, src_id TEXT, dst_id TEXT, dst_kind TEXT, type TEXT, weight REAL, source TEXT, origin TEXT, source_msg_ids TEXT, attrs TEXT, hlc TEXT, created_at INTEGER, updated_at INTEGER, deleted_at INTEGER)")
                db.execSQL("CREATE TABLE soul_fact (id TEXT PRIMARY KEY, device_id TEXT NOT NULL, category TEXT NOT NULL, statement TEXT NOT NULL, attrs TEXT, confidence REAL NOT NULL DEFAULT 0.7, source TEXT NOT NULL, origin TEXT NOT NULL, status TEXT NOT NULL, pinned_core INTEGER NOT NULL DEFAULT 0, sensitive INTEGER NOT NULL DEFAULT 0, local_only INTEGER NOT NULL DEFAULT 0, hlc TEXT NOT NULL, valid_from INTEGER, valid_to INTEGER, supersedes TEXT, source_msg_ids TEXT, extractor_version TEXT, last_confirmed_at INTEGER, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, deleted_at INTEGER)")
                db.execSQL("CREATE TABLE msg_chunk (id TEXT PRIMARY KEY, day_key TEXT NOT NULL, ts_start INTEGER NOT NULL, ts_end INTEGER NOT NULL, msg_ids TEXT NOT NULL, text TEXT NOT NULL, extractor_version TEXT NOT NULL)")
                db.execSQL("CREATE TABLE reminder (id TEXT PRIMARY KEY, node_id TEXT, text TEXT NOT NULL, fire_at INTEGER NOT NULL, tier TEXT NOT NULL DEFAULT 'alarm')")
                db.execSQL("CREATE TABLE proposal (id TEXT PRIMARY KEY, kind TEXT NOT NULL, intent TEXT NOT NULL, options TEXT NOT NULL, status TEXT NOT NULL DEFAULT 'open', created_at INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE day_rule (id TEXT PRIMARY KEY, effective_from INTEGER NOT NULL, start_minute INTEGER NOT NULL, end_minute INTEGER NOT NULL, tz_id TEXT NOT NULL, created_at INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE day_block (day_key TEXT NOT NULL, idx INTEGER NOT NULL, start_msg_id TEXT NOT NULL, start_ts INTEGER NOT NULL, end_ts INTEGER NOT NULL, msg_count INTEGER NOT NULL, part_of_day TEXT, PRIMARY KEY (day_key, idx))")
                db.execSQL("CREATE TABLE export_log (id TEXT PRIMARY KEY, ts INTEGER NOT NULL, kind TEXT NOT NULL, options_json TEXT, counts_json TEXT, bytes INTEGER, verified INTEGER, note TEXT)")
                db.execSQL("CREATE TABLE signal_log (id TEXT PRIMARY KEY, ts_utc INTEGER NOT NULL, kind TEXT NOT NULL, subject TEXT, value REAL, attrs TEXT)")
                db.execSQL("CREATE TABLE learned_pref (key TEXT NOT NULL, scope TEXT NOT NULL, value_json TEXT NOT NULL, samples INTEGER NOT NULL, confidence REAL NOT NULL, locked INTEGER NOT NULL DEFAULT 0, updated_at INTEGER NOT NULL, PRIMARY KEY (key, scope))")
                db.execSQL("CREATE TABLE reliability_state (id INTEGER PRIMARY KEY CHECK (id = 1), last_check_at INTEGER, score TEXT, issues_json TEXT, sweeper_heartbeat INTEGER, last_force_stop_detected INTEGER, p95_latency_ms INTEGER)")
                db.execSQL("CREATE TABLE diag_log (ts INTEGER NOT NULL, level TEXT NOT NULL, tag TEXT NOT NULL, msg TEXT NOT NULL)")
                db.execSQL("CREATE TABLE reminder_fire (id TEXT PRIMARY KEY, reminder_id TEXT NOT NULL, fired_at INTEGER NOT NULL, scheduled_for INTEGER NOT NULL, latency_ms INTEGER NOT NULL, outcome TEXT NOT NULL, delivery TEXT, reason_json TEXT, opener_source TEXT)")
                db.execSQL("CREATE TABLE alarm_registry (reminder_id TEXT PRIMARY KEY, request_code INTEGER NOT NULL, api TEXT NOT NULL, registered_for INTEGER NOT NULL, registered_at INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE proactive_state (id INTEGER PRIMARY KEY CHECK (id = 1), open_thread_msg_id TEXT, thread_opened_at INTEGER, backoff_step INTEGER NOT NULL DEFAULT 0, last_sent_at INTEGER, last_contact_at INTEGER, paused_until INTEGER, silent_days INTEGER NOT NULL DEFAULT 0, sent_today INTEGER NOT NULL DEFAULT 0, fuse_tripped_at INTEGER, day_key TEXT)")
                db.execSQL("CREATE TABLE nudge_log (id TEXT PRIMARY KEY, ts_utc INTEGER NOT NULL, trigger_type TEXT NOT NULL, subject_refs TEXT, score REAL, threshold REAL, gates_json TEXT, decision TEXT NOT NULL, delivery TEXT, msg_id TEXT, text_source TEXT, outcome TEXT, latency_ms INTEGER, reason_json TEXT)")
                db.execSQL("CREATE TABLE nudge_pool (id TEXT PRIMARY KEY, trigger_type TEXT NOT NULL, subject_refs TEXT, score REAL NOT NULL, created_at INTEGER NOT NULL, expires_at INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE nudge_hold (node_id TEXT NOT NULL, trigger_type TEXT NOT NULL, until_ts INTEGER NOT NULL, PRIMARY KEY (node_id, trigger_type))")
                db.execSQL("CREATE TABLE checkin_state (kind TEXT NOT NULL, day_key TEXT NOT NULL, status TEXT NOT NULL, msg_id TEXT, scheduled_for INTEGER, delivered_at INTEGER, PRIMARY KEY (kind, day_key))")
                db.execSQL("CREATE TABLE quote_slot (slot TEXT NOT NULL, day_key TEXT NOT NULL, text TEXT NOT NULL, generated_at INTEGER NOT NULL, state_hash TEXT, source TEXT NOT NULL, regen_count INTEGER NOT NULL DEFAULT 0, PRIMARY KEY (slot, day_key))")

                db.execSQL("CREATE INDEX idx_node_status_due ON node(status, due_at)")
                db.execSQL("CREATE INDEX idx_raw_event_target ON raw_event(target_type, target_id)")
                db.execSQL("CREATE INDEX idx_raw_message_ts ON raw_message(ts_utc)")
                db.execSQL("CREATE INDEX idx_raw_message_day ON raw_message(day_key, ts_utc)")
                db.execSQL("CREATE INDEX idx_reminder_fire ON reminder(fire_at)")
                db.execSQL("CREATE INDEX idx_edge_src_type ON edge(src_id, type)")
                db.execSQL("CREATE INDEX idx_edge_dst_type ON edge(dst_id, type)")

                db.execSQL("CREATE VIRTUAL TABLE node_fts USING fts5(title, body, content='node', content_rowid='rowid')")
                db.execSQL("CREATE VIRTUAL TABLE soul_fts USING fts5(statement, content='soul_fact', content_rowid='rowid')")
                db.execSQL("CREATE VIRTUAL TABLE msg_chunk_fts USING fts5(text, content='msg_chunk', content_rowid='rowid')")

                MineDatabaseCallback.createTriggers(db)
            }
            override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
        }

        val v4Config = androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(migrationDbName)
            .callback(v4Callback)
            .build()
        val v4Helper = net.zetetic.database.sqlcipher.SupportOpenHelperFactory(key).create(v4Config)
        val v4Db = v4Helper.writableDatabase
        v4Db.execSQL(
            "INSERT INTO proposal (id, kind, intent, options, status, created_at) VALUES ('p_legacy', 'ambiguous', 'RESCHEDULE', '[]', 'open', ?)",
            arrayOf<Any>(System.currentTimeMillis())
        )
        v4Helper.close()

        // 2. Open DB with MineDatabaseCallback (version 5)
        val v5Helper = DatabaseProvider.getHelper(context, key, migrationDbName)
        assertEquals(5, v5Helper.readableDatabase.version)

        // 3. Verify payload column exists in proposal table
        var payloadColumnExists = false
        v5Helper.readableDatabase.query("PRAGMA table_info(proposal)").use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            while (cursor.moveToNext()) {
                if (nameIndex != -1 && cursor.getString(nameIndex).equals("payload", ignoreCase = true)) {
                    payloadColumnExists = true
                    break
                }
            }
        }
        assertTrue("Table proposal must contain 'payload' column after v5 migration", payloadColumnExists)

        // 4. Test reading legacy proposal (payload is null)
        val v5Storage = AndroidStorage(v5Helper)
        val legacy = v5Storage.getProposal("p_legacy")
        assertNotNull(legacy)
        assertEquals("p_legacy", legacy!!.id)
        assertEquals(IntentType.RESCHEDULE, legacy.intent)
        assertNull("Legacy proposal must have null payload", legacy.payload)

        // 5. Test saving and reading a proposal with payload in migrated DB
        val newProposal = Proposal(
            id = "p_new",
            kind = "ambiguous",
            intent = IntentType.RESCHEDULE,
            options = listOf(ProposalOption("n1", "Dinner Option")),
            status = "open",
            payload = "{\"targetAtMillis\":1700000000000}"
        )
        v5Storage.saveProposal(newProposal)
        val fetched = v5Storage.getProposal("p_new")
        assertNotNull(fetched)
        assertEquals("{\"targetAtMillis\":1700000000000}", fetched!!.payload)
        assertEquals(1, fetched.options.size)

        // 6. Test getOpenProposals retrieves both with correct payload values
        val openProposals = v5Storage.getOpenProposals()
        assertEquals(2, openProposals.size)
        val legacyInList = openProposals.find { it.id == "p_legacy" }
        val newInList = openProposals.find { it.id == "p_new" }
        assertNotNull(legacyInList)
        assertNull(legacyInList!!.payload)
        assertNotNull(newInList)
        assertEquals("{\"targetAtMillis\":1700000000000}", newInList!!.payload)

        v5Helper.close()
        context.deleteDatabase(migrationDbName)
    }

    @Test
    fun testSaveAndQueryQuoteSlot(): Unit = runBlocking {
        // 1. Initial query non-existent slot returns null
        val empty = storage.getQuoteSlot(QuoteSlot.AFTERNOON, "2026-11-02")
        assertNull(empty)

        // 2. Save preset quote
        val preset = Quote(
            slot = QuoteSlot.MORNING,
            dayKey = "2026-11-02",
            text = "Begin today with calm intention.",
            generatedAt = 1000L,
            source = QuoteSource.PRESET,
            regenCount = 0
        )
        storage.saveQuoteSlot(preset)

        val fetchedPreset = storage.getQuoteSlot(QuoteSlot.MORNING, "2026-11-02")
        assertNotNull(fetchedPreset)
        assertEquals("Begin today with calm intention.", fetchedPreset!!.text)
        assertEquals(QuoteSource.PRESET, fetchedPreset.source)
        assertEquals(0, fetchedPreset.regenCount)

        // 3. Update to live quote with 1 regeneration
        val live = Quote(
            slot = QuoteSlot.MORNING,
            dayKey = "2026-11-02",
            text = "Live LLM quote replacing preset.",
            generatedAt = 2000L,
            source = QuoteSource.LIVE,
            regenCount = 1
        )
        storage.saveQuoteSlot(live)

        val fetchedLive = storage.getQuoteSlot(QuoteSlot.MORNING, "2026-11-02")
        assertNotNull(fetchedLive)
        assertEquals("Live LLM quote replacing preset.", fetchedLive!!.text)
        assertEquals(QuoteSource.LIVE, fetchedLive.source)
        assertEquals(1, fetchedLive.regenCount)
    }

    @Test
    fun testGetUrgentNodes_OrdersNullsLast(): Unit = runBlocking {
        val now = System.currentTimeMillis()
        val node1 = Node("n1", "task", "Task with no due date", "active", null, now, now)
        val node2 = Node("n2", "task", "Urgent task later", "active", now + 100000L, now, now)
        val node3 = Node("n3", "task", "Urgent task soonest", "active", now + 50000L, now, now)
        val node4 = Node("n4", "task", "Done task", "done", now + 10000L, now, now)

        storage.createNodeAndCommit(node1, RawEvent("e1", "CREATE", "node", "n1"), null)
        storage.createNodeAndCommit(node2, RawEvent("e2", "CREATE", "node", "n2"), null)
        storage.createNodeAndCommit(node3, RawEvent("e3", "CREATE", "node", "n3"), null)
        storage.createNodeAndCommit(node4, RawEvent("e4", "CREATE", "node", "n4"), null)

        val urgent = storage.getUrgentNodes()
        assertEquals(3, urgent.size)
        // Order: soonest due_at first, then later due_at, then null due_at last.
        // Done task must be excluded.
        assertEquals("n3", urgent[0].id)
        assertEquals("n2", urgent[1].id)
        assertEquals("n1", urgent[2].id)
    }

    @Test
    fun testReliabilityState_IssuesJsonRoundTrip(): Unit = runBlocking {
        val initial = storage.getReliabilityState()
        assertEquals(ReliabilityState(), initial)

        val state = ReliabilityState(
            lastCheckAt = 1700000000000L,
            score = "at_risk",
            issuesJson = """{"error":"SecurityException","time":1700000000000}""",
            sweeperHeartbeat = 1699999990000L,
            p95LatencyMs = 45
        )
        storage.saveReliabilityState(state)

        val fetched = storage.getReliabilityState()
        assertEquals(1700000000000L, fetched.lastCheckAt)
        assertEquals("at_risk", fetched.score)
        assertEquals("""{"error":"SecurityException","time":1700000000000}""", fetched.issuesJson)
        assertEquals(1699999990000L, fetched.sweeperHeartbeat)
        assertEquals(45, fetched.p95LatencyMs)
    }

    @Test
    fun testAndroidStorage_getNode_HydratesPrioritySeverityPinned_AndEnablesUndo(): Unit = runBlocking {
        val now = System.currentTimeMillis()
        val node = Node(
            id = "n_special",
            type = "task",
            title = "Special task with attributes",
            status = "active",
            dueAt = null,
            createdAt = now,
            updatedAt = now,
            priority = 2,
            severity = 3,
            pinned = true
        )
        val snapshot = NodeStateSnapshot(node, null)
        val snapshotJson = kotlinx.serialization.json.Json.encodeToString(NodeStateSnapshot.serializer(), snapshot)
        val event = RawEvent(
            opId = "op_special_create",
            opType = "CREATE",
            targetType = "node",
            targetId = "n_special",
            beforeJson = null,
            afterJson = snapshotJson,
            tsUtc = now
        )

        storage.createNodeAndCommit(node, event, reminder = null)

        val fetched = storage.getNode("n_special")
        assertNotNull(fetched)
        assertEquals(2, fetched!!.priority)
        assertEquals(3, fetched.severity)
        assertEquals(true, fetched.pinned)

        // Snapshot comparison for undo
        val currentSnapshot = NodeStateSnapshot(fetched, null)
        val decodedSnapshot = kotlinx.serialization.json.Json.decodeFromString(NodeStateSnapshot.serializer(), event.afterJson!!)
        assertEquals("Snapshot must match exactly for undo to succeed", decodedSnapshot, currentSnapshot)

        // Perform undo commit
        val undoEvent = RawEvent(
            opId = "op_special_undo",
            opType = "UNDO",
            targetType = "node",
            targetId = "n_special",
            beforeJson = snapshotJson,
            afterJson = null,
            undoOf = "op_special_create",
            tsUtc = now + 1000L
        )
        storage.undoChangeAndCommit(node = fetched.copy(status = "cancelled"), event = undoEvent, restoreReminder = null)
        val undone = storage.getNode("n_special")
        assertNotNull(undone)
        assertEquals("cancelled", undone!!.status)
    }

    @Test
    fun testAndroidStorage_getRecentEvents_PreservesCauseMsgIdAndFiltersUndoOf(): Unit = runBlocking {
        val now = 1700000000000L

        // 1. Create event with causeMsgId
        val ev1 = RawEvent(
            opId = "op_r7_1",
            opType = "CREATE",
            targetType = "node",
            targetId = "n_r7_1",
            tsUtc = now + 1000L,
            causeMsgId = "u_msg_101"
        )
        val node1 = Node("n_r7_1", "task", "Task R7 1", "active", null, now + 1000L, now + 1000L)
        storage.createNodeAndCommit(node1, ev1, null)

        // 2. Second event without causeMsgId (e.g. chip action)
        val ev2 = RawEvent(
            opId = "op_r7_2",
            opType = "KEEP_STALE",
            targetType = "node",
            targetId = "n_r7_1",
            tsUtc = now + 2000L,
            causeMsgId = null
        )
        val db = dbHelper.writableDatabase
        db.execSQL(
            "INSERT INTO raw_event (op_id, ts_utc, device_id, actor, op_type, target_type, target_id, before_json, after_json, cause_msg_id, undo_of, attrs) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            arrayOf<Any?>(ev2.opId, ev2.tsUtc, "device1", "user", ev2.opType, ev2.targetType, ev2.targetId, null, null, null, null, null)
        )

        // 3. Event with undoOf (undo operation itself - should be filtered out by undo_of IS NULL)
        val evUndo = RawEvent(
            opId = "op_r7_undo",
            opType = "UNDO",
            targetType = "node",
            targetId = "n_r7_1",
            tsUtc = now + 3000L,
            undoOf = "op_r7_1"
        )
        db.execSQL(
            "INSERT INTO raw_event (op_id, ts_utc, device_id, actor, op_type, target_type, target_id, before_json, after_json, cause_msg_id, undo_of, attrs) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            arrayOf<Any?>(evUndo.opId, evUndo.tsUtc, "device1", "user", evUndo.opType, evUndo.targetType, evUndo.targetId, null, null, null, evUndo.undoOf, null)
        )

        // Verify getEvent preserves causeMsgId
        val fetchedEv1 = storage.getEvent("op_r7_1")
        assertNotNull(fetchedEv1)
        assertEquals("u_msg_101", fetchedEv1!!.causeMsgId)

        // Verify getRecentEvents returns UI-visible events in chronological ASC order and filters out undo_of
        val events = storage.getRecentEvents(10)
        assertTrue("Must return at least 2 events", events.size >= 2)
        val r7_1 = events.find { it.opId == "op_r7_1" }
        val r7_2 = events.find { it.opId == "op_r7_2" }
        val r7_undo = events.find { it.opId == "op_r7_undo" }

        assertNotNull("ev1 must be in recent events", r7_1)
        assertEquals("u_msg_101", r7_1!!.causeMsgId)
        assertNotNull("ev2 must be in recent events", r7_2)
        assertNull("ev2 causeMsgId must be null", r7_2!!.causeMsgId)
        assertNull("Undo operation must NOT be in recent events because undo_of is not null", r7_undo)

        assertTrue("Chronological order: ev1 before ev2", events.indexOf(r7_1) < events.indexOf(r7_2))
    }

    @Test
    fun testAndroidStorage_localOnlyAndSensitiveQuarantine(): Unit = runBlocking {
        val now = 1700000000000L
        val staleCutoff = 30L * 24 * 3600 * 1000L

        // 1. Create a localOnly node
        val localNode = Node(
            id = "n_local_med",
            type = "task",
            title = "Secret Medical Appointment",
            status = "active",
            dueAt = now + 1000L,
            createdAt = now,
            updatedAt = now,
            localOnly = true
        )
        val ev1 = RawEvent(
            opId = "op_local_1",
            opType = "CREATE",
            targetType = "node",
            targetId = "n_local_med",
            tsUtc = now
        )
        storage.createNodeAndCommit(localNode, ev1, null)

        // Verify getNode hydrates localOnly
        val fetchedNode = storage.getNode("n_local_med")
        assertNotNull(fetchedNode)
        assertTrue("Node must have localOnly == true", fetchedNode!!.localOnly)

        // Verify provider/query APIs filter out localOnly node
        val ranked = storage.getRankedCandidates(now, staleCutoff, 10)
        assertFalse("Ranked candidates must not include localOnly nodes", ranked.any { it.id == "n_local_med" })

        val dueBetween = storage.getNodesDueBetween(now, now + 5000L, 10)
        assertFalse("getNodesDueBetween must not include localOnly nodes", dueBetween.any { it.id == "n_local_med" })

        val overdue = storage.getOverdueNodes(now + 2000L, 10)
        assertFalse("getOverdueNodes must not include localOnly nodes", overdue.any { it.id == "n_local_med" })

        val counts = storage.getActiveNodeCounts(now, staleCutoff)
        assertEquals("Active counts must not count localOnly node", 0, counts.totalOpen)

        val urgent = storage.getUrgentNodes()
        assertFalse("getUrgentNodes must not include localOnly nodes", urgent.any { it.id == "n_local_med" })

        val searchNode = storage.searchMemoryFts("Medical", 10)
        assertFalse("searchMemoryFts must not include localOnly nodes", searchNode.any { it.id == "n_local_med" })

        // 2. Create a sensitive localOnly soul fact
        val sensitiveFact = SoulFact(
            id = "sf_med_1",
            category = "health",
            statement = "User diagnosed with hypertension",
            confidence = 0.95,
            source = "chat",
            origin = "chat",
            status = "proposed",
            createdAt = now,
            sensitive = true,
            localOnly = true
        )
        val ev2 = RawEvent(
            opId = "op_fact_1",
            opType = "CREATE",
            targetType = "soul_fact",
            targetId = "sf_med_1",
            tsUtc = now
        )
        storage.createSoulFactAndCommit(sensitiveFact, ev2)

        // getProposedSoulFacts should show it for user inspection/review
        val proposed = storage.getProposedSoulFacts()
        val foundProposed = proposed.find { it.id == "sf_med_1" }
        assertNotNull("Proposed soul facts must include quarantined sensitive fact", foundProposed)
        assertTrue(foundProposed!!.sensitive)
        assertTrue(foundProposed.localOnly)

        // Active soul facts must NOT include it
        val activeFacts = storage.getActiveSoulFacts()
        assertFalse("getActiveSoulFacts must not include sensitive/localOnly fact", activeFacts.any { it.id == "sf_med_1" })

        // Even if status were updated to 'active', local_only = 1 must keep it out of active provider queries
        storage.updateSoulFactStatus("sf_med_1", "active")
        val activeAfterUpdate = storage.getActiveSoulFacts()
        assertFalse("getActiveSoulFacts must continue to filter localOnly soul facts even if active", activeAfterUpdate.any { it.id == "sf_med_1" })

        val searchFact = storage.searchMemoryFts("hypertension", 10)
        assertFalse("searchMemoryFts must not return localOnly soul facts", searchFact.any { it.id == "sf_med_1" })
    }

    @Test
    fun testAndroidStorage_DayRuleEffectiveFrom_AndNextRollover(): Unit = runBlocking {
        // 1. Uninitialized returns default rule
        val defaultRule = storage.getEffectiveDayRule(1000L)
        assertEquals("default", defaultRule.id)
        assertEquals(240, defaultRule.startMinute)

        // 2. Save rule effective from 2000L
        val rule1 = DayRule(
            id = "dr_1",
            effectiveFrom = 2000L,
            startMinute = 360, // 06:00
            endMinute = 1320,
            tzId = "UTC",
            createdAt = 1000L
        )
        storage.saveDayRule(rule1)

        assertEquals("Before effective_from returns default", "default", storage.getEffectiveDayRule(1500L).id)
        assertEquals("At effective_from returns dr_1", "dr_1", storage.getEffectiveDayRule(2000L).id)
        assertEquals("After effective_from returns dr_1", "dr_1", storage.getEffectiveDayRule(2500L).id)
        assertEquals(360, storage.getEffectiveDayRule(2500L).startMinute)

        // 3. Save rule 2 effective from 4000L
        val rule2 = DayRule(
            id = "dr_2",
            effectiveFrom = 4000L,
            startMinute = 480, // 08:00
            endMinute = 1320,
            tzId = "UTC",
            createdAt = 3000L
        )
        storage.saveDayRule(rule2)

        assertEquals("Between rules returns dr_1", "dr_1", storage.getEffectiveDayRule(3500L).id)
        assertEquals("At rule 2 effective_from returns dr_2", "dr_2", storage.getEffectiveDayRule(4000L).id)
        assertEquals("After rule 2 returns dr_2", "dr_2", storage.getEffectiveDayRule(5000L).id)
        assertEquals(480, storage.getEffectiveDayRule(5000L).startMinute)
    }

    @Test
    fun testAndroidStorage_MsgChunkFts_SearchAndRedact(): Unit = runBlocking {
        // 1. Insert user and assistant messages
        storage.insertRawMessage("msg_orch_1", "user", "Remember to water the rare orchids tomorrow")
        storage.insertRawMessage("msg_orch_2", "assistant", "I have noted down to water the rare orchids.")

        // 2. Search for keyword in conversation history
        val searchResults = storage.searchMemoryFts("orchids", 10)
        assertTrue("searchMemoryFts must find conversation history containing orchids", searchResults.any { it.details.contains("orchids") })
        val match = searchResults.first { it.details.contains("orchids") }
        assertEquals("chat", match.kind)
        assertTrue("ID should indicate chunk", match.id.contains("msg_orch_1") || match.id.contains("msg_orch_2"))

        // 3. System message should not be indexed into msg_chunk
        storage.insertRawMessage("msg_orch_sys", "system", "System instructions for orchids")
        val sysSearch = storage.searchMemoryFts("instructions", 10)
        assertFalse("System messages must not be indexed in msg_chunk_fts", sysSearch.any { it.id.contains("msg_orch_sys") })

        // 4. Redaction must scrub msg_chunk and msg_chunk_fts via DELETE trigger
        storage.redactMessage("msg_orch_1")
        storage.redactMessage("msg_orch_2")

        val scrubbedResults = storage.searchMemoryFts("orchids", 10)
        assertFalse("Redacted messages must be completely scrubbed from searchMemoryFts", scrubbedResults.any { it.details.contains("orchids") })
    }

    @Test
    fun testConsumeProposalCas_RealSqlite(): Unit = runBlocking {
        val p = Proposal("p_cas_1", "time", IntentType.CREATE, listOf(ProposalOption("1", "Opt 1")), status = "open")
        storage.saveProposal(p)

        // First CAS must succeed
        val firstCas = storage.consumeProposalCas("p_cas_1")
        assertTrue("First CAS on open proposal must return true", firstCas)

        val updated = storage.getProposal("p_cas_1")
        assertNotNull(updated)
        assertEquals("consumed", updated!!.status)

        // Second CAS must fail (already consumed)
        val secondCas = storage.consumeProposalCas("p_cas_1")
        assertFalse("Second CAS on consumed proposal must return false", secondCas)

        // Non-existent proposal CAS must fail
        val missingCas = storage.consumeProposalCas("p_missing")
        assertFalse("CAS on non-existent proposal must return false", missingCas)
    }

    @Test
    fun testUpdateProposalStatus_PreservesCreatedAt_RealSqlite(): Unit = runBlocking {
        val p = Proposal("p_status_test", "time", IntentType.CREATE, listOf(ProposalOption("1", "Opt 1")), status = "open")
        storage.saveProposal(p)

        // Artificially age the proposal to 23 hours ago in SQLite
        val agedCreatedAt = System.currentTimeMillis() - (23 * 3600 * 1000L)
        dbHelper.writableDatabase.execSQL("UPDATE proposal SET created_at = ? WHERE id = ?", arrayOf<Any>(agedCreatedAt, "p_status_test"))

        // Verify getProposal finds it as open
        val initial = storage.getProposal("p_status_test")
        assertNotNull(initial)
        assertEquals("open", initial!!.status)

        // 1. Status flip via updateProposalStatus
        storage.updateProposalStatus("p_status_test", "consumed")
        val consumed = storage.getProposal("p_status_test")
        assertNotNull(consumed)
        assertEquals("consumed", consumed!!.status)

        storage.updateProposalStatus("p_status_test", "open")
        val reopened = storage.getProposal("p_status_test")
        assertNotNull(reopened)
        assertEquals("open", reopened!!.status)

        // Query raw created_at from SQLite table: must be identical to agedCreatedAt (NOT updated to currentTimeMillis)
        var actualCreatedAt = 0L
        dbHelper.readableDatabase.query("SELECT created_at FROM proposal WHERE id = ?", arrayOf<Any>("p_status_test")).use { cursor ->
            if (cursor.moveToFirst()) {
                actualCreatedAt = cursor.getLong(0)
            }
        }
        assertEquals("updateProposalStatus must preserve immutable created_at timestamp in SQLite", agedCreatedAt, actualCreatedAt)

        // 2. Contrast with saveProposal which does INSERT OR REPLACE and overwrites created_at
        storage.saveProposal(p.copy(status = "open"))
        var overwrittenCreatedAt = 0L
        dbHelper.readableDatabase.query("SELECT created_at FROM proposal WHERE id = ?", arrayOf<Any>("p_status_test")).use { cursor ->
            if (cursor.moveToFirst()) {
                overwrittenCreatedAt = cursor.getLong(0)
            }
        }
        assertTrue("saveProposal overwrites created_at with current time", overwrittenCreatedAt > agedCreatedAt + (20 * 3600 * 1000L))
    }

    @Test
    fun testInTransactionCas_RealSqlite_AbortOnAlreadyConsumed(): Unit = runBlocking {
        val p = Proposal("p_cas_abort", "time", IntentType.CREATE, listOf(ProposalOption("1", "Opt 1")), status = "consumed")
        storage.saveProposal(p)

        val node = Node("n_abort", "task", "Abort Task", "active")
        val event = RawEvent("e_abort", "CREATE", "node", "n_abort")

        var exceptionThrown = false
        try {
            storage.createNodeAndCommit(node, event, reminder = null, consumeProposalId = "p_cas_abort")
        } catch (e: ProposalAlreadyResolvedException) {
            exceptionThrown = true
        }

        assertTrue("In-transaction CAS must abort with ProposalAlreadyResolvedException when proposal is not open", exceptionThrown)
        assertNull("Transaction rollback must ensure node was not inserted", storage.getNode("n_abort"))
        assertNull("Transaction rollback must ensure raw_event was not inserted", storage.getEvent("e_abort"))
    }

    @Test
    fun testReopenProposal_RealSqlite_GuardedSemantics(): Unit = runBlocking {
        val p = Proposal("p_reopen_test", "time", IntentType.CREATE, listOf(ProposalOption("1", "Opt 1")), status = "open")
        storage.saveProposal(p)

        // 1. Reopening an already open proposal must return false (no-op)
        val reopenOpen = storage.reopenProposal("p_reopen_test")
        assertFalse("reopenProposal on open proposal must return false", reopenOpen)
        assertEquals("open", storage.getProposal("p_reopen_test")?.status)

        // 2. Consume proposal
        storage.consumeProposal("p_reopen_test")
        assertEquals("consumed", storage.getProposal("p_reopen_test")?.status)

        // 3. Reopen consumed proposal must succeed and return true
        val reopenConsumed = storage.reopenProposal("p_reopen_test")
        assertTrue("reopenProposal on consumed proposal must return true", reopenConsumed)
        assertEquals("open", storage.getProposal("p_reopen_test")?.status)

        // 4. Reopening non-existent proposal must return false
        val reopenMissing = storage.reopenProposal("p_nonexistent")
        assertFalse("reopenProposal on non-existent proposal must return false", reopenMissing)
    }

    @Test
    fun testConcurrentConfirm_RealSqlite_SingleWinnerSemantics(): Unit = runBlocking {
        val p = Proposal("p_race_test", "time", IntentType.CREATE, listOf(ProposalOption("1", "Opt 1")), status = "open")
        storage.saveProposal(p)

        val node1 = Node("n_race_1", "task", "Race Task 1", "active")
        val event1 = RawEvent("e_race_1", "CREATE", "node", "n_race_1")

        val node2 = Node("n_race_2", "task", "Race Task 2", "active")
        val event2 = RawEvent("e_race_2", "CREATE", "node", "n_race_2")

        val results = listOf(
            async(Dispatchers.IO) {
                try {
                    storage.createNodeAndCommit(node1, event1, reminder = null, consumeProposalId = "p_race_test")
                    "SUCCESS_1"
                } catch (e: ProposalAlreadyResolvedException) {
                    "ALREADY_RESOLVED_1"
                }
            },
            async(Dispatchers.IO) {
                try {
                    storage.createNodeAndCommit(node2, event2, reminder = null, consumeProposalId = "p_race_test")
                    "SUCCESS_2"
                } catch (e: ProposalAlreadyResolvedException) {
                    "ALREADY_RESOLVED_2"
                }
            }
        ).awaitAll()

        val successes = results.filter { it.startsWith("SUCCESS") }
        val resolved = results.filter { it.startsWith("ALREADY_RESOLVED") }

        assertEquals("Exactly one concurrent confirm must succeed", 1, successes.size)
        assertEquals("Exactly one concurrent confirm must receive ProposalAlreadyResolvedException", 1, resolved.size)

        val hasNode1 = storage.getNode("n_race_1") != null
        val hasNode2 = storage.getNode("n_race_2") != null
        assertTrue("Exactly one node row must exist in SQLite", hasNode1 xor hasNode2)
        assertEquals("Proposal must end in consumed state", "consumed", storage.getProposal("p_race_test")?.status)
    }

    @Test
    fun testExpiryParity_MissingCreatedAtReturnsNull_RealSqlite(): Unit = runBlocking {
        // Direct SQLite insert with NULL or 0 created_at
        dbHelper.writableDatabase.execSQL(
            "INSERT INTO proposal (id, kind, intent, options, status, created_at) VALUES ('p_zero_created', 'ambiguous', 'RESCHEDULE', '[]', 'open', 0)"
        )
        assertNull("Proposal with created_at = 0 is expired and must return null", storage.getProposal("p_zero_created"))
        assertEquals("getOpenProposals must not return expired proposals", 0, storage.getOpenProposals().filter { it.id == "p_zero_created" }.size)
    }
}
