// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.mine.storage

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

// BUMPED to Version 5 for Day Boundaries and Export Log
class MineDatabaseCallback : SupportSQLiteOpenHelper.Callback(5) {

    override fun onConfigure(db: SupportSQLiteDatabase) {
        super.onConfigure(db)
        db.enableWriteAheadLogging()
        if (!db.isReadOnly) db.setForeignKeyConstraintsEnabled(true)
        db.query("PRAGMA secure_delete = ON").close()
    }

    override fun onCreate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE app_meta (\"key\" TEXT PRIMARY KEY, value TEXT NOT NULL)")
        db.execSQL("""
            CREATE TABLE raw_message (
              id TEXT PRIMARY KEY, device_id TEXT, ts_utc INTEGER, tz_offset_min INTEGER, day_key TEXT,
              role TEXT, content TEXT, redacted_at INTEGER, reasoning TEXT, tags TEXT, persona_id TEXT,
              model_ref TEXT, tokens_in INTEGER, tokens_out INTEGER, client_msg_id TEXT, attrs TEXT
            )
        """)
        db.execSQL("""
            CREATE TABLE raw_event (
              op_id TEXT PRIMARY KEY, ts_utc INTEGER, device_id TEXT, actor TEXT, op_type TEXT,
              target_type TEXT, target_id TEXT, before_json TEXT, after_json TEXT, cause_msg_id TEXT,
              undo_of TEXT, attrs TEXT
            )
        """)
        db.execSQL("""
            CREATE TABLE node (
              id TEXT PRIMARY KEY, device_id TEXT, type TEXT, title TEXT, body TEXT, status TEXT,
              priority INTEGER, severity INTEGER, start_at INTEGER, due_at INTEGER, time_precision TEXT,
              flexible INTEGER, lane TEXT, expires_at INTEGER, source TEXT, origin TEXT,
              source_msg_ids TEXT, extractor_version TEXT, attrs TEXT, local_only INTEGER, hlc TEXT,
              created_at INTEGER, updated_at INTEGER, closed_at INTEGER, deleted_at INTEGER
            )
        """)
        db.execSQL("""
            CREATE TABLE edge (
              id TEXT PRIMARY KEY, device_id TEXT, src_id TEXT, dst_id TEXT, dst_kind TEXT, type TEXT,
              weight REAL, source TEXT, origin TEXT, source_msg_ids TEXT, attrs TEXT, hlc TEXT,
              created_at INTEGER, updated_at INTEGER, deleted_at INTEGER
            )
        """)
        db.execSQL("""
            CREATE TABLE soul_fact (
              id TEXT PRIMARY KEY, device_id TEXT NOT NULL, category TEXT NOT NULL, statement TEXT NOT NULL,
              attrs TEXT, confidence REAL NOT NULL DEFAULT 0.7, source TEXT NOT NULL, origin TEXT NOT NULL,
              status TEXT NOT NULL, pinned_core INTEGER NOT NULL DEFAULT 0, sensitive INTEGER NOT NULL DEFAULT 0,
              local_only INTEGER NOT NULL DEFAULT 0, hlc TEXT NOT NULL, valid_from INTEGER, valid_to INTEGER,
              supersedes TEXT, source_msg_ids TEXT, extractor_version TEXT, last_confirmed_at INTEGER,
              created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, deleted_at INTEGER
            )
        """)
        db.execSQL("""
            CREATE TABLE msg_chunk (
              id TEXT PRIMARY KEY, day_key TEXT NOT NULL, ts_start INTEGER NOT NULL, ts_end INTEGER NOT NULL,
              msg_ids TEXT NOT NULL, text TEXT NOT NULL, extractor_version TEXT NOT NULL
            )
        """)
        
        db.execSQL("CREATE TABLE reminder (id TEXT PRIMARY KEY, text TEXT NOT NULL, fire_at INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE proposal (id TEXT PRIMARY KEY, kind TEXT NOT NULL, intent TEXT NOT NULL, options TEXT NOT NULL, status TEXT NOT NULL DEFAULT 'open', created_at INTEGER NOT NULL)")
        
        // V5 Tables
        db.execSQL("CREATE TABLE day_rule (id TEXT PRIMARY KEY, effective_from INTEGER NOT NULL, start_minute INTEGER NOT NULL, end_minute INTEGER NOT NULL, tz_id TEXT NOT NULL, created_at INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE day_block (day_key TEXT NOT NULL, idx INTEGER NOT NULL, start_msg_id TEXT NOT NULL, start_ts INTEGER NOT NULL, end_ts INTEGER NOT NULL, msg_count INTEGER NOT NULL, part_of_day TEXT, PRIMARY KEY (day_key, idx))")
        db.execSQL("CREATE TABLE export_log (id TEXT PRIMARY KEY, ts INTEGER NOT NULL, kind TEXT NOT NULL, options_json TEXT, counts_json TEXT, bytes INTEGER, verified INTEGER, note TEXT)")

        db.execSQL("CREATE INDEX idx_node_status_due ON node(status, due_at)")
        db.execSQL("CREATE INDEX idx_raw_event_target ON raw_event(target_type, target_id)")
        db.execSQL("CREATE INDEX idx_raw_message_ts ON raw_message(ts_utc)")
        
