// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.mine.storage

import androidx.sqlite.db.SupportSQLiteOpenHelper
import com.anchor.core.models.*
import com.anchor.core.models.Reminder
import com.anchor.core.storage.Storage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

class AndroidStorage(private val dbHelper: SupportSQLiteOpenHelper) : Storage {
    override val ioDispatcher: CoroutineDispatcher = Dispatchers.IO

    private var deviceId: String = ""

    init {
        dbHelper.readableDatabase.query("SELECT value FROM app_meta WHERE \"key\" = 'device_id'").use {
            if (it.moveToFirst()) deviceId = it.getString(0)
        }
        if (deviceId.isEmpty()) {
            deviceId = java.util.UUID.randomUUID().toString()
            dbHelper.writableDatabase.execSQL(
                "INSERT INTO app_meta (\"key\", value) VALUES ('device_id', ?)", arrayOf(deviceId)
            )
        }
    }

    override suspend fun insertRawMessage(id: String, role: String, content: String) = withContext(ioDispatcher) {
        val ts = System.currentTimeMillis()
        
        var ruleStartMinute = 240 // Default 4 AM
        var tzId = TimeZone.getDefault().id
        
        // BP 50: Apply the day_rule in force at the message timestamp
        dbHelper.readableDatabase.query("SELECT start_minute, tz_id FROM day_rule WHERE effective_from <= ? ORDER BY effective_from DESC LIMIT 1", arrayOf(ts)).use {
            if (it.moveToFirst()) {
                ruleStartMinute = it.getInt(0)
                tzId = it.getString(1)
            }
        }

        val tz = TimeZone.getTimeZone(tzId)
        val tzOffset = tz.getOffset(ts) / 60000
        val localTs = ts + (tzOffset * 60000L)
        val rolloverAdjusted = localTs - (ruleStartMinute * 60000L)
        var dayKey = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(rolloverAdjusted))

        // BP 11: day_key is monotonic (never earlier than the previous message's day_key)
        dbHelper.readableDatabase.query("SELECT day_key FROM raw_message ORDER BY ts_utc DESC LIMIT 1").use {
            if (it.moveToFirst()) {
                val lastDayKey = it.getString(0)
                if (dayKey < lastDayKey) {
                    dayKey = lastDayKey
                }
            }
        }

