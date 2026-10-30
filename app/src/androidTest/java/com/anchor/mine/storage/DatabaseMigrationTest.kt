// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.mine.storage

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.anchor.core.models.IntentType
import com.anchor.core.models.Node
import com.anchor.core.models.Proposal
import com.anchor.core.models.ProactiveState
import com.anchor.core.models.RawEvent
import com.anchor.core.models.SoulFact
import kotlinx.coroutines.runBlocking
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DatabaseMigrationTest {

    // Simulates the exact schema before V7 (V6 schema)
    class V6Callback : SupportSQLiteOpenHelper.Callback(6) {
        override fun onCreate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE app_meta (\"key\" TEXT PRIMARY KEY, value TEXT NOT NULL)")
            db.execSQL("CREATE TABLE raw_message (id TEXT PRIMARY KEY, device_id TEXT, ts_utc INTEGER, tz_offset_min INTEGER, day_key TEXT, role TEXT, content TEXT, redacted_at INTEGER, reasoning TEXT, tags TEXT, persona_id TEXT, model_ref TEXT, tokens_in INTEGER, tokens_out INTEGER, client_msg_id TEXT, attrs TEXT)")
            db.execSQL("CREATE TABLE raw_event (op_id TEXT PRIMARY KEY, ts_utc INTEGER, device_id TEXT, actor TEXT, op_type TEXT, target_type TEXT, target_id TEXT, before_json TEXT, after_json TEXT, cause_msg_id TEXT, undo_of TEXT, attrs TEXT)")
            db.execSQL("CREATE TABLE node (id TEXT PRIMARY KEY, device_id TEXT, type TEXT, title TEXT, body TEXT, status TEXT, priority INTEGER, severity INTEGER, start_at INTEGER, due_at INTEGER, time_precision TEXT, flexible INTEGER, lane TEXT, expires_at INTEGER, source TEXT, origin TEXT, source_msg_ids TEXT, extractor_version TEXT, attrs TEXT, local_only INTEGER, hlc TEXT, created_at INTEGER, updated_at INTEGER, closed_at INTEGER, deleted_at INTEGER)")
            db.execSQL("CREATE TABLE reminder (id TEXT PRIMARY KEY, text TEXT NOT NULL, fire_at INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE proposal (id TEXT PRIMARY KEY, kind TEXT NOT NULL, intent TEXT NOT NULL, options TEXT NOT NULL, status TEXT NOT NULL DEFAULT 'open', created_at INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE edge (id TEXT PRIMARY KEY, device_id TEXT, src_id TEXT, dst_id TEXT, dst_kind TEXT, type TEXT, weight REAL, source TEXT, origin TEXT, source_msg_ids TEXT, attrs TEXT, hlc TEXT, created_at INTEGER, updated_at INTEGER, deleted_at INTEGER)")
            db.execSQL("CREATE TABLE soul_fact (id TEXT PRIMARY KEY, device_id TEXT NOT NULL, category TEXT NOT NULL, statement TEXT NOT NULL, attrs TEXT, confidence REAL NOT NULL DEFAULT 0.7, source TEXT NOT NULL, origin TEXT NOT NULL, status TEXT NOT NULL, pinned_core INTEGER NOT NULL DEFAULT 0, sensitive INTEGER NOT NULL DEFAULT 0, local_only INTEGER NOT NULL DEFAULT 0, hlc TEXT NOT NULL, valid_from INTEGER, valid_to INTEGER, supersedes TEXT, source_msg_ids TEXT, extractor_version TEXT, last_confirmed_at INTEGER, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, deleted_at INTEGER)")
            db.execSQL("CREATE TABLE msg_chunk (id TEXT PRIMARY KEY, day_key TEXT NOT NULL, ts_start INTEGER NOT NULL, ts_end INTEGER NOT NULL, msg_ids TEXT NOT NULL, text TEXT NOT NULL, extractor_version TEXT NOT NULL)")
            db.execSQL("CREATE TABLE day_rule (id TEXT PRIMARY KEY, effective_from INTEGER NOT NULL, start_minute INTEGER NOT NULL, end_minute INTEGER NOT NULL, tz_id TEXT NOT NULL, created_at INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE day_block (day_key TEXT NOT NULL, idx INTEGER NOT NULL, start_msg_id TEXT NOT NULL, start_ts INTEGER NOT NULL, end_ts INTEGER NOT NULL, msg_count INTEGER NOT NULL, part_of_day TEXT, PRIMARY KEY (day_key, idx))")
            db.execSQL("CREATE TABLE export_log (id TEXT PRIMARY KEY, ts INTEGER NOT NULL, kind TEXT NOT NULL, options_json TEXT, counts_json TEXT, bytes INTEGER, verified INTEGER, note TEXT)")

            db.execSQL("CREATE VIRTUAL TABLE node_fts USING fts5(title, body, content='node', content_rowid='rowid')")
            db.execSQL("CREATE VIRTUAL TABLE soul_fts USING fts5(statement, content='soul_fact', content_rowid='rowid')")
            db.execSQL("CREATE VIRTUAL TABLE msg_chunk_fts USING fts5(text, content='msg_chunk', content_rowid='rowid')")
        }
        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
    }

    @Test
    fun testMigrationV6toV7_AndEveryMethod() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase("mine_mig_v6_v7.db")
        val key = ByteArray(32) { 3 }

        System.loadLibrary("sqlcipher")
        val factory = SupportOpenHelperFactory(key)
        val v6Config = SupportSQLiteOpenHelper.Configuration.builder(context).name("mine_mig_v6_v7.db").callback(V6Callback()).build()
        val v6Db = factory.create(v6Config).writableDatabase
        v6Db.close()

        // Open with V7 Helper
        val v7Helper = DatabaseProvider.getHelper(context, key, "mine_mig_v6_v7.db")
        val storage = AndroidStorage(v7Helper)

        val state = ProactiveState(openThreadMsgId = "msg_test", sentToday = 5)
        storage.saveProactiveState(state)
        val fetched = storage.getProactiveState()
        assertEquals("msg_test", fetched.openThreadMsgId)
        assertEquals(5, fetched.sentToday)

        v7Helper.close()
    }

    @Test
    fun testFreshV7SmokeTest_RunsEveryStorageMethod() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase("mine_smoke_test.db")
        val key = ByteArray(32) { 2 }
        
        val v7Helper = DatabaseProvider.getHelper(context, key, "mine_smoke_test.db")
        val storage = AndroidStorage(v7Helper)
        
        storage.insertRawMessage("msg_s1", "user", "Hello")
        assertEquals("Hello", storage.getRawMessage("msg_s1"))
        
        storage.redactMessage("msg_s1")
        assertNull(storage.getRawMessage("msg_s1"))
        
        storage.saveProposal(Proposal("p_fresh", "ambiguous", IntentType.CREATE, listOf()))
        storage.consumeProposal("p_fresh")
        assertEquals("consumed", storage.getProposal("p_fresh")?.status)
        
        val n = Node("n_s", "task", "Smoke Task", "active")
        val e = RawEvent("e_s", "CREATE", "node", "n_s")
        storage.createNodeAndCommit(n, e, null)
        
        val stats = storage.getDebugStats()
        assertTrue(stats.containsKey("Rows in raw_message"))
        
        // Proactive State persistence
        val state = ProactiveState(sentToday = 12, backoffStep = 2)
        storage.saveProactiveState(state)
        val fetchedState = storage.getProactiveState()
        assertEquals(12, fetchedState.sentToday)
        assertEquals(2, fetchedState.backoffStep)

        // FTS Sync Verification
        val nFts = Node("n_fts", "task", "Bananas", "active")
        val eFts = RawEvent("e_fts", "CREATE", "node", "n_fts")
        storage.createNodeAndCommit(nFts, eFts, null)
        
        storage.saveSoulFact(SoulFact("s_1", "diet", "Allergic to Apples", 1.0, "chat", "approved", "active", 1000L))
        
        val searchTasks = storage.searchMemoryFts("Bananas", 5)
        assertTrue("Node FTS Trigger must fire and populate index", searchTasks.any { it.title == "Bananas" })
        
        val searchFacts = storage.searchMemoryFts("Apples", 5)
        assertTrue("Soul FTS Trigger must fire and populate index", searchFacts.any { it.details == "Allergic to Apples" })
        
        v7Helper.close()
    }
}