        // FTS5 Tables
        db.execSQL("CREATE VIRTUAL TABLE node_fts USING fts5(title, body, content='node', content_rowid='rowid')")
        db.execSQL("CREATE VIRTUAL TABLE soul_fts USING fts5(statement, content='soul_fact', content_rowid='rowid')")
        db.execSQL("CREATE VIRTUAL TABLE msg_chunk_fts USING fts5(text, content='msg_chunk', content_rowid='rowid')")
        
        createTriggers(db)
    }

    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("CREATE TABLE IF NOT EXISTS reminder (id TEXT PRIMARY KEY, text TEXT NOT NULL, fire_at INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE IF NOT EXISTS proposal (id TEXT PRIMARY KEY, kind TEXT NOT NULL, intent TEXT NOT NULL, options TEXT NOT NULL, created_at INTEGER NOT NULL)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_node_status_due ON node(status, due_at)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_raw_event_target ON raw_event(target_type, target_id)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_raw_message_ts ON raw_message(ts_utc)")
        }
        if (oldVersion < 3) {
            val cursor = db.query("PRAGMA table_info(proposal)")
            var hasStatus = false
            while (cursor.moveToNext()) { if (cursor.getString(1) == "status") hasStatus = true }
            cursor.close()
            if (!hasStatus) db.execSQL("ALTER TABLE proposal ADD COLUMN status TEXT NOT NULL DEFAULT 'open'")
        }
        if (oldVersion < 4) {
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS soul_fact (
                  id TEXT PRIMARY KEY, device_id TEXT NOT NULL, category TEXT NOT NULL, statement TEXT NOT NULL,
                  attrs TEXT, confidence REAL NOT NULL DEFAULT 0.7, source TEXT NOT NULL, origin TEXT NOT NULL,
                  status TEXT NOT NULL, pinned_core INTEGER NOT NULL DEFAULT 0, sensitive INTEGER NOT NULL DEFAULT 0,
                  local_only INTEGER NOT NULL DEFAULT 0, hlc TEXT NOT NULL, valid_from INTEGER, valid_to INTEGER,
                  supersedes TEXT, source_msg_ids TEXT, extractor_version TEXT, last_confirmed_at INTEGER,
                  created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, deleted_at INTEGER
                )
            """)
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS msg_chunk (
                  id TEXT PRIMARY KEY, day_key TEXT NOT NULL, ts_start INTEGER NOT NULL, ts_end INTEGER NOT NULL,
                  msg_ids TEXT NOT NULL, text TEXT NOT NULL, extractor_version TEXT NOT NULL
                )
            """)
            db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS node_fts USING fts5(title, body, content='node', content_rowid='rowid')")
            db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS soul_fts USING fts5(statement, content='soul_fact', content_rowid='rowid')")
            db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS msg_chunk_fts USING fts5(text, content='msg_chunk', content_rowid='rowid')")
            
            db.execSQL("DROP TRIGGER IF EXISTS trg_raw_msg_no_update")
            db.execSQL("DROP TRIGGER IF EXISTS trg_raw_msg_no_delete")
            db.execSQL("DROP TRIGGER IF EXISTS trg_raw_evt_no_update")
            db.execSQL("DROP TRIGGER IF EXISTS trg_raw_evt_no_delete")
            createTriggers(db)
        }
        if (oldVersion < 5) {
            db.execSQL("CREATE TABLE IF NOT EXISTS day_rule (id TEXT PRIMARY KEY, effective_from INTEGER NOT NULL, start_minute INTEGER NOT NULL, end_minute INTEGER NOT NULL, tz_id TEXT NOT NULL, created_at INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE IF NOT EXISTS day_block (day_key TEXT NOT NULL, idx INTEGER NOT NULL, start_msg_id TEXT NOT NULL, start_ts INTEGER NOT NULL, end_ts INTEGER NOT NULL, msg_count INTEGER NOT NULL, part_of_day TEXT, PRIMARY KEY (day_key, idx))")
            db.execSQL("CREATE TABLE IF NOT EXISTS export_log (id TEXT PRIMARY KEY, ts INTEGER NOT NULL, kind TEXT NOT NULL, options_json TEXT, counts_json TEXT, bytes INTEGER, verified INTEGER, note TEXT)")
        }
    }

    private fun createTriggers(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TRIGGER trg_raw_msg_no_update BEFORE UPDATE ON raw_message
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
        db.execSQL("CREATE TRIGGER trg_raw_msg_no_delete BEFORE DELETE ON raw_message BEGIN SELECT RAISE(ABORT, 'raw_message is append-only'); END;")
        db.execSQL("CREATE TRIGGER trg_raw_evt_no_update BEFORE UPDATE ON raw_event BEGIN SELECT RAISE(ABORT, 'raw_event is append-only'); END;")
        db.execSQL("CREATE TRIGGER trg_raw_evt_no_delete BEFORE DELETE ON raw_event BEGIN SELECT RAISE(ABORT, 'raw_event is append-only'); END;")
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
