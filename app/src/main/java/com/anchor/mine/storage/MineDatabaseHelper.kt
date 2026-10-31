// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.mine.storage

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

// BUMPED to Version 1 (Squashed pristine schema)
class MineDatabaseCallback : SupportSQLiteOpenHelper.Callback(1) {

    override fun onConfigure(db: SupportSQLiteDatabase) {
        super.onConfigure(db)
        db.enableWriteAheadLogging()
        if (!db.isReadOnly) db.setForeignKeyConstraintsEnabled(true)
        db.query("PRAGMA secure_delete = ON").close()
    }

    override fun onCreate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE app_meta (\"key\" TEXT PRIMARY KEY, value TEXT NOT NULL)")
        db.execSQL("CREATE TABLE raw_message (id TEXT PRIMARY KEY, device_id TEXT, ts_utc INTEGER, tz_offset_min INTEGER, day_key TEXT, role TEXT, content TEXT, redacted_at INTEGER, reasoning TEXT, tags TEXT, persona_id TEXT, model_ref TEXT, tokens_in INTEGER, tokens_out INTEGER, client_msg_id TEXT, attrs TEXT)")
        db.execSQL("CREATE TABLE raw_event (op_id TEXT PRIMARY KEY, ts_utc INTEGER, device_id TEXT, actor TEXT, op_type TEXT, target_type TEXT, target_id TEXT, before_json TEXT, after_json TEXT, cause_msg_id TEXT, undo_of TEXT, attrs TEXT)")
        db.execSQL("CREATE TABLE node (id TEXT PRIMARY KEY, device_id TEXT, type TEXT, title TEXT, body TEXT, status TEXT, priority INTEGER, severity INTEGER, start_at INTEGER, due_at INTEGER, time_precision TEXT, flexible INTEGER, lane TEXT, expires_at INTEGER, source TEXT, origin TEXT, source_msg_ids TEXT, extractor_version TEXT, attrs TEXT, local_only INTEGER, hlc TEXT, created_at INTEGER, updated_at INTEGER, closed_at INTEGER, deleted_at INTEGER)")
        db.execSQL("CREATE TABLE edge (id TEXT PRIMARY KEY, device_id TEXT, src_id TEXT, dst_id TEXT, dst_kind TEXT, type TEXT, weight REAL, source TEXT, origin TEXT, source_msg_ids TEXT, attrs TEXT, hlc TEXT, created_at INTEGER, updated_at INTEGER, deleted_at INTEGER)")
        db.execSQL("CREATE TABLE soul_fact (id TEXT PRIMARY KEY, device_id TEXT NOT NULL, category TEXT NOT NULL, statement TEXT NOT NULL, attrs TEXT, confidence REAL NOT NULL DEFAULT 0.7, source TEXT NOT NULL, origin TEXT NOT NULL, status TEXT NOT NULL, pinned_core INTEGER NOT NULL DEFAULT 0, sensitive INTEGER NOT NULL DEFAULT 0, local_only INTEGER NOT NULL DEFAULT 0, hlc TEXT NOT NULL, valid_from INTEGER, valid_to INTEGER, supersedes TEXT, source_msg_ids TEXT, extractor_version TEXT, last_confirmed_at INTEGER, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, deleted_at INTEGER)")
        db.execSQL("CREATE TABLE msg_chunk (id TEXT PRIMARY KEY, day_key TEXT NOT NULL, ts_start INTEGER NOT NULL, ts_end INTEGER NOT NULL, msg_ids TEXT NOT NULL, text TEXT NOT NULL, extractor_version TEXT NOT NULL)")
        db.execSQL("CREATE TABLE reminder (id TEXT PRIMARY KEY, text TEXT NOT NULL, fire_at INTEGER NOT NULL)")
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
        
