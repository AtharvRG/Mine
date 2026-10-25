// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine Author
// Part of Mine. See LICENSE in the repository root.

package com.anchor.mine.storage

import androidx.sqlite.db.SupportSQLiteOpenHelper
import com.anchor.core.alarms.Reminder
import com.anchor.core.models.*
import com.anchor.core.storage.Storage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AndroidStorage(private val dbHelper: SupportSQLiteOpenHelper) : Storage {
    override val ioDispatcher: CoroutineDispatcher = Dispatchers.IO

    init {
        // Blueprint 30: Ensure our operational reminder table exists
        dbHelper.writableDatabase.execSQL("""
            CREATE TABLE IF NOT EXISTS reminder (
                id TEXT PRIMARY KEY, text TEXT NOT NULL, fire_at INTEGER NOT NULL
            )
        """)
    }

    override suspend fun insertRawMessage(id: String, content: String) = withContext(ioDispatcher) {
        dbHelper.writableDatabase.execSQL(
            "INSERT INTO raw_message (id, role, content, ts_utc) VALUES (?, 'user', ?, ?)",
            arrayOf<Any>(id, content, System.currentTimeMillis()) // <-- Added <Any>
        )
    }

    override suspend fun getRawMessage(id: String): String? = withContext(ioDispatcher) {
        dbHelper.readableDatabase.query("SELECT content FROM raw_message WHERE id = ?", arrayOf(id)).use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }

    override suspend fun getRecentMessages(limit: Int): List<ChatMessage> = withContext(ioDispatcher) {
        val list = mutableListOf<ChatMessage>()
        dbHelper.readableDatabase.query("SELECT id, role, content FROM raw_message ORDER BY ts_utc ASC LIMIT ?", arrayOf(limit)).use {
            while (it.moveToNext()) {
                list.add(ChatMessage(it.getString(0), it.getString(1), it.getString(2)))
            }
        }
        list
    }

    override suspend fun getActiveNodes(): List<Node> = emptyList() // Stubbed for Phase B
    override suspend fun updateNodeStatus(nodeId: String, newStatus: String) {}
    override suspend fun logEvent(event: RawEvent) {}
    override suspend fun saveProposal(proposal: Proposal) {}

    override suspend fun saveReminder(reminder: Reminder) = withContext(ioDispatcher) {
        dbHelper.writableDatabase.execSQL(
            "INSERT OR REPLACE INTO reminder (id, text, fire_at) VALUES (?, ?, ?)",
            arrayOf<Any>(reminder.id, reminder.text, reminder.fireAtMillis) // <-- Added <Any>
        )
    }

    override suspend fun getActiveReminders(): List<Reminder> = withContext(ioDispatcher) {
        val list = mutableListOf<Reminder>()
        dbHelper.readableDatabase.query("SELECT id, text, fire_at FROM reminder WHERE fire_at > ?", arrayOf(System.currentTimeMillis())).use {
            while (it.moveToNext()) {
                list.add(Reminder(it.getString(0), it.getString(1), it.getLong(2)))
            }
        }
        list
    }

    override suspend fun deleteReminder(id: String) = withContext(ioDispatcher) {
        dbHelper.writableDatabase.execSQL("DELETE FROM reminder WHERE id = ?", arrayOf(id))
    }
}