        dbHelper.writableDatabase.execSQL(
            "INSERT INTO raw_message (id, device_id, ts_utc, tz_offset_min, day_key, role, content) VALUES (?, ?, ?, ?, ?, ?, ?)",
            arrayOf<Any>(id, deviceId, ts, tzOffset, dayKey, role, content)
        )
    }

    override suspend fun redactMessage(id: String) = withContext(ioDispatcher) {
        // Redaction triggers strictly require content=NULL and redacted_at=NOT NULL
        val db = dbHelper.writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("UPDATE raw_message SET content = NULL, redacted_at = ? WHERE id = ?", arrayOf<Any>(System.currentTimeMillis(), id))
            db.execSQL("INSERT INTO raw_event (op_id, target_id, op_type, target_type, ts_utc, device_id, actor) VALUES (?, ?, 'REDACT', 'message', ?, ?, 'user')",
                arrayOf<Any>(java.util.UUID.randomUUID().toString(), id, System.currentTimeMillis(), deviceId))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    override suspend fun getRawMessage(id: String): String? = withContext(ioDispatcher) {
        dbHelper.readableDatabase.query("SELECT content FROM raw_message WHERE id = ?", arrayOf(id)).use {
            if (it.moveToFirst()) if (it.isNull(0)) null else it.getString(0) else null
        }
    }

    override suspend fun getRecentMessages(limit: Int): List<ChatMessage> = withContext(ioDispatcher) {
        val list = mutableListOf<ChatMessage>()
        dbHelper.readableDatabase.query("SELECT id, role, content FROM raw_message ORDER BY ts_utc DESC LIMIT ?", arrayOf(limit)).use {
            while (it.moveToNext()) {
                val content = if (it.isNull(2)) null else it.getString(2)
                list.add(ChatMessage(it.getString(0), it.getString(1), content))
            }
        }
        list.reversed()
    }

    override suspend fun getSetting(key: String): String? = withContext(ioDispatcher) {
        dbHelper.readableDatabase.query("SELECT value FROM app_meta WHERE \"key\" = ?", arrayOf(key)).use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }

    override suspend fun saveSetting(key: String, value: String) = withContext(ioDispatcher) {
        dbHelper.writableDatabase.execSQL("INSERT OR REPLACE INTO app_meta (\"key\", value) VALUES (?, ?)", arrayOf(key, value))
    }

    override suspend fun getActiveNodes(): List<Node> = withContext(ioDispatcher) {
        val list = mutableListOf<Node>()
        dbHelper.readableDatabase.query("SELECT id, type, title, status, due_at, created_at, updated_at, closed_at FROM node WHERE status = 'active' OR status = 'in_progress' OR status = 'paused'").use {
            while (it.moveToNext()) {
                list.add(Node(it.getString(0), it.getString(1), it.getString(2), it.getString(3),
                    if (it.isNull(4)) null else it.getLong(4), it.getLong(5), it.getLong(6), if (it.isNull(7)) null else it.getLong(7)))
            }
        }
        list
    }

    override suspend fun getActiveReminders(): List<Reminder> = withContext(ioDispatcher) {
        val list = mutableListOf<Reminder>()
        dbHelper.readableDatabase.query("SELECT id, text, fire_at FROM reminder WHERE fire_at > ?", arrayOf(System.currentTimeMillis())).use {
            while (it.moveToNext()) { list.add(Reminder(it.getString(0), it.getString(1), it.getLong(2))) }
        }
        list
    }

    override suspend fun getPendingReminders(): List<Reminder> = withContext(ioDispatcher) {
        val list = mutableListOf<Reminder>()
        dbHelper.readableDatabase.query("SELECT id, text, fire_at FROM reminder").use {
            while (it.moveToNext()) list.add(Reminder(it.getString(0), it.getString(1), it.getLong(2)))
        }
        list
    }

    override suspend fun saveProposal(proposal: Proposal) = withContext(ioDispatcher) {
        dbHelper.writableDatabase.execSQL("INSERT OR REPLACE INTO proposal (id, kind, intent, options, status, created_at) VALUES (?, ?, ?, ?, ?, ?)",
            arrayOf<Any>(proposal.id, proposal.kind, proposal.intent.name, proposal.optionsIds.joinToString(","), proposal.status, System.currentTimeMillis()))
    }

    override suspend fun getProposal(id: String): Proposal? = withContext(ioDispatcher) {
        dbHelper.readableDatabase.query("SELECT kind, intent, options, status FROM proposal WHERE id = ?", arrayOf(id)).use {
            if (it.moveToFirst()) Proposal(id, it.getString(0), IntentType.valueOf(it.getString(1)), it.getString(2).split(","), it.getString(3)) else null
        }
    }

    override suspend fun consumeProposal(id: String) = withContext(ioDispatcher) {
        dbHelper.writableDatabase.execSQL("UPDATE proposal SET status = 'consumed' WHERE id = ?", arrayOf(id))
    }

    override suspend fun saveReminder(reminder: Reminder) = withContext(ioDispatcher) {
        dbHelper.writableDatabase.execSQL("INSERT OR REPLACE INTO reminder (id, text, fire_at) VALUES (?, ?, ?)", arrayOf<Any>(reminder.id, reminder.text, reminder.fireAtMillis))
    }

    override suspend fun deleteReminder(id: String) = withContext(ioDispatcher) {
        dbHelper.writableDatabase.execSQL("DELETE FROM reminder WHERE id = ?", arrayOf(id))
    }

    override suspend fun getDebugStats(): Map<String, String> = withContext(ioDispatcher) {
        val stats = mutableMapOf<String, String>()
        val db = dbHelper.readableDatabase
        val tables = listOf("raw_message", "raw_event", "node", "edge", "reminder")
        tables.forEach { table ->
            db.query("SELECT COUNT(*) FROM $table").use {
                if (it.moveToFirst()) stats["Rows in $table"] = it.getInt(0).toString()
            }
        }
        stats
    }

    override suspend fun getNode(id: String): Node? = withContext(ioDispatcher) {
        dbHelper.readableDatabase.query("SELECT type, title, status, due_at, created_at, updated_at, closed_at FROM node WHERE id = ?", arrayOf(id)).use {
            if (it.moveToFirst()) Node(id, it.getString(0), it.getString(1), it.getString(2),
                if (it.isNull(3)) null else it.getLong(3), it.getLong(4), it.getLong(5),
                if (it.isNull(6)) null else it.getLong(6)) else null
        }
    }

    override suspend fun getEvent(opId: String): RawEvent? = withContext(ioDispatcher) {
        dbHelper.readableDatabase.query("SELECT op_type, target_type, target_id, undo_of, before_json, after_json FROM raw_event WHERE op_id = ?", arrayOf(opId)).use {
            if (it.moveToFirst()) RawEvent(opId, it.getString(0), it.getString(1), it.getString(2),
                if(it.isNull(3)) null else it.getString(3),
                if(it.isNull(4)) null else it.getString(4),
                if(it.isNull(5)) null else it.getString(5)) else null
        }
    }

    override suspend fun hasUndo(token: String): Boolean = withContext(ioDispatcher) {
        dbHelper.readableDatabase.query("SELECT COUNT(*) FROM raw_event WHERE undo_of = ?", arrayOf(token)).use {
            if (it.moveToFirst()) it.getInt(0) > 0 else false
        }
    }

    override suspend fun createNodeAndCommit(node: Node, event: RawEvent, reminder: Reminder?) = withContext(ioDispatcher) {
        val db = dbHelper.writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("INSERT INTO node (id, device_id, type, title, status, due_at, origin, hlc, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, 'extracted', '', ?, ?)",
                arrayOf<Any?>(node.id, deviceId, node.type, node.title, node.status, node.dueAt, node.createdAt, node.updatedAt))
            reminder?.let { db.execSQL("INSERT INTO reminder (id, text, fire_at) VALUES (?, ?, ?)", arrayOf<Any>(it.id, it.text, it.fireAtMillis)) }
            db.execSQL("INSERT INTO raw_event (op_id, target_id, op_type, target_type, ts_utc, device_id, actor, before_json, after_json) VALUES (?, ?, ?, ?, ?, ?, 'chat_ai', ?, ?)",
                arrayOf<Any?>(event.opId, event.targetId, event.opType, event.targetType, System.currentTimeMillis(), deviceId, event.beforeJson, event.afterJson))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    override suspend fun updateNodeAndCommit(node: Node, event: RawEvent, deleteReminderId: String?) = withContext(ioDispatcher) {
        val db = dbHelper.writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("UPDATE node SET status = ?, closed_at = ?, updated_at = ? WHERE id = ?",
                arrayOf<Any?>(node.status, node.closedAt, node.updatedAt, node.id))
            deleteReminderId?.let { db.execSQL("DELETE FROM reminder WHERE id = ?", arrayOf(it)) }
            db.execSQL("INSERT INTO raw_event (op_id, target_id, op_type, target_type, ts_utc, device_id, actor, before_json, after_json) VALUES (?, ?, ?, ?, ?, ?, 'chat_ai', ?, ?)",
                arrayOf<Any?>(event.opId, event.targetId, event.opType, event.targetType, System.currentTimeMillis(), deviceId, event.beforeJson, event.afterJson))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    override suspend fun undoChangeAndCommit(node: Node, event: RawEvent, restoreReminder: Reminder?) = withContext(ioDispatcher) {
        val db = dbHelper.writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("UPDATE node SET status = ?, closed_at = ?, updated_at = ? WHERE id = ?",
                arrayOf<Any?>(node.status, node.closedAt, node.updatedAt, node.id))
            restoreReminder?.let { db.execSQL("INSERT OR REPLACE INTO reminder (id, text, fire_at) VALUES (?, ?, ?)", arrayOf<Any>(it.id, it.text, it.fireAtMillis)) }
            db.execSQL("INSERT INTO raw_event (op_id, target_id, op_type, target_type, ts_utc, device_id, actor, undo_of, before_json, after_json) VALUES (?, ?, ?, ?, ?, ?, 'user', ?, ?, ?)",
                arrayOf<Any?>(event.opId, event.targetId, event.opType, event.targetType, System.currentTimeMillis(), deviceId, event.undoOf, event.beforeJson, event.afterJson))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    override suspend fun createReminderAndCommit(reminder: Reminder, event: RawEvent) = withContext(ioDispatcher) {
        val db = dbHelper.writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("INSERT OR REPLACE INTO reminder (id, text, fire_at) VALUES (?, ?, ?)", arrayOf<Any>(reminder.id, reminder.text, reminder.fireAtMillis))
            db.execSQL("INSERT INTO raw_event (op_id, target_id, op_type, target_type, ts_utc, device_id, actor, before_json, after_json) VALUES (?, ?, ?, ?, ?, ?, 'chat_ai', ?, ?)",
                arrayOf<Any?>(event.opId, event.targetId, event.opType, event.targetType, System.currentTimeMillis(), deviceId, event.beforeJson, event.afterJson))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    override suspend fun deleteReminderAndCommit(reminderId: String, event: RawEvent) = withContext(ioDispatcher) {
        val db = dbHelper.writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM reminder WHERE id = ?", arrayOf(reminderId))
            db.execSQL("INSERT INTO raw_event (op_id, target_id, op_type, target_type, ts_utc, device_id, actor, undo_of, before_json, after_json) VALUES (?, ?, ?, ?, ?, ?, 'user', ?, ?, ?)",
                arrayOf<Any?>(event.opId, event.targetId, event.opType, event.targetType, System.currentTimeMillis(), deviceId, event.undoOf, event.beforeJson, event.afterJson))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    override suspend fun saveSoulFact(fact: SoulFact) = withContext(ioDispatcher) {
        dbHelper.writableDatabase.execSQL(
            "INSERT OR REPLACE INTO soul_fact (id, device_id, category, statement, confidence, source, origin, status, hlc, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, '', ?, ?)",
            arrayOf<Any>(fact.id, deviceId, fact.category, fact.statement, fact.confidence, fact.source, fact.origin, fact.status, fact.createdAt, fact.createdAt)
        )
    }

    override suspend fun searchMemoryFts(query: String, limit: Int): List<SearchResult> = withContext(ioDispatcher) {
        val results = mutableListOf<SearchResult>()
        if (query.isBlank()) return@withContext results
        
        // Strip SQL injection chars and format for FTS5 prefix match
        val sanitized = query.replace("\"", "").replace("'", "")
        val matchQuery = "\"$sanitized\"*"
        
        // FTS5 content_rowid exposes the internal hidden rowid. We must map it back to the ULID text ID.
        dbHelper.readableDatabase.query("SELECT id, type, title, body FROM node WHERE rowid IN (SELECT rowid FROM node_fts WHERE node_fts MATCH ? LIMIT ?)", arrayOf<Any>(matchQuery, limit)).use {
            while (it.moveToNext()) {
                results.add(SearchResult(it.getString(0), it.getString(1), it.getString(2), it.getString(3) ?: ""))
            }
        }
        dbHelper.readableDatabase.query("SELECT id, category, statement FROM soul_fact WHERE rowid IN (SELECT rowid FROM soul_fts WHERE soul_fts MATCH ? LIMIT ?)", arrayOf<Any>(matchQuery, limit)).use {
            while (it.moveToNext()) {
                results.add(SearchResult(it.getString(0), "soul_fact", it.getString(1), it.getString(2)))
            }
        }
        results.take(limit)
    }
}