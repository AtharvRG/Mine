// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.mine.storage

import androidx.sqlite.db.SupportSQLiteOpenHelper
import com.anchor.core.alarms.Reminder
import com.anchor.core.models.*
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
        // We removed the CREATE TABLE reminder here; it now lives safely in MineDatabaseHelper.
        // Device ID logic remains...
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
        val tzOffset = TimeZone.getDefault().getOffset(ts) / 60000
        val localTs = ts + (tzOffset * 60000L)
        val rolloverAdjusted = localTs - (4 * 3600 * 1000L) // 4 AM rollover
        val dayKey = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(rolloverAdjusted))

        dbHelper.writableDatabase.execSQL(
            "INSERT INTO raw_message (id, device_id, ts_utc, tz_offset_min, day_key, role, content) VALUES (?, ?, ?, ?, ?, ?, ?)",
            arrayOf<Any>(id, deviceId, ts, tzOffset, dayKey, role, content)
        )
    }

    override suspend fun getRawMessage(id: String): String? = withContext(ioDispatcher) {
        dbHelper.readableDatabase.query("SELECT content FROM raw_message WHERE id = ?", arrayOf(id)).use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }

    override suspend fun getRecentMessages(limit: Int): List<ChatMessage> = withContext(ioDispatcher) {
        val list = mutableListOf<ChatMessage>()
        // Batch 1: Query newest N, then reverse so they are chronological
        dbHelper.readableDatabase.query("SELECT id, role, content FROM raw_message ORDER BY ts_utc DESC LIMIT ?", arrayOf(limit)).use {
            while (it.moveToNext()) {
                list.add(ChatMessage(it.getString(0), it.getString(1), it.getString(2)))
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
        dbHelper.readableDatabase.query("SELECT id, type, title, status, due_at FROM node WHERE status = 'active'").use {
            while (it.moveToNext()) {
                list.add(Node(it.getString(0), it.getString(1), it.getString(2), it.getString(3), if (it.isNull(4)) null else it.getLong(4)))
            }
        }
        list
    }

    override suspend fun updateNodeStatus(nodeId: String, newStatus: String) = withContext(ioDispatcher) {
        dbHelper.writableDatabase.execSQL(
            "UPDATE node SET status = ?, updated_at = ? WHERE id = ?",
            arrayOf<Any?>(newStatus, System.currentTimeMillis(), nodeId)
        )
    }

    override suspend fun logEvent(event: RawEvent) = withContext(ioDispatcher) {
        dbHelper.writableDatabase.execSQL(
            "INSERT INTO raw_event (op_id, target_id, op_type, target_type, ts_utc, device_id, actor, undo_of) VALUES (?, ?, ?, ?, ?, ?, 'chat_ai', ?)",
            arrayOf<Any?>(event.opId, event.targetId, event.opType, event.targetType, System.currentTimeMillis(), deviceId, event.undoOf)
        )
    }

    // ITEM 11: One SQLite Transaction for Gateway
    override suspend fun commitChange(node: Node?, event: RawEvent, reminder: Reminder?) = withContext(ioDispatcher) {
        val db = dbHelper.writableDatabase
        db.beginTransaction()
        try {
            if (node != null) {
                db.execSQL("INSERT OR REPLACE INTO node (id, device_id, type, title, status, due_at, origin, hlc, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, 'extracted', '', ?, ?)",
                    arrayOf<Any?>(node.id, deviceId, node.type, node.title, node.status, node.dueAt, System.currentTimeMillis(), System.currentTimeMillis()))
            }
            if (reminder != null) {
                db.execSQL("INSERT OR REPLACE INTO reminder (id, text, fire_at) VALUES (?, ?, ?)",
                    arrayOf<Any>(reminder.id, reminder.text, reminder.fireAtMillis))
            }
            db.execSQL("INSERT INTO raw_event (op_id, target_id, op_type, target_type, ts_utc, device_id, actor, undo_of) VALUES (?, ?, ?, ?, ?, ?, 'chat_ai', ?)",
                arrayOf<Any?>(event.opId, event.targetId, event.opType, event.targetType, System.currentTimeMillis(), deviceId, event.undoOf))

            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
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

    override suspend fun saveProposal(proposal: Proposal) = withContext(ioDispatcher) {
        dbHelper.writableDatabase.execSQL("INSERT OR REPLACE INTO proposal (id, kind, intent, options) VALUES (?, ?, ?, ?)",
            arrayOf<Any?>(proposal.id, proposal.kind, proposal.intent.name, proposal.optionsIds.joinToString(",")))
    }

    override suspend fun saveReminder(reminder: Reminder) = withContext(ioDispatcher) {
        dbHelper.writableDatabase.execSQL("INSERT OR REPLACE INTO reminder (id, text, fire_at) VALUES (?, ?, ?)", arrayOf<Any>(reminder.id, reminder.text, reminder.fireAtMillis))
    }

    override suspend fun getActiveReminders(): List<Reminder> = withContext(ioDispatcher) {
        val list = mutableListOf<Reminder>()
        dbHelper.readableDatabase.query("SELECT id, text, fire_at FROM reminder WHERE fire_at > ?", arrayOf(System.currentTimeMillis())).use {
            while (it.moveToNext()) { list.add(Reminder(it.getString(0), it.getString(1), it.getLong(2))) }
        }
        list
    }

    override suspend fun deleteReminder(id: String) = withContext(ioDispatcher) {
        dbHelper.writableDatabase.execSQL("DELETE FROM reminder WHERE id = ?", arrayOf(id))
    }
}
