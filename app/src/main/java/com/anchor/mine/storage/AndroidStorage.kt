// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.mine.storage

import androidx.sqlite.db.SupportSQLiteOpenHelper
import com.anchor.core.models.*
import com.anchor.core.models.Reminder
import com.anchor.core.storage.ProposalAlreadyResolvedException
import com.anchor.core.storage.Storage
import com.anchor.core.time.DayRollover
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

class AndroidStorage(@Volatile private var dbHelper: SupportSQLiteOpenHelper) : Storage {
    override val ioDispatcher: CoroutineDispatcher = Dispatchers.IO

    private suspend fun <T> withIoDb(block: suspend CoroutineScope.() -> T): T = withContext(ioDispatcher) {
        DbGate.withReadLock {
            block()
        }
    }

    fun rebind(newHelper: SupportSQLiteOpenHelper) {
        this.dbHelper = newHelper
        this.cachedDeviceId = null
    }

    @Volatile
    private var cachedDeviceId: String? = null

    private fun getOrInitDeviceId(): String {
        cachedDeviceId?.let { return it }
        synchronized(this) {
            cachedDeviceId?.let { return it }
            var id = ""
            try {
                DbGate.withReadLockSync {
                    dbHelper.readableDatabase.query("SELECT value FROM app_meta WHERE \"key\" = 'device_id'").use {
                        if (it.moveToFirst()) id = it.getString(0)
                    }
                    if (id.isEmpty()) {
                        id = java.util.UUID.randomUUID().toString()
                        dbHelper.writableDatabase.execSQL(
                            "INSERT INTO app_meta (\"key\", value) VALUES ('device_id', ?)", arrayOf(id)
                        )
                    }
                }
            } catch (_: Exception) {
                id = java.util.UUID.randomUUID().toString()
            }
            cachedDeviceId = id
            return id
        }
    }

    private val deviceId: String
        get() = getOrInitDeviceId()

    override suspend fun insertRawMessage(id: String, role: String, content: String) =
        insertRawMessage(id, role, content, null, null)

    override suspend fun insertRawMessage(
        id: String,
        role: String,
        content: String,
        causeMsgId: String?,
        dayKey: String?
    ) = insertRawInternal(id, role, content, causeMsgId, dayKey, null)

    /** Reply quotes and proactive kind ride in the existing `attrs` column (no schema change). */
    override suspend fun insertRawMessageWithAttrs(id: String, role: String, content: String, causeMsgId: String?, attrsJson: String?) =
        insertRawInternal(id, role, content, causeMsgId, null, attrsJson?.take(2_000))

