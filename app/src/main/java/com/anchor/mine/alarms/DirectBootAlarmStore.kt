// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.mine.alarms

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Build
import androidx.core.content.ContextCompat
import com.anchor.core.alarms.DirectBootReconciler
import com.anchor.core.models.AlarmTier
import com.anchor.core.models.Reminder

/**
 * Data holder for Direct Boot safety-net alarm entries.
 * IMPORTANT: Invariant 1 (Privacy) - Stored in Device Protected Storage.
 * Contains ZERO private user text (no notes, node bodies, or personal titles).
 */
data class DirectBootAlarm(
    val id: String,
    val fireAtMillis: Long,
    val label: String = "Reminder",
    val tier: AlarmTier = AlarmTier.ALARM
)

/**
 * Manages the Device-Protected (DE) SQLite database for alarms.
 * Accessible immediately after device boot before first user unlock.
 */
class DirectBootAlarmStore(context: Context) {
    private val deContext: Context = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        ContextCompat.createDeviceProtectedStorageContext(context) ?: context
    } else {
        context
    }

    private val dbHelper: DirectBootOpenHelper
        get() = getHelper(deContext)

    companion object {
        @Volatile
        private var instance: DirectBootOpenHelper? = null

        private fun getHelper(context: Context): DirectBootOpenHelper {
            return instance ?: synchronized(this) {
                instance ?: DirectBootOpenHelper(context).also { instance = it }
            }
        }
    }

    fun saveAlarm(id: String, fireAtMillis: Long, tier: AlarmTier, label: String = "Reminder") {
        try {
            val db = dbHelper.writableDatabase
            val values = ContentValues().apply {
                put("id", id)
                put("fire_at", fireAtMillis)
                put("label", label)
                put("tier", tier.name)
            }
            db.insertWithOnConflict("direct_boot_alarm", null, values, SQLiteDatabase.CONFLICT_REPLACE)
        } catch (e: Exception) {
            android.util.Log.w("DirectBootAlarmStore", "saveAlarm failed: ${e.message}")
        }
    }

    fun removeAlarm(id: String) {
        try {
            val db = dbHelper.writableDatabase
            db.delete("direct_boot_alarm", "id = ?", arrayOf(id))
        } catch (e: Exception) {
            android.util.Log.w("DirectBootAlarmStore", "removeAlarm failed: ${e.message}")
        }
    }

    fun clearAll() {
        try {
            val db = dbHelper.writableDatabase
            db.delete("direct_boot_alarm", null, null)
            db.delete("direct_boot_fired", null, null)
        } catch (e: Exception) {
            android.util.Log.w("DirectBootAlarmStore", "clearAll failed: ${e.message}")
        }
    }

    fun getUpcomingAlarms(now: Long, limit: Int = 100): List<DirectBootAlarm> {
        val list = mutableListOf<DirectBootAlarm>()
        try {
            val db = dbHelper.readableDatabase
            db.query(
                "direct_boot_alarm",
                arrayOf("id", "fire_at", "label", "tier"),
                "fire_at >= ?",
                arrayOf((now - 60_000L).toString()),
                null,
                null,
                "fire_at ASC",
                limit.toString()
            ).use { cursor ->
                val idIdx = cursor.getColumnIndexOrThrow("id")
                val fireIdx = cursor.getColumnIndexOrThrow("fire_at")
                val labelIdx = cursor.getColumnIndexOrThrow("label")
                val tierIdx = cursor.getColumnIndexOrThrow("tier")
                while (cursor.moveToNext()) {
                    val tierStr = cursor.getString(tierIdx)
                    val tier = if (tierStr.equals("GENTLE", ignoreCase = true)) AlarmTier.GENTLE else AlarmTier.ALARM
                    list.add(
                        DirectBootAlarm(
                            id = cursor.getString(idIdx),
                            fireAtMillis = cursor.getLong(fireIdx),
                            label = cursor.getString(labelIdx),
                            tier = tier
                        )
                    )
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("DirectBootAlarmStore", "getUpcomingAlarms failed: ${e.message}")
        }
        return list
    }

    fun getAlarmById(id: String): DirectBootAlarm? {
        try {
            val db = dbHelper.readableDatabase
            db.query(
                "direct_boot_alarm",
                arrayOf("id", "fire_at", "label", "tier"),
                "id = ?",
                arrayOf(id),
                null,
                null,
                null
            ).use { cursor ->
                if (cursor.moveToFirst()) {
                    val tierStr = cursor.getString(cursor.getColumnIndexOrThrow("tier"))
                    val tier = if (tierStr.equals("GENTLE", ignoreCase = true)) AlarmTier.GENTLE else AlarmTier.ALARM
                    return DirectBootAlarm(
                        id = cursor.getString(cursor.getColumnIndexOrThrow("id")),
                        fireAtMillis = cursor.getLong(cursor.getColumnIndexOrThrow("fire_at")),
                        label = cursor.getString(cursor.getColumnIndexOrThrow("label")),
                        tier = tier
                    )
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("DirectBootAlarmStore", "getAlarmById failed: ${e.message}")
        }
        return null
    }

    fun recordDirectBootFire(id: String, firedAt: Long, scheduledFor: Long) {
        try {
            val db = dbHelper.writableDatabase
            val values = ContentValues().apply {
                put("id", id)
                put("fired_at", firedAt)
                put("scheduled_for", scheduledFor)
            }
            db.insertWithOnConflict("direct_boot_fired", null, values, SQLiteDatabase.CONFLICT_REPLACE)
        } catch (e: Exception) {
            android.util.Log.w("DirectBootAlarmStore", "recordDirectBootFire failed: ${e.message}")
        }
    }

    fun getFiredAlarms(): List<DirectBootReconciler.FiredRecord> {
        val list = mutableListOf<DirectBootReconciler.FiredRecord>()
        try {
            val db = dbHelper.readableDatabase
            db.query(
                "direct_boot_fired",
                arrayOf("id", "fired_at", "scheduled_for"),
                null,
                null,
                null,
                null,
                "fired_at ASC"
            ).use { cursor ->
                val idIdx = cursor.getColumnIndexOrThrow("id")
                val firedIdx = cursor.getColumnIndexOrThrow("fired_at")
                val schedIdx = cursor.getColumnIndexOrThrow("scheduled_for")
                while (cursor.moveToNext()) {
                    list.add(
                        DirectBootReconciler.FiredRecord(
                            id = cursor.getString(idIdx),
                            firedAt = cursor.getLong(firedIdx),
                            scheduledFor = cursor.getLong(schedIdx)
                        )
                    )
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("DirectBootAlarmStore", "getFiredAlarms failed: ${e.message}")
        }
        return list
    }

    fun clearFiredAlarms() {
        try {
            val db = dbHelper.writableDatabase
            db.delete("direct_boot_fired", null, null)
        } catch (e: Exception) {
            android.util.Log.w("DirectBootAlarmStore", "clearFiredAlarms failed: ${e.message}")
        }
    }

    fun syncFromReminders(reminders: List<Reminder>) {
        try {
            val db = dbHelper.writableDatabase
            db.beginTransaction()
            try {
                db.delete("direct_boot_alarm", null, null)
                for (rem in reminders) {
                    val values = ContentValues().apply {
                        put("id", rem.id)
                        put("fire_at", rem.fireAtMillis)
                        put("label", "Reminder")
                        put("tier", rem.tier.name)
                    }
                    db.insert("direct_boot_alarm", null, values)
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        } catch (e: Exception) {
            android.util.Log.w("DirectBootAlarmStore", "syncFromReminders failed: ${e.message}")
        }
    }

    fun clear() {
        try {
            val db = dbHelper.writableDatabase
            db.delete("direct_boot_alarm", null, null)
            db.delete("direct_boot_fired", null, null)
        } catch (e: Exception) {
            android.util.Log.w("DirectBootAlarmStore", "clear failed: ${e.message}")
        }
    }

    private class DirectBootOpenHelper(context: Context) :
        SQLiteOpenHelper(context, "direct_boot_alarms.db", null, 2) {

        override fun onConfigure(db: SQLiteDatabase) {
            super.onConfigure(db)
            db.enableWriteAheadLogging()
        }

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS direct_boot_alarm (
                    id TEXT PRIMARY KEY,
                    fire_at INTEGER NOT NULL,
                    label TEXT NOT NULL,
                    tier TEXT NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_direct_boot_fire_at ON direct_boot_alarm(fire_at)")
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS direct_boot_fired (
                    id TEXT PRIMARY KEY,
                    fired_at INTEGER NOT NULL,
                    scheduled_for INTEGER NOT NULL
                )
                """.trimIndent()
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS direct_boot_fired (
                        id TEXT PRIMARY KEY,
                        fired_at INTEGER NOT NULL,
                        scheduled_for INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }
    }
}