        createTriggers(db)
    }

    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Squashed to V1. No upgrades necessary.
    }

    companion object {
        fun createTriggers(db: SupportSQLiteDatabase) {
            db.execSQL("""
                CREATE TRIGGER IF NOT EXISTS trg_raw_msg_no_update BEFORE UPDATE ON raw_message
                BEGIN
                    SELECT RAISE(ABORT, 'raw_message is append-only except for redaction')
                    WHERE OLD.redacted_at IS NOT NULL 
                       OR NEW.redacted_at IS NULL 
                       OR NEW.content IS NOT NULL 
                       OR OLD.id != NEW.id
                       OR OLD.device_id != NEW.device_id
                       OR OLD.ts_utc != NEW.ts_utc
                       OR OLD.tz_offset_min != NEW.tz_offset_min
                       OR OLD.day_key != NEW.day_key
                       OR OLD.role != NEW.role
                       OR IFNULL(OLD.reasoning, '') != IFNULL(NEW.reasoning, '')
                       OR IFNULL(OLD.tags, '') != IFNULL(NEW.tags, '')
                       OR IFNULL(OLD.persona_id, '') != IFNULL(NEW.persona_id, '')
                       OR IFNULL(OLD.model_ref, '') != IFNULL(NEW.model_ref, '')
                       OR IFNULL(OLD.tokens_in, 0) != IFNULL(NEW.tokens_in, 0)
                       OR IFNULL(OLD.tokens_out, 0) != IFNULL(NEW.tokens_out, 0)
                       OR IFNULL(OLD.client_msg_id, '') != IFNULL(NEW.client_msg_id, '')
                       OR IFNULL(OLD.attrs, '') != IFNULL(NEW.attrs, '');
                END;
            """)
            db.execSQL("CREATE TRIGGER IF NOT EXISTS trg_raw_msg_no_delete BEFORE DELETE ON raw_message BEGIN SELECT RAISE(ABORT, 'raw_message is append-only'); END;")
            db.execSQL("CREATE TRIGGER IF NOT EXISTS trg_raw_evt_no_update BEFORE UPDATE ON raw_event BEGIN SELECT RAISE(ABORT, 'raw_event is append-only'); END;")
            db.execSQL("CREATE TRIGGER IF NOT EXISTS trg_raw_evt_no_delete BEFORE DELETE ON raw_event BEGIN SELECT RAISE(ABORT, 'raw_event is append-only'); END;")
            
            db.execSQL("CREATE TRIGGER IF NOT EXISTS trg_node_fts_insert AFTER INSERT ON node BEGIN INSERT INTO node_fts (rowid, title, body) VALUES (new.rowid, new.title, new.body); END;")
            db.execSQL("CREATE TRIGGER IF NOT EXISTS trg_node_fts_delete AFTER DELETE ON node BEGIN INSERT INTO node_fts (node_fts, rowid, title, body) VALUES ('delete', old.rowid, old.title, old.body); END;")
            db.execSQL("CREATE TRIGGER IF NOT EXISTS trg_node_fts_update AFTER UPDATE ON node BEGIN INSERT INTO node_fts (node_fts, rowid, title, body) VALUES ('delete', old.rowid, old.title, old.body); INSERT INTO node_fts (rowid, title, body) VALUES (new.rowid, new.title, new.body); END;")
            
            db.execSQL("CREATE TRIGGER IF NOT EXISTS trg_soul_fts_insert AFTER INSERT ON soul_fact BEGIN INSERT INTO soul_fts (rowid, statement) VALUES (new.rowid, new.statement); END;")
            db.execSQL("CREATE TRIGGER IF NOT EXISTS trg_soul_fts_delete AFTER DELETE ON soul_fact BEGIN INSERT INTO soul_fts (soul_fts, rowid, statement) VALUES ('delete', old.rowid, old.statement); END;")
            db.execSQL("CREATE TRIGGER IF NOT EXISTS trg_soul_fts_update AFTER UPDATE ON soul_fact BEGIN INSERT INTO soul_fts (soul_fts, rowid, statement) VALUES ('delete', old.rowid, old.statement); INSERT INTO soul_fts (rowid, statement) VALUES (new.rowid, new.statement); END;")
        }
    }
}

object DatabaseProvider {
    fun getHelper(context: Context, password: ByteArray, dbName: String = "mine_encrypted.db"): SupportSQLiteOpenHelper {
        System.loadLibrary("sqlcipher")
        val factory = SupportOpenHelperFactory(password)
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(dbName)
            .callback(MineDatabaseCallback())
            .build()
        return factory.create(configuration)
    }
}
