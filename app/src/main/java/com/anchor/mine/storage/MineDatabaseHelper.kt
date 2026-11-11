// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.mine.storage

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

// BUMPED to Version 7 (v1.1 Item 3: recurrence rules and habit streaks)
class MineDatabaseCallback : SupportSQLiteOpenHelper.Callback(DATABASE_VERSION) {

    override fun onConfigure(db: SupportSQLiteDatabase) {
        super.onConfigure(db)
        if (!db.isReadOnly) db.setForeignKeyConstraintsEnabled(true)
        db.query("PRAGMA secure_delete = ON").close()
        db.query("PRAGMA busy_timeout = 5000").close()
        db.query("PRAGMA synchronous = NORMAL").close()
    }

    override fun onCreate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE app_meta (\"key\" TEXT PRIMARY KEY, value TEXT NOT NULL)")
        db.execSQL("CREATE TABLE raw_message (id TEXT PRIMARY KEY, device_id TEXT, ts_utc INTEGER, tz_offset_min INTEGER, day_key TEXT, role TEXT, content TEXT, redacted_at INTEGER, reasoning TEXT, tags TEXT, persona_id TEXT, model_ref TEXT, tokens_in INTEGER, tokens_out INTEGER, client_msg_id TEXT, attrs TEXT)")
        db.execSQL("CREATE TABLE raw_event (op_id TEXT PRIMARY KEY, ts_utc INTEGER, device_id TEXT, actor TEXT, op_type TEXT, target_type TEXT, target_id TEXT, before_json TEXT, after_json TEXT, cause_msg_id TEXT, undo_of TEXT, attrs TEXT)")
        db.execSQL("CREATE TABLE node (id TEXT PRIMARY KEY, device_id TEXT, type TEXT, title TEXT, body TEXT, status TEXT, priority INTEGER, severity INTEGER, start_at INTEGER, due_at INTEGER, time_precision TEXT, flexible INTEGER, lane TEXT, expires_at INTEGER, source TEXT, origin TEXT, source_msg_ids TEXT, extractor_version TEXT, attrs TEXT, local_only INTEGER, hlc TEXT, created_at INTEGER, updated_at INTEGER, closed_at INTEGER, deleted_at INTEGER, recurrence_rule TEXT, streak_current INTEGER NOT NULL DEFAULT 0, streak_best INTEGER NOT NULL DEFAULT 0, last_completed_at INTEGER)")
        db.execSQL("CREATE TABLE edge (id TEXT PRIMARY KEY, device_id TEXT, src_id TEXT, dst_id TEXT, dst_kind TEXT, type TEXT, weight REAL, source TEXT, origin TEXT, source_msg_ids TEXT, attrs TEXT, hlc TEXT, created_at INTEGER, updated_at INTEGER, deleted_at INTEGER)")
        db.execSQL("CREATE TABLE soul_fact (id TEXT PRIMARY KEY, device_id TEXT NOT NULL, category TEXT NOT NULL, statement TEXT NOT NULL, attrs TEXT, confidence REAL NOT NULL DEFAULT 0.7, source TEXT NOT NULL, origin TEXT NOT NULL, status TEXT NOT NULL, pinned_core INTEGER NOT NULL DEFAULT 0, sensitive INTEGER NOT NULL DEFAULT 0, local_only INTEGER NOT NULL DEFAULT 0, hlc TEXT NOT NULL, valid_from INTEGER, valid_to INTEGER, supersedes TEXT, source_msg_ids TEXT, extractor_version TEXT, last_confirmed_at INTEGER, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, deleted_at INTEGER)")
        db.execSQL("CREATE TABLE msg_chunk (id TEXT PRIMARY KEY, day_key TEXT NOT NULL, ts_start INTEGER NOT NULL, ts_end INTEGER NOT NULL, msg_ids TEXT NOT NULL, text TEXT NOT NULL, extractor_version TEXT NOT NULL)")
        db.execSQL("CREATE TABLE reminder (id TEXT PRIMARY KEY, node_id TEXT, text TEXT NOT NULL, fire_at INTEGER NOT NULL, tier TEXT NOT NULL DEFAULT 'alarm', rrule TEXT, iteration INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE proposal (id TEXT PRIMARY KEY, kind TEXT NOT NULL, intent TEXT NOT NULL, options TEXT NOT NULL, status TEXT NOT NULL DEFAULT 'open', created_at INTEGER NOT NULL, payload TEXT)")
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
        db.execSQL(OUTBOX_DDL)
        db.execSQL(EMBEDDING_DDL)
        db.execSQL(PLACE_DDL)
        db.execSQL(PLACE_SIGNAL_DDL)
        db.execSQL(SENSING_EVENT_DDL)
        db.execSQL(CALL_EVENT_DDL)
        db.execSQL(VOICE_JOB_DDL)
        db.execSQL(MODEL_ASSET_DDL)

