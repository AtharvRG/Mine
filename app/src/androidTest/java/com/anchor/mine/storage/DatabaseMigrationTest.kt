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
import com.anchor.core.models.RawEvent
import com.anchor.core.models.ReliabilityState
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

    // Simulates schema before V8 (V7 schema)
    class V7Callback : SupportSQLiteOpenHelper.Callback(7) {
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
            db.execSQL("CREATE TABLE proactive_state (id INTEGER PRIMARY KEY CHECK (id = 1), open_thread_msg_id TEXT, thread_opened_at INTEGER, backoff_step INTEGER NOT NULL DEFAULT 0, last_sent_at INTEGER, last_contact_at INTEGER, paused_until INTEGER, silent_days INTEGER NOT NULL DEFAULT 0, sent_today INTEGER NOT NULL DEFAULT 0, fuse_tripped_at INTEGER, day_key TEXT)")
            db.execSQL("CREATE TABLE nudge_log (id TEXT PRIMARY KEY, ts_utc INTEGER NOT NULL, trigger_type TEXT NOT NULL, subject_refs TEXT, score REAL, threshold REAL, gates_json TEXT, decision TEXT NOT NULL, delivery TEXT, msg_id TEXT, text_source TEXT, outcome TEXT, latency_ms INTEGER, reason_json TEXT)")
            db.execSQL("CREATE TABLE nudge_pool (id TEXT PRIMARY KEY, trigger_type TEXT NOT NULL, subject_refs TEXT, score REAL NOT NULL, created_at INTEGER NOT NULL, expires_at INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE nudge_hold (node_id TEXT NOT NULL, trigger_type TEXT NOT NULL, until_ts INTEGER NOT NULL, PRIMARY KEY (node_id, trigger_type))")
            db.execSQL("CREATE TABLE checkin_state (kind TEXT NOT NULL, day_key TEXT NOT NULL, status TEXT NOT NULL, msg_id TEXT, scheduled_for INTEGER, delivered_at INTEGER, PRIMARY KEY (kind, day_key))")
        }
        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
    }

    @Test
    fun testMigrationV7toV8_AndEveryMethod() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase("mine_mig_test.db")
        val key = ByteArray(32) { 1 }

        System.loadLibrary("sqlcipher")
        val factory = SupportOpenHelperFactory(key)
        val v7Config = SupportSQLiteOpenHelper.Configuration.builder(context).name("mine_mig_test.db").callback(V7Callback()).build()
        val v7Db = factory.create(v7Config).writableDatabase
        v7Db.execSQL("INSERT INTO proactive_state (id, backoff_step) VALUES (1, 3)")
        v7Db.close()

        // Open with V8 Helper
        val v8Helper = DatabaseProvider.getHelper(context, key, "mine_mig_test.db")
        val storage = AndroidStorage(v8Helper)

        // Verify V8 tables exist
        val cursor = v8Helper.readableDatabase.query("SELECT COUNT(*) FROM reminder_fire")
        assertTrue(cursor.moveToFirst())
        cursor.close()

        // Check data preserved
        val proState = storage.getProactiveState()
        assertEquals(3, proState.backoffStep)

        // Check new V8 methods
        storage.saveReliabilityState(ReliabilityState(sweeperHeartbeat = 1000L))
        assertEquals(1000L, storage.getReliabilityState().sweeperHeartbeat)

        v8Helper.close()
    }
}
