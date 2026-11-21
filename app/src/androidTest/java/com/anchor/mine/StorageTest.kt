// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.mine.storage

import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StorageTest {

    private lateinit var dbHelper: SupportSQLiteOpenHelper

    @Before
    fun setup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase("mine_storage_test.db") // Start fresh

        val keyProvider = DbKeyProvider(context)
        val rawKey = keyProvider.getOrGenerateKey()

        dbHelper = DatabaseProvider.getHelper(context, rawKey, "mine_storage_test.db")
    }

    @After
    fun teardown() {
        dbHelper.close()
    }

    @Test
    fun testKeystoreAndSqlCipherWriteRead() {
        val db = dbHelper.writableDatabase

        // Write Golden Fixture
        db.execSQL("INSERT INTO app_meta (\"key\", value) VALUES ('schema_version', '1')")
        db.execSQL("INSERT INTO raw_message (id, role, content) VALUES ('msg_1', 'user', 'Hello Mine')")

        // Read it back
        val cursor = db.query("SELECT content FROM raw_message WHERE id = 'msg_1'")
        assertTrue(cursor.moveToFirst())
        assertEquals("Hello Mine", cursor.getString(0))
        cursor.close()
    }

    @Test
    fun testRawMessageIsAppendOnlyThrowsOnUpdate() {
        val db = dbHelper.writableDatabase
        db.execSQL("INSERT INTO raw_message (id, role, content) VALUES ('msg_2', 'user', 'Cannot change')")

        // Use generic Exception to catch any Zetetic wrapper of the SQLite trigger abort
        val exception = assertThrows(Exception::class.java) {
            db.execSQL("UPDATE raw_message SET content = 'Changed' WHERE id = 'msg_2'")
        }

        assertTrue("Exception was: ${exception.message}", exception.message?.contains("raw_message is append-only") == true)
    }
}
