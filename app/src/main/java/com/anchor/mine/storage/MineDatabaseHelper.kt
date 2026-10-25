// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine Author
// Part of Mine. See LICENSE in the repository root.

package com.anchor.mine.storage

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

class MineDatabaseCallback : SupportSQLiteOpenHelper.Callback(1) {

    override fun onConfigure(db: SupportSQLiteDatabase) {
        super.onConfigure(db)
        db.enableWriteAheadLogging()
        // Use the official Support API for foreign keys
        if (!db.isReadOnly) {
            db.setForeignKeyConstraintsEnabled(true)
        }
        // Use query() instead of execSQL() for PRAGMAs to satisfy SQLCipher
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

        // Triggers enforce append-only rule
        db.execSQL("CREATE TRIGGER trg_raw_msg_no_update BEFORE UPDATE ON raw_message BEGIN SELECT RAISE(ABORT, 'raw_message is append-only'); END;")
        db.execSQL("CREATE TRIGGER trg_raw_msg_no_delete BEFORE DELETE ON raw_message BEGIN SELECT RAISE(ABORT, 'raw_message is append-only'); END;")
        db.execSQL("CREATE TRIGGER trg_raw_evt_no_update BEFORE UPDATE ON raw_event BEGIN SELECT RAISE(ABORT, 'raw_event is append-only'); END;")
        db.execSQL("CREATE TRIGGER trg_raw_evt_no_delete BEFORE DELETE ON raw_event BEGIN SELECT RAISE(ABORT, 'raw_event is append-only'); END;")
    }

    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Handled via Migration Snapshots later
    }
}

object DatabaseProvider {
    fun getHelper(context: Context, password: ByteArray): SupportSQLiteOpenHelper {
        System.loadLibrary("sqlcipher")
        val factory = SupportOpenHelperFactory(password)
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name("mine_encrypted.db")
            .callback(MineDatabaseCallback())
            .build()
        return factory.create(configuration)
    }
}