    private suspend fun insertRawInternal(
        id: String,
        role: String,
        content: String,
        causeMsgId: String?,
        dayKey: String?,
        attrs: String?
    ) = withIoDb {
        val ts = System.currentTimeMillis()
        val rule = getEffectiveDayRule(ts)
        val tz = DayRollover.safeTimeZone(rule.tzId)
        val tzOffset = tz.getOffset(ts) / 60000

        var resolvedDayKey = dayKey
        val inheritedFromCause = (resolvedDayKey == null && causeMsgId != null)
        if (inheritedFromCause) {
            dbHelper.readableDatabase.query("SELECT day_key FROM raw_message WHERE id = ? LIMIT 1", arrayOf(causeMsgId)).use {
                if (it.moveToFirst() && !it.isNull(0)) {
                    resolvedDayKey = it.getString(0)
                }
            }
        }

        if (resolvedDayKey == null) {
            resolvedDayKey = DayRollover.computeDayKey(ts, rule)
        }

        // BP 11 & BP 50 §3.13: day_key is strictly monotonic when auto-computed (clock moving backward clamps to lastDayKey).
        // Do NOT overwrite if explicitly passed or inherited from cause message.
        if (dayKey == null && !inheritedFromCause) {
            dbHelper.readableDatabase.query("SELECT day_key FROM raw_message ORDER BY ts_utc DESC, rowid DESC LIMIT 1", emptyArray<Any?>()).use {
                if (it.moveToFirst() && !it.isNull(0)) {
                    val lastDayKey = it.getString(0)
                    if (resolvedDayKey < lastDayKey) {
                        resolvedDayKey = lastDayKey
                    }
                }
            }
        }

        val db = dbHelper.writableDatabase
        db.beginTransaction()
        try {
            db.execSQL(
                "INSERT INTO raw_message (id, device_id, ts_utc, tz_offset_min, day_key, role, content, client_msg_id, attrs) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf<Any?>(id, deviceId, ts, tzOffset, resolvedDayKey, role, content, causeMsgId, attrs)
            )
            if (content.isNotBlank() && role != "system") {
                db.execSQL(
                    "INSERT INTO msg_chunk (id, day_key, ts_start, ts_end, msg_ids, text, extractor_version) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    arrayOf<Any>("chunk_$id", resolvedDayKey!!, ts, ts, "[\"$id\"]", content, "v1")
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    override suspend fun redactMessage(id: String) = withIoDb {
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

    override suspend fun getRawMessage(id: String): String? = withIoDb {
        dbHelper.readableDatabase.query("SELECT content FROM raw_message WHERE id = ?", arrayOf(id)).use {
            if (it.moveToFirst()) if (it.isNull(0)) null else it.getString(0) else null
        }
    }

    override suspend fun hasRawMessage(id: String): Boolean = withIoDb {
        dbHelper.readableDatabase.query("SELECT 1 FROM raw_message WHERE id = ?", arrayOf(id)).use {
            it.moveToFirst()
        }
    }

    override suspend fun getRecentMessages(limit: Int): List<ChatMessage> = withIoDb {
        val list = mutableListOf<ChatMessage>()
        // rowid breaks a ts_utc tie: two captures stored in the same millisecond would otherwise
        // come back in an arbitrary order, and QuickCapture.pending() reads this order as the
        // timeline it answers captures against.
        dbHelper.readableDatabase.query("SELECT id, role, content, day_key, ts_utc, attrs FROM raw_message ORDER BY ts_utc DESC, rowid DESC LIMIT ?", arrayOf(limit)).use {
            while (it.moveToNext()) {
                val content = if (it.isNull(2)) null else it.getString(2)
                val dayKey = if (it.isNull(3)) null else it.getString(3)
                val tsUtc = if (it.isNull(4)) 0L else it.getLong(4)
                val attrs = if (it.isNull(5)) null else it.getString(5)
                list.add(ChatMessage(it.getString(0), it.getString(1), content, dayKey, tsUtc, attrs))
            }
        }
        list.reversed()
    }

    override suspend fun getSetting(key: String): String? = withIoDb {
        dbHelper.readableDatabase.query("SELECT value FROM app_meta WHERE \"key\" = ?", arrayOf(key)).use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }

    override suspend fun saveSetting(key: String, value: String) = withIoDb {
        dbHelper.writableDatabase.execSQL("INSERT OR REPLACE INTO app_meta (\"key\", value) VALUES (?, ?)", arrayOf(key, value))
    }

    override suspend fun saveSettings(values: Map<String, String>) = withIoDb {
        val db = dbHelper.writableDatabase
        db.beginTransaction()
        try {
            values.forEach { (key, value) ->
                db.execSQL("INSERT OR REPLACE INTO app_meta (\"key\", value) VALUES (?, ?)", arrayOf(key, value))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    override suspend fun getEffectiveDayRule(tsUtc: Long): DayRule = withIoDb {
        dbHelper.readableDatabase.query(
            "SELECT id, effective_from, start_minute, end_minute, tz_id, created_at FROM day_rule WHERE effective_from <= ? ORDER BY effective_from DESC LIMIT 1",
            arrayOf<Any>(tsUtc)
        ).use {
            if (it.moveToFirst()) {
                DayRule(
                    id = it.getString(0),
                    effectiveFrom = it.getLong(1),
                    startMinute = it.getInt(2),
                    endMinute = it.getInt(3),
                    tzId = it.getString(4),
                    createdAt = it.getLong(5)
                )
            } else {
                DayRollover.defaultRule(tsUtc)
            }
        }
    }

    override suspend fun saveDayRule(rule: DayRule) = withIoDb {
        dbHelper.writableDatabase.execSQL(
            "INSERT OR REPLACE INTO day_rule (id, effective_from, start_minute, end_minute, tz_id, created_at) VALUES (?, ?, ?, ?, ?, ?)",
            arrayOf<Any>(rule.id, rule.effectiveFrom, rule.startMinute, rule.endMinute, rule.tzId, rule.createdAt)
        )
    }

    private companion object {
        const val NODE_COLS = "id, type, title, status, due_at, created_at, updated_at, closed_at, priority, severity, attrs, local_only, recurrence_rule, streak_current, streak_best, last_completed_at, origin, original_due_at, due_history_json, slip_count, context_trigger, fallback_fire_at"
        const val REMINDER_COLS = "id, text, fire_at, node_id, tier, rrule, iteration"
        const val OUTBOX_COLUMNS =
            "id, job_type, priority, payload_json, idem_key, status, attempts, next_try_at, deadline_at, " +
                "created_at, updated_at, last_error"
    }

    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    private fun mergePinnedAttr(existingAttrs: String?, pinned: Boolean): String? {
        if (existingAttrs.isNullOrBlank()) {
            return if (pinned) """{"pinned":true}""" else null
        }
        return try {
            val root = json.parseToJsonElement(existingAttrs).let { (it as? kotlinx.serialization.json.JsonObject)?.toMutableMap() ?: mutableMapOf() }
            if (pinned) {
                root["pinned"] = kotlinx.serialization.json.JsonPrimitive(true)
            } else {
                root.remove("pinned")
            }
            if (root.isEmpty()) null else kotlinx.serialization.json.JsonObject(root).toString()
        } catch (_: Exception) {
            if (pinned) """{"pinned":true}""" else null
        }
    }

    private fun parseReminder(cursor: android.database.Cursor): Reminder {
        val tierStr = if (cursor.isNull(4)) "alarm" else cursor.getString(4)
        val tier = if (tierStr.equals("gentle", ignoreCase = true)) AlarmTier.GENTLE else AlarmTier.ALARM
        val rrule = if (cursor.columnCount > 5 && !cursor.isNull(5)) cursor.getString(5) else null
        val iteration = if (cursor.columnCount > 6 && !cursor.isNull(6)) cursor.getInt(6) else 0
        return Reminder(
            id = cursor.getString(0),
            text = cursor.getString(1),
            fireAtMillis = cursor.getLong(2),
            nodeId = if (cursor.isNull(3)) null else cursor.getString(3),
            tier = tier,
            rrule = rrule,
            iteration = iteration
        )
    }

    private fun queryNodes(sql: String, args: Array<String>): List<Node> {
        val list = mutableListOf<Node>()
        dbHelper.readableDatabase.query(sql, args).use {
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
                val localOnly = if (it.columnCount > 11 && !it.isNull(11)) it.getInt(11) == 1 else false
                val recurrenceRule = if (it.columnCount > 12 && !it.isNull(12)) it.getString(12) else null
                val streakCurrent = if (it.columnCount > 13 && !it.isNull(13)) it.getInt(13) else 0
                val streakBest = if (it.columnCount > 14 && !it.isNull(14)) it.getInt(14) else 0
                val lastCompletedAt = if (it.columnCount > 15 && !it.isNull(15)) it.getLong(15) else null
                val origin = if (it.columnCount > 16 && !it.isNull(16)) it.getString(16) else "user_edit"
                val originalDueAt = if (it.columnCount > 17 && !it.isNull(17)) it.getLong(17) else null
                val dueHistoryJson = if (it.columnCount > 18 && !it.isNull(18)) it.getString(18) else null
                val slipCount = if (it.columnCount > 19 && !it.isNull(19)) it.getInt(19) else 0
                val contextTrigger = if (it.columnCount > 20 && !it.isNull(20)) it.getString(20) else null
                val fallbackFireAt = if (it.columnCount > 21 && !it.isNull(21)) it.getLong(21) else null
                list.add(Node(
                    id = id,
                    type = type,
                    title = title,
                    status = status,
                    dueAt = dueAt,
                    createdAt = createdAt,
                    updatedAt = updatedAt,
                    closedAt = closedAt,
                    priority = priority,
                    severity = severity,
                    pinned = pinned,
                    localOnly = localOnly,
                    origin = origin,
                    recurrenceRule = recurrenceRule,
                    streakCurrent = streakCurrent,
                    streakBest = streakBest,
                    lastCompletedAt = lastCompletedAt,
                    originalDueAt = originalDueAt,
                    dueHistoryJson = dueHistoryJson,
                    slipCount = slipCount,
                    contextTrigger = contextTrigger,
                    fallbackFireAt = fallbackFireAt
                ))
            }
        }
        return list
    }

    override suspend fun getActiveNodes(limit: Int, cursor: String?): List<Node> = withIoDb {
        val queryBuilder = StringBuilder()
        val args = mutableListOf<String>()

        queryBuilder.append("SELECT $NODE_COLS FROM node WHERE (status = 'active' OR status = 'in_progress' OR status = 'paused')")

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

        queryNodes(queryBuilder.toString(), args.toTypedArray())
    }

    override suspend fun findActiveNodeByTitle(title: String): Node? = withIoDb {
        findActiveNodesByTitle(title).firstOrNull()
    }

    override suspend fun findActiveNodesByTitle(title: String): List<Node> = withIoDb {
        val trimmed = title.trim()
        val sql = "SELECT $NODE_COLS FROM node WHERE (status = 'active' OR status = 'in_progress' OR status = 'paused') AND TRIM(title) = ? COLLATE NOCASE ORDER BY due_at IS NULL, due_at ASC, id ASC"
        queryNodes(sql, arrayOf(trimmed))
    }

    override suspend fun getNodesDueBetween(startMs: Long, endMs: Long, limit: Int, includeLocalOnly: Boolean): List<Node> = withIoDb {
        val localFilter = if (includeLocalOnly) "" else "AND (local_only IS NULL OR local_only = 0) "
        val sql = "SELECT $NODE_COLS FROM node WHERE (status = 'active' OR status = 'in_progress' OR status = 'paused' OR status = 'done') $localFilter AND due_at IS NOT NULL AND due_at >= CAST(? AS INTEGER) AND due_at < CAST(? AS INTEGER) ORDER BY due_at ASC, id ASC LIMIT CAST(? AS INTEGER)"
        queryNodes(sql, arrayOf(startMs.toString(), endMs.toString(), limit.toString()))
    }

    override suspend fun getOverdueNodes(beforeMs: Long, limit: Int): List<Node> = withIoDb {
        val sql = "SELECT $NODE_COLS FROM node WHERE (status = 'active' OR status = 'in_progress' OR status = 'paused') AND (local_only IS NULL OR local_only = 0) AND due_at IS NOT NULL AND due_at < CAST(? AS INTEGER) ORDER BY due_at ASC, id ASC LIMIT CAST(? AS INTEGER)"
        queryNodes(sql, arrayOf(beforeMs.toString(), limit.toString()))
    }

    override suspend fun getRankedCandidates(now: Long, staleCutoffMs: Long, perTierLimit: Int): List<Node> = withIoDb {
        val cutoff = now - staleCutoffMs
        val nonStaleCond = "((due_at IS NOT NULL AND due_at > CAST(? AS INTEGER)) OR created_at = 0 OR (CASE WHEN updated_at > created_at THEN updated_at ELSE created_at END) >= CAST(? AS INTEGER))"
        val activeCond = "(status = 'active' OR status = 'in_progress' OR status = 'paused') AND (local_only IS NULL OR local_only = 0) AND $nonStaleCond"
        val cols = NODE_COLS

        // Tier 1: Overdue (< now)
        val t1Sql = "SELECT $cols FROM node WHERE $activeCond AND due_at IS NOT NULL AND due_at < CAST(? AS INTEGER) ORDER BY due_at ASC, id ASC LIMIT CAST(? AS INTEGER)"
        val tier1 = queryNodes(t1Sql, arrayOf(now.toString(), cutoff.toString(), now.toString(), perTierLimit.toString()))

        // Tier 2: In-progress
        val t2Sql = "SELECT $cols FROM node WHERE (status = 'in_progress') AND (local_only IS NULL OR local_only = 0) AND $nonStaleCond ORDER BY (CASE WHEN updated_at > created_at THEN updated_at ELSE created_at END) DESC, id ASC LIMIT CAST(? AS INTEGER)"
        val tier2 = queryNodes(t2Sql, arrayOf(now.toString(), cutoff.toString(), perTierLimit.toString()))

        // Tier 3: Due within 48h
        val horizon = now + (48L * 3600 * 1000L)
        val t3Sql = "SELECT $cols FROM node WHERE $activeCond AND due_at IS NOT NULL AND due_at >= CAST(? AS INTEGER) AND due_at <= CAST(? AS INTEGER) ORDER BY due_at ASC, id ASC LIMIT CAST(? AS INTEGER)"
        val tier3 = queryNodes(t3Sql, arrayOf(now.toString(), cutoff.toString(), now.toString(), horizon.toString(), perTierLimit.toString()))

        // Tier 4: Pinned
        val t4Sql = "SELECT $cols FROM node WHERE $activeCond AND (attrs LIKE '%\"pinned\":true%' OR attrs LIKE '%\"pinned\": true%') ORDER BY (CASE WHEN updated_at > created_at THEN updated_at ELSE created_at END) DESC, id ASC LIMIT CAST(? AS INTEGER)"
        val tier4 = queryNodes(t4Sql, arrayOf(now.toString(), cutoff.toString(), perTierLimit.toString()))

        // Tier 5: Severity > 0
        val t5Sql = "SELECT $cols FROM node WHERE $activeCond AND severity > 0 ORDER BY severity DESC, (CASE WHEN updated_at > created_at THEN updated_at ELSE created_at END) DESC, id ASC LIMIT CAST(? AS INTEGER)"
        val tier5 = queryNodes(t5Sql, arrayOf(now.toString(), cutoff.toString(), perTierLimit.toString()))

        // Tier 6: Recency
        val t6Sql = "SELECT $cols FROM node WHERE $activeCond ORDER BY (CASE WHEN updated_at > created_at THEN updated_at ELSE created_at END) DESC, id ASC LIMIT CAST(? AS INTEGER)"
        val tier6 = queryNodes(t6Sql, arrayOf(now.toString(), cutoff.toString(), perTierLimit.toString()))

        val union = LinkedHashMap<String, Node>()
        for (node in tier1) union.putIfAbsent(node.id, node)
        for (node in tier2) union.putIfAbsent(node.id, node)
        for (node in tier3) union.putIfAbsent(node.id, node)
        for (node in tier4) union.putIfAbsent(node.id, node)
        for (node in tier5) union.putIfAbsent(node.id, node)
        for (node in tier6) union.putIfAbsent(node.id, node)
        union.values.toList()
    }

    override suspend fun getActiveNodeCounts(now: Long, staleCutoffMs: Long, includeLocalOnly: Boolean): NodeCounts = withIoDb {
        val cutoff = now - staleCutoffMs
        val localFilter = if (includeLocalOnly) "" else "AND (local_only IS NULL OR local_only = 0) "
        val sql = "SELECT COUNT(*), COUNT(CASE WHEN due_at IS NOT NULL AND due_at < CAST(? AS INTEGER) THEN 1 END) FROM node WHERE (status = 'active' OR status = 'in_progress' OR status = 'paused') $localFilter AND ((due_at IS NOT NULL AND due_at > CAST(? AS INTEGER)) OR created_at = 0 OR (CASE WHEN updated_at > created_at THEN updated_at ELSE created_at END) >= CAST(? AS INTEGER))"
        dbHelper.readableDatabase.query(sql, arrayOf(now.toString(), now.toString(), cutoff.toString())).use {
            if (it.moveToFirst()) {
                NodeCounts(it.getInt(0), it.getInt(1))
            } else {
                NodeCounts(0, 0)
            }
        }
    }

    override suspend fun getStaleNodes(now: Long, cutoffMs: Long, includeLocalOnly: Boolean): List<Node> = withIoDb {
        val cutoff = now - cutoffMs
        val localFilter = if (includeLocalOnly) "" else "AND (local_only IS NULL OR local_only = 0) "
        val sql = "SELECT $NODE_COLS FROM node WHERE (status = 'active' OR status = 'in_progress' OR status = 'paused') $localFilter AND (created_at > 0 OR updated_at > 0) AND (CASE WHEN updated_at > created_at THEN updated_at ELSE created_at END) < CAST(? AS INTEGER) AND (due_at IS NULL OR due_at <= CAST(? AS INTEGER)) ORDER BY created_at DESC"
        queryNodes(sql, arrayOf(cutoff.toString(), now.toString()))
    }

    override suspend fun getTrashNodes(now: Long, limit: Int): List<Node> = withIoDb {
        val thirtyDaysAgo = now - (30L * 24 * 3600 * 1000L)
        val sql = "SELECT $NODE_COLS FROM node " +
            "WHERE status = 'cancelled' AND ((closed_at IS NOT NULL AND closed_at >= ?) OR (updated_at IS NOT NULL AND updated_at >= ?)) " +
            "ORDER BY (CASE WHEN closed_at IS NOT NULL THEN closed_at ELSE updated_at END) DESC LIMIT ?"
        queryNodes(sql, arrayOf(thirtyDaysAgo.toString(), thirtyDaysAgo.toString(), limit.toString()))
    }

    override suspend fun eraseNode(nodeId: String) = withIoDb {
        val db = dbHelper.writableDatabase
        db.beginTransaction()
        try {
            db.execSQL(
                "UPDATE raw_event SET before_json = NULL, after_json = NULL WHERE target_id = ? OR target_id IN (SELECT id FROM reminder WHERE node_id = ?)",
                arrayOf<Any>(nodeId, nodeId)
            )
            db.execSQL(
                "DELETE FROM alarm_registry WHERE reminder_id IN (SELECT id FROM reminder WHERE node_id = ?)",
                arrayOf<Any>(nodeId)
            )
            db.execSQL("DELETE FROM reminder WHERE node_id = ?", arrayOf<Any>(nodeId))
            db.execSQL("DELETE FROM edge WHERE src_id = ? OR dst_id = ?", arrayOf<Any>(nodeId, nodeId))
            db.execSQL("DELETE FROM note_reference WHERE node_id = ?", arrayOf<Any>(nodeId))
            db.execSQL("DELETE FROM node WHERE id = ?", arrayOf<Any>(nodeId))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    override suspend fun eraseSoulFact(factId: String) = withIoDb {
        val db = dbHelper.writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM soul_fact WHERE id = ?", arrayOf<Any>(factId))
            db.execSQL("UPDATE raw_event SET before_json = NULL, after_json = NULL WHERE target_id = ?", arrayOf<Any>(factId))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    override suspend fun isSoulFactSuppressed(statement: String): Boolean = withIoDb {
        dbHelper.readableDatabase.query(
            "SELECT 1 FROM soul_fact WHERE LOWER(TRIM(statement)) = LOWER(TRIM(?)) AND status = 'rejected' LIMIT 1",
            arrayOf(statement)
        ).use { it.moveToFirst() }
    }

    // R-25: the caller's clock decides what is "active"; reading the wall clock here made the
    // device disagree with every other clock-aware path (and made the query untestable).
    // R-26: bounded and in fire order, so the Home list shows the soonest reminders first and the
    // read cost is capped. The bound is far above any list a person reads; it exists so the query
    // terminates on a pathological history, not to shape the product.
    override suspend fun getActiveReminders(now: Long, limit: Int): List<Reminder> = withIoDb {
        val list = mutableListOf<Reminder>()
        dbHelper.readableDatabase.query("SELECT $REMINDER_COLS FROM reminder WHERE fire_at > ? ORDER BY fire_at ASC LIMIT ?", arrayOf(now.toString(), limit.toString())).use {
            while (it.moveToNext()) {
                list.add(parseReminder(it))
            }
        }
        list
    }

    // R-26: ORDER BY fire_at walks idx_reminder_fire instead of sorting the whole table, and the
    // LIMIT means the cost of a sweep no longer grows with reminder history.
    override suspend fun getPendingReminders(limit: Int): List<Reminder> = withIoDb {
        val list = mutableListOf<Reminder>()
        dbHelper.readableDatabase.query("SELECT $REMINDER_COLS FROM reminder ORDER BY fire_at ASC LIMIT ?", arrayOf(limit.toString())).use {
            while (it.moveToNext()) {
                list.add(parseReminder(it))
            }
        }
        list
    }

    override suspend fun saveProposal(proposal: Proposal) = withIoDb {
        val optsJson = kotlinx.serialization.json.Json.encodeToString(proposal.options)
        dbHelper.writableDatabase.execSQL("INSERT OR REPLACE INTO proposal (id, kind, intent, options, status, created_at, payload) VALUES (?, ?, ?, ?, ?, ?, ?)",
            arrayOf<Any?>(proposal.id, proposal.kind, proposal.intent.name, optsJson, proposal.status, System.currentTimeMillis(), proposal.payload))
    }

    override suspend fun getProposal(id: String): Proposal? = withIoDb {
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
                Proposal(id, it.getString(0), parseIntentOrCreate(it.getString(1)), opts, it.getString(3), payload)
            } else null
        }
    }

    override suspend fun consumeProposal(id: String) = withIoDb {
        dbHelper.writableDatabase.execSQL("UPDATE proposal SET status = 'consumed' WHERE id = ?", arrayOf(id))
    }

    override suspend fun updateProposalStatus(id: String, status: String) = withIoDb {
        dbHelper.writableDatabase.execSQL("UPDATE proposal SET status = ? WHERE id = ?", arrayOf(status, id))
    }

    override suspend fun reopenProposal(id: String): Boolean = withIoDb {
        val stmt = dbHelper.writableDatabase.compileStatement("UPDATE proposal SET status = 'open' WHERE id = ? AND status = 'consumed'")
        try {
            stmt.bindString(1, id)
            stmt.executeUpdateDelete() > 0
        } finally {
            stmt.close()
        }
    }

    private fun consumeProposalInTx(db: androidx.sqlite.db.SupportSQLiteDatabase, proposalId: String) {
        val stmt = db.compileStatement("UPDATE proposal SET status = 'consumed' WHERE id = ? AND status = 'open'")
        val affected = try {
            stmt.bindString(1, proposalId)
            stmt.executeUpdateDelete()
        } finally {
            stmt.close()
        }
        if (affected == 0) {
            throw ProposalAlreadyResolvedException("Proposal already resolved")
        }
    }

    override suspend fun consumeProposalCas(id: String): Boolean = withIoDb {
        val stmt = dbHelper.writableDatabase.compileStatement("UPDATE proposal SET status = 'consumed' WHERE id = ? AND status = 'open'")
        try {
            stmt.bindString(1, id)
            stmt.executeUpdateDelete() > 0
        } finally {
            stmt.close()
        }
    }

    override suspend fun getOpenProposals(): List<Proposal> = withIoDb {
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
                list.add(Proposal(it.getString(0), it.getString(1), parseIntentOrCreate(it.getString(2)), opts, it.getString(4), payload))
            }
        }
        list
    }

    override suspend fun getUrgentNodes(includeLocalOnly: Boolean): List<Node> = withIoDb {
        val localFilter = if (includeLocalOnly) "" else "AND (local_only IS NULL OR local_only = 0) "
        // ORDER BY due_at IS NULL ensures nulls drop to the bottom in standard SQLite
        queryNodes("SELECT $NODE_COLS FROM node WHERE status IN ('active', 'in_progress') $localFilter ORDER BY due_at IS NULL, due_at ASC LIMIT 3", emptyArray())
    }

    // P1-D 20 (D-121): Home queries. Ranking itself is code-side via rankHomeNodes (D-121).
    override suspend fun getHomePriorityList(now: Long, limit: Int): List<Node> = withIoDb {
        val cutoff = now - 30L * 24 * 3600 * 1000L
        val sql = "SELECT $NODE_COLS FROM node WHERE (status = 'active' OR status = 'in_progress' OR status = 'paused') AND (local_only IS NULL OR local_only = 0) AND ((due_at IS NOT NULL AND due_at > CAST(? AS INTEGER)) OR created_at = 0 OR (CASE WHEN updated_at > created_at THEN updated_at ELSE created_at END) >= CAST(? AS INTEGER)) LIMIT 50"
        val fresh = queryNodes(sql, arrayOf(now.toString(), cutoff.toString()))
        // Cap the candidate pool before code-side ranking (parity with SharedFakeStorage).
        com.anchor.core.storage.rankHomeNodes(fresh, now).take(limit)
    }

    override suspend fun getRecentlyClosed(now: Long, limit: Int): List<Node> = withIoDb {
        val cutoff = now - 24L * 3600 * 1000L
        val sql = "SELECT $NODE_COLS FROM node WHERE (status = 'done' OR status = 'cancelled') AND closed_at IS NOT NULL AND closed_at >= CAST(? AS INTEGER) AND closed_at <= CAST(? AS INTEGER) ORDER BY closed_at DESC, id ASC LIMIT CAST(? AS INTEGER)"
        queryNodes(sql, arrayOf(cutoff.toString(), now.toString(), limit.toString()))
    }

    override suspend fun getLatestEventForTarget(targetType: String, targetId: String): RawEvent? = withIoDb {
        dbHelper.readableDatabase.query(
            "SELECT op_id, op_type, target_type, target_id, undo_of, before_json, after_json, ts_utc, cause_msg_id FROM raw_event WHERE target_type = ? AND target_id = ? ORDER BY ts_utc DESC, rowid DESC LIMIT 1",
            arrayOf(targetType, targetId)
        ).use {
            if (it.moveToFirst()) RawEvent(
                opId = it.getString(0),
                opType = it.getString(1),
                targetType = it.getString(2),
                targetId = it.getString(3),
                undoOf = if (it.isNull(4)) null else it.getString(4),
                beforeJson = if (it.isNull(5)) null else it.getString(5),
                afterJson = if (it.isNull(6)) null else it.getString(6),
                tsUtc = it.getLong(7),
                causeMsgId = if (it.isNull(8)) null else it.getString(8)
            ) else null
        }
    }

    override suspend fun getActiveSoulFacts(includeLocalOnly: Boolean): List<SoulFact> = withIoDb {
        val list = mutableListOf<SoulFact>()
        val sql = if (includeLocalOnly) {
            "SELECT id, category, statement, confidence, source, origin, status, created_at, local_only, sensitive FROM soul_fact WHERE (status = 'active' OR status = 'pinned')"
        } else {
            "SELECT id, category, statement, confidence, source, origin, status, created_at, local_only, sensitive FROM soul_fact WHERE (status = 'active' OR status = 'pinned') AND (local_only IS NULL OR local_only = 0)"
        }
        dbHelper.readableDatabase.query(sql).use {
            while (it.moveToNext()) {
                val localOnly = it.getInt(8) == 1
                val sensitive = it.getInt(9) == 1
                list.add(SoulFact(it.getString(0), it.getString(1), it.getString(2), it.getDouble(3), it.getString(4), it.getString(5), it.getString(6), it.getLong(7), localOnly = localOnly, sensitive = sensitive))
            }
        }
        list
    }

    override suspend fun getProposedSoulFacts(): List<SoulFact> = withIoDb {
        val list = mutableListOf<SoulFact>()
        dbHelper.readableDatabase.query("SELECT id, category, statement, confidence, source, origin, status, created_at, local_only, sensitive FROM soul_fact WHERE status = 'proposed'").use {
            while (it.moveToNext()) {
                val localOnly = it.getInt(8) == 1
                val sensitive = it.getInt(9) == 1
                list.add(SoulFact(it.getString(0), it.getString(1), it.getString(2), it.getDouble(3), it.getString(4), it.getString(5), it.getString(6), it.getLong(7), localOnly = localOnly, sensitive = sensitive))
            }
        }
        list
    }

    override suspend fun updateSoulFactStatus(id: String, status: String) = withIoDb {
        dbHelper.writableDatabase.execSQL("UPDATE soul_fact SET status = ? WHERE id = ?", arrayOf(status, id))
    }

    override suspend fun updateSoulFactStatusAndCommit(id: String, status: String, event: RawEvent) = withIoDb {
        val db = dbHelper.writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("UPDATE soul_fact SET status = ? WHERE id = ?", arrayOf(status, id))
            db.execSQL("INSERT INTO raw_event (op_id, target_id, op_type, target_type, ts_utc, device_id, actor, undo_of, before_json, after_json, cause_msg_id) VALUES (?, ?, ?, ?, ?, ?, 'user', ?, ?, ?, ?)",
                arrayOf<Any?>(event.opId, event.targetId, event.opType, event.targetType, event.tsUtc, deviceId, event.undoOf, event.beforeJson, event.afterJson, event.causeMsgId))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    override suspend fun saveReminder(reminder: Reminder) = withIoDb {
        dbHelper.writableDatabase.execSQL("INSERT OR REPLACE INTO reminder (id, node_id, text, fire_at, tier, rrule, iteration) VALUES (?, ?, ?, ?, ?, ?, ?)", arrayOf<Any?>(reminder.id, reminder.nodeId, reminder.text, reminder.fireAtMillis, reminder.tier.name.lowercase(), reminder.rrule, reminder.iteration))
    }

    override suspend fun deleteReminder(id: String) = withIoDb {
        dbHelper.writableDatabase.execSQL("DELETE FROM reminder WHERE id = ?", arrayOf(id))
    }

    override suspend fun getDebugStats(): Map<String, String> = withIoDb {
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

    override suspend fun getNode(id: String): Node? = withIoDb {
        queryNodes("SELECT $NODE_COLS FROM node WHERE id = ?", arrayOf(id)).firstOrNull()
    }

    override suspend fun getEvent(opId: String): RawEvent? = withIoDb {
        // B16: Query ts_utc to enforce the 24-hour undo window
        dbHelper.readableDatabase.query("SELECT op_type, target_type, target_id, undo_of, before_json, after_json, ts_utc, cause_msg_id FROM raw_event WHERE op_id = ?", arrayOf(opId)).use {
            if (it.moveToFirst()) RawEvent(
                opId,
                it.getString(0),
                it.getString(1),
                it.getString(2),
                if(it.isNull(3)) null else it.getString(3),
                if(it.isNull(4)) null else it.getString(4),
                if(it.isNull(5)) null else it.getString(5),
                it.getLong(6),
                if(it.isNull(7)) null else it.getString(7)
            ) else null
        }
    }

    override suspend fun hasUndo(token: String): Boolean = withIoDb {
        dbHelper.readableDatabase.query("SELECT COUNT(*) FROM raw_event WHERE undo_of = ?", arrayOf(token)).use {
            if (it.moveToFirst()) it.getInt(0) > 0 else false
        }
    }

    override suspend fun getUndoneOpIds(sinceUtc: Long): Set<String> = withIoDb {
        val result = mutableSetOf<String>()
        val cursor = if (sinceUtc > 0L) {
            dbHelper.readableDatabase.query(
                "SELECT undo_of FROM raw_event WHERE undo_of IS NOT NULL AND ts_utc >= ?",
                arrayOf(sinceUtc)
            )
        } else {
            dbHelper.readableDatabase.query(
                "SELECT undo_of FROM raw_event WHERE undo_of IS NOT NULL"
            )
        }
        cursor.use { c ->
            while (c.moveToNext()) {
                val token = c.getString(0)
                if (!token.isNullOrBlank()) result.add(token)
            }
        }
        result
    }

    override suspend fun getRecentEvents(limit: Int): List<RawEvent> = withIoDb {
        val list = mutableListOf<RawEvent>()
        val sql = "SELECT op_id, op_type, target_type, target_id, undo_of, before_json, after_json, ts_utc, cause_msg_id FROM raw_event WHERE undo_of IS NULL AND op_type IN ('CREATE', 'CANCEL', 'COMPLETE', 'RESCHEDULE', 'UPDATE', 'SCHEDULE', 'KEEP_STALE', 'CANCEL_STALE', 'DELETE', 'CONFIRM', 'ACCEPT', 'REJECT', 'LINK', 'UNLINK', 'UPDATE_STATUS', 'ARCHIVE_NODE', 'DELETE_NODE', 'RESTORE_NODE', 'SCHEDULE_WAKEUP', 'UPSERT_TOPIC_NOTE', 'LINK_NOTE_REFERENCE') ORDER BY ts_utc DESC, rowid DESC LIMIT ?"
        dbHelper.readableDatabase.query(sql, arrayOf(limit)).use { cursor ->
            while (cursor.moveToNext()) {
                list.add(
                    RawEvent(
                        opId = cursor.getString(0),
                        opType = cursor.getString(1),
                        targetType = cursor.getString(2),
                        targetId = cursor.getString(3),
                        undoOf = if (cursor.isNull(4)) null else cursor.getString(4),
                        beforeJson = if (cursor.isNull(5)) null else cursor.getString(5),
                        afterJson = if (cursor.isNull(6)) null else cursor.getString(6),
                        tsUtc = cursor.getLong(7),
                        causeMsgId = if (cursor.isNull(8)) null else cursor.getString(8)
                    )
                )
            }
        }
        list.reversed()
    }

    override suspend fun insertRawEvent(event: RawEvent) = withIoDb {
        val db = dbHelper.writableDatabase
        db.beginTransaction()
        try {
            db.execSQL(
                "INSERT INTO raw_event (op_id, target_id, op_type, target_type, ts_utc, device_id, actor, undo_of, before_json, after_json, cause_msg_id) VALUES (?, ?, ?, ?, ?, ?, 'chat_ai', ?, ?, ?, ?)",
                arrayOf<Any?>(
                    event.opId,
                    event.targetId,
                    event.opType,
                    event.targetType,
                    event.tsUtc,
                    deviceId,
                    event.undoOf,
                    event.beforeJson,
                    event.afterJson,
                    event.causeMsgId
                )
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    override suspend fun createNodeAndCommit(node: Node, event: RawEvent, reminder: Reminder?, edge: Edge?, edgeEvent: RawEvent?, consumeProposalId: String?) = withIoDb {
        val db = dbHelper.writableDatabase
        db.beginTransaction()
        try {
            val attrs = if (node.pinned) "{\"pinned\":true}" else null
            val localOnlyVal = if (node.localOnly) 1 else 0
            db.execSQL("INSERT INTO node (id, device_id, type, title, status, due_at, priority, severity, attrs, local_only, origin, hlc, created_at, updated_at, recurrence_rule, streak_current, streak_best, last_completed_at, original_due_at, due_history_json, slip_count, context_trigger, fallback_fire_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, '', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf<Any?>(node.id, deviceId, node.type, node.title, node.status, node.dueAt, node.priority, node.severity, attrs, localOnlyVal, node.origin, node.createdAt, node.updatedAt, node.recurrenceRule, node.streakCurrent, node.streakBest, node.lastCompletedAt, node.originalDueAt, node.dueHistoryJson, node.slipCount, node.contextTrigger, node.fallbackFireAt))
            reminder?.let { db.execSQL("INSERT OR REPLACE INTO reminder (id, node_id, text, fire_at, tier, rrule, iteration) VALUES (?, ?, ?, ?, ?, ?, ?)", arrayOf<Any?>(it.id, it.nodeId, it.text, it.fireAtMillis, it.tier.name.lowercase(), it.rrule, it.iteration)) }
            edge?.let {
                db.execSQL(
                    """
                    INSERT OR REPLACE INTO edge (
                        id, device_id, src_id, dst_id, dst_kind, type, weight, source, origin, source_msg_ids, attrs, hlc, created_at, updated_at, deleted_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                    arrayOf<Any?>(
                        it.id, deviceId, it.srcId, it.dstId, it.dstKind, it.type, it.weight, it.source, it.origin, it.sourceMsgIds, it.attrs, it.hlc, it.createdAt, it.updatedAt, it.deletedAt
                    )
                )
            }
            consumeProposalId?.let {
                consumeProposalInTx(db, it)
            }
            db.execSQL("INSERT INTO raw_event (op_id, target_id, op_type, target_type, ts_utc, device_id, actor, undo_of, before_json, after_json, cause_msg_id) VALUES (?, ?, ?, ?, ?, ?, 'chat_ai', ?, ?, ?, ?)",
                arrayOf<Any?>(event.opId, event.targetId, event.opType, event.targetType, event.tsUtc, deviceId, event.undoOf, event.beforeJson, event.afterJson, event.causeMsgId))
            edgeEvent?.let {
                db.execSQL("INSERT INTO raw_event (op_id, target_id, op_type, target_type, ts_utc, device_id, actor, before_json, after_json, cause_msg_id) VALUES (?, ?, ?, ?, ?, ?, 'chat_ai', ?, ?, ?)",
                    arrayOf<Any?>(it.opId, it.targetId, it.opType, it.targetType, it.tsUtc, deviceId, it.beforeJson, it.afterJson, it.causeMsgId))
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    override suspend fun updateNodeAndCommit(node: Node, event: RawEvent, deleteReminderId: String?, upsertReminder: Reminder?, consumeProposalId: String?, softDeleteEdgeIds: List<String>) = withIoDb {
        val db = dbHelper.writableDatabase
        db.beginTransaction()
        try {
            var existingAttrs: String? = null
            db.query("SELECT attrs FROM node WHERE id = ?", arrayOf<Any?>(node.id)).use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) {
                    existingAttrs = cursor.getString(0)
                }
            }
            val attrs = mergePinnedAttr(existingAttrs, node.pinned)
            val localOnlyVal = if (node.localOnly) 1 else 0
            // T-01: the column list must cover every mutable field the caller can change.
            // Omitting title/type/local_only silently dropped renames on disk while
            // SharedFakeStorage (whole-object replace) kept them, so core tests passed.
            // R-05: a zero-row update means the node vanished between the caller's read and this
            // write. Committing the event anyway produced a receipt for a change that never
            // happened. executeUpdateDelete returns the affected count directly, so the guard
            // needs no separate SELECT changes() round trip; throwing rolls back the whole
            // transaction, event included.
            // Bound through locals: Node is declared in :core, so its properties cannot be
            // smart-cast here.
            val dueAt = node.dueAt
            val closedAt = node.closedAt
            val lastCompletedAt = node.lastCompletedAt
            val recurrenceRule = node.recurrenceRule
            val originalDueAt = node.originalDueAt
            val dueHistoryJson = node.dueHistoryJson
            val slipCount = node.slipCount
            val contextTrigger = node.contextTrigger
            val fallbackFireAt = node.fallbackFireAt
            val updated = db.compileStatement("UPDATE node SET type = ?, title = ?, status = ?, due_at = ?, closed_at = ?, priority = ?, severity = ?, attrs = ?, local_only = ?, origin = ?, recurrence_rule = ?, streak_current = ?, streak_best = ?, last_completed_at = ?, updated_at = ?, original_due_at = ?, due_history_json = ?, slip_count = ?, context_trigger = ?, fallback_fire_at = ? WHERE id = ?").use { stmt ->
                stmt.bindString(1, node.type)
                stmt.bindString(2, node.title)
                stmt.bindString(3, node.status)
                if (dueAt == null) stmt.bindNull(4) else stmt.bindLong(4, dueAt)
                if (closedAt == null) stmt.bindNull(5) else stmt.bindLong(5, closedAt)
                stmt.bindLong(6, node.priority.toLong())
                stmt.bindLong(7, node.severity.toLong())
                if (attrs == null) stmt.bindNull(8) else stmt.bindString(8, attrs)
                stmt.bindLong(9, localOnlyVal.toLong())
                stmt.bindString(10, node.origin)
                if (recurrenceRule == null) stmt.bindNull(11) else stmt.bindString(11, recurrenceRule)
                stmt.bindLong(12, node.streakCurrent.toLong())
                stmt.bindLong(13, node.streakBest.toLong())
                if (lastCompletedAt == null) stmt.bindNull(14) else stmt.bindLong(14, lastCompletedAt)
                stmt.bindLong(15, node.updatedAt)
                if (originalDueAt == null) stmt.bindNull(16) else stmt.bindLong(16, originalDueAt)
                if (dueHistoryJson == null) stmt.bindNull(17) else stmt.bindString(17, dueHistoryJson)
                stmt.bindLong(18, slipCount.toLong())
                if (contextTrigger == null) stmt.bindNull(19) else stmt.bindString(19, contextTrigger)
                if (fallbackFireAt == null) stmt.bindNull(20) else stmt.bindLong(20, fallbackFireAt)
                stmt.bindString(21, node.id)
                stmt.executeUpdateDelete()
            }
            if (updated != 1) throw IllegalStateException("updateNodeAndCommit: no row for node ${node.id}")

            deleteReminderId?.let { db.execSQL("DELETE FROM reminder WHERE id = ?", arrayOf(it)) }

            upsertReminder?.let {
                db.execSQL("INSERT OR REPLACE INTO reminder (id, node_id, text, fire_at, tier, rrule, iteration) VALUES (?, ?, ?, ?, ?, ?, ?)", arrayOf<Any?>(it.id, it.nodeId, it.text, it.fireAtMillis, it.tier.name.lowercase(), it.rrule, it.iteration))
            }

            // B5: Atomic Proposal Consumption
            consumeProposalId?.let {
                consumeProposalInTx(db, it)
            }

            for (edgeId in softDeleteEdgeIds) {
                db.execSQL("UPDATE edge SET deleted_at = ?, updated_at = ? WHERE id = ? AND deleted_at IS NULL", arrayOf<Any?>(node.updatedAt, node.updatedAt, edgeId))
            }

            db.execSQL("INSERT INTO raw_event (op_id, target_id, op_type, target_type, ts_utc, device_id, actor, undo_of, before_json, after_json, cause_msg_id) VALUES (?, ?, ?, ?, ?, ?, 'chat_ai', ?, ?, ?, ?)",
                arrayOf<Any?>(event.opId, event.targetId, event.opType, event.targetType, event.tsUtc, deviceId, event.undoOf, event.beforeJson, event.afterJson, event.causeMsgId))

            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    override suspend fun undoChangeAndCommit(node: Node, event: RawEvent, restoreReminder: Reminder?) = withIoDb {
        val db = dbHelper.writableDatabase
        db.beginTransaction()
        try {
            // FIX: Restore due_at, priority, severity, and attrs so snapshots roll back accurately
            // T-02: the undo snapshot is a whole Node, so the restore must be whole too — a
            // renamed node rolled back to its old status but kept the new title.
            var existingAttrs: String? = null
            db.query("SELECT attrs FROM node WHERE id = ?", arrayOf<Any?>(node.id)).use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) {
                    existingAttrs = cursor.getString(0)
                }
            }
            val attrs = mergePinnedAttr(existingAttrs, node.pinned)
            val localOnlyVal = if (node.localOnly) 1 else 0
            db.execSQL("UPDATE node SET type = ?, title = ?, status = ?, due_at = ?, closed_at = ?, priority = ?, severity = ?, attrs = ?, local_only = ?, origin = ?, recurrence_rule = ?, streak_current = ?, streak_best = ?, last_completed_at = ?, updated_at = ?, original_due_at = ?, due_history_json = ?, slip_count = ?, context_trigger = ?, fallback_fire_at = ? WHERE id = ?",
                arrayOf<Any?>(node.type, node.title, node.status, node.dueAt, node.closedAt, node.priority, node.severity, attrs, localOnlyVal, node.origin, node.recurrenceRule, node.streakCurrent, node.streakBest, node.lastCompletedAt, node.updatedAt, node.originalDueAt, node.dueHistoryJson, node.slipCount, node.contextTrigger, node.fallbackFireAt, node.id))
            restoreReminder?.let { db.execSQL("INSERT OR REPLACE INTO reminder (id, node_id, text, fire_at, tier, rrule, iteration) VALUES (?, ?, ?, ?, ?, ?, ?)", arrayOf<Any?>(it.id, it.nodeId, it.text, it.fireAtMillis, it.tier.name.lowercase(), it.rrule, it.iteration)) }
            db.execSQL("INSERT INTO raw_event (op_id, target_id, op_type, target_type, ts_utc, device_id, actor, undo_of, before_json, after_json, cause_msg_id) VALUES (?, ?, ?, ?, ?, ?, 'user', ?, ?, ?, ?)",
                arrayOf<Any?>(event.opId, event.targetId, event.opType, event.targetType, event.tsUtc, deviceId, event.undoOf, event.beforeJson, event.afterJson, event.causeMsgId))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    override suspend fun createReminderAndCommit(reminder: Reminder, event: RawEvent, consumeProposalId: String?) = withIoDb {
        val db = dbHelper.writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("INSERT OR REPLACE INTO reminder (id, node_id, text, fire_at, tier, rrule, iteration) VALUES (?, ?, ?, ?, ?, ?, ?)", arrayOf<Any?>(reminder.id, reminder.nodeId, reminder.text, reminder.fireAtMillis, reminder.tier.name.lowercase(), reminder.rrule, reminder.iteration))
            consumeProposalId?.let {
                consumeProposalInTx(db, it)
            }
            db.execSQL("INSERT INTO raw_event (op_id, target_id, op_type, target_type, ts_utc, device_id, actor, undo_of, before_json, after_json, cause_msg_id) VALUES (?, ?, ?, ?, ?, ?, 'chat_ai', ?, ?, ?, ?)",
                arrayOf<Any?>(event.opId, event.targetId, event.opType, event.targetType, event.tsUtc, deviceId, event.undoOf, event.beforeJson, event.afterJson, event.causeMsgId))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    override suspend fun deleteReminderAndCommit(reminderId: String, event: RawEvent) = withIoDb {
        val db = dbHelper.writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM reminder WHERE id = ?", arrayOf(reminderId))
            db.execSQL("INSERT INTO raw_event (op_id, target_id, op_type, target_type, ts_utc, device_id, actor, undo_of, before_json, after_json, cause_msg_id) VALUES (?, ?, ?, ?, ?, ?, 'user', ?, ?, ?, ?)",
                arrayOf<Any?>(event.opId, event.targetId, event.opType, event.targetType, event.tsUtc, deviceId, event.undoOf, event.beforeJson, event.afterJson, event.causeMsgId))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    private fun saveSoulFactInternal(db: androidx.sqlite.db.SupportSQLiteDatabase, fact: SoulFact) {
        val sensitiveVal = if (fact.sensitive) 1 else 0
        val localOnlyVal = if (fact.localOnly) 1 else 0
        val updated = db.compileStatement(
            "UPDATE soul_fact SET category = ?, statement = ?, confidence = ?, source = ?, origin = ?, status = ?, sensitive = ?, local_only = ?, updated_at = ? WHERE id = ?"
        ).use { stmt ->
            stmt.bindString(1, fact.category)
            stmt.bindString(2, fact.statement)
            stmt.bindDouble(3, fact.confidence)
            stmt.bindString(4, fact.source)
            stmt.bindString(5, fact.origin)
            stmt.bindString(6, fact.status)
            stmt.bindLong(7, sensitiveVal.toLong())
            stmt.bindLong(8, localOnlyVal.toLong())
            stmt.bindLong(9, fact.createdAt)
            stmt.bindString(10, fact.id)
            stmt.executeUpdateDelete()
        }
        if (updated == 0) {
            db.execSQL(
                "INSERT INTO soul_fact (id, device_id, category, statement, confidence, source, origin, status, sensitive, local_only, hlc, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, '', ?, ?)",
                arrayOf<Any>(fact.id, deviceId, fact.category, fact.statement, fact.confidence, fact.source, fact.origin, fact.status, sensitiveVal, localOnlyVal, fact.createdAt, fact.createdAt)
            )
        }
    }

    override suspend fun saveSoulFact(fact: SoulFact) = withIoDb {
        val db = dbHelper.writableDatabase
        saveSoulFactInternal(db, fact)
    }

    override suspend fun createSoulFactAndCommit(fact: SoulFact, event: RawEvent) = withIoDb {
        val db = dbHelper.writableDatabase
        db.beginTransaction()
        try {
            saveSoulFactInternal(db, fact)
            db.execSQL("INSERT INTO raw_event (op_id, target_id, op_type, target_type, ts_utc, device_id, actor, undo_of, before_json, after_json, cause_msg_id) VALUES (?, ?, ?, ?, ?, ?, 'chat_ai', ?, ?, ?, ?)",
                arrayOf<Any?>(event.opId, event.targetId, event.opType, event.targetType, event.tsUtc, deviceId, event.undoOf, event.beforeJson, event.afterJson, event.causeMsgId))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    override suspend fun searchMemoryFts(query: String, limit: Int): List<SearchResult> = withIoDb {
        val nodeResults = mutableListOf<SearchResult>()
        val soulResults = mutableListOf<SearchResult>()
        val chatResults = mutableListOf<SearchResult>()
        if (query.isBlank()) return@withIoDb emptyList()
        val safeLimit = limit.coerceIn(1, 20)

        try {
            val sanitized = query.replace(Regex("[^\\p{L}\\p{M}\\p{N}\\s]"), "").trim()
            val tokens = sanitized.split(Regex("\\s+")).filter { it.isNotBlank() }
            if (tokens.isEmpty()) return@withIoDb emptyList()
            val matchQuery = tokens.joinToString(" AND ") { "\"$it\"*" }

            // B1: Strict status filtering applied natively to FTS joins
            dbHelper.readableDatabase.query(
                "SELECT node.id, node.type, node.title, node.status, node.due_at FROM node JOIN node_fts ON node.rowid = node_fts.rowid WHERE node_fts MATCH ? AND node.status NOT IN ('archived', 'cancelled', 'done') AND (node.local_only IS NULL OR node.local_only = 0) ORDER BY node_fts.rank LIMIT ?",
                arrayOf<Any>(matchQuery, safeLimit)
            ).use {
                while (it.moveToNext()) {
                    val due = if (it.isNull(4)) "" else "Due: ${it.getLong(4)}"
                    nodeResults.add(SearchResult(it.getString(0), it.getString(1), it.getString(2), "Status: ${it.getString(3)} $due".trim()))
                }
            }
            dbHelper.readableDatabase.query(
                "SELECT soul_fact.id, soul_fact.category, soul_fact.statement FROM soul_fact JOIN soul_fts ON soul_fact.rowid = soul_fts.rowid WHERE soul_fts MATCH ? AND soul_fact.status IN ('active', 'pinned') AND (soul_fact.local_only IS NULL OR soul_fact.local_only = 0) ORDER BY soul_fts.rank LIMIT ?",
                arrayOf<Any>(matchQuery, safeLimit)
            ).use {
                while (it.moveToNext()) {
                    soulResults.add(SearchResult(it.getString(0), "soul_fact", it.getString(1), it.getString(2)))
                }
            }
            dbHelper.readableDatabase.query(
                "SELECT msg_chunk.id, msg_chunk.day_key, msg_chunk.text, msg_chunk.ts_start FROM msg_chunk JOIN msg_chunk_fts ON msg_chunk.rowid = msg_chunk_fts.rowid WHERE msg_chunk_fts MATCH ? ORDER BY msg_chunk_fts.rank LIMIT ?",
                arrayOf<Any>(matchQuery, safeLimit)
            ).use {
                while (it.moveToNext()) {
                    chatResults.add(SearchResult(it.getString(0), "chat", it.getString(1), it.getString(2), it.getLong(3)))
                }
            }
        } catch (e: Exception) {
            // R-23: an empty result is indistinguishable from a genuine miss, so a broken index
            // would make the model answer "No results found." forever. Record the failure.
            logDiagError("searchMemoryFts failed for query '$query': ${e.message}")
        }
        val interleaved = mutableListOf<SearchResult>()
        val maxLen = maxOf(nodeResults.size, soulResults.size, chatResults.size)
        for (i in 0 until maxLen) {
            if (i < nodeResults.size) interleaved.add(nodeResults[i])
            if (i < soulResults.size) interleaved.add(soulResults[i])
            if (i < chatResults.size) interleaved.add(chatResults[i])
        }
        return@withIoDb interleaved.take(safeLimit)
    }

    override suspend fun saveEmbedding(
        ownerType: String,
        ownerId: String,
        modelVersion: String,
        vector: com.anchor.core.retrieval.QuantVector
    ) = withIoDb {
        dbHelper.writableDatabase.execSQL(
            "INSERT OR REPLACE INTO embedding (owner_type, owner_id, model_version, dim, scale, vec, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
            arrayOf<Any?>(ownerType, ownerId, modelVersion, vector.dim, vector.scale, vector.bytes, System.currentTimeMillis())
        )
    }

    override suspend fun getEmbeddings(
        ownerType: String,
        modelVersion: String
    ): List<Pair<String, com.anchor.core.retrieval.QuantVector>> = withIoDb {
        val list = mutableListOf<Pair<String, com.anchor.core.retrieval.QuantVector>>()
        val sql = when (ownerType) {
            "node" -> "SELECT e.owner_id, e.scale, e.vec FROM embedding e JOIN node n ON e.owner_id = n.id WHERE e.owner_type = 'node' AND e.model_version = ? AND n.status NOT IN ('archived', 'cancelled', 'done') AND (n.local_only IS NULL OR n.local_only = 0)"
            "soul_fact" -> "SELECT e.owner_id, e.scale, e.vec FROM embedding e JOIN soul_fact sf ON e.owner_id = sf.id WHERE e.owner_type = 'soul_fact' AND e.model_version = ? AND sf.status IN ('active', 'pinned') AND (sf.local_only IS NULL OR sf.local_only = 0)"
            else -> "SELECT owner_id, scale, vec FROM embedding WHERE owner_type = ? AND model_version = ?"
        }
        val args = if (ownerType == "node" || ownerType == "soul_fact") arrayOf(modelVersion) else arrayOf(ownerType, modelVersion)
        dbHelper.readableDatabase.query(sql, args).use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow("owner_id")
            val scaleIdx = cursor.getColumnIndexOrThrow("scale")
            val vecIdx = cursor.getColumnIndexOrThrow("vec")
            while (cursor.moveToNext()) {
                val ownerId = cursor.getString(idIdx)
                val scale = cursor.getFloat(scaleIdx)
                val vec = cursor.getBlob(vecIdx)
                list.add(ownerId to com.anchor.core.retrieval.QuantVector(vec, scale))
            }
        }
        list
    }

    override suspend fun deleteEmbedding(ownerType: String, ownerId: String) = withIoDb {
        dbHelper.writableDatabase.execSQL(
            "DELETE FROM embedding WHERE owner_type = ? AND owner_id = ?",
            arrayOf(ownerType, ownerId)
        )
    }

    /**
     * Blueprint 23 §5 `search_history`. msg_chunk holds one row per message, so the chunk FTS
     * index is the message index; the chunk id is `chunk_<raw_message.id>`.
     * Redacted rows are excluded twice over: redaction NULLs raw_message.content and deletes
     * the chunk, and the join below re-checks raw_message so a stale chunk can never leak.
     */
    override suspend fun searchHistory(query: String, limit: Int): List<SearchResult> = withIoDb {
        val results = mutableListOf<SearchResult>()
        if (query.isBlank()) return@withIoDb results
        val safeLimit = limit.coerceIn(1, 10)

        try {
            val sanitized = query.replace(Regex("[^\\p{L}\\p{M}\\p{N}\\s]"), "").trim()
            val tokens = sanitized.split(Regex("\\s+")).filter { it.isNotBlank() }
            if (tokens.isEmpty()) return@withIoDb results
            val matchQuery = tokens.joinToString(" AND ") { "\"$it\"*" }

            // Join through msg_chunk explicitly. A correlated subquery on msg_chunk_fts.rowid
            // returns NULL for a message with no chunk, and an external-content FTS5 table
            // (content='msg_chunk') then resolves that NULL to the content row at the same
            // position — which resurrects system messages that were never chunked. Joining
            // msg_chunk -> raw_message by id keeps the match anchored to a real chunk.
            dbHelper.readableDatabase.query(
                "SELECT raw_message.id, raw_message.role, raw_message.content, raw_message.ts_utc, raw_message.day_key " +
                    "FROM raw_message " +
                    "JOIN msg_chunk ON msg_chunk.id = 'chunk_' || raw_message.id " +
                    "JOIN msg_chunk_fts ON msg_chunk_fts.rowid = msg_chunk.rowid " +
                    "WHERE msg_chunk_fts MATCH ? AND raw_message.content IS NOT NULL AND raw_message.redacted_at IS NULL " +
                    "ORDER BY raw_message.ts_utc DESC LIMIT ?",
                arrayOf<Any>(matchQuery, safeLimit)
            ).use {
                while (it.moveToNext()) {
                    results.add(SearchResult(it.getString(0), it.getString(1), it.getString(4) ?: "", it.getString(2), it.getLong(3)))
                }
            }
        } catch (e: Exception) {
            // R-23: a broken index must not look like "no such conversation".
            logDiagError("searchHistory failed for query '$query': ${e.message}")
        }
        return@withIoDb results
    }

    /**
     * R-24: a stored intent this build does not know (written by a newer build, or a downgrade)
     * must not make the proposal unreadable. Degrade to CREATE and say so in diagnostics.
     */
    private fun parseIntentOrCreate(raw: String?): IntentType =
        try {
            IntentType.valueOf(raw ?: "")
        } catch (e: IllegalArgumentException) {
            logDiagError("Unknown stored proposal intent '$raw'; degraded to CREATE")
            IntentType.CREATE
        }

    /** Best-effort diagnostics write; a failing log must never turn into a failing query. */
    private fun logDiagError(msg: String) {
        runCatching {
            dbHelper.writableDatabase.execSQL(
                "INSERT INTO diag_log (ts, level, tag, msg) VALUES (?, 'ERROR', 'AndroidStorage', ?)",
                arrayOf<Any>(System.currentTimeMillis(), msg.take(500))
            )
        }
    }

    override suspend fun getProactiveState(): ProactiveState = withIoDb {
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

    override suspend fun saveProactiveState(state: ProactiveState) = withIoDb {
        dbHelper.writableDatabase.execSQL(
            "INSERT OR REPLACE INTO proactive_state (id, open_thread_msg_id, thread_opened_at, backoff_step, last_sent_at, last_contact_at, paused_until, silent_days, sent_today, fuse_tripped_at, day_key) VALUES (1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            arrayOf<Any?>(state.openThreadMsgId, state.threadOpenedAt, state.backoffStep, state.lastSentAt, state.lastContactAt, state.pausedUntil, state.silentDays, state.sentToday, state.fuseTrippedAt, state.dayKey)
        )
    }

    override suspend fun getReliabilityState(): ReliabilityState = withIoDb {
        dbHelper.readableDatabase.query("SELECT last_check_at, score, issues_json, sweeper_heartbeat, p95_latency_ms FROM reliability_state WHERE id = 1").use {
            if (it.moveToFirst()) {
                ReliabilityState(
                    lastCheckAt = if (it.isNull(0)) null else it.getLong(0),
                    score = if (it.isNull(1)) null else it.getString(1),
                    issuesJson = if (it.isNull(2)) null else it.getString(2),
                    sweeperHeartbeat = if (it.isNull(3)) null else it.getLong(3),
                    p95LatencyMs = if (it.isNull(4)) null else it.getInt(4)
                )
            } else ReliabilityState()
        }
    }

    override suspend fun saveReliabilityState(state: ReliabilityState) = withIoDb {
        val db = dbHelper.writableDatabase
        val lca = state.lastCheckAt
        val sc = state.score
        val ij = state.issuesJson
        val sh = state.sweeperHeartbeat
        val p95 = state.p95LatencyMs

        val updated = db.compileStatement(
            "UPDATE reliability_state SET last_check_at = ?, score = ?, issues_json = ?, sweeper_heartbeat = ?, p95_latency_ms = ? WHERE id = 1"
        ).use { stmt ->
            if (lca != null) stmt.bindLong(1, lca) else stmt.bindNull(1)
            if (sc != null) stmt.bindString(2, sc) else stmt.bindNull(2)
            if (ij != null) stmt.bindString(3, ij) else stmt.bindNull(3)
            if (sh != null) stmt.bindLong(4, sh) else stmt.bindNull(4)
            if (p95 != null) stmt.bindLong(5, p95.toLong()) else stmt.bindNull(5)
            stmt.executeUpdateDelete()
        }
        if (updated == 0) {
            db.execSQL(
                "INSERT INTO reliability_state (id, last_check_at, score, issues_json, sweeper_heartbeat, p95_latency_ms) VALUES (1, ?, ?, ?, ?, ?)",
                arrayOf<Any?>(state.lastCheckAt, state.score, state.issuesJson, state.sweeperHeartbeat, state.p95LatencyMs)
            )
        }
    }

    override suspend fun recordReminderFire(fire: ReminderFire) = withIoDb {
        dbHelper.writableDatabase.execSQL(
            "INSERT INTO reminder_fire (id, reminder_id, fired_at, scheduled_for, latency_ms, outcome) VALUES (?, ?, ?, ?, ?, ?)",
            arrayOf<Any?>(fire.id, fire.reminderId, fire.firedAt, fire.scheduledFor, fire.latencyMs, fire.outcome)
        )
    }

    override suspend fun getRegisteredAlarms(): List<Pair<String, Long>> = withIoDb {
        val list = mutableListOf<Pair<String, Long>>()
        dbHelper.readableDatabase.query("SELECT reminder_id, registered_for FROM alarm_registry").use {
            while (it.moveToNext()) {
                list.add(it.getString(0) to it.getLong(1))
            }
        }
        list
    }

    override suspend fun getRegisteredAlarmRecords(): List<com.anchor.core.models.RegisteredAlarm> = withIoDb {
        val list = mutableListOf<com.anchor.core.models.RegisteredAlarm>()
        dbHelper.readableDatabase.query("SELECT reminder_id, registered_for, api FROM alarm_registry").use {
            while (it.moveToNext()) {
                val api = if (it.isNull(2)) "alarm_clock" else it.getString(2)
                list.add(com.anchor.core.models.RegisteredAlarm(it.getString(0), it.getLong(1), api))
            }
        }
        list
    }

    override suspend fun saveRegisteredAlarm(reminderId: String, requestCode: Int, registeredFor: Long, api: String) = withIoDb {
        dbHelper.writableDatabase.execSQL(
            "INSERT OR REPLACE INTO alarm_registry (reminder_id, request_code, api, registered_for, registered_at) VALUES (?, ?, ?, ?, ?)",
            arrayOf<Any>(reminderId, requestCode, api, registeredFor, System.currentTimeMillis())
        )
    }

    override suspend fun deleteRegisteredAlarm(reminderId: String) = withIoDb {
        dbHelper.writableDatabase.execSQL("DELETE FROM alarm_registry WHERE reminder_id = ?", arrayOf(reminderId))
    }

    override suspend fun getReminderByNodeId(nodeId: String): Reminder? = withIoDb {
        dbHelper.readableDatabase.query("SELECT $REMINDER_COLS FROM reminder WHERE node_id = ?", arrayOf(nodeId)).use {
            if (it.moveToFirst()) parseReminder(it) else null
        }
    }

    override suspend fun getReminderById(id: String): Reminder? = withIoDb {
        dbHelper.readableDatabase.query("SELECT $REMINDER_COLS FROM reminder WHERE id = ?", arrayOf(id)).use {
            if (it.moveToFirst()) parseReminder(it) else null
        }
    }

    override suspend fun clearAlarmRegistry() = withIoDb {
        dbHelper.writableDatabase.execSQL("DELETE FROM alarm_registry")
    }

    override suspend fun hasReminderFire(reminderId: String, outcome: String): Boolean = withIoDb {
        dbHelper.readableDatabase.query("SELECT 1 FROM reminder_fire WHERE reminder_id = ? AND outcome = ?", arrayOf(reminderId, outcome)).use {
            it.moveToFirst()
        }
    }

    override suspend fun getReminderFire(reminderId: String, outcome: String): ReminderFire? = withIoDb {
        dbHelper.readableDatabase.query(
            "SELECT id, reminder_id, fired_at, scheduled_for, latency_ms, outcome FROM reminder_fire WHERE reminder_id = ? AND outcome = ? ORDER BY fired_at DESC LIMIT 1",
            arrayOf(reminderId, outcome)
        ).use {
            if (it.moveToFirst()) {
                ReminderFire(
                    id = it.getString(0),
                    reminderId = it.getString(1),
                    firedAt = it.getLong(2),
                    scheduledFor = it.getLong(3),
                    latencyMs = it.getInt(4),
                    outcome = it.getString(5)
                )
            } else null
        }
    }

    override suspend fun getRecentReminderFires(limit: Int): List<ReminderFire> = withIoDb {
        val list = mutableListOf<ReminderFire>()
        dbHelper.readableDatabase.query(
            "SELECT id, reminder_id, fired_at, scheduled_for, latency_ms, outcome FROM reminder_fire ORDER BY fired_at DESC LIMIT ?",
            arrayOf(limit.toString())
        ).use {
            while (it.moveToNext()) {
                list.add(
                    ReminderFire(
                        id = it.getString(0),
                        reminderId = it.getString(1),
                        firedAt = it.getLong(2),
                        scheduledFor = it.getLong(3),
                        latencyMs = it.getInt(4),
                        outcome = it.getString(5)
                    )
                )
            }
        }
        list
    }

    override suspend fun saveEdge(edge: Edge) = withIoDb {
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

    override suspend fun getEdge(id: String): Edge? = withIoDb {
        dbHelper.readableDatabase.query(
            "SELECT id, src_id, dst_id, dst_kind, type, weight, source, origin, source_msg_ids, attrs, hlc, created_at, updated_at, deleted_at FROM edge WHERE id = ? AND deleted_at IS NULL",
            arrayOf(id)
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                cursorToEdge(cursor)
            } else null
        }
    }

    override suspend fun getEdgesForSource(srcId: String, type: String?): List<Edge> = withIoDb {
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

    override suspend fun getEdgesForDestination(dstId: String, type: String?): List<Edge> = withIoDb {
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

    override suspend fun deleteEdge(id: String) = withIoDb {
        val now = System.currentTimeMillis()
        dbHelper.writableDatabase.execSQL(
            "UPDATE edge SET deleted_at = ?, updated_at = ? WHERE id = ? AND deleted_at IS NULL",
            arrayOf<Any?>(now, now, id)
        )
    }

    override suspend fun createEdgeAndCommit(edge: Edge, event: RawEvent) = withIoDb {
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
                "INSERT INTO raw_event (op_id, target_id, op_type, target_type, ts_utc, device_id, actor, before_json, after_json, cause_msg_id) VALUES (?, ?, ?, ?, ?, ?, 'chat_ai', ?, ?, ?)",
                arrayOf<Any?>(event.opId, event.targetId, event.opType, event.targetType, event.tsUtc, deviceId, event.beforeJson, event.afterJson, event.causeMsgId)
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    override suspend fun deleteEdgeAndCommit(edgeId: String, event: RawEvent) = withIoDb {
        val db = dbHelper.writableDatabase
        val now = event.tsUtc
        db.beginTransaction()
        try {
            db.execSQL(
                "UPDATE edge SET deleted_at = ?, updated_at = ? WHERE id = ? AND deleted_at IS NULL",
                arrayOf<Any?>(now, now, edgeId)
            )
            db.execSQL(
                "INSERT INTO raw_event (op_id, target_id, op_type, target_type, ts_utc, device_id, actor, undo_of, before_json, after_json, cause_msg_id) VALUES (?, ?, ?, ?, ?, ?, 'user', ?, ?, ?, ?)",
                arrayOf<Any?>(event.opId, event.targetId, event.opType, event.targetType, event.tsUtc, deviceId, event.undoOf, event.beforeJson, event.afterJson, event.causeMsgId)
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    override suspend fun getGraphNeighborhood(nodeId: String, maxHops: Int, cap: Int): List<Node> = withIoDb {
        val visitedNodeIds = mutableSetOf<String>()
        var currentFrontier = setOf(nodeId)
        val resultNodes = mutableListOf<Node>()

        visitedNodeIds.add(nodeId)

        for (hop in 1..maxHops) {
            val nextFrontier = mutableSetOf<String>()
            for (currId in currentFrontier) {
                val outgoing = getEdgesForSource(currId, null).map { it.dstId }
                val incoming = getEdgesForDestination(currId, null).map { it.srcId }
                for (neighborId in outgoing + incoming) {
                    if (neighborId !in visitedNodeIds) {
                        visitedNodeIds.add(neighborId)
                        nextFrontier.add(neighborId)
                        val node = getNode(neighborId)
                        if (node != null && node.status != "archived" && node.status != "cancelled") {
                            resultNodes.add(node)
                            if (resultNodes.size >= cap) return@withIoDb resultNodes
                        }
                    }
                }
            }
            currentFrontier = nextFrontier
            if (currentFrontier.isEmpty()) break
        }
        resultNodes
    }

    override suspend fun getCalendarBuckets(startMs: Long, endMs: Long): Map<String, List<Node>> = withIoDb {
        val rule = getEffectiveDayRule(startMs)
        val matchingNodes = getNodesDueBetween(startMs, endMs, limit = 1000)
            .filter { it.status != "archived" && it.status != "cancelled" }
        matchingNodes.groupBy {
            DayRollover.computeDayKey(it.dueAt!!, rule)
        }
    }

    override suspend fun getBoardViewNodes(limitPerColumn: Int): Map<String, List<Node>> = withIoDb {
        val allOpen = getActiveNodes(limit = 1000)
        val todo = allOpen.filter { it.status == "active" || it.status == "paused" }.take(limitPerColumn)
        val inProgress = allOpen.filter { it.status == "in_progress" }.take(limitPerColumn)
        val doneSql = "SELECT $NODE_COLS FROM node WHERE (status = 'done' OR status = 'closed') ORDER BY coalesce(closed_at, updated_at, created_at) DESC, id ASC LIMIT CAST(? AS INTEGER)"
        val done = queryNodes(doneSql, arrayOf(limitPerColumn.toString()))

        mapOf(
            "todo" to todo,
            "in_progress" to inProgress,
            "done" to done
        )
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

    override suspend fun getDaysWithMessages(startDayKey: String, endDayKey: String): Set<String> = withIoDb {
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

    override suspend fun getHeatMapCounts(startDayKey: String, endDayKey: String): Map<String, Int> = withIoDb {
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

    override suspend fun getMessagesForDay(dayKey: String): List<ChatMessage> = withIoDb {
        val list = mutableListOf<ChatMessage>()
        dbHelper.readableDatabase.query(
            "SELECT id, role, content, day_key, ts_utc, attrs FROM raw_message WHERE day_key = ? ORDER BY ts_utc ASC",
            arrayOf(dayKey)
        ).use {
            while (it.moveToNext()) {
                val id = it.getString(0)
                val role = it.getString(1)
                val content = if (it.isNull(2)) null else it.getString(2)
                val dKey = if (it.isNull(3)) null else it.getString(3)
                val ts = if (it.isNull(4)) 0L else it.getLong(4)
                val attrs = if (it.isNull(5)) null else it.getString(5)
                list.add(ChatMessage(id, role, content, dKey, ts, attrs))
            }
        }
        list
    }

    // R-26: the range predicate bounds the rows scanned; the LIMIT bounds the rows returned. A day
    // or week never approaches it — reaching it means the range itself was wrong.
    override suspend fun getRemindersBetween(startMs: Long, endMs: Long, limit: Int): List<Reminder> = withIoDb {
        val list = mutableListOf<Reminder>()
        dbHelper.readableDatabase.query(
            "SELECT $REMINDER_COLS FROM reminder WHERE fire_at >= ? AND fire_at <= ? ORDER BY fire_at ASC LIMIT ?",
            arrayOf<Any>(startMs, endMs, limit)
        ).use {
            while (it.moveToNext()) {
                list.add(parseReminder(it))
            }
        }
        list
    }

    override suspend fun getRemindersForDay(startOfDayMs: Long, endOfDayMs: Long, limit: Int): List<Reminder> =
        getRemindersBetween(startOfDayMs, endOfDayMs, limit)

    override suspend fun getQuoteSlot(slot: QuoteSlot, dayKey: String): Quote? = withIoDb {
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

    override suspend fun saveQuoteSlot(quote: Quote) = withIoDb {
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

    override suspend fun getCompletedTodayCount(startOfDayMs: Long, endOfDayMs: Long): Int = withIoDb {
        dbHelper.readableDatabase.query(
            "SELECT COUNT(*) FROM node WHERE status = 'done' AND closed_at IS NOT NULL AND closed_at >= CAST(? AS INTEGER) AND closed_at < CAST(? AS INTEGER)",
            arrayOf(startOfDayMs.toString(), endOfDayMs.toString())
        ).use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }
    }

    // Offline outbox (blueprint 41 §4, audit §6 item 6)

    override suspend fun enqueueOutbox(job: OutboxJob) = withIoDb {
        // idem_key is UNIQUE: re-enqueueing the same logical job must not create a second turn.
        dbHelper.writableDatabase.execSQL(
            "INSERT OR IGNORE INTO outbox (id, job_type, priority, payload_json, idem_key, status, attempts, " +
                "next_try_at, deadline_at, created_at, updated_at, last_error) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
            arrayOf<Any?>(
                job.id, job.jobType, job.priority, job.payloadJson, job.idemKey, job.status, job.attempts,
                job.nextTryAt, job.deadlineAt, job.createdAt, job.updatedAt, job.lastError
            )
        )
    }

    override suspend fun claimOutbox(id: String, nextTryAt: Long, now: Long) = withIoDb {
        dbHelper.writableDatabase.execSQL(
            "UPDATE outbox SET next_try_at = ?, updated_at = ? WHERE id = ?",
            arrayOf<Any>(nextTryAt, now, id)
        )
    }

    override suspend fun getDueOutbox(jobType: String, now: Long): List<OutboxJob> = withIoDb {
        queryOutbox(
            "SELECT $OUTBOX_COLUMNS FROM outbox WHERE job_type = ? AND status = ? AND next_try_at <= ? " +
                "ORDER BY priority ASC, created_at ASC, id ASC",
            arrayOf(jobType, OutboxJob.STATUS_PENDING, now.toString())
        )
    }

    override suspend fun getPendingOutbox(jobType: String): List<OutboxJob> = withIoDb {
        queryOutbox(
            "SELECT $OUTBOX_COLUMNS FROM outbox WHERE job_type = ? AND status = ? " +
                "ORDER BY priority ASC, created_at ASC, id ASC",
            arrayOf(jobType, OutboxJob.STATUS_PENDING)
        )
    }

    override suspend fun markOutboxDone(id: String, now: Long) = withIoDb {
        dbHelper.writableDatabase.execSQL(
            "UPDATE outbox SET status = ?, updated_at = ? WHERE id = ?",
            arrayOf<Any>(OutboxJob.STATUS_DONE, now, id)
        )
    }

    override suspend fun markOutboxFailed(id: String, now: Long, nextTryAt: Long, error: String, status: String) = withIoDb {
        // attempts counts how many times this job has been tried; next_try_at is the caller's
        // backoff decision (blueprint 41 §3.4), not a constant here.
        dbHelper.writableDatabase.execSQL(
            "UPDATE outbox SET status = ?, attempts = attempts + 1, next_try_at = ?, updated_at = ?, " +
                "last_error = ? WHERE id = ?",
            arrayOf<Any>(status, nextTryAt, now, error, id)
        )
    }

    override suspend fun dropStaleOutbox(now: Long): Int = withIoDb {
        val db = dbHelper.writableDatabase
        db.compileStatement(
            "UPDATE outbox SET status = ?, updated_at = ? WHERE status = ? AND deadline_at IS NOT NULL AND deadline_at < ?"
        ).use { stmt ->
            stmt.bindString(1, OutboxJob.STATUS_DROPPED)
            stmt.bindLong(2, now)
            stmt.bindString(3, OutboxJob.STATUS_PENDING)
            stmt.bindLong(4, now)
            stmt.executeUpdateDelete()
        }
    }

    private fun queryOutbox(sql: String, args: Array<String>): List<OutboxJob> =
        dbHelper.readableDatabase.query(sql, args).use { c ->
            val out = ArrayList<OutboxJob>(c.count)
            while (c.moveToNext()) out.add(c.toOutboxJob())
            out
        }

    private fun android.database.Cursor.toOutboxJob() = OutboxJob(
        id = getString(0),
        jobType = getString(1),
        priority = getInt(2),
        payloadJson = getString(3),
        idemKey = getString(4),
        status = getString(5),
        attempts = getInt(6),
        nextTryAt = getLong(7),
        deadlineAt = if (isNull(8) || getString(8).isNullOrEmpty()) null else getLong(8),
        createdAt = getLong(9),
        updatedAt = getLong(10),
        lastError = if (isNull(11) || getString(11).isNullOrEmpty()) null else getString(11)
    )

    // Learning & Behavioral Preferences (Blueprint 25)
    override suspend fun recordSignal(signal: com.anchor.core.models.SignalLog) = withIoDb {
        dbHelper.writableDatabase.execSQL(
            "INSERT INTO signal_log (id, ts_utc, kind, subject, value, attrs) VALUES (?,?,?,?,?,?)",
            arrayOf<Any?>(
                signal.id, signal.tsUtc, signal.kind,
                signal.subject,
                signal.value,
                signal.attrs
            )
        )
    }

    override suspend fun getSignals(kind: String?, sinceUtc: Long, limit: Int): List<com.anchor.core.models.SignalLog> = withIoDb {
        val sql = if (kind != null) {
            "SELECT id, ts_utc, kind, subject, value, attrs FROM signal_log WHERE kind = ? AND ts_utc >= ? ORDER BY ts_utc ASC LIMIT ?"
        } else {
            "SELECT id, ts_utc, kind, subject, value, attrs FROM signal_log WHERE ts_utc >= ? ORDER BY ts_utc ASC LIMIT ?"
        }
        val args = if (kind != null) {
            arrayOf(kind, sinceUtc.toString(), limit.coerceAtLeast(0).toString())
        } else {
            arrayOf(sinceUtc.toString(), limit.coerceAtLeast(0).toString())
        }
        dbHelper.readableDatabase.query(sql, args).use { c ->
            val out = ArrayList<com.anchor.core.models.SignalLog>(c.count)
            while (c.moveToNext()) {
                out.add(
                    com.anchor.core.models.SignalLog(
                        id = c.getString(0),
                        tsUtc = c.getLong(1),
                        kind = c.getString(2),
                        subject = if (c.isNull(3) || c.getString(3).isEmpty()) null else c.getString(3),
                        value = if (c.isNull(4)) null else c.getDouble(4),
                        attrs = if (c.isNull(5) || c.getString(5).isEmpty()) null else c.getString(5)
                    )
                )
            }
            out
        }
    }

    override suspend fun getLearnedPref(key: String, scope: String): com.anchor.core.models.LearnedPref? = withIoDb {
        dbHelper.readableDatabase.query(
            "SELECT \"key\", scope, value_json, samples, confidence, locked, updated_at FROM learned_pref WHERE \"key\" = ? AND scope = ?",
            arrayOf(key, scope)
        ).use { c ->
            if (c.moveToFirst()) {
                com.anchor.core.models.LearnedPref(
                    key = c.getString(0),
                    scope = c.getString(1),
                    valueJson = c.getString(2),
                    samples = c.getInt(3),
                    confidence = c.getDouble(4),
                    locked = c.getInt(5) == 1,
                    updatedAt = c.getLong(6)
                )
            } else null
        }
    }

    override suspend fun getAllLearnedPrefs(): List<com.anchor.core.models.LearnedPref> = withIoDb {
        dbHelper.readableDatabase.query(
            "SELECT \"key\", scope, value_json, samples, confidence, locked, updated_at FROM learned_pref ORDER BY \"key\" ASC, scope ASC"
        ).use { c ->
            val out = ArrayList<com.anchor.core.models.LearnedPref>(c.count)
            while (c.moveToNext()) {
                out.add(
                    com.anchor.core.models.LearnedPref(
                        key = c.getString(0),
                        scope = c.getString(1),
                        valueJson = c.getString(2),
                        samples = c.getInt(3),
                        confidence = c.getDouble(4),
                        locked = c.getInt(5) == 1,
                        updatedAt = c.getLong(6)
                    )
                )
            }
            out
        }
    }

    override suspend fun saveLearnedPref(pref: com.anchor.core.models.LearnedPref) = withIoDb {
        dbHelper.writableDatabase.execSQL(
            "INSERT OR REPLACE INTO learned_pref (\"key\", scope, value_json, samples, confidence, locked, updated_at) VALUES (?,?,?,?,?,?,?)",
            arrayOf<Any>(
                pref.key, pref.scope, pref.valueJson, pref.samples, pref.confidence,
                if (pref.locked) 1 else 0,
                pref.updatedAt
            )
        )
    }

    override suspend fun resetLearning(preserveLocked: Boolean) = withIoDb {
        if (preserveLocked) {
            dbHelper.writableDatabase.execSQL("DELETE FROM learned_pref WHERE locked = 0")
        } else {
            dbHelper.writableDatabase.execSQL("DELETE FROM learned_pref")
        }
    }

    // Blueprint 43: Context Sensing (Places and Signals)
    override suspend fun savePlace(place: com.anchor.core.sensing.Place, signal: com.anchor.core.sensing.PlaceSignal?) = withIoDb {
        val db = dbHelper.writableDatabase
        db.beginTransaction()
        try {
            db.execSQL(
                "INSERT OR REPLACE INTO place (id, node_id, kind, label, updated_at, deleted_at, hlc) VALUES (?,?,?,?,?,?,?)",
                arrayOf<Any?>(place.id, place.nodeId, place.kind, place.label, place.updatedAt, place.deletedAt, place.hlc)
            )
            if (signal != null) {
                db.execSQL(
                    "INSERT OR REPLACE INTO place_signal (place_id, wifi_json, geo_lat, geo_lon, geo_radius_m) VALUES (?,?,?,?,?)",
                    arrayOf<Any?>(signal.placeId, signal.wifiJson, signal.geoLat, signal.geoLon, signal.geoRadiusM)
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    override suspend fun getPlaces(): List<com.anchor.core.sensing.Place> = withIoDb {
        val list = mutableListOf<com.anchor.core.sensing.Place>()
        dbHelper.readableDatabase.query(
            "SELECT id, node_id, kind, label, updated_at, deleted_at, hlc FROM place WHERE deleted_at IS NULL ORDER BY updated_at DESC"
        ).use { c ->
            while (c.moveToNext()) {
                list.add(
                    com.anchor.core.sensing.Place(
                        id = c.getString(0),
                        nodeId = if (c.isNull(1)) null else c.getString(1),
                        kind = c.getString(2),
                        label = c.getString(3),
                        updatedAt = c.getLong(4),
                        deletedAt = if (c.isNull(5)) null else c.getLong(5),
                        hlc = if (c.isNull(6)) null else c.getString(6)
                    )
                )
            }
        }
        list
    }

    override suspend fun getPlace(id: String): com.anchor.core.sensing.Place? = withIoDb {
        dbHelper.readableDatabase.query(
            "SELECT id, node_id, kind, label, updated_at, deleted_at, hlc FROM place WHERE id = ? AND deleted_at IS NULL",
            arrayOf(id)
        ).use { c ->
            if (c.moveToFirst()) {
                com.anchor.core.sensing.Place(
                    id = c.getString(0),
                    nodeId = if (c.isNull(1)) null else c.getString(1),
                    kind = c.getString(2),
                    label = c.getString(3),
                    updatedAt = c.getLong(4),
                    deletedAt = if (c.isNull(5)) null else c.getLong(5),
                    hlc = if (c.isNull(6)) null else c.getString(6)
                )
            } else null
        }
    }

    override suspend fun getPlaceSignals(): List<Pair<com.anchor.core.sensing.Place, com.anchor.core.sensing.PlaceSignal>> = withIoDb {
        val list = mutableListOf<Pair<com.anchor.core.sensing.Place, com.anchor.core.sensing.PlaceSignal>>()
        dbHelper.readableDatabase.query(
            "SELECT p.id, p.node_id, p.kind, p.label, p.updated_at, p.deleted_at, p.hlc, " +
                "s.wifi_json, s.geo_lat, s.geo_lon, s.geo_radius_m " +
                "FROM place p INNER JOIN place_signal s ON p.id = s.place_id " +
                "WHERE p.deleted_at IS NULL"
        ).use { c ->
            while (c.moveToNext()) {
                val place = com.anchor.core.sensing.Place(
                    id = c.getString(0),
                    nodeId = if (c.isNull(1)) null else c.getString(1),
                    kind = c.getString(2),
                    label = c.getString(3),
                    updatedAt = c.getLong(4),
                    deletedAt = if (c.isNull(5)) null else c.getLong(5),
                    hlc = if (c.isNull(6)) null else c.getString(6)
                )
                val signal = com.anchor.core.sensing.PlaceSignal(
                    placeId = place.id,
                    wifiJson = if (c.isNull(7)) null else c.getString(7),
                    geoLat = if (c.isNull(8)) null else c.getDouble(8),
                    geoLon = if (c.isNull(9)) null else c.getDouble(9),
                    geoRadiusM = if (c.isNull(10)) null else c.getInt(10)
                )
                list.add(place to signal)
            }
        }
        list
    }

    override suspend fun deletePlace(id: String, deletedAt: Long) = withIoDb {
        dbHelper.writableDatabase.execSQL(
            "UPDATE place SET deleted_at = ?, updated_at = ? WHERE id = ?",
            arrayOf<Any>(deletedAt, deletedAt, id)
        )
    }

    override suspend fun recordSensingEvent(event: com.anchor.core.sensing.SensingEvent) = withIoDb {
        dbHelper.writableDatabase.execSQL(
            "INSERT INTO sensing_event (id, ts_utc, axis, from_state, to_state, place_id, confidence, source, attrs) VALUES (?,?,?,?,?,?,?,?,?)",
            arrayOf<Any?>(
                event.id,
                event.tsUtc,
                event.axis,
                event.fromState,
                event.toState,
                event.placeId,
                event.confidence,
                event.source,
                event.attrs
            )
        )
    }

    override suspend fun getRecentSensingEvents(limit: Int): List<com.anchor.core.sensing.SensingEvent> = withIoDb {
        val list = mutableListOf<com.anchor.core.sensing.SensingEvent>()
        dbHelper.readableDatabase.query(
            "SELECT id, ts_utc, axis, from_state, to_state, place_id, confidence, source, attrs FROM sensing_event ORDER BY ts_utc DESC LIMIT ?",
            arrayOf(limit.coerceAtLeast(0).toString())
        ).use { c ->
            while (c.moveToNext()) {
                list.add(
                    com.anchor.core.sensing.SensingEvent(
                        id = c.getString(0),
                        tsUtc = c.getLong(1),
                        axis = c.getString(2),
                        fromState = if (c.isNull(3)) null else c.getString(3),
                        toState = c.getString(4),
                        placeId = if (c.isNull(5)) null else c.getString(5),
                        confidence = if (c.isNull(6)) null else c.getFloat(6),
                        source = if (c.isNull(7)) null else c.getString(7),
                        attrs = if (c.isNull(8)) null else c.getString(8)
                    )
                )
            }
        }
        list
    }

    override suspend fun pruneSensingEvents(olderThanUtc: Long): Int = withIoDb {
        val db = dbHelper.writableDatabase
        var count = 0
        db.query("SELECT COUNT(*) FROM sensing_event WHERE ts_utc < ?", arrayOf(olderThanUtc.toString())).use { c ->
            if (c.moveToFirst()) count = c.getInt(0)
        }
        if (count > 0) {
            db.execSQL("DELETE FROM sensing_event WHERE ts_utc < ?", arrayOf(olderThanUtc.toString()))
        }
        count
    }

    // Blueprint 44: Call Nudge (Call Events)
    override suspend fun saveCallEvent(callEvent: com.anchor.core.nudge.CallEvent) = withIoDb {
        dbHelper.writableDatabase.execSQL(
            "INSERT OR REPLACE INTO call_event (id, started_at, ended_at, duration_s, direction, contact_ref, nudge_id, state, created_at) VALUES (?,?,?,?,?,?,?,?,?)",
            arrayOf<Any?>(
                callEvent.id,
                callEvent.startedAt,
                callEvent.endedAt,
                callEvent.durationS,
                callEvent.direction,
                callEvent.contactRef,
                callEvent.nudgeId,
                callEvent.state.wire,
                callEvent.createdAt
            )
        )
    }

    override suspend fun getCallEvent(id: String): com.anchor.core.nudge.CallEvent? = withIoDb {
        dbHelper.readableDatabase.query(
            "SELECT id, started_at, ended_at, duration_s, direction, contact_ref, nudge_id, state, created_at FROM call_event WHERE id = ?",
            arrayOf(id)
        ).use { c ->
            if (c.moveToFirst()) {
                com.anchor.core.nudge.CallEvent(
                    id = c.getString(0),
                    startedAt = c.getLong(1),
                    endedAt = c.getLong(2),
                    durationS = c.getInt(3),
                    direction = if (c.isNull(4)) null else c.getString(4),
                    contactRef = if (c.isNull(5)) null else c.getString(5),
                    nudgeId = if (c.isNull(6)) null else c.getString(6),
                    state = com.anchor.core.nudge.CallNudgeState.fromWire(c.getString(7)),
                    createdAt = c.getLong(8)
                )
            } else null
        }
    }

    override suspend fun getRecentCallEvents(limit: Int): List<com.anchor.core.nudge.CallEvent> = withIoDb {
        val list = mutableListOf<com.anchor.core.nudge.CallEvent>()
        dbHelper.readableDatabase.query(
            "SELECT id, started_at, ended_at, duration_s, direction, contact_ref, nudge_id, state, created_at FROM call_event ORDER BY ended_at DESC, created_at DESC LIMIT ?",
            arrayOf(limit.coerceAtLeast(0).toString())
        ).use { c ->
            while (c.moveToNext()) {
                list.add(
                    com.anchor.core.nudge.CallEvent(
                        id = c.getString(0),
                        startedAt = c.getLong(1),
                        endedAt = c.getLong(2),
                        durationS = c.getInt(3),
                        direction = if (c.isNull(4)) null else c.getString(4),
                        contactRef = if (c.isNull(5)) null else c.getString(5),
                        nudgeId = if (c.isNull(6)) null else c.getString(6),
                        state = com.anchor.core.nudge.CallNudgeState.fromWire(c.getString(7)),
                        createdAt = c.getLong(8)
                    )
                )
            }
        }
        list
    }

    override suspend fun getCallEventsBetween(startUtc: Long, endUtc: Long): List<com.anchor.core.nudge.CallEvent> = withIoDb {
        val list = mutableListOf<com.anchor.core.nudge.CallEvent>()
        dbHelper.readableDatabase.query(
            "SELECT id, started_at, ended_at, duration_s, direction, contact_ref, nudge_id, state, created_at FROM call_event WHERE ended_at BETWEEN ? AND ? ORDER BY ended_at ASC",
            arrayOf(startUtc.toString(), endUtc.toString())
        ).use { c ->
            while (c.moveToNext()) {
                list.add(
                    com.anchor.core.nudge.CallEvent(
                        id = c.getString(0),
                        startedAt = c.getLong(1),
                        endedAt = c.getLong(2),
                        durationS = c.getInt(3),
                        direction = if (c.isNull(4)) null else c.getString(4),
                        contactRef = if (c.isNull(5)) null else c.getString(5),
                        nudgeId = if (c.isNull(6)) null else c.getString(6),
                        state = com.anchor.core.nudge.CallNudgeState.fromWire(c.getString(7)),
                        createdAt = c.getLong(8)
                    )
                )
            }
        }
        list
    }

    override suspend fun updateCallEventState(id: String, state: com.anchor.core.nudge.CallNudgeState, nudgeId: String?) = withIoDb {
        if (nudgeId != null) {
            dbHelper.writableDatabase.execSQL(
                "UPDATE call_event SET state = ?, nudge_id = ? WHERE id = ?",
                arrayOf(state.wire, nudgeId, id)
            )
        } else {
            dbHelper.writableDatabase.execSQL(
                "UPDATE call_event SET state = ? WHERE id = ?",
                arrayOf(state.wire, id)
            )
        }
    }

    override suspend fun pruneCallEvents(olderThanUtc: Long): Int = withIoDb {
        val db = dbHelper.writableDatabase
        var count = 0
        db.query("SELECT COUNT(*) FROM call_event WHERE ended_at < ?", arrayOf(olderThanUtc.toString())).use { c ->
            if (c.moveToFirst()) count = c.getInt(0)
        }
        if (count > 0) {
            db.execSQL("DELETE FROM call_event WHERE ended_at < ?", arrayOf(olderThanUtc.toString()))
        }
        count
    }

    // Blueprint 60: Voice (Voice Jobs & Model Assets)
    override suspend fun saveVoiceJob(job: com.anchor.core.voice.VoiceJob) = withIoDb {
        dbHelper.writableDatabase.execSQL(
            "INSERT OR REPLACE INTO voice_job (id, kind, state, msg_id, audio_path, target_ref, attempts, created_at, updated_at, error) VALUES (?,?,?,?,?,?,?,?,?,?)",
            arrayOf<Any?>(job.id, job.kind.wire, job.state.wire, job.msgId, job.audioPath, job.targetRef, job.attempts, job.createdAt, job.updatedAt, job.error)
        )
    }

    override suspend fun getVoiceJob(id: String): com.anchor.core.voice.VoiceJob? = withIoDb {
        dbHelper.readableDatabase.query(
            "SELECT id, kind, state, msg_id, audio_path, target_ref, attempts, created_at, updated_at, error FROM voice_job WHERE id = ?",
            arrayOf(id)
        ).use { c ->
            if (c.moveToFirst()) {
                com.anchor.core.voice.VoiceJob(
                    id = c.getString(0),
                    kind = com.anchor.core.voice.VoiceJobKind.fromWire(c.getString(1)),
                    state = com.anchor.core.voice.VoiceJobState.fromWire(c.getString(2)),
                    msgId = if (c.isNull(3)) null else c.getString(3),
                    audioPath = if (c.isNull(4)) null else c.getString(4),
                    targetRef = if (c.isNull(5)) null else c.getString(5),
                    attempts = c.getInt(6),
                    createdAt = c.getLong(7),
                    updatedAt = c.getLong(8),
                    error = if (c.isNull(9)) null else c.getString(9)
                )
            } else null
        }
    }

    override suspend fun getRecentVoiceJobs(limit: Int): List<com.anchor.core.voice.VoiceJob> = withIoDb {
        val list = mutableListOf<com.anchor.core.voice.VoiceJob>()
        dbHelper.readableDatabase.query(
            "SELECT id, kind, state, msg_id, audio_path, target_ref, attempts, created_at, updated_at, error FROM voice_job ORDER BY updated_at DESC LIMIT ?",
            arrayOf(limit.coerceAtLeast(0).toString())
        ).use { c ->
            while (c.moveToNext()) {
                list.add(
                    com.anchor.core.voice.VoiceJob(
                        id = c.getString(0),
                        kind = com.anchor.core.voice.VoiceJobKind.fromWire(c.getString(1)),
                        state = com.anchor.core.voice.VoiceJobState.fromWire(c.getString(2)),
                        msgId = if (c.isNull(3)) null else c.getString(3),
                        audioPath = if (c.isNull(4)) null else c.getString(4),
                        targetRef = if (c.isNull(5)) null else c.getString(5),
                        attempts = c.getInt(6),
                        createdAt = c.getLong(7),
                        updatedAt = c.getLong(8),
                        error = if (c.isNull(9)) null else c.getString(9)
                    )
                )
            }
        }
        list
    }

    override suspend fun updateVoiceJobState(id: String, state: com.anchor.core.voice.VoiceJobState, error: String?) = withIoDb {
        val now = System.currentTimeMillis()
        if (error != null) {
            dbHelper.writableDatabase.execSQL(
                "UPDATE voice_job SET state = ?, error = ?, updated_at = ? WHERE id = ?",
                arrayOf<Any?>(state.wire, error, now, id)
            )
        } else {
            dbHelper.writableDatabase.execSQL(
                "UPDATE voice_job SET state = ?, updated_at = ? WHERE id = ?",
                arrayOf<Any?>(state.wire, now, id)
            )
        }
    }

    override suspend fun saveModelAsset(asset: com.anchor.core.voice.ModelAsset) = withIoDb {
        dbHelper.writableDatabase.execSQL(
            "INSERT OR REPLACE INTO model_asset (id, kind, name, version, size_bytes, sha256, license_ref, path, state, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?)",
            arrayOf<Any?>(asset.id, asset.kind.wire, asset.name, asset.version, asset.sizeBytes, asset.sha256, asset.licenseRef, asset.path, asset.state.wire, asset.updatedAt)
        )
    }

    override suspend fun getModelAssets(): List<com.anchor.core.voice.ModelAsset> = withIoDb {
        val list = mutableListOf<com.anchor.core.voice.ModelAsset>()
        dbHelper.readableDatabase.query(
            "SELECT id, kind, name, version, size_bytes, sha256, license_ref, path, state, updated_at FROM model_asset ORDER BY name ASC"
        ).use { c ->
            while (c.moveToNext()) {
                list.add(
                    com.anchor.core.voice.ModelAsset(
                        id = c.getString(0),
                        kind = com.anchor.core.voice.ModelAssetKind.fromWire(c.getString(1)),
                        name = c.getString(2),
                        version = if (c.isNull(3)) null else c.getString(3),
                        sizeBytes = if (c.isNull(4)) null else c.getLong(4),
                        sha256 = if (c.isNull(5)) null else c.getString(5),
                        licenseRef = if (c.isNull(6)) null else c.getString(6),
                        path = if (c.isNull(7)) null else c.getString(7),
                        state = com.anchor.core.voice.ModelAssetState.fromWire(c.getString(8)),
                        updatedAt = c.getLong(9)
                    )
                )
            }
        }
        list
    }

    override suspend fun getModelAsset(id: String): com.anchor.core.voice.ModelAsset? = withIoDb {
        dbHelper.readableDatabase.query(
            "SELECT id, kind, name, version, size_bytes, sha256, license_ref, path, state, updated_at FROM model_asset WHERE id = ?",
            arrayOf(id)
        ).use { c ->
            if (c.moveToFirst()) {
                com.anchor.core.voice.ModelAsset(
                    id = c.getString(0),
                    kind = com.anchor.core.voice.ModelAssetKind.fromWire(c.getString(1)),
                    name = c.getString(2),
                    version = if (c.isNull(3)) null else c.getString(3),
                    sizeBytes = if (c.isNull(4)) null else c.getLong(4),
                    sha256 = if (c.isNull(5)) null else c.getString(5),
                    licenseRef = if (c.isNull(6)) null else c.getString(6),
                    path = if (c.isNull(7)) null else c.getString(7),
                    state = com.anchor.core.voice.ModelAssetState.fromWire(c.getString(8)),
                    updatedAt = c.getLong(9)
                )
            } else null
        }
    }

    override suspend fun pruneOperationalLogs(now: Long): Int = withIoDb {
        val diagCutoff = now - 7 * 86_400_000L
        val fireCutoff = now - 90 * 86_400_000L
        val outboxCutoff = now - 7 * 86_400_000L

        var pruned = 0
        val db = dbHelper.writableDatabase
        db.beginTransaction()
        try {
            val stmt1 = db.compileStatement("DELETE FROM diag_log WHERE ts < ?")
            stmt1.bindLong(1, diagCutoff)
            pruned += stmt1.executeUpdateDelete()

            val stmt2 = db.compileStatement("DELETE FROM reminder_fire WHERE fired_at < ?")
            stmt2.bindLong(1, fireCutoff)
            pruned += stmt2.executeUpdateDelete()

            val stmt3 = db.compileStatement("DELETE FROM outbox WHERE status IN ('done', 'dropped') AND updated_at < ?")
            stmt3.bindLong(1, outboxCutoff)
            pruned += stmt3.executeUpdateDelete()

            db.setTransactionSuccessful()
        } catch (_: Exception) {
        } finally {
            db.endTransaction()
        }
        pruned
    }

    override suspend fun rebuildFtsIndexes(): Unit = withIoDb {
        val db = dbHelper.writableDatabase
        try {
            db.execSQL("INSERT INTO node_fts(node_fts) VALUES('rebuild')")
            db.execSQL("INSERT INTO soul_fts(soul_fts) VALUES('rebuild')")
            db.execSQL("INSERT INTO msg_chunk_fts(msg_chunk_fts) VALUES('rebuild')")
        } catch (t: Throwable) {
            android.util.Log.w("AndroidStorage", "Failed to rebuild FTS indexes: ${t.message}")
        }
    }

    // Blueprint 61 §4: Ambient Listening & Blueprint 21 §3.7: Day Digest Storage
    override suspend fun saveVoiceprint(voiceprint: com.anchor.core.ambient.Voiceprint) = withIoDb {
        dbHelper.writableDatabase.execSQL(
            "INSERT OR REPLACE INTO voiceprint (id, embedding, model_ref, created_at) VALUES (?, ?, ?, ?)",
            arrayOf<Any?>(voiceprint.id, voiceprint.embedding, voiceprint.modelRef, voiceprint.createdAt)
        )
    }

    override suspend fun getVoiceprint(): com.anchor.core.ambient.Voiceprint? = withIoDb {
        dbHelper.readableDatabase.query(
            "SELECT id, embedding, model_ref, created_at FROM voiceprint LIMIT 1"
        ).use { c ->
            if (c.moveToFirst()) {
                com.anchor.core.ambient.Voiceprint(
                    id = c.getString(0),
                    embedding = c.getBlob(1),
                    modelRef = c.getString(2),
                    createdAt = c.getLong(3)
                )
            } else null
        }
    }

    override suspend fun deleteVoiceprint() = withIoDb {
        dbHelper.writableDatabase.execSQL("DELETE FROM voiceprint")
    }

    override suspend fun saveAmbientSession(session: com.anchor.core.ambient.AmbientSession) = withIoDb {
        dbHelper.writableDatabase.execSQL(
            "INSERT OR REPLACE INTO ambient_session (id, started_at, ended_at, end_reason, listen_s, speech_s, uploaded_s, segments) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            arrayOf<Any?>(session.id, session.startedAt, session.endedAt, session.endReason, session.listenSeconds, session.speechSeconds, session.uploadedSeconds, session.segmentsCount)
        )
    }

    override suspend fun getRecentAmbientSessions(limit: Int): List<com.anchor.core.ambient.AmbientSession> = withIoDb {
        val list = mutableListOf<com.anchor.core.ambient.AmbientSession>()
        dbHelper.readableDatabase.query(
            "SELECT id, started_at, ended_at, end_reason, listen_s, speech_s, uploaded_s, segments FROM ambient_session ORDER BY started_at DESC LIMIT ?",
            arrayOf(limit.toString())
        ).use { c ->
            while (c.moveToNext()) {
                list.add(
                    com.anchor.core.ambient.AmbientSession(
                        id = c.getString(0),
                        startedAt = c.getLong(1),
                        endedAt = if (c.isNull(2)) null else c.getLong(2),
                        endReason = if (c.isNull(3)) null else c.getString(3),
                        listenSeconds = c.getInt(4),
                        speechSeconds = c.getInt(5),
                        uploadedSeconds = c.getInt(6),
                        segmentsCount = c.getInt(7)
                    )
                )
            }
        }
        list
    }

    override suspend fun saveAmbientWindow(window: com.anchor.core.ambient.AmbientWindow) = withIoDb {
        dbHelper.writableDatabase.execSQL(
            "INSERT OR REPLACE INTO ambient_window (id, session_id, started_at, ended_at, state, gate_opened) VALUES (?, ?, ?, ?, ?, ?)",
            arrayOf<Any?>(window.id, window.sessionId, window.startedAt, window.endedAt, window.state, if (window.gateOpened) 1 else 0)
        )
    }

    override suspend fun getAmbientWindows(sessionId: String): List<com.anchor.core.ambient.AmbientWindow> = withIoDb {
        val list = mutableListOf<com.anchor.core.ambient.AmbientWindow>()
        dbHelper.readableDatabase.query(
            "SELECT id, session_id, started_at, ended_at, state, gate_opened FROM ambient_window WHERE session_id = ? ORDER BY started_at ASC",
            arrayOf(sessionId)
        ).use { c ->
            while (c.moveToNext()) {
                list.add(
                    com.anchor.core.ambient.AmbientWindow(
                        id = c.getString(0),
                        sessionId = c.getString(1),
                        startedAt = c.getLong(2),
                        endedAt = c.getLong(3),
                        state = c.getString(4),
                        gateOpened = c.getInt(5) == 1
                    )
                )
            }
        }
        list
    }

    override suspend fun saveAmbientSegment(segment: com.anchor.core.ambient.AmbientSegment) = withIoDb {
        dbHelper.writableDatabase.execSQL(
            "INSERT OR REPLACE INTO ambient_segment (id, window_id, t_start, t_end, label, score, short, text, stt_ref, expires_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            arrayOf<Any?>(segment.id, segment.windowId, segment.tStart, segment.tEnd, segment.label, segment.score, if (segment.isShort) 1 else 0, segment.text, segment.sttRef, segment.expiresAt)
        )
    }

    override suspend fun getAmbientSegments(windowId: String): List<com.anchor.core.ambient.AmbientSegment> = withIoDb {
        val list = mutableListOf<com.anchor.core.ambient.AmbientSegment>()
        dbHelper.readableDatabase.query(
            "SELECT id, window_id, t_start, t_end, label, score, short, text, stt_ref, expires_at FROM ambient_segment WHERE window_id = ? ORDER BY t_start ASC",
            arrayOf(windowId)
        ).use { c ->
            while (c.moveToNext()) {
                list.add(
                    com.anchor.core.ambient.AmbientSegment(
                        id = c.getString(0),
                        windowId = c.getString(1),
                        tStart = c.getLong(2),
                        tEnd = c.getLong(3),
                        label = c.getString(4),
                        score = if (c.isNull(5)) null else c.getFloat(5),
                        isShort = c.getInt(6) == 1,
                        text = if (c.isNull(7)) null else c.getString(7),
                        sttRef = if (c.isNull(8)) null else c.getString(8),
                        expiresAt = c.getLong(9)
                    )
                )
            }
        }
        list
    }

    override suspend fun saveAmbientReport(report: com.anchor.core.ambient.AmbientReport) = withIoDb {
        dbHelper.writableDatabase.execSQL(
            "INSERT OR REPLACE INTO ambient_report (id, day_key, generated_at, state) VALUES (?, ?, ?, ?)",
            arrayOf<Any?>(report.id, report.dayKey, report.generatedAt, report.state)
        )
    }

    override suspend fun getAmbientReport(id: String): com.anchor.core.ambient.AmbientReport? = withIoDb {
        dbHelper.readableDatabase.query(
            "SELECT id, day_key, generated_at, state FROM ambient_report WHERE id = ?",
            arrayOf(id)
        ).use { c ->
            if (c.moveToFirst()) {
                com.anchor.core.ambient.AmbientReport(
                    id = c.getString(0),
                    dayKey = c.getString(1),
                    generatedAt = c.getLong(2),
                    state = c.getString(3)
                )
            } else null
        }
    }

    override suspend fun getLatestAmbientReport(): com.anchor.core.ambient.AmbientReport? = withIoDb {
        dbHelper.readableDatabase.query(
            "SELECT id, day_key, generated_at, state FROM ambient_report ORDER BY generated_at DESC LIMIT 1"
        ).use { c ->
            if (c.moveToFirst()) {
                com.anchor.core.ambient.AmbientReport(
                    id = c.getString(0),
                    dayKey = c.getString(1),
                    generatedAt = c.getLong(2),
                    state = c.getString(3)
                )
            } else null
        }
    }

    override suspend fun updateAmbientReportState(id: String, state: String) = withIoDb {
        dbHelper.writableDatabase.execSQL(
            "UPDATE ambient_report SET state = ? WHERE id = ?",
            arrayOf<Any?>(state, id)
        )
    }

    override suspend fun saveAmbientReportItem(item: com.anchor.core.ambient.AmbientReportItem) = withIoDb {
        dbHelper.writableDatabase.execSQL(
            "INSERT OR REPLACE INTO ambient_report_item (id, report_id, window_id, kind, summary, node_id, flags, state, reviewed_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            arrayOf<Any?>(item.id, item.reportId, item.windowId, item.kind, item.summary, item.nodeId, item.flags, item.state, item.reviewedAt)
        )
    }

    override suspend fun getAmbientReportItems(reportId: String): List<com.anchor.core.ambient.AmbientReportItem> = withIoDb {
        val list = mutableListOf<com.anchor.core.ambient.AmbientReportItem>()
        dbHelper.readableDatabase.query(
            "SELECT id, report_id, window_id, kind, summary, node_id, flags, state, reviewed_at FROM ambient_report_item WHERE report_id = ?",
            arrayOf(reportId)
        ).use { c ->
            while (c.moveToNext()) {
                list.add(
                    com.anchor.core.ambient.AmbientReportItem(
                        id = c.getString(0),
                        reportId = c.getString(1),
                        windowId = if (c.isNull(2)) null else c.getString(2),
                        kind = c.getString(3),
                        summary = c.getString(4),
                        nodeId = if (c.isNull(5)) null else c.getString(5),
                        flags = if (c.isNull(6)) null else c.getString(6),
                        state = c.getString(7),
                        reviewedAt = if (c.isNull(8)) null else c.getLong(8)
                    )
                )
            }
        }
        list
    }

    override suspend fun updateAmbientReportItemState(id: String, state: String, reviewedAt: Long?) = withIoDb {
        if (reviewedAt != null) {
            dbHelper.writableDatabase.execSQL(
                "UPDATE ambient_report_item SET state = ?, reviewed_at = ? WHERE id = ?",
                arrayOf<Any?>(state, reviewedAt, id)
            )
        } else {
            dbHelper.writableDatabase.execSQL(
                "UPDATE ambient_report_item SET state = ? WHERE id = ?",
                arrayOf<Any?>(state, id)
            )
        }
    }

    override suspend fun pruneAmbientStaging(now: Long): Int = withIoDb {
        val db = dbHelper.writableDatabase
        val cutoff = now - 7 * 86_400_000L
        // Expired, never-reviewed ambient proposals. The node id lives in ambient_report_item.node_id.
        val expired = "SELECT id FROM node WHERE origin = 'ambient' AND status = 'proposed' AND (created_at < ? OR id IN " +
            "(SELECT node_id FROM ambient_report_item WHERE node_id IS NOT NULL AND kind = 'proposal' AND state = 'pending' " +
            "AND report_id IN (SELECT id FROM ambient_report WHERE generated_at < ?)))"
        var pruned = 0
        var failure: Exception? = null
        db.beginTransaction()
        try {
            pruned += db.compileStatement("DELETE FROM ambient_segment WHERE expires_at < ?").use {
                it.bindLong(1, now); it.executeUpdateDelete()
            }
            // raw_event is append-only: scrub payloads (allowed by trg_raw_evt_no_update), never DELETE.
            db.execSQL("UPDATE raw_event SET before_json = NULL, after_json = NULL WHERE target_type = 'node' AND target_id IN ($expired)", arrayOf<Any?>(cutoff, cutoff))
            db.execSQL("DELETE FROM embedding WHERE owner_type = 'node' AND owner_id IN ($expired)", arrayOf<Any?>(cutoff, cutoff))
            pruned += db.compileStatement("DELETE FROM node WHERE id IN ($expired)").use {
                it.bindLong(1, cutoff); it.bindLong(2, cutoff); it.executeUpdateDelete()
            }
            pruned += db.compileStatement(
                "DELETE FROM ambient_report_item WHERE kind = 'proposal' AND state = 'pending' AND report_id IN (SELECT id FROM ambient_report WHERE generated_at < ?)"
            ).use { it.bindLong(1, cutoff); it.executeUpdateDelete() }
            db.setTransactionSuccessful()
        } catch (e: Exception) {
            failure = e
        } finally {
            db.endTransaction()
        }
        // Logged AFTER endTransaction: a diag row written inside the failed transaction would be rolled back too.
        failure?.let { logDiagError("pruneAmbientStaging failed: ${it.javaClass.simpleName}: ${it.message}"); throw it }
        pruned
    }

    override suspend fun deleteAllAmbientData(): Int = withIoDb {
        val db = dbHelper.writableDatabase
        val proposed = "SELECT id FROM node WHERE origin = 'ambient' AND status = 'proposed'"
        var total = 0
        var failure: Exception? = null
        db.beginTransaction()
        try {
            db.execSQL(
                "UPDATE raw_event SET before_json = NULL, after_json = NULL WHERE (target_type = 'node' AND target_id IN ($proposed)) " +
                    "OR target_type IN ('ambient_report', 'ambient_report_item', 'voiceprint')"
            )
            db.execSQL("DELETE FROM embedding WHERE owner_type = 'node' AND owner_id IN ($proposed)")
            for (sql in listOf(
                "DELETE FROM node WHERE origin = 'ambient' AND status = 'proposed'",
                "DELETE FROM ambient_segment", "DELETE FROM ambient_report_item", "DELETE FROM ambient_report",
                "DELETE FROM ambient_window", "DELETE FROM ambient_session", "DELETE FROM voiceprint"
            )) total += db.compileStatement(sql).use { it.executeUpdateDelete() }
            db.setTransactionSuccessful()
        } catch (e: Exception) {
            failure = e
        } finally {
            db.endTransaction()
        }
        failure?.let { logDiagError("deleteAllAmbientData failed: ${it.javaClass.simpleName}"); throw it }
        // secure_delete is ON; also fold the WAL so freed pages are not left in the -wal file.
        runCatching { db.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() } }
        total
    }

    override suspend fun saveDayDigest(digest: com.anchor.core.consolidation.DayDigest) = withIoDb {
        dbHelper.writableDatabase.execSQL(
            "INSERT OR REPLACE INTO day_digest (day_key, completed_count, open_count, summary_text, open_loops, wins, generated_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
            arrayOf<Any?>(digest.dayKey, digest.completedCount, digest.openCount, digest.summaryText, digest.openLoops.joinToString("\n"), digest.wins.joinToString("\n"), digest.generatedAt)
        )
    }

    override suspend fun getDayDigest(dayKey: String): com.anchor.core.consolidation.DayDigest? = withIoDb {
        dbHelper.readableDatabase.query(
            "SELECT day_key, completed_count, open_count, summary_text, open_loops, wins, generated_at FROM day_digest WHERE day_key = ?",
            arrayOf(dayKey)
        ).use { c ->
            if (c.moveToFirst()) {
                val openLoopsStr = if (c.isNull(4)) "" else c.getString(4)
                val winsStr = if (c.isNull(5)) "" else c.getString(5)
                com.anchor.core.consolidation.DayDigest(
                    dayKey = c.getString(0),
                    completedCount = c.getInt(1),
                    openCount = c.getInt(2),
                    summaryText = c.getString(3),
                    openLoops = if (openLoopsStr.isBlank()) emptyList() else openLoopsStr.split("\n"),
                    wins = if (winsStr.isBlank()) emptyList() else winsStr.split("\n"),
                    generatedAt = c.getLong(6)
                )
            } else null
        }
    }

    override suspend fun getNodesCreatedBetween(startMs: Long, endMs: Long, limit: Int): List<Node> = withIoDb {
        val sql = "SELECT $NODE_COLS FROM node WHERE created_at >= ? AND created_at <= ? AND deleted_at IS NULL ORDER BY created_at ASC LIMIT ?"
        queryNodes(sql, arrayOf(startMs.toString(), endMs.toString(), limit.toString()))
    }

    override suspend fun getNodesClosedBetween(startMs: Long, endMs: Long, limit: Int): List<Node> = withIoDb {
        val sql = "SELECT $NODE_COLS FROM node WHERE status IN ('done', 'completed') AND ((closed_at >= ? AND closed_at <= ?) OR (closed_at IS NULL AND updated_at >= ? AND updated_at <= ?)) AND deleted_at IS NULL ORDER BY COALESCE(closed_at, updated_at) ASC LIMIT ?"
        queryNodes(sql, arrayOf(startMs.toString(), endMs.toString(), startMs.toString(), endMs.toString(), limit.toString()))
    }

    override suspend fun getCancelledEventsBetween(startMs: Long, endMs: Long, limit: Int): List<RawEvent> = withIoDb {
        val list = mutableListOf<RawEvent>()
        val sql = "SELECT op_id, op_type, target_type, target_id, undo_of, before_json, after_json, ts_utc, cause_msg_id FROM raw_event WHERE ts_utc >= ? AND ts_utc <= ? AND op_type IN ('CANCEL', 'DELETE_NODE') AND undo_of IS NULL ORDER BY ts_utc ASC LIMIT ?"
        dbHelper.readableDatabase.query(sql, arrayOf(startMs.toString(), endMs.toString(), limit.toString())).use { cursor ->
            while (cursor.moveToNext()) {
                list.add(
                    RawEvent(
                        opId = cursor.getString(0),
                        opType = cursor.getString(1),
                        targetType = cursor.getString(2),
                        targetId = cursor.getString(3),
                        undoOf = if (cursor.isNull(4)) null else cursor.getString(4),
                        beforeJson = if (cursor.isNull(5)) null else cursor.getString(5),
                        afterJson = if (cursor.isNull(6)) null else cursor.getString(6),
                        tsUtc = cursor.getLong(7),
                        causeMsgId = if (cursor.isNull(8)) null else cursor.getString(8)
                    )
                )
            }
        }
        list
    }

    override suspend fun getGoalNodes(): List<Node> = withIoDb {
        val sql = "SELECT $NODE_COLS FROM node WHERE type = 'goal' AND deleted_at IS NULL AND status != 'archived' ORDER BY created_at ASC"
        queryNodes(sql, emptyArray())
    }

    override suspend fun getHabitNodes(): List<Node> = withIoDb {
        val sql = "SELECT $NODE_COLS FROM node WHERE (type = 'habit' OR recurrence_rule IS NOT NULL) AND deleted_at IS NULL AND status != 'archived' ORDER BY streak_current DESC"
        queryNodes(sql, emptyArray())
    }

    override suspend fun getActionTimestampsBetween(startMs: Long, endMs: Long, limit: Int): List<Long> = withIoDb {
        val timestamps = mutableListOf<Long>()
        val msgSql = "SELECT ts_utc FROM raw_message WHERE ts_utc >= ? AND ts_utc <= ? AND role = 'user' AND redacted_at IS NULL ORDER BY ts_utc ASC LIMIT ?"
        dbHelper.readableDatabase.query(msgSql, arrayOf(startMs.toString(), endMs.toString(), limit.toString())).use { c ->
            while (c.moveToNext()) {
                timestamps.add(c.getLong(0))
            }
        }
        val evSql = "SELECT ts_utc FROM raw_event WHERE ts_utc >= ? AND ts_utc <= ? AND actor = 'user' ORDER BY ts_utc ASC LIMIT ?"
        dbHelper.readableDatabase.query(evSql, arrayOf(startMs.toString(), endMs.toString(), limit.toString())).use { c ->
            while (c.moveToNext()) {
                timestamps.add(c.getLong(0))
            }
        }
        timestamps.sorted().take(limit)
    }

    override suspend fun getFullInsightsReport(
        startMs: Long,
        endMs: Long,
        nowMs: Long,
        includeMood: Boolean
    ): com.anchor.core.insights.FullInsightsReport = withIoDb {
        val engine = com.anchor.core.insights.InsightsEngine(getEffectiveDayRule(nowMs))
        val created = getNodesCreatedBetween(startMs, endMs)
        val closed = getNodesClosedBetween(startMs, endMs)
        val cancelled = getCancelledEventsBetween(startMs, endMs)
        val openCounts = getActiveNodeCounts(nowMs)
        val weekly = engine.buildWeeklyReview(startMs, endMs, created, closed, cancelled, openCounts.totalOpen)
        val habits = getHabitNodes()
        val monthly = engine.buildMonthlyReview(startMs, endMs, isMonthToDate = false, created, closed, cancelled, openCounts.totalOpen, habits)
        val goals = getGoalNodes()
        val edges = mutableListOf<com.anchor.core.models.Edge>()
        for (g in goals) {
            edges.addAll(getEdgesForDestination(g.id))
            edges.addAll(getEdgesForSource(g.id))
        }
        val subtaskNodeIds = edges.map { it.srcId }.toSet() + edges.map { it.dstId }.toSet()
        val allMap = mutableMapOf<String, Node>()
        for (id in subtaskNodeIds) {
            getNode(id)?.let { allMap[id] = it }
        }
        for (g in goals) {
            allMap[g.id] = g
        }
        val goalProgress = engine.buildGoalProgress(goals, edges, allMap)
        val habitStreaks = engine.buildHabitStreaks(habits)
        val actionTs = getActionTimestampsBetween(startMs, endMs)
        val heatStrip = engine.buildActivityHeatStrip(actionTs)
        val moodSignals = if (includeMood) getSignals("mood", sinceUtc = startMs, limit = 1000) else emptyList()
        val moodTrends = engine.buildMoodTrends(moodSignals, optInEnabled = includeMood)

        com.anchor.core.insights.FullInsightsReport(
            generatedAt = nowMs,
            weeklyReview = weekly,
            monthlyReview = monthly,
            goalProgress = goalProgress,
            habitStreaks = habitStreaks,
            activityHeatStrip = heatStrip,
            moodTrends = moodTrends
        )
    }

    override suspend fun saveDayBlock(block: com.anchor.core.models.DayBlock) = withIoDb {
        dbHelper.writableDatabase.execSQL(
            "INSERT OR REPLACE INTO day_block (day_key, idx, start_msg_id, start_ts, end_ts, msg_count, part_of_day) VALUES (?, ?, ?, ?, ?, ?, ?)",
            arrayOf<Any?>(block.dayKey, block.idx, block.startMsgId, block.startTs, block.endTs, block.msgCount, block.partOfDay)
        )
    }

    override suspend fun getDayBlocks(dayKey: String): List<com.anchor.core.models.DayBlock> = withIoDb {
        val list = mutableListOf<com.anchor.core.models.DayBlock>()
        dbHelper.readableDatabase.query(
            "SELECT day_key, idx, start_msg_id, start_ts, end_ts, msg_count, part_of_day FROM day_block WHERE day_key = ? ORDER BY idx ASC",
            arrayOf(dayKey)
        ).use { cursor ->
            while (cursor.moveToNext()) {
                list.add(
                    com.anchor.core.models.DayBlock(
                        dayKey = cursor.getString(0),
                        idx = cursor.getInt(1),
                        startMsgId = cursor.getString(2),
                        startTs = cursor.getLong(3),
                        endTs = cursor.getLong(4),
                        msgCount = cursor.getInt(5),
                        partOfDay = cursor.getString(6)
                    )
                )
            }
        }
        list
    }

    override suspend fun saveExportLog(record: com.anchor.core.models.ExportLogRecord) = withIoDb {
        dbHelper.writableDatabase.execSQL(
            "INSERT OR REPLACE INTO export_log (id, ts, kind, options_json, counts_json, bytes, verified, note) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            arrayOf<Any?>(record.id, record.ts, record.kind, record.optionsJson, record.countsJson, record.bytes, if (record.verified) 1 else 0, record.note)
        )
    }

    override suspend fun getRecentExportLogs(limit: Int): List<com.anchor.core.models.ExportLogRecord> = withIoDb {
        val list = mutableListOf<com.anchor.core.models.ExportLogRecord>()
        dbHelper.readableDatabase.query(
            "SELECT id, ts, kind, options_json, counts_json, bytes, verified, note FROM export_log ORDER BY ts DESC LIMIT ?",
            arrayOf(limit.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                list.add(
                    com.anchor.core.models.ExportLogRecord(
                        id = cursor.getString(0),
                        ts = cursor.getLong(1),
                        kind = cursor.getString(2),
                        optionsJson = cursor.getString(3),
                        countsJson = cursor.getString(4),
                        bytes = if (cursor.isNull(5)) null else cursor.getLong(5),
                        verified = cursor.getInt(6) == 1,
                        note = cursor.getString(7)
                    )
                )
            }
        }
        list
    }

    override suspend fun saveCheckinState(record: com.anchor.core.models.CheckinStateRecord) = withIoDb {
        dbHelper.writableDatabase.execSQL(
            "INSERT OR REPLACE INTO checkin_state (kind, day_key, status, msg_id, scheduled_for, delivered_at) VALUES (?, ?, ?, ?, ?, ?)",
            arrayOf<Any?>(record.kind, record.dayKey, record.status, record.msgId, record.scheduledFor, record.deliveredAt)
        )
    }

    override suspend fun getCheckinState(kind: String, dayKey: String): com.anchor.core.models.CheckinStateRecord? = withIoDb {
        dbHelper.readableDatabase.query(
            "SELECT kind, day_key, status, msg_id, scheduled_for, delivered_at FROM checkin_state WHERE kind = ? AND day_key = ? LIMIT 1",
            arrayOf(kind, dayKey)
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                com.anchor.core.models.CheckinStateRecord(
                    kind = cursor.getString(0),
                    dayKey = cursor.getString(1),
                    status = cursor.getString(2),
                    msgId = cursor.getString(3),
                    scheduledFor = if (cursor.isNull(4)) null else cursor.getLong(4),
                    deliveredAt = if (cursor.isNull(5)) null else cursor.getLong(5)
                )
            } else null
        }
    }

    override suspend fun saveNudgeLog(record: com.anchor.core.models.NudgeLogRecord) = withIoDb {
        dbHelper.writableDatabase.execSQL(
            "INSERT OR REPLACE INTO nudge_log (id, ts_utc, trigger_type, subject_refs, score, threshold, gates_json, decision, delivery, msg_id, text_source, outcome, latency_ms, reason_json) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            arrayOf<Any?>(record.id, record.tsUtc, record.triggerType, record.subjectRefs, record.score, record.threshold, record.gatesJson, record.decision, record.delivery, record.msgId, record.textSource, record.outcome, record.latencyMs, record.reasonJson)
        )
    }

    override suspend fun getRecentNudgeLogs(limit: Int): List<com.anchor.core.models.NudgeLogRecord> = withIoDb {
        val list = mutableListOf<com.anchor.core.models.NudgeLogRecord>()
        dbHelper.readableDatabase.query(
            "SELECT id, ts_utc, trigger_type, subject_refs, score, threshold, gates_json, decision, delivery, msg_id, text_source, outcome, latency_ms, reason_json FROM nudge_log ORDER BY ts_utc DESC LIMIT ?",
            arrayOf(limit.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                list.add(
                    com.anchor.core.models.NudgeLogRecord(
                        id = cursor.getString(0),
                        tsUtc = cursor.getLong(1),
                        triggerType = cursor.getString(2),
                        subjectRefs = cursor.getString(3),
                        score = if (cursor.isNull(4)) null else cursor.getDouble(4),
                        threshold = if (cursor.isNull(5)) null else cursor.getDouble(5),
                        gatesJson = cursor.getString(6),
                        decision = cursor.getString(7),
                        delivery = cursor.getString(8),
                        msgId = cursor.getString(9),
                        textSource = cursor.getString(10),
                        outcome = cursor.getString(11),
                        latencyMs = if (cursor.isNull(12)) null else cursor.getLong(12),
                        reasonJson = cursor.getString(13)
                    )
                )
            }
        }
        list
    }

    // --- Companion Brain / v4: Topic Notes ---
    override suspend fun saveTopicNote(note: com.anchor.core.models.TopicNote) = withIoDb {
        val db = dbHelper.writableDatabase
        val pinnedVal = if (note.pinned) 1 else 0
        db.execSQL(
            "INSERT OR REPLACE INTO topic_note (id, topic, content, bullets_json, pinned, last_edited_at, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
            arrayOf<Any?>(note.id, note.topic, note.content, note.bulletsJson, pinnedVal, note.lastEditedAt, note.createdAt)
        )
    }

    override suspend fun getTopicNote(id: String): com.anchor.core.models.TopicNote? = withIoDb {
        dbHelper.readableDatabase.query(
            "SELECT id, topic, content, bullets_json, pinned, last_edited_at, created_at FROM topic_note WHERE id = ?",
            arrayOf(id)
        ).use { cursor ->
            if (cursor.moveToNext()) {
                com.anchor.core.models.TopicNote(
                    id = cursor.getString(0),
                    topic = cursor.getString(1),
                    content = cursor.getString(2),
                    bulletsJson = cursor.getString(3),
                    pinned = cursor.getInt(4) == 1,
                    lastEditedAt = cursor.getLong(5),
                    createdAt = cursor.getLong(6)
                )
            } else null
        }
    }

    override suspend fun getTopicNoteByTopic(topic: String): com.anchor.core.models.TopicNote? = withIoDb {
        dbHelper.readableDatabase.query(
            "SELECT id, topic, content, bullets_json, pinned, last_edited_at, created_at FROM topic_note WHERE topic = ? COLLATE NOCASE LIMIT 1",
            arrayOf(topic.trim())
        ).use { cursor ->
            if (cursor.moveToNext()) {
                com.anchor.core.models.TopicNote(
                    id = cursor.getString(0),
                    topic = cursor.getString(1),
                    content = cursor.getString(2),
                    bulletsJson = cursor.getString(3),
                    pinned = cursor.getInt(4) == 1,
                    lastEditedAt = cursor.getLong(5),
                    createdAt = cursor.getLong(6)
                )
            } else null
        }
    }

    override suspend fun getAllTopicNotes(): List<com.anchor.core.models.TopicNote> = withIoDb {
        val list = mutableListOf<com.anchor.core.models.TopicNote>()
        dbHelper.readableDatabase.query(
            "SELECT id, topic, content, bullets_json, pinned, last_edited_at, created_at FROM topic_note ORDER BY pinned DESC, last_edited_at DESC",
            emptyArray()
        ).use { cursor ->
            while (cursor.moveToNext()) {
                list.add(
                    com.anchor.core.models.TopicNote(
                        id = cursor.getString(0),
                        topic = cursor.getString(1),
                        content = cursor.getString(2),
                        bulletsJson = cursor.getString(3),
                        pinned = cursor.getInt(4) == 1,
                        lastEditedAt = cursor.getLong(5),
                        createdAt = cursor.getLong(6)
                    )
                )
            }
        }
        list
    }

    override suspend fun searchTopicNotes(query: String, limit: Int): List<com.anchor.core.models.TopicNote> = withIoDb {
        val list = mutableListOf<com.anchor.core.models.TopicNote>()
        val cleanQ = query.trim()
        if (cleanQ.isEmpty()) return@withIoDb getAllTopicNotes().take(limit)

        // Match using FTS table with prefix wildcards
        val sanitized = cleanQ.replace(Regex("[^\\p{L}\\p{M}\\p{N}\\s]"), "").trim()
        val tokens = sanitized.split(Regex("\\s+")).filter { it.isNotBlank() }
        val ftsMatch = if (tokens.isNotEmpty()) tokens.joinToString(" AND ") { "\"$it\"*" } else ""
        val sql = if (ftsMatch.isNotBlank()) {
            "SELECT t.id, t.topic, t.content, t.bullets_json, t.pinned, t.last_edited_at, t.created_at " +
            "FROM topic_note t JOIN topic_note_fts f ON t.rowid = f.rowid " +
            "WHERE topic_note_fts MATCH ? " +
            "ORDER BY t.pinned DESC, rank LIMIT ?"
        } else {
            "SELECT id, topic, content, bullets_json, pinned, last_edited_at, created_at FROM topic_note WHERE topic LIKE ? OR content LIKE ? ORDER BY pinned DESC, last_edited_at DESC LIMIT ?"
        }
        val args = if (ftsMatch.isNotBlank()) arrayOf(ftsMatch, limit.toString()) else arrayOf("%$cleanQ%", "%$cleanQ%", limit.toString())

        try {
            dbHelper.readableDatabase.query(sql, args).use { cursor ->
                while (cursor.moveToNext()) {
                    list.add(
                        com.anchor.core.models.TopicNote(
                            id = cursor.getString(0),
                            topic = cursor.getString(1),
                            content = cursor.getString(2),
                            bulletsJson = cursor.getString(3),
                            pinned = cursor.getInt(4) == 1,
                            lastEditedAt = cursor.getLong(5),
                            createdAt = cursor.getLong(6)
                        )
                    )
                }
            }
        } catch (e: Exception) {
            // Fallback to LIKE query
            dbHelper.readableDatabase.query(
                "SELECT id, topic, content, bullets_json, pinned, last_edited_at, created_at FROM topic_note WHERE topic LIKE ? OR content LIKE ? ORDER BY pinned DESC, last_edited_at DESC LIMIT ?",
                arrayOf("%$cleanQ%", "%$cleanQ%", limit.toString())
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    list.add(
                        com.anchor.core.models.TopicNote(
                            id = cursor.getString(0),
                            topic = cursor.getString(1),
                            content = cursor.getString(2),
                            bulletsJson = cursor.getString(3),
                            pinned = cursor.getInt(4) == 1,
                            lastEditedAt = cursor.getLong(5),
                            createdAt = cursor.getLong(6)
                        )
                    )
                }
            }
        }
        list
    }

    override suspend fun deleteTopicNote(id: String) = withIoDb {
        val db = dbHelper.writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM note_reference WHERE note_id = ?", arrayOf(id))
            db.execSQL("DELETE FROM topic_note WHERE id = ?", arrayOf(id))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    // --- Note References ---
    override suspend fun linkNoteReference(ref: com.anchor.core.models.NoteReference) = withIoDb {
        dbHelper.writableDatabase.execSQL(
            "INSERT OR REPLACE INTO note_reference (id, note_id, node_id, created_at, mode) VALUES (?, ?, ?, ?, ?)",
            arrayOf<Any?>(ref.id, ref.noteId, ref.nodeId, ref.createdAt, ref.mode)
        )
    }

    override suspend fun getReferencesForNote(noteId: String): List<com.anchor.core.models.NoteReference> = withIoDb {
        val list = mutableListOf<com.anchor.core.models.NoteReference>()
        dbHelper.readableDatabase.query(
            "SELECT id, note_id, node_id, created_at, mode FROM note_reference WHERE note_id = ? ORDER BY created_at DESC",
            arrayOf(noteId)
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val mode = if (cursor.columnCount > 4 && !cursor.isNull(4)) cursor.getString(4) else "read_write"
                list.add(
                    com.anchor.core.models.NoteReference(
                        id = cursor.getString(0),
                        noteId = cursor.getString(1),
                        nodeId = cursor.getString(2),
                        createdAt = cursor.getLong(3),
                        mode = mode
                    )
                )
            }
        }
        list
    }

    override suspend fun getReferencesForNode(nodeId: String): List<com.anchor.core.models.NoteReference> = withIoDb {
        val list = mutableListOf<com.anchor.core.models.NoteReference>()
        dbHelper.readableDatabase.query(
            "SELECT id, note_id, node_id, created_at, mode FROM note_reference WHERE node_id = ? ORDER BY created_at DESC",
            arrayOf(nodeId)
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val mode = if (cursor.columnCount > 4 && !cursor.isNull(4)) cursor.getString(4) else "read_write"
                list.add(
                    com.anchor.core.models.NoteReference(
                        id = cursor.getString(0),
                        noteId = cursor.getString(1),
                        nodeId = cursor.getString(2),
                        createdAt = cursor.getLong(3),
                        mode = mode
                    )
                )
            }
        }
        list
    }

    override suspend fun deleteNoteReference(id: String) = withIoDb {
        dbHelper.writableDatabase.execSQL("DELETE FROM note_reference WHERE id = ?", arrayOf(id))
    }

    // --- Agent Wakeup Self-Alarms ---
    override suspend fun saveAgentWakeup(wakeup: com.anchor.core.models.AgentWakeup) = withIoDb {
        dbHelper.writableDatabase.execSQL(
            "INSERT OR REPLACE INTO agent_wakeup (id, fire_at, reason, payload_json, state, created_at) VALUES (?, ?, ?, ?, ?, ?)",
            arrayOf<Any?>(wakeup.id, wakeup.fireAt, wakeup.reason, wakeup.payloadJson, wakeup.state, wakeup.createdAt)
        )
    }

    override suspend fun getPendingAgentWakeups(upToMillis: Long): List<com.anchor.core.models.AgentWakeup> = withIoDb {
        val list = mutableListOf<com.anchor.core.models.AgentWakeup>()
        dbHelper.readableDatabase.query(
            "SELECT id, fire_at, reason, payload_json, state, created_at FROM agent_wakeup WHERE state = 'pending' AND fire_at <= ? ORDER BY fire_at ASC",
            arrayOf(upToMillis.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                list.add(
                    com.anchor.core.models.AgentWakeup(
                        id = cursor.getString(0),
                        fireAt = cursor.getLong(1),
                        reason = cursor.getString(2),
                        payloadJson = if (cursor.isNull(3)) null else cursor.getString(3),
                        state = cursor.getString(4),
                        createdAt = cursor.getLong(5)
                    )
                )
            }
        }
        list
    }

    override suspend fun markAgentWakeupState(id: String, state: String) = withIoDb {
        dbHelper.writableDatabase.execSQL(
            "UPDATE agent_wakeup SET state = ? WHERE id = ?",
            arrayOf(state, id)
        )
    }

    override suspend fun getAgentWakeup(id: String): com.anchor.core.models.AgentWakeup? = withIoDb {
        dbHelper.readableDatabase.query(
            "SELECT id, fire_at, reason, payload_json, state, created_at FROM agent_wakeup WHERE id = ?",
            arrayOf(id)
        ).use { cursor ->
            if (cursor.moveToNext()) {
                com.anchor.core.models.AgentWakeup(
                    id = cursor.getString(0),
                    fireAt = cursor.getLong(1),
                    reason = cursor.getString(2),
                    payloadJson = if (cursor.isNull(3)) null else cursor.getString(3),
                    state = cursor.getString(4),
                    createdAt = cursor.getLong(5)
                )
            } else null
        }
    }

    override suspend fun unstickRunningWakeups(olderThanMillis: Long): Int = withIoDb {
        val db = dbHelper.writableDatabase
        db.execSQL(
            "UPDATE agent_wakeup SET state = 'pending' WHERE state = 'running' AND fire_at <= ?",
            arrayOf(olderThanMillis.toString())
        )
        0
    }
}
