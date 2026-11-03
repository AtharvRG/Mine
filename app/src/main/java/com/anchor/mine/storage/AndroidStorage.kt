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
import kotlinx.serialization.encodeToString
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
        val db = dbHelper.writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("UPDATE raw_message SET content = NULL, redacted_at = ? WHERE id = ?", arrayOf<Any>(System.currentTimeMillis(), id))
            db.execSQL("INSERT INTO raw_event (op_id, target_id, op_type, target_type, ts_utc, device_id, actor) VALUES (?, ?, 'REDACT', 'message', ?, ?, 'user')",
                arrayOf<Any>(java.util.UUID.randomUUID().toString(), id, System.currentTimeMillis(), deviceId))
            
            // B17: Delete any derived msg_chunk containing this message.
            // The FTS5 AFTER DELETE trigger will automatically scrub the FTS index!
            db.execSQL("DELETE FROM msg_chunk WHERE msg_ids LIKE ?", arrayOf("%\"$id\"%"))
            
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
        dbHelper.readableDatabase.query("SELECT id, role, content, day_key, ts_utc FROM raw_message ORDER BY ts_utc DESC LIMIT ?", arrayOf(limit)).use {
            while (it.moveToNext()) {
                val content = if (it.isNull(2)) null else it.getString(2)
                val dayKey = if (it.isNull(3)) null else it.getString(3)
                val tsUtc = if (it.isNull(4)) 0L else it.getLong(4)
                list.add(ChatMessage(it.getString(0), it.getString(1), content, dayKey, tsUtc))
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

    override suspend fun getActiveNodes(limit: Int, cursor: String?, order: String): List<Node> = withContext(ioDispatcher) {
        val list = mutableListOf<Node>()
        val queryBuilder = StringBuilder()
        val args = mutableListOf<String>()

        queryBuilder.append("SELECT id, type, title, status, due_at, created_at, updated_at, closed_at, priority, severity, attrs FROM node WHERE (status = 'active' OR status = 'in_progress' OR status = 'paused')")

        if (!cursor.isNullOrBlank()) {
            val parts = cursor.split(":", limit = 2)
            val cursorDueAt = parts[0].toLongOrNull()
            val cursorId = parts.getOrElse(1) { "" }

            if (cursorDueAt != null) {
                queryBuilder.append(" AND ((due_at > CAST(? AS INTEGER)) OR (due_at = CAST(? AS INTEGER) AND id > ?) OR (due_at IS NULL))")
                args.add(cursorDueAt.toString())
                args.add(cursorDueAt.toString())
                args.add(cursorId)
            } else {
                queryBuilder.append(" AND (due_at IS NULL AND id > ?)")
                args.add(cursorId)
            }
        }

        queryBuilder.append(" ORDER BY due_at IS NULL, due_at ASC, id ASC LIMIT CAST(? AS INTEGER)")
        args.add(limit.toString())

        dbHelper.readableDatabase.query(queryBuilder.toString(), args.toTypedArray()).use {
            while (it.moveToNext()) {
                val id = it.getString(0)
                val type = it.getString(1)
                val title = it.getString(2)
                val status = it.getString(3)
                val dueAt = if (it.isNull(4)) null else it.getLong(4)
                val createdAt = it.getLong(5)
                val updatedAt = it.getLong(6)
                val closedAt = if (it.isNull(7)) null else it.getLong(7)
                val priority = if (it.isNull(8)) 0 else it.getInt(8)
                val severity = if (it.isNull(9)) 0 else it.getInt(9)
                val attrs = if (it.isNull(10)) null else it.getString(10)
                val pinned = attrs?.contains("\"pinned\":true") == true || attrs?.contains("\"pinned\": true") == true
                list.add(Node(id, type, title, status, dueAt, createdAt, updatedAt, closedAt, priority, severity, pinned))
            }
        }
        list
    }

    override suspend fun getActiveNodeCounts(now: Long, staleCutoffMs: Long): NodeCounts = withContext(ioDispatcher) {
        val cutoff = now - staleCutoffMs
        val sql = "SELECT COUNT(*), COUNT(CASE WHEN due_at IS NOT NULL AND due_at < CAST(? AS INTEGER) THEN 1 END) FROM node WHERE (status = 'active' OR status = 'in_progress' OR status = 'paused') AND (created_at = 0 OR (CASE WHEN updated_at > 0 THEN updated_at ELSE created_at END) >= CAST(? AS INTEGER))"
        dbHelper.readableDatabase.query(sql, arrayOf(now.toString(), cutoff.toString())).use {
            if (it.moveToFirst()) {
                NodeCounts(it.getInt(0), it.getInt(1))
            } else {
                NodeCounts(0, 0)
            }
        }
    }

    override suspend fun getStaleNodes(cutoffMs: Long, now: Long): List<Node> = withContext(ioDispatcher) {
        val list = mutableListOf<Node>()
        val cutoff = now - cutoffMs
        val sql = "SELECT id, type, title, status, due_at, created_at, updated_at, closed_at, priority, severity, attrs FROM node WHERE (status = 'active' OR status = 'in_progress' OR status = 'paused') AND (created_at > 0 OR updated_at > 0) AND (CASE WHEN updated_at > 0 THEN updated_at ELSE created_at END) < CAST(? AS INTEGER) ORDER BY created_at DESC"
        dbHelper.readableDatabase.query(sql, arrayOf(cutoff.toString())).use {
            while (it.moveToNext()) {
                val id = it.getString(0)
                val type = it.getString(1)
                val title = it.getString(2)
                val status = it.getString(3)
                val dueAt = if (it.isNull(4)) null else it.getLong(4)
                val createdAt = it.getLong(5)
                val updatedAt = it.getLong(6)
                val closedAt = if (it.isNull(7)) null else it.getLong(7)
                val priority = if (it.isNull(8)) 0 else it.getInt(8)
                val severity = if (it.isNull(9)) 0 else it.getInt(9)
                val attrs = if (it.isNull(10)) null else it.getString(10)
                val pinned = attrs?.contains("\"pinned\":true") == true || attrs?.contains("\"pinned\": true") == true
                list.add(Node(id, type, title, status, dueAt, createdAt, updatedAt, closedAt, priority, severity, pinned))
            }
        }
        list
    }

    override suspend fun getActiveReminders(): List<Reminder> = withContext(ioDispatcher) {
        val list = mutableListOf<Reminder>()
        dbHelper.readableDatabase.query("SELECT id, text, fire_at, node_id, tier FROM reminder WHERE fire_at > ?", arrayOf(System.currentTimeMillis())).use {
            while (it.moveToNext()) {
                val tierStr = if (it.isNull(4)) "alarm" else it.getString(4)
                val tier = if (tierStr.equals("gentle", ignoreCase = true)) AlarmTier.GENTLE else AlarmTier.ALARM
                list.add(Reminder(it.getString(0), it.getString(1), it.getLong(2), if(it.isNull(3)) null else it.getString(3), tier))
            }
        }
        list
    }

    override suspend fun getPendingReminders(): List<Reminder> = withContext(ioDispatcher) {
        val list = mutableListOf<Reminder>()
        dbHelper.readableDatabase.query("SELECT id, text, fire_at, node_id, tier FROM reminder").use {
            while (it.moveToNext()) {
                val tierStr = if (it.isNull(4)) "alarm" else it.getString(4)
                val tier = if (tierStr.equals("gentle", ignoreCase = true)) AlarmTier.GENTLE else AlarmTier.ALARM
                list.add(Reminder(it.getString(0), it.getString(1), it.getLong(2), if(it.isNull(3)) null else it.getString(3), tier))
            }
        }
        list
    }

    override suspend fun saveProposal(proposal: Proposal) = withContext(ioDispatcher) {
        val optsJson = kotlinx.serialization.json.Json.encodeToString(proposal.options)
        dbHelper.writableDatabase.execSQL("INSERT OR REPLACE INTO proposal (id, kind, intent, options, status, created_at, payload) VALUES (?, ?, ?, ?, ?, ?, ?)",
            arrayOf<Any?>(proposal.id, proposal.kind, proposal.intent.name, optsJson, proposal.status, System.currentTimeMillis(), proposal.payload))
    }

    override suspend fun getProposal(id: String): Proposal? = withContext(ioDispatcher) {
        val expiryThreshold = System.currentTimeMillis() - (24 * 3600 * 1000L)
        // B5: Enforce 24-hour expiry directly at the read level. If it's old, it doesn't exist to the Gateway.
        dbHelper.readableDatabase.query("SELECT kind, intent, options, status, payload FROM proposal WHERE id = ? AND created_at > ?", arrayOf<Any>(id, expiryThreshold)).use {
            if (it.moveToFirst()) {
                val opts = try {
                    kotlinx.serialization.json.Json.decodeFromString<List<ProposalOption>>(it.getString(2))
                } catch (e: Exception) {
                    it.getString(2).split(",").map { idStr -> ProposalOption(idStr, "Unknown") }
                }
                val payload = if (it.isNull(4)) null else it.getString(4)
                Proposal(id, it.getString(0), IntentType.valueOf(it.getString(1)), opts, it.getString(3), payload)
            } else null
        }
    }

    override suspend fun consumeProposal(id: String) = withContext(ioDispatcher) {
        dbHelper.writableDatabase.execSQL("UPDATE proposal SET status = 'consumed' WHERE id = ?", arrayOf(id))
    }

    override suspend fun getOpenProposals(): List<Proposal> = withContext(ioDispatcher) {
        val list = mutableListOf<Proposal>()
        val expiryThreshold = System.currentTimeMillis() - (24 * 3600 * 1000L)
        dbHelper.readableDatabase.query("SELECT id, kind, intent, options, status, payload FROM proposal WHERE status = 'open' AND created_at > ?", arrayOf(expiryThreshold)).use {
            while (it.moveToNext()) {
                val opts = try {
                    kotlinx.serialization.json.Json.decodeFromString<List<ProposalOption>>(it.getString(3))
                } catch (e: Exception) {
                    it.getString(3).split(",").map { id -> ProposalOption(id, "Unknown") }
                }
                val payload = if (it.isNull(5)) null else it.getString(5)
                list.add(Proposal(it.getString(0), it.getString(1), IntentType.valueOf(it.getString(2)), opts, it.getString(4), payload))
            }
        }
        list
    }

    override suspend fun getUrgentNodes(): List<Node> = withContext(ioDispatcher) {
        val list = mutableListOf<Node>()
        // ORDER BY due_at IS NULL ensures nulls drop to the bottom in standard SQLite
        dbHelper.readableDatabase.query("SELECT id, type, title, status, due_at, created_at, updated_at, closed_at FROM node WHERE status IN ('active', 'in_progress') ORDER BY due_at IS NULL, due_at ASC LIMIT 3").use {
            while (it.moveToNext()) {
                list.add(Node(it.getString(0), it.getString(1), it.getString(2), it.getString(3), 
                    if (it.isNull(4)) null else it.getLong(4), it.getLong(5), it.getLong(6), if (it.isNull(7)) null else it.getLong(7)))
            }
        }
        list
    }

    override suspend fun getActiveSoulFacts(): List<SoulFact> = withContext(ioDispatcher) {
        val list = mutableListOf<SoulFact>()
        dbHelper.readableDatabase.query("SELECT id, category, statement, confidence, source, origin, status, created_at FROM soul_fact WHERE status = 'active' OR status = 'pinned'").use {
            while (it.moveToNext()) {
                list.add(SoulFact(it.getString(0), it.getString(1), it.getString(2), it.getDouble(3), it.getString(4), it.getString(5), it.getString(6), it.getLong(7)))
            }
        }
        list
    }

    override suspend fun getProposedSoulFacts(): List<SoulFact> = withContext(ioDispatcher) {
        val list = mutableListOf<SoulFact>()
        dbHelper.readableDatabase.query("SELECT id, category, statement, confidence, source, origin, status, created_at FROM soul_fact WHERE status = 'proposed'").use {
            while (it.moveToNext()) {
                list.add(SoulFact(it.getString(0), it.getString(1), it.getString(2), it.getDouble(3), it.getString(4), it.getString(5), it.getString(6), it.getLong(7)))
            }
        }
        list
    }

    override suspend fun updateSoulFactStatus(id: String, status: String) = withContext(ioDispatcher) {
        dbHelper.writableDatabase.execSQL("UPDATE soul_fact SET status = ? WHERE id = ?", arrayOf(status, id))
    }

    override suspend fun saveReminder(reminder: Reminder) = withContext(ioDispatcher) {
        dbHelper.writableDatabase.execSQL("INSERT OR REPLACE INTO reminder (id, node_id, text, fire_at, tier) VALUES (?, ?, ?, ?, ?)", arrayOf<Any?>(reminder.id, reminder.nodeId, reminder.text, reminder.fireAtMillis, reminder.tier.name.lowercase()))
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
        // B16: Query ts_utc to enforce the 24-hour undo window
        dbHelper.readableDatabase.query("SELECT op_type, target_type, target_id, undo_of, before_json, after_json, ts_utc FROM raw_event WHERE op_id = ?", arrayOf(opId)).use {
            if (it.moveToFirst()) RawEvent(opId, it.getString(0), it.getString(1), it.getString(2), 
                if(it.isNull(3)) null else it.getString(3), 
                if(it.isNull(4)) null else it.getString(4), 
                if(it.isNull(5)) null else it.getString(5),
                it.getLong(6)) else null
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
            reminder?.let { db.execSQL("INSERT OR REPLACE INTO reminder (id, node_id, text, fire_at, tier) VALUES (?, ?, ?, ?, ?)", arrayOf<Any?>(it.id, it.nodeId, it.text, it.fireAtMillis, it.tier.name.lowercase())) }
            db.execSQL("INSERT INTO raw_event (op_id, target_id, op_type, target_type, ts_utc, device_id, actor, before_json, after_json) VALUES (?, ?, ?, ?, ?, ?, 'chat_ai', ?, ?)",
                arrayOf<Any?>(event.opId, event.targetId, event.opType, event.targetType, System.currentTimeMillis(), deviceId, event.beforeJson, event.afterJson))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    override suspend fun updateNodeAndCommit(node: Node, event: RawEvent, deleteReminderId: String?, upsertReminder: Reminder?, consumeProposalId: String?) = withContext(ioDispatcher) {
        val db = dbHelper.writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("UPDATE node SET status = ?, due_at = ?, closed_at = ?, updated_at = ? WHERE id = ?",
                arrayOf<Any?>(node.status, node.dueAt, node.closedAt, node.updatedAt, node.id))

            deleteReminderId?.let { db.execSQL("DELETE FROM reminder WHERE id = ?", arrayOf(it)) }

            upsertReminder?.let {
                db.execSQL("INSERT OR REPLACE INTO reminder (id, node_id, text, fire_at, tier) VALUES (?, ?, ?, ?, ?)", arrayOf<Any?>(it.id, it.nodeId, it.text, it.fireAtMillis, it.tier.name.lowercase()))
            }

            // B5: Atomic Proposal Consumption
            consumeProposalId?.let {
                db.execSQL("UPDATE proposal SET status = 'consumed' WHERE id = ?", arrayOf(it))
            }

            db.execSQL("INSERT INTO raw_event (op_id, target_id, op_type, target_type, ts_utc, device_id, actor, before_json, after_json) VALUES (?, ?, ?, ?, ?, ?, 'chat_ai', ?, ?)",
                arrayOf<Any?>(event.opId, event.targetId, event.opType, event.targetType, System.currentTimeMillis(), deviceId, event.beforeJson, event.afterJson))

            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    override suspend fun undoChangeAndCommit(node: Node, event: RawEvent, restoreReminder: Reminder?) = withContext(ioDispatcher) {
        val db = dbHelper.writableDatabase
        db.beginTransaction()
        try {
            // FIX: Restore due_at so rescheduled items roll back accurately
            db.execSQL("UPDATE node SET status = ?, due_at = ?, closed_at = ?, updated_at = ? WHERE id = ?",
                arrayOf<Any?>(node.status, node.dueAt, node.closedAt, node.updatedAt, node.id))
            restoreReminder?.let { db.execSQL("INSERT OR REPLACE INTO reminder (id, node_id, text, fire_at, tier) VALUES (?, ?, ?, ?, ?)", arrayOf<Any?>(it.id, it.nodeId, it.text, it.fireAtMillis, it.tier.name.lowercase())) }
            db.execSQL("INSERT INTO raw_event (op_id, target_id, op_type, target_type, ts_utc, device_id, actor, undo_of, before_json, after_json) VALUES (?, ?, ?, ?, ?, ?, 'user', ?, ?, ?)",
                arrayOf<Any?>(event.opId, event.targetId, event.opType, event.targetType, System.currentTimeMillis(), deviceId, event.undoOf, event.beforeJson, event.afterJson))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    override suspend fun createReminderAndCommit(reminder: Reminder, event: RawEvent) = withContext(ioDispatcher) {
        val db = dbHelper.writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("INSERT OR REPLACE INTO reminder (id, node_id, text, fire_at, tier) VALUES (?, ?, ?, ?, ?)", arrayOf<Any?>(reminder.id, reminder.nodeId, reminder.text, reminder.fireAtMillis, reminder.tier.name.lowercase()))
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
        // Obsolete stub, use createSoulFactAndCommit
    }

    override suspend fun createSoulFactAndCommit(fact: SoulFact, event: RawEvent) = withContext(ioDispatcher) {
        val db = dbHelper.writableDatabase
        db.beginTransaction()
        try {
            db.execSQL(
                "INSERT OR REPLACE INTO soul_fact (id, device_id, category, statement, confidence, source, origin, status, hlc, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, '', ?, ?)",
                arrayOf<Any>(fact.id, deviceId, fact.category, fact.statement, fact.confidence, fact.source, fact.origin, fact.status, fact.createdAt, fact.createdAt)
            )
            db.execSQL("INSERT INTO raw_event (op_id, target_id, op_type, target_type, ts_utc, device_id, actor, before_json, after_json) VALUES (?, ?, ?, ?, ?, ?, 'chat_ai', ?, ?)",
                arrayOf<Any?>(event.opId, event.targetId, event.opType, event.targetType, System.currentTimeMillis(), deviceId, event.beforeJson, event.afterJson))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    override suspend fun searchMemoryFts(query: String, limit: Int): List<SearchResult> = withContext(ioDispatcher) {
        val results = mutableListOf<SearchResult>()
        if (query.isBlank()) return@withContext results
        
        try {
            val sanitized = query.replace(Regex("[^\\p{L}\\p{M}\\p{N}\\s]"), "").trim()
            val tokens = sanitized.split(Regex("\\s+")).filter { it.isNotBlank() }
            if (tokens.isEmpty()) return@withContext results
            val matchQuery = tokens.joinToString(" AND ") { "\"$it\"*" }
            
            val safeLimit = minOf(limit, 20)
            
            // B1: Strict status filtering applied natively to FTS joins
            dbHelper.readableDatabase.query("SELECT id, type, title, status, due_at FROM node WHERE rowid IN (SELECT rowid FROM node_fts WHERE node_fts MATCH ? ORDER BY rank) AND status != 'archived' LIMIT ?", arrayOf<Any>(matchQuery, safeLimit)).use {
                while (it.moveToNext()) {
                    val due = if (it.isNull(4)) "" else "Due: ${it.getLong(4)}"
                    results.add(SearchResult(it.getString(0), it.getString(1), it.getString(2), "Status: ${it.getString(3)} $due".trim()))
                }
            }
            dbHelper.readableDatabase.query("SELECT id, category, statement FROM soul_fact WHERE rowid IN (SELECT rowid FROM soul_fts WHERE soul_fts MATCH ? ORDER BY rank) AND status IN ('active', 'pinned') LIMIT ?", arrayOf<Any>(matchQuery, safeLimit)).use {
                while (it.moveToNext()) {
                    results.add(SearchResult(it.getString(0), "soul_fact", it.getString(1), it.getString(2)))
                }
            }
        } catch (_: Exception) {}
        return@withContext results.take(limit)
    }

    override suspend fun getProactiveState(): ProactiveState = withContext(ioDispatcher) {
        dbHelper.readableDatabase.query("SELECT open_thread_msg_id, thread_opened_at, backoff_step, last_sent_at, last_contact_at, paused_until, silent_days, sent_today, fuse_tripped_at, day_key FROM proactive_state WHERE id = 1").use {
            if (it.moveToFirst()) {
                ProactiveState(
                    openThreadMsgId = if (it.isNull(0)) null else it.getString(0),
                    threadOpenedAt = if (it.isNull(1)) null else it.getLong(1),
                    backoffStep = it.getInt(2),
                    lastSentAt = if (it.isNull(3)) null else it.getLong(3),
                    lastContactAt = if (it.isNull(4)) null else it.getLong(4),
                    pausedUntil = if (it.isNull(5)) null else it.getLong(5),
                    silentDays = it.getInt(6),
                    sentToday = it.getInt(7),
                    fuseTrippedAt = if (it.isNull(8)) null else it.getLong(8),
                    dayKey = if (it.isNull(9)) null else it.getString(9)
                )
            } else ProactiveState()
        }
    }

    override suspend fun saveProactiveState(state: ProactiveState) = withContext(ioDispatcher) {
        dbHelper.writableDatabase.execSQL(
            "INSERT OR REPLACE INTO proactive_state (id, open_thread_msg_id, thread_opened_at, backoff_step, last_sent_at, last_contact_at, paused_until, silent_days, sent_today, fuse_tripped_at, day_key) VALUES (1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            arrayOf<Any?>(state.openThreadMsgId, state.threadOpenedAt, state.backoffStep, state.lastSentAt, state.lastContactAt, state.pausedUntil, state.silentDays, state.sentToday, state.fuseTrippedAt, state.dayKey)
        )
    }

    override suspend fun getReliabilityState(): ReliabilityState = withContext(ioDispatcher) {
        dbHelper.readableDatabase.query("SELECT last_check_at, score, sweeper_heartbeat, p95_latency_ms FROM reliability_state WHERE id = 1").use {
            if (it.moveToFirst()) {
                ReliabilityState(
                    lastCheckAt = if (it.isNull(0)) null else it.getLong(0),
                    score = if (it.isNull(1)) null else it.getString(1),
                    sweeperHeartbeat = if (it.isNull(2)) null else it.getLong(2),
                    p95LatencyMs = if (it.isNull(3)) null else it.getInt(3)
                )
            } else ReliabilityState()
        }
    }

    override suspend fun saveReliabilityState(state: ReliabilityState) = withContext(ioDispatcher) {
        dbHelper.writableDatabase.execSQL(
            "INSERT OR REPLACE INTO reliability_state (id, last_check_at, score, sweeper_heartbeat, p95_latency_ms) VALUES (1, ?, ?, ?, ?)",
            arrayOf<Any?>(state.lastCheckAt, state.score, state.sweeperHeartbeat, state.p95LatencyMs)
        )
    }

    override suspend fun recordReminderFire(fire: ReminderFire) = withContext(ioDispatcher) {
        dbHelper.writableDatabase.execSQL(
            "INSERT INTO reminder_fire (id, reminder_id, fired_at, scheduled_for, latency_ms, outcome) VALUES (?, ?, ?, ?, ?, ?)",
            arrayOf<Any?>(fire.id, fire.reminderId, fire.firedAt, fire.scheduledFor, fire.latencyMs, fire.outcome)
        )
    }

    override suspend fun getRegisteredAlarms(): List<Pair<String, Long>> = withContext(ioDispatcher) {
        val list = mutableListOf<Pair<String, Long>>()
        dbHelper.readableDatabase.query("SELECT reminder_id, registered_for FROM alarm_registry").use {
            while (it.moveToNext()) {
                list.add(it.getString(0) to it.getLong(1))
            }
        }
        list
    }

    override suspend fun saveRegisteredAlarm(reminderId: String, requestCode: Int, registeredFor: Long) = withContext(ioDispatcher) {
        dbHelper.writableDatabase.execSQL(
            "INSERT OR REPLACE INTO alarm_registry (reminder_id, request_code, api, registered_for, registered_at) VALUES (?, ?, 'alarm_clock', ?, ?)",
            arrayOf<Any>(reminderId, requestCode, registeredFor, System.currentTimeMillis())
        )
    }

    override suspend fun deleteRegisteredAlarm(reminderId: String) = withContext(ioDispatcher) {
        dbHelper.writableDatabase.execSQL("DELETE FROM alarm_registry WHERE reminder_id = ?", arrayOf(reminderId))
    }

    override suspend fun getReminderByNodeId(nodeId: String): Reminder? = withContext(ioDispatcher) {
        dbHelper.readableDatabase.query("SELECT id, text, fire_at, node_id, tier FROM reminder WHERE node_id = ?", arrayOf(nodeId)).use {
            if (it.moveToFirst()) {
                val tierStr = if (it.isNull(4)) "alarm" else it.getString(4)
                val tier = if (tierStr.equals("gentle", ignoreCase = true)) AlarmTier.GENTLE else AlarmTier.ALARM
                Reminder(it.getString(0), it.getString(1), it.getLong(2), it.getString(3), tier)
            } else null
        }
    }
    
    override suspend fun clearAlarmRegistry() = withContext(ioDispatcher) {
        dbHelper.writableDatabase.execSQL("DELETE FROM alarm_registry")
    }

    override suspend fun hasReminderFire(reminderId: String, outcome: String): Boolean = withContext(ioDispatcher) {
        dbHelper.readableDatabase.query("SELECT 1 FROM reminder_fire WHERE reminder_id = ? AND outcome = ?", arrayOf(reminderId, outcome)).use {
            it.moveToFirst()
        }
    }

    override suspend fun saveEdge(edge: Edge) = withContext(ioDispatcher) {
        dbHelper.writableDatabase.execSQL(
            """
            INSERT OR REPLACE INTO edge (
                id, device_id, src_id, dst_id, dst_kind, type, weight, source, origin, source_msg_ids, attrs, hlc, created_at, updated_at, deleted_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf<Any?>(
                edge.id,
                deviceId,
                edge.srcId,
                edge.dstId,
                edge.dstKind,
                edge.type,
                edge.weight,
                edge.source,
                edge.origin,
                edge.sourceMsgIds,
                edge.attrs,
                edge.hlc,
                edge.createdAt,
                edge.updatedAt,
                edge.deletedAt
            )
        )
    }

    override suspend fun getEdge(id: String): Edge? = withContext(ioDispatcher) {
        dbHelper.readableDatabase.query(
            "SELECT id, src_id, dst_id, dst_kind, type, weight, source, origin, source_msg_ids, attrs, hlc, created_at, updated_at, deleted_at FROM edge WHERE id = ? AND deleted_at IS NULL",
            arrayOf(id)
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                cursorToEdge(cursor)
            } else null
        }
    }

    override suspend fun getEdgesForSource(srcId: String, type: String?): List<Edge> = withContext(ioDispatcher) {
        val list = mutableListOf<Edge>()
        val sql = if (type == null) {
            "SELECT id, src_id, dst_id, dst_kind, type, weight, source, origin, source_msg_ids, attrs, hlc, created_at, updated_at, deleted_at FROM edge WHERE src_id = ? AND deleted_at IS NULL ORDER BY created_at ASC"
        } else {
            "SELECT id, src_id, dst_id, dst_kind, type, weight, source, origin, source_msg_ids, attrs, hlc, created_at, updated_at, deleted_at FROM edge WHERE src_id = ? AND type = ? AND deleted_at IS NULL ORDER BY created_at ASC"
        }
        val args = if (type == null) arrayOf(srcId) else arrayOf(srcId, type)
        dbHelper.readableDatabase.query(sql, args).use { cursor ->
            while (cursor.moveToNext()) {
                list.add(cursorToEdge(cursor))
            }
        }
        list
    }

    override suspend fun getEdgesForDestination(dstId: String, type: String?): List<Edge> = withContext(ioDispatcher) {
        val list = mutableListOf<Edge>()
        val sql = if (type == null) {
            "SELECT id, src_id, dst_id, dst_kind, type, weight, source, origin, source_msg_ids, attrs, hlc, created_at, updated_at, deleted_at FROM edge WHERE dst_id = ? AND deleted_at IS NULL ORDER BY created_at ASC"
        } else {
            "SELECT id, src_id, dst_id, dst_kind, type, weight, source, origin, source_msg_ids, attrs, hlc, created_at, updated_at, deleted_at FROM edge WHERE dst_id = ? AND type = ? AND deleted_at IS NULL ORDER BY created_at ASC"
        }
        val args = if (type == null) arrayOf(dstId) else arrayOf(dstId, type)
        dbHelper.readableDatabase.query(sql, args).use { cursor ->
            while (cursor.moveToNext()) {
                list.add(cursorToEdge(cursor))
            }
        }
        list
    }

    override suspend fun deleteEdge(id: String) = withContext(ioDispatcher) {
        val now = System.currentTimeMillis()
        dbHelper.writableDatabase.execSQL(
            "UPDATE edge SET deleted_at = ?, updated_at = ? WHERE id = ? AND deleted_at IS NULL",
            arrayOf<Any?>(now, now, id)
        )
    }

    override suspend fun createEdgeAndCommit(edge: Edge, event: RawEvent) = withContext(ioDispatcher) {
        val db = dbHelper.writableDatabase
        db.beginTransaction()
        try {
            db.execSQL(
                """
                INSERT OR REPLACE INTO edge (
                    id, device_id, src_id, dst_id, dst_kind, type, weight, source, origin, source_msg_ids, attrs, hlc, created_at, updated_at, deleted_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any?>(
                    edge.id,
                    deviceId,
                    edge.srcId,
                    edge.dstId,
                    edge.dstKind,
                    edge.type,
                    edge.weight,
                    edge.source,
                    edge.origin,
                    edge.sourceMsgIds,
                    edge.attrs,
                    edge.hlc,
                    edge.createdAt,
                    edge.updatedAt,
                    edge.deletedAt
                )
            )
            db.execSQL(
                "INSERT INTO raw_event (op_id, target_id, op_type, target_type, ts_utc, device_id, actor, before_json, after_json) VALUES (?, ?, ?, ?, ?, ?, 'chat_ai', ?, ?)",
                arrayOf<Any?>(event.opId, event.targetId, event.opType, event.targetType, System.currentTimeMillis(), deviceId, event.beforeJson, event.afterJson)
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    override suspend fun deleteEdgeAndCommit(edgeId: String, event: RawEvent) = withContext(ioDispatcher) {
        val db = dbHelper.writableDatabase
        val now = System.currentTimeMillis()
        db.beginTransaction()
        try {
            db.execSQL(
                "UPDATE edge SET deleted_at = ?, updated_at = ? WHERE id = ? AND deleted_at IS NULL",
                arrayOf<Any?>(now, now, edgeId)
            )
            db.execSQL(
                "INSERT INTO raw_event (op_id, target_id, op_type, target_type, ts_utc, device_id, actor, undo_of, before_json, after_json) VALUES (?, ?, ?, ?, ?, ?, 'user', ?, ?, ?)",
                arrayOf<Any?>(event.opId, event.targetId, event.opType, event.targetType, now, deviceId, event.undoOf, event.beforeJson, event.afterJson)
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun cursorToEdge(cursor: android.database.Cursor): Edge {
        return Edge(
            id = cursor.getString(0),
            srcId = cursor.getString(1),
            dstId = cursor.getString(2),
            dstKind = if (cursor.isNull(3)) "node" else cursor.getString(3),
            type = if (cursor.isNull(4)) EdgeTypes.FOLLOW_UP else cursor.getString(4),
            weight = if (cursor.isNull(5)) 1.0 else cursor.getDouble(5),
            source = if (cursor.isNull(6)) "user" else cursor.getString(6),
            origin = if (cursor.isNull(7)) "manual" else cursor.getString(7),
            sourceMsgIds = if (cursor.isNull(8)) null else cursor.getString(8),
            attrs = if (cursor.isNull(9)) null else cursor.getString(9),
            hlc = if (cursor.isNull(10)) null else cursor.getString(10),
            createdAt = if (cursor.isNull(11)) 0L else cursor.getLong(11),
            updatedAt = if (cursor.isNull(12)) 0L else cursor.getLong(12),
            deletedAt = if (cursor.isNull(13)) null else cursor.getLong(13)
        )
    }

    override suspend fun getDaysWithMessages(startDayKey: String, endDayKey: String): Set<String> = withContext(ioDispatcher) {
        val days = mutableSetOf<String>()
        dbHelper.readableDatabase.query(
            "SELECT DISTINCT day_key FROM raw_message WHERE day_key >= ? AND day_key <= ? AND content IS NOT NULL ORDER BY day_key ASC",
            arrayOf(startDayKey, endDayKey)
        ).use {
            while (it.moveToNext()) {
                if (!it.isNull(0)) {
                    days.add(it.getString(0))
                }
            }
        }
        days
    }

    override suspend fun getHeatMapCounts(startDayKey: String, endDayKey: String): Map<String, Int> = withContext(ioDispatcher) {
        val map = mutableMapOf<String, Int>()
        dbHelper.readableDatabase.query(
            "SELECT day_key, COUNT(*) FROM raw_message WHERE day_key >= ? AND day_key <= ? GROUP BY day_key",
            arrayOf(startDayKey, endDayKey)
        ).use {
            while (it.moveToNext()) {
                if (!it.isNull(0)) {
                    map[it.getString(0)] = it.getInt(1)
                }
            }
        }
        map
    }

    override suspend fun getMessagesForDay(dayKey: String): List<ChatMessage> = withContext(ioDispatcher) {
        val list = mutableListOf<ChatMessage>()
        dbHelper.readableDatabase.query(
            "SELECT id, role, content, day_key, ts_utc FROM raw_message WHERE day_key = ? ORDER BY ts_utc ASC",
            arrayOf(dayKey)
        ).use {
            while (it.moveToNext()) {
                val id = it.getString(0)
                val role = it.getString(1)
                val content = if (it.isNull(2)) null else it.getString(2)
                val dKey = if (it.isNull(3)) null else it.getString(3)
                val ts = if (it.isNull(4)) 0L else it.getLong(4)
                list.add(ChatMessage(id, role, content, dKey, ts))
            }
        }
        list
    }

    override suspend fun getRemindersBetween(startMs: Long, endMs: Long): List<Reminder> = withContext(ioDispatcher) {
        val list = mutableListOf<Reminder>()
        dbHelper.readableDatabase.query(
            "SELECT id, text, fire_at, node_id, tier FROM reminder WHERE fire_at >= ? AND fire_at <= ? ORDER BY fire_at ASC",
            arrayOf(startMs, endMs)
        ).use {
            while (it.moveToNext()) {
                val tierStr = if (it.isNull(4)) "alarm" else it.getString(4)
                val tier = if (tierStr.equals("gentle", ignoreCase = true)) AlarmTier.GENTLE else AlarmTier.ALARM
                list.add(Reminder(it.getString(0), it.getString(1), it.getLong(2), if (it.isNull(3)) null else it.getString(3), tier))
            }
        }
        list
    }

    override suspend fun getRemindersForDay(startOfDayMs: Long, endOfDayMs: Long): List<Reminder> =
        getRemindersBetween(startOfDayMs, endOfDayMs)

    override suspend fun getQuoteSlot(slot: QuoteSlot, dayKey: String): Quote? = withContext(ioDispatcher) {
        dbHelper.readableDatabase.query(
            "SELECT slot, day_key, text, generated_at, state_hash, source, regen_count FROM quote_slot WHERE slot = ? AND day_key = ?",
            arrayOf(slot.name, dayKey)
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                val s = QuoteSlot.valueOf(cursor.getString(0))
                val dk = cursor.getString(1)
                val text = cursor.getString(2)
                val genAt = cursor.getLong(3)
                val stateHash = if (cursor.isNull(4)) null else cursor.getString(4)
                val srcStr = cursor.getString(5)
                val src = if (srcStr.equals("live", ignoreCase = true)) QuoteSource.LIVE else QuoteSource.PRESET
                val regenCount = cursor.getInt(6)
                Quote(s, dk, text, genAt, stateHash, src, regenCount)
            } else null
        }
    }

    override suspend fun saveQuoteSlot(quote: Quote) = withContext(ioDispatcher) {
        dbHelper.writableDatabase.execSQL(
            """
            INSERT OR REPLACE INTO quote_slot (
                slot, day_key, text, generated_at, state_hash, source, regen_count
            ) VALUES (?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf<Any?>(
                quote.slot.name,
                quote.dayKey,
                quote.text,
                quote.generatedAt,
                quote.stateHash,
                quote.source.name.lowercase(Locale.US),
                quote.regenCount
            )
        )
    }
}