        createIndexes(db)
        createTriggers(db)
    }

    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            var columnExists = false
            db.query("PRAGMA table_info(reminder)").use { cursor ->
                val nameIndex = cursor.getColumnIndex("name")
                while (cursor.moveToNext()) {
                    if (nameIndex != -1 && cursor.getString(nameIndex).equals("tier", ignoreCase = true)) {
                        columnExists = true
                        break
                    }
                }
            }
            if (!columnExists) {
                db.execSQL("ALTER TABLE reminder ADD COLUMN tier TEXT NOT NULL DEFAULT 'alarm'")
            }
        }
        if (oldVersion < 3) {
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_edge_src_type ON edge(src_id, type)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_edge_dst_type ON edge(dst_id, type)")
        }
        if (oldVersion < 4) {
            db.execSQL("CREATE TABLE IF NOT EXISTS quote_slot (slot TEXT NOT NULL, day_key TEXT NOT NULL, text TEXT NOT NULL, generated_at INTEGER NOT NULL, state_hash TEXT, source TEXT NOT NULL, regen_count INTEGER NOT NULL DEFAULT 0, PRIMARY KEY (slot, day_key))")
        }
        if (oldVersion < 5) {
            var columnExists = false
            db.query("PRAGMA table_info(proposal)").use { cursor ->
                val nameIndex = cursor.getColumnIndex("name")
                while (cursor.moveToNext()) {
                    if (nameIndex != -1 && cursor.getString(nameIndex).equals("payload", ignoreCase = true)) {
                        columnExists = true
                        break
                    }
                }
            }
            if (!columnExists) {
                db.execSQL("ALTER TABLE proposal ADD COLUMN payload TEXT")
            }
        }
        if (oldVersion < 6) {
            db.execSQL(OUTBOX_DDL)
        }
        if (oldVersion < 7) {
            val nodeCols = mutableSetOf<String>()
            db.query("PRAGMA table_info(node)").use { cursor ->
                val nameIdx = cursor.getColumnIndex("name")
                while (cursor.moveToNext()) {
                    if (nameIdx != -1) nodeCols.add(cursor.getString(nameIdx).lowercase())
                }
            }
            if ("recurrence_rule" !in nodeCols) {
                db.execSQL("ALTER TABLE node ADD COLUMN recurrence_rule TEXT")
            }
            if ("streak_current" !in nodeCols) {
                db.execSQL("ALTER TABLE node ADD COLUMN streak_current INTEGER NOT NULL DEFAULT 0")
            }
            if ("streak_best" !in nodeCols) {
                db.execSQL("ALTER TABLE node ADD COLUMN streak_best INTEGER NOT NULL DEFAULT 0")
            }
            if ("last_completed_at" !in nodeCols) {
                db.execSQL("ALTER TABLE node ADD COLUMN last_completed_at INTEGER")
            }

            val remCols = mutableSetOf<String>()
            db.query("PRAGMA table_info(reminder)").use { cursor ->
                val nameIdx = cursor.getColumnIndex("name")
                while (cursor.moveToNext()) {
                    if (nameIdx != -1) remCols.add(cursor.getString(nameIdx).lowercase())
                }
            }
            if ("rrule" !in remCols) {
                db.execSQL("ALTER TABLE reminder ADD COLUMN rrule TEXT")
            }
            if ("iteration" !in remCols) {
                db.execSQL("ALTER TABLE reminder ADD COLUMN iteration INTEGER NOT NULL DEFAULT 0")
            }
        }
        if (oldVersion < 8) {
            db.execSQL(EMBEDDING_DDL)
        }
        if (oldVersion < 9) {
            db.execSQL(PLACE_DDL)
            db.execSQL(PLACE_SIGNAL_DDL)
            db.execSQL(SENSING_EVENT_DDL)
        }
        if (oldVersion < 10) {
            db.execSQL(CALL_EVENT_DDL)
        }
        if (oldVersion < 11) {
            db.execSQL(VOICE_JOB_DDL)
            db.execSQL(MODEL_ASSET_DDL)
        }
        // T19: an upgraded device must end up with the same indexes and triggers as a fresh
        // install. Indexes were previously created in onCreate only, so a device upgraded from v1
        // never got idx_reminder_fire - the index that makes the R-26 `ORDER BY fire_at` cheap.
        createIndexes(db)
        createTriggers(db)
        // B3-13: Rebuild FTS tables on upgrade to ensure integrity
        runCatching {
            db.execSQL("INSERT INTO node_fts(node_fts) VALUES('rebuild')")
            db.execSQL("INSERT INTO soul_fts(soul_fts) VALUES('rebuild')")
            db.execSQL("INSERT INTO msg_chunk_fts(msg_chunk_fts) VALUES('rebuild')")
        }
    }

    override fun onOpen(db: SupportSQLiteDatabase) {
        super.onOpen(db)
        val triggerCount = try {
            db.query("SELECT COUNT(*) FROM sqlite_master WHERE type = 'trigger'").use {
                if (it.moveToFirst()) it.getInt(0) else 0
            }
        } catch (_: Exception) { 0 }

        if (triggerCount < 13) {
            createIndexes(db)
            createTriggers(db)
        }
    }

    companion object {
        const val DATABASE_VERSION = 11

        /**
         * Blueprint 41 §4, verbatim. Shared by [onCreate] and the `oldVersion < 6` upgrade branch so
         * a fresh install and an upgraded device cannot drift (T19).
         */
        private const val OUTBOX_DDL =
            "CREATE TABLE IF NOT EXISTS outbox (id TEXT PRIMARY KEY, job_type TEXT NOT NULL, " +
                "priority INTEGER NOT NULL, payload_json TEXT NOT NULL, idem_key TEXT NOT NULL UNIQUE, " +
                "status TEXT NOT NULL DEFAULT 'pending', attempts INTEGER NOT NULL DEFAULT 0, " +
                "next_try_at INTEGER NOT NULL, deadline_at INTEGER, created_at INTEGER NOT NULL, " +
                "updated_at INTEGER NOT NULL, last_error TEXT)"

        /**
         * Blueprint 21 §3.3: Quantized vector embeddings table.
         */
        private const val EMBEDDING_DDL =
            "CREATE TABLE IF NOT EXISTS embedding (" +
                "owner_type TEXT NOT NULL, " +
                "owner_id TEXT NOT NULL, " +
                "model_version TEXT NOT NULL, " +
                "dim INTEGER NOT NULL, " +
                "scale REAL NOT NULL, " +
                "vec BLOB NOT NULL, " +
                "created_at INTEGER NOT NULL, " +
                "PRIMARY KEY (owner_type, owner_id, model_version))"

        /** Blueprint 43 §4: Context Sensing Places. */
        private const val PLACE_DDL =
            "CREATE TABLE IF NOT EXISTS place (" +
                "id TEXT PRIMARY KEY, " +
                "node_id TEXT, " +
                "kind TEXT NOT NULL, " +
                "label TEXT NOT NULL, " +
                "updated_at INTEGER NOT NULL, " +
                "deleted_at INTEGER, " +
                "hlc TEXT)"

        /** Blueprint 43 §4: Device-local Place Signals (never exported). */
        private const val PLACE_SIGNAL_DDL =
            "CREATE TABLE IF NOT EXISTS place_signal (" +
                "place_id TEXT PRIMARY KEY, " +
                "wifi_json TEXT, " +
                "geo_lat REAL, " +
                "geo_lon REAL, " +
                "geo_radius_m INTEGER)"

        /** Blueprint 43 §4: Device-local Sensing Event Log (pruned). */
        private const val SENSING_EVENT_DDL =
            "CREATE TABLE IF NOT EXISTS sensing_event (" +
                "id TEXT PRIMARY KEY, " +
                "ts_utc INTEGER NOT NULL, " +
                "axis TEXT NOT NULL, " +
                "from_state TEXT, " +
                "to_state TEXT NOT NULL, " +
                "place_id TEXT, " +
                "confidence REAL, " +
                "source TEXT, " +
                "attrs TEXT)"

        /** Blueprint 44 §4: Device-local Call Events (pruned after 30 days). */
        private const val CALL_EVENT_DDL =
            "CREATE TABLE IF NOT EXISTS call_event (" +
                "id TEXT PRIMARY KEY, " +
                "started_at INTEGER NOT NULL, " +
                "ended_at INTEGER NOT NULL, " +
                "duration_s INTEGER NOT NULL, " +
                "direction TEXT, " +
                "contact_ref TEXT, " +
                "nudge_id TEXT, " +
                "state TEXT NOT NULL, " +
                "created_at INTEGER NOT NULL)"

        /** Blueprint 60 §4: Device-local Voice Jobs. */
        private const val VOICE_JOB_DDL =
            "CREATE TABLE IF NOT EXISTS voice_job (" +
                "id TEXT PRIMARY KEY, " +
                "kind TEXT NOT NULL, " +
                "state TEXT NOT NULL, " +
                "msg_id TEXT, " +
                "audio_path TEXT, " +
                "target_ref TEXT, " +
                "attempts INTEGER DEFAULT 0, " +
                "created_at INTEGER NOT NULL, " +
                "updated_at INTEGER NOT NULL, " +
                "error TEXT)"

        /** Blueprint 60 §4: Device-local Model Assets. */
        private const val MODEL_ASSET_DDL =
            "CREATE TABLE IF NOT EXISTS model_asset (" +
                "id TEXT PRIMARY KEY, " +
                "kind TEXT NOT NULL, " +
                "name TEXT NOT NULL, " +
                "version TEXT, " +
                "size_bytes INTEGER, " +
                "sha256 TEXT, " +
                "license_ref TEXT, " +
                "path TEXT, " +
                "state TEXT NOT NULL, " +
                "updated_at INTEGER NOT NULL)"

        private val INDEXES = listOf(
            "CREATE INDEX IF NOT EXISTS idx_node_status_due ON node(status, due_at)",
            "CREATE INDEX IF NOT EXISTS idx_node_habit ON node(type, streak_current)",
            "CREATE INDEX IF NOT EXISTS idx_raw_event_target ON raw_event(target_type, target_id)",
            "CREATE INDEX IF NOT EXISTS idx_raw_message_ts ON raw_message(ts_utc)",
            "CREATE INDEX IF NOT EXISTS idx_raw_message_day ON raw_message(day_key, ts_utc)",
            "CREATE INDEX IF NOT EXISTS idx_reminder_fire ON reminder(fire_at)",
            "CREATE INDEX IF NOT EXISTS idx_edge_src_type ON edge(src_id, type)",
            "CREATE INDEX IF NOT EXISTS idx_edge_dst_type ON edge(dst_id, type)",
            "CREATE INDEX IF NOT EXISTS idx_outbox_due ON outbox(status, priority, next_try_at)",
            "CREATE INDEX IF NOT EXISTS idx_reminder_node_id ON reminder(node_id)",
            "CREATE INDEX IF NOT EXISTS idx_node_status_closed ON node(status, closed_at)",
            "CREATE INDEX IF NOT EXISTS idx_soul_fact_status ON soul_fact(status)",
            "CREATE INDEX IF NOT EXISTS idx_signal_log_kind_ts ON signal_log(kind, ts_utc)",
            "CREATE INDEX IF NOT EXISTS idx_proposal_status_created ON proposal(status, created_at)",
            "CREATE INDEX IF NOT EXISTS idx_embedding_owner ON embedding(owner_type, model_version)",
            "CREATE INDEX IF NOT EXISTS idx_sensing_event_ts ON sensing_event(ts_utc)",
            "CREATE INDEX IF NOT EXISTS idx_place_kind ON place(kind)",
            "CREATE INDEX IF NOT EXISTS idx_call_event_ended ON call_event(ended_at)",
            "CREATE INDEX IF NOT EXISTS idx_voice_job_state ON voice_job(state)"
        )

        /** Every trigger created by [createTriggers], in drop order. */
        private val TRIGGER_NAMES = listOf(
            "trg_raw_msg_no_update",
            "trg_raw_msg_no_delete",
            "trg_raw_evt_no_update",
            "trg_raw_evt_no_delete",
            "trg_node_fts_insert",
            "trg_node_fts_delete",
            "trg_node_fts_update",
            "trg_soul_fts_insert",
            "trg_soul_fts_delete",
            "trg_soul_fts_update",
            "trg_msg_chunk_fts_insert",
            "trg_msg_chunk_fts_delete",
            "trg_msg_chunk_fts_update"
        )

        /** FTS tables backing the triggers. Created by [createTriggers] on every open. */
        private val FTS_TABLES = listOf(
            "CREATE VIRTUAL TABLE IF NOT EXISTS node_fts USING fts5(title, body, content='node', content_rowid='rowid')",
            "CREATE VIRTUAL TABLE IF NOT EXISTS soul_fts USING fts5(statement, content='soul_fact', content_rowid='rowid')",
            "CREATE VIRTUAL TABLE IF NOT EXISTS msg_chunk_fts USING fts5(text, content='msg_chunk', content_rowid='rowid')"
        )

        fun createIndexes(db: SupportSQLiteDatabase) {
            for (sql in INDEXES) db.execSQL(sql)
        }

        fun createTriggers(db: SupportSQLiteDatabase) {
            // T19: every trigger below was `CREATE TRIGGER IF NOT EXISTS`, so a device that had
            // already created a trigger kept the OLD body forever - editing the SQL here never
            // reached an upgraded install. Dropping first makes this function authoritative: what
            // is written here is what runs, on fresh installs and upgrades alike.
            for (trigger in TRIGGER_NAMES) {
                db.execSQL("DROP TRIGGER IF EXISTS $trigger")
            }
            for (sql in FTS_TABLES) db.execSQL(sql)
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
            db.execSQL("""
                CREATE TRIGGER IF NOT EXISTS trg_raw_evt_no_update BEFORE UPDATE ON raw_event
                BEGIN
                    SELECT RAISE(ABORT, 'raw_event is append-only except for erase scrubbing')
                    WHERE NEW.before_json IS NOT NULL 
                       OR NEW.after_json IS NOT NULL 
                       OR OLD.op_id != NEW.op_id
                       OR OLD.ts_utc != NEW.ts_utc
                       OR OLD.device_id != NEW.device_id
                       OR OLD.actor != NEW.actor
                       OR OLD.op_type != NEW.op_type
                       OR OLD.target_type != NEW.target_type
                       OR OLD.target_id != NEW.target_id
                       OR IFNULL(OLD.cause_msg_id, '') != IFNULL(NEW.cause_msg_id, '')
                       OR IFNULL(OLD.undo_of, '') != IFNULL(NEW.undo_of, '')
                       OR IFNULL(OLD.attrs, '') != IFNULL(NEW.attrs, '');
                END;
            """)
            db.execSQL("CREATE TRIGGER IF NOT EXISTS trg_raw_evt_no_delete BEFORE DELETE ON raw_event BEGIN SELECT RAISE(ABORT, 'raw_event is append-only'); END;")
            
            db.execSQL("CREATE TRIGGER IF NOT EXISTS trg_node_fts_insert AFTER INSERT ON node BEGIN INSERT INTO node_fts (rowid, title, body) VALUES (new.rowid, new.title, new.body); END;")
            db.execSQL("CREATE TRIGGER IF NOT EXISTS trg_node_fts_delete AFTER DELETE ON node BEGIN INSERT INTO node_fts (node_fts, rowid, title, body) VALUES ('delete', old.rowid, old.title, old.body); END;")
            db.execSQL("CREATE TRIGGER IF NOT EXISTS trg_node_fts_update AFTER UPDATE ON node BEGIN INSERT INTO node_fts (node_fts, rowid, title, body) VALUES ('delete', old.rowid, old.title, old.body); INSERT INTO node_fts (rowid, title, body) VALUES (new.rowid, new.title, new.body); END;")
            
            db.execSQL("CREATE TRIGGER IF NOT EXISTS trg_soul_fts_insert AFTER INSERT ON soul_fact BEGIN INSERT INTO soul_fts (rowid, statement) VALUES (new.rowid, new.statement); END;")
            db.execSQL("CREATE TRIGGER IF NOT EXISTS trg_soul_fts_delete AFTER DELETE ON soul_fact BEGIN INSERT INTO soul_fts (soul_fts, rowid, statement) VALUES ('delete', old.rowid, old.statement); END;")
            db.execSQL("CREATE TRIGGER IF NOT EXISTS trg_soul_fts_update AFTER UPDATE ON soul_fact BEGIN INSERT INTO soul_fts (soul_fts, rowid, statement) VALUES ('delete', old.rowid, old.statement); INSERT INTO soul_fts (rowid, statement) VALUES (new.rowid, new.statement); END;")

            db.execSQL("CREATE TRIGGER IF NOT EXISTS trg_msg_chunk_fts_insert AFTER INSERT ON msg_chunk BEGIN INSERT INTO msg_chunk_fts (rowid, text) VALUES (new.rowid, new.text); END;")
            db.execSQL("CREATE TRIGGER IF NOT EXISTS trg_msg_chunk_fts_delete AFTER DELETE ON msg_chunk BEGIN INSERT INTO msg_chunk_fts (msg_chunk_fts, rowid, text) VALUES ('delete', old.rowid, old.text); END;")
            db.execSQL("CREATE TRIGGER IF NOT EXISTS trg_msg_chunk_fts_update AFTER UPDATE ON msg_chunk BEGIN INSERT INTO msg_chunk_fts (msg_chunk_fts, rowid, text) VALUES ('delete', old.rowid, old.text); INSERT INTO msg_chunk_fts (rowid, text) VALUES (new.rowid, new.text); END;")
        }
    }
}

object DatabaseProvider {
    fun getHelper(context: Context, password: ByteArray, dbName: String = "mine_encrypted.db"): SupportSQLiteOpenHelper {
        System.loadLibrary("sqlcipher")
        val factory = SupportOpenHelperFactory(password, null, true)
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(dbName)
            .callback(MineDatabaseCallback())
            .build()
        return factory.create(configuration)
    }
}
